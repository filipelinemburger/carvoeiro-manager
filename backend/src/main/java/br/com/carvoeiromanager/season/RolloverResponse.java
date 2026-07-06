package br.com.carvoeiromanager.season;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures
) {}
