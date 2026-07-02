package br.com.carvoeiromanager.match;

public record LiveEventResponse(
        int minute,
        String type,
        boolean home,
        String playerId,
        String playerName,
        String playerInId,
        String playerInName,
        String severity,
        String text) {
}
