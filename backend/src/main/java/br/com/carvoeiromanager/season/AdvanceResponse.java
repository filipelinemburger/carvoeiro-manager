package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.contract.PreContractEventResponse;
import br.com.carvoeiromanager.finance.FinanceRoundResponse;
import br.com.carvoeiromanager.match.BookingResponse;
import br.com.carvoeiromanager.match.FixtureResultResponse;
import br.com.carvoeiromanager.match.InjuryResponse;
import br.com.carvoeiromanager.match.PlayerRating;
import br.com.carvoeiromanager.match.SuspensionResponse;
import br.com.carvoeiromanager.transfer.CompletedTransferResponse;
import br.com.carvoeiromanager.watchlist.PlayerAlertResponse;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

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
 *   <li>{@code TRAINING_REPORT} — only {@code date} and {@code trainingReport} are meaningful;
 *       persisted as its own news item when the weekly training tick produced notable events.</li>
 * </ul>
 * {@code playerRatings} holds the managed club's 0–10 performance grades and is populated only for
 * the user's own (live-played) match; it is empty for AI-simulated rounds, transfers and older
 * news items persisted before the feature existed.
 * {@code transfers} holds AI-to-AI trades completed during a TRANSFER event (empty for
 * MATCH/FINISHED, and deserialized as {@code null} — not empty — from news payloads persisted
 * before this field existed, so callers must null-guard it).
 * {@code trainingReport} is populated only on {@code TRAINING_REPORT} items (the Preparador
 * Físico's weekly digest); it deserializes as {@code null} from every older payload and from
 * MATCH/TRANSFER/FINISHED items — callers must null-guard it.
 * {@code preContractEvents} — pre-contract happenings on a TRANSFER day; null in payloads
 * persisted before the feature existed — callers must null-guard.
 * {@code playerAlerts} — watchlist alerts (sold/listed/free-agent/offer-received); null on
 * payloads persisted before the feature existed; null-guard it.
 * {@code matchPreview} — the Auxiliar Técnico's opponent analysis; only on MATCH_PREVIEW items,
 * null everywhere else and on payloads persisted before the feature existed — callers must
 * null-guard it.
 * {@code bidsReceived} and {@code seasonFinished} default to {@code 0}/{@code false} (rather
 * than failing deserialization) when absent from a payload, since the standalone WATCHLIST
 * news item (see {@code WatchlistNotifier}) is a sparse {type, date, playerAlerts} map that
 * carries neither field.
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
        @JsonSetter(nulls = Nulls.AS_EMPTY) int bidsReceived,
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean seasonFinished,
        List<PlayerRating> playerRatings,
        List<CompletedTransferResponse> transfers,
        TrainingReportResponse trainingReport,
        List<PreContractEventResponse> preContractEvents,
        List<PlayerAlertResponse> playerAlerts,
        MatchPreviewData matchPreview) {
}
