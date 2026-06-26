package br.com.elifootcarvoeiro.seed;

import java.util.List;

public record SeedClub(
        String name,
        String shortName,
        String nickname,
        String primaryColor,
        String secondaryColor,
        List<SeedPlayer> players
) {}
