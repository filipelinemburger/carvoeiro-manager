package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMatchInputCoachTest {

    private final TeamMatchInput base = new TeamMatchInput(
            50.0, 40.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.1, Mentality.BALANCED, GameStyle.EQUILIBRADO);

    @Test
    void aNeutralMultiplierReturnsTheVerySameInstance() {
        // The determinism contract: clubs without a head coach must produce byte-identical
        // seeded results, so every pre-existing simulation test stays valid. isSameAs, not
        // isEqualTo — x * 1.0 == x is exact in IEEE-754, so isEqualTo would also pass with the
        // early return deleted and would not pin the documented identity contract.
        assertThat(base.withCoachMultiplier(1.0)).isSameAs(base);
    }

    @Test
    void theMultiplierScalesBothAttackAndDefenseAndNothingElse() {
        TeamMatchInput scaled = base.withCoachMultiplier(1.04);

        // Asserted as a whole record rather than field by field: the constructor takes ten
        // positional doubles, so a stray `* m` on fitnessDecay (which would make a BETTER coach's
        // side tire FASTER) is exactly the slip a per-field assertion tends to miss.
        // 50.0 * 1.04 == 52.0 and 40.0 * 1.04 == 41.6 are both exact in IEEE-754.
        assertThat(scaled).isEqualTo(new TeamMatchInput(
                52.0, 41.6, 1.0, 1.0, 0.5, 0.5, 0.8, 0.1, Mentality.BALANCED, GameStyle.EQUILIBRADO));
    }
}
