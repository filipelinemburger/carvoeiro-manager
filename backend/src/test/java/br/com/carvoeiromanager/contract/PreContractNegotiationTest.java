package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.contract.PreContractNegotiation.OfferVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PreContractNegotiationTest {

    // demand() needs ContractValuation (Spring bean); test the pure parts directly and
    // demand() via a hand-built instance in the IT (Task 3). classify/keepProbability are pure.
    private final PreContractNegotiation negotiation = new PreContractNegotiation(null);

    @Test
    void offerAtOrAboveDemandAgrees() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("1000.00"), demand)).isEqualTo(OfferVerdict.AGREE);
        assertThat(negotiation.classify(new BigDecimal("1500.00"), demand)).isEqualTo(OfferVerdict.AGREE);
    }

    @Test
    void offerBetweenSeventyPercentAndDemandCounters() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("700.00"), demand)).isEqualTo(OfferVerdict.COUNTER);
        assertThat(negotiation.classify(new BigDecimal("999.99"), demand)).isEqualTo(OfferVerdict.COUNTER);
    }

    @Test
    void offerBelowSeventyPercentRejects() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("699.99"), demand)).isEqualTo(OfferVerdict.REJECT);
        assertThat(negotiation.classify(new BigDecimal("1.00"), demand)).isEqualTo(OfferVerdict.REJECT);
    }

    @Test
    void keepProbabilityFollowsTheSpecFormula() {
        // base case: equal reputations, age 28 -> 0.30
        assertThat(negotiation.keepProbability(60, 60, 28)).isCloseTo(0.30, within(1e-9));
        // rep advantage and age both raise it: 0.30 + 0.05*4 + 0.02*4 = 0.58
        assertThat(negotiation.keepProbability(64, 60, 32)).isCloseTo(0.58, within(1e-9));
    }

    @Test
    void keepProbabilityIsClampedToTheSpecBounds() {
        assertThat(negotiation.keepProbability(40, 90, 18)).isCloseTo(0.05, within(1e-9));
        assertThat(negotiation.keepProbability(90, 40, 40)).isCloseTo(0.70, within(1e-9));
    }
}
