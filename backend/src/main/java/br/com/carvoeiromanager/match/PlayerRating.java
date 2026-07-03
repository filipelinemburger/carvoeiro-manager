package br.com.carvoeiromanager.match;

/**
 * A single player's 0–10 performance grade for one finished match. Purely derived from the
 * match timeline (goals, cards, minutes, team result / clean sheet) — see
 * {@link PlayerRatingCalculator}. Surfaced on the live full-time screen
 * ({@link LiveMatchState#playerRatings()}) and in the persisted post-match news summary
 * ({@code AdvanceResponse.playerRatings()}).
 */
public record PlayerRating(
        String playerId,
        String name,
        String position,
        int minutesPlayed,
        int goals,
        double rating) {
}
