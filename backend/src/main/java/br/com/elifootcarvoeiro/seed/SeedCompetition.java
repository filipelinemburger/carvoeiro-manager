package br.com.elifootcarvoeiro.seed;

import java.util.List;

/**
 * A single competition entry within a SeedCareer, carrying its name, type (e.g. "LEAGUE"),
 * season year, and the shortNames of the clubs that participate in it.
 */
public record SeedCompetition(
        String name,
        String type,
        int seasonYear,
        List<String> memberShortNames
) {}
