package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record PreContractOfferRequest(UUID playerId, BigDecimal wage, int contractYears) {}
