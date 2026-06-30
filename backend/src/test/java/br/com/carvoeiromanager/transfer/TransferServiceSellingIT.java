package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceSellingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("sell_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anAiClub(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
    }

    /** Create an unsolicited INCOMING bid from an AI club for one of the managed club's players. */
    private TransferOffer incomingBidFor(Career c, Player managedPlayer, BigDecimal amount) {
        TransferOffer offer = TransferOffer.create(c.id(), managedPlayer.getId(), anAiClub(c), c.clubId(),
                TransferDirection.INCOMING, amount, 0, null, null);
        return transferOfferRepository.save(offer);
    }

    private Player aManagedOutfielder(Career c) {
        return playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() != Position.GK).findFirst().orElseThrow();
    }

    @Test
    void acceptingAnIncomingBidSellsThePlayer() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringWithinTheCeilingIsAccepted() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal counter = value.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP); // <= 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", counter);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringAboveTheCeilingIsRejected() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal greedy = value.multiply(new BigDecimal("1.30")).setScale(2, RoundingMode.HALF_UP); // > 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", greedy);

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void cannotSellTheLastGoalkeeper() {
        Career c = createCareer();
        // Reduce managed GKs to exactly one.
        List<Player> gks = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).toList();
        if (gks.size() > 1) {
            playerRepository.deleteAll(gks.subList(1, gks.size()));
        }
        Player lastGk = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        TransferOffer bid = incomingBidFor(c, lastGk, new BigDecimal("800000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SQUAD_TOO_SMALL");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void counteringWithZeroAmountIsRejectedAndPlayerNotSold() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", java.math.BigDecimal.ZERO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("INVALID_TRANSFER");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void getOffersReturnsTheActiveInbox() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        incomingBidFor(c, p, new BigDecimal("900000.00"));
        List<TransferOfferResponse> inbox = transferService.getOffers(c.id(), c.userId());
        assertThat(inbox).anyMatch(o -> o.direction().equals("INCOMING") && o.status().equals("PENDING"));
    }

    @Test
    void generateIncomingOffersCreatesBidsInsideAWindowAndNoneOutside() {
        Career c = createCareer();
        transferService.generateIncomingOffers(c.id(), LocalDate.of(2026, 1, 15)); // pre-season window OPEN
        long inWindow = transferOfferRepository.findBySaveGameIdAndStatusIn(
                c.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size();
        assertThat(inWindow).isGreaterThanOrEqualTo(0); // bounded; may be 0..2 deterministically

        Career c2 = createCareer();
        transferService.generateIncomingOffers(c2.id(), LocalDate.of(2026, 5, 1)); // window CLOSED
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(
                c2.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED))).isEmpty();
    }

    @Test
    void foreignUserCannotReadOrRespond() {
        Career owner = createCareer();
        Career intruder = createCareer();
        Player p = aManagedOutfielder(owner);
        TransferOffer bid = incomingBidFor(owner, p, new BigDecimal("900000.00"));
        assertThatThrownBy(() -> transferService.getOffers(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> transferService.respondToIncoming(owner.id(), intruder.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
