package br.com.carvoeiromanager.player;

public enum Pressing {
    LOW(0.90, 0.00, 0.85),
    MEDIUM(1.00, 0.00, 1.00),
    HIGH(1.12, 0.50, 1.25);

    private final double tempoMult;
    private final double aggressionAdd;
    private final double decayMult;

    Pressing(double tempoMult, double aggressionAdd, double decayMult) {
        this.tempoMult = tempoMult;
        this.aggressionAdd = aggressionAdd;
        this.decayMult = decayMult;
    }

    public double tempoMult() { return tempoMult; }
    public double aggressionAdd() { return aggressionAdd; }
    public double decayMult() { return decayMult; }
}
