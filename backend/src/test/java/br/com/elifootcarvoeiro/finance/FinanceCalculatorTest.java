package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCalculatorTest {

    private final FinanceCalculator calc = new FinanceCalculator();

    private Player withOverall(int overall) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "P", Position.MID,
                25, "Brasil", 50, 50, 50, 50, 50, 50, overall);
    }

    @Test
    void weeklyWageRisesWithOverallAndAppliesAgeFactor() {
        // base = overall^2 * 20; prime age factor 1.00
        assertThat(calc.weeklyWage(70, 25)).isEqualByComparingTo("98000.00"); // 4900*20
        // youth (<=20) factor 0.60
        assertThat(calc.weeklyWage(70, 19)).isEqualByComparingTo("58800.00");
        // veteran (>=32) factor 0.80
        assertThat(calc.weeklyWage(70, 33)).isEqualByComparingTo("78400.00");
        // higher overall => strictly higher wage
        assertThat(calc.weeklyWage(80, 25)).isGreaterThan(calc.weeklyWage(70, 25));
    }

    @Test
    void reputationIsClampedSquadAverage() {
        assertThat(calc.reputation(List.of(withOverall(70), withOverall(70), withOverall(60)))).isEqualTo(67);
        // clamp floor 40
        assertThat(calc.reputation(List.of(withOverall(10), withOverall(10)))).isEqualTo(40);
        // clamp ceiling 90
        assertThat(calc.reputation(List.of(withOverall(99), withOverall(99)))).isEqualTo(90);
    }

    @Test
    void stadiumAndProfileFormulas() {
        assertThat(calc.stadiumCapacity(67)).isEqualTo(41500);        // 8000 + 67*500
        assertThat(calc.startingBalance(67)).isEqualByComparingTo("13400000.00"); // 67*200000
        assertThat(calc.sponsorshipPerRound(67)).isEqualByComparingTo("536000.00"); // 67*8000
        assertThat(calc.upkeepPerRound(41500)).isEqualByComparingTo("166000.00");   // 41500*4
    }

    @Test
    void gateIncomeNeverExceedsCapacityAndRisesWithReputation() {
        // fill = 0.40 + 70/200 + 60/400 = 0.90; attendance = round(43000*0.90)=38700; gate=*50
        assertThat(calc.gateIncome(70, 60, 43000)).isEqualByComparingTo("1935000.00");
        // a small stadium caps attendance at capacity: fill would be >1 only via clamp; here capacity binds
        BigDecimalAssertCap(calc, 90, 90, 1000);
        // higher reputations => higher (or equal) gate for the same capacity
        assertThat(calc.gateIncome(80, 70, 50000)).isGreaterThan(calc.gateIncome(50, 50, 50000));
    }

    private static void BigDecimalAssertCap(FinanceCalculator calc, int homeRep, int awayRep, int capacity) {
        // attendance can never exceed capacity, so gate <= capacity * 50
        assertThat(calc.gateIncome(homeRep, awayRep, capacity))
                .isLessThanOrEqualTo(new java.math.BigDecimal(capacity * 50).setScale(2));
    }

    @Test
    void prizeMoneyDecreasesWithPositionAndIsAlwaysPositive() {
        assertThat(calc.prizeMoney(1, 10)).isEqualByComparingTo("5000000.00");  // 500000*10
        assertThat(calc.prizeMoney(10, 10)).isEqualByComparingTo("500000.00");  // 500000*1
        assertThat(calc.prizeMoney(1, 10)).isGreaterThan(calc.prizeMoney(2, 10));
        assertThat(calc.prizeMoney(10, 10).signum()).isPositive();
    }
}
