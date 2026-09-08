package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.match.Lineup;
import br.com.carvoeiromanager.match.LineupPlayer;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.TransferDirection;
import br.com.carvoeiromanager.transfer.TransferOffer;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanServiceOutIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;
    @Autowired LoanOfferRepository loanOfferRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanout_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /** A club distinct from the managed club and from {@code excludeClubId} — for a third-party bid. */
    private UUID aThirdClubId(Career c, UUID excludeClubId) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()) && !id.equals(excludeClubId))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * A player the managed club can spare, found rather than forced: outside the top 11 by
     * overall (so SellerContextFactory does not call him a key player) and sharing a position
     * with a squadmate within 3 overall (so his position has cover). Those are exactly the two
     * conditions SellerContextFactory checks, at KEY_PLAYER_RANK_THRESHOLD = 11 and
     * COVER_OVERALL_MARGIN = 3.
     */
    private Player aSparePlayer(Career c) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        Player spare = squad.stream().skip(11)
                .filter(p -> p.getPosition() != Position.GK)
                .filter(p -> squad.stream().anyMatch(o -> !o.getId().equals(p.getId())
                        && o.getPosition() == p.getPosition()
                        && o.getOverall() >= p.getOverall() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the seed has no spare outfielder with positional cover — the fixture "
                                + "assumption behind every test in this class has changed"));
        spare.setContractYears(3);
        playerRepository.save(spare);
        return playerRepository.findById(spare.getId()).orElseThrow();
    }

    /** Makes the borrower's level low enough that the spare player clears it comfortably. */
    private Club aWillingBorrower(Career c, Player forPlayer) {
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(Math.max(1, forPlayer.getOverall() - 15));
        clubRepository.save(borrower);
        return borrower;
    }

    @Test
    void aGenerousOfferForASparePlayerIsAcceptedAndMovesHim() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        assertThat(r.refusal()).isNull();

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getClubId()).isEqualTo(borrower.getId());
        assertThat(reloaded.getLoanWageShare()).isEqualTo(100);
        assertThat(reloaded.getLoanMayFaceOwner()).isTrue();
    }

    /**
     * A listed player who is then lent is not the owner's to sell while lent — the listing must
     * not outlive the loan, or {@code TransferService.unpagedMarket} would keep showing him under
     * the borrower's club name, with "Fazer proposta" always failing PLAYER_ALREADY_ON_LOAN.
     */
    @Test
    void lendingAListedPlayerClearsTheListing() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        spare.listForTransfer(ListedReason.OWNER);
        playerRepository.save(spare);
        Club borrower = aWillingBorrower(c, spare);

        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.isTransferListed()).isFalse();
        assertThat(reloaded.getListedReason()).isNull();
    }

    @Test
    void aKeyPlayerIsRefusedWithoutNegotiation() {
        Career c = createCareer();
        Player best = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        best.setContractYears(3);
        playerRepository.save(best);
        Club borrower = aWillingBorrower(c, best);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), best.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.KEY_PLAYER);
        assertThat(playerRepository.findById(best.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerBeneathTheBorrowersLevelIsRefusedWithoutNegotiation() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + 20);   // far above him
        clubRepository.save(borrower);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.BELOW_LEVEL);
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void meanTermsOnAPlausiblePlayerAreCounteredNotRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        // Reputation just above his level, so the borrower needs real subsidy.
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.TERMS_REJECTED);
        assertThat(r.counterOwnerWageShare()).isNotNull().isPositive();
        assertThat(r.counterMayFaceOwner()).isTrue();   // the borrower asks for the release
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerWithOneYearLeftCannotBeLoaned() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        spare.setContractYears(1);
        playerRepository.save(spare);
        Club borrower = aWillingBorrower(c, spare);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_CONTRACT_TOO_SHORT));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerAlreadyOutOnLoanCannotBeLoanedAgain() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);
        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void acceptingALoanClearsTheOwnersLineupAndKillsOpenBids() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        // A live bid on the spare player, still open when the loan is accepted.
        UUID bidderClubId = aThirdClubId(c, borrower.getId());
        TransferOffer bid = TransferOffer.create(c.id(), spare.getId(), bidderClubId, c.clubId(),
                TransferDirection.INCOMING, BigDecimal.valueOf(1000), 0, null, null);
        transferOfferRepository.save(bid);

        // The spare player in the managed club's lineup, and set as its penalty taker.
        Lineup lineup = Lineup.create(c.id(), c.clubId(), "4-4-2");
        lineup.setPenaltyTakerId(spare.getId());
        lineupRepository.save(lineup);
        lineupPlayerRepository.save(LineupPlayer.create(lineup.getId(), spare.getId(), true));

        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.ACCEPTED))).hasSize(1);

        // The same obligations selling and terminating carry — a live bid must not outlive the
        // loan, because the sell guard would then pass trivially on a player who has left.
        assertThat(transferOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED))).isEmpty();

        assertThat(lineupPlayerRepository.findByLineupId(lineup.getId()))
                .extracting(LineupPlayer::getPlayerId)
                .doesNotContain(spare.getId());
        assertThat(lineupRepository.findById(lineup.getId()).orElseThrow().getPenaltyTakerId()).isNull();
    }

    /** Pads {@code clubId} up to the professional squad ceiling with throwaway players. */
    private void fillSquadToTheCap(Career c, UUID clubId) {
        int room = SquadRules.MAX_SQUAD_SIZE
                - playerRepository.findByClubIdAndYouthProspectFalse(clubId).size();
        List<Player> padding = new java.util.ArrayList<>();
        for (int i = 0; i < room; i++) {
            padding.add(Player.create(c.id(), clubId, "Pad-" + UUID.randomUUID(), Position.MID,
                    25, "Brasil", 60, 60, 60, 60, 60, 60, 60));
        }
        playerRepository.saveAll(padding);
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(clubId))
                .hasSize(SquadRules.MAX_SQUAD_SIZE);
    }

    /**
     * A loan needs two clubs. The borrower filter excludes the managed club itself, so "lend him to
     * us" answers CLUB_NOT_FOUND rather than executing a loan from a club to itself — worth pinning
     * because the filter is the ONLY thing standing between that request and a player whose owner
     * and borrower are the same row, which no later code path is written to survive.
     */
    @Test
    void namingYourOwnClubAsTheBorrowerIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), c.clubId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.CLUB_NOT_FOUND));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aBorrowerClubThatDoesNotExistIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), UUID.randomUUID(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.CLUB_NOT_FOUND));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    /**
     * Taking a player in fills a place, so a borrower already at the ceiling is refused exactly as
     * a buyer would be — checked before the AI is ever asked, so a full club never gets as far as
     * countering an offer it could not honour.
     */
    @Test
    void aBorrowerAlreadyAtTheSquadCapIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);
        fillSquadToTheCap(c, borrower.getId());

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_FULL));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED, LoanOfferStatus.ACCEPTED,
                        LoanOfferStatus.REJECTED))).isEmpty();
    }

    /**
     * {@code offerLoanOut} calls {@code squadDeparture.requireDepartureAllowed} before any
     * negotiation starts — this pins the squad-floor half of that guard. Trimmed to exactly the
     * floor so the departure itself (not some other check) is what refuses it.
     */
    @Test
    void lendingAPlayerWhenTheSquadIsAtTheFloorIsRefused() {
        Career c = createCareer();
        List<Player> seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        List<Player> drop = seniors.subList(SquadRules.MIN_SQUAD_SIZE, seniors.size());
        playerRepository.deleteAll(drop);
        List<Player> kept = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(kept).hasSize(SquadRules.MIN_SQUAD_SIZE);

        Player target = kept.stream().filter(p -> p.getPosition() != Position.GK).findFirst().orElseThrow();
        target.setContractYears(3);
        playerRepository.save(target);
        Club borrower = aWillingBorrower(c, target);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), target.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }

    /**
     * The other half of {@code requireDepartureAllowed}: the last-goalkeeper rule. Pinned the same
     * way {@code TransferServiceSellingIT.cannotSellTheLastGoalkeeper} pins it for a sale — reduce
     * senior goalkeepers to exactly one, then try to lend that one out.
     */
    @Test
    void lendingTheLastGoalkeeperIsRefused() {
        Career c = createCareer();
        List<Player> gks = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).toList();
        if (gks.size() > 1) {
            playerRepository.deleteAll(gks.subList(1, gks.size()));
        }
        Player lastGk = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        lastGk.setContractYears(3);
        playerRepository.save(lastGk);
        Club borrower = aWillingBorrower(c, lastGk);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), lastGk.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        Player reloaded = playerRepository.findById(lastGk.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aWageShareAboveOneHundredIsRejectedBeforeAnythingIsMutated() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 101, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_INVALID_TERMS));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED, LoanOfferStatus.ACCEPTED,
                        LoanOfferStatus.REJECTED))).isEmpty();
    }

    @Test
    void aNegativeWageShareIsRejectedBeforeAnythingIsMutated() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), -1, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_INVALID_TERMS));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED, LoanOfferStatus.ACCEPTED,
                        LoanOfferStatus.REJECTED))).isEmpty();
    }

    /**
     * A loan is a squad movement like any transfer: {@code offerLoanOut} must refuse it the same
     * way {@code TransferService.makeOffer} refuses a buy outside the window (mirrored here with
     * {@code TransferServiceBuyingIT}'s own May 1 outside-window fixture — past pre-season, before
     * mid-season). Without this, lending out was the one squad departure the backend never gated
     * on the window at all.
     */
    @Test
    void offeringALoanOutOutsideTheWindowIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(c.save().seasonYear(), 5, 1));
        saveGameRepository.save(save);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.TRANSFER_WINDOW_CLOSED));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED, LoanOfferStatus.ACCEPTED,
                        LoanOfferStatus.REJECTED))).isEmpty();
    }
}
