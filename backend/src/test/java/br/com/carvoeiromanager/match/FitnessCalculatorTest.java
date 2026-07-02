// FitnessCalculatorTest.java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class FitnessCalculatorTest {

    private final FitnessCalculator calc = new FitnessCalculator();
    private static final LocalDate D0 = LocalDate.of(2026, 3, 1);

    @Test
    void neverPlayedReportsFullFitness() {
        assertThat(calc.effectiveFitness(100, null, D0, 25)).isEqualTo(100);
    }

    @Test
    void recoveryClimbsWithRestDaysAndCapsAt100() {
        // base 60, prime age, RECOVERY_BASE 6/day: +18 after 3 days -> 78
        assertThat(calc.effectiveFitness(60, D0, D0.plusDays(3), 27)).isEqualTo(78);
        // 20 days would overshoot -> capped at 100
        assertThat(calc.effectiveFitness(60, D0, D0.plusDays(20), 27)).isEqualTo(100);
    }

    @Test
    void veteransRecoverSlowerThanYouthOverTheSameRest() {
        int youth = calc.effectiveFitness(50, D0, D0.plusDays(4), 20);
        int veteran = calc.effectiveFitness(50, D0, D0.plusDays(4), 34);
        assertThat(youth).isGreaterThan(veteran);
    }

    @Test
    void playingDecaysBaseFitnessAndHigherStaminaDecaysLess() {
        // prime age, stamina 70, outfield: clamp(70/70)=1.0, PRIME 1.0, MID 1.0 -> decay = DECAY_BASE 24 -> 76
        assertThat(calc.decayedBaseFitness(100, 70, 27, Position.MID)).isEqualTo(76);
        int highStamina = calc.decayedBaseFitness(100, 90, 27, Position.MID);
        int lowStamina = calc.decayedBaseFitness(100, 60, 27, Position.MID);
        assertThat(highStamina).isGreaterThan(lowStamina); // fitter engine tires less
        assertThat(highStamina).isLessThan(100);           // still tired
    }

    @Test
    void goalkeepersDecayFarLessThanOutfielders() {
        // Same kickoff/stamina/age: a GK (x0.45) loses ~11 -> 89, an outfielder ~24 -> 76.
        int keeper = calc.decayedBaseFitness(100, 70, 27, Position.GK);
        int outfielder = calc.decayedBaseFitness(100, 70, 27, Position.MID);
        assertThat(keeper).isGreaterThan(outfielder);
        assertThat(keeper).isEqualTo(89);
    }

    @Test
    void fitnessMultIsOneAtFullAndBottomsAt080() {
        assertThat(calc.fitnessMult(100)).isEqualTo(1.0);
        assertThat(calc.fitnessMult(50)).isEqualTo(0.90);
        assertThat(calc.fitnessMult(0)).isEqualTo(0.80);
    }

    @Test
    void injuryChanceRisesAsFitnessFallsAndWithAge() {
        double fresh = calc.injuryChance(100, 27);
        double tired = calc.injuryChance(40, 27);
        double tiredVeteran = calc.injuryChance(40, 34);
        assertThat(tired).isGreaterThan(fresh);
        assertThat(tiredVeteran).isGreaterThan(tired);
        assertThat(fresh).isEqualTo(0.025);
    }

    @Test
    void partialMinutesScaleTheDecay() {
        // Full 90' equals the existing 4-arg decay.
        int full = calc.decayedBaseFitness(100, 70, 27, Position.MID);
        assertThat(calc.decayedBaseFitness(100, 70, 27, Position.MID, 90)).isEqualTo(full);
        // A 30' cameo decays ~1/3 as much; an unused sub (0') does not decay.
        assertThat(calc.decayedBaseFitness(100, 70, 27, Position.MID, 0)).isEqualTo(100);
        int cameo = calc.decayedBaseFitness(100, 70, 27, Position.MID, 30);
        assertThat(100 - cameo).isLessThan(100 - full);
    }
}
