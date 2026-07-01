package br.com.carvoeiromanager.competition;

import java.util.UUID;

public record StandingRow(
        UUID clubId,
        String clubName,
        String commonName,
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
