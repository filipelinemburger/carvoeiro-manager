package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.club.ClimateZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Structural invariants of the club pool that hold no matter how many divisions exist.
 *
 * <p>These are cheap and they have all failed for real. A duplicated shortName (Camboriú and
 * Atlético-MG both wanting CAM) silently makes one club unreachable by every lookup that resolves
 * a club by code. A competition naming a member that is not in the pool fails much later, inside
 * career creation, as a null club id.
 */
class SeedPoolIntegrityTest {

    /** The one club that deliberately plays in no competition at all. */
    private static final String INTENTIONAL_ORPHAN = "HER";

    private final SeedCareer career = new SeedLoader().loadCareer();

    @Test
    void everyShortNameIsUnique() {
        Map<String, Long> counts = career.clubPool().stream()
                .collect(Collectors.groupingBy(SeedClub::shortName, Collectors.counting()));
        List<String> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        assertThat(duplicated).as("duplicated shortNames").isEmpty();
    }

    @Test
    void everyShortNameIsThreeUppercaseLetters() {
        assertThat(career.clubPool()).allSatisfy(club ->
                assertThat(club.shortName()).as("shortName of %s", club.name()).matches("[A-Z]{3}"));
    }

    @Test
    void everyCompetitionMemberExistsInThePool() {
        Set<String> pool = career.clubPool().stream()
                .map(SeedClub::shortName)
                .collect(Collectors.toSet());
        for (SeedCompetition competition : career.competitions()) {
            assertThat(competition.memberShortNames())
                    .as("members of %s missing from the pool", competition.name())
                    .allMatch(pool::contains);
        }
    }

    @Test
    void noCompetitionListsTheSameClubTwice() {
        for (SeedCompetition competition : career.competitions()) {
            assertThat(competition.memberShortNames())
                    .as("duplicate members in %s", competition.name())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void hercilioLuzIsTheOnlyClubOutsideEveryCompetition() {
        Set<String> playing = career.competitions().stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());
        List<String> orphans = career.clubPool().stream()
                .map(SeedClub::shortName)
                .filter(s -> !playing.contains(s))
                .sorted()
                .toList();
        assertThat(orphans).containsExactly(INTENTIONAL_ORPHAN);
    }

    @Test
    void everyClubNameAndCommonNameIsUnique() {
        assertThat(career.clubPool().stream().map(SeedClub::name).toList()).doesNotHaveDuplicates();
        assertThat(career.clubPool().stream().map(SeedClub::commonName).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void everyClubDeclaresAClimateZoneThatParses() {
        // Club.climateZone defaults to SUDESTE, so a missing or misspelled value does not fail —
        // it silently gives the club São Paulo's weather. This is the only thing that catches it.
        assertThat(career.clubPool()).allSatisfy(club -> {
            assertThat(club.climateZone()).as("climateZone of %s", club.name()).isNotBlank();
            assertThatCode(() -> ClimateZone.valueOf(club.climateZone()))
                    .as("climateZone of %s", club.name())
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void everyStateMapsToExactlyOneClimateZone() {
        // Two clubs in the same state on different zones is always a typo. Today the pool is
        // perfectly 1:1 across 13 states; Série C adds AM, MA, PB and SE.
        Map<String, Set<String>> zonesByState = career.clubPool().stream()
                .collect(Collectors.groupingBy(SeedClub::state,
                        Collectors.mapping(SeedClub::climateZone, Collectors.toSet())));
        assertThat(zonesByState).allSatisfy((state, zones) ->
                assertThat(zones).as("zones seen for state %s", state).hasSize(1));
    }
}
