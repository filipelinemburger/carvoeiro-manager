package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.contract.PreContractEventResponse;
import br.com.carvoeiromanager.contract.PreContractService;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.PhaseResolver;
import br.com.carvoeiromanager.finance.FinanceRoundResponse;
import br.com.carvoeiromanager.finance.FinanceService;
import br.com.carvoeiromanager.match.FixtureResultResponse;
import br.com.carvoeiromanager.match.FixtureSimulator;
import br.com.carvoeiromanager.match.LiveMatchRepository;
import br.com.carvoeiromanager.match.MatchOutcomeApplier;
import br.com.carvoeiromanager.match.PlayerRating;
import br.com.carvoeiromanager.match.PrecomputedFixture;
import br.com.carvoeiromanager.match.RoundOutcome;
import br.com.carvoeiromanager.player.TrainingService;
import br.com.carvoeiromanager.player.TrainingWeekReport;
import br.com.carvoeiromanager.transfer.CompletedTransferResponse;
import br.com.carvoeiromanager.transfer.TransferDirection;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import br.com.carvoeiromanager.transfer.TransferService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Date-driven season advance. Each {@link #advance} pops exactly one unprocessed
 * {@link SeasonEvent} (ordered by date then sequence), moves the save's current date
 * to it, and processes it:
 * <ul>
 *   <li>{@code MATCH} — simulate the round's fixtures via {@link FixtureSimulator}, charge
 *       the matchday finances (gate + matchCost), and award the season-end prize when no
 *       MATCH event remains (which also flips the save to {@code FINISHED}).</li>
 *   <li>{@code TRANSFER} — generate AI incoming bids for the date (window-gated inside
 *       {@link TransferService#generateIncomingOffers}).</li>
 *   <li>{@code FINISHED} — returned when no calendar entries remain and the save is flipped
 *       to {@code FINISHED} (no active event guard already handled the previous last match).</li>
 * </ul>
 *
 * <h3>Monthly finance</h3>
 * Before processing each event, if the event's calendar month is later than
 * {@code save.lastFinanceMonth} (a YYYYMM int), {@link FinanceService#processMonth} is
 * called and {@code lastFinanceMonth} is updated. This guarantees wages, sponsorship, and
 * stadium upkeep are charged exactly once per in-game month regardless of how many events
 * fall within that month or across two interleaved competitions.
 *
 * <h3>Weekly training</h3>
 * Likewise, before processing each event the weekly training tick runs once per in-game ISO
 * week ({@code save.lastTrainingWeek}, a YYYYWW int) via {@link TrainingService#processWeek};
 * when the managed club's Preparador Físico produced a report it is persisted as its own
 * {@code TRAINING_REPORT} news item.
 */
@Service
public class SeasonAdvanceService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;
    private final TransferService transferService;
    private final TransferOfferRepository transferOfferRepository;
    private final NewsItemRepository newsItemRepository;
    private final LiveMatchRepository liveMatchRepository;
    private final ObjectMapper objectMapper;
    private final TrainingService trainingService;
    private final PreContractService preContractService;
    private final MatchPreviewService matchPreviewService;
    private final PhaseResolver phaseResolver;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final BackgroundCompetitionService backgroundCompetitionService;

    public SeasonAdvanceService(SaveGameService saveGameService,
                                SaveGameRepository saveGameRepository,
                                SeasonEventRepository seasonEventRepository,
                                CompetitionRepository competitionRepository,
                                FixtureRepository fixtureRepository,
                                FixtureSimulator fixtureSimulator,
                                FinanceService financeService,
                                TransferService transferService,
                                TransferOfferRepository transferOfferRepository,
                                NewsItemRepository newsItemRepository,
                                LiveMatchRepository liveMatchRepository,
                                ObjectMapper objectMapper,
                                TrainingService trainingService,
                                PreContractService preContractService,
                                MatchPreviewService matchPreviewService,
                                PhaseResolver phaseResolver,
                                CompetitionPhaseRepository competitionPhaseRepository,
                                BackgroundCompetitionService backgroundCompetitionService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.transferService = transferService;
        this.transferOfferRepository = transferOfferRepository;
        this.newsItemRepository = newsItemRepository;
        this.liveMatchRepository = liveMatchRepository;
        this.objectMapper = objectMapper;
        this.trainingService = trainingService;
        this.preContractService = preContractService;
        this.matchPreviewService = matchPreviewService;
        this.phaseResolver = phaseResolver;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.backgroundCompetitionService = backgroundCompetitionService;
    }

    @Transactional
    public AdvanceResponse advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        save.setLastPlayedAt(Instant.now());
        if (save.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException(ErrorCode.SEASON_FINISHED, "The season has already finished");
        }
        if (liveMatchRepository.findBySaveGameId(saveGameId).isPresent()) {
            throw new ApiException(ErrorCode.LIVE_MATCH_IN_PROGRESS, "Finish the live match before advancing");
        }

        // Scoped to the managed club: background rounds sit on the same queue but the player is
        // not present for them, so popping one here would report someone else's fixture as their
        // own match. They are drained silently by BackgroundCompetitionService instead.
        SeasonEvent event = seasonEventRepository
                .findNextManagedEvent(saveGameId, save.getManagedClubId());
        if (event == null) {
            // No calendar entries left to process: the season is over.
            save.setStatus(SaveGameStatus.FINISHED);
            saveGameRepository.save(save);
            return new AdvanceResponse("FINISHED", null, null, null,
                    List.of(), List.of(), List.of(), List.of(), null, 0, true, List.of(), List.of(), null,
                    List.of(), List.of(), null, null);
        }

        LocalDate eventDate = event.getEventDate();
        save.setCurrentDate(eventDate);

        // Monthly finance: charge wages/sponsorship/upkeep once per in-game month. processMonth
        // self-guards (no-op if this month was already charged) and advances lastFinanceMonth.
        // This assumes the calendar never leaves a >1-month gap with no events (SeasonCalendarGenerator
        // spaces events at most a few days apart), so no month is silently skipped.
        financeService.processMonth(saveGameId, eventDate);

        // Weekly training: tick every club's training once per in-game ISO week. processWeek
        // self-guards like processMonth; when the physio produced a report, persist it as its
        // own TRAINING_REPORT feed item (lower seq than the event's item → shows as older news).
        maybePersistTrainingReport(saveGameId, eventDate);

        AdvanceResponse response = switch (event.getType()) {
            case MATCH -> processMatch(save, event);
            case TRANSFER -> processTransfer(save, event);
        };

        event.markProcessed();
        seasonEventRepository.save(event);
        saveGameRepository.save(save);

        // Persist the advance outcome as a news item so the feed survives reloads.
        persistNewsItem(saveGameId, event.getEventDate(), response);

        // The next managed fixture's opponent analysis lands right after each advance,
        // dated the eve of that match (self-guarding: assistant + idempotency inside).
        matchPreviewService.maybePersistPreview(saveGameId);

        return response;
    }

    private AdvanceResponse processMatch(SaveGame save, SeasonEvent event) {
        final UUID saveGameId = save.getId();
        final UUID competitionId = event.getCompetitionId();
        final int round = event.getRound();
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, competitionId, round);
        RoundOutcome outcome = fixtureSimulator.simulateRound(save, competitionId, fixtures);
        return recordMatchRound(save, event, outcome, fixtures, List.of());
    }

    /**
     * Finalise a MATCH round whose fixtures are already simulated into {@code outcome}: bump the
     * round, charge matchday finances, award the season-end prize on the final matchday, and build
     * the response. Shared by the instant advance and the live-match finish. {@code playerRatings}
     * carries the managed club's 0–10 grades from a live-played match (empty for AI-simulated rounds).
     */
    AdvanceResponse recordMatchRound(SaveGame save, SeasonEvent event, RoundOutcome outcome,
                                     List<Fixture> fixtures, List<PlayerRating> playerRatings) {
        final UUID saveGameId = save.getId();
        final UUID competitionId = event.getCompetitionId();
        final int round = event.getRound();
        final LocalDate eventDate = event.getEventDate();

        String competitionName = competitionRepository.findById(competitionId)
                .map(c -> c.getName())
                .orElse(null);

        // Keep currentRound meaningful for offer round-stamping (offers stamp save.getCurrentRound()).
        save.setCurrentRound(round + 1);

        FinanceRoundResponse matchdayFinance =
                financeService.processMatchday(saveGameId, competitionId, fixtures, eventDate);

        // Catch the background world up to the date the player just reached. The return value is
        // deliberately ignored: background results never enter the advance response — the player
        // sees them in that competition's table, not in their own match feed.
        backgroundCompetitionService.resolveDue(save, eventDate);

        // If this round just finished a round-robin phase, materialize the knockout phase after
        // it (seeded from this phase's final standings) before the season-finished check below —
        // otherwise a newly materialized knockout's still-unprocessed events would be missed and
        // this round would incorrectly read as the season's last one.
        phaseResolver.resolveIfPhaseComplete(save, event);

        // The season finishes when this was the last unprocessed MATCH event the player is in.
        boolean seasonFinished = noMoreMatchEvents(saveGameId, event.getId(), save.getManagedClubId());
        BigDecimal prize = BigDecimal.ZERO;
        if (seasonFinished) {
            save.setStatus(SaveGameStatus.FINISHED);
            prize = financeService.processSeasonEnd(saveGameId, competitionId, eventDate);
        }

        // AdvanceResponse.finance is the per-match response (gate + matchCost + optional prize).
        // Monthly costs are NOT included — they appear on the Finances page grouped by month.
        FinanceRoundResponse finance = new FinanceRoundResponse(
                matchdayFinance.gate(), matchdayFinance.matchCost(),
                matchdayFinance.sponsorship(), matchdayFinance.wages(),
                matchdayFinance.upkeep(), prize,
                matchdayFinance.net(), matchdayFinance.balance().add(prize));

        String phaseName = resolvePhaseName(event, fixtures);

        return new AdvanceResponse("MATCH", eventDate.toString(), competitionName, round,
                List.copyOf(outcome.results()), List.copyOf(outcome.bookings()),
                List.copyOf(outcome.newSuspensions()), List.copyOf(outcome.newInjuries()),
                finance, 0, seasonFinished, List.copyOf(playerRatings), List.of(), null, List.of(),
                List.of(), null, phaseName);
    }

    /**
     * Formats the round's phase for display, e.g. {@code "Quartas de final — Volta"} for a
     * knockout leg or {@code "Primeira Fase"} for a round-robin round — so the UI can show that
     * instead of a bare "Rodada 12". Null for a plain (unphased) league round.
     */
    private String resolvePhaseName(SeasonEvent event, List<Fixture> fixtures) {
        UUID phaseId = event.getPhaseId();
        if (phaseId == null) {
            return null;
        }
        CompetitionPhase phase = competitionPhaseRepository.findById(phaseId).orElse(null);
        if (phase == null) {
            return null;
        }
        if (phase.getKind() != PhaseKind.KNOCKOUT) {
            return phase.getName();
        }
        Integer leg = fixtures.stream().map(Fixture::getLeg).filter(l -> l != null).findFirst().orElse(null);
        if (leg == null) {
            return phase.getName();
        }
        return phase.getName() + " — " + (leg == 1 ? "Ida" : "Volta");
    }

    /**
     * Called by the live-match finish: the user's fixture is already simulated (result + summary +
     * played ids); this simulates the rest of the round, merges, finalises, marks the event
     * processed and persists the news item.
     *
     * @param precomputedOthers the round's other fixtures' results (plus the exact XI captured at
     *                          kickoff — see {@link PrecomputedFixture}), pre-simulated at
     *                          live-match kickoff ({@code LiveMatchService#kickoff}); REPLAYED here
     *                          rather than re-simulated, keeping results (and attribution) identical
     *                          to what the manager saw while the match was in progress — including
     *                          across any player-state mutation (e.g. this method's own weekly
     *                          training tick, below) that happens between kickoff and finish. Empty
     *                          for in-flight matches created before this existed, which falls back
     *                          to the legacy recompute-at-finish path inside {@link FixtureSimulator}.
     */
    @Transactional
    public AdvanceResponse completeUserMatch(SaveGame save, FixtureResultResponse userResult,
                                             MatchOutcomeApplier.Summary userSummary,
                                             UUID userFixtureId, Set<UUID> userPlayedIds,
                                             List<PlayerRating> playerRatings,
                                             Map<UUID, PrecomputedFixture> precomputedOthers) {
        final UUID saveGameId = save.getId();
        SeasonEvent event = seasonEventRepository
                .findNextManagedEvent(saveGameId, save.getManagedClubId());
        final LocalDate eventDate = event.getEventDate();
        save.setCurrentDate(eventDate);
        financeService.processMonth(saveGameId, eventDate);
        maybePersistTrainingReport(saveGameId, eventDate);

        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, event.getCompetitionId(), event.getRound());
        List<Fixture> others = fixtures.stream().filter(f -> !f.getId().equals(userFixtureId)).toList();
        RoundOutcome othersOutcome = fixtureSimulator.simulateRound(save, event.getCompetitionId(),
                others, userPlayedIds, precomputedOthers);

        List<FixtureResultResponse> results = new ArrayList<>();
        results.add(userResult);
        results.addAll(othersOutcome.results());
        RoundOutcome merged = new RoundOutcome(
                results,
                concat(userSummary.bookings(), othersOutcome.bookings()),
                concat(userSummary.newSuspensions(), othersOutcome.newSuspensions()),
                concat(userSummary.newInjuries(), othersOutcome.newInjuries()));

        AdvanceResponse response = recordMatchRound(save, event, merged, fixtures, playerRatings);
        event.markProcessed();
        seasonEventRepository.save(event);
        saveGameRepository.save(save);
        persistNewsItem(saveGameId, eventDate, response);

        // The next managed fixture's opponent analysis lands right after each advance,
        // dated the eve of that match (self-guarding: assistant + idempotency inside).
        matchPreviewService.maybePersistPreview(saveGameId);

        return response;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private AdvanceResponse processTransfer(SaveGame save, SeasonEvent event) {
        int before = activeIncomingBids(save.getId());
        List<CompletedTransferResponse> transfers = transferService.processMarketDay(save.getId(), event.getEventDate());
        int bidsReceived = Math.max(0, activeIncomingBids(save.getId()) - before);
        List<PreContractEventResponse> preContractEvents =
                preContractService.processMarketDay(save, event.getEventDate());
        return new AdvanceResponse("TRANSFER", event.getEventDate().toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, bidsReceived, false,
                List.of(), transfers, null, preContractEvents, List.of(), null, null);
    }

    private int activeIncomingBids(UUID saveGameId) {
        return (int) transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING)
                .count();
    }

    /** Runs the weekly training tick and persists the physio's report (when any) as news. */
    private void maybePersistTrainingReport(UUID saveGameId, LocalDate eventDate) {
        TrainingWeekReport report = trainingService.processWeek(saveGameId, eventDate);
        if (report == null) {
            return;
        }
        TrainingReportResponse dto = new TrainingReportResponse(report.staffName(), report.quality(),
                report.entries().stream()
                        .map(e -> new TrainingReportEntry(e.playerName(), e.message()))
                        .toList());
        AdvanceResponse response = new AdvanceResponse("TRAINING_REPORT", eventDate.toString(),
                null, null, List.of(), List.of(), List.of(), List.of(), null, 0, false,
                List.of(), List.of(), dto, List.of(), List.of(), null, null);
        persistNewsItem(saveGameId, eventDate, response);
    }

    /** Serialize the response and write a news_item row (seq = count + 1). */
    private void persistNewsItem(UUID saveGameId, LocalDate eventDate, AdvanceResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, eventDate, response.type(), payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize news item", e);
        }
    }

    /**
     * True when no other unprocessed MATCH event of a competition the managed club plays in remains
     * besides the one just popped.
     *
     * <p>The season ends when the <em>player</em> has no matches left. Background competitions share
     * this queue, and holding the season open for their rounds would strand the player on a date
     * with nothing of their own to do. The calendar happens to pin the last background round on the
     * managed finale's date, so today an unscoped check would answer the same — but that is a
     * property of the dating, not of what "the season is over" means, and season completion must
     * not depend on it.
     */
    private boolean noMoreMatchEvents(UUID saveGameId, UUID currentEventId, UUID managedClubId) {
        return seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId).stream()
                .filter(e -> !e.getId().equals(currentEventId))
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .noneMatch(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), managedClubId));
    }
}
