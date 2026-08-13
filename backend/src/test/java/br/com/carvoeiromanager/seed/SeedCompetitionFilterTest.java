package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seed filter exists so the test suite does not pay for competitions it never looks at.
 * That makes it the one piece of production code whose whole job is to change what tests see —
 * so it gets tested directly rather than trusted because the suite got faster.
 */
class SeedCompetitionFilterTest {

    private static final String CATARINENSE = "Campeonato Catarinense";
    private static final String SERIE_B = "Série B";
    private static final String SERIE_A = "Série A";
    private static final String SERIE_C = "Série C";

    private static Set<String> competitionNames(SeedCareer career) {
        return career.competitions().stream().map(SeedCompetition::name).collect(Collectors.toSet());
    }

    private static Set<String> shortNames(SeedCareer career) {
        return career.clubPool().stream().map(SeedClub::shortName).collect(Collectors.toSet());
    }

    @Test
    void noFilterLoadsTheWholeSeedSoProductionIsUnaffected() {
        SeedCareer full = new SeedLoader().loadCareer();

        assertThat(competitionNames(full))
                .containsExactlyInAnyOrder(CATARINENSE, SERIE_B, SERIE_A, SERIE_C);
        assertThat(full.clubPool()).hasSize(60);
    }

    @Test
    void blankFilterIsTreatedAsNoFilter() {
        // The opt-in escape hatch is @TestPropertySource("carvoeiro.seed.competitions="), so an
        // empty value has to mean "everything" rather than "nothing".
        assertThat(new SeedLoader("").loadCareer().clubPool()).hasSize(60);
        assertThat(new SeedLoader("   ").loadCareer().clubPool()).hasSize(60);
    }

    @Test
    void filteringDropsTheCompetitionAndTheClubsThatOnlyPlayedInIt() {
        SeedCareer trimmed = new SeedLoader(CATARINENSE + "," + SERIE_B).loadCareer();

        assertThat(competitionNames(trimmed)).containsExactlyInAnyOrder(CATARINENSE, SERIE_B);
        // Exactly the pool as it stood before Série A landed — dropping Série C takes its ten
        // exclusive members with it, and BRU/FIG/BAR stay for the Catarinense.
        assertThat(trimmed.clubPool()).hasSize(31);
        assertThat(shortNames(trimmed)).doesNotContain("FLA", "PAL", "CRU", "ATM", "BFR");
        assertThat(shortNames(trimmed)).doesNotContain("FER", "AMA", "VRE", "PAY", "CAX");
        assertThat(shortNames(trimmed)).doesNotContain("GUA", "FLO", "CFZ", "YPI", "MGA");
        assertThat(shortNames(trimmed)).contains("BRU", "FIG", "BAR");
    }

    @Test
    void aClubPlayingBothAManagedAndADroppedCompetitionSurvives() {
        SeedCareer trimmed = new SeedLoader(CATARINENSE + "," + SERIE_B).loadCareer();

        // Chapecoense plays the Catarinense and Série A. Dropping Série A must not take it with
        // it, or the Catarinense would silently lose a club and its fixture list would not build.
        assertThat(shortNames(trimmed)).contains("CHA");
        assertThat(trimmed.competitions().stream()
                .filter(c -> c.name().equals(CATARINENSE)).findFirst().orElseThrow()
                .memberShortNames()).hasSize(12).contains("CHA");
    }

    @Test
    void theDeliberateOrphanIsNotMistakenForAClubLeftWithNothingToPlay() {
        SeedCareer trimmed = new SeedLoader(CATARINENSE + "," + SERIE_B).loadCareer();

        // HER belongs to no competition at all, on purpose. It looks exactly like the clubs the
        // filter is meant to drop, so the rule is "kept if it never had a competition" rather
        // than "dropped if it has none now".
        assertThat(shortNames(trimmed)).contains("HER");
    }

    @Test
    void everyRetainedCompetitionsMembersAreStillInThePool() {
        SeedCareer trimmed = new SeedLoader(CATARINENSE).loadCareer();

        Set<String> pool = shortNames(trimmed);
        List<String> members = trimmed.competitions().get(0).memberShortNames();
        assertThat(pool).containsAll(members);
    }

    @Test
    void aMisspelledCompetitionFailsLoudlyInsteadOfLoadingAnEmptyWorld() {
        assertThatThrownBy(() -> new SeedLoader("Serie B").loadCareer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Serie B");
    }
}
