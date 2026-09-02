package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;

/** The AI's answer to a set of loan terms; {@code counter} is null unless the decision is COUNTER. */
public record LoanNegotiationResult(NegotiationDecision decision, LoanTerms counter) {
}
