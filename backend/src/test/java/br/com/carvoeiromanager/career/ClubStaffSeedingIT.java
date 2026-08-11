package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubStaffSeedingIT {

    @Autowired SaveGameService saveGameService;
    @Autowired StaffRepository staffRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;

    private SaveGameResponse createCareer() {
        User user = User.create("seedstaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void theManagedClubStartsWithItsResearchedCommission() {
        SaveGameResponse save = createCareer();

        List<Staff> staff = staffRepository.findByClubId(save.managedClubId());

        assertThat(staff).isNotEmpty();
        assertThat(staff).allSatisfy(s -> {
            assertThat(s.getSaveGameId()).isEqualTo(save.id());
            assertThat(s.getQuality()).isEqualTo(StaffQuality.forAverage(s.attributeAverage()));
            assertThat(s.getWage()).isEqualByComparingTo(s.getQuality().monthlyWage());
        });
    }

    @Test
    void theManagedClubNeverGetsAHeadCoachBecauseThePlayerIsOne() {
        SaveGameResponse save = createCareer();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.TECNICO))
                .isEmpty();
    }

    @Test
    void rivalClubsAlsoStartStaffedAndDifferFromEachOther() {
        SaveGameResponse save = createCareer();

        List<Club> rivals = clubRepository.findBySaveGameId(save.id()).stream()
                .filter(c -> !c.getId().equals(save.managedClubId()))
                .toList();
        List<Staff> rivalStaff = rivals.stream()
                .flatMap(c -> staffRepository.findByClubId(c.getId()).stream())
                .toList();

        assertThat(rivalStaff).isNotEmpty();
        // The point of the whole slice: rivals are no longer mechanically identical. Compared PER
        // CLUB, not across the flattened list — different ROLES trivially have different averages,
        // so a flat distinct() would pass even if all 30 rivals were seeded the same commission,
        // which is the exact regression this is here to catch.
        long distinctCommissions = rivals.stream()
                .map(club -> staffRepository.findByClubId(club.getId()).stream()
                        .sorted(java.util.Comparator.comparing(Staff::getRole))
                        .map(s -> s.getRole() + ":" + s.attributeAverage())
                        .toList())
                .filter(signature -> !signature.isEmpty())
                .distinct()
                .count();
        assertThat(distinctCommissions).isGreaterThan(rivals.size() / 2);
    }

    @Test
    void atLeastOneRivalHasAHeadCoach() {
        SaveGameResponse save = createCareer();

        List<Staff> coaches = staffRepository.findBySaveGameId(save.id()).stream()
                .filter(s -> s.getRole() == StaffRole.TECNICO)
                .toList();

        assertThat(coaches).isNotEmpty();
        assertThat(coaches).noneMatch(s -> s.getClubId().equals(save.managedClubId()));
    }
}
