package br.com.carvoeiromanager.finance;

import java.math.BigDecimal;

public record FinanceTransactionResponse(
        int seasonYear,
        int round,
        String type,
        BigDecimal amount,
        String description,
        String txDate
) {}
