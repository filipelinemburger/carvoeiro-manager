package br.com.elifootcarvoeiro.career;

import java.util.List;
import java.util.UUID;

public record SaveGameResponse(
        UUID id,
        int seasonYear,
        UUID competitionId,
        String competitionName,
        UUID managedClubId,
        String managedClubName,
        int currentRound,
        int totalRounds,
        String currentDate,
        List<CompetitionSummary> competitions,
        String status,
        boolean transferWindowOpen
) {}
