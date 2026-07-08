package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.HEALTHY;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.TIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClubFinancialHealthEvaluatorTest {

    @Mock PlayerRepository playerRepository;

    private final FinanceCalculator financeCalculator = new FinanceCalculator();
    private ClubFinancialHealthEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ClubFinancialHealthEvaluator(playerRepository, financeCalculator);
    }

    /** One overall-60, age-25 player: monthlyWage = weeklyWage(60,25)*4 = 72000*4 = 288000. */
    private Player onePlayer() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "P", Position.MID,
                25, "Brasil", 60, 60, 60, 60, 10, 60, 60);
    }

    private Club clubWithBalance(BigDecimal balance) {
        Club club = Club.create(UUID.randomUUID(), "Club", "CLB");
        club.setBalance(balance);
        return club;
    }

    @Test
    void balanceBelowOneMonthlyWageBillIsDesperate() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of(onePlayer()));
        Club club = clubWithBalance(new BigDecimal("287999.99")); // just under 1x bill (288000)

        assertThat(evaluator.evaluate(club)).isEqualTo(DESPERATE);
    }

    @Test
    void balanceAtExactlyOneMonthlyWageBillIsNotDesperate() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of(onePlayer()));
        Club club = clubWithBalance(new BigDecimal("288000.00")); // exactly 1x bill

        assertThat(evaluator.evaluate(club)).isNotEqualTo(DESPERATE);
        assertThat(evaluator.evaluate(club)).isEqualTo(TIGHT);
    }

    @Test
    void balanceBelowThreeMonthlyWageBillsIsTight() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of(onePlayer()));
        Club club = clubWithBalance(new BigDecimal("863999.99")); // just under 3x bill (864000)

        assertThat(evaluator.evaluate(club)).isEqualTo(TIGHT);
    }

    @Test
    void balanceAtExactlyThreeMonthlyWageBillsIsHealthy() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of(onePlayer()));
        Club club = clubWithBalance(new BigDecimal("864000.00")); // exactly 3x bill

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }

    @Test
    void balanceWellAboveThreeMonthlyWageBillsIsHealthy() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of(onePlayer()));
        Club club = clubWithBalance(new BigDecimal("10000000.00"));

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }

    @Test
    void anEmptySquadIsAlwaysHealthyRegardlessOfBalance() {
        when(playerRepository.findByClubId(any())).thenReturn(List.of());
        Club club = clubWithBalance(BigDecimal.ZERO);

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }
}
