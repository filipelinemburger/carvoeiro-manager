package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, deterministic contract economics (no RNG). A player's wage demand is
 * DEMAND_PREMIUM above their 2B baseline wage, so it is in the same currency as
 * existing wages and rises as the 2E age curve grows a young player's overall.
 */
@Component
public class ContractValuation {

    static final BigDecimal DEMAND_PREMIUM = new BigDecimal("1.15");
    static final int DEFAULT_CONTRACT_YEARS = 3;
    static final int MIN_CONTRACT_YEARS = 1;
    static final int MAX_CONTRACT_YEARS = 5;

    private final FinanceCalculator financeCalculator;

    public ContractValuation(FinanceCalculator financeCalculator) {
        this.financeCalculator = financeCalculator;
    }

    public BigDecimal wageDemand(int overall, int age) {
        return financeCalculator.weeklyWage(overall, age)
                .multiply(DEMAND_PREMIUM).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean acceptsWage(int overall, int age, BigDecimal offered) {
        return offered != null && offered.compareTo(wageDemand(overall, age)) >= 0;
    }

    public int defaultContractYears() {
        return DEFAULT_CONTRACT_YEARS;
    }

    public boolean isValidLength(int years) {
        return years >= MIN_CONTRACT_YEARS && years <= MAX_CONTRACT_YEARS;
    }
}
