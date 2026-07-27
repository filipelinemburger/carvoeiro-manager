package br.com.carvoeiromanager.player;

/**
 * The EAFC focus dimension of a role (spec §2.2). Each focus carries a small attack/defense
 * "lean"; the engine multiplies a role's contributions by (1 + lean(focus) − lean(canonical
 * default focus)), so an archetype played at its canonical default focus is EXACTLY its base
 * profile — the determinism contract inherited from RoleBehaviour.
 */
public enum RoleFocus {
    DEFEND("Defesa", -0.08, 0.08),
    BALANCED("Equilibrado", 0.0, 0.0),
    ATTACK("Ataque", 0.08, -0.08),
    BUILD_UP("Construção", 0.03, -0.01),
    SUPPORT("Apoio", 0.04, -0.02),
    ROAMING("Livre", 0.05, -0.04),
    VERSATILE("Versátil", 0.02, 0.02),
    AGGRESSIVE("Agressivo", 0.0, 0.05),
    BALL_WINNING("Combativo", -0.03, 0.06),
    WIDE("Aberto", 0.02, -0.02);

    private final String label;
    private final double attackLean;
    private final double defenseLean;

    RoleFocus(String label, double attackLean, double defenseLean) {
        this.label = label;
        this.attackLean = attackLean;
        this.defenseLean = defenseLean;
    }

    public String label() { return label; }
    public double attackLean() { return attackLean; }
    public double defenseLean() { return defenseLean; }
}
