package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
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

    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SeasonRolloverService seasonRolloverService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CompetitionRepository competitionRepository;
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
        // Given a fresh career (rounds 1 and 2 are in the pre-season window)
        Career c = createCareer();

        // When advancing two rounds (the hook runs generateIncomingOffers for rounds 1 and 2)
        roundSimulationService.simulateNextRound(c.id(), c.userId());
        roundSimulationService.simulateNextRound(c.id(), c.userId());

        // Then any generated offers are INCOMING, PENDING, for the managed club, bounded, no errors
        List<TransferOffer> offers = transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL);
        assertThat(offers).allMatch(o -> o.getDirection() == TransferDirection.INCOMING);
        assertThat(offers).allMatch(o -> o.getStatus() == TransferOfferStatus.PENDING);
        assertThat(offers).allMatch(o -> o.getToClubId().equals(c.clubId()));
        assertThat(offers.size()).isLessThanOrEqualTo(4); // up to 2 per generating round
    }

    @Test
    void rolloverPurgesTransferOffers() {
        // Given a career with at least one transfer offer
        Career c = createCareer();
        Player managedPlayer = playerRepository.findByClubId(c.clubId()).get(0);
        UUID aiClub = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        transferOfferRepository.save(TransferOffer.create(c.id(), managedPlayer.getId(), aiClub,
                c.clubId(), TransferDirection.INCOMING, new BigDecimal("900000.00"), 0));

        // When the season is played out and rolled over
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        seasonRolloverService.rollover(c.id(), c.userId());

        // Then all transfer offers for the save are purged
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL)).isEmpty();
    }
}
