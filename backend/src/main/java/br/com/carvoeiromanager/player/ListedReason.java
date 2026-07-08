package br.com.carvoeiromanager.player;

/**
 * Why a player is currently transfer-listed. Set by the AI listing engine (AI clubs) or by the
 * managed user via the manual list/unlist endpoints ({@code OWNER}). Presentation labels
 * (pt-BR chip text) live on the frontend.
 */
public enum ListedReason {
    /** Morale has bottomed out (<= -3): the player wants out. */
    PLAYER_REQUEST,
    /** Barely getting minutes this season. */
    UNUSED,
    /** The club is DESPERATE and is cashing in its most valuable assets. */
    FINANCIAL,
    /** Squad depth floor: guarantees at least one listing so the market never runs dry. */
    SURPLUS,
    /** The managed user listed this player themselves. */
    OWNER
}
