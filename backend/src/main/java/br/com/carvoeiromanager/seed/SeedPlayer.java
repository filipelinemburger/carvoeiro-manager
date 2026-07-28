package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedPlayer(
        String name,
        String position,
        int age,
        String nationality,
        List<String> positions,
        List<String> roles,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        SeedAttributes attributes
) {}
