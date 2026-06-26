package br.com.elifootcarvoeiro.player;

public enum Marking {
    ZONAL(1.00, 0.00),
    MAN_TIGHT(1.05, 0.50);

    private final double defenseMult;
    private final double aggressionAdd;

    Marking(double defenseMult, double aggressionAdd) {
        this.defenseMult = defenseMult;
        this.aggressionAdd = aggressionAdd;
    }

    public double defenseMult() { return defenseMult; }
    public double aggressionAdd() { return aggressionAdd; }
}
