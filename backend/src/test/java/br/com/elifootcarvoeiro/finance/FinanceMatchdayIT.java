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

        Fixture fx = Fixture.create(s.getId(), compA.getId(), 0, a1.getId(), a2.getId(), LocalDate.of(2026, 1, 15));
        fixtureRepository.save(fx);

        financeService.processMatchday(s.getId(), compA.getId(), List.of(fx));

        // Members charged (balances change); outsider untouched.
        assertThat(clubRepository.findById(a1.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a1Before);
        assertThat(clubRepository.findById(a2.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a2Before);
        assertThat(clubRepository.findById(outsider.getId()).orElseThrow().getBalance()).isEqualByComparingTo(outBefore);
    }

    @Test
    void processMatchdayWritesManagedClubLedgerRowsWithTheMatchdayRound() {
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
        Fixture fx = Fixture.create(s.getId(), compA.getId(), round, a1.getId(), a2.getId(), LocalDate.of(2026, 1, 15));
        fixtureRepository.save(fx);

        FinanceRoundResponse breakdown = financeService.processMatchday(s.getId(), compA.getId(), List.of(fx));

        // Managed-club breakdown is populated (not the empty fallback).
        assertThat(breakdown.gate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(breakdown.sponsorship()).isEqualByComparingTo(new BigDecimal("480000"));
        assertThat(breakdown.upkeep()).isEqualByComparingTo(new BigDecimal("80000"));
        assertThat(breakdown.prize()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(breakdown.net()).isEqualByComparingTo(
                breakdown.gate().add(breakdown.sponsorship()).subtract(breakdown.wages()).subtract(breakdown.upkeep()));

        // Ledger rows are written for the managed club and carry the matchday's round (5), not 0.
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(s.getId(), a1.getId());
        assertThat(ledger).extracting(FinanceTransaction::getType)
                .contains(FinanceTransactionType.GATE, FinanceTransactionType.SPONSOR,
                        FinanceTransactionType.WAGES, FinanceTransactionType.UPKEEP);
        assertThat(ledger).allSatisfy(t -> assertThat(t.getRound()).isEqualTo(round));
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
