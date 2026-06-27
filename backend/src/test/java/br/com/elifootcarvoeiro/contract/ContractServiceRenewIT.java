package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
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
class ContractServiceRenewIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("renew_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player aManagedPlayer(Career c) {
        return playerRepository.findByClubId(c.clubId()).get(0);
    }

    @Test
    void renewingAtTheDemandUpdatesWageAndLength() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());

        PlayerContractResponse r = contractService.renewContract(c.id(), c.userId(), p.getId(), demand, 4);

        assertThat(r.contractYears()).isEqualTo(4);
        assertThat(r.wage()).isEqualByComparingTo(demand);
        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getContractYears()).isEqualTo(4);
        assertThat(reloaded.getWage()).isEqualByComparingTo(demand);
    }

    @Test
    void aLowballWageIsRejected() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        int originalContractYears = p.getContractYears();
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        BigDecimal lowball = demand.subtract(new BigDecimal("1"));

        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), lowball, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("PLAYER_REJECTS_TERMS");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getContractYears())
                .isEqualTo(originalContractYears); // unchanged
    }

    @Test
    void anInvalidLengthIsRejected() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), demand, 6))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_CONTRACT"));
    }

    @Test
    void cannotRenewAPlayerFromAnotherClub() {
        Career c = createCareer();
        UUID otherClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        Player aiPlayer = playerRepository.findByClubId(otherClub).get(0);
        BigDecimal demand = contractValuation.wageDemand(aiPlayer.getOverall(), aiPlayer.getAge());
        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), aiPlayer.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
