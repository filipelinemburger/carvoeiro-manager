package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF /
 * player who committed a FOUL). {@code playerInId} is the player coming ON (SUBSTITUTION), the corner
 * taker (CORNER_GOAL), or the assister (open-play GOAL, when the goal was assisted) — else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null. {@code detail} is the {@link AiManagerBrain.Posture} name on AI-produced
 * {@code TACTICS_CHANGE} (null = the user's own change), or {@link #SECOND_YELLOW_DETAIL} on a
 * RED_CARD produced by a second yellow — else null.
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity,
        String detail) {

    /** {@code detail} value on a RED_CARD produced by a second yellow in the same match. */
    public static final String SECOND_YELLOW_DETAIL = "SECOND_YELLOW";
}
