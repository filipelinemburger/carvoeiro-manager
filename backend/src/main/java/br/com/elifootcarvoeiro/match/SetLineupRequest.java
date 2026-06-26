package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<UUID> startingPlayerIds) {
}
