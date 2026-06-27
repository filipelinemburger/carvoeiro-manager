package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceSignIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired PlayerRepository playerRepository;
    @Autowired RoundSimulationService roundSimulationService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("sign_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private FreeAgentResponse anyFreeAgent(Career c) {
        return contractService.getFreeAgents(c.id(), c.userId()).get(0);
    }

    @Test
    void signingAtTheDemandMovesTheFreeAgentToYourClub() {
        Career c = createCareer();
        FreeAgentResponse fa = anyFreeAgent(c);

        PlayerContractResponse r = contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3);

        assertThat(r.contractYears()).isEqualTo(3);
        Player signed = playerRepository.findById(fa.playerId()).orElseThrow();
        assertThat(signed.getClubId()).isEqualTo(c.clubId());
        assertThat(signed.getWage()).isEqualByComparingTo(fa.wageDemand());
        // No longer a free agent.
        assertThat(contractService.getFreeAgents(c.id(), c.userId()))
                .noneMatch(x -> x.playerId().equals(fa.playerId()));
    }

    @Test
    void aLowballWageIsRejected() {
        Career c = createCareer();
        FreeAgentResponse fa = anyFreeAgent(c);
        assertThatThrownBy(() -> contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand().subtract(new BigDecimal("1")), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PLAYER_REJECTS_TERMS"));
    }

    @Test
    void signingIsBlockedWhenTheWindowIsClosed() {
        Career c = createCareer();
        for (int i = 0; i < 5; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        FreeAgentResponse fa = anyFreeAgent(c);
        assertThatThrownBy(() -> contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED"));
    }

    @Test
    void cannotSignAPlayerWhoIsNotAFreeAgent() {
        Career c = createCareer();
        Player owned = playerRepository.findByClubId(c.clubId()).get(0);
        BigDecimal demand = contractValuation.wageDemand(owned.getOverall(), owned.getAge());
        assertThatThrownBy(() -> contractService.signFreeAgent(c.id(), c.userId(), owned.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
