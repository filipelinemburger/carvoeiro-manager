package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record BookingResponse(UUID playerId, String playerName, String type) {
}
