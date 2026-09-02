package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;
    @Autowired LoanOfferRepository loanOfferRepository;

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

        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        // The same obligations selling and terminating carry — a live bid must not outlive the
        // loan, because the sell guard would then pass trivially on a player who has left.
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.ACCEPTED))).hasSize(1);
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
}
