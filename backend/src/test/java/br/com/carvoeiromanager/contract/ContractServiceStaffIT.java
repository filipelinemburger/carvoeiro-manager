package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceStaffIT {

    @Autowired ContractService contractService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired StaffRepository staffRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("contractstaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player freeAgent(UUID saveGameId) {
        return Player.create(saveGameId, null, "Livre Um", Position.MID,
                26, "Brasil", 60, 60, 60, 60, 60, 60, 60);
    }

    @Test
    void anExcelenteScoutLowersTheEffectiveWageDemandNeededToSign() {
        Career c = createCareer();
        Player target = freeAgent(c.id());
        playerRepository.save(target);
        // The managed club now starts with a researched OLHEIRO seeded at career creation
        // (Task 4), so replace rather than blindly insert — otherwise this collides with the
        // staff_club_id_role_key unique constraint. (Base CrudRepository#delete auto-wraps in
        // its own transaction outside a @Transactional test; the derived deleteByClubIdAndRole
        // does not, so it can't be used bare here.)
        staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO).ifPresent(staffRepository::delete);
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.OLHEIRO,
                StaffQuality.EXCELENTE, "Marcos Vieira", new BigDecimal("80000.00"), 17, 17));

        // For a 60-overall, 26-year-old (prime) player: weeklyWage = 60*60*20*1.00 = 72 000.00;
        // undiscounted demand = 72 000.00 * 1.15 = 82 800.00; Excelente's x0.9 scout discount
        // brings it to 74 520.00. An offer of 78 000.00 sits strictly between the two — it must
        // be REJECTED without the discount and ACCEPTED with it, so this offer genuinely proves
        // the multiplier is wired in, not just that some discount exists.
        BigDecimal offer = new BigDecimal("78000.00");

        PlayerContractResponse response = contractService.signFreeAgent(
                c.id(), c.userId(), target.getId(), offer, 3);

        assertThat(response.wage()).isEqualByComparingTo(offer);
    }
}
