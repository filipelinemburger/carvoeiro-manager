package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth;
import br.com.carvoeiromanager.transfer.SellerContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPlausibilityTest {

    private final LoanPlausibility plausibility = new LoanPlausibility();

    /** Spare at his owner: not a key player, and his position has cover. */
    private static SellerContext spare() {
        return new SellerContext(false, false, false, FinancialHealth.HEALTHY);
    }

    private static SellerContext keyPlayer() {
        return new SellerContext(false, true, false, FinancialHealth.HEALTHY);
    }

    private static SellerContext onlyOneInHisPosition() {
        return new SellerContext(false, false, true, FinancialHealth.HEALTHY);
    }

    @Test
    void aSparePlayerAtTheBorrowersLevelIsWorthNegotiating() {
        assertThat(plausibility.check(spare(), 70, 70)).isEmpty();
    }

    @Test
    void aClubWillNotLendSomeoneItNeeds() {
        assertThat(plausibility.check(keyPlayer(), 70, 70)).contains(LoanRefusal.KEY_PLAYER);
        assertThat(plausibility.check(onlyOneInHisPosition(), 70, 70))
                .contains(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void aBorrowerWillNotTakeSomeoneBeneathItsLevel() {
        // reputation 70, tolerance 3 -> 66 is refused, 67 is accepted.
        assertThat(plausibility.check(spare(), 66, 70)).contains(LoanRefusal.BELOW_LEVEL);
        assertThat(plausibility.check(spare(), 67, 70)).isEmpty();
    }

    @Test
    void theOwnersRefusalIsReportedBeforeTheBorrowersLevel() {
        // Both fail; the owner's answer is the one that ends the conversation first, because
        // no improvement to the offer can move it.
        assertThat(plausibility.check(keyPlayer(), 40, 80)).contains(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void loansUpwardAreRareByConstruction() {
        // A small club (reputation 50) and a big borrower (reputation 78).
        // Its reserve is beneath the big club's level...
        assertThat(plausibility.check(spare(), 52, 78)).contains(LoanRefusal.BELOW_LEVEL);
        // ...and its star, who would pass the level test, is a key player there.
        assertThat(plausibility.check(keyPlayer(), 79, 78)).contains(LoanRefusal.KEY_PLAYER);
        // The narrow door: a standout who is nonetheless spare at his club.
        assertThat(plausibility.check(spare(), 79, 78)).isEmpty();
    }

    @Test
    void loansDownwardAreTheEasyCase() {
        // A big club's reserve, offered to a smaller club, clears both tests comfortably.
        assertThat(plausibility.check(spare(), 72, 55)).isEmpty();
    }
}
