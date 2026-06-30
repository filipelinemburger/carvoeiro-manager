package br.com.carvoeiromanager.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferOfferResponse(
        UUID id, UUID playerId, String playerName,
        UUID fromClubId, String fromClubName, UUID toClubId, String toClubName,
        String direction, BigDecimal amount, BigDecimal counterAmount, String status, int round,
        BigDecimal wage, Integer contractYears) {}
