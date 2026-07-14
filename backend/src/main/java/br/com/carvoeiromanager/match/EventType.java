package br.com.carvoeiromanager.match;

import java.util.Arrays;
import java.util.List;

public enum EventType {
    GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME,
    // Appended (ordinals above feed LiveCommentary variant selection and must not shift).
    PENALTY_GOAL, FREE_KICK_GOAL, CORNER_GOAL, PENALTY_MISSED, FOUL;

    /**
     * True for every type that counts on the scoreboard. SET_PIECE_GOAL is legacy: no longer
     * emitted, still counted so old persisted match_events rows keep working.
     */
    public boolean isGoal() {
        return this == GOAL || this == SET_PIECE_GOAL || this == PENALTY_GOAL
                || this == FREE_KICK_GOAL || this == CORNER_GOAL;
    }

    /** Names of every goal-counting type — the single source for DB queries over match_events.type. */
    public static List<String> goalTypeNames() {
        return Arrays.stream(values()).filter(EventType::isGoal).map(Enum::name).toList();
    }
}
