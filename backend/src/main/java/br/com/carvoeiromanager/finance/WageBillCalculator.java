package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.player.Player;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single place that turns a squad into per-club monthly wage bills. {@link FinanceService}
 * (what a club is actually charged each in-game month) and
 * {@link br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator} (what a club is
 * classified against) both read through this class so the two numbers cannot drift apart again.
 */
@Component
public class WageBillCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final FinanceCalculator financeCalculator;

    public WageBillCalculator(FinanceCalculator financeCalculator) {
        this.financeCalculator = financeCalculator;
    }

    /**
     * Monthly wage bill per club, for every club with a player in {@code players}. A loaned
     * player is paid by BOTH clubs: {@code loanWageShare} percent by his owner, the rest by the
     * club he plays for. The borrower's part is the remainder rather than its own percentage, so
     * the halves always sum to the wage exactly — two independently rounded percentages would
     * create or destroy money every month.
     */
    public Map<UUID, BigDecimal> monthlyWageBills(List<Player> players) {
        Map<UUID, BigDecimal> bills = new HashMap<>();
        for (Player player : players) {
            if (player.getClubId() == null) {
                continue;   // free agents are nobody's payroll
            }
            BigDecimal wage = financeCalculator.monthlyWage(player.getOverall(), player.getAge());
            if (player.isOnLoan()) {
                BigDecimal ownerPart = wage
                        .multiply(BigDecimal.valueOf(player.getLoanWageShare()))
                        .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
                bills.merge(player.getOwnerClubId(), ownerPart, BigDecimal::add);
                bills.merge(player.getClubId(), wage.subtract(ownerPart), BigDecimal::add);
            } else {
                bills.merge(player.getClubId(), wage, BigDecimal::add);
            }
        }
        return bills;
    }
}
