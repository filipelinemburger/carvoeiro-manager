package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventTypeTest {
    @Test
    void definesLiveOnlyEventTypes() {
        assertThat(EventType.valueOf("CHANCE")).isNotNull();
        assertThat(EventType.valueOf("INJURY")).isNotNull();
        assertThat(EventType.valueOf("SUBSTITUTION")).isNotNull();
    }

    @Test
    void goalTypesAreExactlyTheScoringOnes() {
        assertThat(EventType.GOAL.isGoal()).isTrue();
        assertThat(EventType.SET_PIECE_GOAL.isGoal()).isTrue();   // legacy, still counted
        assertThat(EventType.PENALTY_GOAL.isGoal()).isTrue();
        assertThat(EventType.FREE_KICK_GOAL.isGoal()).isTrue();
        assertThat(EventType.CORNER_GOAL.isGoal()).isTrue();
        assertThat(EventType.PENALTY_MISSED.isGoal()).isFalse();
        assertThat(EventType.YELLOW_CARD.isGoal()).isFalse();
        assertThat(EventType.CHANCE.isGoal()).isFalse();
    }

    @Test
    void goalTypeNamesDriveTheDatabaseQueries() {
        assertThat(EventType.goalTypeNames()).containsExactlyInAnyOrder(
                "GOAL", "SET_PIECE_GOAL", "PENALTY_GOAL", "FREE_KICK_GOAL", "CORNER_GOAL");
    }

    @Test
    void newValuesAreAppendedSoExistingOrdinalsAreStable() {
        // LiveCommentary.pick derives variant choice from ordinal — pre-split values must not shift.
        assertThat(EventType.GOAL.ordinal()).isZero();
        assertThat(EventType.HALF_TIME.ordinal()).isEqualTo(8);
    }
}
