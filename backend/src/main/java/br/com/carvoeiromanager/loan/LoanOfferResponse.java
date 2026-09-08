package br.com.carvoeiromanager.loan;

import java.util.UUID;

/**
 * The outcome of proposing a loan. {@code refusal} is null on ACCEPTED; on REJECTED it says why
 * the player is off the table, and on COUNTERED it is TERMS_REJECTED — the only refusal a better
 * offer can answer.
 * <p>
 * {@code managedClubIsOwner} says which side of the loan the manager's own club sits on
 * ({@code offer.getOwnerClubId().equals(save.getManagedClubId())}). Every wage-share number here
 * is always "the owner's share" regardless of direction, so the same percentage means opposite
 * things depending on this flag — a client rendering these terms without it (a flat "70% de
 * salário") cannot say whether that 70% is coming out of the manager's own budget or the AI
 * owner's.
 */
public record LoanOfferResponse(UUID offerId, UUID playerId, String playerName,
                                LoanOfferStatus status, boolean managedClubIsOwner,
                                int ownerWageShare, boolean mayFaceOwner,
                                Integer counterOwnerWageShare, Boolean counterMayFaceOwner,
                                LoanRefusal refusal) {
}
