package br.com.carvoeiromanager.competition;

import java.util.UUID;

public record TopScorerRow(UUID playerId, String playerName, UUID clubId, String clubName, int goals) {}
