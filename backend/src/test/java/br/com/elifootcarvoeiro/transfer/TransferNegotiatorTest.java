package br.com.elifootcarvoeiro.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransferNegotiatorTest {

    private final TransferNegotiator negotiator = new TransferNegotiator();
    private final BigDecimal value = new BigDecimal("1000000.00");

    @Test
    void buyOfferAtOrAboveNinetyFivePercentIsAccepted() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("950000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(r.counterAmount()).isNull();
    }

    @Test
    void buyOfferInTheNegotiableBandIsCounteredAtOneTenth() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("800000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(r.counterAmount()).isEqualByComparingTo("1100000.00"); // value * 1.10
    }

    @Test
    void buyOfferBelowSeventyPercentIsRejected() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("500000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.REJECT);
        assertThat(r.counterAmount()).isNull();
    }

    @Test
    void aiAcceptsASellCounterUpToOneFifteenOfValue() {
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.00"))).isTrue();
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.01"))).isFalse();
    }

    @Test
    void unsolicitedBidIsNinetyPercentOfValue() {
        assertThat(negotiator.bidAmount(value)).isEqualByComparingTo("900000.00");
    }
}
