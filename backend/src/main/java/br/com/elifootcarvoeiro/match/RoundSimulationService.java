package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.finance.FinanceService;
import br.com.elifootcarvoeiro.transfer.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a single-round simulation: resolves the current round, delegates
 * fixture simulation to {@link FixtureSimulator}, advances save-game state, and
 * triggers per-round finance and transfer side-effects.
 */
@Service
public class RoundSimulationService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;
    private final TransferService transferService;

    public RoundSimulationService(SaveGameService saveGameService,
                                  SaveGameRepository saveGameRepository,
                                  CompetitionRepository competitionRepository,
                                  FixtureRepository fixtureRepository,
                                  FixtureSimulator fixtureSimulator,
                                  FinanceService financeService,
                                  TransferService transferService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.transferService = transferService;
    }

    @Transactional
    public SimulateRoundResponse simulateNextRound(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        final Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        final int nextRound = saveGame.getCurrentRound();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), nextRound);

        RoundOutcome outcome = fixtureSimulator.simulateRound(saveGame, competition.getId(), fixtures);

        final int newCurrentRound = nextRound + 1;
        saveGame.setCurrentRound(newCurrentRound);
        if (newCurrentRound >= competition.getTotalRounds()) {
            saveGame.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(saveGame);

        FinanceRoundResponse roundFinance = financeService.processRound(saveGameId, nextRound, fixtures);
        BigDecimal prize = BigDecimal.ZERO;
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            prize = financeService.processSeasonEnd(saveGameId);
        }
        FinanceRoundResponse finance = new FinanceRoundResponse(
                roundFinance.gate(), roundFinance.sponsorship(), roundFinance.wages(),
                roundFinance.upkeep(), prize, roundFinance.net(), roundFinance.balance().add(prize));

        transferService.generateIncomingOffers(saveGameId, newCurrentRound);

        return new SimulateRoundResponse(newCurrentRound, List.copyOf(outcome.results()),
                saveGame.getStatus() == SaveGameStatus.FINISHED,
                List.copyOf(outcome.bookings()), List.copyOf(outcome.newSuspensions()),
                finance);
    }
}
