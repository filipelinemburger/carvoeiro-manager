package br.com.carvoeiromanager.match;

/**
 * Enriched, engine-ready strength of one team for one match.
 * {@code fitnessDecay} is precomputed from pressing + average stamina; the engine applies it per
 * minute. {@code cornerThreat}/{@code freeKickThreat} are ~[0,1] taker-skill threats (corner
 * taker's Cruzamento, free-kick taker's Bola parada). {@code penaltyConversion} is the
 * precomputed chance a penalty is scored, in [0.70, 0.95].
 */
public record TeamMatchInput(
        double attack,
        double defense,
        double tempo,
        double aggression,
        double cornerThreat,
        double freeKickThreat,
        double penaltyConversion,
        double fitnessDecay) {
}
