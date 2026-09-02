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
    /** Plausible player, unacceptable terms. */
    TERMS_REJECTED
}
