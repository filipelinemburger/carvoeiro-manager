package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;

/**
 * Enriched, engine-ready strength of one team for one match.
 * {@code fitnessDecay} is precomputed from pressing + average stamina; the engine applies it per
 * minute. {@code cornerThreat}/{@code freeKickThreat} are ~[0,1] taker-skill threats (corner
 * taker's Cruzamento, free-kick taker's Bola parada). {@code penaltyConversion} is the
 * precomputed chance a penalty is scored, in [0.70, 0.95]. {@code mentality} feeds
 * {@link MatchupModifiers} to reward/punish an attack/defense mismatch against the opponent;
 * {@code gameStyle} feeds the same modifier for the counter-attack-vs-attacking bonus.
 */
public record TeamMatchInput(
        double attack,
        double defense,
        double tempo,
        double aggression,
        double cornerThreat,
        double freeKickThreat,
        double penaltyConversion,
        double fitnessDecay,
        Mentality mentality,
        GameStyle gameStyle) {

    /**
     * Scales attack and defense by the head coach's influence. Neutrality at {@code m == 1.0} is
     * guaranteed by IEEE-754 alone ({@code x * 1.0 == x} bit-exactly), which is what keeps every
     * seeded simulation result stable for clubs with no head coach. The early return is therefore
     * an allocation you don't pay for, not the thing that makes determinism hold — but it IS
     * contractual: callers may rely on getting the same instance back.
     */
    public TeamMatchInput withCoachMultiplier(double m) {
        if (m == 1.0) {
            return this;
        }
        return new TeamMatchInput(attack * m, defense * m, tempo, aggression, cornerThreat,
                freeKickThreat, penaltyConversion, fitnessDecay, mentality, gameStyle);
    }
}
