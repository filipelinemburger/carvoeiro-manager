package br.com.elifootcarvoeiro.competition;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class FixtureSchedulerTest {
    @Test
    void doubleRoundRobinForFourTeamsHasSixRoundsAndTwelveMatches() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(4);

        assertThat(fixtures).hasSize(12);                 // 4 teams: 2*(4-1)*(4/2)
        assertThat(fixtures.stream().map(ScheduledFixture::round).distinct().count()).isEqualTo(6L);
    }

    @Test
    void everyOrderedPairMeetsExactlyOnce() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(4);
        Set<String> pairs = new HashSet<>();
        for (ScheduledFixture f : fixtures) {
            assertThat(f.homeIndex()).isNotEqualTo(f.awayIndex());
            assertThat(pairs.add(f.homeIndex() + "-" + f.awayIndex())).isTrue(); // no duplicate ordered pair
        }
        assertThat(pairs).hasSize(12); // 4*3 ordered pairs
    }

    @Test
    void eachRoundHasEachTeamExactlyOnce() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(6);
        Map<Integer, List<ScheduledFixture>> byRound = new HashMap<>();
        fixtures.forEach(f -> byRound.computeIfAbsent(f.round(), r -> new ArrayList<>()).add(f));
        for (var entry : byRound.entrySet()) {
            Set<Integer> teams = new HashSet<>();
            entry.getValue().forEach(f -> { teams.add(f.homeIndex()); teams.add(f.awayIndex()); });
            assertThat(teams).hasSize(6);
        }
    }
}
