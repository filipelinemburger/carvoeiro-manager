package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record MakeOfferRequest(UUID playerId, BigDecimal amount, BigDecimal wage, int contractYears) {}
