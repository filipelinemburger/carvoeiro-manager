package br.com.elifootcarvoeiro.competition;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionMutationTest {

    @Test
    void seasonYearAndTotalRoundsAreMutableForRollover() {
        // Given a competition for season 2026 with 18 rounds
        Competition competition = Competition.create(UUID.randomUUID(), "Campeonato Catarinense",
                "LEAGUE", 2026, 18);

        // When rolling it into the next season
        competition.setSeasonYear(2027);
        competition.setTotalRounds(18);

        // Then the new values are reflected
        assertThat(competition.getSeasonYear()).isEqualTo(2027);
        assertThat(competition.getTotalRounds()).isEqualTo(18);
    }
}
