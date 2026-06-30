package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionClub;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceMatchdayIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired FinanceService financeService;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private static final LocalDate MATCH_DATE = LocalDate.of(2026, 1, 15);

    @Test
    void processMatchdayChargesOnlyTheCompetitionsMembers() {
        User u = User.create("fin_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);

        // Competition A has clubA1 (home) + clubA2; clubOutsider is NOT a member.
        Competition compA = Competition.create(s.getId(), "A", "LEAGUE", 2026, 2);
        competitionRepository.save(compA);
        Club a1 = club(s.getId(), "A1"); Club a2 = club(s.getId(), "A2"); Club outsider = club(s.getId(), "OUT");
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a1.getId()));
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a2.getId()));

        BigDecimal a1Before = a1.getBalance();
        BigDecimal a2Before = a2.getBalance();
        BigDecimal outBefore = outsider.getBalance();

        Fixture fx = Fixture.create(s.getId(), compA.getId(), 0, a1.getId(), a2.getId(), MATCH_DATE);
        fixtureRepository.save(fx);

        financeService.processMatchday(s.getId(), compA.getId(), List.of(fx), MATCH_DATE);

        // Members charged (balances change); outsider untouched.
        assertThat(clubRepository.findById(a1.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a1Before);
        assertThat(clubRepository.findById(a2.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a2Before);
        assertThat(clubRepository.findById(outsider.getId()).orElseThrow().getBalance()).isEqualByComparingTo(outBefore);
    }

    @Test
    void processMatchdayWritesManagedClubLedgerRowsWithGateAndMatchCostOnly() {
        User u = User.create("fin_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);

        Competition compA = Competition.create(s.getId(), "A", "LEAGUE", 2026, 2);
        competitionRepository.save(compA);
        Club a1 = club(s.getId(), "A1");
        Club a2 = club(s.getId(), "A2");
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a1.getId()));
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a2.getId()));

        // Manage the home club so gate income is produced and ledger rows are written.
        s.setManagedClubId(a1.getId());
        saveGameRepository.save(s);

        int round = 5;
        Fixture fx = Fixture.create(s.getId(), compA.getId(), round, a1.getId(), a2.getId(), MATCH_DATE);
        fixtureRepository.save(fx);

        FinanceRoundResponse breakdown = financeService.processMatchday(s.getId(), compA.getId(), List.of(fx), MATCH_DATE);

        // New model: sponsorship, wages, upkeep are ZERO per match
        assertThat(breakdown.sponsorship()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(breakdown.wages()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(breakdown.upkeep()).isEqualByComparingTo(BigDecimal.ZERO);

        // Gate is populated (managed club is home)
        assertThat(breakdown.gate()).isGreaterThan(BigDecimal.ZERO);

        // matchCost is populated (managed club played)
        assertThat(breakdown.matchCost()).isGreaterThan(BigDecimal.ZERO);

        // Net = gate - matchCost
        assertThat(breakdown.net()).isEqualByComparingTo(
                breakdown.gate().subtract(breakdown.matchCost()));

        // Ledger rows: GATE and MATCH_COST present; no SPONSOR/WAGES/UPKEEP
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(s.getId(), a1.getId());
        assertThat(ledger).extracting(FinanceTransaction::getType)
                .contains(FinanceTransactionType.GATE, FinanceTransactionType.MATCH_COST);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.SPONSOR);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.WAGES);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.UPKEEP);

        // All rows carry the round and txDate
        assertThat(ledger).allSatisfy(t -> assertThat(t.getRound()).isEqualTo(round));
        assertThat(ledger).allSatisfy(t -> assertThat(t.getTxDate()).isEqualTo(MATCH_DATE));

        // MATCH_COST ledger amount is negative (debit)
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.MATCH_COST)
                .findFirst().orElseThrow().getAmount().signum()).isNegative();
    }

    @Test
    void awayClubIsAlsoChargedMatchCost() {
        // Given the away club is also managed
        User u = User.create("fin_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);

        Competition compA = Competition.create(s.getId(), "A", "LEAGUE", 2026, 2);
        competitionRepository.save(compA);
        Club home = club(s.getId(), "H1");
        Club away = club(s.getId(), "A1");
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), home.getId()));
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), away.getId()));

        // Manage the AWAY club
        s.setManagedClubId(away.getId());
        saveGameRepository.save(s);

        Fixture fx = Fixture.create(s.getId(), compA.getId(), 0, home.getId(), away.getId(), MATCH_DATE);
        fixtureRepository.save(fx);

        FinanceRoundResponse breakdown = financeService.processMatchday(s.getId(), compA.getId(), List.of(fx), MATCH_DATE);

        // Away club has no gate income but is charged matchCost
        assertThat(breakdown.gate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(breakdown.matchCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(breakdown.net()).isEqualByComparingTo(BigDecimal.ZERO.subtract(breakdown.matchCost()));

        // Ledger has MATCH_COST but no GATE
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(s.getId(), away.getId());
        assertThat(ledger).anyMatch(t -> t.getType() == FinanceTransactionType.MATCH_COST);
        assertThat(ledger).noneMatch(t -> t.getType() == FinanceTransactionType.GATE);
    }

    @Test
    void moneyConservation_processMatchdayDebitsByExactComputedAmount() {
        // Given the managed club is home
        User u = User.create("fin_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);

        Competition compA = Competition.create(s.getId(), "A", "LEAGUE", 2026, 2);
        competitionRepository.save(compA);
        Club a1 = club(s.getId(), "A1");
        Club a2 = club(s.getId(), "A2");
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a1.getId()));
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a2.getId()));
        s.setManagedClubId(a1.getId());
        saveGameRepository.save(s);

        BigDecimal balanceBefore = a1.getBalance();
        Fixture fx = Fixture.create(s.getId(), compA.getId(), 0, a1.getId(), a2.getId(), MATCH_DATE);
        fixtureRepository.save(fx);

        FinanceRoundResponse r = financeService.processMatchday(s.getId(), compA.getId(), List.of(fx), MATCH_DATE);

        BigDecimal balanceAfter = clubRepository.findById(a1.getId()).orElseThrow().getBalance();
        // balance changed by exactly net = gate - matchCost
        assertThat(balanceAfter).isEqualByComparingTo(balanceBefore.add(r.net()));
    }

    private Club club(UUID save, String shortName) {
        Club c = Club.create(save, shortName + " FC", shortName);
        c.setReputation(60); c.setStadiumCapacity(20000);
        c.setBalance(new BigDecimal("10000000"));
        c.setSponsorshipPerRound(new BigDecimal("480000"));
        c.setStadiumUpkeepPerRound(new BigDecimal("80000"));
        clubRepository.save(c);
        return c;
    }
}
