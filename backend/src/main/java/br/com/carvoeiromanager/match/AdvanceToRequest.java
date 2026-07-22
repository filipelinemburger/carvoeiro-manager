package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;

/** Request body for skipping the live match forward to a checkpoint ({@code HALF_TIME}/{@code FULL_TIME}). */
public record AdvanceToRequest(@NotBlank String target) {
}
