package br.com.carvoeiromanager.transfer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic AI negotiation. Buy offers are priced off an ask factor built from the seller's
 * context (listing status, player importance, position cover, club finances), then accepted near
 * that effective value, countered in a negotiable band, or rejected; the AI accepts a managed
 * sell-counter up to a ceiling; unsolicited bids come in below value.
 */
@Component
public class TransferNegotiator {

    private static final BigDecimal ACCEPT_THRESHOLD = new BigDecimal("0.95");
    private static final BigDecimal NEGOTIABLE_FLOOR = new BigDecimal("0.70");
    private static final BigDecimal COUNTER_FACTOR = new BigDecimal("1.10");
    private static final BigDecimal SELL_COUNTER_MAX = new BigDecimal("1.15");
    private static final BigDecimal BID_FACTOR = new BigDecimal("0.90");
    private static final BigDecimal LISTED_BID_FACTOR = new BigDecimal("0.95");

    private static final BigDecimal LISTED_ASK_FACTOR = new BigDecimal("0.85");
    private static final BigDecimal UNLISTED_BASE_ASK_FACTOR = BigDecimal.ONE;
    private static final BigDecimal KEY_PLAYER_PREMIUM = new BigDecimal("0.20");
    private static final BigDecimal NO_COVER_PREMIUM = new BigDecimal("0.15");
    private static final BigDecimal DESPERATE_HEALTH_MULTIPLIER = new BigDecimal("0.75");
    private static final BigDecimal TIGHT_HEALTH_MULTIPLIER = new BigDecimal("0.90");
    private static final BigDecimal HEALTHY_HEALTH_MULTIPLIER = BigDecimal.ONE;
    private static final BigDecimal MIN_ASK_FACTOR = new BigDecimal("0.65");
    private static final BigDecimal MAX_ASK_FACTOR = new BigDecimal("1.50");

    public NegotiationResult respondToBuy(BigDecimal value, BigDecimal offer, SellerContext context) {
        BigDecimal effective = scaled(value, askFactor(context));
        if (offer.compareTo(scaled(effective, ACCEPT_THRESHOLD)) >= 0) {
            return new NegotiationResult(NegotiationDecision.ACCEPT, null);
        }
        if (offer.compareTo(scaled(effective, NEGOTIABLE_FLOOR)) >= 0) {
            return new NegotiationResult(NegotiationDecision.COUNTER, scaled(effective, COUNTER_FACTOR));
        }
        return new NegotiationResult(NegotiationDecision.REJECT, null);
    }

    /**
     * Listed players are priced to move (0.85). Unlisted players start at 1.00 and get pricier
     * the more the seller values them: +0.20 if they're a key player (top-11 overall in their
     * squad), +0.15 if their position has no cover. The whole thing is scaled by the seller's
     * financial health (desperate clubs sell cheap) and clamped to [0.65, 1.50].
     */
    private BigDecimal askFactor(SellerContext context) {
        BigDecimal base = context.listed()
                ? LISTED_ASK_FACTOR
                : UNLISTED_BASE_ASK_FACTOR
                        .add(context.keyPlayer() ? KEY_PLAYER_PREMIUM : BigDecimal.ZERO)
                        .add(context.noCover() ? NO_COVER_PREMIUM : BigDecimal.ZERO);
        BigDecimal factor = base.multiply(healthMultiplier(context.health()));
        if (factor.compareTo(MIN_ASK_FACTOR) < 0) {
            return MIN_ASK_FACTOR;
        }
        if (factor.compareTo(MAX_ASK_FACTOR) > 0) {
            return MAX_ASK_FACTOR;
        }
        return factor;
    }

    private BigDecimal healthMultiplier(ClubFinancialHealthEvaluator.FinancialHealth health) {
        return switch (health) {
            case DESPERATE -> DESPERATE_HEALTH_MULTIPLIER;
            case TIGHT -> TIGHT_HEALTH_MULTIPLIER;
            case HEALTHY -> HEALTHY_HEALTH_MULTIPLIER;
        };
    }

    public boolean acceptsSellCounter(BigDecimal value, BigDecimal managedCounter) {
        return managedCounter.compareTo(scaled(value, SELL_COUNTER_MAX)) <= 0;
    }

    /** Unsolicited AI bid: 95% of value for a player the seller has listed, 90% otherwise. */
    public BigDecimal bidAmount(BigDecimal value, boolean listed) {
        return scaled(value, listed ? LISTED_BID_FACTOR : BID_FACTOR);
    }

    private BigDecimal scaled(BigDecimal value, BigDecimal factor) {
        return value.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
