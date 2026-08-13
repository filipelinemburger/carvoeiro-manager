package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.Formation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {

    private static final Set<String> VALID_POSITIONS = Set.of("GK", "DEF", "MID", "ATT");

    /** The real 20 clubs of the 2026 Brasileirão Série B. */
    private static final Set<String> SERIE_B_2026 = Set.of(
            "CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
            "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE");

    @Test
    void loadsCatarinenseWithClubPoolIncludingCriciuma() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.competitions().get(0).name()).isEqualTo("Campeonato Catarinense");
        assertThat(data.clubPool()).isNotEmpty();
        // Pool size is no longer even: 55 clubs (31 + the 19 Série A sides + the 5 new Série C
        // ones), with the lone orphan HER not tied to any competition's parity.
        assertThat(data.clubPool()).hasSize(55);
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
    void clubPoolHasFiftyFiveClubsAllInSomeCompetitionExceptTheOrphanHercilioLuz() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).hasSize(55);

        Set<String> members = data.competitions().stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());
        // HER is the pool's deliberate orphan (spec §Task 11): it stays in clubPool but plays
        // no competition, so it's the one club allowed to be absent from `members`.
        assertThat(data.clubPool()).allSatisfy(c -> {
            if (c.shortName().equals("HER")) {
                assertThat(members).as("HER must stay an orphan").doesNotContain("HER");
            } else {
                assertThat(members).as("club %s has no competition", c.shortName())
                        .contains(c.shortName());
            }
        });
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

        // After the Catarinense data-realism pass, every one of the 50 pool clubs has a
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
            // CRI keeps its pre-existing 27-player authored squad; Série A sides carry the
            // deeper 22-24 rosters a top-flight season needs; everyone else targets 18-23.
            int max = club.shortName().equals("CRI") ? 27 : 24;
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

    @Test
    void loadsFreeAgentsFromTheSeedFile() {
        var freeAgents = new SeedLoader().loadFreeAgents();

        assertThat(freeAgents).isNotEmpty();
        assertThat(freeAgents).allSatisfy(p -> {
            assertThat(VALID_POSITIONS).contains(p.position());
            assertThat(p.attributes()).as("%s should be authored", p.name()).isNotNull();
            assertThat(p.nationality()).isNotBlank();
        });
    }

    @Test
    void freeAgentNamesDoNotCollideWithAnyClubRosterPlayer() {
        SeedLoader loader = new SeedLoader();
        Set<String> clubNames = loader.loadCareer().clubPool().stream()
                .flatMap(c -> c.players().stream())
                .map(SeedPlayer::name)
                .collect(Collectors.toSet());
        Set<String> freeAgentNames = loader.loadFreeAgents().stream()
                .map(SeedPlayer::name).collect(Collectors.toSet());

        assertThat(clubNames).as("free agent names must not collide with any club roster player")
                .doesNotContainAnyElementsOf(freeAgentNames);
    }

    @Test
    void catarinenseHasTwelveMembersAndFourPhases() {
        SeedCareer career = new SeedLoader().loadCareer();

        SeedCompetition cat = career.competitions().stream()
                .filter(c -> c.name().equals("Campeonato Catarinense")).findFirst().orElseThrow();

        assertThat(cat.memberShortNames()).hasSize(12)
                .contains("JOI", "CAR", "SCA")
                .doesNotContain("HER");

        assertThat(cat.phases()).extracting(SeedPhase::name)
                .containsExactly("Primeira Fase", "Quartas de final", "Semifinal", "Final");
        assertThat(cat.phases().get(0).kind()).isEqualTo("ROUND_ROBIN");
        assertThat(cat.phases().get(0).rounds()).isEqualTo(11);
        assertThat(cat.phases().get(0).qualifiers()).isEqualTo(8);
        assertThat(cat.phases().get(1).kind()).isEqualTo("KNOCKOUT");
        assertThat(cat.phases().get(1).legs()).isEqualTo(2);
        assertThat(cat.phases().get(1).rounds()).isEqualTo(2);
    }

    @Test
    void hercilioLuzStaysInThePoolAsAnOrphan() {
        SeedCareer career = new SeedLoader().loadCareer();

        assertThat(career.clubPool()).extracting(SeedClub::shortName).contains("HER");

        boolean inAnyCompetition = career.competitions().stream()
                .anyMatch(c -> c.memberShortNames().contains("HER"));
        assertThat(inAnyCompetition).isFalse();
    }

    @Test
    void serieBRemainsAPlainLeagueWithNoPhases() {
        SeedCareer career = new SeedLoader().loadCareer();

        SeedCompetition serieB = career.competitions().stream()
                .filter(c -> c.name().equals("Série B")).findFirst().orElseThrow();

        assertThat(serieB.memberShortNames()).hasSize(20);
        assertThat(serieB.phases()).isNullOrEmpty();
    }

    @Test
    void serieAHasTwentyMembersAndNoPhases() {
        SeedCareer career = new SeedLoader().loadCareer();

        SeedCompetition serieA = career.competitions().stream()
                .filter(c -> c.name().equals("Série A")).findFirst().orElseThrow();

        assertThat(serieA.memberShortNames()).hasSize(20);
        assertThat(serieA.phases()).isNullOrEmpty();   // a plain double round-robin
    }

    @Test
    void chapecoensePlaysBothTheCatarinenseAndSerieA() {
        SeedCareer career = new SeedLoader().loadCareer();

        // A club may belong to several competitions; the managed/background split is per
        // competition, not per club, so CHA needs no duplicate pool entry.
        assertThat(career.competitions().stream()
                .filter(c -> c.memberShortNames().contains("CHA"))
                .map(SeedCompetition::name))
                .containsExactlyInAnyOrder("Campeonato Catarinense", "Série A");
        assertThat(career.clubPool()).filteredOn(c -> c.shortName().equals("CHA")).hasSize(1);
    }

    @Test
    void theTwoBotafogosAndTheTwoAtleticosAreDistinctClubs() {
        SeedCareer career = new SeedLoader().loadCareer();

        // Three real short-name collisions the pool has to keep apart: BOT is Botafogo-SP
        // and predates Botafogo-RJ (BFR); ATH is Athletic Club of Minas Gerais, not
        // Athletico Paranaense (CAP); CAM is Camboriú, not Atlético Mineiro (ATM).
        assertThat(club(career, "BOT").state()).isEqualTo("SP");
        assertThat(club(career, "BFR").state()).isEqualTo("RJ");
        assertThat(club(career, "ATH").state()).isEqualTo("MG");
        assertThat(club(career, "CAP").state()).isEqualTo("PR");
        assertThat(club(career, "CAM").state()).isEqualTo("SC");
        assertThat(club(career, "ATM").state()).isEqualTo("MG");
        assertThat(club(career, "ATH").name()).isNotEqualTo(club(career, "CAP").name());
        assertThat(club(career, "CAM").name()).isNotEqualTo(club(career, "ATM").name());
    }

    @Test
    void criciumaDoesNotPlayInSerieA() {
        SeedCareer career = new SeedLoader().loadCareer();

        SeedCompetition serieA = career.competitions().stream()
                .filter(c -> c.name().equals("Série A")).findFirst().orElseThrow();

        // The whole point of the slice: a competition the managed club is not in, so the
        // player has a division above them that runs without them.
        assertThat(serieA.memberShortNames()).doesNotContain("CRI");
    }

    @Test
    void everySerieAClubOutranksTheManagedClub() {
        SeedCareer career = new SeedLoader().loadCareer();

        SeedCompetition serieA = career.competitions().stream()
                .filter(c -> c.name().equals("Série A")).findFirst().orElseThrow();
        double criciuma = topElevenStrength(club(career, "CRI"));

        assertThat(serieA.memberShortNames()).allSatisfy(shortName ->
                assertThat(topElevenStrength(club(career, shortName)))
                        .as("%s must be stronger than Criciúma", shortName)
                        .isGreaterThan(criciuma));
    }

    private static SeedClub club(SeedCareer career, String shortName) {
        return career.clubPool().stream()
                .filter(c -> c.shortName().equals(shortName)).findFirst()
                .orElseThrow(() -> new AssertionError("no club " + shortName + " in the pool"));
    }

    /** Mean best anchor over the first eleven — squads are authored strongest-XI-first. */
    private static double topElevenStrength(SeedClub club) {
        return club.players().stream().limit(11)
                .mapToInt(p -> IntStream.of(p.pace(), p.finishing(), p.passing(),
                                p.tackling(), p.goalkeeping(), p.stamina())
                        .max().orElseThrow())
                .average().orElseThrow();
    }
}
