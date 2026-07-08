package br.com.carvoeiromanager.transfer;

import java.math.BigDecimal;

/**
 * One AI-to-AI transfer completed during a market day, surfaced in the "Transferências" news
 * block. {@code fromClubName} is the selling club (the player's old home), {@code toClubName}
 * the buying club — matching the "{playerName}: {fromClubName} → {toClubName} ({fee})" copy,
 * not {@link TransferOffer}'s buyer-is-"from" convention.
 */
public record CompletedTransferResponse(String playerName, String fromClubName, String toClubName, BigDecimal fee) {
}
