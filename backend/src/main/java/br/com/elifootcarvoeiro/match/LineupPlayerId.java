package br.com.elifootcarvoeiro.match;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key class for {@link LineupPlayer}.
 * Maps to the (lineup_id, player_id) PK defined in V2__game.sql.
 * Must be Serializable and override equals/hashCode per JPA spec.
 */
public class LineupPlayerId implements Serializable {

    private UUID lineupId;
    private UUID playerId;

    public LineupPlayerId() {
        // for JPA
    }

    public LineupPlayerId(UUID lineupId, UUID playerId) {
        this.lineupId = lineupId;
        this.playerId = playerId;
    }

    public UUID getLineupId() { return lineupId; }
    public UUID getPlayerId() { return playerId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineupPlayerId other)) return false;
        return Objects.equals(lineupId, other.lineupId) && Objects.equals(playerId, other.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineupId, playerId);
    }
}
