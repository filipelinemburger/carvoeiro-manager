package br.com.carvoeiromanager.match;

import java.util.UUID;

public record AttributedEvent(UUID playerId, EventType type, boolean home) {
}
