package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;

public record RenewContractRequest(BigDecimal wage, int contractYears) {}
