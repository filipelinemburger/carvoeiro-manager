package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void monthlyWageIsFourTimesWeeklyWage() {
        // monthlyWage = weeklyWage * 4
        assertThat(calc.monthlyWage(70, 25)).isEqualByComparingTo(
                calc.weeklyWage(70, 25).multiply(new BigDecimal("4")));
        assertThat(calc.monthlyWage(70, 19)).isEqualByComparingTo(
                calc.weeklyWage(70, 19).multiply(new BigDecimal("4")));
        assertThat(calc.monthlyWage(70, 33)).isEqualByComparingTo(
                calc.weeklyWage(70, 33).multiply(new BigDecimal("4")));
        // higher overall => higher monthly wage
        assertThat(calc.monthlyWage(80, 25)).isGreaterThan(calc.monthlyWage(70, 25));
    }

    @Test
    void monthlySponsorshipIsProportionalToReputation() {
        // R$130 000 × reputation
        assertThat(calc.monthlySponsorship(60)).isEqualByComparingTo("7800000.00");
        assertThat(calc.monthlySponsorship(40)).isEqualByComparingTo("5200000.00");
        assertThat(calc.monthlySponsorship(90)).isEqualByComparingTo("11700000.00");
        // strictly increasing with reputation
        assertThat(calc.monthlySponsorship(70)).isGreaterThan(calc.monthlySponsorship(60));
    }

    @Test
    void monthlyUpkeepIsProportionalToStadiumCapacity() {
        // R$6 × stadiumCapacity
        assertThat(calc.monthlyUpkeep(38000)).isEqualByComparingTo("228000.00");
        assertThat(calc.monthlyUpkeep(20000)).isEqualByComparingTo("120000.00");
        // larger stadium => higher upkeep
        assertThat(calc.monthlyUpkeep(50000)).isGreaterThan(calc.monthlyUpkeep(38000));
    }

    @Test
    void matchCostIsProportionalToStadiumCapacity() {
        // R$2 × stadiumCapacity per match
        assertThat(calc.matchCost(38000)).isEqualByComparingTo("76000.00");
        assertThat(calc.matchCost(20000)).isEqualByComparingTo("40000.00");
        // larger stadium => higher matchCost
        assertThat(calc.matchCost(50000)).isGreaterThan(calc.matchCost(38000));
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
        assertThat(calc.monthlySponsorship(67)).isEqualByComparingTo("8710000.00"); // 67*130000
        assertThat(calc.monthlyUpkeep(41500)).isEqualByComparingTo("249000.00");   // 41500*6
    }

    @Test
    void gateIncomeIsAttendanceTimesTicketPriceAndNeverNegative() {
        // gateIncome now just turns a realized crowd into money (crowd sizing moved to
        // AttendanceCalculator). Attendance values below reproduce the old formula's outputs
        // so the expected totals are unchanged.

        // was: fill = 0.40 + 70/200 + 60/400 = 0.90; attendance = round(43000*0.90) = 38700
        assertThat(calc.gateIncome(38700)).isEqualByComparingTo("1935000.00");

        // was: rep 90/90 in a 1000-seat stadium clamps fill to 1.0, so attendance == capacity
        assertThat(calc.gateIncome(1000)).isEqualByComparingTo("50000.00");

        // was: rep 80/70 (fill=0.975, attendance=48750) vs rep 50/50 (fill=0.775, attendance=38750)
        // for the same 50 000-seat capacity — higher attendance => higher (or equal) gate
        assertThat(calc.gateIncome(48750)).isGreaterThan(calc.gateIncome(38750));

        // gateIncome clamps attendance at zero; it must never return a negative amount
        assertThat(calc.gateIncome(-100)).isEqualByComparingTo("0.00");
    }

    @Test
    void prizeMoneyDecreasesWithPositionAndIsAlwaysPositive() {
        assertThat(calc.prizeMoney(1, 10)).isEqualByComparingTo("5000000.00");  // 500000*10
        assertThat(calc.prizeMoney(10, 10)).isEqualByComparingTo("500000.00");  // 500000*1
        assertThat(calc.prizeMoney(1, 10)).isGreaterThan(calc.prizeMoney(2, 10));
        assertThat(calc.prizeMoney(10, 10).signum()).isPositive();
    }
}
