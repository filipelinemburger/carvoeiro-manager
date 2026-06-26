package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<LineupStarter> starters) {
}
