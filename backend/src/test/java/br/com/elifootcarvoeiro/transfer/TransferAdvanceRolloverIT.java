package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.season.AdvanceResponse;
import br.com.elifootcarvoeiro.season.SeasonAdvanceService;
import br.com.elifootcarvoeiro.season.SeasonRolloverService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferAdvanceRolloverIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService seasonRolloverService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("txadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private static final List<TransferOfferStatus> ALL = List.of(
            TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED, TransferOfferStatus.ACCEPTED,
            TransferOfferStatus.REJECTED, TransferOfferStatus.WITHDRAWN);

    @Test
    void advancingInsideAWindowProducesOnlyValidIncomingOffers() {
        // Given a fresh career, the early calendar lands inside the pre-season transfer window,
        // so incoming bids are generated on the TRANSFER events the advance pops.
        Career c = createCareer();

        // When advancing through the first several events (which include pre-season TRANSFER stops)
        for (int i = 0; i < 6; i++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            // Stop once the season would finish (defensive; never reached this early).
            if (r.seasonFinished()) {
                break;
            }
        }

        // Then any generated offers are INCOMING, PENDING, for the managed club, and bounded
        List<TransferOffer> offers = transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL);
        assertThat(offers).allMatch(o -> o.getDirection() == TransferDirection.INCOMING);
        assertThat(offers).allMatch(o -> o.getStatus() == TransferOfferStatus.PENDING);
        assertThat(offers).allMatch(o -> o.getToClubId().equals(c.clubId()));
        // Up to 2 bids per processed TRANSFER event; a handful of windows at most this early.
        assertThat(offers.size()).isLessThanOrEqualTo(8);
    }

    @Test
    void rolloverPurgesTransferOffers() {
        // Given a career with at least one transfer offer
        Career c = createCareer();
        Player managedPlayer = playerRepository.findByClubId(c.clubId()).get(0);
        UUID aiClub = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        transferOfferRepository.save(TransferOffer.create(c.id(), managedPlayer.getId(), aiClub,
                c.clubId(), TransferDirection.INCOMING, new BigDecimal("900000.00"), 0, null, null));

        // When the season is played out and rolled over
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        seasonRolloverService.rollover(c.id(), c.userId());

        // Then all transfer offers for the save are purged
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL)).isEmpty();
    }
}
