package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.SellerContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether a loan is worth discussing at all, before any term is considered. Two questions, and
 * the shape of real loan markets falls out of them without a rule about club size: a small club's
 * reserve is beneath a big club's level, and its star is a key player it will not lend.
 */
@Component
public class LoanPlausibility {

    /**
     * How far below a club's reputation a player may sit and still interest it. A squad is not
     * made only of its mean, so a small margin is right; more than this and a club would take
     * players plainly beneath it.
     */
    static final int LEVEL_TOLERANCE = 3;

    /**
     * @param ownerContext      the owning club's view of the player, from {@code SellerContextFactory}
     * @param playerOverall     the player's overall
     * @param borrowerReputation the borrowing club's reputation, which is its squad quality
     * @return the reason to refuse, or empty when the loan is worth negotiating
     */
    public Optional<LoanRefusal> check(SellerContext ownerContext, int playerOverall,
                                       int borrowerReputation) {
        if (ownerContext.keyPlayer() || ownerContext.noCover()) {
            return Optional.of(LoanRefusal.KEY_PLAYER);
        }
        if (playerOverall < borrowerReputation - LEVEL_TOLERANCE) {
            return Optional.of(LoanRefusal.BELOW_LEVEL);
        }
        return Optional.empty();
    }
}
