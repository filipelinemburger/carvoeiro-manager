package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Note on assertion style: the neutrality contract uses exact {@code isEqualTo(1.0)} — that
 * exactness is the whole point, since it is what keeps the engines byte-identical. Every other
 * assertion compares a *computed* double against a *literal* one (e.g. {@code 1 - 0.06 * 1.0}
 * versus {@code 0.94}), which differ by an ulp in IEEE-754, so those use a tolerance.
 */
class WeatherEffectsTest {

    private static final double EPSILON = 1e-12;

    private static TeamMatchInput team(GameStyle style) {
        return new TeamMatchInput(70.0, 68.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.20,
                Mentality.BALANCED, style);
    }

    private static final TeamMatchInput NEUTRAL_TEAM = team(GameStyle.EQUILIBRADO);

    @Test
    void sunnyAndMildIsExactlyANoOp() {
        for (int t = 13; t <= 28; t++) {
            WeatherEffects.Impact impact = WeatherEffects.of(
                    new Weather(WeatherCondition.ENSOLARADO, t), NEUTRAL_TEAM, NEUTRAL_TEAM);
            assertThat(impact.homeChanceMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.awayChanceMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.homeDecayMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.awayDecayMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.setPieceMult()).as("t=%d", t).isEqualTo(1.0);
        }
    }

    @Test
    void neutralWeatherHelperIsANoOp() {
        WeatherEffects.Impact impact =
                WeatherEffects.of(Weather.neutral(), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeChanceMult()).isEqualTo(1.0);
        assertThat(impact.setPieceMult()).isEqualTo(1.0);
    }

    @Test
    void rainSuppressesScoringForBothSides() {
        WeatherEffects.Impact light = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);
        WeatherEffects.Impact heavy = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA_FORTE, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);

        assertThat(light.homeChanceMult()).isCloseTo(0.97, within(EPSILON));
        assertThat(light.awayChanceMult()).isCloseTo(0.97, within(EPSILON));
        assertThat(heavy.homeChanceMult()).isCloseTo(0.93, within(EPSILON));
        assertThat(heavy.awayChanceMult()).isCloseTo(0.93, within(EPSILON));
    }

    @Test
    void heavyRainPunishesPassingAndRewardsDirectPlay() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA_FORTE, 20),
                team(GameStyle.JOGO_DE_PASSES),
                team(GameStyle.JOGO_DIRETO));

        // wetness 1.0: passing x(1 - 0.06), direct x(1 + 0.04), both on top of the 0.93 wet-pitch mult
        assertThat(impact.homeChanceMult()).isCloseTo(0.93 * 0.94, within(EPSILON));
        assertThat(impact.awayChanceMult()).isCloseTo(0.93 * 1.04, within(EPSILON));
        assertThat(impact.homeChanceMult()).isLessThan(impact.awayChanceMult());
    }

    @Test
    void lightRainAppliesHalfTheStyleEffect() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA, 20),
                team(GameStyle.JOGO_DE_PASSES),
                NEUTRAL_TEAM);

        assertThat(impact.homeChanceMult()).isCloseTo(0.97 * 0.97, within(EPSILON));
    }

    @Test
    void heatAmplifiesFatigueAndHitsHighPressingHardest() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 33),
                team(GameStyle.PRESSAO_ALTA),
                NEUTRAL_TEAM);

        // T=33 -> base bonus 0.02*5 = 0.10; the pressing side pays 1.5x that bonus
        assertThat(impact.awayDecayMult()).isCloseTo(1.10, within(EPSILON));
        assertThat(impact.homeDecayMult()).isCloseTo(1.15, within(EPSILON));
    }

    @Test
    void heatDecayIsCapped() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 45), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeDecayMult()).isCloseTo(1.20, within(EPSILON));
    }

    @Test
    void coldReducesFatigue() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 8), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeDecayMult()).isCloseTo(0.95, within(EPSILON));
        assertThat(impact.awayDecayMult()).isCloseTo(0.95, within(EPSILON));
    }

    @Test
    void windBluntsSetPiecesAndNudgesScoringDown() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.VENTANIA, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.setPieceMult()).isCloseTo(0.85, within(EPSILON));
        assertThat(impact.homeChanceMult()).isCloseTo(0.98, within(EPSILON));
    }
}
