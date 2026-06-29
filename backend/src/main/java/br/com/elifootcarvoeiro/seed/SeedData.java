package br.com.elifootcarvoeiro.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedData(
        String competitionName,
        int seasonYear,
        List<SeedClub> clubs
) {}
