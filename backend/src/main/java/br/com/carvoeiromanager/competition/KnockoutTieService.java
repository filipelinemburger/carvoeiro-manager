package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the tie list for one knockout phase, aggregate scores and winner included. */
@Service
public class KnockoutTieService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;

    public KnockoutTieService(SaveGameService saveGameService,
                              CompetitionRepository competitionRepository,
                              CompetitionPhaseRepository competitionPhaseRepository,
                              FixtureRepository fixtureRepository,
                              ClubRepository clubRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
    }

    public List<KnockoutTieResponse> getTies(UUID saveGameId, UUID competitionId, UUID phaseId, UUID userId) {
        // Enforce save-game ownership before exposing any data.
        saveGameService.requireOwned(saveGameId, userId);

        // The competition must belong to this save game; otherwise treat as not found
        // (avoids leaking competitions from other save games).
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));

        // A phase must belong to this competition — same guard StandingsService applies, for the
        // same reason: an unknown or foreign phase id would otherwise silently resolve to "no
        // fixtures" (an empty list) rather than a clear NOT_FOUND.
        boolean belongs = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(competition.getId()).stream()
                .anyMatch(p -> p.getId().equals(phaseId));
        if (!belongs) {
            throw new ApiException(ErrorCode.COMPETITION_NOT_FOUND,
                    "Phase not found in competition " + competition.getId());
        }

        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, phaseId);
        List<KnockoutTie> ties = TieResolver.tiesOf(fixtures);

        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));

        return ties.stream().map(tie -> toResponse(tie, clubById)).toList();
    }

    private KnockoutTieResponse toResponse(KnockoutTie tie, Map<UUID, Club> clubById) {
        Fixture first = tie.firstLeg();
        Fixture second = tie.secondLeg();

        // A tie whose legs are not both played must return nulls, not throw — this endpoint is
        // called mid-season when half the bracket is still unplayed.
        String winnerClubId = null;
        try {
            winnerClubId = TieResolver.winner(tie).toString();
        } catch (IllegalStateException notYetDecided) {
            // leave winnerClubId null
        }

        Club higher = clubById.get(tie.higherSeedClubId());
        Club lower = clubById.get(tie.lowerSeedClubId());

        return new KnockoutTieResponse(
                tie.tieId().toString(),
                first.getTieOrder(),
                tie.higherSeedClubId().toString(),
                higher != null ? higher.getName() : null,
                tie.lowerSeedClubId().toString(),
                lower != null ? lower.getName() : null,
                first.isPlayed() ? first.getHomeGoals() : null,
                first.isPlayed() ? first.getAwayGoals() : null,
                second != null && second.isPlayed() ? second.getHomeGoals() : null,
                second != null && second.isPlayed() ? second.getAwayGoals() : null,
                second != null ? second.getPenaltyHome() : null,
                second != null ? second.getPenaltyAway() : null,
                winnerClubId);
    }
}
