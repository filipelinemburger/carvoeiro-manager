package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Classifies a club's financial standing against its own monthly wage bill, using the same
 * {@link FinanceCalculator#monthlyWage} formula {@code FinanceService} charges every club each
 * in-game month. Feeds the AI listing engine's FINANCIAL trigger.
 */
@Component
public class ClubFinancialHealthEvaluator {

    private static final BigDecimal TIGHT_MULTIPLIER = BigDecimal.valueOf(3);

    public enum FinancialHealth { HEALTHY, TIGHT, DESPERATE }

    private final PlayerRepository playerRepository;
    private final FinanceCalculator financeCalculator;

    public ClubFinancialHealthEvaluator(PlayerRepository playerRepository, FinanceCalculator financeCalculator) {
        this.playerRepository = playerRepository;
        this.financeCalculator = financeCalculator;
    }

    /**
     * DESPERATE: balance below one month's wage bill. TIGHT: below three months'. Otherwise
     * HEALTHY. A club with zero wage bill (empty squad) is always HEALTHY — there's nothing to
     * be desperate about.
     */
    public FinancialHealth evaluate(Club club) {
        BigDecimal monthlyWageBill = monthlyWageBill(club.getId());
        if (monthlyWageBill.signum() == 0) {
            return FinancialHealth.HEALTHY;
        }
        BigDecimal balance = club.getBalance();
        if (balance.compareTo(monthlyWageBill) < 0) {
            return FinancialHealth.DESPERATE;
        }
        if (balance.compareTo(monthlyWageBill.multiply(TIGHT_MULTIPLIER)) < 0) {
            return FinancialHealth.TIGHT;
        }
        return FinancialHealth.HEALTHY;
    }

    private BigDecimal monthlyWageBill(UUID clubId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Player p : playerRepository.findByClubId(clubId)) {
            total = total.add(financeCalculator.monthlyWage(p.getOverall(), p.getAge()));
        }
        return total;
    }
}
