package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.finance.FinanceService;
import br.com.elifootcarvoeiro.match.RoundOutcome;
import br.com.elifootcarvoeiro.match.FixtureSimulator;
import br.com.elifootcarvoeiro.transfer.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Date-driven season advance. Each {@link #advance} pops exactly one unprocessed
 * {@link SeasonEvent} (ordered by date then sequence), moves the save's current date
 * to it, and processes it:
 * <ul>
 *   <li>{@code MATCH} — simulate the round's fixtures via {@link FixtureSimulator}, charge
 *       the matchday finances, and award the season-end prize when no MATCH event remains
 *       (which also flips the save to {@code FINISHED}).</li>
 *   <li>{@code TRANSFER} — generate AI incoming bids for the date (window-gated inside
 *       {@link TransferService#generateIncomingOffers}).</li>
 * </ul>
 * Replaces the old round-based {@code RoundSimulationService}. Match determinism is
 * unchanged (the seed is keyed off {@code fixture.getRound()}); for the single
 * Catarinense competition every club is a member, so the per-matchday finance is
 * equivalent to the old per-round path.
 */
@Service
public class SeasonAdvanceService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;
    private final TransferService transferService;

    public SeasonAdvanceService(SaveGameService saveGameService,
                                SaveGameRepository saveGameRepository,
                                SeasonEventRepository seasonEventRepository,
                                FixtureRepository fixtureRepository,
                                FixtureSimulator fixtureSimulator,
                                FinanceService financeService,
                                TransferService transferService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.transferService = transferService;
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
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        save.setCurrentDate(event.getEventDate());

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

        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, competitionId, round);

        RoundOutcome outcome = fixtureSimulator.simulateRound(save, competitionId, fixtures);

        // Keep currentRound meaningful for offer round-stamping (offers stamp save.getCurrentRound()).
        save.setCurrentRound(round + 1);

        FinanceRoundResponse matchdayFinance =
                financeService.processMatchday(saveGameId, competitionId, fixtures);

        // The season finishes when this was the last unprocessed MATCH event.
        boolean seasonFinished = noMoreMatchEvents(saveGameId, event.getId());
        BigDecimal prize = BigDecimal.ZERO;
        if (seasonFinished) {
            save.setStatus(SaveGameStatus.FINISHED);
            prize = financeService.processSeasonEnd(saveGameId, competitionId);
        }

        FinanceRoundResponse finance = new FinanceRoundResponse(
                matchdayFinance.gate(), matchdayFinance.sponsorship(), matchdayFinance.wages(),
                matchdayFinance.upkeep(), prize, matchdayFinance.net(),
                matchdayFinance.balance().add(prize));

        return new AdvanceResponse("MATCH", event.getEventDate(), round,
                List.copyOf(outcome.results()), List.copyOf(outcome.bookings()),
                List.copyOf(outcome.newSuspensions()), finance, seasonFinished);
    }

    private AdvanceResponse processTransfer(SaveGame save, SeasonEvent event) {
        transferService.generateIncomingOffers(save.getId(), event.getEventDate());
        return new AdvanceResponse("TRANSFER", event.getEventDate(), null,
                List.of(), List.of(), List.of(), null, false);
    }

    /** True when no other unprocessed MATCH event remains besides the one just popped. */
    private boolean noMoreMatchEvents(UUID saveGameId, UUID currentEventId) {
        return seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId).stream()
                .filter(e -> !e.getId().equals(currentEventId))
                .noneMatch(e -> e.getType() == SeasonEventType.MATCH);
    }
}
