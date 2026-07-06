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

    @Test
    void mapsAllSixteenCodes() {
        String[] codes = {
            "4-4-2", "4-3-3", "3-5-2", "5-3-2", "4-5-1", "4-2-3-1",
            "4-1-4-1", "4-3-2-1", "3-4-3", "4-4-1-1", "5-4-1", "4-2-4",
            "3-4-2-1", "4-1-2-1-2", "5-2-3", "4-3-1-2",
        };
        for (String code : codes) {
            assertThat(Formation.fromCode(code).code()).isEqualTo(code);
        }
        assertThat(Formation.values()).hasSize(16);
    }

    @Test
    void everyFormationMultiplierStaysInBand() {
        for (Formation f : Formation.values()) {
            assertThat(f.attackMult()).isBetween(0.88, 1.14);
            assertThat(f.defenseMult()).isBetween(0.88, 1.14);
        }
    }

    @Test
    void extremeFormationsBiasCorrectly() {
        assertThat(Formation.F_4_2_4.attackMult()).isGreaterThan(Formation.F_4_4_2.attackMult());
        assertThat(Formation.F_5_4_1.defenseMult()).isGreaterThan(Formation.F_4_4_2.defenseMult());
    }
}
