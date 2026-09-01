package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.finance.FinanceCalculator;
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

    @Test
    void terminationFeeIsHalfOfTheRemainingWageBill() {
        // monthlyWage(70,25) = 70*70*20*1.00*4 = 392000.00
        // fee = years * 10 months * 392000.00 * 0.50
        assertThat(valuation.terminationFee(70, 25, 3)).isEqualByComparingTo("5880000.00");
        assertThat(valuation.terminationFee(70, 25, 1)).isEqualByComparingTo("1960000.00");
        assertThat(valuation.terminationFee(60, 25, 2)).isEqualByComparingTo("2880000.00");
    }

    @Test
    void terminationFeeFollowsTheAgeCurveLikeEveryOtherWageFormula() {
        // A veteran's 0.80 factor makes him cheaper to release than the same player in his prime.
        assertThat(valuation.terminationFee(70, 33, 2)).isEqualByComparingTo("3136000.00");
        assertThat(valuation.terminationFee(70, 33, 2))
                .isLessThan(valuation.terminationFee(70, 25, 2));
    }

    @Test
    void aLongerContractIsProportionallyMoreExpensiveToTerminate() {
        assertThat(valuation.terminationFee(70, 25, 4))
                .isEqualByComparingTo(valuation.terminationFee(70, 25, 2).multiply(new BigDecimal("2")));
    }
}
