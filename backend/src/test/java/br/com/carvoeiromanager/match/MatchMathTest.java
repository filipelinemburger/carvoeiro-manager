package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatchMathTest {

    @Test
    void homeSideGetsExactlyTheHomeXgEdgeOverAnIdenticalAwaySide() {
        double home = MatchMath.perMinuteScoreChance(50, 50, true);
        double away = MatchMath.perMinuteScoreChance(50, 50, false);
        // home xg - away xg == 2 * HOME_XG (per full match), divided by MINUTES per minute
        assertThat(home - away).isEqualTo(2 * MatchMath.HOME_XG / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void strongerAttackerScoresMoreWhenUnclamped() {
        double strong = MatchMath.perMinuteScoreChance(60, 50, false);
        double weak = MatchMath.perMinuteScoreChance(40, 50, false);
        assertThat(strong).isGreaterThan(weak);
    }

    @Test
    void hopelessMismatchIsFlooredAtXgFloor() {
        assertThat(MatchMath.perMinuteScoreChance(0, 100, false))
                .isEqualTo(MatchMath.XG_FLOOR / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void hugeMismatchIsCappedAtXgCeiling() {
        assertThat(MatchMath.perMinuteScoreChance(100, 0, true))
                .isEqualTo(MatchMath.XG_CEILING / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void fitnessFactorIsOneWhenFreshAndFlooredWhenExhausted() {
        assertThat(MatchMath.fitnessFactor(0.0, 90)).isEqualTo(1.0, within(1e-9));
        assertThat(MatchMath.fitnessFactor(1.0, 90)).isEqualTo(0.75, within(1e-9));
        assertThat(MatchMath.fitnessFactor(0.15, 45)).isEqualTo(0.9625, within(1e-9));
    }
}
