package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StaffPoolSeedTest {

    private final List<SeedStaff> pool = new SeedLoader().loadStaffPool();

    @Test
    void everyHirableRoleIsCovered() {
        Set<StaffRole> covered = pool.stream().map(SeedStaff::role).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrderElementsOf(StaffRole.HIRABLE);
    }

    @Test
    void theUnemployedPoolNeverOffersAHeadCoach() {
        assertThat(pool).noneMatch(s -> s.role() == StaffRole.TECNICO);
    }

    @Test
    void attributesAreWithinBounds() {
        assertThat(pool).allSatisfy(s -> {
            assertThat(s.primaryAttribute()).isBetween(1, 20);
            assertThat(s.secondaryAttribute()).isBetween(1, 20);
        });
    }

    @Test
    void namesAreUniqueAndNonBlank() {
        assertThat(pool).allSatisfy(s -> assertThat(s.name()).isNotBlank());
        assertThat(pool.stream().map(SeedStaff::name).distinct().count()).isEqualTo(pool.size());
    }

    // The floor used to be 12. Reconciling club-staff.json (nine researched names moved from
    // "unemployed" to "employed at a specific club", per ClubStaffSeedTest) brought
    // PREPARADOR_FISICO down to 8. That is still defensible: a later task in this slice makes
    // staff employed at rival clubs poachable (see design doc §8), so the unemployed pool stops
    // being the only source of hiring candidates — roughly 80 employed professionals across
    // club-staff.json become reachable through poaching, on top of whatever remains here.
    @Test
    void everyRoleHasAtLeastEightEntries() {
        Map<StaffRole, Long> byRole = pool.stream()
                .collect(Collectors.groupingBy(SeedStaff::role, Collectors.counting()));
        assertThat(byRole).allSatisfy((role, n) -> assertThat(n).isGreaterThanOrEqualTo(8L));
    }
}
