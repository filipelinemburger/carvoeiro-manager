package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StandingsService {

    private final ClubRepository clubRepository;
    private final FixtureRepository fixtureRepository;
    private final StandingsCalculator standingsCalculator;

    public StandingsService(ClubRepository clubRepository,
                             FixtureRepository fixtureRepository) {
        this.clubRepository = clubRepository;
        this.fixtureRepository = fixtureRepository;
        this.standingsCalculator = new StandingsCalculator();
    }

    public List<StandingRow> getStandings(UUID saveGameId, UUID competitionId) {
        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        if (clubs.isEmpty()) {
            throw new ApiException(
                    "SAVE_GAME_NOT_FOUND",
                    "No clubs found for save game " + saveGameId,
                    HttpStatus.NOT_FOUND);
        }

        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId);
        return standingsCalculator.compute(clubs, allFixtures);
    }
}
