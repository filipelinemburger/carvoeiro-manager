package br.com.carvoeiromanager.player;

/** Age-band modifiers for fitness recovery, injury risk, and match fitness decay. */
public enum AgeBand {
    YOUNG(1.15, 1.0, 0.95),
    PRIME(1.0, 1.0, 1.0),
    EXPERIENCED(0.9, 1.2, 1.10),
    VETERAN(0.8, 1.4, 1.20);

    private final double recoveryFactor;
    private final double injuryFactor;
    private final double decayFactor;

    AgeBand(double recoveryFactor, double injuryFactor, double decayFactor) {
        this.recoveryFactor = recoveryFactor;
        this.injuryFactor = injuryFactor;
        this.decayFactor = decayFactor;
    }

    public static AgeBand of(int age) {
        if (age <= 23) return YOUNG;
        if (age <= 29) return PRIME;
        if (age <= 32) return EXPERIENCED;
        return VETERAN;
    }

    public double recoveryFactor() { return recoveryFactor; }
    public double injuryFactor() { return injuryFactor; }
    public double decayFactor() { return decayFactor; }
}
