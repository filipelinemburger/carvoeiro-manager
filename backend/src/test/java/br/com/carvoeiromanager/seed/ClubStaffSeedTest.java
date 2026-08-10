package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the researched club-staff seed. Coverage is deliberately uneven (a role with no
 * confirmed source is simply absent, leaving an open slot); what must never happen is a
 * contradiction — the same person employed twice, or employed and simultaneously on the
 * unemployed market.
 */
class ClubStaffSeedTest {

    private final SeedLoader loader = new SeedLoader();
    private final List<SeedClubStaff> clubStaff = loader.loadClubStaff();
    private final List<SeedStaff> unemployedPool = loader.loadStaffPool();

    private List<SeedStaff> allEntries() {
        return clubStaff.stream().flatMap(c -> c.staff().stream()).toList();
    }

    @Test
    void everyClubShortNameResolvesAgainstTheClubPool() {
        Set<String> known = loader.loadCareer().clubPool().stream()
                .map(SeedClub::shortName).collect(Collectors.toSet());
        assertThat(clubStaff).allSatisfy(c -> assertThat(known).contains(c.clubShortName()));
    }

    @Test
    void aClubIsListedAtMostOnce() {
        assertThat(clubStaff.stream().map(SeedClubStaff::clubShortName).distinct().count())
                .isEqualTo(clubStaff.size());
    }

    @Test
    void aClubFillsEachRoleAtMostOnce() {
        assertThat(clubStaff).allSatisfy(c ->
                assertThat(c.staff().stream().map(SeedStaff::role).distinct().count())
                        .isEqualTo(c.staff().size()));
    }

    @Test
    void namesAreUniqueAcrossEveryClub() {
        List<String> names = allEntries().stream().map(SeedStaff::name).toList();
        assertThat(names.stream().distinct().count()).isEqualTo(names.size());
    }

    @Test
    void nobodyIsBothEmployedAndOnTheUnemployedMarket() {
        Set<String> employed = allEntries().stream().map(SeedStaff::name).collect(Collectors.toSet());
        Set<String> unemployed = unemployedPool.stream().map(SeedStaff::name).collect(Collectors.toSet());
        assertThat(employed).doesNotContainAnyElementsOf(unemployed);
    }

    @Test
    void attributesAreWithinBoundsAndNamesNonBlank() {
        assertThat(allEntries()).allSatisfy(s -> {
            assertThat(s.name()).isNotBlank();
            assertThat(s.primaryAttribute()).isBetween(1, 20);
            assertThat(s.secondaryAttribute()).isBetween(1, 20);
        });
    }

    @Test
    void theManagedClubHasNoHeadCoachBecauseThePlayerIsTheHeadCoach() {
        assertThat(clubStaff)
                .filteredOn(c -> c.clubShortName().equals("CRI"))
                .allSatisfy(c -> assertThat(c.staff()).noneMatch(s -> s.role() == StaffRole.TECNICO));
    }
}
