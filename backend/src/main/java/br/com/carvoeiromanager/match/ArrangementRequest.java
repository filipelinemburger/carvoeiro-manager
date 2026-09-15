package br.com.carvoeiromanager.match;

import java.util.Map;

/**
 * A manual rearrangement of the managed side, sent inside a {@link LiveTacticsRequest}. String-keyed
 * because it crosses JSON. {@code slotIndexes}: playerId → slot index, for exactly the players on the
 * pitch. {@code roles}: playerId → {@code RoleArchetype} name for hand-set roles only; may be empty.
 */
public record ArrangementRequest(Map<String, Integer> slotIndexes, Map<String, String> roles) {
}
