package br.com.carvoeiromanager.seed;

public record SeedPlayer(
        String name,
        String position,
        int age,
        String nationality,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        SeedAttributes attributes
) {}
