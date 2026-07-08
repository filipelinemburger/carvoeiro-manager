package br.com.carvoeiromanager.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.HEALTHY;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.TIGHT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven coverage of {@link TransferNegotiator#respondToBuy}'s ask-factor math:
 * listed -> 0.85; unlisted -> 1.00 + 0.20 keyPlayer + 0.15 noCover; x health multiplier
 * (DESPERATE 0.75 / TIGHT 0.90 / HEALTHY 1.00); clamped to [0.65, 1.50]. Every case below
 * derives its expected effective value/accept/counter figures from that formula so the
 * assertions double as documentation of the game balance.
 */
class TransferNegotiatorTest {

    private final TransferNegotiator negotiator = new TransferNegotiator();
    private final BigDecimal value = new BigDecimal("1000000.00");

    private SellerContext ctx(boolean listed, boolean keyPlayer, boolean noCover,
                               ClubFinancialHealthEvaluator.FinancialHealth health) {
        return new SellerContext(listed, keyPlayer, noCover, health);
    }

    private BigDecimal pct(BigDecimal base, String factor) {
        return base.multiply(new BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- listed players: flat 0.85 ask factor, scaled by health ----

    @Test
    void listedHealthySellerAsksEightyFivePercentOfValue() {
        SellerContext context = ctx(true, false, false, HEALTHY);
        BigDecimal effective = pct(value, "0.85"); // 850000.00
        BigDecimal accept = pct(effective, "0.95"); // 807500.00 (~0.81 x value)

        NegotiationResult atAccept = negotiator.respondToBuy(value, accept, context);
        assertThat(atAccept.decision()).isEqualTo(NegotiationDecision.ACCEPT);

        NegotiationResult justBelow = negotiator.respondToBuy(value, accept.subtract(new BigDecimal("0.01")), context);
        assertThat(justBelow.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(justBelow.counterAmount()).isEqualByComparingTo(pct(effective, "1.10")); // 935000.00
    }

    @Test
    void listedTightSellerAsksEightyFivePercentTimesNinetyPercent() {
        SellerContext context = ctx(true, false, false, TIGHT);
        BigDecimal effective = pct(pct(value, "0.85"), "0.90"); // 765000.00
        BigDecimal accept = pct(effective, "0.95"); // 726750.00

        NegotiationResult r = negotiator.respondToBuy(value, accept, context);
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void listedDesperateSellerAskFactorClampsAtSixtyFivePercentFloor() {
        // 0.85 * 0.75 = 0.6375, below the 0.65 floor -> clamps to 0.65.
        SellerContext context = ctx(true, false, false, DESPERATE);
        BigDecimal clampedEffective = pct(value, "0.65"); // 650000.00
        BigDecimal accept = pct(clampedEffective, "0.95"); // 617500.00

        NegotiationResult atClampedAccept = negotiator.respondToBuy(value, accept, context);
        assertThat(atClampedAccept.decision()).isEqualTo(NegotiationDecision.ACCEPT);

        // An offer priced against the unclamped 0.6375 factor would fall short of the real
        // (clamped) accept threshold, proving the clamp actually kicked in.
        BigDecimal unclampedAccept = pct(pct(pct(value, "0.85"), "0.75"), "0.95"); // 605625.00
        NegotiationResult belowClampedFloor = negotiator.respondToBuy(value, unclampedAccept, context);
        assertThat(belowClampedFloor.decision()).isEqualTo(NegotiationDecision.COUNTER);
    }

    // ---- unlisted players: base 1.00 + premiums, x health ----

    @Test
    void unlistedOrdinaryHealthySellerAsksFullValue() {
        SellerContext context = ctx(false, false, false, HEALTHY);
        NegotiationResult accept = negotiator.respondToBuy(value, pct(value, "0.95"), context);
        assertThat(accept.decision()).isEqualTo(NegotiationDecision.ACCEPT);

        NegotiationResult counter = negotiator.respondToBuy(value, pct(value, "0.80"), context);
        assertThat(counter.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(counter.counterAmount()).isEqualByComparingTo(pct(value, "1.10"));

        NegotiationResult reject = negotiator.respondToBuy(value, pct(value, "0.50"), context);
        assertThat(reject.decision()).isEqualTo(NegotiationDecision.REJECT);
    }

    @Test
    void unlistedKeyPlayerHealthySellerAsksOneTwentyPercentOfValue() {
        SellerContext context = ctx(false, true, false, HEALTHY);
        BigDecimal effective = pct(value, "1.20"); // 1200000.00
        BigDecimal accept = pct(effective, "0.95"); // 1140000.00

        NegotiationResult r = negotiator.respondToBuy(value, accept, context);
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void unlistedNoCoverHealthySellerAsksOneFifteenPercentOfValue() {
        SellerContext context = ctx(false, false, true, HEALTHY);
        BigDecimal effective = pct(value, "1.15"); // 1150000.00
        BigDecimal accept = pct(effective, "0.95"); // 1092500.00

        NegotiationResult r = negotiator.respondToBuy(value, accept, context);
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void unlistedKeyPlayerWithoutCoverHealthySellerAsksOneThirtyFivePercentOfValue() {
        SellerContext context = ctx(false, true, true, HEALTHY);
        BigDecimal effective = pct(value, "1.35"); // 1350000.00
        BigDecimal accept = pct(effective, "0.95"); // 1282500.00 (~1.28 x value)

        NegotiationResult atAccept = negotiator.respondToBuy(value, accept, context);
        assertThat(atAccept.decision()).isEqualTo(NegotiationDecision.ACCEPT);

        NegotiationResult justBelow = negotiator.respondToBuy(value, accept.subtract(new BigDecimal("0.01")), context);
        assertThat(justBelow.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(justBelow.counterAmount()).isEqualByComparingTo(pct(effective, "1.10")); // 1485000.00

        BigDecimal floor = pct(effective, "0.70"); // 945000.00
        NegotiationResult atFloor = negotiator.respondToBuy(value, floor, context);
        assertThat(atFloor.decision()).isEqualTo(NegotiationDecision.COUNTER);

        NegotiationResult belowFloor = negotiator.respondToBuy(value, floor.subtract(new BigDecimal("0.01")), context);
        assertThat(belowFloor.decision()).isEqualTo(NegotiationDecision.REJECT);
    }

    @Test
    void unlistedKeyPlayerWithoutCoverDesperateSellerStillAcceptsALowerOffer() {
        // (1.00 + 0.20 + 0.15) * 0.75 = 1.0125
        SellerContext context = ctx(false, true, true, DESPERATE);
        BigDecimal effective = pct(value, "1.0125"); // 1012500.00
        BigDecimal accept = pct(effective, "0.95"); // 961875.00 -- below plain value, unlike the healthy case

        NegotiationResult r = negotiator.respondToBuy(value, accept, context);
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(accept).isLessThan(value);
    }

    @Test
    void unlistedKeyPlayerWithoutCoverTightSellerAsksInBetween() {
        // (1.00 + 0.20 + 0.15) * 0.90 = 1.215
        SellerContext context = ctx(false, true, true, TIGHT);
        BigDecimal effective = pct(value, "1.215"); // 1215000.00
        BigDecimal accept = pct(effective, "0.95"); // 1154250.00

        NegotiationResult r = negotiator.respondToBuy(value, accept, context);
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
    }

    // ---- unaffected negotiator behavior ----

    @Test
    void aiAcceptsASellCounterUpToOneFifteenOfValue() {
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.00"))).isTrue();
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.01"))).isFalse();
    }

    @Test
    void unsolicitedBidIsNinetyPercentOfValueWhenNotListed() {
        assertThat(negotiator.bidAmount(value, false)).isEqualByComparingTo("900000.00");
    }

    @Test
    void unsolicitedBidIsNinetyFivePercentOfValueWhenListed() {
        assertThat(negotiator.bidAmount(value, true)).isEqualByComparingTo("950000.00");
    }
}
