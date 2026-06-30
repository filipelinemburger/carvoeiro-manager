package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs club finances each round and at season end, and serves the managed club's
 * finances to the API. Deterministic (delegates all amounts to FinanceCalculator).
 * Itemized ledger rows are written ONLY for the managed club.
 *
 * <h3>New monthly model (as of V10)</h3>
 * <ul>
 *   <li><b>Per match</b> (processMatchday): gate income (home clubs) + matchCost (every playing club).</li>
 *   <li><b>Per month</b> (processMonth): wages, sponsorship, stadium upkeep — charged once per in-game
 *       month for ALL clubs. Called from SeasonAdvanceService when the event date enters a new month.</li>
 *   <li><b>Season end</b> (processSeasonEnd): prize money, unchanged.</li>
 * </ul>
 */
@Service
public class FinanceService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final FinanceCalculator financeCalculator;
    private final StandingsCalculator standingsCalculator = new StandingsCalculator();

    public FinanceService(SaveGameService saveGameService,
                          SaveGameRepository saveGameRepository,
                          ClubRepository clubRepository,
                          PlayerRepository playerRepository,
                          CompetitionRepository competitionRepository,
                          CompetitionClubRepository competitionClubRepository,
                          FixtureRepository fixtureRepository,
                          FinanceTransactionRepository financeTransactionRepository,
                          FinanceCalculator financeCalculator) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.financeCalculator = financeCalculator;
    }

    /**
     * Processes per-match finances for a single matchday within a specific competition.
     * Charges ONLY:
     * <ul>
     *   <li>Gate income — home clubs in the provided fixtures.</li>
     *   <li>Match cost — every club that played in a fixture (home or away).</li>
     * </ul>
     * Sponsorship, wages, and upkeep are NOT charged here (they are monthly).
     * Ledger rows (GATE, MATCH_COST) are written only when the managed club participated.
     *
     * @param matchDate the in-game date of the matchday (stamped on ledger rows)
     */
    @Transactional
    public FinanceRoundResponse processMatchday(UUID saveGameId, UUID competitionId,
                                                List<Fixture> matchdayFixtures, LocalDate matchDate) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        // Only the competition's member clubs are charged this matchday.
        List<UUID> memberIds = competitionClubRepository.findByCompetitionId(competitionId).stream()
                .map(cc -> cc.getClubId())
                .toList();

        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));

        Set<UUID> homeClubs = matchdayFixtures.stream()
                .map(Fixture::getHomeClubId)
                .collect(Collectors.toCollection(HashSet::new));

        // All clubs that played in any fixture this matchday (home or away).
        Set<UUID> playingClubs = new HashSet<>();
        for (Fixture f : matchdayFixtures) {
            playingClubs.add(f.getHomeClubId());
            playingClubs.add(f.getAwayClubId());
        }

        int round = matchdayFixtures.isEmpty() ? 0 : matchdayFixtures.get(0).getRound();

        BigDecimal mGate = BigDecimal.ZERO;
        BigDecimal mMatchCost = BigDecimal.ZERO;
        BigDecimal mBalance = BigDecimal.ZERO;
        boolean managedIsProcessed = false;

        for (UUID clubId : memberIds) {
            Club club = clubById.get(clubId);
            if (club == null) continue;

            BigDecimal gate = BigDecimal.ZERO;
            if (homeClubs.contains(clubId)) {
                UUID awayId = matchdayFixtures.stream()
                        .filter(f -> f.getHomeClubId().equals(clubId))
                        .map(Fixture::getAwayClubId)
                        .findFirst()
                        .orElse(null);
                Club away = awayId != null ? clubById.get(awayId) : null;
                int awayRep = away != null ? away.getReputation() : 0;
                gate = financeCalculator.gateIncome(club.getReputation(), awayRep, club.getStadiumCapacity());
            }

            BigDecimal matchCost = playingClubs.contains(clubId)
                    ? financeCalculator.matchCost(club.getStadiumCapacity())
                    : BigDecimal.ZERO;

            BigDecimal net = gate.subtract(matchCost);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (clubId.equals(managedClubId)) {
                managedIsProcessed = true;
                mGate = gate;
                mMatchCost = matchCost;
                mBalance = club.getBalance();
                if (gate.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.GATE, gate, "Renda de bilheteria", matchDate);
                }
                if (matchCost.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.MATCH_COST, matchCost.negate(), "Custo da partida", matchDate);
                }
            }
        }

        if (!managedIsProcessed) {
            Club managed = clubById.get(managedClubId);
            mBalance = managed != null ? managed.getBalance() : BigDecimal.ZERO;
        }

        BigDecimal net = mGate.subtract(mMatchCost);
        // Sponsorship, wages, upkeep are zero per-match in the new model.
        return new FinanceRoundResponse(mGate, mMatchCost, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, net, mBalance);
    }

    /**
     * Charges monthly costs (wages, sponsorship, stadium upkeep) for EVERY club in the save.
     * Called once per in-game month on first entry into that month during advance.
     * Ledger rows are written only for the managed club.
     *
     * @param monthDate the in-game date that triggered the month boundary (stamped on ledger rows)
     */
    @Transactional
    public void processMonth(UUID saveGameId, LocalDate monthDate) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final int month = monthDate.getYear() * 100 + monthDate.getMonthValue();
        // Self-idempotency guard (defense in depth): never charge a month at or before the
        // last one charged. The caller enters each new month at most once, but this also makes
        // a repeated/direct call safe and lets processMonth own the lastFinanceMonth advance.
        if (month <= saveGame.getLastFinanceMonth()) {
            return;
        }
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();
        // Monthly costs are not tied to a match round; ledger rows are grouped by their tx_date.
        final int round = 0;

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Map<UUID, BigDecimal> wageBillByClub = monthlyWageBills(saveGameId);

        for (Club club : clubs) {
            BigDecimal wages = wageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal sponsorship = financeCalculator.monthlySponsorship(club.getReputation());
            BigDecimal upkeep = financeCalculator.monthlyUpkeep(club.getStadiumCapacity());

            BigDecimal net = sponsorship.subtract(wages).subtract(upkeep);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (club.getId().equals(managedClubId)) {
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio mensal", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários mensais", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção mensal do estádio", monthDate);
            }
        }
        saveGame.setLastFinanceMonth(month);
        saveGameRepository.save(saveGame);
    }

    /**
     * Distributes prize money based on final standings for a specific competition.
     * Returns the managed club's prize (ZERO if managed club is not in this competition).
     *
     * @param date the in-game date at which the season ended (stamped on the PRIZE row)
     */
    @Transactional
    public BigDecimal processSeasonEnd(UUID saveGameId, UUID competitionId, LocalDate date) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        // Load only the clubs belonging to this competition.
        List<UUID> memberIds = competitionClubRepository.findByCompetitionId(competitionId).stream()
                .map(cc -> cc.getClubId())
                .toList();
        List<Club> memberClubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> memberIds.contains(c.getId()))
                .toList();

        Competition competition = competitionRepository.findById(competitionId).orElseThrow();
        List<Fixture> played = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId);
        List<StandingRow> table = standingsCalculator.compute(memberClubs, played);

        Map<UUID, Club> clubById = memberClubs.stream().collect(Collectors.toMap(Club::getId, c -> c));
        BigDecimal managedPrize = BigDecimal.ZERO;
        int position = 1;
        for (StandingRow row : table) {
            BigDecimal prize = financeCalculator.prizeMoney(position, memberClubs.size());
            Club club = clubById.get(row.clubId());
            if (club != null) {
                club.adjustBalance(prize);
                clubRepository.save(club);
                if (club.getId().equals(managedClubId)) {
                    managedPrize = prize;
                    record(saveGameId, managedClubId, seasonYear, competition.getTotalRounds(),
                            FinanceTransactionType.PRIZE, prize, "Premiação - " + position + "º lugar", date);
                }
            }
            position++;
        }
        return managedPrize;
    }

    public FinancesResponse getFinances(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Club club = clubRepository.findById(clubId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException("CLUB_NOT_FOUND", "Club not found", HttpStatus.NOT_FOUND));

        List<FinanceTransactionResponse> transactions = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(saveGameId, clubId).stream()
                .map(t -> new FinanceTransactionResponse(t.getSeasonYear(), t.getRound(),
                        t.getType().name(), t.getAmount(), t.getDescription(),
                        t.getTxDate() != null ? t.getTxDate().toString() : null))
                .toList();
        return new FinancesResponse(club.getBalance(), transactions);
    }

    // ---- Private helpers ----

    private Map<UUID, BigDecimal> monthlyWageBills(UUID saveGameId) {
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null)
                .collect(Collectors.groupingBy(Player::getClubId,
                        Collectors.reducing(BigDecimal.ZERO,
                                p -> financeCalculator.monthlyWage(p.getOverall(), p.getAge()),
                                BigDecimal::add)));
    }

    private void record(UUID saveGameId, UUID clubId, int seasonYear, int round,
                        FinanceTransactionType type, BigDecimal amount, String description,
                        LocalDate txDate) {
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGameId, clubId, seasonYear, round, type, amount, description, txDate));
    }
}
