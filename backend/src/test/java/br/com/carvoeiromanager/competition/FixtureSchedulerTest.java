package br.com.carvoeiromanager.competition;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void singleRoundRobinPlaysEveryPairExactlyOnce() {
        // Given a 12-club field
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        // Then: 12*11/2 = 66 matches over 11 rounds, each pair once in either orientation
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).extracting(ScheduledFixture::round)
                .containsOnly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        Set<Set<Integer>> pairs = fixtures.stream()
                .map(f -> Set.of(f.homeIndex(), f.awayIndex()))
                .collect(Collectors.toSet());
        assertThat(pairs).hasSize(66);
    }

    @Test
    void singleRoundRobinGivesEveryClubOneMatchPerRound() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        for (int round = 0; round <= 10; round++) {
            final int r = round;
            List<Integer> clubs = fixtures.stream().filter(f -> f.round() == r)
                    .flatMap(f -> Stream.of(f.homeIndex(), f.awayIndex())).sorted().toList();
            assertThat(clubs).as("round %d", r).hasSize(12).doesNotHaveDuplicates();
        }
    }

    @Test
    void singleRoundRobinBalancesHomeAndAwayWithinOne() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        for (int club = 0; club < 12; club++) {
            final int c = club;
            long home = fixtures.stream().filter(f -> f.homeIndex() == c).count();
            long away = fixtures.stream().filter(f -> f.awayIndex() == c).count();
            assertThat(Math.abs(home - away)).as("club %d home=%d away=%d", c, home, away)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void knockoutPairingsSeedBestAgainstWorst() {
        List<int[]> ties = new FixtureScheduler().knockoutPairings(8);

        assertThat(ties).hasSize(4);
        assertThat(ties.get(0)).containsExactly(0, 7); // 1st v 8th
        assertThat(ties.get(1)).containsExactly(1, 6); // 2nd v 7th
        assertThat(ties.get(2)).containsExactly(2, 5); // 3rd v 6th
        assertThat(ties.get(3)).containsExactly(3, 4); // 4th v 5th
    }

    @Test
    void knockoutPairingsHandlesFourAndTwo() {
        assertThat(new FixtureScheduler().knockoutPairings(4))
                .satisfies(ties -> {
                    assertThat(ties).hasSize(2);
                    assertThat(ties.get(0)).containsExactly(0, 3);
                    assertThat(ties.get(1)).containsExactly(1, 2);
                });
        assertThat(new FixtureScheduler().knockoutPairings(2))
                .satisfies(ties -> {
                    assertThat(ties).hasSize(1);
                    assertThat(ties.get(0)).containsExactly(0, 1);
                });
    }

    @Test
    void knockoutPairingsRejectsNonPowerOfTwo() {
        assertThatThrownBy(() -> new FixtureScheduler().knockoutPairings(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
