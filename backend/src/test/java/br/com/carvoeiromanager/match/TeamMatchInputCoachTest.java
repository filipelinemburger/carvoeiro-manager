package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMatchInputCoachTest {

    private final TeamMatchInput base = new TeamMatchInput(
            50.0, 40.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.1, Mentality.BALANCED, GameStyle.EQUILIBRADO);

    @Test
    void aNeutralMultiplierLeavesTheInputUntouched() {
        // The determinism contract: clubs without a head coach must produce byte-identical
        // seeded results, so every pre-existing simulation test stays valid.
        assertThat(base.withCoachMultiplier(1.0)).isEqualTo(base);
    }

    @Test
    void theMultiplierScalesBothAttackAndDefenseAndNothingElse() {
        TeamMatchInput scaled = base.withCoachMultiplier(1.04);

        assertThat(scaled.attack()).isEqualTo(52.0);
        assertThat(scaled.defense()).isEqualTo(41.6);
        assertThat(scaled.tempo()).isEqualTo(base.tempo());
        assertThat(scaled.penaltyConversion()).isEqualTo(base.penaltyConversion());
        assertThat(scaled.mentality()).isEqualTo(base.mentality());
        assertThat(scaled.gameStyle()).isEqualTo(base.gameStyle());
    }
}
