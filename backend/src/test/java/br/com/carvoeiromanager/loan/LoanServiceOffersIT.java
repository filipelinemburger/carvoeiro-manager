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

/**
 * The GET listing and withdraw endpoint added so a COUNTERED loan offer survives its dialog
 * closing (Task 6) — {@code LoanServiceCounterIT} already covers {@code acceptCounter} itself, so
 * this class is only about the offer being independently reachable afterwards: retrieved via
 * {@link LoanService#getOffers}, and either accepted or withdrawn from there.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanServiceOffersIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanoffers_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /** Mirrors LoanServiceCounterIT's own helper: a player outside the top 11 by overall, with
     * positional cover, so a lend-out offer is guaranteed to reach the negotiator rather than
     * being refused by plausibility before a counter is even possible. */
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

    /** A borrower reputable enough to want the player but not so much it accepts the opening
     * terms outright — {@code LoanNegotiator} is RNG-free, so this reliably produces a counter. */
    private Club aBorrowerThatWillCounter(Career c, Player forPlayer) {
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(forPlayer.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);
        return borrower;
    }

    @Test
    void aCounteredOfferIsRetrievableAfterTheDialogClosesAndCanStillBeAccepted() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aBorrowerThatWillCounter(c, spare);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        // "The dialog closes": nothing but the save and offer id survives — refetch it exactly as
        // a fresh page load would, rather than reusing the response `offerLoanOut` returned above.
        List<LoanOfferResponse> reopened = loanService.getOffers(c.id(), c.userId());
        assertThat(reopened).hasSize(1);
        LoanOfferResponse listed = reopened.get(0);
        assertThat(listed.offerId()).isEqualTo(countered.offerId());
        assertThat(listed.status()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(listed.playerId()).isEqualTo(spare.getId());
        assertThat(listed.counterOwnerWageShare()).isEqualTo(countered.counterOwnerWageShare());
        assertThat(listed.counterMayFaceOwner()).isEqualTo(countered.counterMayFaceOwner());
        // TERMS_REJECTED is the only refusal a COUNTERED offer can ever carry.
        assertThat(listed.refusal()).isEqualTo(LoanRefusal.TERMS_REJECTED);

        LoanOfferResponse accepted = loanService.acceptCounter(c.id(), c.userId(), listed.offerId());

        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isTrue();
        // Accepted (or rejected) offers are no longer "open" — the listing empties out.
        assertThat(loanService.getOffers(c.id(), c.userId())).isEmpty();
    }

    @Test
    void aWithdrawnCounteredOfferCannotBeAccepted() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aBorrowerThatWillCounter(c, spare);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        LoanOfferResponse withdrawn = loanService.withdrawOffer(c.id(), c.userId(), countered.offerId());
        assertThat(withdrawn.status()).isEqualTo(LoanOfferStatus.WITHDRAWN);

        // Withdrawn is not "open" either — it drops out of the listing the same way ACCEPTED does.
        assertThat(loanService.getOffers(c.id(), c.userId())).isEmpty();

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), countered.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSFER));

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isFalse();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void withdrawingAnOfferThatIsNotOpenIsRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        // A reputation far below the player's overall gets a plain ACCEPT, never a counter — see
        // LoanNegotiator/LoanPlausibility.
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(Math.max(1, spare.getOverall() - 15));
        clubRepository.save(borrower);

        LoanOfferResponse accepted = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);
        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);

        assertThatThrownBy(() -> loanService.withdrawOffer(c.id(), c.userId(), accepted.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSFER));
    }

    @Test
    void withdrawingAnUnknownOfferIsRefused() {
        Career c = createCareer();

        assertThatThrownBy(() -> loanService.withdrawOffer(c.id(), c.userId(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.TRANSFER_OFFER_NOT_FOUND));
    }
}
