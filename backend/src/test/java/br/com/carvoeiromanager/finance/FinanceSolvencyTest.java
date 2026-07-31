package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A season's arithmetic for a mid-table club, run at both ends of the real capacity range.
 * Guards the recalibration that real stadium capacities forced: a small club must not be
 * bankrupted by simply existing.
 */
class FinanceSolvencyTest {

    private static final int MONTHS = 10;
    private static final int HOME_MATCHES = 9;
    private static final int TOTAL_MATCHES = 18;
    private static final int SQUAD_SIZE = 22;
    private static final int AVG_AGE = 26;

    private final FinanceCalculator finance = new FinanceCalculator();
    private final AttendanceCalculator attendance = new AttendanceCalculator();

    @Test
    void aSmallClubSurvivesASeason() {
        assertThat(seasonNet(3000, 45)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aMidSizedClubSurvivesASeason() {
        assertThat(seasonNet(19225, 60)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aLargeClubSurvivesASeason() {
        assertThat(seasonNet(63903, 75)).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * Net change over a season: sponsorship and prize in, wages, upkeep, and match costs out.
     *
     * <p>Squad average overall is deliberately set equal to {@code reputation}, not some fixed
     * value: {@link FinanceCalculator#reputation(java.util.List)} returns the clamped average
     * squad overall, so reputation and squad quality are the same quantity in this model, not
     * independent inputs. Decoupling them here would charge/credit a club for a squad it
     * doesn't have.
     */
    private BigDecimal seasonNet(int capacity, int reputation) {
        BigDecimal wages = finance.monthlyWage(reputation, AVG_AGE)
                .multiply(BigDecimal.valueOf((long) SQUAD_SIZE * MONTHS));
        BigDecimal sponsorship = finance.monthlySponsorship(reputation)
                .multiply(BigDecimal.valueOf(MONTHS));
        BigDecimal upkeep = finance.monthlyUpkeep(capacity)
                .multiply(BigDecimal.valueOf(MONTHS));
        BigDecimal matchCosts = finance.matchCost(capacity)
                .multiply(BigDecimal.valueOf(TOTAL_MATCHES));

        int crowd = attendance.attendance(capacity, reputation, reputation, 8, 10, 20, Weather.neutral());
        BigDecimal gate = finance.gateIncome(crowd).multiply(BigDecimal.valueOf(HOME_MATCHES));
        BigDecimal prize = finance.prizeMoney(10, 20);

        return sponsorship.add(gate).add(prize).subtract(wages).subtract(upkeep).subtract(matchCosts);
    }
}
