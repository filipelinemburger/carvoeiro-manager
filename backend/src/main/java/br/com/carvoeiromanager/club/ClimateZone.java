package br.com.carvoeiromanager.club;

import java.time.Month;

/**
 * Coarse Brazilian climate zone for a club's home city, driving match-day weather generation.
 * Four zones cover all seeded clubs; the monthly profiles live in {@code seed/climate.json} so
 * they are tunable without a recompile.
 */
public enum ClimateZone {
    SUL("Sul"),
    SUDESTE("Sudeste"),
    CENTRO_OESTE("Centro-Oeste"),
    NORDESTE_LITORAL("Litoral nordestino");

    private final String label;

    ClimateZone(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public MonthProfile profileFor(Month month) {
        return ClimateTable.profileFor(this, month);
    }
}
