package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.match.EventType;
import br.com.carvoeiromanager.match.FixtureAppearance;
import br.com.carvoeiromanager.match.FixtureAppearanceRepository;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.season.Season;
import br.com.carvoeiromanager.season.SeasonPlayerStats;
import br.com.carvoeiromanager.season.SeasonPlayerStatsRepository;
import br.com.carvoeiromanager.season.SeasonRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregates player match statistics: live (current in-progress season, computed on demand from
 * fixture_appearance + match_events) and archived (past seasons, read from season_player_stats).
 * {@link #aggregateCompetition} is the shared per-competition math reused by the live path here
 * AND by {@code SeasonRolloverService}'s archival step, so the two never compute stats differently.
 */
@Service
public class PlayerStatsService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchEventRepository matchEventRepository;
    private final SeasonPlayerStatsRepository seasonPlayerStatsRepository;
    private final SeasonRepository seasonRepository;

    public PlayerStatsService(SaveGameService saveGameService, CompetitionRepository competitionRepository,
                              FixtureRepository fixtureRepository,
                              FixtureAppearanceRepository fixtureAppearanceRepository,
                              MatchEventRepository matchEventRepository,
                              SeasonPlayerStatsRepository seasonPlayerStatsRepository,
                              SeasonRepository seasonRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchEventRepository = matchEventRepository;
        this.seasonPlayerStatsRepository = seasonPlayerStatsRepository;
        this.seasonRepository = seasonRepository;
    }

    /** One player's aggregate line within one competition's currently-live (unarchived) data. */
    public record CompetitionPlayerAggregate(UUID playerId, UUID clubId, int matchesPlayed, int minutesPlayed,
                                              int goals, int assists, int fouls, int yellowCards, int redCards) {
    }

    public PlayerStatsResponse getStats(UUID saveGameId, UUID playerId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);

        List<SeasonStatsLine> current = currentSeasonLines(saveGameId, playerId);
        List<SeasonStatsLine> archived = archivedSeasonLines(saveGameId, playerId);

        List<SeasonStatsLine> seasons = new ArrayList<>();
        seasons.addAll(current);
        seasons.addAll(archived);

        SeasonStatsLine totals = seasons.stream().reduce(
                new SeasonStatsLine(0, "Total", 0, 0, 0, 0, 0, 0, 0),
                (a, b) -> new SeasonStatsLine(0, "Total",
                        a.matchesPlayed() + b.matchesPlayed(), a.minutesPlayed() + b.minutesPlayed(),
                        a.goals() + b.goals(), a.assists() + b.assists(), a.fouls() + b.fouls(),
                        a.yellowCards() + b.yellowCards(), a.redCards() + b.redCards()));

        return new PlayerStatsResponse(List.copyOf(seasons), totals);
    }

    private List<SeasonStatsLine> currentSeasonLines(UUID saveGameId, UUID playerId) {
        List<SeasonStatsLine> lines = new ArrayList<>();
        for (Competition competition : competitionRepository.findBySaveGameId(saveGameId)) {
            CompetitionPlayerAggregate agg = aggregateCompetition(saveGameId, competition.getId()).get(playerId);
            if (agg == null) continue;
            lines.add(new SeasonStatsLine(competition.getSeasonYear(), competition.getName(),
                    agg.matchesPlayed(), agg.minutesPlayed(), agg.goals(), agg.assists(), agg.fouls(),
                    agg.yellowCards(), agg.redCards()));
        }
        return lines;
    }

    /**
     * Archived stat lines for this player, restricted to the requesting save game. {@code
     * SeasonPlayerStats} has no {@code saveGameId} column of its own — it only reaches one
     * indirectly through {@code Season.saveGameId} — so a row belonging to a different save game
     * (possibly a different user's career, if the same player id happens to recur) must be
     * resolved and filtered out here rather than trusted at face value.
     */
    private List<SeasonStatsLine> archivedSeasonLines(UUID saveGameId, UUID playerId) {
        List<SeasonStatsLine> lines = new ArrayList<>();
        for (SeasonPlayerStats row : seasonPlayerStatsRepository.findByPlayerId(playerId)) {
            Season season = seasonRepository.findById(row.getSeasonId()).orElseThrow();
            if (!season.getSaveGameId().equals(saveGameId)) {
                continue;
            }
            lines.add(toLine(row, season));
        }
        return lines;
    }

    private SeasonStatsLine toLine(SeasonPlayerStats row, Season season) {
        return new SeasonStatsLine(season.getSeasonYear(), season.getCompetitionName(),
                row.getMatchesPlayed(), row.getMinutesPlayed(), row.getGoals(), row.getAssists(),
                row.getFouls(), row.getYellowCards(), row.getRedCards());
    }

    /**
     * Aggregates EVERY player's stat line for one competition from currently-live season-scoped
     * data. Package-private: called from here for a single player's live display, and from
     * {@code SeasonRolloverService} for every player at archival time — the only place this math
     * is written.
     */
    Map<UUID, CompetitionPlayerAggregate> aggregateCompetition(UUID saveGameId, UUID competitionId) {
        List<UUID> fixtureIds = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId)
                .stream().map(Fixture::getId).toList();
        if (fixtureIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<FixtureAppearance>> appearancesByPlayer = fixtureAppearanceRepository
                .findByFixtureIdIn(fixtureIds).stream()
                .collect(Collectors.groupingBy(FixtureAppearance::getPlayerId));
        Map<UUID, List<MatchEvent>> eventsByPlayer = matchEventRepository
                .findByFixtureIdIn(fixtureIds).stream()
                .collect(Collectors.groupingBy(MatchEvent::getPlayerId));

        Set<UUID> playerIds = new HashSet<>();
        playerIds.addAll(appearancesByPlayer.keySet());
        playerIds.addAll(eventsByPlayer.keySet());

        Map<UUID, CompetitionPlayerAggregate> out = new HashMap<>();
        for (UUID playerId : playerIds) {
            List<FixtureAppearance> apps = appearancesByPlayer.getOrDefault(playerId, List.of());
            List<MatchEvent> events = eventsByPlayer.getOrDefault(playerId, List.of());

            int matches = apps.size();
            int minutes = apps.stream().mapToInt(FixtureAppearance::getMinutesPlayed).sum();
            int fouls = apps.stream().mapToInt(FixtureAppearance::getFouls).sum();
            UUID clubId = apps.isEmpty() ? (events.isEmpty() ? null : events.get(0).getClubId())
                    : apps.get(0).getClubId();

            int goals = (int) events.stream()
                    .filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
            int assists = (int) events.stream().filter(e -> "ASSIST".equals(e.getType())).count();
            int yellows = (int) events.stream().filter(e -> "YELLOW_CARD".equals(e.getType())).count();
            int reds = (int) events.stream().filter(e -> "RED_CARD".equals(e.getType())).count();

            out.put(playerId, new CompetitionPlayerAggregate(playerId, clubId, matches, minutes,
                    goals, assists, fouls, yellows, reds));
        }
        return out;
    }
}
