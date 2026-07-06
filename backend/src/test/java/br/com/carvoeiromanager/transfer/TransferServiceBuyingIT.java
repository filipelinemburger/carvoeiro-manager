package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.contract.ContractValuation;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceBuyingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired ContractValuation contractValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("buy_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player anAiPlayer(Career c) {
        UUID other = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        return playerRepository.findByClubId(other).get(0);
    }

    private BigDecimal pct(BigDecimal value, String factor) {
        return value.multiply(new BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal okWage(Player p) {
        return contractValuation.wageDemand(p.getOverall(), p.getAge());
    }

    @Test
    void offeringFullValueExecutesTheTransfer() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aLowOfferIsCounteredAndCanBeAccepted() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse countered = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.80"), okWage(target), 3);
        assertThat(countered.status()).isEqualTo("COUNTERED");
        assertThat(countered.counterAmount()).isEqualByComparingTo(pct(value, "1.10"));

        TransferOfferResponse done = transferService.acceptCounter(c.id(), c.userId(), countered.id());
        assertThat(done.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aDerisoryOfferIsRejected() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.50"), okWage(target), 3);
        assertThat(r.status()).isEqualTo("REJECTED");
    }

    @Test
    void anOfferOverBudgetIsRejectedWithInsufficientFunds() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal balance = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        assertThatThrownBy(() -> transferService.makeOffer(
                c.id(), c.userId(), target.getId(), balance.add(new BigDecimal("1")), okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void offersAreBlockedWhenTheWindowIsClosed() {
        Career c = createCareer();
        // Move the save date outside any transfer window (May 1 is past pre-season, before mid-season).
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1));
        saveGameRepository.save(save);
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        assertThatThrownBy(() -> transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void foreignUserCannotBrowseTheMarket() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> transferService.getMarket(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void marketListsOnlyOtherClubsPlayersWithAValue() {
        Career c = createCareer();
        var market = transferService.getMarket(c.id(), c.userId());
        assertThat(market).isNotEmpty();
        assertThat(market).noneMatch(m -> m.clubId().equals(c.clubId()));
        assertThat(market).allMatch(m -> m.value().signum() > 0);
    }

    @Test
    void youthProspectsDoNotCountAgainstTheSquadCapWhenBuying() {
        Career c = createCareer();
        int seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        int toAdd = Math.max(0, 30 - seniors) + 5;
        for (int i = 0; i < toAdd; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i,
                    br.com.carvoeiromanager.player.Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            prospect.markAsProspect(80);
            playerRepository.save(prospect);
        }
        assertThat(playerRepository.findByClubId(c.clubId()).size()).isGreaterThanOrEqualTo(30);

        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }
}
