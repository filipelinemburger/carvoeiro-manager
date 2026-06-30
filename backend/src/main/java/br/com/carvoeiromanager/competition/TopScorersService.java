package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregates GOAL and SET_PIECE_GOAL match-events into a sorted top-scorer table for a competition.
 * Used live (end-of-season screen) and by the season archive at rollover.
 */
@Service
public class TopScorersService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final MatchEventRepository matchEventRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;

    public TopScorersService(SaveGameService saveGameService,
                             CompetitionRepository competitionRepository,
                             FixtureRepository fixtureRepository,
                             MatchEventRepository matchEventRepository,
                             PlayerRepository playerRepository,
                             ClubRepository clubRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
    }

    public List<TopScorerRow> getTopScorers(UUID saveGameId, UUID competitionId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(
                        "COMPETITION_NOT_FOUND", "Competition not found", HttpStatus.NOT_FOUND));

        List<UUID> fixtureIds = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                .map(br.com.carvoeiromanager.competition.Fixture::getId)
                .toList();
        if (fixtureIds.isEmpty()) {
            return List.of();
        }

        List<MatchEvent> goals = matchEventRepository.findByFixtureIdInAndTypeIn(
                fixtureIds, List.of("GOAL", "SET_PIECE_GOAL"));
        if (goals.isEmpty()) {
            return List.of();
        }

        Map<UUID, Player> playerById = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Map<UUID, String> clubNameById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Long> goalsByPlayer = goals.stream()
                .collect(Collectors.groupingBy(MatchEvent::getPlayerId, Collectors.counting()));

        return goalsByPlayer.entrySet().stream()
                .map(entry -> {
                    Player p = playerById.get(entry.getKey());
                    String playerName = p != null ? p.getName() : "";
                    UUID clubId = p != null ? p.getClubId() : null;
                    String clubName = clubId != null ? clubNameById.getOrDefault(clubId, "") : "";
                    return new TopScorerRow(entry.getKey(), playerName, clubId, clubName,
                            entry.getValue().intValue());
                })
                .sorted(Comparator.comparingInt(TopScorerRow::goals).reversed()
                        .thenComparing(TopScorerRow::playerName))
                .toList();
    }
}
