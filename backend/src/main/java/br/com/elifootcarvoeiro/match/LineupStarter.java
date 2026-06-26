package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LineupStarter(@NotNull UUID playerId, @NotBlank String role) {
}
