package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.match.BookingResponse;
import br.com.elifootcarvoeiro.match.FixtureResultResponse;
import br.com.elifootcarvoeiro.match.InjuryResponse;
import br.com.elifootcarvoeiro.match.SuspensionResponse;

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
        boolean seasonFinished) {
}
