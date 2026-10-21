package br.com.carvoeiromanager.career;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/save-games. {@code sackingEnabled} is whether the board may end
 * the career; absent (null) means yes.
 */
public record CreateSaveGameRequest(
        @NotBlank String managerName,
        @NotBlank String managerNationality,
        Boolean sackingEnabled
) {
    public CreateSaveGameRequest(String managerName, String managerNationality) {
        this(managerName, managerNationality, null);
    }
}
