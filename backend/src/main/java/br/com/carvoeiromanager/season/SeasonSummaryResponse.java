package br.com.carvoeiromanager.season;

/**
 * One archived competition-year in the season history.
 *
 * @param managedClubPosition the player's finishing position, or {@code null} for a background
 *                             competition their club is not a member of. Clients render null as a
 *                             dash — it means "you were not in this competition", not "unknown".
 */
public record SeasonSummaryResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        Integer managedClubPosition,
        String topScorerName,
        int topScorerGoals
) {}
