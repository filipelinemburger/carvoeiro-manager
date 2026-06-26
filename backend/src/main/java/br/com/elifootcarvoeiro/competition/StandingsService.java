package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StandingsService {

    private final SaveGameService saveGameService;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final StandingsCalculator standingsCalculator;

    public StandingsService(SaveGameService saveGameService,
                             ClubRepository clubRepository,
                             CompetitionRepository competitionRepository,
                             FixtureRepository fixtureRepository) {
        this.saveGameService = saveGameService;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
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

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(
                saveGameId, competition.getId());
        return standingsCalculator.compute(clubs, allFixtures);
    }
}
