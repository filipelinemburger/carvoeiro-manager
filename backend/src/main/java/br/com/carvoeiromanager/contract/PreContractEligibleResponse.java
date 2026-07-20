package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record PreContractEligibleResponse(UUID playerId, String name, String position,
                                          int age, int overall, String clubName,
                                          BigDecimal wageDemand) {}
