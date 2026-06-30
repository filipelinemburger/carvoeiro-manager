package br.com.carvoeiromanager.match;

import java.util.UUID;

public record SuspensionResponse(UUID playerId, String playerName, int matches) {
}
