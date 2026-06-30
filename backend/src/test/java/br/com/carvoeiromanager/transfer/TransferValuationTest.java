package br.com.carvoeiromanager.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferValuationTest {

    private final TransferValuation valuation = new TransferValuation();

    @Test
    void marketValueScalesWithOverallAndAge() {
        // base = overall^3 * 5; 70^3 = 343000 -> 1,715,000
        // prime age factor 1.00
        assertThat(valuation.marketValue(70, 25)).isEqualByComparingTo("1715000.00");
        // youth (<=21) factor 1.05
        assertThat(valuation.marketValue(70, 19)).isEqualByComparingTo("1800750.00");
        // decline (<=32) factor 0.70
        assertThat(valuation.marketValue(70, 31)).isEqualByComparingTo("1200500.00");
        // veteran (>=35) factor 0.20
        assertThat(valuation.marketValue(70, 36)).isEqualByComparingTo("343000.00");
    }

    @Test
    void higherOverallIsWorthMoreAtTheSameAge() {
        assertThat(valuation.marketValue(80, 25)).isGreaterThan(valuation.marketValue(70, 25));
    }

    @Test
    void primeIsWorthMoreThanVeteranAtSameOverall() {
        assertThat(valuation.marketValue(75, 25)).isGreaterThan(valuation.marketValue(75, 36));
    }
}
