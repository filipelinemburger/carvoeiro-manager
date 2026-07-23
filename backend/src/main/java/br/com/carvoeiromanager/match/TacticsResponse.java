package br.com.carvoeiromanager.match;

import java.util.UUID;

public record TacticsResponse(
        String mentality,
        String gameStyle,
        String pressing,
        String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
