package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.Formation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {

    private static final Set<String> VALID_POSITIONS = Set.of("GK", "DEF", "MID", "ATT");

    /** The real 20 clubs of the 2026 Brasileirão Série B. */
    private static final Set<String> SERIE_B_2026 = Set.of(
            "CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
            "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE");

    @Test
    void loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.competitions().get(0).name()).isEqualTo("Campeonato Catarinense");
        assertThat(data.clubPool()).isNotEmpty();
        assertThat(data.clubPool().size() % 2).isZero();
        assertThat(data.clubPool()).anyMatch(c -> c.shortName().equals("CRI"));
        // Every club — Série B and Catarinense-only alike — now carries a full authored
        // squad (>= 18 players); no club is left at the old thin 14-15 placeholder size.
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(c.players()).hasSizeGreaterThanOrEqualTo(18));
    }

    @Test
    void serieBHasTheRealTwenty2026Members() {
        SeedCareer data = new SeedLoader().loadCareer();

        SeedCompetition serieB = data.competitions().stream()
                .filter(c -> c.name().equals("Série B")).findFirst().orElseThrow();
        assertThat(serieB.memberShortNames())
                .containsExactlyInAnyOrderElementsOf(SERIE_B_2026);
    }

    @Test
    void clubPoolHasTwentyEightClubsAllInSomeCompetition() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).hasSize(28);

        Set<String> members = data.competitions().stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(members).as("club %s has no competition", c.shortName())
                        .contains(c.shortName()));
        Set<String> pool = data.clubPool().stream()
                .map(SeedClub::shortName).collect(Collectors.toSet());
        assertThat(members).allSatisfy(m ->
                assertThat(pool).as("member %s missing from pool", m).contains(m));
    }

    @Test
    void everyPlayerHasValidPosition() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club ->
                assertThat(club.players()).allSatisfy(player ->
                        assertThat(VALID_POSITIONS).contains(player.position())));
    }

    @Test
    void everyClubHasAtLeastOneGoalkeeper() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club ->
                assertThat(club.players())
                        .anyMatch(p -> "GK".equals(p.position())));
    }

    @Test
    void seasonYearIs2026() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.competitions()).allSatisfy(c ->
                assertThat(c.seasonYear()).isEqualTo(2026));
    }

    @Test
    void everyPoolPlayerCarriesAuthoredAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        // After the Catarinense data-realism pass, every one of the 28 pool clubs has a
        // hand-authored roster — there are no more derived-attribute players in the seed.
        assertThat(data.clubPool()).allSatisfy(club -> assertThat(club.players())
                .allSatisfy(p -> assertThat(p.attributes())
                        .as("%s %s should be authored", club.shortName(), p.name())
                        .isNotNull()));
    }

    @Test
    void everySquadHasRealisticSizeAndGoalkeeperCover() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club -> {
            // CRI keeps its pre-existing 27-player authored squad; every other club
            // (Série B and Catarinense-only alike) targets 18-23.
            int max = club.shortName().equals("CRI") ? 27 : 23;
            assertThat(club.players()).as("%s squad size", club.shortName()).hasSizeBetween(18, max);
            long gks = club.players().stream()
                    .filter(p -> "GK".equals(p.position())).count();
            assertThat(gks).as("%s GK count", club.shortName()).isBetween(2L, 3L);
        });
    }

    @Test
    void authoredAttributesHoldTheResearchedValues() {
        SeedCareer data = new SeedLoader().loadCareer();

        SeedPlayer otero = data.clubPool().stream()
                .filter(c -> c.shortName().equals("CRI")).findFirst().orElseThrow()
                .players().stream().filter(p -> p.name().equals("Otero")).findFirst().orElseThrow();

        // Otero is a documented free-kick/long-shot playmaker — the authored block reflects that.
        assertThat(otero.attributes().bolaParada()).isEqualTo(88);
        assertThat(otero.attributes().chuteLonge()).isEqualTo(82);
        assertThat(otero.attributes().visao()).isEqualTo(80);
    }

    @Test
    void criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego() {
        SeedCareer data = new SeedLoader().loadCareer();

        // The user's own example of the known-as naming bug: Criciúma's roster had a
        // player named simply "Diego" who is publicly known by a fuller real name.
        // The known-as correction pass must have fixed this specific, named case.
        Set<String> criciumaNames = data.clubPool().stream()
                .filter(c -> c.shortName().equals("CRI")).findFirst().orElseThrow()
                .players().stream().map(SeedPlayer::name).collect(Collectors.toSet());
        assertThat(criciumaNames).doesNotContain("Diego");
    }

    @Test
    void everyClubHasAValidTacticalIdentity() {
        SeedCareer data = new SeedLoader().loadCareer();

        Set<String> validFormations = java.util.Arrays.stream(Formation.values())
                .map(Formation::code)
                .collect(Collectors.toSet());

        assertThat(data.clubPool()).allSatisfy(club -> {
            assertThat(validFormations).as("%s formation", club.shortName()).contains(club.formation());
            assertThat(club.mentality()).as("%s mentality", club.shortName())
                    .isIn("DEFENSIVE", "BALANCED", "ATTACKING");
            assertThat(club.pressing()).as("%s pressing", club.shortName())
                    .isIn("LOW", "MEDIUM", "HIGH");
            assertThat(club.marking()).as("%s marking", club.shortName())
                    .isIn("ZONAL", "MAN_TIGHT");
        });
    }

    @Test
    void tacticalIdentitiesShowRealVarietyAcrossThePool() {
        SeedCareer data = new SeedLoader().loadCareer();

        Set<String> formations = data.clubPool().stream()
                .map(SeedClub::formation).collect(Collectors.toSet());
        Set<String> mentalities = data.clubPool().stream()
                .map(SeedClub::mentality).collect(Collectors.toSet());
        Set<String> pressings = data.clubPool().stream()
                .map(SeedClub::pressing).collect(Collectors.toSet());

        assertThat(formations).as("formation variety across the pool").hasSizeGreaterThanOrEqualTo(5);
        assertThat(mentalities).as("mentality variety across the pool")
                .containsExactlyInAnyOrder("DEFENSIVE", "BALANCED", "ATTACKING");
        assertThat(pressings).as("pressing variety across the pool")
                .containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH");
    }
}
