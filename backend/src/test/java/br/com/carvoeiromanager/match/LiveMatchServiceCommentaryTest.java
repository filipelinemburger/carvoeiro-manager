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
}
