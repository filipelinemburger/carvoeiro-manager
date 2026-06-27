package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ContractValuationTest {

    private final FinanceCalculator finance = new FinanceCalculator();
    private final ContractValuation valuation = new ContractValuation(finance);

    @Test
    void wageDemandIsFifteenPercentAboveBaselineWage() {
        // weeklyWage(70,25) = 70*70*20 * 1.00 = 98000.00 ; demand = *1.15 = 112700.00
        assertThat(valuation.wageDemand(70, 25)).isEqualByComparingTo("112700.00");
    }

    @Test
    void acceptsWageOnlyAtOrAboveTheDemand() {
        BigDecimal demand = valuation.wageDemand(70, 25); // 112700.00
        assertThat(valuation.acceptsWage(70, 25, demand)).isTrue();
        assertThat(valuation.acceptsWage(70, 25, demand.add(new BigDecimal("0.01")))).isTrue();
        assertThat(valuation.acceptsWage(70, 25, demand.subtract(new BigDecimal("0.01")))).isFalse();
        assertThat(valuation.acceptsWage(70, 25, null)).isFalse();
    }

    @Test
    void higherOverallDemandsMore() {
        assertThat(valuation.wageDemand(80, 25)).isGreaterThan(valuation.wageDemand(70, 25));
    }

    @Test
    void lengthValidationAndDefault() {
        assertThat(valuation.defaultContractYears()).isEqualTo(3);
        assertThat(valuation.isValidLength(1)).isTrue();
        assertThat(valuation.isValidLength(5)).isTrue();
        assertThat(valuation.isValidLength(0)).isFalse();
        assertThat(valuation.isValidLength(6)).isFalse();
    }
}
