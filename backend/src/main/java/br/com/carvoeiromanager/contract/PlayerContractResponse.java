package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record PlayerContractResponse(UUID playerId, String name, BigDecimal wage, int contractYears) {}
