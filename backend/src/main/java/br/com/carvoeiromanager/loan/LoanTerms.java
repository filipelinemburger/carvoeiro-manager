package br.com.carvoeiromanager.loan;

/**
 * The two negotiable terms of a loan: the percentage of the wage the OWNER pays, and whether the
 * borrowing club may field him against his owner.
 */
public record LoanTerms(int ownerWageShare, boolean mayFaceOwner) {
}
