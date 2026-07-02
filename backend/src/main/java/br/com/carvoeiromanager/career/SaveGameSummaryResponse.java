package br.com.carvoeiromanager.career;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight career summary for the "Carregar jogo salvo" list. Cheap to build
 * (no competition/round assembly) — just enough to identify and choose a career.
 */
public record SaveGameSummaryResponse(
        UUID id,
        String managedClubName,
        int seasonYear,
        int currentRound,
        String currentDate,
        String status,
        Instant createdAt
) {}
