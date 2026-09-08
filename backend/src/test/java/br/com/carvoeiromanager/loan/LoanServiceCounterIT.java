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
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanServiceCounterIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;
    @Autowired LoanOfferRepository loanOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loancounter_" + UUID.randomUUID(), "hash");
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

    @Test
    void acceptingTheCounterExecutesTheLoanOnTheAisTerms() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        LoanOfferResponse accepted = loanService.acceptCounter(
                c.id(), c.userId(), countered.offerId());

        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        // The COUNTER's terms are what execute, not the ones originally offered.
        assertThat(reloaded.getLoanWageShare()).isEqualTo(countered.counterOwnerWageShare());
        assertThat(reloaded.getLoanMayFaceOwner()).isEqualTo(countered.counterMayFaceOwner());
    }

    @Test
    void thereIsNothingToAcceptOnAnOfferThatWasNotCountered() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(Math.max(1, spare.getOverall() - 15));
        clubRepository.save(borrower);

        LoanOfferResponse accepted = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);
        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), accepted.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSFER));
    }

    /**
     * {@code acceptCounter} re-runs {@code requireDepartureAllowed} against the offer's owner club
     * right before executing — not just at offer time — because the squad can shrink in between.
     * Here the managed club sits exactly on the floor by the time the AI's counter is accepted, and
     * accepting must refuse rather than push it below.
     */
    @Test
    void acceptingACounterAfterTheSquadDroppedToTheFloorFailsAndMovesNobody() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        // The squad drops to the floor after the AI answered but before the manager accepts.
        List<Player> others = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> !p.getId().equals(spare.getId()))
                .toList();
        List<Player> drop = others.subList(SquadRules.MIN_SQUAD_SIZE - 1, others.size());
        playerRepository.deleteAll(drop);
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()))
                .hasSize(SquadRules.MIN_SQUAD_SIZE);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }

    /**
     * The last-goalkeeper half of the same re-check, and the exact scenario the review flagged:
     * offer the backup keeper, let the AI counter, lose the first-choice keeper in between (a
     * termination, a sale — anything), then accept. Without the re-check the club would be left
     * with zero goalkeepers; with it, accepting is refused instead.
     */
    @Test
    void acceptingACounterAfterTheOnlyOtherGoalkeeperLeftFailsAndMovesNobody() {
        Career c = createCareer();
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        Player backupGk = squad.stream().skip(11)
                .filter(p -> p.getPosition() == Position.GK)
                .filter(p -> squad.stream().anyMatch(o -> !o.getId().equals(p.getId())
                        && o.getPosition() == Position.GK
                        && o.getOverall() >= p.getOverall() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the seed has no spare goalkeeper with cover — the fixture assumption "
                                + "behind this test has changed"));
        Player coveringGk = squad.stream()
                .filter(p -> p.getPosition() == Position.GK && !p.getId().equals(backupGk.getId())
                        && p.getOverall() >= backupGk.getOverall() - 3)
                .findFirst().orElseThrow();

        // Trim every other goalkeeper away so the squad holds exactly these two.
        List<Player> extraGks = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK)
                .filter(p -> !p.getId().equals(backupGk.getId()) && !p.getId().equals(coveringGk.getId()))
                .toList();
        playerRepository.deleteAll(extraGks);

        backupGk.setContractYears(3);
        playerRepository.save(backupGk);

        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(backupGk.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), backupGk.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        // The covering keeper leaves the squad between the counter and the accept.
        playerRepository.delete(coveringGk);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        Player reloaded = playerRepository.findById(backupGk.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }

    /**
     * The AI answered against the club that held him at the time. If he leaves that club before the
     * manager accepts, accepting must not execute a loan that moves him out from under whoever
     * holds him now — {@code acceptCounter} re-checks the offer's owner against his current
     * {@code club_id}.
     *
     * <p><b>The move used here is an outright sale, and that choice is the whole point.</b> Sending
     * him out on a second loan instead would trip {@code acceptCounter}'s {@code isOnLoan} filter as
     * well as its stale-owner filter, and a test that trips two guards at once pins neither: delete
     * the ownership check and it would still pass. A sale leaves {@code isOnLoan()} false and moves
     * only {@code club_id} — exactly what {@code TransferExecutor.execute} writes to the row — so
     * the stale-owner check is the only thing standing between the accept and a player being lent
     * out from under his new club.
     */
    @Test
    void acceptingACounterAfterThePlayerHasBeenSoldFailsAndMovesNobody() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        // Sold to a third club: club_id moves, ownership does not exist (he is nobody's loanee).
        UUID buyerClubId = aThirdClubId(c, borrower.getId());
        Player sold = playerRepository.findById(spare.getId()).orElseThrow();
        sold.setClubId(buyerClubId);
        playerRepository.save(sold);
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan())
                .as("the sale must not make him a loanee — otherwise the isOnLoan filter, not the "
                        + "stale-owner filter, is what refuses below")
                .isFalse();

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(buyerClubId);
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getOwnerClubId()).isNull();
    }

    /**
     * {@code acceptCounter} re-checks the window the same way it re-checks the departure guard —
     * an offer that outlived the window must not execute just because it was made while the
     * window was still open. Mirrors {@code TransferServiceBuyingIT}'s own outside-window fixture
     * (May 1: past pre-season, before mid-season).
     */
    @Test
    void acceptingACounterAfterTheWindowClosedIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        // The window shuts after the AI answered but before the manager accepts.
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(c.save().seasonYear(), 5, 1));
        saveGameRepository.save(save);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.TRANSFER_WINDOW_CLOSED));

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }
}
