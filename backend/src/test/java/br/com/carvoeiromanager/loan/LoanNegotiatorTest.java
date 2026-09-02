package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanNegotiatorTest {

    private final LoanNegotiator negotiator = new LoanNegotiator();

    @Test
    void theRequiredShareFallsAsThePlayerRisesAboveTheBorrowersFloor() {
        // reputation 70, tolerance 3 -> floor 67. overall 70 -> surplus 3 -> 60 - 30 = 30.
        assertThat(negotiator.requiredOwnerShare(70, 70, true)).isEqualTo(30);
        // overall 67 -> surplus 0 -> the full base share.
        assertThat(negotiator.requiredOwnerShare(67, 70, true)).isEqualTo(60);
        // A clear upgrade for a smaller club costs the owner nothing.
        assertThat(negotiator.requiredOwnerShare(72, 55, true)).isZero();
    }

    @Test
    void withholdingTheFixtureReleaseCostsFifteenPoints() {
        assertThat(negotiator.requiredOwnerShare(70, 70, false)).isEqualTo(45);
    }

    @Test
    void theShareNeverLeavesZeroToOneHundred() {
        assertThat(negotiator.requiredOwnerShare(99, 40, true)).isZero();
        assertThat(negotiator.requiredOwnerShare(40, 99, false)).isEqualTo(100);
    }

    @Test
    void anAiBorrowerAcceptsAtOrAboveTheRequiredSubsidy() {
        LoanNegotiationResult exact = negotiator.respond(new LoanTerms(30, true), 70, 70, false);
        assertThat(exact.decision()).isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(exact.counter()).isNull();

        assertThat(negotiator.respond(new LoanTerms(80, true), 70, 70, false).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void anAiBorrowerCountersUpwardsAndAsksForTheFixtureRelease() {
        LoanNegotiationResult r = negotiator.respond(new LoanTerms(10, false), 70, 70, false);

        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        // It wants to be allowed to field him against the owner...
        assertThat(r.counter().mayFaceOwner()).isTrue();
        // ...and prices the share for that term, not for the one it was offered.
        assertThat(r.counter().ownerWageShare()).isEqualTo(30);
    }

    @Test
    void anAiOwnerAcceptsAtOrBelowWhatItIsWillingToPay() {
        assertThat(negotiator.respond(new LoanTerms(30, true), 70, 70, true).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(negotiator.respond(new LoanTerms(0, true), 70, 70, true).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void anAiOwnerCountersDownwardsAndWithholdsTheFixtureRelease() {
        LoanNegotiationResult r = negotiator.respond(new LoanTerms(90, true), 70, 70, true);

        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(r.counter().mayFaceOwner()).isFalse();
        // Priced with the fixture withheld: 30 + 15.
        assertThat(r.counter().ownerWageShare()).isEqualTo(45);
    }

    @Test
    void aCounterAlwaysMovesBothTermsTowardTheResponder() {
        LoanTerms ownerCounter = negotiator.respond(new LoanTerms(90, true), 70, 70, true).counter();
        LoanTerms borrowerCounter = negotiator.respond(new LoanTerms(0, false), 70, 70, false).counter();

        assertThat(ownerCounter.mayFaceOwner()).isFalse();     // the owner withholds
        assertThat(borrowerCounter.mayFaceOwner()).isTrue();   // the borrower asks for it
        assertThat(ownerCounter.ownerWageShare()).isLessThan(90);
        assertThat(borrowerCounter.ownerWageShare()).isGreaterThan(0);
    }
}
