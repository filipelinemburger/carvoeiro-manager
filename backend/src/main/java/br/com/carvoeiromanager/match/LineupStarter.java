package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One selected player in a lineup request/response. {@code instruction} is the optional per-player
 * "instrução pessoal" (a {@link br.com.carvoeiromanager.player.PlayerInstruction} name, or null/blank
 * for none) — deliberately un-annotated so an absent value is accepted; the service validates it.
 */
public record LineupStarter(@NotNull UUID playerId, @NotBlank String role, String instruction) {

    /**
     * Convenience constructor for a starter with no personal instruction. Keeps the many
     * {@code (playerId, role)} call sites (tests) compiling unchanged; Jackson still binds JSON
     * by component name, so a client omitting {@code instruction} deserialises it as null.
     */
    public LineupStarter(UUID playerId, String role) {
        this(playerId, role, null);
    }
}
