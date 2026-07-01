package br.com.carvoeiromanager.match;

/**
 * Carrier record returned by {@link LineupService#lowFitnessStarters} to the season layer.
 * Kept in the {@code match} package so the {@code season} package can import it without
 * introducing a {@code match → season} cycle (season already depends on match).
 */
public record AdvancePreviewLowFitness(String playerId, String playerName, int fitness) {
}
