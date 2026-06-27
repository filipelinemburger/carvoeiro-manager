package br.com.elifootcarvoeiro.finance;

import java.math.BigDecimal;

public record FinanceRoundResponse(
        BigDecimal gate,
        BigDecimal sponsorship,
        BigDecimal wages,
        BigDecimal upkeep,
        BigDecimal prize,
        BigDecimal net,
        BigDecimal balance
) {}
