package br.com.carvoeiromanager.career;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/v1/save-games. */
public record CreateSaveGameRequest(
        @NotBlank String managerName,
        @NotBlank String managerNationality
) {}
