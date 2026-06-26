package br.com.elifootcarvoeiro.match;

/**
 * Enriched, engine-ready strength of one team for one match.
 * {@code fitnessDecay} is precomputed from pressing + average stamina; the engine
 * applies it per minute. {@code setPieceThreat} is ~[0,1].
 */
public record TeamMatchInput(
        double attack,
        double defense,
        double tempo,
        double aggression,
        double setPieceThreat,
        double fitnessDecay) {
}
