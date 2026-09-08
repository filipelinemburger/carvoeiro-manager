package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanOfferEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired LoanOfferRepository loanOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanoffer_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private LoanOffer aPendingOffer(Career c) {
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        return LoanOffer.create(c.id(), p.getId(), c.clubId(), borrower, 40, false, 1);
    }

    @Test
    void anOfferIsBornPendingAndRoundTripsItsTerms() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        loanOfferRepository.save(offer);

        LoanOffer reloaded = loanOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LoanOfferStatus.PENDING);
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getOwnerWageShare()).isEqualTo(40);
        assertThat(reloaded.isMayFaceOwner()).isFalse();
        assertThat(reloaded.getCounterOwnerWageShare()).isNull();
        assertThat(reloaded.getCounterMayFaceOwner()).isNull();
    }

    @Test
    void counteringStoresBothCounterTermsAndFlipsTheStatus() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        offer.markCountered(65, true);
        loanOfferRepository.save(offer);

        LoanOffer reloaded = loanOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(reloaded.getCounterOwnerWageShare()).isEqualTo(65);
        assertThat(reloaded.getCounterMayFaceOwner()).isTrue();
    }

    @Test
    void openOffersForASaveAreFoundByStatus() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        loanOfferRepository.save(offer);

        List<LoanOffer> open = loanOfferRepository.findBySaveGameIdAndStatusIn(
                c.id(), List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED));

        assertThat(open).extracting(LoanOffer::getId).containsExactly(offer.getId());
    }
}
