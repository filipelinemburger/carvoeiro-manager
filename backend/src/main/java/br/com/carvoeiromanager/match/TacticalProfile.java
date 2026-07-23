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
 *
 * <p>{@code slotAssignments} maps each starter to the {@link FormationSlots.Slot} it was greedily
 * assigned at kickoff (see {@code SlotAssigner#assignToSlots}). It grades how well each player fits
 * the formation slot they play in — {@link TeamStrengthCalculator} penalises playing out of a
 * player's detailed position. We store the {@link FormationSlots.Slot} (not just a
 * {@link br.com.carvoeiromanager.player.DetailedPosition}) because a slot's accepted set may be
 * multi-valued (e.g. a central striker slot accepts {@code {ATACANTE, FALSO_9}}) and grading needs
 * both {@code slot.accepted()} and {@code slot.coarse()}. An empty map is the legacy/neutral no-op:
 * every player resolves to a x1.0 multiplier, keeping engine results byte-identical. NOTE: a
 * mid-match formation change does NOT re-grade positions — it preserves the kickoff assignment;
 * acceptable since a well-formed squad's assignment is all-exact (x1.0) either way.
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
        Map<UUID, PlayerInstruction> instructions,
        Map<UUID, FormationSlots.Slot> slotAssignments) {

    /** This player's instruction, or {@code null} when none is set (the neutral no-op). */
    public PlayerInstruction instructionFor(UUID playerId) {
        return instructions == null ? null : instructions.get(playerId);
    }
}
