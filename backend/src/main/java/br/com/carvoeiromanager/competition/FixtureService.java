package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the managed club's season fixture list for a competition, oriented from that club's
 * point of view (home/away flag, goals for/against, opponent identity).
 */
@Service
public class FixtureService {

    private final SaveGameService saveGameService;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final WeatherService weatherService;

    public FixtureService(SaveGameService saveGameService,
                          ClubRepository clubRepository,
                          CompetitionRepository competitionRepository,
                          FixtureRepository fixtureRepository,
                          WeatherService weatherService) {
        this.saveGameService = saveGameService;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.weatherService = weatherService;
    }

    public List<FixtureEntryResponse> getFixtures(UUID saveGameId, UUID competitionId, UUID userId) {
        // Enforce save-game ownership before exposing any data.
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);

        // The competition must belong to this save game; otherwise treat as not found
        // (avoids leaking competitions from other save games).
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));

        UUID managedClubId = saveGame.getManagedClubId();
        if (managedClubId == null) {
            return List.of();
        }

        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));

        return fixtureRepository.findBySaveGameIdAndCompetitionIdOrderByRound(saveGameId, competition.getId())
                .stream()
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .map(f -> toEntry(f, managedClubId, clubById, saveGame.getCurrentDate()))
                .toList();
    }

    private FixtureEntryResponse toEntry(Fixture fixture, UUID managedClubId, Map<UUID, Club> clubById,
                                         LocalDate today) {
        boolean home = fixture.getHomeClubId().equals(managedClubId);
        UUID opponentClubId = home ? fixture.getAwayClubId() : fixture.getHomeClubId();
        Club opponent = clubById.get(opponentClubId);
        Club homeClub = clubById.get(fixture.getHomeClubId());

        Integer goalsFor = null;
        Integer goalsAgainst = null;
        if (fixture.isPlayed()) {
            goalsFor = home ? fixture.getHomeGoals() : fixture.getAwayGoals();
            goalsAgainst = home ? fixture.getAwayGoals() : fixture.getHomeGoals();
        }

        // Gated through visibleWeather so an upcoming fixture more than a day out returns nulls —
        // that gating is the whole point of the feature.
        String weatherLabel = null;
        Integer temperatureC = null;
        if (homeClub != null) {
            Optional<Weather> weather = weatherService.visibleWeather(fixture, homeClub, today);
            weatherLabel = weather.map(w -> w.condition().name()).orElse(null);
            temperatureC = weather.map(Weather::temperatureC).orElse(null);
        }

        return new FixtureEntryResponse(
                fixture.getId(),
                fixture.getRound(),
                fixture.getMatchDate(),
                home,
                opponentClubId,
                opponent != null ? opponent.getName() : "",
                opponent != null ? opponent.getShortName() : "",
                homeClub != null ? homeClub.resolvedStadiumName() : null,
                fixture.isPlayed(),
                goalsFor,
                goalsAgainst,
                fixture.getAttendance(),
                weatherLabel,
                temperatureC);
    }
}
