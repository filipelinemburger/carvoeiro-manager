package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.match.AdvancePreviewLowFitness;
import br.com.carvoeiromanager.season.AdvancePreviewResponse.IneligibleStarterResponse;
import br.com.carvoeiromanager.season.AdvancePreviewResponse.LowFitnessStarterResponse;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes a non-mutating look-ahead at the next {@link #preview} advance so the UI can decide
 * whether to disable the "Avançar" button. See {@link AdvancePreviewResponse} for field semantics.
 */
@Service
public class AdvancePreviewService {

    private final SaveGameService saveGameService;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final LineupService lineupService;
    private final TransferWindow transferWindow;
    private final TransferOfferRepository transferOfferRepository;

    public AdvancePreviewService(SaveGameService saveGameService,
                                 SeasonEventRepository seasonEventRepository,
                                 FixtureRepository fixtureRepository,
                                 LineupService lineupService,
                                 TransferWindow transferWindow,
                                 TransferOfferRepository transferOfferRepository) {
        this.saveGameService = saveGameService;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.lineupService = lineupService;
        this.transferWindow = transferWindow;
        this.transferOfferRepository = transferOfferRepository;
    }

    @Transactional(readOnly = true)
    public AdvancePreviewResponse preview(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        LocalDate currentDate = save.getCurrentDate();

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);

        String nextEventType = (next == null) ? "FINISHED" : next.getType().name();

        boolean managedClubPlaysNext = false;
        List<IneligibleStarterResponse> ineligibleStarters = List.of();
        List<LowFitnessStarterResponse> lowFitnessStarters = List.of();
        if (next != null && next.getType() == SeasonEventType.MATCH) {
            List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                    saveGameId, next.getCompetitionId(), next.getRound());
            managedClubPlaysNext = fixtures.stream().anyMatch(f ->
                    managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()));
            if (managedClubPlaysNext) {
                ineligibleStarters = lineupService.savedIneligibleStarters(saveGameId, managedClubId).stream()
                        .map(AdvancePreviewService::toIneligibleStarter)
                        .toList();
                lowFitnessStarters = lineupService.lowFitnessStarters(saveGameId, managedClubId, currentDate, 50).stream()
                        .map(r -> new LowFitnessStarterResponse(r.playerId(), r.playerName(), r.fitness()))
                        .toList();
            }
        }

        // The window "closes on this advance" when it is open now but the next event lands outside it.
        // currentDate is normally always set, but guard defensively (it is nullable on the entity).
        boolean windowClosingAfterAdvance = next != null
                && currentDate != null
                && transferWindow.isOpen(currentDate)
                && !transferWindow.isOpen(next.getEventDate());

        int pendingTransfers = transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size();

        UUID defaultStandingsCompetitionId = defaultStandingsCompetition(saveGameId);

        return new AdvancePreviewResponse(
                nextEventType,
                managedClubPlaysNext,
                ineligibleStarters,
                windowClosingAfterAdvance,
                pendingTransfers,
                defaultStandingsCompetitionId == null ? null : defaultStandingsCompetitionId.toString(),
                lowFitnessStarters);
    }

    private static IneligibleStarterResponse toIneligibleStarter(Player p) {
        String reason = p.getSuspensionRemaining() > 0 ? "SUSPENDED" : "INJURED";
        return new IneligibleStarterResponse(p.getId().toString(), p.getName(), reason);
    }

    /**
     * The competition the Classificação screen should default to: the most recently <em>played</em>
     * match's competition, or — if no match has been played yet — the next scheduled match's
     * competition. Null only when the save has no MATCH events at all.
     */
    private UUID defaultStandingsCompetition(UUID saveGameId) {
        List<SeasonEvent> matchEvents = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .toList();

        Comparator<SeasonEvent> byDateThenSeq =
                Comparator.comparing(SeasonEvent::getEventDate).thenComparingInt(SeasonEvent::getSequence);

        Optional<SeasonEvent> lastPlayed = matchEvents.stream()
                .filter(SeasonEvent::isProcessed)
                .max(byDateThenSeq);
        if (lastPlayed.isPresent()) {
            return lastPlayed.get().getCompetitionId();
        }

        return matchEvents.stream()
                .filter(e -> !e.isProcessed())
                .min(byDateThenSeq)
                .map(SeasonEvent::getCompetitionId)
                .orElse(null);
    }
}
