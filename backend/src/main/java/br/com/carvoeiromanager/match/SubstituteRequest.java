package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubstituteRequest(@NotNull UUID outPlayerId, @NotNull UUID inPlayerId) {
}
