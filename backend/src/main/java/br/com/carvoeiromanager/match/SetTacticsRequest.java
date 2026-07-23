package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SetTacticsRequest(
        @NotBlank String mentality,
        // Optional (no @NotBlank): pre-GameStyle-UI clients omit it; a null/blank value leaves the
        // lineup's current game style unchanged rather than resetting it.
        String gameStyle,
        @NotBlank String pressing,
        @NotBlank String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
