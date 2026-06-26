package br.com.elifootcarvoeiro.player;

public enum Mentality {
    DEFENSIVE(0.88, 1.12),
    BALANCED(1.00, 1.00),
    ATTACKING(1.12, 0.90);

    private final double attackMult;
    private final double defenseMult;

    Mentality(double attackMult, double defenseMult) {
        this.attackMult = attackMult;
        this.defenseMult = defenseMult;
    }

    public double attackMult() { return attackMult; }
    public double defenseMult() { return defenseMult; }
}
