package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record MarketPlayerResponse(
        UUID playerId, String name, String position, int age, int overall,
        UUID clubId, String clubName, BigDecimal value, BigDecimal wage, BigDecimal wageDemand) {}
