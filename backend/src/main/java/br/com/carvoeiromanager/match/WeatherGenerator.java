package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.club.MonthProfile;

import java.time.LocalDate;
import java.util.Random;
import java.util.UUID;

/**
 * Derives a fixture's weather from the home club's climate zone and the month of the match.
 * Pure and deterministic: the RNG is seeded from the fixture id, so the same fixture yields
 * identical weather in every process and after every reload. Weather is therefore never stored —
 * the engines, the eve-of-match news, and the UI all recompute the same answer.
 *
 * <p>Accepted cost: retuning {@code climate.json} later retroactively changes a played fixture's
 * recomputed weather. This is <b>not</b> merely a display detail, though — {@code
 * AttendanceCalculator} prices a fixture's crowd off the weather in effect at match resolution,
 * and that attendance IS persisted on the fixture row. So a retune can leave a finished match
 * showing, say, {@code CHUVA_FORTE} next to a crowd that was actually priced under {@code
 * ENSOLARADO}. Accepted anyway: it is no worse than any other historical balance retune, and
 * storing weather just to freeze this one derived number would reintroduce the very
 * engine/news/UI disagreement risk this class exists to rule out.
 *
 * <p>Draw order is fixed for reproducibility: rain, heavy-rain escalation, wind, cloud, temperature.
 */
public final class WeatherGenerator {

    /** Share of rainy matches that escalate to CHUVA_FORTE. */
    private static final double HEAVY_RAIN_SHARE = 1.0 / 3.0;
    /** Share of dry, windless matches that are merely cloudy rather than sunny. */
    private static final double CLOUD_SHARE = 0.40;
    /** Rain cools the air. */
    private static final int RAIN_TEMPERATURE_DROP = 3;

    private WeatherGenerator() {
    }

    public static Weather forFixture(UUID fixtureId, ClimateZone zone, LocalDate matchDate) {
        MonthProfile profile = zone.profileFor(matchDate.getMonth());
        Random rng = new Random(
                fixtureId.getMostSignificantBits() ^ fixtureId.getLeastSignificantBits());

        boolean raining = rng.nextDouble() < profile.rainChance();
        boolean heavy = raining && rng.nextDouble() < HEAVY_RAIN_SHARE;
        boolean windy = !raining && rng.nextDouble() < profile.windChance();
        boolean cloudy = !raining && !windy && rng.nextDouble() < CLOUD_SHARE;

        WeatherCondition condition;
        if (heavy) {
            condition = WeatherCondition.CHUVA_FORTE;
        } else if (raining) {
            condition = WeatherCondition.CHUVA;
        } else if (windy) {
            condition = WeatherCondition.VENTANIA;
        } else if (cloudy) {
            condition = WeatherCondition.NUBLADO;
        } else {
            condition = WeatherCondition.ENSOLARADO;
        }

        int span = profile.maxC() - profile.minC();
        int temperature = profile.minC() + (span > 0 ? rng.nextInt(span + 1) : 0);
        if (raining) {
            temperature -= RAIN_TEMPERATURE_DROP;
        }

        return new Weather(condition, temperature);
    }
}
