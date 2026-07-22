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
    static final double HOME_ADVANTAGE = 5.0;

    /** Fitness multiplier for a decay at a given minute: 1.0 fresh, declining quadratically, floored. */
    static double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    /** Per-minute scoring probability from an attacking and a defending strength. */
    static double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
