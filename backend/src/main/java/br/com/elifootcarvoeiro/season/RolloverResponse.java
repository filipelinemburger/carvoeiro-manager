package br.com.elifootcarvoeiro.season;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> newcomers,
        List<SquadChangeResponse> expiredContracts
) {}
