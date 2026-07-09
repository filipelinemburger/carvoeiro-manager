package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Shared set-piece math for both match engines (live and instant). Pure, stateless,
 * deterministic given the caller's RNG: {@link #roll} consumes exactly one draw.
 *
 * <p>Threats are ~[0,1] taker skills computed by {@code TeamStrengthCalculator}
 * (corner taker's Cruzamento, free-kick taker's Bola parada). Penalties carry a fixed
 * subtype weight — they come from fouls, not taker skill — and a conversion chance
 * derived from the penalty taker's Bola parada.
 */
public final class SetPieces {

    /** Which kind of set piece a set-piece scoring chance turned out to be. */
    public enum Kind { CORNER, FREE_KICK, PENALTY }

    static final double SET_PIECE_SHARE = 0.18;
    private static final double CORNER_BASE_WEIGHT = 50.0;
    private static final double FREE_KICK_BASE_WEIGHT = 30.0;
    private static final double PENALTY_WEIGHT = 20.0;
    private static final double CONVERSION_BASE = 0.70;
    private static final double CONVERSION_SKILL_SPAN = 0.25;

    private SetPieces() {
    }

    /** Chance that a scoring event is a set piece — same scale as the pre-split formula. */
    public static double share(double cornerThreat, double freeKickThreat) {
        double blended = (cornerThreat + freeKickThreat) / 2.0;
        return Math.max(0.0, Math.min(0.6, SET_PIECE_SHARE * (0.5 + blended)));
    }

    /** One RNG draw: subtype by effective weight — skilled takers shift the mix toward their type. */
    public static Kind roll(double cornerThreat, double freeKickThreat, Random rng) {
        double corner = CORNER_BASE_WEIGHT * (0.5 + cornerThreat);
        double freeKick = FREE_KICK_BASE_WEIGHT * (0.5 + freeKickThreat);
        double pick = rng.nextDouble() * (corner + freeKick + PENALTY_WEIGHT);
        if (pick < corner) return Kind.CORNER;
        if (pick < corner + freeKick) return Kind.FREE_KICK;
        return Kind.PENALTY;
    }

    /** Penalty conversion chance from the taker's Bola parada: [0.70, 0.95], ~0.82 average. */
    public static double conversionChance(int bolaParada) {
        return CONVERSION_BASE + CONVERSION_SKILL_SPAN * (bolaParada / 99.0);
    }

    /**
     * Corner-scorer weight: headers. Centre-backs and strikers rise for corners; keepers never
     * score. The {@code + 10} floor keeps a poor header able to score occasionally.
     */
    public static int headerWeight(Player p) {
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 10;
            case DEF -> 8;
            case MID -> 4;
            case GK -> 0;
        };
        return positionFactor * (p.attribute(Attribute.CABECEIO) + 10);
    }

    /**
     * The designated taker when present in the pool; otherwise the FIRST player in pool order
     * with the maximal fallback attribute, skipping goalkeepers (a keeper is used only when the
     * pool is all keepers). A designated keeper taker id IS honored — that was the user's choice.
     * Deterministic, no RNG.
     */
    public static Player takerOrFallback(List<Player> pool, UUID takerId,
                                         ToIntFunction<Player> fallbackAttribute) {
        if (takerId != null) {
            for (Player p : pool) {
                if (p.getId().equals(takerId)) return p;
            }
        }
        Player best = null;
        for (Player p : pool) {
            if (p.getPosition() == Position.GK) continue;
            if (best == null || fallbackAttribute.applyAsInt(p) > fallbackAttribute.applyAsInt(best)) {
                best = p;
            }
        }
        return best != null ? best : pool.get(0);
    }
}
