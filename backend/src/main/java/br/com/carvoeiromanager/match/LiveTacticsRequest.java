package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;

/** Request body for changing tactics during a live match. */
public record LiveTacticsRequest(
        @NotBlank String mentality,
        @NotBlank String pressing,
        @NotBlank String marking,
        @NotBlank String formation) {
}
