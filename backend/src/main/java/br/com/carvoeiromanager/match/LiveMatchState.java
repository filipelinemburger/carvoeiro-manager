package br.com.carvoeiromanager.match;

import java.util.List;

public record LiveMatchState(
        String fixtureId,
        String competitionName,
        Integer round,
        String matchDate,
        String homeClubName,
        String awayClubName,
        boolean managedIsHome,
        int minute,
        int homeGoals,
        int awayGoals,
        String status,
        List<LiveEventResponse> events,
        List<LivePlayerResponse> onPitch,
        List<LivePlayerResponse> bench,
        int subsRemaining) {
}
