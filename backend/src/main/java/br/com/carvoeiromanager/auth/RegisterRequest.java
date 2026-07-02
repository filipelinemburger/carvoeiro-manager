package br.com.carvoeiromanager.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9]+$",
                message = "Username must contain only letters and numbers (no spaces or special characters)")
        String username,
        @NotBlank @Size(min = 6, max = 100) String password
) {}
