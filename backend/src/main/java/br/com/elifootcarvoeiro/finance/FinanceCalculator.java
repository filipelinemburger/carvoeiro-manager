package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.player.Player;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, deterministic money formulas (no RNG). All amounts are BigDecimal scaled to 2.
 * Constants are the locked initial calibration (see the plan's Global Constraints).
 *
 * <h3>Monthly-model calibration notes</h3>
 * The finance model charges wages, sponsorship, and stadium upkeep once per in-game month
 * (10 months per season, Jan–Oct) rather than per match-round. Gate income and matchCost
 * remain per-match. Calibration targets: a rep-60 club with ~22 players (avg overall 60)
 * should finish the season with a positive balance after 10 months, ~18 Catarinense matches
 * (9 home), and prize money.
 *
 * Key calibration constants:
 * <ul>
 *   <li>monthlyWage = weeklyWage × 4 (spec'd; individual player monthly payroll)</li>
 *   <li>MONTHLY_SPONSORSHIP_PER_REP = 100 000 (R$100 000 × reputation per month;
 *       for rep=60: R$6 000 000/month — roughly offsets most of the wage bill)</li>
 *   <li>MONTHLY_UPKEEP_PER_SEAT = R$6/seat/month; for cap=38 000: R$228 000/month</li>
 *   <li>MATCH_COST_PER_SEAT = R$2/seat per match played (each club, home or away)</li>
 * </ul>
 *
 * Sanity estimate for rep=60 (capacity=38 000, 22 players avg overall 60):
 * <pre>
 *   monthlyWages  ≈ 22 × 72 000 × 4 = 6 336 000
 *   monthlySpons  =     100 000 × 60 = 6 000 000
 *   monthlyUpkeep =          6 × 38 000 =   228 000
 *   net monthly (no gate) ≈ −564 000/month → −5 640 000 over 10 months
 *   9 home gate @ rep=60 vs rep=60 avg ≈ 14 535 000
 *   18 matchCosts @ 2 × 38 000            ≈  1 368 000
 *   net gate/cost                         ≈ 13 167 000
 *   prize (median position) ~3 000 000
 *   net season ≈ 12 000 000 + 13 167 000 − 5 640 000 + 3 000 000 ≈ +22.5 M  → solvent
 * </pre>
 */
@Component
public class FinanceCalculator {

    private static final BigDecimal TICKET_PRICE = new BigDecimal("50");
    private static final int WAGE_OVERALL_FACTOR = 20;
    private static final BigDecimal YOUTH_FACTOR = new BigDecimal("0.60");
    private static final BigDecimal VETERAN_FACTOR = new BigDecimal("0.80");
    private static final BigDecimal PRIME_FACTOR = new BigDecimal("1.00");
    private static final int REP_FLOOR = 40;
    private static final int REP_CEILING = 90;
    private static final BigDecimal STARTING_BALANCE_PER_REP = new BigDecimal("200000");
    /** Monthly sponsorship per reputation point. */
    private static final BigDecimal MONTHLY_SPONSORSHIP_PER_REP = new BigDecimal("100000");
    /** Monthly upkeep per stadium seat. */
    private static final BigDecimal MONTHLY_UPKEEP_PER_SEAT = new BigDecimal("6");
    /** Operational cost per stadium seat per match played (home or away). */
    private static final BigDecimal MATCH_COST_PER_SEAT = new BigDecimal("2");
    private static final BigDecimal PRIZE_BASE = new BigDecimal("500000");

    // --- Legacy per-round constants (kept for backward-compat uses in tests/seeding) ---
    private static final BigDecimal SPONSORSHIP_PER_REP = new BigDecimal("8000");
    private static final BigDecimal UPKEEP_PER_SEAT = new BigDecimal("4");

    public BigDecimal weeklyWage(int overall, int age) {
        BigDecimal base = BigDecimal.valueOf((long) overall * overall * WAGE_OVERALL_FACTOR);
        BigDecimal factor = age <= 20 ? YOUTH_FACTOR : age >= 32 ? VETERAN_FACTOR : PRIME_FACTOR;
        return scaled(base.multiply(factor));
    }

    /**
     * Monthly wage = weeklyWage × 4 (individual player).
     * Keeps weeklyWage untouched so transfer-valuation formulas remain unchanged.
     */
    public BigDecimal monthlyWage(int overall, int age) {
        return scaled(weeklyWage(overall, age).multiply(new BigDecimal("4")));
    }

    /**
     * Monthly sponsorship income for a club with the given reputation.
     * R$100 000 × reputation per month.
     */
    public BigDecimal monthlySponsorship(int reputation) {
        return scaled(MONTHLY_SPONSORSHIP_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

    /**
     * Monthly stadium upkeep cost for a club.
     * R$6 × stadiumCapacity per month.
     */
    public BigDecimal monthlyUpkeep(int stadiumCapacity) {
        return scaled(MONTHLY_UPKEEP_PER_SEAT.multiply(BigDecimal.valueOf(stadiumCapacity)));
    }

    /**
     * Per-match operational cost for a club that played in a fixture (home or away).
     * R$2 × stadiumCapacity.
     */
    public BigDecimal matchCost(int stadiumCapacity) {
        return scaled(MATCH_COST_PER_SEAT.multiply(BigDecimal.valueOf(stadiumCapacity)));
    }

    public int reputation(List<Player> squad) {
        if (squad.isEmpty()) {
            return REP_FLOOR;
        }
        int avg = (int) Math.round(squad.stream().mapToInt(Player::getOverall).average().orElse(0));
        return Math.max(REP_FLOOR, Math.min(REP_CEILING, avg));
    }

    public int stadiumCapacity(int reputation) {
        return 8000 + reputation * 500;
    }

    public BigDecimal startingBalance(int reputation) {
        return scaled(STARTING_BALANCE_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

    /** @deprecated Retained for seeding / legacy tests only. Use monthlySponsorship for new finance logic. */
    @Deprecated
    public BigDecimal sponsorshipPerRound(int reputation) {
        return scaled(SPONSORSHIP_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

    /** @deprecated Retained for seeding / legacy tests only. Use monthlyUpkeep for new finance logic. */
    @Deprecated
    public BigDecimal upkeepPerRound(int stadiumCapacity) {
        return scaled(UPKEEP_PER_SEAT.multiply(BigDecimal.valueOf(stadiumCapacity)));
    }

    public BigDecimal gateIncome(int homeReputation, int awayReputation, int stadiumCapacity) {
        double fill = 0.40 + homeReputation / 200.0 + awayReputation / 400.0;
        fill = Math.max(0.25, Math.min(1.0, fill));
        int attendance = Math.min(stadiumCapacity, (int) Math.round(stadiumCapacity * fill));
        return scaled(TICKET_PRICE.multiply(BigDecimal.valueOf(attendance)));
    }

    public BigDecimal prizeMoney(int position, int clubCount) {
        return scaled(PRIZE_BASE.multiply(BigDecimal.valueOf((long) clubCount - position + 1)));
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
