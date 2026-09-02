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
class LoanServiceCounterIT {

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
     * The AI answered against the club that held him at the time. If he was lent, sold or
     * released before the manager accepts, that club may no longer be his — accepting must not
     * execute a loan that moves him out from under whoever holds him now.
     */
    @Test
    void acceptingACounterAfterThePlayerHasMovedFailsAndMovesNobody() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        UUID thirdClubId = aThirdClubId(c, borrower.getId());
        Player moved = playerRepository.findById(spare.getId()).orElseThrow();
        moved.loanTo(thirdClubId, 50, false);
        playerRepository.save(moved);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(thirdClubId);
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
    }
}
