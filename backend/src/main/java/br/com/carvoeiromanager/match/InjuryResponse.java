package br.com.carvoeiromanager.match;

import java.util.UUID;

public record InjuryResponse(UUID playerId, String playerName, int matches, String severity) {
}
