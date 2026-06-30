package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;

public record SignFreeAgentRequest(BigDecimal wage, int contractYears) {}
