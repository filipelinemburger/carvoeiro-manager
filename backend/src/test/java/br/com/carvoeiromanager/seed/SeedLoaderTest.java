package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {

    private static final Set<String> VALID_POSITIONS = Set.of("GK", "DEF", "MID", "ATT");

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

        assertThat(data.competitions().get(0).seasonYear()).isEqualTo(2026);
    }
}
