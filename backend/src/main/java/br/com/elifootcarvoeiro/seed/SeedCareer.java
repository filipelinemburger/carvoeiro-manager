package br.com.elifootcarvoeiro.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level structure of a career seed file.
 * Contains a pool of clubs (each with players) and a list of competitions
 * that reference clubs by shortName.
 * ignoreUnknown = true so legacy fields (competitionName, seasonYear, clubs) are silently skipped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedCareer(
        List<SeedClub> clubPool,
        List<SeedCompetition> competitions
) {}
