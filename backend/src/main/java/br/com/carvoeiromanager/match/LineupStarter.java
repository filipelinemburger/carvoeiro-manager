package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One selected player in a lineup request/response. {@code role} carries the
 * {@link br.com.carvoeiromanager.player.RoleArchetype} name (the field keeps its wire-compatible
 * name). {@code focus} is the optional {@link br.com.carvoeiromanager.player.RoleFocus} name — a
 * null/blank value means "the archetype's canonical default focus". {@code instruction} is the
 * optional per-player "instrução pessoal" (a {@link br.com.carvoeiromanager.player.PlayerInstruction}
 * name, or null/blank for none) — both are deliberately un-annotated so an absent value is accepted;
 * the service validates them. {@code slotIndex} is the explicit formation slot assignment.
 */
public record LineupStarter(@NotNull UUID playerId, @NotBlank String role, String focus,
                            String instruction, Integer slotIndex) {

    /**
     * Convenience constructor for a starter at the default focus, with no personal instruction and
     * no explicit slot. Keeps the many {@code (playerId, role)} call sites (tests) compiling unchanged.
     */
    public LineupStarter(UUID playerId, String role) {
        this(playerId, role, null, null, null);
    }

    /** Starter with an instruction but default focus and no explicit slot (pre-drag-and-drop call sites). */
    public LineupStarter(UUID playerId, String role, String instruction) {
        this(playerId, role, null, instruction, null);
    }
}
