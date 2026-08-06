package br.com.carvoeiromanager.seed;

import java.util.List;

/**
 * A single competition entry within a SeedCareer, carrying its name, type (e.g. "LEAGUE"),
 * season year, the shortNames of the clubs that participate in it, and its ordered phases.
 * A null or empty {@code phases} means a plain double round-robin league (pre-V47 behaviour).
 */
public record SeedCompetition(
        String name,
        String type,
        int seasonYear,
        List<String> memberShortNames,
        List<SeedPhase> phases
) {}
