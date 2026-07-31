package br.com.carvoeiromanager.match;

/**
 * Shared, pure match-scoring math used by both simulation engines ({@link LiveMatchEngine} and
 * {@link MatchSimulationService}), mirroring the {@link SetPieces} static-utility pattern. Keeping
 * a single copy guarantees the live and instant paths score identically. No RNG, no I/O, no Spring.
 */
final class MatchMath {

    private MatchMath() {
    }

    static final int MINUTES = 90;
    static final double BASE = 50.0;
    static final double FITNESS_FLOOR = 0.75;

    // --- Recalibrated scoring curve (calibrated against the target bands by MatchCalibrationTest) ---
    static final double GAIN = 2.0;         // talent-spread multiplier (1.0 == legacy)
    static final double ANCHOR = 1.44;      // xG of an average (d == REF) matchup, home bonus excluded
    static final double REF = -6.68;        // average-matchup differential across seeded squads
    static final double HOME_XG = 0.20;     // home-edge xG bonus (home +, away -)
    static final double XG_FLOOR = 0.2;     // lower clamp (unchanged)
    static final double XG_CEILING = 3.5;   // upper clamp (new; guards extreme mismatches)

    /** Fitness multiplier for a decay at a given minute: 1.0 fresh, declining quadratically, floored. */
    static double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    /**
     * Per-minute scoring probability from an attacking and a defending strength. The strength
     * differential is amplified by {@link #GAIN} around the average matchup ({@link #REF}); the
     * home side receives a fixed {@link #HOME_XG} bonus and the away side the mirror penalty, so
     * talent spread and home advantage are independent knobs.
     */
    static double perMinuteScoreChance(double attack, double defense, boolean attackerIsHome) {
        double d = attack - defense;
        double xg = ANCHOR + GAIN * (d - REF) / BASE;
        xg += attackerIsHome ? HOME_XG : -HOME_XG;
        xg = Math.max(XG_FLOOR, Math.min(XG_CEILING, xg));
        return xg / MINUTES;
    }
}
