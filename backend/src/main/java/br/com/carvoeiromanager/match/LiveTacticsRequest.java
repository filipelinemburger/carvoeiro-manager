package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;

/** Request body for changing tactics during a live match. */
public record LiveTacticsRequest(
        @NotBlank String mentality,
        // Optional (no @NotBlank): pre-GameStyle-UI clients omit it; null/blank means "keep current".
        String gameStyle,
        // Optional (no @NotBlank): pre-AttackZone-UI clients omit it; null/blank means "keep current".
        String attackZone,
        @NotBlank String pressing,
        @NotBlank String marking,
        @NotBlank String formation) {
}
