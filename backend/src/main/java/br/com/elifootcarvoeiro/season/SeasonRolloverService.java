package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.FixtureScheduler;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsService;
import br.com.elifootcarvoeiro.competition.TopScorerRow;
import br.com.elifootcarvoeiro.competition.TopScorersService;
import br.com.elifootcarvoeiro.match.LineupPlayerRepository;
import br.com.elifootcarvoeiro.match.LineupRepository;
import br.com.elifootcarvoeiro.match.MatchEventRepository;
import br.com.elifootcarvoeiro.player.NewgenGenerator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerAgingService;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rolls a finished career into the next season: archives the finished season,
 * then mutates the live world in place. Deterministic for a given save+year.
 *
 * FK-safe ordering: a competition's fixtures (whose match_events cascade) and the
 * managed club's lineup are deleted, and the persistence context flushed, BEFORE
 * any player is deleted, because match_events.player_id and lineup_players.player_id
 * reference players(id) WITHOUT ON DELETE CASCADE.
 */
@Service
public class SeasonRolloverService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final MatchEventRepository matchEventRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final StandingsService standingsService;
    private final TopScorersService topScorersService;
    private final SeasonRepository seasonRepository;
    private final SeasonStandingRepository seasonStandingRepository;
    private final SeasonTopScorerRepository seasonTopScorerRepository;
    private final PlayerAgingService playerAgingService;
    private final NewgenGenerator newgenGenerator;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public SeasonRolloverService(SaveGameService saveGameService,
                                 SaveGameRepository saveGameRepository,
                                 CompetitionRepository competitionRepository,
                                 FixtureRepository fixtureRepository,
                                 MatchEventRepository matchEventRepository,
                                 ClubRepository clubRepository,
                                 PlayerRepository playerRepository,
                                 LineupRepository lineupRepository,
                                 LineupPlayerRepository lineupPlayerRepository,
                                 StandingsService standingsService,
                                 TopScorersService topScorersService,
                                 SeasonRepository seasonRepository,
                                 SeasonStandingRepository seasonStandingRepository,
                                 SeasonTopScorerRepository seasonTopScorerRepository,
                                 PlayerAgingService playerAgingService,
                                 NewgenGenerator newgenGenerator) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.standingsService = standingsService;
        this.topScorersService = topScorersService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
        this.playerAgingService = playerAgingService;
        this.newgenGenerator = newgenGenerator;
    }

    @Transactional
    public RolloverResponse rollover(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() != SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_NOT_FINISHED",
                    "The current season is not finished yet", HttpStatus.UNPROCESSABLE_CONTENT);
        }

        final int finishingYear = saveGame.getSeasonYear();
        final UUID managedClubId = saveGame.getManagedClubId();
        final Random rng = new Random(
                saveGameId.getMostSignificantBits() ^ (long) finishingYear);

        final List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        final Map<UUID, String> clubNameById = clubs.stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        final List<Competition> competitions = competitionRepository.findBySaveGameId(saveGameId);

        // 1. Archive each competition from live data, then purge its results.
        for (Competition competition : competitions) {
            archive(saveGameId, userId, competition, finishingYear, managedClubId, clubNameById);

            List<UUID> fixtureIds = fixtureRepository
                    .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                    .map(Fixture::getId).toList();
            if (!fixtureIds.isEmpty()) {
                matchEventRepository.deleteByFixtureIdIn(fixtureIds);
            }
            fixtureRepository.deleteAll(
                    fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()));
        }
        // Ensure events + fixtures are gone before any player is deleted (FK safety).
        fixtureRepository.flush();

        // 2. Clear the managed club's lineup before deleting players (FK safety).
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
            lineupRepository.delete(lineup);
        });
        lineupRepository.flush();

        // 3. Age every player (id order, one seeded RNG); partition retiring vs carried.
        List<Player> players = new ArrayList<>(playerRepository.findBySaveGameId(saveGameId));
        players.sort(Comparator.comparing(Player::getId));

        List<SquadChangeResponse> retirements = new ArrayList<>();
        List<SquadChangeResponse> newcomers = new ArrayList<>();
        List<Player> toDelete = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();

        for (Player player : players) {
            player.setAge(player.getAge() + 1);
            playerAgingService.applyAgeCurve(player, rng);
            boolean retires = rng.nextDouble() < playerAgingService.retirementProbability(player.getAge());
            boolean managed = player.getClubId().equals(managedClubId);
            if (retires) {
                if (managed) {
                    retirements.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
                Player newgen = newgenGenerator.generate(
                        saveGameId, player.getClubId(), player.getPosition(), rng);
                toSave.add(newgen);
                if (managed) {
                    newcomers.add(new SquadChangeResponse(
                            newgen.getName(), newgen.getPosition().name(), newgen.getAge()));
                }
                toDelete.add(player);
            } else {
                player.resetSeasonState();
                toSave.add(player);
            }
        }
        playerRepository.deleteAll(toDelete);
        playerRepository.flush();
        playerRepository.saveAll(toSave);

        // 4. Regenerate fixtures and roll each competition's year forward.
        List<Club> clubsSorted = clubs.stream()
                .sorted(Comparator.comparing(Club::getId)).toList();
        int clubCount = clubsSorted.size();
        for (Competition competition : competitions) {
            fixtureScheduler.doubleRoundRobin(clubCount).forEach(scheduled -> {
                UUID home = clubsSorted.get(scheduled.homeIndex()).getId();
                UUID away = clubsSorted.get(scheduled.awayIndex()).getId();
                fixtureRepository.save(Fixture.create(
                        saveGameId, competition.getId(), scheduled.round(), home, away));
            });
            competition.setSeasonYear(finishingYear + 1);
            competitionRepository.save(competition);
        }

        // 5. Advance the save into the new season.
        saveGame.advanceToNextSeason();
        saveGameRepository.save(saveGame);

        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers));
    }

    private void archive(UUID saveGameId, UUID userId, Competition competition, int finishingYear,
                         UUID managedClubId, Map<UUID, String> clubNameById) {
        List<StandingRow> table = standingsService.getStandings(saveGameId, competition.getId(), userId);
        List<TopScorerRow> scorers = topScorersService.getTopScorers(saveGameId, competition.getId(), userId);

        StandingRow champion = table.get(0);
        int managedPosition = positionOf(table, managedClubId);
        String managedName = clubNameById.getOrDefault(managedClubId, "");

        Season season = Season.create(saveGameId, competition.getId(), finishingYear,
                competition.getName(), champion.clubId(), champion.clubName(),
                managedClubId, managedName, managedPosition);
        seasonRepository.save(season);

        int position = 1;
        for (StandingRow row : table) {
            seasonStandingRepository.save(SeasonStanding.create(season.getId(), position++,
                    row.clubId(), row.clubName(), row.played(), row.won(), row.drawn(), row.lost(),
                    row.goalsFor(), row.goalsAgainst(), row.goalDifference(), row.points()));
        }
        int rank = 1;
        for (TopScorerRow scorer : scorers) {
            seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), rank++,
                    scorer.playerId(), scorer.playerName(), scorer.clubId(), scorer.clubName(),
                    scorer.goals()));
        }
    }

    private int positionOf(List<StandingRow> table, UUID managedClubId) {
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).clubId().equals(managedClubId)) {
                return i + 1;
            }
        }
        return table.size(); // defensive: managed club should always be present
    }
}
