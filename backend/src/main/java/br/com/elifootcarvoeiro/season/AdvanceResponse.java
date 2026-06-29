package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.match.BookingResponse;
import br.com.elifootcarvoeiro.match.FixtureResultResponse;
import br.com.elifootcarvoeiro.match.SuspensionResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * One step of the date-driven season advance. Exactly one calendar {@code season_event}
 * is processed per advance:
 * <ul>
 *   <li>{@code MATCH} — {@code round}, {@code results}, {@code bookings}, {@code newSuspensions}
 *       and {@code finance} are populated; {@code seasonFinished} is true on the last matchday.</li>
 *   <li>{@code TRANSFER} — only {@code type} and {@code date} are meaningful; results/finance are
 *       empty/null because no match is played, only AI incoming bids are generated.</li>
 * </ul>
 */
public record AdvanceResponse(
        String type,
        LocalDate date,
        Integer round,
        List<FixtureResultResponse> results,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions,
        FinanceRoundResponse finance,
        boolean seasonFinished
) {
}
