package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceServiceIT {

    @Autowired FinanceService financeService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
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

    @Test
    void processRoundMovesEveryClubBalanceAndItemizesTheManagedClub() {
        // Given a fresh career
        Career c = createCareer();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        List<Fixture> round0 = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competition.getId(), 0);
        Map<UUID, BigDecimal> before = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Club::getBalance));

        // When the round's finances are processed
        FinanceRoundResponse managed = financeService.processRound(c.id(), 0, round0);

        // Then every club's balance changed (income/wages/upkeep applied)
        Map<UUID, BigDecimal> after = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getBalance()));
        after.forEach((id, bal) -> assertThat(bal).as("club %s balance moved", id)
                .isNotEqualByComparingTo(before.get(id)));

        // And the managed club has itemized rows for round 0 (SPONSOR + WAGES + UPKEEP, plus GATE if home)
        boolean managedIsHome = round0.stream().anyMatch(f -> f.getHomeClubId().equals(c.clubId()));
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        assertThat(ledger).extracting(t -> t.getType().name())
                .contains("SPONSOR", "WAGES", "UPKEEP");
        assertThat(ledger.stream().anyMatch(t -> t.getType() == FinanceTransactionType.GATE))
                .isEqualTo(managedIsHome);
        // WAGES and UPKEEP are recorded as negative amounts; SPONSOR positive
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.WAGES)
                .findFirst().orElseThrow().getAmount().signum()).isNegative();
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.SPONSOR)
                .findFirst().orElseThrow().getAmount().signum()).isPositive();
        // The returned breakdown's net equals gate + sponsorship - wages - upkeep
        assertThat(managed.net()).isEqualByComparingTo(
                managed.gate().add(managed.sponsorship()).subtract(managed.wages()).subtract(managed.upkeep()));
    }

    @Test
    void processSeasonEndAwardsPrizeMoney() {
        // Given a fully-played season
        Career c = createCareer();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        BigDecimal managedBalanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        // When season-end finances run
        BigDecimal prize = financeService.processSeasonEnd(c.id());

        // Then the managed club received prize money and has a PRIZE row
        assertThat(prize).isGreaterThan(BigDecimal.ZERO);
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(managedBalanceBefore.add(prize));
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.PRIZE);
    }

    @Test
    void getFinancesReturnsBalanceAndTransactionsForOwner() {
        Career c = createCareer();
        financeService.processRound(c.id(), 0, fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competitionRepository.findBySaveGameId(c.id()).get(0).getId(), 0));

        FinancesResponse finances = financeService.getFinances(c.id(), c.clubId(), c.userId());
        assertThat(finances.balance()).isGreaterThan(BigDecimal.ZERO);
        assertThat(finances.transactions()).isNotEmpty();
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
