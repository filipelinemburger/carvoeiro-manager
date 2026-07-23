package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedClub(
        String name,
        String shortName,
        String commonName,
        String nickname,
        String stadium,
        String primaryColor,
        String secondaryColor,
        String formation,
        String mentality,
        String pressing,
        String marking,
        // Optional hand-picked GameStyle name (deserialized by field name; absent → null, in which
        // case SaveGameService derives the style from the club's mentality).
        String style,
        List<SeedPlayer> players
) {}
