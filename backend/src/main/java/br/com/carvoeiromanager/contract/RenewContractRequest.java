package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;

public record RenewContractRequest(BigDecimal wage, int contractYears) {}
