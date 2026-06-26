package br.com.elifootcarvoeiro.competition;

import java.util.UUID;

public record TopScorerRow(UUID playerId, String playerName, UUID clubId, String clubName, int goals) {}
