package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.watchlist.PlayerAlertResponse;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures,
        List<SquadChangeResponse> preContractArrivals,
        List<SquadChangeResponse> preContractDepartures,
        List<PlayerAlertResponse> watchlistEvents
) {}
