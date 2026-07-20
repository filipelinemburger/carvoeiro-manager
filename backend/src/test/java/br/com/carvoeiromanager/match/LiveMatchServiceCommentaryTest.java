package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.competition.Fixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchServiceCommentaryTest {

    private LiveTimelineEvent goal(int minute, boolean home) {
        return new LiveTimelineEvent(minute, EventType.GOAL, home, UUID.randomUUID(), null, null, null);
    }

    @Test
    void aGoalThatErasesADeficitForTheManagedSideIsAComeback() {
        LiveTimelineEvent opponentGoal = goal(10, false);
        LiveTimelineEvent managedEqualizer = goal(30, true);
        List<LiveTimelineEvent> events = List.of(opponentGoal, managedEqualizer);

        assertThat(LiveMatchService.isComebackGoal(events, managedEqualizer, true)).isTrue();
    }

    @Test
    void aGoalThatExtendsALeadIsNotAComeback() {
        LiveTimelineEvent managedFirst = goal(10, true);
        LiveTimelineEvent managedSecond = goal(30, true);
        List<LiveTimelineEvent> events = List.of(managedFirst, managedSecond);

        assertThat(LiveMatchService.isComebackGoal(events, managedSecond, true)).isFalse();
    }

    @Test
    void anOpponentGoalIsNeverAComebackForTheManagedSide() {
        LiveTimelineEvent opponentFirst = goal(10, false);
        LiveTimelineEvent opponentSecond = goal(30, false);
        List<LiveTimelineEvent> events = List.of(opponentFirst, opponentSecond);

        assertThat(LiveMatchService.isComebackGoal(events, opponentSecond, true)).isFalse();
    }

    @Test
    void aNonGoalEventIsNeverAComeback() {
        LiveTimelineEvent card = new LiveTimelineEvent(10, EventType.YELLOW_CARD, true, UUID.randomUUID(), null, null, null);
        assertThat(LiveMatchService.isComebackGoal(List.of(card), card, true)).isFalse();
    }

    @Test
    void twoConsecutiveLossesTriggersTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture mostRecent = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        mostRecent.recordResult(0, 1);
        Fixture secondMostRecent = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        secondMostRecent.recordResult(2, 0);

        assertThat(LiveMatchService.isLosingStreak(List.of(mostRecent, secondMostRecent), managedClubId)).isTrue();
    }

    @Test
    void aWinOrDrawMixedInBreaksTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture loss = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        loss.recordResult(0, 1);
        Fixture draw = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        draw.recordResult(1, 1);

        assertThat(LiveMatchService.isLosingStreak(List.of(loss, draw), managedClubId)).isFalse();
    }

    @Test
    void fewerThanTwoPlayedFixturesNeverTriggersTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture onlyOne = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        onlyOne.recordResult(0, 1);

        assertThat(LiveMatchService.isLosingStreak(List.of(onlyOne), managedClubId)).isFalse();
    }

    @Test
    void threeWinsInARowTriggersTheHypeStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win2.recordResult(0, 1);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win3.recordResult(3, 1);

        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2, win3), managedClubId)).isTrue();
    }

    @Test
    void aDrawMixedIntoTheLastThreeBreaksTheThreeWinCheckButNotUnbeaten() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture draw = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        draw.recordResult(1, 1);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        win2.recordResult(1, 0);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win3.recordResult(0, 2);
        Fixture win4 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win4.recordResult(1, 0);

        // Last 3 are win1/draw/win2 in most-recent-first order below — not 3 straight wins —
        // but the last 5 (win1, draw, win2, win3, win4) have no loss, so unbeaten-over-5 still holds.
        assertThat(LiveMatchService.isHypeStreak(List.of(win1, draw, win2, win3, win4), managedClubId)).isTrue();
    }

    @Test
    void aLossInsideTheTopThreeBreaksBothChecks() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        win2.recordResult(0, 1);
        Fixture loss = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        loss.recordResult(0, 2);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win3.recordResult(0, 1);
        Fixture win4 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win4.recordResult(1, 0);

        // Top 3 (win1, win2, loss) breaks the 3-win check; all 5 (win1, win2, loss, win3, win4)
        // contain a loss, breaking the unbeaten-5 check too — both legs must fail.
        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2, loss, win3, win4), managedClubId)).isFalse();
    }

    @Test
    void fewerThanThreePlayedFixturesNeverTriggersTheHypeStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, opponentId, managedClubId, null);
        win2.recordResult(0, 1);

        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2), managedClubId)).isFalse();
    }

    @Test
    void aGoalThatReachesAThreeGoalMarginIsAHypeGoal() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        LiveTimelineEvent m3 = goal(50, true);
        List<LiveTimelineEvent> events = List.of(m1, m2, m3);

        assertThat(LiveMatchService.isHypeGoal(events, m3, true, false)).isTrue();
    }

    @Test
    void aGoalThatExtendsAnAlreadyThreeGoalMarginIsNotAHypeGoalAgain() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        LiveTimelineEvent m3 = goal(50, true);
        LiveTimelineEvent m4 = goal(70, true);
        List<LiveTimelineEvent> events = List.of(m1, m2, m3, m4);

        assertThat(LiveMatchService.isHypeGoal(events, m4, true, false)).isFalse();
    }

    @Test
    void enteringOnAStreakMakesTheFirstManagedGoalAHypeGoalRegardlessOfMargin() {
        LiveTimelineEvent m1 = goal(10, true);
        List<LiveTimelineEvent> events = List.of(m1);

        assertThat(LiveMatchService.isHypeGoal(events, m1, true, true)).isTrue();
    }

    @Test
    void enteringOnAStreakOnlyTheFirstManagedGoalIsAHypeGoal() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        List<LiveTimelineEvent> events = List.of(m1, m2);

        assertThat(LiveMatchService.isHypeGoal(events, m2, true, true)).isFalse();
    }

    @Test
    void anOpponentGoalIsNeverAHypeGoal() {
        LiveTimelineEvent opponentGoal = goal(10, false);
        List<LiveTimelineEvent> events = List.of(opponentGoal);

        assertThat(LiveMatchService.isHypeGoal(events, opponentGoal, true, true)).isFalse();
    }
}
