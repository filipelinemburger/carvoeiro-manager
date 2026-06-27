package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record FreeAgentResponse(
        UUID playerId, String name, String position, int age, int overall, BigDecimal wageDemand) {}
