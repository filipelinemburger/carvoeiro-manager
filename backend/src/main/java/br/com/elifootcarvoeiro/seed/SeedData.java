package br.com.elifootcarvoeiro.seed;

import java.util.List;

public record SeedData(
        String competitionName,
        int seasonYear,
        List<SeedClub> clubs
) {}
