package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link RoleArchetype}: the 10 legacy-mapped archetypes' weight-regression contract
 * against the retired {@link PlayerRole}, contribution math, the canonical-default-focus
 * invariant, and {@link RoleFocus} lean bounds. The taxonomy shape itself (valid positions,
 * focus lists, defaults) is covered separately by {@code RoleArchetypeFutbinSyncTest}.
 */
class RoleArchetypeTest {

    private Player outfield(Position pos, int pace, int fin, int pass, int tkl) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                pace, fin, pass, tkl, 10, 70, 75);
    }

    @Test
    void legacyMappedArchetypesKeepTheOldPlayerRoleWeights() {
        assertThat(RoleArchetype.GOALKEEPER.attackWeights()).isEqualTo(Map.of());
        assertThat(RoleArchetype.GOALKEEPER.defenseWeights())
                .isEqualTo(Map.of(Attribute.REFLEXOS, 1.0));

        assertThat(RoleArchetype.STOPPER.attackWeights())
                .isEqualTo(Map.of(Attribute.VELOCIDADE, 0.1, Attribute.FINALIZACAO, 0.1));
        assertThat(RoleArchetype.STOPPER.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 1.0));

        assertThat(RoleArchetype.FULLBACK.attackWeights())
                .isEqualTo(Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.2));
        assertThat(RoleArchetype.FULLBACK.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.8));

        assertThat(RoleArchetype.BALL_PLAYING_DEFENDER.attackWeights())
                .isEqualTo(Map.of(Attribute.VELOCIDADE, 0.1, Attribute.PASSE, 0.4));
        assertThat(RoleArchetype.BALL_PLAYING_DEFENDER.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.85));

        assertThat(RoleArchetype.HOLDING.attackWeights())
                .isEqualTo(Map.of(Attribute.PASSE, 0.3));
        assertThat(RoleArchetype.HOLDING.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.8));

        assertThat(RoleArchetype.BOX_TO_BOX.attackWeights()).isEqualTo(Map.of(
                Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.3, Attribute.PASSE, 0.3));
        assertThat(RoleArchetype.BOX_TO_BOX.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.5));

        assertThat(RoleArchetype.PLAYMAKER.attackWeights())
                .isEqualTo(Map.of(Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.6));
        assertThat(RoleArchetype.PLAYMAKER.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.2));

        assertThat(RoleArchetype.POACHER.attackWeights())
                .isEqualTo(Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.8));
        assertThat(RoleArchetype.POACHER.defenseWeights()).isEqualTo(Map.of());

        assertThat(RoleArchetype.TARGET_FORWARD.attackWeights())
                .isEqualTo(Map.of(Attribute.FINALIZACAO, 0.6, Attribute.PASSE, 0.2));
        assertThat(RoleArchetype.TARGET_FORWARD.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.1));

        assertThat(RoleArchetype.WINGER.attackWeights()).isEqualTo(Map.of(
                Attribute.VELOCIDADE, 0.6, Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.2));
        assertThat(RoleArchetype.WINGER.defenseWeights())
                .isEqualTo(Map.of(Attribute.DESARME, 0.05));
    }

    @Test
    void contributionsMatchWeightedSums() {
        // 0.2*pace + 0.8*finishing = 0.2*50 + 0.8*90 = 82 (ported from PlayerRoleTest.poacherWeightsFinishingForAttack)
        Player poacherProfile = outfield(Position.ATT, 50, 90, 40, 10);
        assertThat(RoleArchetype.POACHER.attackContribution(poacherProfile)).isEqualTo(82.0);
        assertThat(RoleArchetype.POACHER.defenseContribution(poacherProfile)).isEqualTo(0.0);

        // defense = 0.8 * tackling = 64 (ported from PlayerRoleTest.holdingMidWeightsTacklingForDefense)
        Player holdingProfile = outfield(Position.MID, 50, 50, 60, 80);
        assertThat(RoleArchetype.HOLDING.defenseContribution(holdingProfile)).isEqualTo(64.0);

        // defense = 1.0 * goalkeeping(REFLEXOS) = 85; attack weights are all zero
        // (ported from PlayerRoleTest.goalkeeperWeightsGoalkeepingForDefenseAndNothingForAttack)
        Player gk = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.GK, 25, "BRA",
                50, 10, 30, 20, 85, 70, 75);
        assertThat(RoleArchetype.GOALKEEPER.defenseContribution(gk)).isEqualTo(85.0);
        assertThat(RoleArchetype.GOALKEEPER.attackContribution(gk)).isEqualTo(0.0);
    }

    @Test
    void canonicalDefaultFocusIsValidSomewhere() {
        for (RoleArchetype archetype : RoleArchetype.values()) {
            boolean appearsSomewhere = archetype.validPositions().stream()
                    .anyMatch(pos -> archetype.focusesFor(pos).contains(archetype.canonicalDefaultFocus()));
            assertThat(appearsSomewhere)
                    .as("%s canonicalDefaultFocus() %s should appear in at least one position's focus list",
                            archetype, archetype.canonicalDefaultFocus())
                    .isTrue();
        }
    }

    @Test
    void focusLeansAreSmall() {
        for (RoleFocus focus : RoleFocus.values()) {
            assertThat(focus.attackLean()).as("%s attackLean", focus).isBetween(-0.1, 0.1);
            assertThat(focus.defenseLean()).as("%s defenseLean", focus).isBetween(-0.1, 0.1);
        }
    }
}
