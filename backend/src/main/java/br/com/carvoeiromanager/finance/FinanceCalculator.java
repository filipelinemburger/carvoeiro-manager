package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.player.Player;
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
 * remain per-match, and the crowd that gate income is billed against comes from
 * {@link AttendanceCalculator} — this class no longer derives attendance inline. Calibration
 * targets: a club anywhere in the real stadium-capacity range (roughly 3 000 seats to 63 903)
 * should finish the season with a positive balance after 10 months, ~18 Catarinense matches
 * (9 home), and prize money.
 *
 * Key calibration constants:
 * <ul>
 *   <li>monthlyWage = weeklyWage × 4 (spec'd; individual player monthly payroll)</li>
 *   <li>MONTHLY_SPONSORSHIP_PER_REP = 130 000 (R$130 000 × reputation per month;
 *       for rep=60: R$7 800 000/month — the counterweight to payroll, and the constant that
 *       had to move once real capacities replaced the derived 8 000 + reputation × 500 formula:
 *       a small club's real capacity can be as low as 3 000 seats, too little gate income to
 *       cover payroll on its own)</li>
 *   <li>MONTHLY_UPKEEP_PER_SEAT = R$6/seat/month; for cap=19 225 (Criciúma): R$115 350/month</li>
 *   <li>MATCH_COST_PER_SEAT = R$2/seat per match played (each club, home or away)</li>
 * </ul>
 *
 * Sanity estimate for rep=60 (capacity=19 225, Criciúma's real capacity, 22 players avg
 * overall 60):
 * <pre>
 *   monthlyWages  ≈ 22 × 72 000 × 4  = 6 336 000
 *   monthlySpons  =     130 000 × 60 = 7 800 000
 *   monthlyUpkeep =          6 × 19 225 =  115 350
 *   net monthly (no gate) ≈ +1 348 650/month → +13 486 500 over 10 months
 *   9 home gate (crowd from AttendanceCalculator, rep=60 vs rep=60, mid-table form/position)
 *       ≈ 7 441 650
 *   18 matchCosts @ 2 × 19 225            ≈    692 100
 *   net gate/cost                         ≈  6 749 550
 *   prize (position 10 of 20)             ≈  5 500 000
 *   net season ≈ 13 486 500 + 6 749 550 + 5 500 000 ≈ +25.7 M  → solvent
 * </pre>
 *
 * {@code FinanceSolvencyTest} pins this down at both ends of the real capacity range:
 * a 3 000-seat club at reputation 45 and a 63 903-seat club at reputation 75 must also
 * both finish a season in the black.
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
    private static final BigDecimal MONTHLY_SPONSORSHIP_PER_REP = new BigDecimal("130000");
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

    /**
     * Gate receipts for a realized crowd. The crowd model itself lives in
     * {@link AttendanceCalculator} — this method only turns bodies into money.
     */
    public BigDecimal gateIncome(int attendance) {
        return scaled(TICKET_PRICE.multiply(BigDecimal.valueOf(Math.max(0, attendance))));
    }

    public BigDecimal prizeMoney(int position, int clubCount) {
        return scaled(PRIZE_BASE.multiply(BigDecimal.valueOf((long) clubCount - position + 1)));
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
