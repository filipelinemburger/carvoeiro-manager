package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only bridge for the byte-identity suites: reproduces the retired {@code PlayerRole}'s
 * coarse-band defaults (GK→GOALKEEPER, DEF→FULL_BACK, MID→BOX_TO_BOX, ATT→POACHER) as the
 * equivalent {@link RoleArchetype}s, at their canonical default focus, with every player made
 * fully familiar (++) with the archetype they are handed.
 *
 * <p>That is exactly the regression contract of the role-model swap — legacy archetype + canonical
 * focus + familiarity 2 — so every golden signature and hand-computed strength captured against the
 * old model must still reproduce verbatim. A test using this helper is asserting the contract, not
 * working around it.
 */
final class LegacyRoles {

    private LegacyRoles() {
    }

    /** The archetype the retired {@code PlayerRole.defaultFor(band)} mapped to. */
    static RoleArchetype archetypeFor(Position band) {
        return switch (band) {
            case GK -> RoleArchetype.GOALKEEPER;
            case DEF -> RoleArchetype.FULLBACK;
            case MID -> RoleArchetype.BOX_TO_BOX;
            case ATT -> RoleArchetype.POACHER;
        };
    }

    /**
     * Builds the role map for an XI and, as a side effect, makes each player fully familiar with the
     * archetype they are assigned — the familiarity multiplier is then exactly x1.0.
     */
    static Map<UUID, RoleAssignment> forXi(List<Player> xi) {
        Map<UUID, RoleAssignment> roles = new HashMap<>();
        for (Player p : xi) {
            RoleArchetype archetype = archetypeFor(p.getPosition());
            p.setFamiliarRoles(List.of(archetype.name() + "++"));
            roles.put(p.getId(), new RoleAssignment(archetype, archetype.canonicalDefaultFocus()));
        }
        return roles;
    }
}
