package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
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

    public FinanceRoundResponse processRound(UUID saveGameId, int round, List<Fixture> roundFixtures) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Map<UUID, Club> clubById = clubs.stream().collect(Collectors.toMap(Club::getId, c -> c));
        Map<UUID, BigDecimal> wageBillByClub = wageBills(saveGameId);

        // Map each home club to its opponent (for gate attendance).
        Map<UUID, UUID> opponentOfHome = new HashMap<>();
        for (Fixture f : roundFixtures) {
            opponentOfHome.put(f.getHomeClubId(), f.getAwayClubId());
        }

        FinanceRoundResponse managedBreakdown = null;
        for (Club club : clubs) {
            BigDecimal wages = wageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal sponsorship = club.getSponsorshipPerRound();
            BigDecimal upkeep = club.getStadiumUpkeepPerRound();
            BigDecimal gate = BigDecimal.ZERO;
            if (opponentOfHome.containsKey(club.getId())) {
                Club away = clubById.get(opponentOfHome.get(club.getId()));
                int awayRep = away != null ? away.getReputation() : 0;
                gate = financeCalculator.gateIncome(club.getReputation(), awayRep, club.getStadiumCapacity());
            }
            BigDecimal net = gate.add(sponsorship).subtract(wages).subtract(upkeep);
            club.adjustBalance(net);

            if (club.getId().equals(managedClubId)) {
                if (gate.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.GATE, gate, "Renda de bilheteria");
                }
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção do estádio");
                managedBreakdown = new FinanceRoundResponse(gate, sponsorship, wages, upkeep,
                        BigDecimal.ZERO, net, club.getBalance());
            }
        }
        clubRepository.saveAll(clubs);

        if (managedBreakdown == null) { // managed club had no fixture nor profile (defensive)
            BigDecimal balance = clubById.containsKey(managedClubId)
                    ? clubById.get(managedClubId).getBalance() : BigDecimal.ZERO;
            managedBreakdown = new FinanceRoundResponse(BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, balance);
        }
        return managedBreakdown;
    }

    public BigDecimal processSeasonEnd(UUID saveGameId) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> played = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId());
        List<StandingRow> table = standingsCalculator.compute(clubs, played);

        Map<UUID, Club> clubById = clubs.stream().collect(Collectors.toMap(Club::getId, c -> c));
        BigDecimal managedPrize = BigDecimal.ZERO;
        int position = 1;
        for (StandingRow row : table) {
            BigDecimal prize = financeCalculator.prizeMoney(position, clubs.size());
            Club club = clubById.get(row.clubId());
            if (club != null) {
                club.adjustBalance(prize);
                if (club.getId().equals(managedClubId)) {
                    managedPrize = prize;
                    record(saveGameId, managedClubId, seasonYear, competition.getTotalRounds(),
                            FinanceTransactionType.PRIZE, prize, "Premiação - " + position + "º lugar");
                }
            }
            position++;
        }
        clubRepository.saveAll(clubs);
        return managedPrize;
    }

    /**
     * Processes finances for a single matchday within a specific competition.
     * Sponsorship, wages, and upkeep apply only to the competition's member clubs.
     * Gate income applies only to the home clubs in the provided matchday fixtures.
     * Ledger rows are written only when the managed club is among the processed members.
     */
    @Transactional
    public FinanceRoundResponse processMatchday(UUID saveGameId, UUID competitionId,
                                                List<Fixture> matchdayFixtures) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        // Only the competition's member clubs are charged this matchday.
        List<UUID> memberIds = competitionClubRepository.findByCompetitionId(competitionId).stream()
                .map(cc -> cc.getClubId())
                .toList();

        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        Map<UUID, BigDecimal> wageBillByClub = wageBills(saveGameId);

        Set<UUID> homeClubs = matchdayFixtures.stream()
                .map(Fixture::getHomeClubId)
                .collect(Collectors.toCollection(HashSet::new));

        int round = matchdayFixtures.isEmpty() ? 0 : matchdayFixtures.get(0).getRound();

        BigDecimal mGate = BigDecimal.ZERO;
        BigDecimal mSpon = BigDecimal.ZERO;
        BigDecimal mWages = BigDecimal.ZERO;
        BigDecimal mUpkeep = BigDecimal.ZERO;
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

            BigDecimal sponsorship = club.getSponsorshipPerRound();
            BigDecimal wages = wageBillByClub.getOrDefault(clubId, BigDecimal.ZERO);
            BigDecimal upkeep = club.getStadiumUpkeepPerRound();
            BigDecimal net = gate.add(sponsorship).subtract(wages).subtract(upkeep);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (clubId.equals(managedClubId)) {
                managedIsProcessed = true;
                mGate = gate;
                mSpon = sponsorship;
                mWages = wages;
                mUpkeep = upkeep;
                mBalance = club.getBalance();
                // Ledger rows use the matchday's round, derived from the fixtures.
                if (gate.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.GATE, gate, "Renda de bilheteria");
                }
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção do estádio");
            }
        }

        if (!managedIsProcessed) {
            Club managed = clubById.get(managedClubId);
            mBalance = managed != null ? managed.getBalance() : BigDecimal.ZERO;
        }

        BigDecimal net = mGate.add(mSpon).subtract(mWages).subtract(mUpkeep);
        return new FinanceRoundResponse(mGate, mSpon, mWages, mUpkeep, BigDecimal.ZERO, net, mBalance);
    }

    /**
     * Distributes prize money based on final standings for a specific competition.
     * Returns the managed club's prize (ZERO if managed club is not in this competition).
     */
    @Transactional
    public BigDecimal processSeasonEnd(UUID saveGameId, UUID competitionId) {
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
                            FinanceTransactionType.PRIZE, prize, "Premiação - " + position + "º lugar");
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
                        t.getType().name(), t.getAmount(), t.getDescription()))
                .toList();
        return new FinancesResponse(club.getBalance(), transactions);
    }

    private Map<UUID, BigDecimal> wageBills(UUID saveGameId) {
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null)
                .collect(Collectors.groupingBy(Player::getClubId,
                        Collectors.reducing(BigDecimal.ZERO, Player::getWage, BigDecimal::add)));
    }

    private void record(UUID saveGameId, UUID clubId, int seasonYear, int round,
                        FinanceTransactionType type, BigDecimal amount, String description) {
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGameId, clubId, seasonYear, round, type, amount, description));
    }
}
