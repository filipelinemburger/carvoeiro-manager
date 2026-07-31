package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceCalculatorTest {

    private final AttendanceCalculator calculator = new AttendanceCalculator();

    private static final Weather SUNNY = new Weather(WeatherCondition.ENSOLARADO, 22);
    private static final Weather DOWNPOUR = new Weather(WeatherCondition.CHUVA_FORTE, 18);

    @Test
    void attendanceNeverExceedsCapacity() {
        int attendance = calculator.attendance(10000, 90, 90, 15, 1, 20, SUNNY);
        assertThat(attendance).isLessThanOrEqualTo(10000);
    }

    @Test
    void attendanceIsNeverNegative() {
        int attendance = calculator.attendance(3000, 40, 40, 0, 20, 20, DOWNPOUR);
        assertThat(attendance).isGreaterThan(0);
    }

    @Test
    void goodFormDrawsABiggerCrowdThanBadForm() {
        int good = calculator.attendance(20000, 60, 60, 15, 10, 20, SUNNY);
        int bad = calculator.attendance(20000, 60, 60, 0, 10, 20, SUNNY);
        assertThat(good).isGreaterThan(bad);
    }

    @Test
    void leadingTheTableDrawsABiggerCrowdThanPropUpTheTable() {
        int top = calculator.attendance(20000, 60, 60, 8, 1, 20, SUNNY);
        int bottom = calculator.attendance(20000, 60, 60, 8, 20, 20, SUNNY);
        assertThat(top).isGreaterThan(bottom);
    }

    @Test
    void heavyRainAlwaysReducesTheCrowd() {
        int dry = calculator.attendance(20000, 60, 60, 8, 10, 20, SUNNY);
        int wet = calculator.attendance(20000, 60, 60, 8, 10, 20, DOWNPOUR);
        assertThat(wet).isLessThan(dry);
    }

    @Test
    void extremeTemperatureAddsAFurtherPenalty() {
        int mild = calculator.attendance(20000, 60, 60, 8, 10, 20,
                new Weather(WeatherCondition.ENSOLARADO, 24));
        int scorching = calculator.attendance(20000, 60, 60, 8, 10, 20,
                new Weather(WeatherCondition.ENSOLARADO, 37));
        assertThat(scorching).isLessThan(mild);
    }

    @Test
    void aSingleClubCompetitionDoesNotDivideByZero() {
        int attendance = calculator.attendance(20000, 60, 60, 8, 1, 1, SUNNY);
        assertThat(attendance).isPositive();
    }

    @Test
    void theSpreadBetweenBestAndWorstCaseMatchesTheSpec() {
        int best = calculator.attendance(20000, 60, 60, 15, 1, 20, SUNNY);
        int worst = calculator.attendance(20000, 60, 60, 0, 20, 20,
                new Weather(WeatherCondition.CHUVA_FORTE, 37));
        assertThat(worst / (double) best).isBetween(0.45, 0.55);
    }
}
