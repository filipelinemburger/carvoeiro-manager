package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleBehaviourTest {

    @Test
    void everyRoleHasAtLeastOneBehaviour() {
        for (PlayerRole role : PlayerRole.values()) {
            assertThat(role.behaviours()).as(role.name()).isNotEmpty();
        }
    }

    @Test
    void defaultBehaviourReproducesTheBaseRoleWeightsExactly() {
        // The determinism contract: a lineup that never picks a variant scores identically
        // to the role-only model.
        for (PlayerRole role : PlayerRole.values()) {
            RoleBehaviour def = role.defaultBehaviour();
            assertThat(def.role()).isEqualTo(role);
            assertThat(def.effectiveAttackWeights()).as(role + " attack").isEqualTo(role.attackWeights());
            assertThat(def.effectiveDefenseWeights()).as(role + " defense").isEqualTo(role.defenseWeights());
        }
    }

    @Test
    void behavioursOnlyValidateForTheirOwnRole() {
        assertThat(RoleBehaviour.FALSO_9.isValidFor(PlayerRole.POACHER)).isTrue();
        assertThat(RoleBehaviour.FALSO_9.isValidFor(PlayerRole.WINGER)).isFalse();
    }

    @Test
    void falso9ShiftsEmphasisFromFinishingToPassingAndVision() {
        var base = PlayerRole.POACHER.attackWeights();
        var eff = RoleBehaviour.FALSO_9.effectiveAttackWeights();
        // finishing pulled down, passing added, vision introduced
        assertThat(eff.get(Attribute.FINALIZACAO)).isLessThan(base.get(Attribute.FINALIZACAO));
        assertThat(eff.getOrDefault(Attribute.PASSE, 0.0)).isGreaterThan(base.getOrDefault(Attribute.PASSE, 0.0));
        assertThat(eff.getOrDefault(Attribute.VISAO, 0.0)).isGreaterThan(0.0);
    }

    @Test
    void effectiveWeightsAreNeverNegative() {
        for (RoleBehaviour b : RoleBehaviour.values()) {
            b.effectiveAttackWeights().values().forEach(w -> assertThat(w).isGreaterThan(0.0));
            b.effectiveDefenseWeights().values().forEach(w -> assertThat(w).isGreaterThan(0.0));
        }
    }
}
