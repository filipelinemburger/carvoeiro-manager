package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.TopScorerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SeasonHistoryService {

    private final SaveGameService saveGameService;
    private final SeasonRepository seasonRepository;
    private final SeasonStandingRepository seasonStandingRepository;
    private final SeasonTopScorerRepository seasonTopScorerRepository;

    public SeasonHistoryService(SaveGameService saveGameService,
                                SeasonRepository seasonRepository,
                                SeasonStandingRepository seasonStandingRepository,
                                SeasonTopScorerRepository seasonTopScorerRepository) {
        this.saveGameService = saveGameService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
    }

    public List<SeasonSummaryResponse> getHistory(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(saveGameId).stream()
                .map(this::toSummary)
                .toList();
    }

    public SeasonDetailResponse getSeason(UUID saveGameId, int seasonYear, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        List<Season> seasons = seasonRepository.findBySaveGameIdAndSeasonYearOrderByCompetitionName(
                saveGameId, seasonYear);
        Season season = seasons.stream().findFirst()
                .orElseThrow(() -> new ApiException(
                        "SEASON_NOT_FOUND", "Season not found", HttpStatus.NOT_FOUND));

        List<SeasonStandingResponse> table = seasonStandingRepository
                .findBySeasonIdOrderByPosition(season.getId()).stream()
                .map(s -> new SeasonStandingResponse(s.getPosition(), s.getClubName(), s.getPlayed(),
                        s.getWon(), s.getDrawn(), s.getLost(), s.getGoalsFor(), s.getGoalsAgainst(),
                        s.getGoalDifference(), s.getPoints()))
                .toList();

        List<TopScorerResponse> scorers = seasonTopScorerRepository
                .findBySeasonIdOrderByRank(season.getId()).stream()
                .map(s -> new TopScorerResponse(s.getPlayerName(), s.getClubName(), s.getGoals()))
                .toList();

        return new SeasonDetailResponse(season.getSeasonYear(), season.getCompetitionName(),
                season.getChampionClubName(), table, scorers);
    }

    private SeasonSummaryResponse toSummary(Season season) {
        List<SeasonTopScorer> scorers = seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId());
        String topName = scorers.isEmpty() ? null : scorers.get(0).getPlayerName();
        int topGoals = scorers.isEmpty() ? 0 : scorers.get(0).getGoals();
        return new SeasonSummaryResponse(season.getSeasonYear(), season.getCompetitionName(),
                season.getChampionClubName(), season.getManagedClubPosition(), topName, topGoals);
    }
}
