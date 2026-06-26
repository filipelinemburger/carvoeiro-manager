package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record TacticsResponse(
        String mentality,
        String pressing,
        String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
