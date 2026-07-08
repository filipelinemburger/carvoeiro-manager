package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedClub(
        String name,
        String shortName,
        String commonName,
        String nickname,
        String primaryColor,
        String secondaryColor,
        String formation,
        String mentality,
        String pressing,
        String marking,
        List<SeedPlayer> players
) {}
