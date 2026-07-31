package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceServiceStaffIT {

    @Autowired FinanceService financeService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired StaffRepository staffRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("financestaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void aHiredAssistantBoostsMonthlySponsorshipAndTheirWageIsDeducted() {
        Career c = createCareer();
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.ASSISTENTE,
                StaffQuality.EXCELENTE, "Roberto Lima", new BigDecimal("80000.00"), 17, 17));

        financeService.processMonth(c.id(), LocalDate.of(2026, 3, 1));

        // Assert on the recorded ledger rows directly rather than the club's overall balance —
        // the balance also moves from the unrelated (unmodified) player-wage and upkeep charges,
        // so only the ledger rows isolate the two staff-specific effects being tested here.
        List<FinanceTransactionResponse> transactions =
                financeService.getFinances(c.id(), c.clubId(), c.userId()).transactions();

        FinanceTransactionResponse staffWageTx = transactions.stream()
                .filter(t -> t.type().equals("STAFF_WAGES")).findFirst().orElseThrow();
        assertThat(staffWageTx.amount()).isEqualByComparingTo("-80000.00");

        FinanceTransactionResponse sponsorTx = transactions.stream()
                .filter(t -> t.type().equals("SPONSOR")).findFirst().orElseThrow();
        // R$130 000 x reputation x Excelente's 1.1 multiplier (see FinanceCalculator.monthlySponsorship
        // and the Global Constraints' effect table).
        BigDecimal expectedSponsorship = new BigDecimal("130000")
                .multiply(BigDecimal.valueOf(club.getReputation()))
                .multiply(new BigDecimal("1.1"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(sponsorTx.amount()).isEqualByComparingTo(expectedSponsorship);
    }
}
