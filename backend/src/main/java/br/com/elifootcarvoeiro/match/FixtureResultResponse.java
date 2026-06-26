package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record FixtureResultResponse(
        UUID fixtureId,
        UUID homeClubId,
        String homeClubName,
        int homeGoals,
        UUID awayClubId,
        String awayClubName,
        int awayGoals
) {
}
