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
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(c.players()).hasSizeGreaterThanOrEqualTo(14));
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
        // No orphans: every pool club belongs to at least one competition,
        // and every membership entry resolves to a pool club.
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
    void everySerieBPlayerCarriesAuthoredAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> assertThat(club.players())
                        .allSatisfy(p -> assertThat(p.attributes())
                                .as("%s %s should be authored", club.shortName(), p.name())
                                .isNotNull()));
    }

    @Test
    void catarinenseOnlyClubsKeepDerivedAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> !SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> assertThat(club.players())
                        .allSatisfy(p -> assertThat(p.attributes())
                                .as("%s %s should be derived", club.shortName(), p.name())
                                .isNull()));
    }

    @Test
    void serieBSquadsHaveRealisticSizeAndGoalkeeperCover() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> {
                    // CRI keeps its pre-existing 27-player authored squad; others are 18-23.
                    int max = club.shortName().equals("CRI") ? 27 : 23;
                    assertThat(club.players()).hasSizeBetween(18, max);
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
    void everyClubHasAValidTacticalIdentity() {
        SeedCareer data = new SeedLoader().loadCareer();

        java.util.Set<String> validFormations = java.util.Arrays.stream(Formation.values())
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
}
