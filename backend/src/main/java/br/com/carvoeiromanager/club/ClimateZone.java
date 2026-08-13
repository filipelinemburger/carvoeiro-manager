package br.com.carvoeiromanager.club;

import java.time.Month;

/**
 * Coarse Brazilian climate zone for a club's home city, driving match-day weather generation.
 * Five zones cover all seeded clubs; the monthly profiles live in {@code seed/climate.json} so
 * they are tunable without a recompile.
 */
public enum ClimateZone {
    SUL("Sul"),
    SUDESTE("Sudeste"),
    CENTRO_OESTE("Centro-Oeste"),
    NORDESTE_LITORAL("Litoral nordestino"),
    /**
     * Equatorial north — Manaus, Belém, São Luís. Hot and humid year-round with a heavy Dec–May
     * wet season and almost no wind. Belém sat on {@link #NORDESTE_LITORAL} until this zone
     * existed, which is why Remo moves here in the same change.
     */
    NORTE("Norte equatorial");

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
