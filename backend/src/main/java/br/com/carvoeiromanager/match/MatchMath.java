package br.com.carvoeiromanager.match;

/**
 * Shared, pure match-scoring math and calibration constants used by both simulation engines
 * ({@link LiveMatchEngine} and {@link MatchSimulationService}), mirroring the {@link SetPieces}
 * static-utility pattern. Keeping a single copy guarantees the live and instant paths score —
 * and book — identically. No RNG, no I/O, no Spring.
 */
final class MatchMath {

    private MatchMath() {
    }

    static final int MINUTES = 90;

    // ~1.35 cards per side per 90 at aggression 1.0. Lowered from 0.02 (spec 2026-08-13 §9): the
    // old rate produced a card storm. A single copy here — rather than one in each engine kept in
    // lockstep by convention — makes the live and instant paths use the identical rate by
    // construction.
    public static final double BASE_CARD_RATE = 0.015;
    public static final double RED_SHARE = 0.06;

    /**
     * Last minute of extra time. NOTE: {@link #MINUTES} stays 90 — it is the per-minute rate
     * divisor for {@link #perMinuteScoreChance} and the normalizer in {@link #fitnessFactor},
     * not a loop bound. A minute of extra time carries the same scoring rate as a minute of
     * normal time, and fitnessFactor keeps decaying past 90 (frac > 1) down to FITNESS_FLOOR.
     */
    static final int EXTRA_TIME_END = 120;

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
