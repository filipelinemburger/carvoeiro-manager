package br.com.carvoeiromanager.career;

import java.util.UUID;

/**
 * A lightweight summary of a competition included in the SaveGameResponse.
 */
public record CompetitionSummary(
        UUID id,
        String name,
        String type,
        int seasonYear,
        int totalRounds
) {}
