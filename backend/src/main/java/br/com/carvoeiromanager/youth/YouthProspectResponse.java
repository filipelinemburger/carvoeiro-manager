package br.com.carvoeiromanager.youth;

import java.util.UUID;

public record YouthProspectResponse(
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
        int stamina,
        int potentialStars
) {}
