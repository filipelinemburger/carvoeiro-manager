package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.player.Player;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, deterministic money formulas (no RNG). All amounts are BigDecimal scaled to 2.
 * Constants are the locked initial calibration (see the plan's Global Constraints).
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
    private static final BigDecimal SPONSORSHIP_PER_REP = new BigDecimal("8000");
    private static final BigDecimal UPKEEP_PER_SEAT = new BigDecimal("4");
    private static final BigDecimal PRIZE_BASE = new BigDecimal("500000");

    public BigDecimal weeklyWage(int overall, int age) {
        BigDecimal base = BigDecimal.valueOf((long) overall * overall * WAGE_OVERALL_FACTOR);
        BigDecimal factor = age <= 20 ? YOUTH_FACTOR : age >= 32 ? VETERAN_FACTOR : PRIME_FACTOR;
        return scaled(base.multiply(factor));
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

    public BigDecimal sponsorshipPerRound(int reputation) {
        return scaled(SPONSORSHIP_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

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
