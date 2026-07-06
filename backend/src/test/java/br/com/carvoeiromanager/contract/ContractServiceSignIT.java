package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceSignIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired PlayerRepository playerRepository;

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
        // Move the save date outside any transfer window (May 1 is past pre-season, before mid-season).
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1));
        saveGameRepository.save(save);
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

    @Test
    void youthProspectsDoNotCountAgainstTheSquadCapWhenSigning() {
        Career c = createCareer();
        int seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        // Fill the academy so the club, counting prospects, exceeds the 30-player senior cap...
        int toAdd = Math.max(0, 30 - seniors) + 5;
        for (int i = 0; i < toAdd; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i,
                    br.com.carvoeiromanager.player.Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            prospect.markAsProspect(80);
            playerRepository.save(prospect);
        }
        assertThat(playerRepository.findByClubId(c.clubId()).size()).isGreaterThanOrEqualTo(30);

        // ...but seniors are still under the cap, so signing a free agent must succeed.
        FreeAgentResponse fa = anyFreeAgent(c);
        PlayerContractResponse r = contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3);

        assertThat(r.contractYears()).isEqualTo(3);
        assertThat(playerRepository.findById(fa.playerId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }
}
