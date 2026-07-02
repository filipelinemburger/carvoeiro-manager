package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<LineupStarter> starters,
        List<LineupStarter> bench) {

    /** Bench is optional; normalise null to an empty list for callers. */
    public List<LineupStarter> bench() {
        return bench == null ? List.of() : bench;
    }
}
