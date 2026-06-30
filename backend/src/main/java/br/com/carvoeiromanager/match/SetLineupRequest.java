package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<LineupStarter> starters) {
}
