package br.com.carvoeiromanager.finance;

import java.math.BigDecimal;
import java.util.List;

public record FinancesResponse(BigDecimal balance, List<FinanceTransactionResponse> transactions) {}
