package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.RoleArchetype;

import java.util.Map;
import java.util.UUID;

/**
 * A manual rearrangement of one side, applied at {@code minute} (spec 2026-09-10). A complete snapshot,
 * not a delta: {@code slotIndexes} places every player on the pitch by slot index into the formation in
 * force at that minute, so it replays without knowing any earlier state. {@code roles} holds only the
 * archetypes the manager set by hand; everyone else takes their slot's natural role. Like
 * {@link TimedTactics} it draws no RNG.
 */
public record TimedSlots(int minute, boolean home, Map<UUID, Integer> slotIndexes,
                         Map<UUID, RoleArchetype> roles) {
}
