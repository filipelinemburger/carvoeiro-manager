package br.com.carvoeiromanager.match;

/** The weather one fixture is played in. Derived, never persisted. */
public record Weather(WeatherCondition condition, int temperatureC) {

    /** The neutral weather: guaranteed by {@code WeatherEffects} to be a mechanical no-op. */
    public static Weather neutral() {
        return new Weather(WeatherCondition.ENSOLARADO, 20);
    }
}
