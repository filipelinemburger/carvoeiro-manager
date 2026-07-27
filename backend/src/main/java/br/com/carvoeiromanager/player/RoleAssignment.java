package br.com.carvoeiromanager.player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a manager actually assigns to a player for a match (design spec §2.4): a
 * {@link RoleArchetype} plus the {@link RoleFocus} it is played at. Replaces the retired
 * {@code PlayerRole}/{@code RoleBehaviour} pair as the single scoring unit shared by
 * team-strength scoring and both match engines' attribution.
 *
 * <p>A contribution is the archetype's weighted attribute sum scaled by two neutral-at-default
 * multipliers:
 * <ul>
 *   <li><b>focus</b> — the lean difference against the archetype's
 *       {@link RoleArchetype#canonicalDefaultFocus()}, so the canonical focus is exactly x1.0;</li>
 *   <li><b>familiarity</b> — x1.00 fully familiar ({@code ++}), x0.97 somewhat ({@code +}),
 *       x0.92 unfamiliar.</li>
 * </ul>
 *
 * <p>Regression contract: a legacy-mapped archetype, at its canonical default focus, played by a
 * fully-familiar player, reproduces the retired {@code PlayerRole} math EXACTLY.
 */
public record RoleAssignment(RoleArchetype archetype, RoleFocus focus) {

    public RoleAssignment {
        Objects.requireNonNull(archetype, "archetype must not be null");
        Objects.requireNonNull(focus, "focus must not be null");
    }

    public double attackContribution(Player p) {
        return archetype.attackContribution(p) * focusAttackMult() * familiarityMult(p, archetype);
    }

    public double defenseContribution(Player p) {
        return archetype.defenseContribution(p) * focusDefenseMult() * familiarityMult(p, archetype);
    }

    /** Attack scaling of this focus relative to the archetype's canonical default (exactly 1.0 there). */
    public double focusAttackMult() {
        return 1.0 + focus.attackLean() - archetype.canonicalDefaultFocus().attackLean();
    }

    /** Defense scaling of this focus relative to the archetype's canonical default (exactly 1.0 there). */
    public double focusDefenseMult() {
        return 1.0 + focus.defenseLean() - archetype.canonicalDefaultFocus().defenseLean();
    }

    /** Familiarity penalty for playing an archetype the player is not fully trained in. */
    public static double familiarityMult(Player p, RoleArchetype archetype) {
        return switch (p.familiarity(archetype)) {
            case 2 -> 1.00;
            case 1 -> 0.97;
            default -> 0.92;
        };
    }

    /** The vanilla assignment for a position: its default archetype at that archetype's canonical focus. */
    public static RoleAssignment defaultFor(DetailedPosition position) {
        RoleArchetype archetype = RoleArchetype.defaultFor(position);
        return new RoleAssignment(archetype, archetype.canonicalDefaultFocus());
    }

    /**
     * The effective assignment for a player: the persisted entry when present, else the position
     * default. Deliberately does NOT re-check validity — validation happens at write time only, so a
     * grandfathered or deliberately improvised assignment keeps scoring with exactly what the manager
     * set. Single source of truth shared by team-strength scoring and both engines' attribution.
     */
    public static RoleAssignment resolve(Map<UUID, RoleAssignment> roles, Player p) {
        RoleAssignment persisted = roles == null ? null : roles.get(p.getId());
        return persisted != null ? persisted : defaultFor(p.getDetailedPosition());
    }
}
