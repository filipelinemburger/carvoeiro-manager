package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic crowd model. Reputation sets the baseline draw; recent form, league
 * position, and the weather modulate it, so a home gate reflects how the season is actually
 * going rather than being a constant.
 *
 * <p>Extracted from {@code FinanceCalculator.gateIncome}, which previously computed attendance
 * as a throwaway local and returned only money. Finance no longer owns crowd behaviour.
 *
 * <p>Ranges: form 0.90–1.10, position 0.90–1.10, weather 0.74–1.00 — so the worst case
 * (bottom of the table, no form, heavy rain, temperature extreme) is ~0.60x the reputation
 * baseline and the best case ~1.21x.
 */
@Component
public class AttendanceCalculator {

    private static final double FILL_FLOOR = 0.15;
    private static final double FILL_CEILING = 1.0;

    private static final int FORM_MAX_POINTS = 15;
    private static final double FORM_MIN = 0.90;
    private static final double FORM_SPAN = 0.20;

    private static final double POSITION_MAX = 1.10;
    private static final double POSITION_SPAN = 0.20;

    private static final int HOT_THRESHOLD = 35;
    private static final int COLD_THRESHOLD = 8;
    private static final double EXTREME_TEMPERATURE_FACTOR = 0.95;

    public int attendance(int stadiumCapacity, int homeReputation, int awayReputation,
                          int recentFormPoints, int leaguePosition, int clubCount,
                          Weather weather) {
        double fill = base(homeReputation, awayReputation)
                * form(recentFormPoints)
                * position(leaguePosition, clubCount)
                * weather(weather);

        fill = Math.max(FILL_FLOOR, Math.min(FILL_CEILING, fill));
        return Math.min(stadiumCapacity, (int) Math.round(stadiumCapacity * fill));
    }

    /** Unchanged from the original gateIncome formula. */
    private double base(int homeReputation, int awayReputation) {
        return 0.40 + homeReputation / 200.0 + awayReputation / 400.0;
    }

    /** Points from the last 5 league matches, 0-15. */
    private double form(int recentFormPoints) {
        int points = Math.max(0, Math.min(FORM_MAX_POINTS, recentFormPoints));
        return FORM_MIN + FORM_SPAN * (points / (double) FORM_MAX_POINTS);
    }

    /** 1-based league position; a one-club competition is treated as neutral. */
    private double position(int leaguePosition, int clubCount) {
        if (clubCount <= 1) {
            return 1.0;
        }
        int pos = Math.max(1, Math.min(clubCount, leaguePosition));
        return POSITION_MAX - POSITION_SPAN * (pos - 1) / (double) (clubCount - 1);
    }

    private double weather(Weather weather) {
        double factor = switch (weather.condition()) {
            case ENSOLARADO -> 1.00;
            case NUBLADO -> 0.98;
            case VENTANIA -> 0.95;
            case CHUVA -> 0.90;
            case CHUVA_FORTE -> 0.78;
        };
        int t = weather.temperatureC();
        if (t >= HOT_THRESHOLD || t <= COLD_THRESHOLD) {
            factor *= EXTREME_TEMPERATURE_FACTOR;
        }
        return factor;
    }
}
