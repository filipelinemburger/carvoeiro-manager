package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractOfferIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractService preContractService;
    @Autowired PreContractNegotiation negotiation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("precon_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        // Move into the pre-contract window (July 15+).
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 8, 1));
        saveGameRepository.save(save);
        return c;
    }

    /** A rival final-year outfield senior, made eligible by pinning contractYears to 1. */
    private Player makeEligibleRival(Career c) {
        Player p = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(x -> !x.isYouthProspect() && !x.isFreeAgent())
                .filter(x -> !c.clubId().equals(x.getClubId()))
                .findFirst().orElseThrow();
        p.setContractYears(1);
        playerRepository.save(p);
        return p;
    }

    @Test
    void eligibleListContainsOnlyFinalYearRivalsInsideTheWindow() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);

        List<PreContractEligibleResponse> eligible = preContractService.getEligiblePlayers(c.id(), c.userId());

        assertThat(eligible).extracting(PreContractEligibleResponse::playerId).contains(rival.getId());
        // Nobody from the managed club, nobody with more than a year left.
        List<UUID> managedIds = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId())
                .stream().map(Player::getId).toList();
        assertThat(eligible).extracting(PreContractEligibleResponse::playerId)
                .doesNotContainAnyElementsOf(managedIds);
    }

    @Test
    void offerAtTheDemandAgreesAndCreatesAnAgreement() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());

        PreContractOfferResponse r = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3);

        assertThat(r.status()).isEqualTo("AGREED");
        assertThat(r.agreement()).isNotNull();
        assertThat(r.agreement().wage()).isEqualByComparingTo(demand);
        assertThat(agreementRepository.findBySaveGameId(c.id())).hasSize(1);
    }

    @Test
    void lowballDrawsACounterWhichCanBeAccepted() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        BigDecimal eighty = demand.multiply(new BigDecimal("0.80"));

        PreContractOfferResponse counter = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), eighty, 3);
        assertThat(counter.status()).isEqualTo("COUNTERED");
        assertThat(counter.demandedWage()).isEqualByComparingTo(demand);
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();

        PreContractOfferResponse accepted = preContractService.acceptCounter(
                c.id(), c.userId(), rival.getId(), 3);
        assertThat(accepted.status()).isEqualTo("AGREED");
        assertThat(accepted.agreement().wage()).isEqualByComparingTo(demand);
    }

    @Test
    void insultingOfferIsRejectedOutright() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand.multiply(new BigDecimal("0.50")), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_REJECTS_TERMS));
    }

    @Test
    void offersOutsideTheWindowAreBlocked() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1));
        saveGameRepository.save(save);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), new BigDecimal("99999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PRE_CONTRACT_WINDOW_CLOSED));
    }

    @Test
    void aPlayerWithAnActiveAgreementCannotReceiveASecondOffer() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        preContractService.makeOffer(c.id(), c.userId(), rival.getId(), demand, 3);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PRE_CONTRACT_ALREADY_EXISTS));
    }

    @Test
    void aPlayerWithYearsLeftIsNotEligible() {
        Career c = createCareer();
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(x -> !x.isYouthProspect() && !x.isFreeAgent())
                .filter(x -> !c.clubId().equals(x.getClubId()))
                .findFirst().orElseThrow();
        rival.setContractYears(3);
        playerRepository.save(rival);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), new BigDecimal("99999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_PRE_CONTRACT_ELIGIBLE));
    }

    @Test
    void withdrawingAnAgreedDealDeletesIt() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        PreContractOfferResponse r = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3);

        preContractService.withdraw(c.id(), c.userId(), r.agreement().id());

        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }
}
