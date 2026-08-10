package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit-level coverage for {@link LiveTimeline#statsThrough(int)} — a pure function of a
 * hand-built {@link LiveTimeline}, so timelines are built directly here rather than through
 * {@link LiveMatchEngine}. The engine itself fills {@code minuteStats} with one entry per
 * simulated minute (see {@code LiveMatchEngine.simulate}, which rolls a {@link MinuteStats} entry
 * every minute); several cases here exercise a hand-built non-empty {@code minuteStats} to cover
 * the fold in isolation.
 */
class LiveTimelineStatsThroughTest {

    private static LiveTimelineEvent event(int minute, EventType type, boolean home) {
        return new LiveTimelineEvent(minute, type, home, UUID.randomUUID(), null, null, null);
    }

    private static LiveTimelineEvent goal(int minute, boolean home) {
        return event(minute, EventType.GOAL, home);
    }

    private static LiveTimelineEvent chance(int minute, boolean home) {
        return event(minute, EventType.CHANCE, home);
    }

    private static LiveTimeline timeline(List<LiveTimelineEvent> events, List<MinuteStats> minuteStats) {
        return new LiveTimeline(0, 0, events, Map.of(), List.of(), 90, minuteStats);
    }

    @Test
    void emptyTimelineAtMinuteZeroHasAllCountersZeroAndNeutralPossession() {
        // Given an empty timeline
        LiveTimeline empty = timeline(List.of(), List.of());

        // When stats are read at minute 0
        MatchStats stats = empty.statsThrough(0);

        // Then every counter is zero and possession is a neutral 50/50
        assertThat(stats.home()).isEqualTo(new TeamStats(50, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(stats.away()).isEqualTo(new TeamStats(50, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void eventDerivedCountersLandOnTheRightSideWithNoMinuteStats() {
        // Given one of every event-derived counter, split across both sides
        List<LiveTimelineEvent> events = List.of(
                goal(10, true),
                event(15, EventType.PENALTY_MISSED, true),
                event(25, EventType.CORNER_GOAL, true),
                event(35, EventType.YELLOW_CARD, true),
                goal(12, false),
                chance(20, false),
                event(30, EventType.FOUL, false),
                event(40, EventType.RED_CARD, false));
        LiveTimeline timeline = timeline(events, List.of());

        // When stats are read after every event
        MatchStats stats = timeline.statsThrough(90);

        // Then home has: 2 goals (GOAL + CORNER_GOAL), 1 missed penalty (on target), 1 corner
        // (from the corner goal), 1 yellow — and its saves reflect away's single on-target goal.
        assertThat(stats.home()).isEqualTo(new TeamStats(50, 3, 3, 0, 1, 0, 0, 1, 0));
        // And away has: 1 goal, 1 chance (a shot, not on target), 1 foul, 1 red — and its saves
        // reflect home's 3 on-target shots minus home's 2 goals.
        assertThat(stats.away()).isEqualTo(new TeamStats(50, 2, 1, 1, 0, 0, 1, 0, 1));
    }

    @Test
    void throughMinuteGenuinelyFiltersOutFutureEvents() {
        // Given a single home goal at minute 60
        LiveTimeline timeline = timeline(List.of(goal(60, true)), List.of());

        // When stats are read at minute 45
        TeamStats atFortyFive = timeline.statsThrough(45).home();
        // Then the goal has not happened yet as far as this reveal is concerned
        assertThat(atFortyFive.shots()).isZero();
        assertThat(atFortyFive.shotsOnTarget()).isZero();

        // When stats are read at minute 90
        TeamStats atNinety = timeline.statsThrough(90).home();
        // Then the goal is counted
        assertThat(atNinety.shots()).isEqualTo(1);
        assertThat(atNinety.shotsOnTarget()).isEqualTo(1);
    }

    @Test
    void nineNearMissChancesStillCountAsNineShotsNotJustTheNarratedOnes() {
        // Given nine near-miss chances for the home side and no goals at all
        List<LiveTimelineEvent> events = new ArrayList<>();
        for (int minute = 1; minute <= 9; minute++) {
            events.add(chance(minute, true));
        }
        LiveTimeline timeline = timeline(events, List.of());

        // When stats are read after all nine chances
        TeamStats home = timeline.statsThrough(90).home();

        // Then all nine chances count as shots, not only the ones that became goals (zero here)
        assertThat(home.shots()).isEqualTo(9);
        assertThat(home.shotsOnTarget()).isZero();
    }

    @Test
    void aCornerGoalAlwaysImpliesAtLeastOneCornerEvenWithNoRolledCorners() {
        // Given a single corner goal and no minuteStats contribution at all
        LiveTimeline timeline = timeline(List.of(event(20, EventType.CORNER_GOAL, true)), List.of());

        // When stats are read
        TeamStats home = timeline.statsThrough(90).home();

        // Then the corner goal alone is enough to produce a corner
        assertThat(home.corners()).isEqualTo(1);
        assertThat(home.corners()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void savesAreDerivedFromTheOpponentsOnTargetShotsMinusTheirGoalsAndAreNeverNegative() {
        // Given the away side scores twice from open play and nothing else happens
        LiveTimeline timeline = timeline(List.of(goal(10, false), goal(20, false)), List.of());

        // When stats are read
        MatchStats stats = timeline.statsThrough(90);

        // Then home saves = away shotsOnTarget(2) - away goals(2) = 0, never negative
        assertThat(stats.home().saves()).isZero();
        // And away saves = home shotsOnTarget(0) - home goals(0) = 0
        assertThat(stats.away().saves()).isZero();
    }

    @Test
    void minuteStatsFoldAddsExtraShotsOnTargetCornersAndOffsidesAndAveragesPossession() {
        // Given three revealed minutes with varying possession and per-side minute stats
        MinuteStats minute1 = new MinuteStats(70,
                new MinuteTeamStats(2, 1, 1, 0), new MinuteTeamStats(1, 0, 0, 1));
        MinuteStats minute2 = new MinuteStats(50,
                new MinuteTeamStats(1, 1, 0, 1), new MinuteTeamStats(2, 1, 1, 0));
        MinuteStats minute3 = new MinuteStats(60,
                new MinuteTeamStats(0, 0, 1, 0), new MinuteTeamStats(1, 0, 0, 0));
        LiveTimeline timeline = timeline(List.of(), List.of(minute1, minute2, minute3));

        // When stats are read through minute 3 (no events at all, so every count is the fold)
        MatchStats stats = timeline.statsThrough(3);

        // Then home's extraShots, openPlayOnTarget, corners and offsides are summed across minutes
        assertThat(stats.home().shots()).isEqualTo(2 + 1 + 0);
        assertThat(stats.home().shotsOnTarget()).isEqualTo(1 + 1 + 0);
        assertThat(stats.home().corners()).isEqualTo(1 + 0 + 1);
        assertThat(stats.home().offsides()).isEqualTo(0 + 1 + 0);

        // And so are away's
        assertThat(stats.away().shots()).isEqualTo(1 + 2 + 1);
        assertThat(stats.away().shotsOnTarget()).isEqualTo(0 + 1 + 0);
        assertThat(stats.away().corners()).isEqualTo(0 + 1 + 0);
        assertThat(stats.away().offsides()).isEqualTo(1 + 0 + 0);

        // And possession is the rounded mean of the three minutes' homePossession, with the two
        // sides always summing to 100
        assertThat(stats.home().possession()).isEqualTo(60);
        assertThat(stats.away().possession()).isEqualTo(40);
        assertThat(stats.home().possession() + stats.away().possession()).isEqualTo(100);
    }

    @Test
    void throughMinuteLargerThanMinuteStatsSizeClampsInsteadOfThrowing() {
        // Given only two minutes of rolled stats
        MinuteStats minute1 = new MinuteStats(60, new MinuteTeamStats(1, 1, 0, 0), MinuteTeamStats.EMPTY);
        MinuteStats minute2 = new MinuteStats(40, new MinuteTeamStats(1, 0, 0, 0), MinuteTeamStats.EMPTY);
        LiveTimeline timeline = timeline(List.of(), List.of(minute1, minute2));

        // When stats are requested for minute 90, far beyond the two rolled minutes
        TeamStats home = timeline.statsThrough(90).home();

        // Then only the two available minutes are folded in, without throwing
        assertThat(home.shots()).isEqualTo(2);
        assertThat(home.shotsOnTarget()).isEqualTo(1);
    }
}
