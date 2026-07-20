package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure pre-contract economics (spec constants live here, nowhere else):
 * a player under contract demands a 10% premium over his normal wage demand; an offer
 * at 70-99% of the demand draws a single counter at the demand; the current club's
 * counter-renew chance follows the spec's clamped linear formula.
 */
@Component
public class PreContractNegotiation {

    public static final BigDecimal PREMIUM = new BigDecimal("1.10");
    public static final BigDecimal COUNTER_FLOOR = new BigDecimal("0.70");
    public static final BigDecimal DEFENSE_FACTOR = new BigDecimal("1.05");

    private final ContractValuation contractValuation;

    public PreContractNegotiation(ContractValuation contractValuation) {
        this.contractValuation = contractValuation;
    }

    public enum OfferVerdict { AGREE, COUNTER, REJECT }

    public BigDecimal demand(int overall, int age) {
        return contractValuation.wageDemand(overall, age)
                .multiply(PREMIUM).setScale(2, RoundingMode.HALF_UP);
    }

    public OfferVerdict classify(BigDecimal offered, BigDecimal demand) {
        if (offered.compareTo(demand) >= 0) {
            return OfferVerdict.AGREE;
        }
        if (offered.compareTo(demand.multiply(COUNTER_FLOOR)) >= 0) {
            return OfferVerdict.COUNTER;
        }
        return OfferVerdict.REJECT;
    }

    /** clamp(0.30 + 0.05*(fromRep - toRep) + 0.02*(age - 28), 0.05, 0.70) per the spec. */
    public double keepProbability(int fromClubReputation, int toClubReputation, int age) {
        double p = 0.30 + 0.05 * (fromClubReputation - toClubReputation) + 0.02 * (age - 28);
        return Math.max(0.05, Math.min(0.70, p));
    }
}
