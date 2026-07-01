// FitnessCalculatorTest.java
package br.com.carvoeiromanager.match;

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
        // base 60, prime age, RECOVERY_BASE 5/day: +15 after 3 days -> 75
        assertThat(calc.effectiveFitness(60, D0, D0.plusDays(3), 27)).isEqualTo(75);
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
        int highStamina = calc.decayedBaseFitness(100, 90, 27);
        int lowStamina = calc.decayedBaseFitness(100, 60, 27);
        assertThat(highStamina).isGreaterThan(lowStamina); // fitter engine tires less
        assertThat(highStamina).isLessThan(100);           // still tired
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
}
