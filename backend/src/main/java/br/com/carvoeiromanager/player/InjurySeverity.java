package br.com.carvoeiromanager.player;

import java.util.Random;

/** Injury severity tiers with their layoff ranges (in matches) and draw distribution. */
public enum InjurySeverity {
    NONE(0, 0),
    KNOCK(1, 1),
    MINOR(2, 4),
    MAJOR(5, 12);

    private final int minMatches;
    private final int maxMatches;

    InjurySeverity(int minMatches, int maxMatches) {
        this.minMatches = minMatches;
        this.maxMatches = maxMatches;
    }

    /** A concrete layoff length within this tier's range. NONE returns 0. */
    public int layoffMatches(Random rng) {
        if (this == NONE) return 0;
        return minMatches + rng.nextInt(maxMatches - minMatches + 1);
    }

    /** Draws an actual injury tier (never NONE): KNOCK 60%, MINOR 30%, MAJOR 10%. */
    public static InjurySeverity roll(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.60) return KNOCK;
        if (r < 0.90) return MINOR;
        return MAJOR;
    }
}
