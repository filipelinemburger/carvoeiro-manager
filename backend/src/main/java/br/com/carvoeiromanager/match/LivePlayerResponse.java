package br.com.carvoeiromanager.match;

public record LivePlayerResponse(
        String playerId,
        String name,
        String position,
        int overall,
        int fitness,
        boolean hobbled,
        boolean onPitch,
        boolean used,
        boolean eligible) {
}
