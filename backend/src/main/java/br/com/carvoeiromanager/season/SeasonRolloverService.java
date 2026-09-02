package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.FinalClassification;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TopScorerRow;
import br.com.carvoeiromanager.competition.TopScorersService;
import br.com.carvoeiromanager.contract.PreContractAgreement;
import br.com.carvoeiromanager.contract.PreContractAgreementRepository;
import br.com.carvoeiromanager.contract.PreContractService;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.loan.LoanOfferRepository;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.NewgenGenerator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerAgingService;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.YouthDevelopmentService;
import br.com.carvoeiromanager.watchlist.PlayerAlertResponse;
import br.com.carvoeiromanager.watchlist.WatchlistEntry;
import br.com.carvoeiromanager.watchlist.WatchlistEntryRepository;
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
    private final CompetitionPhaseRepository competitionPhaseRepository;
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
    private final SeasonPlayerStatsRepository seasonPlayerStatsRepository;
    private final PlayerAgingService playerAgingService;

    private final NewgenGenerator newgenGenerator;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final LoanOfferRepository loanOfferRepository;
    private final SeasonCalendarGenerator seasonCalendarGenerator;
    private final SeasonEventRepository seasonEventRepository;
    private final NewsItemRepository newsItemRepository;
    private final YouthDevelopmentService youthDevelopmentService;
    private final br.com.carvoeiromanager.stats.PlayerStatsService playerStatsService;
    private final br.com.carvoeiromanager.match.FixtureAppearanceRepository fixtureAppearanceRepository;
    private final PreContractService preContractService;
    private final PreContractAgreementRepository preContractAgreementRepository;
    private final WatchlistEntryRepository watchlistEntryRepository;
    private final MatchPreviewService matchPreviewService;
    private final BackgroundCompetitionService backgroundCompetitionService;

    private static final int MAX_INTAKE_PER_SEASON = 3;
    private static final int ACADEMY_TARGET = 10;
    private static final int DEPARTURE_AGE = 20;

    public SeasonRolloverService(SaveGameService saveGameService,
                                 SaveGameRepository saveGameRepository,
                                 CompetitionRepository competitionRepository,
                                 CompetitionPhaseRepository competitionPhaseRepository,
                                 FixtureRepository fixtureRepository,
                                 MatchEventRepository matchEventRepository,
                                 br.com.carvoeiromanager.match.FixtureAppearanceRepository fixtureAppearanceRepository,
                                 ClubRepository clubRepository,
                                 PlayerRepository playerRepository,
                                 LineupRepository lineupRepository,
                                 LineupPlayerRepository lineupPlayerRepository,
                                 StandingsService standingsService,
                                 TopScorersService topScorersService,
                                 SeasonRepository seasonRepository,
                                 SeasonStandingRepository seasonStandingRepository,
                                 SeasonTopScorerRepository seasonTopScorerRepository,
                                 SeasonPlayerStatsRepository seasonPlayerStatsRepository,
                                 br.com.carvoeiromanager.stats.PlayerStatsService playerStatsService,
                                 PlayerAgingService playerAgingService,
                                 NewgenGenerator newgenGenerator,
                                 FinanceTransactionRepository financeTransactionRepository,
                                 TransferOfferRepository transferOfferRepository,
                                 LoanOfferRepository loanOfferRepository,
                                 SeasonCalendarGenerator seasonCalendarGenerator,
                                 SeasonEventRepository seasonEventRepository,
                                 NewsItemRepository newsItemRepository,
                                 YouthDevelopmentService youthDevelopmentService,
                                 PreContractService preContractService,
                                 PreContractAgreementRepository preContractAgreementRepository,
                                 WatchlistEntryRepository watchlistEntryRepository,
                                 MatchPreviewService matchPreviewService,
                                 BackgroundCompetitionService backgroundCompetitionService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.standingsService = standingsService;
        this.topScorersService = topScorersService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
        this.seasonPlayerStatsRepository = seasonPlayerStatsRepository;
        this.playerStatsService = playerStatsService;
        this.playerAgingService = playerAgingService;
        this.newgenGenerator = newgenGenerator;
        this.financeTransactionRepository = financeTransactionRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.loanOfferRepository = loanOfferRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.seasonEventRepository = seasonEventRepository;
        this.newsItemRepository = newsItemRepository;
        this.youthDevelopmentService = youthDevelopmentService;
        this.preContractService = preContractService;
        this.preContractAgreementRepository = preContractAgreementRepository;
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.matchPreviewService = matchPreviewService;
        this.backgroundCompetitionService = backgroundCompetitionService;
    }

    @Transactional
    public RolloverResponse rollover(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() != SaveGameStatus.FINISHED) {
            throw new ApiException(ErrorCode.SEASON_NOT_FINISHED, "The current season is not finished yet");
        }

        final int finishingYear = saveGame.getSeasonYear();
        final UUID managedClubId = saveGame.getManagedClubId();
        final Random rng = new Random(
                saveGameId.getMostSignificantBits() ^ (long) finishingYear);

        // Resolve any pre-contract deal still AGREED, then map LOCKED deals by player so
        // the contract-tick pass below executes them instead of lapsing/renewing.
        Map<UUID, PreContractAgreement> lockedDeals =
                preContractService.resolveRemainingAgreements(saveGame, rng);

        final List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        final Map<UUID, String> clubNameById = clubs.stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        final List<Competition> competitions = competitionRepository.findBySaveGameId(saveGameId);

        // 1. Archive each competition from live data, then purge its results.
        for (Competition competition : competitions) {
            // EVERY competition is archived, background ones included: a division the player is not
            // in still crowns a champion, and that title is part of the world's history — skipping
            // the archive erased it from every screen, because the news feed is purged below too.
            //
            // Membership decides which archive shape is written, and it is asked of
            // BackgroundCompetitionService.isManaged — the same membership predicate the simulator
            // uses. It must NEVER be inferred from the final classification: indexOf() returns -1
            // for a non-member, which the managed branch below turns into "finished last", quietly
            // recording the player in a competition they never entered.
            boolean managed = backgroundCompetitionService.isManaged(competition.getId(), managedClubId);
            archive(saveGameId, userId, competition, finishingYear,
                    managed ? managedClubId : null, clubNameById);

            List<UUID> fixtureIds = fixtureRepository
                    .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                    .map(Fixture::getId).toList();
            if (!fixtureIds.isEmpty()) {
                matchEventRepository.deleteByFixtureIdIn(fixtureIds);
                fixtureAppearanceRepository.deleteByFixtureIdIn(fixtureIds);
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
        // Purge season-scoped loan offers alongside them, for the same reason.
        loanOfferRepository.deleteBySaveGameId(saveGameId);
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

        // Loans come home BEFORE contracts tick — the contract is with the owner — and AFTER the
        // archive above, so a loaned player's goals stay credited to the club he played them for.
        List<SquadChangeResponse> loanReturns = new ArrayList<>();
        List<Player> returning = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(Player::isOnLoan)
                .toList();
        for (Player player : returning) {
            boolean toManaged = managedClubId.equals(player.getOwnerClubId());
            player.returnFromLoan();
            if (toManaged) {
                loanReturns.add(new SquadChangeResponse(
                        player.getName(), player.getPosition().name(), player.getAge()));
            }
        }
        playerRepository.saveAll(returning);
        playerRepository.flush();

        // 3. Age every player (id order, one seeded RNG); tick contracts; partition.
        List<Player> players = new ArrayList<>(playerRepository.findBySaveGameId(saveGameId));
        players.sort(Comparator.comparing(Player::getId));

        List<SquadChangeResponse> retirements = new ArrayList<>();
        List<SquadChangeResponse> expiredContracts = new ArrayList<>();
        List<SquadChangeResponse> youthIntake = new ArrayList<>();
        List<SquadChangeResponse> youthDepartures = new ArrayList<>();
        List<SquadChangeResponse> preContractArrivals = new ArrayList<>();
        List<SquadChangeResponse> preContractDepartures = new ArrayList<>();
        List<Player> toDelete = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();
        List<PlayerAlertResponse> watchlistEvents = new ArrayList<>();
        java.util.Set<UUID> watchedIds = watchlistEntryRepository.findBySaveGameId(saveGameId).stream()
                .map(WatchlistEntry::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());

        for (Player player : players) {
            if (player.isYouthProspect()) {
                player.setAge(player.getAge() + 1);
                boolean managedYouth = !player.isFreeAgent() && player.getClubId().equals(managedClubId);
                if (player.getAge() >= DEPARTURE_AGE) {
                    if (managedYouth) {
                        youthDepartures.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                    }
                    toDelete.add(player);
                } else {
                    youthDevelopmentService.develop(player);
                    toSave.add(player);
                }
                continue;
            }

            player.setAge(player.getAge() + 1);
            playerAgingService.applyAgeCurve(player, rng);
            boolean retires = rng.nextDouble() < playerAgingService.retirementProbability(player.getAge());
            boolean freeAgent = player.isFreeAgent();
            boolean managed = !freeAgent && player.getClubId().equals(managedClubId);

            if (retires) {
                // A retiring player dies with any LOCKED pre-contract deal still on him — the
                // lockedDeals map is only consulted below, for players who don't retire, so a
                // retiree's deal is simply purged with the rest of the season-scoped table.
                if (freeAgent) {
                    // A free agent who retires simply leaves the pool (no replacement).
                    toDelete.add(player);
                    continue;
                }
                if (managed) {
                    retirements.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
                if (!managed) {
                    // AI clubs keep newgen-on-retirement; the managed club fills gaps from its academy.
                    Player newgen = newgenGenerator.generate(
                            saveGameId, player.getClubId(), player.getPosition(), rng);
                    newgen.setContractYears(2 + rng.nextInt(3));
                    toSave.add(newgen);
                }
                toDelete.add(player);
                continue;
            }

            // Carried over: reset per-season state, then tick the contract.
            player.resetSeasonState();
            if (!freeAgent) {
                player.setContractYears(player.getContractYears() - 1);
                if (player.getContractYears() <= 0) {
                    PreContractAgreement deal = lockedDeals.get(player.getId());
                    if (deal != null) {
                        // A binding pre-contract executes: free move, agreed terms.
                        player.setClubId(deal.getToClubId());
                        player.setWage(deal.getWage());
                        player.setContractYears(deal.getContractYears());
                        SquadChangeResponse change = new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge());
                        if (deal.getToClubId().equals(managedClubId)) {
                            preContractArrivals.add(change);
                        } else if (deal.getFromClubId().equals(managedClubId)) {
                            preContractDepartures.add(change);
                        }
                    } else {
                        // No deal: the contract simply lapses — managed AND AI players
                        // alike now fall into the free-agent pool (the silent AI
                        // auto-renew is gone; AI clubs renew proactively in-season).
                        if (managed) {
                            expiredContracts.add(new SquadChangeResponse(
                                    player.getName(), player.getPosition().name(), player.getAge()));
                        }
                        if (watchedIds.contains(player.getId())) {
                            watchlistEvents.add(new PlayerAlertResponse("FREE_AGENT",
                                    player.getName(),
                                    clubNameById.getOrDefault(player.getClubId(), "?"), null));
                        }
                        player.setClubId(null);
                        player.setContractYears(0);
                    }
                }
            }
            toSave.add(player);
        }
        playerRepository.deleteAll(toDelete);
        playerRepository.flush();
        playerRepository.saveAll(toSave);

        // A club can take several players back at once, and the squad cap is otherwise only
        // checked one signing at a time — so settle any overflow by listing the weakest. This
        // runs AFTER the loop above, not right after the loan returns: every carried-over player
        // has resetSeasonState() called on him in that loop, which unconditionally clears
        // transferListed/listedReason — listing the surplus any earlier would just get wiped out
        // before rollover ever returns.
        List<SquadChangeResponse> surplusListings = listSurplusAfterReturns(saveGameId, managedClubId);

        // Pre-contract agreements are season-scoped: executed or dead, all purged now.
        preContractAgreementRepository.deleteBySaveGameId(saveGameId);

        // Top up the managed club's youth academy toward the target.
        int academySize = playerRepository.findByClubIdAndYouthProspectTrue(managedClubId).size();
        int intake = Math.min(MAX_INTAKE_PER_SEASON, Math.max(0, ACADEMY_TARGET - academySize));
        Position[] positions = Position.values();
        for (int i = 0; i < intake; i++) {
            Position pos = positions[rng.nextInt(positions.length)];
            Player prospect = newgenGenerator.generateProspect(saveGameId, managedClubId, pos, rng);
            playerRepository.save(prospect);
            youthIntake.add(new SquadChangeResponse(
                    prospect.getName(), prospect.getPosition().name(), prospect.getAge()));
        }

        // 4. Roll each competition's year forward, then regenerate the dated calendar
        //    (fixtures over each competition's members + the season_event queue).
        for (Competition competition : competitions) {
            competition.setSeasonYear(finishingYear + 1);
            competitionRepository.save(competition);
        }
        seasonCalendarGenerator.generate(saveGameId, finishingYear + 1, competitions, managedClubId);

        // 5. Advance the save into the new season.
        saveGame.advanceToNextSeason();
        saveGameRepository.save(saveGame);

        // Round 1 of the new season gets its opponent analysis right away (staff carries over).
        matchPreviewService.maybePersistPreview(saveGameId);

        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(expiredContracts),
                List.copyOf(youthIntake), List.copyOf(youthDepartures),
                List.copyOf(preContractArrivals), List.copyOf(preContractDepartures),
                List.copyOf(loanReturns), List.copyOf(surplusListings),
                List.copyOf(watchlistEvents));
    }

    /**
     * Any club over the squad ceiling after its loans return lists its weakest professionals as
     * SURPLUS until it is back at the cap. Returns the managed club's listings for the summary.
     */
    private List<SquadChangeResponse> listSurplusAfterReturns(UUID saveGameId, UUID managedClubId) {
        List<SquadChangeResponse> managedListings = new ArrayList<>();
        Map<UUID, List<Player>> byClub = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect())
                .collect(Collectors.groupingBy(Player::getClubId));

        List<Player> toSave = new ArrayList<>();
        for (Map.Entry<UUID, List<Player>> entry : byClub.entrySet()) {
            int overflow = entry.getValue().size() - SquadRules.MAX_SQUAD_SIZE;
            if (overflow <= 0) {
                continue;
            }
            List<Player> weakestFirst = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                    .toList();
            for (Player player : weakestFirst.subList(0, overflow)) {
                player.listForTransfer(ListedReason.SURPLUS);
                toSave.add(player);
                if (managedClubId.equals(entry.getKey())) {
                    managedListings.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
            }
        }
        playerRepository.saveAll(toSave);
        return managedListings;
    }

    /**
     * Archives one finished competition: its champion, its first-phase table, its top scorers and
     * its per-player aggregates.
     *
     * @param memberManagedClubId the managed club's id when it is a <b>member</b> of this
     *                             competition, and {@code null} when it is not. Null is what marks
     *                             a background competition here, and the caller derives it from
     *                             {@link BackgroundCompetitionService#isManaged} — never from the
     *                             final classification. Passing null does not merely blank three
     *                             columns: it puts the managed club out of this method's reach
     *                             entirely, so no position can be fabricated for it.
     */
    private void archive(UUID saveGameId, UUID userId, Competition competition, int finishingYear,
                         UUID memberManagedClubId, Map<UUID, String> clubNameById) {
        // SeasonStanding archives the first phase's table — the honest league record. For a
        // phase-less competition (e.g. Série B), phases is empty and phaseId is null, which is
        // today's "whole competition" behaviour, unchanged.
        List<CompetitionPhase> phases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(competition.getId());
        UUID firstPhaseId = phases.isEmpty() ? null : phases.get(0).getId();
        List<StandingRow> table = standingsService.getStandings(
                saveGameId, competition.getId(), firstPhaseId, userId);
        List<TopScorerRow> scorers = topScorersService.getTopScorers(saveGameId, competition.getId(), userId);

        // Season.championClubId/managedClubPosition come from the final classification — the
        // knockout winner, not the (now purely-league) table leader.
        List<UUID> finalOrder = finalClassificationOrder(saveGameId, competition, phases, table);
        UUID championClubId = finalOrder.get(0);
        String championClubName = clubNameById.getOrDefault(championClubId, "");

        Season season;
        if (memberManagedClubId == null) {
            // Background competition: champion and table only. No managed club, no position —
            // null, not a stand-in. See Season.createForBackgroundCompetition and V52.
            season = Season.createForBackgroundCompetition(saveGameId, competition.getId(),
                    finishingYear, competition.getName(), championClubId, championClubName);
        } else {
            int managedPosition = finalOrder.indexOf(memberManagedClubId) + 1;
            if (managedPosition == 0) {
                // Defensive only, and reachable solely for a club that IS a member: membership is
                // established by the caller, so a miss here means the classification lost a member.
                managedPosition = finalOrder.size();
            }
            season = Season.create(saveGameId, competition.getId(), finishingYear,
                    competition.getName(), championClubId, championClubName, memberManagedClubId,
                    clubNameById.getOrDefault(memberManagedClubId, ""), managedPosition);
        }
        seasonRepository.save(season);

        int position = 1;
        for (StandingRow row : table) {
            seasonStandingRepository.save(SeasonStanding.create(season.getId(), position++,
                    row.clubId(), row.clubName(), row.played(), row.won(), row.drawn(), row.lost(),
                    row.goalsFor(), row.goalsAgainst(), row.goalDifference(), row.points()));
        }
        Map<UUID, Player> playersById = playerRepository
                .findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        int rank = 1;
        for (TopScorerRow scorer : scorers) {
            // TopScorersService reports the player's CURRENT club, and a manager-terminated
            // contract leaves him club-less while the archive still has to run (the archive is
            // step 1; contracts only lapse in step 3). season_top_scorer.club_id is NOT NULL, so
            // a released top scorer used to abort the whole rollover — a save that cannot advance.
            UUID scoringClubId = scoringClubIdOf(scorer, playersById);
            if (scoringClubId == null) {
                // Club-less with no release mark either: nothing honest to name, so omit the row
                // rather than write a placeholder. Not reachable through gameplay today.
                continue;
            }
            seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), rank++,
                    scorer.playerId(), scorer.playerName(), scoringClubId,
                    clubNameById.getOrDefault(scoringClubId, ""), scorer.goals()));
        }

        for (Map.Entry<UUID, br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate> entry
                : playerStatsService.aggregateCompetition(saveGameId, competition.getId()).entrySet()) {
            br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate agg = entry.getValue();
            br.com.carvoeiromanager.player.Player player = playersById.get(entry.getKey());
            String playerName = player != null ? player.getName() : "";
            String clubName = agg.clubId() != null ? clubNameById.getOrDefault(agg.clubId(), "") : "";
            seasonPlayerStatsRepository.save(SeasonPlayerStats.create(season.getId(), entry.getKey(), playerName,
                    agg.clubId(), clubName, agg.matchesPlayed(), agg.minutesPlayed(), agg.goals(),
                    agg.assists(), agg.fouls(), agg.yellowCards(), agg.redCards()));
        }
    }

    /**
     * The club an archived top-scorer row should name: his current club, or — when he was released
     * mid-season and is a free agent by the time the archive runs — the club that released him,
     * which is the club he actually scored those goals for. Null when neither is known, which the
     * caller treats as "omit the row".
     *
     * <p>Deliberately resolved here and not in {@link TopScorersService}: that service also feeds
     * the live in-season screens, where a released player must show as a free agent rather than
     * still wearing his ex-club's name.
     */
    private UUID scoringClubIdOf(TopScorerRow scorer, Map<UUID, Player> playersById) {
        if (scorer.clubId() != null) {
            return scorer.clubId();
        }
        Player player = playersById.get(scorer.playerId());
        return player != null ? player.getReleasedByClubId() : null;
    }

    /**
     * The Brazilian classificação final for archiving: champion, runner-up, then each knockout
     * round's losers, then non-qualifiers. A competition with no knockout phases returns its
     * plain table order.
     *
     * <p>A declared knockout phase with no materialized fixtures is a bug, not a state to paper
     * over: this record is persisted history, so archiving the table order instead would record
     * a plausible-looking champion that never won anything, with nothing surfacing the fault.
     * Fail loudly instead.
     *
     * <p>This now runs for background competitions too, whose brackets are materialized and played
     * by {@link BackgroundCompetitionService#resolveDue} via {@code PhaseResolver}. Should that
     * ever regress, the failure surfaces here — at rollover, far from its cause — so the message
     * names the competition by name as well as by id.
     */
    private List<UUID> finalClassificationOrder(UUID saveGameId, Competition competition,
                                                 List<CompetitionPhase> phases,
                                                 List<StandingRow> firstPhaseTable) {
        UUID competitionId = competition.getId();
        List<CompetitionPhase> knockoutPhases = phases.stream()
                .filter(p -> p.getKind() == PhaseKind.KNOCKOUT)
                .toList();
        if (knockoutPhases.isEmpty()) {
            return firstPhaseTable.stream().map(StandingRow::clubId).toList();
        }
        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId);
        Map<UUID, List<Fixture>> fixturesByPhase = allFixtures.stream()
                .filter(f -> f.getPhaseId() != null)
                .collect(Collectors.groupingBy(Fixture::getPhaseId));
        knockoutPhases.stream()
                .filter(p -> fixturesByPhase.getOrDefault(p.getId(), List.of()).isEmpty())
                .findFirst()
                .ifPresent(p -> { throw new IllegalStateException(
                        "Knockout phase " + p.getName() + " (" + p.getId() + ") has no fixtures; "
                        + "cannot archive a final classification for competition "
                        + competition.getName() + " (" + competitionId + ")"); });
        return FinalClassification.compute(firstPhaseTable, knockoutPhases, fixturesByPhase);
    }
}
