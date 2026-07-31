package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPreviewWeatherTest {

    @Test
    void heavyRainAdviceMentionsTheWetPitch() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.CHUVA_FORTE, 18));
        assertThat(advice).containsIgnoringCase("gramado");
    }

    @Test
    void hotWeatherAdviceWarnsAboutPressing() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.ENSOLARADO, 34));
        assertThat(advice).containsIgnoringCase("press");
    }

    @Test
    void windAdviceMentionsSetPieces() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.VENTANIA, 22));
        assertThat(advice).containsIgnoringCase("bola parada");
    }

    @Test
    void mildWeatherGivesNoAdvice() {
        assertThat(MatchPreviewService.weatherAdvice(Weather.neutral())).isNull();
    }
}
