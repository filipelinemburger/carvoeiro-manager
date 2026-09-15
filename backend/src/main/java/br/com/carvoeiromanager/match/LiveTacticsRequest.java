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
        @NotBlank String formation,
        // Optional: null means "no rearrangement" — every client from before live positioning.
        ArrangementRequest arrangement) {

    public LiveTacticsRequest(String mentality, String gameStyle, String attackZone,
                              String pressing, String marking, String formation) {
        this(mentality, gameStyle, attackZone, pressing, marking, formation, null);
    }
}
