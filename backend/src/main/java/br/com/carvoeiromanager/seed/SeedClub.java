package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedClub(
        String name,
        String shortName,
        String commonName,
        String nickname,
        String primaryColor,
        String secondaryColor,
        List<SeedPlayer> players
) {}
