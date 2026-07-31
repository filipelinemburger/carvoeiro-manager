package br.com.carvoeiromanager.season;

import java.util.List;

/**
 * The Auxiliar Técnico's pre-match opponent analysis, carried by a {@code MATCH_PREVIEW}
 * news item dated the eve of the fixture. Sections beyond the standings/form basics are
 * present only when the auxiliar's attributes unlock them (server-side depth gating):
 * {@code bestPlayers} size and {@code topScorer} depend on Avaliação de Jogadores;
 * {@code tacticalRead} and {@code insights} depend on Conhecimento Tático — absent
 * sections are {@code null} (or a shorter list) and the frontend renders only what exists.
 * {@code weather}, {@code temperatureC}, and {@code weatherAdvice} are null when the
 * fixture's forecast is not yet revealed (see {@code WeatherService}'s reveal rule).
 */
public record MatchPreviewData(
        String fixtureId,
        int round,
        String matchDate,
        boolean home,
        String stadiumName,
        String weather,
        Integer temperatureC,
        String weatherAdvice,
        String opponentClubId,
        String opponentName,
        String opponentShortName,
        String assistantName,
        StandingSnapshot opponentStanding,
        StandingSnapshot managedStanding,
        List<String> form,
        List<PreviewPlayer> bestPlayers,
        PreviewTopScorer topScorer,
        TacticalRead tacticalRead,
        List<String> insights) {

    /** One club's line in the standings comparison; {@code position} is 1-based. */
    public record StandingSnapshot(int position, int points, int played, int won, int drawn,
                                   int lost, int goalsFor, int goalsAgainst) {
    }

    public record PreviewPlayer(String name, String position, int overall) {
    }

    public record PreviewTopScorer(String name, int goals) {
    }

    public record TacticalRead(String formation, String mentality, String pressing, String marking) {
    }
}
