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
        int awayGoals,
        String stadium,
        Integer attendance,
        String weather,
        Integer temperatureC,
        /**
         * Shootout score, or null when the match was not decided on penalties. homeGoals/awayGoals
         * stay the 120-minute score, so without these a knockout tie won on penalties reads as a
         * draw everywhere outside the bracket -- result screen, news feed and calendar.
         */
        Integer penaltyHome,
        Integer penaltyAway
) {

    /** True when this match went to a shootout, i.e. both penalty scores are present. */
    public boolean decidedOnPenalties() {
        return penaltyHome != null && penaltyAway != null;
    }
}
