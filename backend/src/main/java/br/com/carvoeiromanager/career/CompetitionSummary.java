package br.com.carvoeiromanager.career;

import java.util.UUID;

/**
 * A lightweight summary of a competition included in the SaveGameResponse.
 *
 * <p>{@code hasPhases} tells the frontend whether this competition is phased (a round-robin
 * stage followed by knockout rounds, e.g. the Catarinense) or a plain league with no phases at
 * all (e.g. Série B) — only the former has anything for the phases/ties endpoints to return.
 */
public record CompetitionSummary(
        UUID id,
        String name,
        String type,
        int seasonYear,
        int totalRounds,
        boolean hasPhases
) {}
