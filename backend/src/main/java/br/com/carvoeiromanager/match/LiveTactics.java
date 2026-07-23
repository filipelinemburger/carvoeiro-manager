package br.com.carvoeiromanager.match;

/** The currently active tactical settings for the managed side in a live match. */
public record LiveTactics(
        String mentality,
        String pressing,
        String marking,
        String formation,
        String gameStyle,
        String attackZone) {
}
