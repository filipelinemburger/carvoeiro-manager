package br.com.carvoeiromanager.loan;

import java.util.UUID;

/**
 * The outcome of proposing a loan. {@code refusal} is null on ACCEPTED; on REJECTED it says why
 * the player is off the table, and on COUNTERED it is TERMS_REJECTED — the only refusal a better
 * offer can answer.
 */
public record LoanOfferResponse(UUID offerId, UUID playerId, String playerName,
                                LoanOfferStatus status, int ownerWageShare, boolean mayFaceOwner,
                                Integer counterOwnerWageShare, Boolean counterMayFaceOwner,
                                LoanRefusal refusal) {
}
