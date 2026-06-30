package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FormationTest {

    @Test
    void mapsKnownCodes() {
        assertThat(Formation.fromCode("4-3-3")).isEqualTo(Formation.F_4_3_3);
        assertThat(Formation.fromCode("5-3-2")).isEqualTo(Formation.F_5_3_2);
    }

    @Test
    void unknownCodeDefaultsTo442() {
        assertThat(Formation.fromCode("9-0-1")).isEqualTo(Formation.F_4_4_2);
        assertThat(Formation.fromCode(null)).isEqualTo(Formation.F_4_4_2);
    }

    @Test
    void attackingFormationsBiasAttackOverDefensiveOnes() {
        assertThat(Formation.F_4_3_3.attackMult()).isGreaterThan(Formation.F_5_3_2.attackMult());
        assertThat(Formation.F_5_3_2.defenseMult()).isGreaterThan(Formation.F_4_3_3.defenseMult());
    }
}
