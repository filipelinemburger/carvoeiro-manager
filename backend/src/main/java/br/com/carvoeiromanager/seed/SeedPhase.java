package br.com.carvoeiromanager.seed;

/**
 * One phase of a seeded competition. {@code kind} is "ROUND_ROBIN" or "KNOCKOUT";
 * {@code rounds} is how many match-units it occupies; {@code qualifiers} is how many
 * clubs advance out of a round-robin phase (null for a knockout phase).
 */
public record SeedPhase(
        String name,
        String kind,
        int legs,
        int rounds,
        Integer qualifiers
) {}
