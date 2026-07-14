package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One player's training assignment; {@code focus} is null to opt the player out of training. */
public record TrainingAssignment(@NotNull UUID playerId, String focus, @NotBlank String intensity) {
}
