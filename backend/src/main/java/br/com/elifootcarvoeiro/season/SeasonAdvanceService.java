package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.finance.FinanceService;
import br.com.elifootcarvoeiro.match.RoundOutcome;
import br.com.elifootcarvoeiro.match.FixtureSimulator;
import br.com.elifootcarvoeiro.transfer.TransferDirection;
import br.com.elifootcarvoeiro.transfer.TransferOfferRepository;
import br.com.elifootcarvoeiro.transfer.TransferOfferStatus;
import br.com.elifootcarvoeiro.transfer.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    public SeasonAdvanceService(SaveGameService saveGameService,
                                SaveGameRepository saveGameRepository,
                                SeasonEventRepository seasonEventRepository,
                                CompetitionRepository competitionRepository,
                                FixtureRepository fixtureRepository,
                                FixtureSimulator fixtureSimulator,
                                FinanceService financeService,
                                TransferService transferService,
                                TransferOfferRepository transferOfferRepository) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.transferService = transferService;
        this.transferOfferRepository = transferOfferRepository;
    }

    @Transactional
    public AdvanceResponse advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        if (save.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        SeasonEvent event = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        if (event == null) {
            // No calendar entries left to process: the season is over.
            save.setStatus(SaveGameStatus.FINISHED);
            saveGameRepository.save(save);
            return new AdvanceResponse("FINISHED", null, null, null,
                    List.of(), List.of(), List.of(), List.of(), null, 0, true);
        }

        LocalDate eventDate = event.getEventDate();
        save.setCurrentDate(eventDate);

        // Monthly finance: charge wages/sponsorship/upkeep once on first entry to each month.
        int eventMonth = eventDate.getYear() * 100 + eventDate.getMonthValue();
        if (eventMonth > save.getLastFinanceMonth()) {
            financeService.processMonth(saveGameId, eventDate);
            save.setLastFinanceMonth(eventMonth);
        }

        AdvanceResponse response = switch (event.getType()) {
            case MATCH -> processMatch(save, event);
            case TRANSFER -> processTransfer(save, event);
        };

        event.markProcessed();
        seasonEventRepository.save(event);
        saveGameRepository.save(save);
        return response;
    }

    private AdvanceResponse processMatch(SaveGame save, SeasonEvent event) {
        final UUID saveGameId = save.getId();
        final UUID competitionId = event.getCompetitionId();
        final int round = event.getRound();
        final LocalDate eventDate = event.getEventDate();

        String competitionName = competitionRepository.findById(competitionId)
                .map(c -> c.getName())
                .orElse(null);

        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, competitionId, round);

        RoundOutcome outcome = fixtureSimulator.simulateRound(save, competitionId, fixtures);

        // Keep currentRound meaningful for offer round-stamping (offers stamp save.getCurrentRound()).
        save.setCurrentRound(round + 1);

        FinanceRoundResponse matchdayFinance =
                financeService.processMatchday(saveGameId, competitionId, fixtures, eventDate);

        // The season finishes when this was the last unprocessed MATCH event.
        boolean seasonFinished = noMoreMatchEvents(saveGameId, event.getId());
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

        return new AdvanceResponse("MATCH", eventDate.toString(), competitionName, round,
                List.copyOf(outcome.results()), List.copyOf(outcome.bookings()),
                List.copyOf(outcome.newSuspensions()), List.copyOf(outcome.newInjuries()),
                finance, 0, seasonFinished);
    }

    private AdvanceResponse processTransfer(SaveGame save, SeasonEvent event) {
        int before = activeIncomingBids(save.getId());
        transferService.generateIncomingOffers(save.getId(), event.getEventDate());
        int bidsReceived = Math.max(0, activeIncomingBids(save.getId()) - before);
        return new AdvanceResponse("TRANSFER", event.getEventDate().toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, bidsReceived, false);
    }

    private int activeIncomingBids(UUID saveGameId) {
        return (int) transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING)
                .count();
    }

    /** True when no other unprocessed MATCH event remains besides the one just popped. */
    private boolean noMoreMatchEvents(UUID saveGameId, UUID currentEventId) {
        return seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId).stream()
                .filter(e -> !e.getId().equals(currentEventId))
                .noneMatch(e -> e.getType() == SeasonEventType.MATCH);
    }
}
