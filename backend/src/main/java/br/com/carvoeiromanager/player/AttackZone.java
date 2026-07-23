package br.com.carvoeiromanager.player;

/**
 * Where a team tries to force the play ("onde forçar o jogo"). Shapes who scores/assists an
 * open-play goal by re-weighting the attribution picks — {@link #LATERAIS} rewards crossers and
 * headers down the wings, {@link #CENTRO} rewards playmakers and long-shooters through the middle.
 *
 * <p>Determinism contract: {@link #MISTO} is the neutral default. Its scorer/assist weights are
 * exactly the pre-AttackZone formulas, so a squad on MISTO produces byte-identical attribution to
 * the pre-change engine.
 */
public enum AttackZone {
    LATERAIS("Pelos lados"),
    CENTRO("Pelo centro"),
    MISTO("Misto");

    private final String label;

    AttackZone(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
