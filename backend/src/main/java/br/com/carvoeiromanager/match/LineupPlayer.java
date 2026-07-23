package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.PlayerRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private PlayerRole role;

    /**
     * Optional per-player instruction ("instrução pessoal"). Nullable and never set by the factory
     * methods — stays null (no instruction) unless a manager explicitly assigns one (Task 28 DTO
     * wiring). A null instruction is the neutral no-op that keeps engine results byte-identical.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "instruction", length = 20)
    private PlayerInstruction instruction;

    protected LineupPlayer() {
        // for JPA
    }

    private LineupPlayer(UUID lineupId, UUID playerId, boolean starting, PlayerRole role) {
        this.lineupId = lineupId;
        this.playerId = playerId;
        this.starting = starting;
        this.role = role;
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting) {
        return new LineupPlayer(lineupId, playerId, starting, PlayerRole.BOX_TO_BOX);
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting, PlayerRole role) {
        return new LineupPlayer(lineupId, playerId, starting, role);
    }

    public UUID getLineupId() { return lineupId; }
    public UUID getPlayerId() { return playerId; }
    public boolean isStarting() { return starting; }
    public PlayerRole getRole() { return role; }
    public PlayerInstruction getInstruction() { return instruction; }

    public void setStarting(boolean starting) { this.starting = starting; }
    public void setRole(PlayerRole role) { this.role = role; }
    public void setInstruction(PlayerInstruction instruction) { this.instruction = instruction; }
}
