package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.springframework.stereotype.Component;

/**
 * Prices a plausible loan. There is one market-clearing owner-share for a given player and
 * borrower, and both sides are measured against it: the AI as borrower takes anything at or above
 * it, the AI as owner anything at or below it. Deterministic, no RNG.
 */
@Component
public class LoanNegotiator {

    /** What a player sitting exactly on the borrower's floor costs the owner in subsidy. */
    static final int BASE_SHARE = 60;
    /** How much of that subsidy each point of overall above the floor buys back. */
    static final int SHARE_PER_OVERALL = 10;
    /** Refusing to let him face his owner is a concession the other side charges for. */
    static final int FIXTURE_RELEASE_PREMIUM = 15;

    public LoanNegotiationResult respond(LoanTerms offered, int playerOverall,
                                         int borrowerReputation, boolean aiIsOwner) {
        int required = requiredOwnerShare(playerOverall, borrowerReputation, offered.mayFaceOwner());
        boolean acceptable = aiIsOwner
                ? offered.ownerWageShare() <= required   // the owner will not pay more than this
                : offered.ownerWageShare() >= required;  // the borrower needs at least this much
        if (acceptable) {
            return new LoanNegotiationResult(NegotiationDecision.ACCEPT, null);
        }
        // A counter moves both terms toward the responder: a borrower wants to field him against
        // his owner, an owner wants to prevent it — and the share is re-priced for that term.
        boolean counterMayFaceOwner = !aiIsOwner;
        int counterShare = requiredOwnerShare(playerOverall, borrowerReputation, counterMayFaceOwner);
        return new LoanNegotiationResult(NegotiationDecision.COUNTER,
                new LoanTerms(counterShare, counterMayFaceOwner));
    }

    /** The owner-paid share that clears the market for this player at this borrower, 0-100. */
    int requiredOwnerShare(int playerOverall, int borrowerReputation, boolean mayFaceOwner) {
        int floor = borrowerReputation - LoanPlausibility.LEVEL_TOLERANCE;
        int surplus = playerOverall - floor;
        int share = BASE_SHARE - surplus * SHARE_PER_OVERALL;
        if (!mayFaceOwner) {
            share += FIXTURE_RELEASE_PREMIUM;
        }
        return Math.max(0, Math.min(100, share));
    }
}
