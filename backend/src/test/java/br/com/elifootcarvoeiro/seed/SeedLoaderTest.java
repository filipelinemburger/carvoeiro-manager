package br.com.elifootcarvoeiro.seed;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {

    private static final Set<String> VALID_POSITIONS = Set.of("GK", "DEF", "MID", "ATT");

    @Test
    void loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma() {
        SeedData data = new SeedLoader().loadCatarinense();

        assertThat(data.competitionName()).isEqualTo("Campeonato Catarinense");
        assertThat(data.clubs()).isNotEmpty();
        assertThat(data.clubs().size() % 2).isZero();
        assertThat(data.clubs()).anyMatch(c -> c.shortName().equals("CRI"));
        assertThat(data.clubs()).allSatisfy(c ->
                assertThat(c.players()).hasSizeGreaterThanOrEqualTo(14));
    }

    @Test
    void everyPlayerHasValidPosition() {
        SeedData data = new SeedLoader().loadCatarinense();

        assertThat(data.clubs()).allSatisfy(club ->
                assertThat(club.players()).allSatisfy(player ->
                        assertThat(VALID_POSITIONS).contains(player.position())));
    }

    @Test
    void everyClubHasAtLeastOneGoalkeeper() {
        SeedData data = new SeedLoader().loadCatarinense();

        assertThat(data.clubs()).allSatisfy(club ->
                assertThat(club.players())
                        .anyMatch(p -> "GK".equals(p.position())));
    }

    @Test
    void seasonYearIs2026() {
        SeedData data = new SeedLoader().loadCatarinense();

        assertThat(data.seasonYear()).isEqualTo(2026);
    }
}
