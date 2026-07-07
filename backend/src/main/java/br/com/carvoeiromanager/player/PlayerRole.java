package br.com.carvoeiromanager.player;

import java.util.Map;

/**
 * A tactical role bound to a position band, carrying the attribute weights that turn a
 * player's attributes into an attack and a defence contribution.
 *
 * <p>Weights are keyed by {@link Attribute} (absent = zero), so new attributes can enter the
 * scoring without shifting any index. The initial weights reproduce the previous 5-attribute
 * model exactly — the six anchor attributes carry the legacy values, so scoring is unchanged
 * until the weights are enriched.
 */
public enum PlayerRole {
    GOALKEEPER(Position.GK,
            Map.of(),
            Map.of(Attribute.REFLEXOS, 1.0)),
    STOPPER(Position.DEF,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.FINALIZACAO, 0.1),
            Map.of(Attribute.DESARME, 1.0)),
    FULL_BACK(Position.DEF,
            Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.2),
            Map.of(Attribute.DESARME, 0.8)),
    BALL_PLAYING_DEF(Position.DEF,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.PASSE, 0.4),
            Map.of(Attribute.DESARME, 0.85)),
    HOLDING_MID(Position.MID,
            Map.of(Attribute.PASSE, 0.3),
            Map.of(Attribute.DESARME, 0.8)),
    BOX_TO_BOX(Position.MID,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.3, Attribute.PASSE, 0.3),
            Map.of(Attribute.DESARME, 0.5)),
    PLAYMAKER(Position.MID,
            Map.of(Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.6),
            Map.of(Attribute.DESARME, 0.2)),
    POACHER(Position.ATT,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.8),
            Map.of()),
    TARGET_MAN(Position.ATT,
            Map.of(Attribute.FINALIZACAO, 0.6, Attribute.PASSE, 0.2),
            Map.of(Attribute.DESARME, 0.1)),
    WINGER(Position.ATT,
            Map.of(Attribute.VELOCIDADE, 0.6, Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.2),
            Map.of(Attribute.DESARME, 0.05));

    private final Position band;
    private final Map<Attribute, Double> attackWeights;
    private final Map<Attribute, Double> defenseWeights;

    PlayerRole(Position band, Map<Attribute, Double> attackWeights, Map<Attribute, Double> defenseWeights) {
        this.band = band;
        this.attackWeights = attackWeights;
        this.defenseWeights = defenseWeights;
    }

    public boolean isValidFor(Position position) { return band == position; }

    public Map<Attribute, Double> attackWeights() { return attackWeights; }

    public Map<Attribute, Double> defenseWeights() { return defenseWeights; }

    public double attackContribution(Player p) { return weighted(attackWeights, p.attributes()); }

    public double defenseContribution(Player p) { return weighted(defenseWeights, p.attributes()); }

    /** Weighted sum of the given attribute weights against a player's attribute profile. */
    public static double weighted(Map<Attribute, Double> weights, Attributes attrs) {
        double sum = 0.0;
        for (Map.Entry<Attribute, Double> e : weights.entrySet()) {
            sum += e.getValue() * attrs.get(e.getKey());
        }
        return sum;
    }

    public static PlayerRole defaultFor(Position position) {
        return switch (position) {
            case GK -> GOALKEEPER;
            case DEF -> FULL_BACK;
            case MID -> BOX_TO_BOX;
            case ATT -> POACHER;
        };
    }
}
