package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record PreContractAgreementResponse(UUID id, UUID playerId, String playerName,
                                           String position, int age, int overall,
                                           String fromClubName, String toClubName,
                                           BigDecimal wage, int contractYears,
                                           String status, boolean incoming) {}
