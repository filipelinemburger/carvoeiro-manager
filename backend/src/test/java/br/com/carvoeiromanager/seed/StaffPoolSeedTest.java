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

    @Test
    void everyRoleHasAtLeastTwelveEntries() {
        Map<StaffRole, Long> byRole = pool.stream()
                .collect(Collectors.groupingBy(SeedStaff::role, Collectors.counting()));
        assertThat(byRole).allSatisfy((role, n) -> assertThat(n).isGreaterThanOrEqualTo(12L));
    }
}
