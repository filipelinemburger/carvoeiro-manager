package br.com.elifootcarvoeiro.competition;

import java.util.UUID;

public record StandingRow(
        UUID clubId,
        String clubName,
        int played,
        int won,
        int drawn,
        int lost,
        int goalsFor,
        int goalsAgainst,
        int goalDifference,
        int points
) {
}
