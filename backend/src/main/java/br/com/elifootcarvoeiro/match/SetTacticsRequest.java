package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SetTacticsRequest(
        @NotBlank String mentality,
        @NotBlank String pressing,
        @NotBlank String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
