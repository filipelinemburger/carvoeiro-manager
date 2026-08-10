package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
    @Autowired StaffRepository staffRepository;

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
        BigDecimal originalWage = p.getWage();
        // The managed club now starts with a researched OLHEIRO seeded at career creation
        // (Task 4), whose scout discount shaves the acceptance threshold below the raw
        // wageDemand used here. Remove it so this test keeps exercising the plain
        // undiscounted-threshold rejection path rather than the discount interaction
        // (that interaction is covered separately, e.g. ContractServiceStaffIT).
        staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO).ifPresent(staffRepository::delete);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        BigDecimal lowball = demand.subtract(new BigDecimal("1"));

        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), lowball, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.PLAYER_REJECTS_TERMS);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getContractYears())
                .isEqualTo(originalContractYears); // unchanged
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getWage())
                .isEqualByComparingTo(originalWage); // unchanged
    }

    @Test
    void anInvalidLengthIsRejected() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), demand, 6))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_CONTRACT));
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
