package br.com.carvoeiromanager.board;

import java.util.List;

/** {@code GET /api/v1/save-games/{id}/board}: where the manager stands, and why (spec §9.1). */
public record BoardResponse(
        int confidence,
        boolean ultimatumActive,
        boolean sackingEnabled,
        boolean graceSeason,
        List<BoardObjectiveResponse> objectives,
        List<BoardChangeResponse> recentChanges) {
}
