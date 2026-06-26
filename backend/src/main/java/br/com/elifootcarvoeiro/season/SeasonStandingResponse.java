package br.com.elifootcarvoeiro.season;

public record SeasonStandingResponse(
        int position,
        String clubName,
        int played,
        int won,
        int drawn,
        int lost,
        int goalsFor,
        int goalsAgainst,
        int goalDifference,
        int points
) {}
