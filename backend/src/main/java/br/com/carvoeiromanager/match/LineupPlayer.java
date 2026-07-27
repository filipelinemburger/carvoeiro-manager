package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleFocus;
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

    /** The tactical archetype the manager assigned (spec §2.4); replaces the retired PlayerRole. */
    @Enumerated(EnumType.STRING)
    @Column(name = "archetype", nullable = false, length = 24)
    private RoleArchetype archetype;

    /** The focus the archetype is played at; the archetype's canonical default when unspecified. */
    @Enumerated(EnumType.STRING)
    @Column(name = "focus", nullable = false, length = 16)
    private RoleFocus focus;

    /**
     * Optional per-player instruction ("instrução pessoal"). Nullable and never set by the factory
     * methods — stays null (no instruction) unless a manager explicitly assigns one (Task 28 DTO
     * wiring). A null instruction is the neutral no-op that keeps engine results byte-identical.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "instruction", length = 20)
    private PlayerInstruction instruction;

    /**
     * Index of the formation slot this starter occupies (0 = GK slot; order of
     * {@code FormationSlots.of}). Null for bench rows and legacy lineups — the engine then
     * falls back to greedy assignment.
     */
    @Column(name = "slot_index")
    private Integer slotIndex;

    protected LineupPlayer() {
        // for JPA
    }

    private LineupPlayer(UUID lineupId, UUID playerId, boolean starting,
                         RoleArchetype archetype, RoleFocus focus) {
        this.lineupId = lineupId;
        this.playerId = playerId;
        this.starting = starting;
        this.archetype = archetype;
        this.focus = focus;
    }

    /** No-role factory: the direct successor of the old {@code PlayerRole.BOX_TO_BOX} default. */
    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting) {
        return new LineupPlayer(lineupId, playerId, starting, RoleArchetype.BOX_TO_BOX, RoleFocus.BALANCED);
    }

    /** Assigns the archetype at its canonical default focus. */
    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting, RoleArchetype archetype) {
        return new LineupPlayer(lineupId, playerId, starting, archetype, archetype.canonicalDefaultFocus());
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting,
                                      RoleArchetype archetype, RoleFocus focus) {
        return new LineupPlayer(lineupId, playerId, starting, archetype, focus);
    }

    public UUID getLineupId() { return lineupId; }
    public UUID getPlayerId() { return playerId; }
    public boolean isStarting() { return starting; }
    public RoleArchetype getArchetype() { return archetype; }
    public RoleFocus getFocus() { return focus; }
    public PlayerInstruction getInstruction() { return instruction; }
    public Integer getSlotIndex() { return slotIndex; }

    public void setStarting(boolean starting) { this.starting = starting; }
    public void setArchetype(RoleArchetype archetype) { this.archetype = archetype; }
    public void setFocus(RoleFocus focus) { this.focus = focus; }
    public void setInstruction(PlayerInstruction instruction) { this.instruction = instruction; }
    public void setSlotIndex(Integer slotIndex) { this.slotIndex = slotIndex; }
}
