package br.com.carvoeiromanager.match;

/**
 * Match-day sky condition. {@code wetness} is the pitch-wetness the engine reads.
 *
 * <p>The API exposes this enum's {@code name()} on the wire, same as every other enum in this
 * codebase ({@code mentality().name()}, {@code e.type().name()}, ...). The pt-BR display string
 * lives client-side, in {@code WeatherChip.LABELS} — this enum has no display label of its own.
 */
public enum WeatherCondition {
    ENSOLARADO(0.0),
    NUBLADO(0.0),
    VENTANIA(0.0),
    CHUVA(0.5),
    CHUVA_FORTE(1.0);

    private final double wetness;

    WeatherCondition(double wetness) {
        this.wetness = wetness;
    }

    /** 0.0 dry, 0.5 light rain, 1.0 heavy rain. */
    public double wetness() { return wetness; }
}
