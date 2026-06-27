package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final FixtureRepository fixtureRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final FinanceCalculator financeCalculator;
    private final StandingsCalculator standingsCalculator = new StandingsCalculator();

    public FinanceService(SaveGameService saveGameService,
                          SaveGameRepository saveGameRepository,
                          ClubRepository clubRepository,
                          PlayerRepository playerRepository,
                          CompetitionRepository competitionRepository,
                          FixtureRepository fixtureRepository,
                          FinanceTransactionRepository financeTransactionRepository,
                          FinanceCalculator financeCalculator) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
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
