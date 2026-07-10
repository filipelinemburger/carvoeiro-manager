package br.com.carvoeiromanager.match;

import java.util.UUID;

public record FixtureResultResponse(
        UUID fixtureId,
        UUID homeClubId,
        String homeClubName,
        String homeClubShortName,
        int homeGoals,
        UUID awayClubId,
        String awayClubName,
        String awayClubShortName,
        int awayGoals
) {
}
