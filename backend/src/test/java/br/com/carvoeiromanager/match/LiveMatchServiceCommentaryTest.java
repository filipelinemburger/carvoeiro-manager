package br.com.carvoeiromanager.match;

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
}
