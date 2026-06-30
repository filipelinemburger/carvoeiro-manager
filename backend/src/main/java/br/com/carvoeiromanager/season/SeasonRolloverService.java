package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TopScorerRow;
import br.com.carvoeiromanager.competition.TopScorersService;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.contract.FreeAgentGenerator;
import br.com.carvoeiromanager.player.NewgenGenerator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerAgingService;
import br.com.carvoeiromanager.player.PlayerRepository;
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
    private static final int FREE_AGENT_FLOOR = 6;

    private final NewgenGenerator newgenGenerator;
    private final FreeAgentGenerator freeAgentGenerator;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final SeasonCalendarGenerator seasonCalendarGenerator;
    private final SeasonEventRepository seasonEventRepository;
    private final NewsItemRepository newsItemRepository;

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
                                 NewgenGenerator newgenGenerator,
                                 FreeAgentGenerator freeAgentGenerator,
                                 FinanceTransactionRepository financeTransactionRepository,
                                 TransferOfferRepository transferOfferRepository,
                                 SeasonCalendarGenerator seasonCalendarGenerator,
                                 SeasonEventRepository seasonEventRepository,
                                 NewsItemRepository newsItemRepository) {
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
        this.freeAgentGenerator = freeAgentGenerator;
        this.financeTransactionRepository = financeTransactionRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.seasonEventRepository = seasonEventRepository;
        this.newsItemRepository = newsItemRepository;
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

        // Purge the finished season's finance ledger (balances persist on the club).
        financeTransactionRepository.deleteBySaveGameId(saveGameId);
        // Purge season-scoped transfer offers (players/clubs change at rollover).
        transferOfferRepository.deleteBySaveGameId(saveGameId);
        // Purge stale season events so the new calendar can be regenerated cleanly.
        seasonEventRepository.deleteBySaveGameId(saveGameId);
        // Purge the finished season's news feed (a fresh season starts a fresh feed).
        newsItemRepository.deleteBySaveGameId(saveGameId);

        // 2. Clear the managed club's lineup before deleting players (FK safety).
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
            lineupRepository.delete(lineup);
        });
        lineupRepository.flush();

        // 3. Age every player (id order, one seeded RNG); tick contracts; partition.
        List<Player> players = new ArrayList<>(playerRepository.findBySaveGameId(saveGameId));
        players.sort(Comparator.comparing(Player::getId));

        List<SquadChangeResponse> retirements = new ArrayList<>();
        List<SquadChangeResponse> newcomers = new ArrayList<>();
        List<SquadChangeResponse> expiredContracts = new ArrayList<>();
        List<Player> toDelete = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();

        for (Player player : players) {
            player.setAge(player.getAge() + 1);
            playerAgingService.applyAgeCurve(player, rng);
            boolean retires = rng.nextDouble() < playerAgingService.retirementProbability(player.getAge());
            boolean freeAgent = player.isFreeAgent();
            boolean managed = !freeAgent && player.getClubId().equals(managedClubId);

            if (retires) {
                if (freeAgent) {
                    // A free agent who retires simply leaves the pool (no replacement).
                    toDelete.add(player);
                    continue;
                }
                if (managed) {
                    retirements.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
                Player newgen = newgenGenerator.generate(
                        saveGameId, player.getClubId(), player.getPosition(), rng);
                newgen.setContractYears(2 + rng.nextInt(3));
                toSave.add(newgen);
                if (managed) {
                    newcomers.add(new SquadChangeResponse(
                            newgen.getName(), newgen.getPosition().name(), newgen.getAge()));
                }
                toDelete.add(player);
                continue;
            }

            // Carried over: reset per-season state, then tick the contract.
            player.resetSeasonState();
            if (!freeAgent) {
                player.setContractYears(player.getContractYears() - 1);
                if (player.getContractYears() <= 0) {
                    if (managed) {
                        expiredContracts.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                        player.setClubId(null);          // lapses into the free-agent pool
                        player.setContractYears(0);
                    } else {
                        player.setContractYears(2 + rng.nextInt(3)); // AI auto-renews
                    }
                }
            }
            toSave.add(player);
        }
        playerRepository.deleteAll(toDelete);
        playerRepository.flush();
        playerRepository.saveAll(toSave);

        // Replenish the free-agent pool to the floor so the market never dies.
        int poolSize = playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).size();
        for (int i = poolSize; i < FREE_AGENT_FLOOR; i++) {
            playerRepository.save(freeAgentGenerator.generate(saveGameId, rng));
        }

        // 4. Roll each competition's year forward, then regenerate the dated calendar
        //    (fixtures over each competition's members + the season_event queue).
        for (Competition competition : competitions) {
            competition.setSeasonYear(finishingYear + 1);
            competitionRepository.save(competition);
        }
        seasonCalendarGenerator.generate(saveGameId, finishingYear + 1, competitions);

        // 5. Advance the save into the new season.
        saveGame.advanceToNextSeason();
        saveGameRepository.save(saveGame);

        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers), List.copyOf(expiredContracts));
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
