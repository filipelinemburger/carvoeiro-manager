package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF).
 * {@code playerInId} is the player coming ON — only for {@code SUBSTITUTION}, else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null.
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity) {
}
