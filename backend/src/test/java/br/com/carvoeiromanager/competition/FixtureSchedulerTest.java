package br.com.carvoeiromanager.competition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // Covers the state-championship-sized fields (4) through Série D groups (4) up to Série C (20),
    // plus common intermediate sizes, so a greedy that only balances at one teamCount can't hide.
    @ParameterizedTest
    @ValueSource(ints = {4, 6, 8, 10, 12, 16, 20})
    void singleRoundRobinPlaysEveryPairExactlyOnce(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        int expectedMatches = teamCount * (teamCount - 1) / 2;
        assertThat(fixtures).as("teamCount %d", teamCount).hasSize(expectedMatches);

        Set<Set<Integer>> pairs = fixtures.stream()
                .map(f -> Set.of(f.homeIndex(), f.awayIndex()))
                .collect(Collectors.toSet());
        assertThat(pairs).as("teamCount %d", teamCount).hasSize(expectedMatches);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6, 8, 10, 12, 16, 20})
    void singleRoundRobinGivesEveryClubOneMatchPerRound(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        int expectedRounds = teamCount - 1;
        assertThat(fixtures.stream().map(ScheduledFixture::round).distinct().count())
                .as("teamCount %d", teamCount).isEqualTo((long) expectedRounds);

        for (int round = 0; round < expectedRounds; round++) {
            final int r = round;
            List<Integer> clubs = fixtures.stream().filter(f -> f.round() == r)
                    .flatMap(f -> Stream.of(f.homeIndex(), f.awayIndex())).sorted().toList();
            assertThat(clubs).as("teamCount %d round %d", teamCount, r)
                    .hasSize(teamCount).doesNotHaveDuplicates();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6, 8, 10, 12, 16, 20})
    void singleRoundRobinBalancesHomeAndAwayWithinOne(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        for (int club = 0; club < teamCount; club++) {
            final int c = club;
            long home = fixtures.stream().filter(f -> f.homeIndex() == c).count();
            long away = fixtures.stream().filter(f -> f.awayIndex() == c).count();
            assertThat(Math.abs(home - away))
                    .as("teamCount %d club %d home=%d away=%d", teamCount, c, home, away)
                    .isLessThanOrEqualTo(1);
        }
    }

    // An odd field cannot pair everyone off every round. Série C sits at 13 members between the
    // seed's Série C intakes, so the scheduler has to hand out byes rather than reject the size.
    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13, 19})
    void singleRoundRobinPlaysEveryPairExactlyOnceWithAnOddField(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        int expectedMatches = teamCount * (teamCount - 1) / 2;
        assertThat(fixtures).as("teamCount %d", teamCount).hasSize(expectedMatches);

        Set<Set<Integer>> pairs = fixtures.stream()
                .map(f -> Set.of(f.homeIndex(), f.awayIndex()))
                .collect(Collectors.toSet());
        assertThat(pairs).as("teamCount %d", teamCount).hasSize(expectedMatches);
        assertThat(fixtures).as("no club index may exceed the field", teamCount)
                .allSatisfy(f -> {
                    assertThat(f.homeIndex()).isLessThan(teamCount);
                    assertThat(f.awayIndex()).isLessThan(teamCount);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13, 19})
    void anOddFieldTakesOneMoreRoundAndGivesEveryClubExactlyOneBye(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        // teamCount rounds, not teamCount - 1: each club sits out exactly one of them.
        assertThat(FixtureScheduler.roundsPerLeg(teamCount)).isEqualTo(teamCount);
        assertThat(fixtures.stream().map(ScheduledFixture::round).distinct().count())
                .as("teamCount %d", teamCount).isEqualTo((long) teamCount);

        for (int round = 0; round < teamCount; round++) {
            final int r = round;
            List<Integer> clubs = fixtures.stream().filter(f -> f.round() == r)
                    .flatMap(f -> Stream.of(f.homeIndex(), f.awayIndex())).sorted().toList();
            assertThat(clubs).as("teamCount %d round %d", teamCount, r)
                    .hasSize(teamCount - 1).doesNotHaveDuplicates();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13, 19})
    void anOddFieldStillBalancesHomeAndAwayWithinOne(int teamCount) {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(teamCount);

        for (int club = 0; club < teamCount; club++) {
            final int c = club;
            long home = fixtures.stream().filter(f -> f.homeIndex() == c).count();
            long away = fixtures.stream().filter(f -> f.awayIndex() == c).count();
            assertThat(home + away).as("teamCount %d club %d match count", teamCount, c)
                    .isEqualTo(teamCount - 1L);
            assertThat(Math.abs(home - away))
                    .as("teamCount %d club %d home=%d away=%d", teamCount, c, home, away)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void doubleRoundRobinAlsoHandlesAnOddField() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(5);

        assertThat(fixtures).hasSize(20);                 // 5 * 4 ordered pairs
        assertThat(fixtures.stream().map(ScheduledFixture::round).distinct().count())
                .isEqualTo(10L);                          // 2 * roundsPerLeg(5)
        assertThat(FixtureScheduler.roundsPerLeg(4)).isEqualTo(3);
        Set<String> ordered = fixtures.stream()
                .map(f -> f.homeIndex() + "-" + f.awayIndex()).collect(Collectors.toSet());
        assertThat(ordered).hasSize(20);
    }

    @Test
    void aFieldOfFewerThanTwoIsStillRejected() {
        assertThatThrownBy(() -> new FixtureScheduler().singleRoundRobin(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixtureScheduler().doubleRoundRobin(1))
                .isInstanceOf(IllegalArgumentException.class);
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
