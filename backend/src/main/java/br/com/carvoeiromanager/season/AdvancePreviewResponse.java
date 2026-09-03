package br.com.carvoeiromanager.season;

import java.util.List;

/**
 * Look-ahead at the next season advance, so the UI can decide whether the "Avançar" button
 * should be disabled before the user clicks it.
 *
 * <ul>
 *   <li>{@code nextEventType} — {@code MATCH}, {@code TRANSFER} or {@code FINISHED} (no events left).</li>
 *   <li>{@code managedClubPlaysNext} — true when the next event is a MATCH round in which the
 *       managed club has a fixture. {@code ineligibleStarters} is only populated in that case.</li>
 *   <li>{@code ineligibleStarters} — players in the saved starting XI who cannot play the next
 *       match: suspended, injured, or on loan and not released to face this particular opponent.
 *       Non-empty here means advancing would start a match with an ineligible starter selected
 *       (UI blocks the advance — the manager must fix the lineup first).</li>
 *   <li>{@code windowClosingAfterAdvance} — true when the transfer window is open now but the next
 *       event lands outside it (this advance closes the window).</li>
 *   <li>{@code pendingTransfers} — count of unresolved offers (PENDING/COUNTERED) involving the
 *       managed club. Combined with {@code windowClosingAfterAdvance}, the UI blocks the advance so
 *       the manager resolves bids before the window shuts.</li>
 *   <li>{@code defaultStandingsCompetitionId} — the competition of the most recently played match,
 *       or (if none played yet) the next scheduled match; the Classificação screen defaults to it.</li>
 * </ul>
 */
public record AdvancePreviewResponse(
        String nextEventType,
        boolean managedClubPlaysNext,
        List<IneligibleStarterResponse> ineligibleStarters,
        boolean windowClosingAfterAdvance,
        int pendingTransfers,
        String defaultStandingsCompetitionId,
        List<LowFitnessStarterResponse> lowFitnessStarters) {

    /**
     * A saved starter who cannot play the next match. {@code reason} is {@code SUSPENDED},
     * {@code INJURED} or {@code LOAN_BARRED} (on loan, not released to face his owner).
     */
    public record IneligibleStarterResponse(String playerId, String playerName, String reason) {
    }

    /** A saved starter below the fitness soft-warning threshold. Non-blocking. */
    public record LowFitnessStarterResponse(String playerId, String playerName, int fitness) {
    }
}
