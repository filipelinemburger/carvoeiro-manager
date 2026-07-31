package br.com.carvoeiromanager.match;

/** Match-day sky condition. {@code wetness} is the pitch-wetness the engine reads. */
public enum WeatherCondition {
    ENSOLARADO("Ensolarado", 0.0),
    NUBLADO("Nublado", 0.0),
    VENTANIA("Ventania", 0.0),
    CHUVA("Chuva", 0.5),
    CHUVA_FORTE("Chuva forte", 1.0);

    private final String label;
    private final double wetness;

    WeatherCondition(String label, double wetness) {
        this.label = label;
        this.wetness = wetness;
    }

    public String label() { return label; }

    /** 0.0 dry, 0.5 light rain, 1.0 heavy rain. */
    public double wetness() { return wetness; }
}
