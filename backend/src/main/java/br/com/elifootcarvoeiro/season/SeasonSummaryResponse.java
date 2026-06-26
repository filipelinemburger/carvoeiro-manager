package br.com.elifootcarvoeiro.season;

public record SeasonSummaryResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        int managedClubPosition,
        String topScorerName,
        int topScorerGoals
) {}
