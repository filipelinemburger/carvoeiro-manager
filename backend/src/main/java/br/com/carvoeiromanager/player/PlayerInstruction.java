package br.com.carvoeiromanager.player;

/**
 * An optional per-player instruction ("instrução pessoal") layered on top of a player's tactical
 * role. Each instruction shifts engine weights/multipliers for that one player — never adding,
 * removing or reordering any RNG draw — so a squad with NO instructions set (the empty instructions
 * map, always the case for AI clubs and unset players) produces byte-identical results to the
 * pre-instruction engine.
 *
 * <p>Effects, applied identically in {@code LiveMatchEngine} and {@code FixtureSimulator}
 * (attribution) and {@code TeamStrengthCalculator} (strength):
 * <ul>
 *   <li>{@link #INFILTRAR_NA_AREA} — open-play scorer weight ×1.5.</li>
 *   <li>{@link #CHUTAR_DE_LONGE} — a midfielder's open-play scorer position-factor rises 6→8, then
 *       the scorer weight is ×1.25.</li>
 *   <li>{@link #CRUZAR_MAIS} — open-play assist weight ×1.5.</li>
 *   <li>{@link #SEGURAR_POSICAO} — strength attack contribution ×0.85, defense contribution ×1.10.</li>
 *   <li>{@link #MARCACAO_DURA} — strength defense contribution ×1.08, and card + foul weight ×1.5.</li>
 *   <li>{@link #DRIBLAR_MAIS} — intentionally ENGINE-NEUTRAL (see below).</li>
 * </ul>
 *
 * <p><b>{@link #DRIBLAR_MAIS} is deliberately inert.</b> Its intended effect is "fouled / penalty-won
 * attribution weight ×1.5", but the engine has no fouled-player or penalty-won-player attribution
 * (FOUL events attribute the FOULER; penalties attribute the TAKER). Adding such an attribution would
 * require a NEW RNG draw, which violates the zero-new-draws determinism doctrine. So DRIBLAR_MAIS
 * persists and is a valid, selectable instruction but currently changes no weights. It stays inert
 * pending a future fouled/penalty-won attribution feature.
 */
public enum PlayerInstruction {
    INFILTRAR_NA_AREA("Infiltrar na área"),
    CHUTAR_DE_LONGE("Chutar de longe"),
    CRUZAR_MAIS("Cruzar mais"),
    SEGURAR_POSICAO("Segurar posição"),
    MARCACAO_DURA("Marcação dura"),
    /** Engine-neutral: persists but changes no weights — see the class javadoc for why. */
    DRIBLAR_MAIS("Driblar mais");

    private final String label;

    PlayerInstruction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
