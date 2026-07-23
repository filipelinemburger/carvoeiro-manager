package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
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
        UUID cornerTakerId) {
}
