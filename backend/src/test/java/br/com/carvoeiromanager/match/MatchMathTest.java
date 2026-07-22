package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatchMathTest {

    @Test
    void equalStrengthYieldsBaselineExpectedGoals() {
        // diff = 0 -> expectedGoals = 1.4 -> per-minute = 1.4 / 90
        assertThat(MatchMath.perMinuteScoreChance(65, 65)).isEqualTo(1.4 / 90.0, within(1e-9));
    }

    @Test
    void hopelessMismatchIsFlooredAtPoint2() {
        // diff = -2 -> 1.4 - 2 = -0.6 -> floored to 0.2 -> 0.2 / 90
        assertThat(MatchMath.perMinuteScoreChance(0, 100)).isEqualTo(0.2 / 90.0, within(1e-9));
    }

    @Test
    void fitnessFactorIsOneWhenFreshAndFlooredWhenExhausted() {
        assertThat(MatchMath.fitnessFactor(0.0, 90)).isEqualTo(1.0, within(1e-9));      // no decay
        assertThat(MatchMath.fitnessFactor(1.0, 90)).isEqualTo(0.75, within(1e-9));     // floored
        assertThat(MatchMath.fitnessFactor(0.15, 45)).isEqualTo(0.9625, within(1e-9));  // frac=0.5
    }

    @Test
    void exposesTheSharedConstants() {
        assertThat(MatchMath.MINUTES).isEqualTo(90);
        assertThat(MatchMath.HOME_ADVANTAGE).isEqualTo(5.0);
    }
}
