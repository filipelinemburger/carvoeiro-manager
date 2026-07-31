package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherEngineIntegrationTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    private static TeamMatchInput team(GameStyle style) {
        return new TeamMatchInput(72.0, 70.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.20,
                Mentality.BALANCED, style);
    }

    @Test
    void neutralWeatherIsByteIdenticalAcrossManySeeds() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch sunny = engine.simulate(home, away,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed);
            SimulatedMatch neutral = engine.simulate(home, away, Weather.neutral(), seed);

            assertThat(sunny.homeGoals()).isEqualTo(neutral.homeGoals());
            assertThat(sunny.awayGoals()).isEqualTo(neutral.awayGoals());
        }
    }

    @Test
    void heavyRainLowersTotalGoalsAcrossManyMatches() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        int dry = 0;
        int wet = 0;
        for (long seed = 0; seed < 3000; seed++) {
            SimulatedMatch d = engine.simulate(home, away, Weather.neutral(), seed);
            SimulatedMatch w = engine.simulate(home, away,
                    new Weather(WeatherCondition.CHUVA_FORTE, 20), seed);
            dry += d.homeGoals() + d.awayGoals();
            wet += w.homeGoals() + w.awayGoals();
        }
        assertThat(wet).isLessThan(dry);
    }

    @Test
    void aHotDayPunishesAHighPressingSide() {
        TeamMatchInput pressing = team(GameStyle.PRESSAO_ALTA);
        TeamMatchInput balanced = team(GameStyle.EQUILIBRADO);

        int mildPressingGoals = 0;
        int hotPressingGoals = 0;
        for (long seed = 0; seed < 3000; seed++) {
            mildPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed).homeGoals();
            hotPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 36), seed).homeGoals();
        }
        assertThat(hotPressingGoals).isLessThan(mildPressingGoals);
    }
}
