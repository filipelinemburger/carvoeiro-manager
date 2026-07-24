package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One selected player in a lineup request/response. {@code instruction} is the optional per-player
 * "instrução pessoal" (a {@link br.com.carvoeiromanager.player.PlayerInstruction} name, or null/blank
 * for none) — deliberately un-annotated so an absent value is accepted; the service validates it.
 * {@code slotIndex} is the explicit formation slot assignment (0 = GK slot).
 */
public record LineupStarter(@NotNull UUID playerId, @NotBlank String role, String instruction,
                            Integer slotIndex) {

    /**
     * Convenience constructor for a starter with no personal instruction and no explicit slot.
     * Keeps the many {@code (playerId, role)} call sites (tests) compiling unchanged.
     */
    public LineupStarter(UUID playerId, String role) {
        this(playerId, role, null, null);
    }

    /** Starter with an instruction but no explicit slot (pre-drag-and-drop call sites). */
    public LineupStarter(UUID playerId, String role, String instruction) {
        this(playerId, role, instruction, null);
    }
}
