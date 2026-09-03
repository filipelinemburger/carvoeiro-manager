package br.com.carvoeiromanager.loan;

/**
 * Why a loan was refused. The distinction is load-bearing for the UI: the first two are refusals
 * about the PLAYER and no better offer will move them, while the third is about the OFFER and is
 * the only one worth answering with improved terms.
 */
public enum LoanRefusal {
    /** His club needs him — he is a key player there, or his position has no cover. */
    KEY_PLAYER,
    /** He is beneath the borrowing club's level. */
    BELOW_LEVEL,
    /**
     * Plausible player, unacceptable terms. Unlike the two above, this one never comes out of
     * {@link LoanPlausibility#check} — plausibility only ever answers about the PLAYER. It is
     * produced by the negotiator instead, when the player passes both plausibility tests and the
     * offer is then countered. A reader arriving here from {@code check()} should not go looking
     * for the branch that returns it: there isn't one.
     */
    TERMS_REJECTED
}
