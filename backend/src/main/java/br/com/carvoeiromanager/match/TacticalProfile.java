package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Pressing;

import java.util.Map;
import java.util.UUID;

/**
 * The tactical settings applied to one club for one match. Not a JPA entity:
 * built by {@link TacticsService} from a persisted lineup (managed club) or as a
 * default (AI clubs / unset). {@code formation} is the display code, e.g. "4-3-3".
 * {@code gameStyle} scales attack/defense/fitness-decay by how well the XI fits the style.
 * {@code attackZone} re-weights who scores/assists an open-play goal (wings vs centre vs mixed).
 * {@code instructions} holds each starter's optional per-player instruction (empty for AI/unset);
 * an empty map is the neutral no-op that keeps engine results byte-identical.
 */
public record TacticalProfile(
        Mentality mentality,
        GameStyle gameStyle,
        AttackZone attackZone,
        String formation,
        Pressing pressing,
        Marking marking,
        Map<UUID, PlayerRole> roles,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId,
        Map<UUID, PlayerInstruction> instructions) {

    /** This player's instruction, or {@code null} when none is set (the neutral no-op). */
    public PlayerInstruction instructionFor(UUID playerId) {
        return instructions == null ? null : instructions.get(playerId);
    }
}
