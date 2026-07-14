package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF /
 * player who committed a FOUL). {@code playerInId} is the player coming ON (SUBSTITUTION), the corner
 * taker (CORNER_GOAL), or the assister (open-play GOAL, when the goal was assisted) — else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null. {@code detail} is the {@link AiManagerBrain.Posture} name — only for
 * AI-produced {@code TACTICS_CHANGE}, else null (a null detail on TACTICS_CHANGE means the user's
 * own change).
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity,
        String detail) {
}
