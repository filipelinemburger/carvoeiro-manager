package br.com.carvoeiromanager.transfer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic AI negotiation. Buy offers are accepted near value, countered in a
 * negotiable band, or rejected; the AI accepts a managed sell-counter up to a ceiling;
 * unsolicited bids come in below value.
 */
@Component
public class TransferNegotiator {

    private static final BigDecimal ACCEPT_THRESHOLD = new BigDecimal("0.95");
    private static final BigDecimal NEGOTIABLE_FLOOR = new BigDecimal("0.70");
    private static final BigDecimal COUNTER_FACTOR = new BigDecimal("1.10");
    private static final BigDecimal SELL_COUNTER_MAX = new BigDecimal("1.15");
    private static final BigDecimal BID_FACTOR = new BigDecimal("0.90");

    public NegotiationResult respondToBuy(BigDecimal value, BigDecimal offer) {
        if (offer.compareTo(scaled(value, ACCEPT_THRESHOLD)) >= 0) {
            return new NegotiationResult(NegotiationDecision.ACCEPT, null);
        }
        if (offer.compareTo(scaled(value, NEGOTIABLE_FLOOR)) >= 0) {
            return new NegotiationResult(NegotiationDecision.COUNTER, scaled(value, COUNTER_FACTOR));
        }
        return new NegotiationResult(NegotiationDecision.REJECT, null);
    }

    public boolean acceptsSellCounter(BigDecimal value, BigDecimal managedCounter) {
        return managedCounter.compareTo(scaled(value, SELL_COUNTER_MAX)) <= 0;
    }

    public BigDecimal bidAmount(BigDecimal value) {
        return scaled(value, BID_FACTOR);
    }

    private BigDecimal scaled(BigDecimal value, BigDecimal factor) {
        return value.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
