package br.com.elifootcarvoeiro.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps to the {@code lineup_players} join table created in V2__game.sql.
 * Uses @IdClass with composite PK (lineup_id, player_id).
 */
@Entity
@Table(name = "lineup_players")
@IdClass(LineupPlayerId.class)
public class LineupPlayer {

    @Id
    @Column(name = "lineup_id", nullable = false)
    private UUID lineupId;

    @Id
    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "starting", nullable = false)
    private boolean starting;

    protected LineupPlayer() {
        // for JPA
    }

    private LineupPlayer(UUID lineupId, UUID playerId, boolean starting) {
        this.lineupId = lineupId;
        this.playerId = playerId;
        this.starting = starting;
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting) {
        return new LineupPlayer(lineupId, playerId, starting);
    }

    public UUID getLineupId() { return lineupId; }
    public UUID getPlayerId() { return playerId; }
    public boolean isStarting() { return starting; }

    public void setStarting(boolean starting) { this.starting = starting; }
}
