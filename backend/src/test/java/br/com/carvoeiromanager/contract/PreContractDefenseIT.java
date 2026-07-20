package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractDefenseIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private record Threat(Career c, Player player, PreContractAgreement agreement) {}

    /** A managed player with an AGREED incoming poach from the first AI club. */
    private Threat createThreat() {
        User user = User.create("def_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 8, 1));
        saveGameRepository.save(save);
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        mine.setContractYears(1);
        playerRepository.save(mine);
        Club poacher = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> !club.getId().equals(c.clubId()))
                .findFirst().orElseThrow();
        BigDecimal agreedWage = contractValuation.wageDemand(mine.getOverall(), mine.getAge())
                .multiply(PreContractNegotiation.PREMIUM).setScale(2, RoundingMode.HALF_UP);
        PreContractAgreement agreement = PreContractAgreement.create(c.id(), mine.getId(),
                c.clubId(), poacher.getId(), agreedWage, 3, LocalDate.of(2026, 7, 25));
        agreementRepository.save(agreement);
        return new Threat(c, mine, agreement);
    }

    @Test
    void aRenewalBeatingTheAgreedWageByFivePercentCancelsThePoach() {
        Threat t = createThreat();
        BigDecimal winning = t.agreement().getWage()
                .multiply(PreContractNegotiation.DEFENSE_FACTOR).setScale(2, RoundingMode.CEILING);

        contractService.renewContract(t.c().id(), t.c().userId(), t.player().getId(), winning, 3);

        PreContractAgreement reloaded = agreementRepository.findById(t.agreement().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PreContractStatus.CANCELLED);
        Player kept = playerRepository.findById(t.player().getId()).orElseThrow();
        assertThat(kept.getClubId()).isEqualTo(t.c().clubId());
        assertThat(kept.getContractYears()).isEqualTo(3);
        assertThat(kept.getWage()).isEqualByComparingTo(winning);
    }

    @Test
    void aRenewalBelowTheDefenseThresholdIsBlocked() {
        Threat t = createThreat();
        BigDecimal losing = t.agreement().getWage(); // 1.00x < required 1.05x

        assertThatThrownBy(() -> contractService.renewContract(
                t.c().id(), t.c().userId(), t.player().getId(), losing, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT));
        assertThat(agreementRepository.findById(t.agreement().getId()).orElseThrow().getStatus())
                .isEqualTo(PreContractStatus.AGREED);
    }

    @Test
    void aLockedDepartureCannotBeRenewedAtAll() {
        Threat t = createThreat();
        PreContractAgreement agreement = agreementRepository.findById(t.agreement().getId()).orElseThrow();
        agreement.lock();
        agreementRepository.save(agreement);

        assertThatThrownBy(() -> contractService.renewContract(
                t.c().id(), t.c().userId(), t.player().getId(), new BigDecimal("999999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT));
    }
}
