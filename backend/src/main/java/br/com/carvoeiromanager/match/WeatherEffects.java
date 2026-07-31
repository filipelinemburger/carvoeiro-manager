package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;

/**
 * Pure, RNG-free environmental modifiers applied to both sides of a match, mirroring the
 * {@link MatchupModifiers} / {@link SetPieces} static-utility pattern. Where
 * {@code MatchupModifiers} answers "does my mentality beat theirs", this answers "what is the
 * pitch doing to both of us".
 *
 * <p><b>Determinism contract:</b> {@link WeatherCondition#ENSOLARADO} at a mild temperature
 * ({@value #MILD_MIN}–{@value #MILD_MAX}°C) returns exactly {@code 1.0} on every field, so a
 * neutral match is byte-identical to the pre-weather engine. Same promise as
 * {@link GameStyle#EQUILIBRADO}.
 */
public final class WeatherEffects {

    static final int MILD_MIN = 13;
    static final int MILD_MAX = 28;

    /** Wet-pitch scoring multiplier, applied to both sides. */
    private static final double LIGHT_RAIN_CHANCE = 0.97;
    private static final double HEAVY_RAIN_CHANCE = 0.93;
    private static final double WIND_CHANCE = 0.98;

    /** Per-side style response to a wet pitch, scaled by wetness. */
    private static final double PASSING_RAIN_PENALTY = 0.06;
    private static final double DIRECT_RAIN_BONUS = 0.04;

    /** Fatigue response to temperature. */
    private static final double HEAT_PER_DEGREE = 0.02;
    private static final double HEAT_DECAY_CAP = 1.20;
    private static final double PRESSING_HEAT_FACTOR = 1.5;
    private static final int COLD_THRESHOLD = 12;
    private static final double COLD_DECAY = 0.95;

    /** Wind's effect on corner and free-kick threat. */
    private static final double WIND_SET_PIECE = 0.85;

    private WeatherEffects() {
    }

    /**
     * Per-side chance and fatigue multipliers plus a shared set-piece multiplier.
     * {@code homeDecayMult}/{@code awayDecayMult} scale that side's {@code fitnessDecay};
     * {@code setPieceMult} scales both sides' corner and free-kick threat.
     */
    public record Impact(double homeChanceMult, double awayChanceMult,
                         double homeDecayMult, double awayDecayMult,
                         double setPieceMult) {
    }

    public static Impact of(Weather weather, TeamMatchInput home, TeamMatchInput away) {
        double wetPitch = switch (weather.condition()) {
            case CHUVA -> LIGHT_RAIN_CHANCE;
            case CHUVA_FORTE -> HEAVY_RAIN_CHANCE;
            case VENTANIA -> WIND_CHANCE;
            case ENSOLARADO, NUBLADO -> 1.0;
        };

        double wetness = weather.condition().wetness();
        double homeChance = wetPitch * styleResponse(home.gameStyle(), wetness);
        double awayChance = wetPitch * styleResponse(away.gameStyle(), wetness);

        double homeDecay = decayMult(weather.temperatureC(), home.gameStyle());
        double awayDecay = decayMult(weather.temperatureC(), away.gameStyle());

        double setPiece = weather.condition() == WeatherCondition.VENTANIA ? WIND_SET_PIECE : 1.0;

        return new Impact(homeChance, awayChance, homeDecay, awayDecay, setPiece);
    }

    /** A wet pitch drags on a passing game and suits a direct one. Other styles are unaffected. */
    private static double styleResponse(GameStyle style, double wetness) {
        if (wetness == 0.0) {
            return 1.0;
        }
        return switch (style) {
            case JOGO_DE_PASSES -> 1 - PASSING_RAIN_PENALTY * wetness;
            case JOGO_DIRETO -> 1 + DIRECT_RAIN_BONUS * wetness;
            default -> 1.0;
        };
    }

    /**
     * Heat above {@value #MILD_MAX}°C accelerates fatigue, and a high-pressing side pays
     * {@value #PRESSING_HEAT_FACTOR}x the penalty — the tactical bite of a hot afternoon.
     * Cold at or below {@value #COLD_THRESHOLD}°C mildly reduces it.
     */
    private static double decayMult(int temperatureC, GameStyle style) {
        if (temperatureC <= COLD_THRESHOLD) {
            return COLD_DECAY;
        }
        if (temperatureC <= MILD_MAX) {
            return 1.0;
        }
        double bonus = HEAT_PER_DEGREE * (temperatureC - MILD_MAX);
        if (style == GameStyle.PRESSAO_ALTA) {
            bonus *= PRESSING_HEAT_FACTOR;
        }
        return Math.min(HEAT_DECAY_CAP, 1.0 + bonus);
    }
}
