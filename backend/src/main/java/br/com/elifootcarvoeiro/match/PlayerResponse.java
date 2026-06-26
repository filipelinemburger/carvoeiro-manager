package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record PlayerResponse(
        UUID id,
        String name,
        String position,
        int age,
        int overall,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina
) {
}
