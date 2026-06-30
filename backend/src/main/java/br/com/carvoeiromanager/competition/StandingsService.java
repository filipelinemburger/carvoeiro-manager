package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
    private final StandingsCalculator standingsCalculator;

    public StandingsService(SaveGameService saveGameService,
                             ClubRepository clubRepository,
                             CompetitionRepository competitionRepository,
                             CompetitionClubRepository competitionClubRepository,
                             FixtureRepository fixtureRepository) {
        this.saveGameService = saveGameService;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.standingsCalculator = new StandingsCalculator();
    }

    public List<StandingRow> getStandings(UUID saveGameId, UUID competitionId, UUID userId) {
        // Enforce save-game ownership before exposing any data.
        saveGameService.requireOwned(saveGameId, userId);

        // The competition must belong to this save game; otherwise treat as not found
        // (avoids leaking competitions from other save games).
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(
                        "COMPETITION_NOT_FOUND",
                        "Competition not found",
                        HttpStatus.NOT_FOUND));

        // Only include clubs that are members of this specific competition.
        Set<UUID> memberIds = competitionClubRepository.findByCompetitionId(competition.getId()).stream()
                .map(CompetitionClub::getClubId)
                .collect(Collectors.toSet());
        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> memberIds.contains(c.getId()))
                .toList();
        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(
                saveGameId, competition.getId());
        return standingsCalculator.compute(clubs, allFixtures);
    }
}
