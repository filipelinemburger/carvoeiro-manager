package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.finance.WageBillCalculator;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Classifies a club's financial standing against its own monthly wage bill, read through
 * {@link WageBillCalculator} — the same helper {@code FinanceService} charges every club each
 * in-game month, split between a loan's owner and borrower. Feeds the AI listing engine's
 * FINANCIAL trigger.
 */
@Component
public class ClubFinancialHealthEvaluator {

    private static final BigDecimal TIGHT_MULTIPLIER = BigDecimal.valueOf(3);

    public enum FinancialHealth { HEALTHY, TIGHT, DESPERATE }

    private final PlayerRepository playerRepository;
    private final WageBillCalculator wageBillCalculator;

    public ClubFinancialHealthEvaluator(PlayerRepository playerRepository, WageBillCalculator wageBillCalculator) {
        this.playerRepository = playerRepository;
        this.wageBillCalculator = wageBillCalculator;
    }

    /**
     * DESPERATE: balance below one month's wage bill. TIGHT: below three months'. Otherwise
     * HEALTHY. A club with zero wage bill (empty squad) is always HEALTHY — there's nothing to
     * be desperate about.
     */
    public FinancialHealth evaluate(Club club) {
        BigDecimal monthlyWageBill = monthlyWageBill(club);
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

    /**
     * Reads the whole save's squad (not just this club's) because a loan's owner carries part of
     * a wage for a player who does not appear in {@code findByClubId(club)} at all — he plays for
     * his borrower. Going through {@link WageBillCalculator} on the full squad is what keeps this
     * number identical to what {@code FinanceService} actually charges.
     */
    private BigDecimal monthlyWageBill(Club club) {
        return wageBillCalculator.monthlyWageBills(playerRepository.findBySaveGameId(club.getSaveGameId()))
                .getOrDefault(club.getId(), BigDecimal.ZERO);
    }
}
