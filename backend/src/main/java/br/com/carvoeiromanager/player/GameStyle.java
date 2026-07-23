package br.com.carvoeiromanager.player;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A team's playing style (estilo de jogo). Each style carries flat attack/defense/decay
 * multipliers plus a "squad fit" bonus that scales attack (or defense) up when the outfield XI's
 * raw attributes suit the style, and down when they don't.
 *
 * <p>Determinism contract: {@link #EQUILIBRADO} is the neutral no-op. Its attack/defense
 * multipliers are exactly {@code 1.0} for any fit (fit-scale is {@code 0.0}), its decay multiplier
 * is {@code 1.0}, and {@link #fit(List)} returns {@code 0.0} for any XI — so a squad on the neutral
 * style produces byte-identical strength to the pre-GameStyle engine. The fit-attribute weights are
 * stored in an {@link EnumMap}, so the squad-fit summation always runs in enum-declaration order and
 * is reproducible run-to-run.
 */
public enum GameStyle {
    EQUILIBRADO("Equilibrado", 1.00, 1.00, 1.00, 0.00, 0.00, Map.of()),
    JOGO_DE_PASSES("Jogo de passes", 1.03, 0.99, 1.00, 0.05, 0.00,
            Map.of(Attribute.PASSE, 0.5, Attribute.VISAO, 0.3, Attribute.COMPOSTURA, 0.2)),
    CONTRA_ATAQUE("Contra-ataque", 0.99, 1.02, 1.00, 0.05, 0.00,
            Map.of(Attribute.VELOCIDADE, 0.4, Attribute.ACELERACAO, 0.3, Attribute.FINALIZACAO, 0.3)),
    JOGO_DIRETO("Jogo direto", 1.02, 1.00, 1.00, 0.05, 0.00,
            Map.of(Attribute.FORCA, 0.35, Attribute.CABECEIO, 0.35, Attribute.CHUTE_LONGE, 0.30)),
    PRESSAO_ALTA("Pressão alta", 1.02, 1.02, 1.15, 0.00, 0.05,
            Map.of(Attribute.RESISTENCIA, 0.4, Attribute.MARCACAO, 0.3, Attribute.DESARME, 0.3)),
    RETRANCA("Retranca", 0.94, 1.05, 1.00, 0.00, 0.05,
            Map.of(Attribute.MARCACAO, 0.35, Attribute.POSICIONAMENTO, 0.35, Attribute.CONCENTRACAO, 0.30));

    /** Raw-attribute average that maps to fit 0; the span below scales to the [-1, 1] clamp. */
    private static final double FIT_MIDPOINT = 58.0;
    private static final double FIT_SPAN = 20.0;

    private final String label;
    private final double baseAttack;
    private final double baseDefense;
    private final double decayMult;
    private final double attackFitScale;
    private final double defenseFitScale;
    private final Map<Attribute, Double> fitAttributes;

    GameStyle(String label, double baseAttack, double baseDefense, double decayMult,
              double attackFitScale, double defenseFitScale, Map<Attribute, Double> fitAttributes) {
        this.label = label;
        this.baseAttack = baseAttack;
        this.baseDefense = baseDefense;
        this.decayMult = decayMult;
        this.attackFitScale = attackFitScale;
        this.defenseFitScale = defenseFitScale;
        // EnumMap keeps a stable, enum-ordinal iteration order so the fit summation is reproducible.
        this.fitAttributes = fitAttributes.isEmpty() ? Map.of() : new EnumMap<>(fitAttributes);
    }

    public String label() {
        return label;
    }

    /**
     * The squad's fit to this style, in [-1, 1]. The caller MUST pass ONLY the outfield players
     * (goalkeeper excluded) — the fit attributes are outfield-oriented and averaging a GK in would
     * distort the result.
     *
     * <p>For each outfield player the weighted raw-attribute score is
     * {@code Σ weight_i · p.attribute(attr_i)} using {@link Player#attribute(Attribute)} — the base
     * value BEFORE any form/morale/fitness multiplier. Those scores are averaged across the players
     * and mapped {@code (avg − 58) / 20}, clamped to [-1, 1]: avg 58 → 0, 78 → +1, 38 → −1, and any
     * average below 58 yields a negative malus. {@link #EQUILIBRADO} (empty weight map) and an empty
     * XI both return {@code 0.0}, so the neutral style never divides by an empty weight set or NaNs.
     */
    public double fit(List<Player> outfieldXi) {
        if (fitAttributes.isEmpty() || outfieldXi == null || outfieldXi.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Player p : outfieldXi) {
            double weightedAttr = 0.0;
            for (Map.Entry<Attribute, Double> weight : fitAttributes.entrySet()) {
                weightedAttr += weight.getValue() * p.attribute(weight.getKey());
            }
            sum += weightedAttr;
        }
        double avg = sum / outfieldXi.size();
        return clamp((avg - FIT_MIDPOINT) / FIT_SPAN, -1.0, 1.0);
    }

    /** Attack multiplier for the given squad fit: {@code baseAttack + attackFitScale · fit}. */
    public double attackMult(double fit) {
        return baseAttack + attackFitScale * fit;
    }

    /** Defense multiplier for the given squad fit: {@code baseDefense + defenseFitScale · fit}. */
    public double defenseMult(double fit) {
        return baseDefense + defenseFitScale * fit;
    }

    /** Flat fitness-decay multiplier (independent of squad fit). */
    public double decayMult() {
        return decayMult;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
