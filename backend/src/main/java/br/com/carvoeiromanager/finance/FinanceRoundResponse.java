package br.com.carvoeiromanager.finance;

import java.math.BigDecimal;

public record FinanceRoundResponse(
        BigDecimal gate,
        BigDecimal matchCost,
        BigDecimal sponsorship,
        BigDecimal wages,
        BigDecimal upkeep,
        BigDecimal prize,
        BigDecimal net,
        BigDecimal balance
) {}
