package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.finance.FinanceRoundResponse;
import br.com.carvoeiromanager.match.BookingResponse;
import br.com.carvoeiromanager.match.FixtureResultResponse;
import br.com.carvoeiromanager.match.InjuryResponse;
import br.com.carvoeiromanager.match.PlayerRating;
import br.com.carvoeiromanager.match.SuspensionResponse;

import java.util.List;

/**
 * One step of the date-driven season advance. Exactly one calendar {@code season_event}
 * is processed per advance:
 * <ul>
 *   <li>{@code MATCH} — {@code competitionName}, {@code round}, {@code results},
 *       {@code bookings}, {@code newSuspensions} and {@code finance} are populated;
 *       {@code seasonFinished} is true on the last matchday.</li>
 *   <li>{@code TRANSFER} — {@code date} and {@code bidsReceived} are meaningful; all
 *       match-related fields are empty/null because no match is played, only AI incoming
 *       bids are generated.</li>
 *   <li>{@code FINISHED} — all event-specific fields are null/zero/empty; the season is
 *       over with no calendar entries remaining.</li>
 * </ul>
 * {@code playerRatings} holds the managed club's 0–10 performance grades and is populated only for
 * the user's own (live-played) match; it is empty for AI-simulated rounds, transfers and older
 * news items persisted before the feature existed.
 */
public record AdvanceResponse(
        String type,
        String date,
        String competitionName,
        Integer round,
        List<FixtureResultResponse> results,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions,
        List<InjuryResponse> newInjuries,
        FinanceRoundResponse finance,
        int bidsReceived,
        boolean seasonFinished,
        List<PlayerRating> playerRatings) {
}
