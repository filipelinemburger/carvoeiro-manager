package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StandingsService {

    private final SaveGameService saveGameService;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final StandingsCalculator standingsCalculator;

    public StandingsService(SaveGameService saveGameService,
                             ClubRepository clubRepository,
                             CompetitionRepository competitionRepository,
                             CompetitionClubRepository competitionClubRepository,
                             FixtureRepository fixtureRepository,
                             CompetitionPhaseRepository competitionPhaseRepository) {
        this.saveGameService = saveGameService;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.standingsCalculator = new StandingsCalculator();
    }

    public List<StandingRow> getStandings(UUID saveGameId, UUID competitionId, UUID userId) {
        return getStandings(saveGameId, competitionId, null, userId);
    }

    /**
     * Same as {@link #getStandings(UUID, UUID, UUID)}, scoped to a single phase's fixtures.
     * A null {@code phaseId} means "the whole competition" — today's behaviour.
     */
    public List<StandingRow> getStandings(UUID saveGameId, UUID competitionId, UUID phaseId, UUID userId) {
        // Enforce save-game ownership before exposing any data.
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        return computeStandings(save, competitionId, phaseId, null);
    }

    /**
     * Same computation as {@link #getStandings}, without the ownership check — for internal
     * engine use where there is no authenticated user in context (e.g. match resolution needs a
     * club's league position while simulating a round).
     *
     * <p>Ownership is not re-checked here; instead the seam is narrowed at the type level. This
     * method takes an already-resolved {@link SaveGame} rather than a raw {@code saveGameId}, so a
     * controller cannot reach it with a merely-claimed, unverified id — the caller must already
     * hold a real {@code SaveGame} instance, which in practice means it went through
     * {@link #getStandings} / {@code SaveGameService.requireOwned} or an internal engine flow
     * (season advance, live-match finish) that resolved one earlier. That is a compiler-enforced
     * nudge, not a redundant ownership check — the only caller today ({@code MatchContextResolver})
     * is engine-internal and never receives a raw id from a request.
     *
     * @param asOfDate when non-null, only fixtures whose {@code matchDate} is strictly before this
     *                  date count toward the table — lets engine code read a club's position as it
     *                  stood BEFORE a given match, rather than that match's own just-recorded result
     *                  leaking into the table used to price its own attendance. Null means no bound
     *                  (today's full, current table — what {@link #getStandings} exposes).
     */
    public List<StandingRow> computeStandings(SaveGame save, UUID competitionId, LocalDate asOfDate) {
        return computeStandings(save, competitionId, null, asOfDate);
    }

    /**
     * Standings over a single phase's fixtures, or over the whole competition when
     * {@code phaseId} is null (a plain league, or a caller that wants the aggregate).
     * A knockout phase has no meaningful table; callers should render its ties instead —
     * that is Task 12's job, not this method's.
     *
     * @param phaseId when non-null, only fixtures belonging to this phase count toward the
     *                 table. Null means every fixture in the competition — today's behaviour.
     * @param asOfDate see {@link #computeStandings(SaveGame, UUID, LocalDate)}.
     */
    public List<StandingRow> computeStandings(SaveGame save, UUID competitionId, UUID phaseId,
                                               LocalDate asOfDate) {
        UUID saveGameId = save.getId();
        // The competition must belong to this save game; otherwise treat as not found
        // (avoids leaking competitions from other save games).
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));

        // A phase must belong to this competition. Without this, an unknown or foreign phase id
        // filters every fixture out and returns a full table with played=0 for everyone — a
        // silently wrong answer where the competition guard above would have raised NOT_FOUND.
        if (phaseId != null) {
            boolean belongs = competitionPhaseRepository
                    .findByCompetitionIdOrderByOrdinalAsc(competition.getId()).stream()
                    .anyMatch(p -> p.getId().equals(phaseId));
            if (!belongs) {
                throw new ApiException(ErrorCode.COMPETITION_NOT_FOUND,
                        "Phase not found in competition " + competition.getId());
            }
        }

        // Only include clubs that are members of this specific competition.
        Set<UUID> memberIds = competitionClubRepository.findByCompetitionId(competition.getId()).stream()
                .map(CompetitionClub::getClubId)
                .collect(Collectors.toSet());
        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> memberIds.contains(c.getId()))
                .toList();
        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(
                saveGameId, competition.getId());
        List<Fixture> scoped = phaseId == null ? allFixtures
                : allFixtures.stream().filter(f -> phaseId.equals(f.getPhaseId())).toList();
        List<Fixture> countedFixtures = asOfDate == null ? scoped
                : scoped.stream()
                        .filter(f -> f.getMatchDate() != null && f.getMatchDate().isBefore(asOfDate))
                        .toList();
        return standingsCalculator.compute(clubs, countedFixtures);
    }
}
