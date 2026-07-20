package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * AI clubs' keep-or-release decision for their own final-contract-year players, run
 * during the pre-contract window. Conservative by design: only clearly surplus players
 * (old, or well below squad standard) are released, and never below the squad floor —
 * released players become pre-contract targets and, if unsigned, real free agents.
 */
@Component
public class AiContractRenewalPolicy {

    public static final int RELEASE_AGE = 34;
    public static final int MEDIAN_TOLERANCE = 5;
    public static final int SQUAD_FLOOR = 19;

    public boolean shouldRelease(int age, int overall, int squadMedianOverall, int remainingSquadSize) {
        if (remainingSquadSize <= SQUAD_FLOOR) {
            return false;
        }
        return age >= RELEASE_AGE || overall < squadMedianOverall - MEDIAN_TOLERANCE;
    }

    public int renewYears(Random rng) {
        return 2 + rng.nextInt(3);
    }
}
