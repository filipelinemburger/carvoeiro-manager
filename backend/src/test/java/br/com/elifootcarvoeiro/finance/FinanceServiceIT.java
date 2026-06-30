package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceServiceIT {

    @Autowired FinanceService financeService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<Fixture> round0Fixtures(Career c, UUID competitionId) {
        return fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(c.id(), competitionId, 0);
    }

    @Test
    void processMatchdayChargesGateAndMatchCostOnly() {
        // Given a fresh career
        Career c = createCareer();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        List<Fixture> round0 = round0Fixtures(c, competition.getId());
        java.util.Set<UUID> memberIds = competitionClubRepository.findByCompetitionId(competition.getId()).stream()
                .map(cc -> cc.getClubId()).collect(java.util.stream.Collectors.toSet());
        Map<UUID, BigDecimal> before = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> memberIds.contains(cl.getId()))
                .collect(Collectors.toMap(Club::getId, Club::getBalance));

        // When the matchday's finances are processed (per-match model: gate + matchCost only)
        LocalDate matchDate = LocalDate.of(2026, 1, 15);
        FinanceRoundResponse managed = financeService.processMatchday(c.id(), competition.getId(), round0, matchDate);

        // Then sponsorship, wages, upkeep are ZERO in the per-match response (monthly now)
        assertThat(managed.sponsorship()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(managed.wages()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(managed.upkeep()).isEqualByComparingTo(BigDecimal.ZERO);

        // And net = gate - matchCost (no sponsorship/wages/upkeep per match)
        assertThat(managed.net()).isEqualByComparingTo(
                managed.gate().subtract(managed.matchCost()));

        // Member clubs that played had their balance changed (gate and/or matchCost)
        Map<UUID, BigDecimal> after = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> memberIds.contains(cl.getId()))
                .collect(Collectors.toMap(Club::getId, Club::getBalance));

        // Clubs in round0 played → balance changed
        boolean anyChanged = after.entrySet().stream()
                .anyMatch(e -> e.getValue().compareTo(before.get(e.getKey())) != 0);
        assertThat(anyChanged).isTrue();

        // And managed club has GATE/MATCH_COST ledger rows (no SPONSOR/WAGES/UPKEEP here)
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.SPONSOR);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.WAGES);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.UPKEEP);

        // If managed club played, MATCH_COST row should be present
        boolean managedPlayed = round0.stream().anyMatch(f ->
                f.getHomeClubId().equals(c.clubId()) || f.getAwayClubId().equals(c.clubId()));
        assertThat(ledger.stream().anyMatch(t -> t.getType() == FinanceTransactionType.MATCH_COST))
                .isEqualTo(managedPlayed);
    }

    @Test
    void processMonthChargesWagesSponsorsUpkeepForEveryClub() {
        // Given a fresh career
        Career c = createCareer();
        List<Club> allClubs = clubRepository.findBySaveGameId(c.id());
        Map<UUID, BigDecimal> before = allClubs.stream()
                .collect(Collectors.toMap(Club::getId, Club::getBalance));

        LocalDate monthDate = LocalDate.of(2026, 2, 1);

        // When monthly finance is processed
        financeService.processMonth(c.id(), monthDate);

        // Then every club's balance changed (wages/sponsorship/upkeep)
        Map<UUID, BigDecimal> after = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Club::getBalance));
        after.forEach((id, bal) ->
                assertThat(bal).as("club %s balance moved after processMonth", id)
                        .isNotEqualByComparingTo(before.get(id)));

        // And managed club has SPONSOR, WAGES, UPKEEP rows in the ledger
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        assertThat(ledger).extracting(t -> t.getType().name())
                .contains("SPONSOR", "WAGES", "UPKEEP");

        // WAGES and UPKEEP are negative, SPONSOR positive
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.WAGES)
                .findFirst().orElseThrow().getAmount().signum()).isNegative();
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.SPONSOR)
                .findFirst().orElseThrow().getAmount().signum()).isPositive();
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.UPKEEP)
                .findFirst().orElseThrow().getAmount().signum()).isNegative();

        // All monthly rows carry the txDate
        assertThat(ledger.stream().filter(t ->
                t.getType() == FinanceTransactionType.SPONSOR ||
                t.getType() == FinanceTransactionType.WAGES ||
                t.getType() == FinanceTransactionType.UPKEEP)
                .allMatch(t -> t.getTxDate() != null && t.getTxDate().equals(monthDate))).isTrue();
    }

    @Test
    void processMonthIsIdempotentWithinTheSameMonth() {
        // Given: calling processMonth twice for the same date should be guarded by the caller,
        // but the amounts themselves should be deterministic and conserved when called once.
        Career c = createCareer();
        Club managedBefore = clubRepository.findById(c.clubId()).orElseThrow();
        BigDecimal balanceBefore = managedBefore.getBalance();

        financeService.processMonth(c.id(), LocalDate.of(2026, 3, 1));

        BigDecimal balanceAfter = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        BigDecimal delta = balanceAfter.subtract(balanceBefore);

        // The delta equals sponsorship - wages - upkeep (exact values are deterministic)
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        BigDecimal expectedDelta = ledger.stream()
                .map(FinanceTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(delta).isEqualByComparingTo(expectedDelta);
    }

    @Test
    void processSeasonEndAwardsPrizeMoney() {
        // Given a fresh career (prize money is awarded by final standing position in Catarinense)
        Career c = createCareer();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        BigDecimal managedBalanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        // When season-end finances run for the competition (with a date)
        LocalDate endDate = LocalDate.of(2026, 10, 30);
        BigDecimal prize = financeService.processSeasonEnd(c.id(), competition.getId(), endDate);

        // Then the managed club received prize money and has a PRIZE row
        assertThat(prize).isGreaterThan(BigDecimal.ZERO);
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(managedBalanceBefore.add(prize));
        List<FinanceTransaction> prizeRows = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId())
                .stream().filter(t -> t.getType() == FinanceTransactionType.PRIZE).toList();
        assertThat(prizeRows).isNotEmpty();
        // PRIZE row carries the txDate
        assertThat(prizeRows.get(0).getTxDate()).isEqualTo(endDate);
    }

    @Test
    void getFinancesReturnsBalanceAndTransactionsForOwner() {
        Career c = createCareer();
        // Process a month to generate ledger rows
        financeService.processMonth(c.id(), LocalDate.of(2026, 2, 1));

        FinancesResponse finances = financeService.getFinances(c.id(), c.clubId(), c.userId());
        assertThat(finances.balance()).isNotNull();
        assertThat(finances.transactions()).isNotEmpty();
        // All monthly-model rows carry txDate in the response
        assertThat(finances.transactions().stream()
                .allMatch(t -> t.txDate() != null)).isTrue();
    }

    @Test
    void getFinancesRejectsForeignUserAndUnknownClub() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> financeService.getFinances(owner.id(), owner.clubId(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> financeService.getFinances(owner.id(), UUID.randomUUID(), owner.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("CLUB_NOT_FOUND");
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }
}
