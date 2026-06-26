package br.com.elifootcarvoeiro.career;

import java.util.UUID;

public record SaveGameResponse(
        UUID id,
        int seasonYear,
        UUID managedClubId,
        String managedClubName,
        int currentRound,
        int totalRounds
) {}
