package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.RoleFocus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The regression contract of the role-model swap: a legacy-mapped {@link RoleArchetype}, at its
 * canonical default {@link RoleFocus}, played by a fully-familiar player, reproduces the retired
 * {@code PlayerRole} math EXACTLY. Plus the two new multipliers (focus lean difference, familiarity)
 * and {@link RoleAssignment#resolve}'s grandfathering contract.
 */
class RoleAssignmentContributionTest {

    /** The retired {@code PlayerRole} attack weights, transcribed verbatim before its deletion. */
    private static final Map<RoleArchetype, Map<Attribute, Double>> LEGACY_ATTACK = Map.ofEntries(
            Map.entry(RoleArchetype.GOALKEEPER, Map.of()),
            Map.entry(RoleArchetype.STOPPER, Map.of(Attribute.VELOCIDADE, 0.1, Attribute.FINALIZACAO, 0.1)),
            Map.entry(RoleArchetype.FULLBACK, Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.2)),
            Map.entry(RoleArchetype.BALL_PLAYING_DEFENDER, Map.of(Attribute.VELOCIDADE, 0.1, Attribute.PASSE, 0.4)),
            Map.entry(RoleArchetype.HOLDING, Map.of(Attribute.PASSE, 0.3)),
            Map.entry(RoleArchetype.BOX_TO_BOX,
                    Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.3, Attribute.PASSE, 0.3)),
            Map.entry(RoleArchetype.PLAYMAKER, Map.of(Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.6)),
            Map.entry(RoleArchetype.POACHER, Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.8)),
            Map.entry(RoleArchetype.TARGET_FORWARD, Map.of(Attribute.FINALIZACAO, 0.6, Attribute.PASSE, 0.2)),
            Map.entry(RoleArchetype.WINGER,
                    Map.of(Attribute.VELOCIDADE, 0.6, Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.2)));

    /** The retired {@code PlayerRole} defence weights, transcribed verbatim before its deletion. */
    private static final Map<RoleArchetype, Map<Attribute, Double>> LEGACY_DEFENSE = Map.ofEntries(
            Map.entry(RoleArchetype.GOALKEEPER, Map.of(Attribute.REFLEXOS, 1.0)),
            Map.entry(RoleArchetype.STOPPER, Map.of(Attribute.DESARME, 1.0)),
            Map.entry(RoleArchetype.FULLBACK, Map.of(Attribute.DESARME, 0.8)),
            Map.entry(RoleArchetype.BALL_PLAYING_DEFENDER, Map.of(Attribute.DESARME, 0.85)),
            Map.entry(RoleArchetype.HOLDING, Map.of(Attribute.DESARME, 0.8)),
            Map.entry(RoleArchetype.BOX_TO_BOX, Map.of(Attribute.DESARME, 0.5)),
            Map.entry(RoleArchetype.PLAYMAKER, Map.of(Attribute.DESARME, 0.2)),
            Map.entry(RoleArchetype.POACHER, Map.of()),
            Map.entry(RoleArchetype.TARGET_FORWARD, Map.of(Attribute.DESARME, 0.1)),
            Map.entry(RoleArchetype.WINGER, Map.of(Attribute.DESARME, 0.05)));

    private static Player player(Position pos, int pace, int fin, int pass, int tkl, int gk) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                pace, fin, pass, tkl, gk, 70, 75);
    }

    /** The same player, made fully familiar (++) with the archetype under test. */
    private static Player familiarWith(RoleArchetype archetype, Player p) {
        p.setFamiliarRoles(List.of(archetype.name() + "++"));
        return p;
    }

    private static double weighted(Map<Attribute, Double> weights, Attributes attrs) {
        double sum = 0.0;
        for (Map.Entry<Attribute, Double> e : weights.entrySet()) {
            sum += e.getValue() * attrs.get(e.getKey());
        }
        return sum;
    }

    @Test
    void legacyDefaultsReproduceOldPlayerRoleContributionsExactly() {
        for (RoleArchetype archetype : LEGACY_ATTACK.keySet()) {
            Player p = familiarWith(archetype, player(Position.MID, 61, 73, 82, 57, 44));
            RoleAssignment assignment = new RoleAssignment(archetype, archetype.canonicalDefaultFocus());

            assertThat(assignment.attackContribution(p))
                    .as("attack for %s", archetype)
                    .isEqualTo(weighted(LEGACY_ATTACK.get(archetype), p.attributes()));
            assertThat(assignment.defenseContribution(p))
                    .as("defense for %s", archetype)
                    .isEqualTo(weighted(LEGACY_DEFENSE.get(archetype), p.attributes()));
        }
    }

    @Test
    void thePortedPlayerRoleTestNumbersStillHold() {
        // 0.2*pace + 0.8*finishing = 0.2*50 + 0.8*90 = 82 (ported from PlayerRoleTest).
        Player poacher = familiarWith(RoleArchetype.POACHER, player(Position.ATT, 50, 90, 40, 10, 10));
        RoleAssignment poacherRole = new RoleAssignment(RoleArchetype.POACHER, RoleFocus.ATTACK);
        assertThat(poacherRole.attackContribution(poacher)).isEqualTo(82.0);
        assertThat(poacherRole.defenseContribution(poacher)).isEqualTo(0.0);

        // defense = 0.8 * tackling = 64.
        Player holder = familiarWith(RoleArchetype.HOLDING, player(Position.MID, 50, 50, 60, 80, 10));
        assertThat(new RoleAssignment(RoleArchetype.HOLDING, RoleFocus.DEFEND).defenseContribution(holder))
                .isEqualTo(64.0);

        // defense = 1.0 * goalkeeping = 85; attack weights are all zero.
        Player keeper = familiarWith(RoleArchetype.GOALKEEPER, player(Position.GK, 50, 10, 30, 20, 85));
        RoleAssignment gkRole = new RoleAssignment(RoleArchetype.GOALKEEPER, RoleFocus.DEFEND);
        assertThat(gkRole.defenseContribution(keeper)).isEqualTo(85.0);
        assertThat(gkRole.attackContribution(keeper)).isEqualTo(0.0);
    }

    @Test
    void everyArchetypeAtItsCanonicalFocusHasExactlyNeutralFocusMultipliers() {
        for (RoleArchetype archetype : RoleArchetype.values()) {
            RoleAssignment canonical = new RoleAssignment(archetype, archetype.canonicalDefaultFocus());
            assertThat(canonical.focusAttackMult()).as("attack mult for %s", archetype).isEqualTo(1.0);
            assertThat(canonical.focusDefenseMult()).as("defense mult for %s", archetype).isEqualTo(1.0);
        }
    }

    @Test
    void unfamiliarArchetypeCosts8Percent() {
        Player p = player(Position.ATT, 50, 90, 40, 10, 10);
        p.setFamiliarRoles(List.of(RoleArchetype.TARGET_FORWARD.name() + "++"));   // not POACHER
        RoleAssignment poacher = new RoleAssignment(RoleArchetype.POACHER, RoleFocus.ATTACK);

        assertThat(p.familiarity(RoleArchetype.POACHER)).isZero();
        assertThat(poacher.attackContribution(p)).isEqualTo(82.0 * 0.92, within(1e-9));
    }

    @Test
    void somewhatFamiliarCosts3Percent() {
        Player p = player(Position.ATT, 50, 90, 40, 10, 10);
        p.setFamiliarRoles(List.of(RoleArchetype.POACHER.name() + "+"));
        RoleAssignment poacher = new RoleAssignment(RoleArchetype.POACHER, RoleFocus.ATTACK);

        assertThat(p.familiarity(RoleArchetype.POACHER)).isEqualTo(1);
        assertThat(poacher.attackContribution(p)).isEqualTo(82.0 * 0.97, within(1e-9));
    }

    @Test
    void nonDefaultFocusShiftsContributionsByTheLeanDifference() {
        // WINGER's canonical focus is BALANCED (lean 0/0); ATTACK leans +0.08 attack / −0.08 defense.
        Player p = familiarWith(RoleArchetype.WINGER, player(Position.ATT, 80, 60, 55, 40, 10));
        RoleAssignment balanced = new RoleAssignment(RoleArchetype.WINGER, RoleFocus.BALANCED);
        RoleAssignment attacking = new RoleAssignment(RoleArchetype.WINGER, RoleFocus.ATTACK);

        assertThat(attacking.attackContribution(p))
                .isEqualTo(balanced.attackContribution(p) * 1.08, within(1e-9));
        assertThat(attacking.defenseContribution(p))
                .isEqualTo(balanced.defenseContribution(p) * 0.92, within(1e-9));
    }

    @Test
    void resolveFallsBackToPositionDefault() {
        Player p = player(Position.MID, 60, 60, 60, 60, 10);
        p.setDetailedPosition(DetailedPosition.MEIA_ARMADOR);

        assertThat(RoleAssignment.resolve(Map.of(), p))
                .isEqualTo(RoleAssignment.defaultFor(DetailedPosition.MEIA_ARMADOR));
        assertThat(RoleAssignment.resolve(null, p).archetype()).isEqualTo(RoleArchetype.PLAYMAKER);
    }

    @Test
    void resolveReturnsAPersistedAssignmentVerbatimEvenWhenInvalidForThePlayersPosition() {
        // Grandfathering contract: validation happens at write time only, so a legacy or deliberately
        // improvised assignment keeps scoring with exactly what the manager set.
        Player p = player(Position.DEF, 60, 60, 60, 60, 10);
        p.setDetailedPosition(DetailedPosition.ZAGUEIRO);
        RoleAssignment improvised = new RoleAssignment(RoleArchetype.POACHER, RoleFocus.ATTACK);

        assertThat(RoleArchetype.POACHER.validFor(DetailedPosition.ZAGUEIRO)).isFalse();
        assertThat(RoleAssignment.resolve(Map.of(p.getId(), improvised), p)).isEqualTo(improvised);
    }

    @Test
    void defaultForPairsThePositionDefaultArchetypeWithItsCanonicalFocus() {
        for (DetailedPosition pos : DetailedPosition.values()) {
            RoleAssignment assignment = RoleAssignment.defaultFor(pos);
            assertThat(assignment.archetype()).isEqualTo(RoleArchetype.defaultFor(pos));
            assertThat(assignment.focus()).isEqualTo(assignment.archetype().canonicalDefaultFocus());
        }
    }
}
