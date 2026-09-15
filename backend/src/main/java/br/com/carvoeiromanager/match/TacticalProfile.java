package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleAssignment;

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
 * <p>{@code roles} maps each starter to the {@link RoleAssignment} (archetype + focus) the manager
 * set; a missing entry resolves to the player's position default (see
 * {@link RoleAssignment#resolve}).
 *
 * <p>{@code slotAssignments} maps each starter to the {@link FormationSlots.Slot} it was greedily
 * assigned at kickoff (see {@code SlotAssigner#assignToSlots}). It grades how well each player fits
 * the formation slot they play in — {@link TeamStrengthCalculator} penalises playing out of a
 * player's detailed position. We store the {@link FormationSlots.Slot} (not just a
 * {@link br.com.carvoeiromanager.player.DetailedPosition}) because grading needs the slot's
 * {@code accepted()} set as a whole, measured against every position the player can play. An empty
 * map is the legacy/neutral no-op: every player resolves to a x1.0 multiplier, keeping engine
 * results byte-identical.
 *
 * <p>{@code slotIndexes} carries each starter's kickoff slot INDEX into {@code FormationSlots.of(formation)}
 * — the only thing that distinguishes two slots accepting the same positions. It is where the live engine
 * starts tracking the arrangement from; mid-match rearrangement, substitute slot inheritance and a
 * manager's formation relocation all happen on the engine's own side state, not in this record
 * (spec 2026-09-10). An empty map means "untracked".
 */
public record TacticalProfile(
        Mentality mentality,
        GameStyle gameStyle,
        AttackZone attackZone,
        String formation,
        Pressing pressing,
        Marking marking,
        Map<UUID, RoleAssignment> roles,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId,
        Map<UUID, PlayerInstruction> instructions,
        Map<UUID, FormationSlots.Slot> slotAssignments,
        Map<UUID, Integer> slotIndexes) {

    /**
     * Pre-index constructor. Yields no slot indexes — the neutral "untracked" profile that legacy
     * callers and the engine's fixed-seed golden tests build, which the live engine never rearranges
     * (see {@code LiveMatchEngine}'s arrangement tracking), keeping those timelines byte-identical.
     */
    public TacticalProfile(Mentality mentality, GameStyle gameStyle, AttackZone attackZone, String formation,
                           Pressing pressing, Marking marking, Map<UUID, RoleAssignment> roles,
                           UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId,
                           Map<UUID, PlayerInstruction> instructions,
                           Map<UUID, FormationSlots.Slot> slotAssignments) {
        this(mentality, gameStyle, attackZone, formation, pressing, marking, roles, penaltyTakerId,
                freekickTakerId, cornerTakerId, instructions, slotAssignments, Map.of());
    }

    /**
     * The same tactics with a live arrangement laid over them: roles, slot assignments and slot
     * indexes replaced, everything else — mentality, style, takers, instructions — untouched.
     */
    public TacticalProfile withArrangement(Map<UUID, FormationSlots.Slot> arrangedSlots,
                                           Map<UUID, Integer> arrangedIndexes,
                                           Map<UUID, RoleAssignment> arrangedRoles) {
        return new TacticalProfile(mentality, gameStyle, attackZone, formation, pressing, marking,
                arrangedRoles, penaltyTakerId, freekickTakerId, cornerTakerId, instructions,
                arrangedSlots, arrangedIndexes);
    }

    /** This player's instruction, or {@code null} when none is set (the neutral no-op). */
    public PlayerInstruction instructionFor(UUID playerId) {
        return instructions == null ? null : instructions.get(playerId);
    }
}
