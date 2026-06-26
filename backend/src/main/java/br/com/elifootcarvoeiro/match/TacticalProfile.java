package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Pressing;

import java.util.Map;
import java.util.UUID;

/**
 * The tactical settings applied to one club for one match. Not a JPA entity:
 * built by {@link TacticsService} from a persisted lineup (managed club) or as a
 * default (AI clubs / unset). {@code formation} is the display code, e.g. "4-3-3".
 */
public record TacticalProfile(
        Mentality mentality,
        String formation,
        Pressing pressing,
        Marking marking,
        Map<UUID, PlayerRole> roles,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
