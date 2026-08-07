package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Builds the phase list for a competition, in bracket order. */
@Service
public class CompetitionPhaseService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final SeasonEventRepository seasonEventRepository;

    public CompetitionPhaseService(SaveGameService saveGameService,
                                   CompetitionRepository competitionRepository,
                                   CompetitionPhaseRepository competitionPhaseRepository,
                                   SeasonEventRepository seasonEventRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.seasonEventRepository = seasonEventRepository;
    }

    public List<PhaseResponse> getPhases(UUID saveGameId, UUID competitionId, UUID userId) {
        // Enforce save-game ownership before exposing any data.
        saveGameService.requireOwned(saveGameId, userId);

        // The competition must belong to this save game; otherwise treat as not found
        // (avoids leaking competitions from other save games).
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));

        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(saveGameId);
        return competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competition.getId()).stream()
                .map(phase -> toResponse(phase, events))
                .toList();
    }

    /**
     * A phase is complete once every one of its calendar events is processed. A phase with no
     * events yet (a knockout round not yet reached) is NOT complete — an empty stream's
     * {@code allMatch} would otherwise vacuously report true.
     */
    private PhaseResponse toResponse(CompetitionPhase phase, List<SeasonEvent> events) {
        List<SeasonEvent> phaseEvents = events.stream()
                .filter(e -> phase.getId().equals(e.getPhaseId()))
                .toList();
        boolean complete = !phaseEvents.isEmpty() && phaseEvents.stream().allMatch(SeasonEvent::isProcessed);

        return new PhaseResponse(
                phase.getId().toString(),
                phase.getOrdinal(),
                phase.getName(),
                phase.getKind().name(),
                phase.getLegs(),
                phase.getRounds(),
                phase.getQualifiers(),
                complete);
    }
}
