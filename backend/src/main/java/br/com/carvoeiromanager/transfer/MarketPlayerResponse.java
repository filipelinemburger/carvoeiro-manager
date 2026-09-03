package br.com.carvoeiromanager.transfer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the transfer market.
 *
 * <p>{@code contractYears} and {@code onLoan} are there for the loan surface, not the bid surface:
 * borrowing a player requires at least {@code LoanService.MIN_CONTRACT_YEARS_TO_LOAN} years left on
 * his contract and that he is not already out on loan, and both are refused server-side. Sending
 * them with the row lets the client disable its "borrow" action before the click instead of
 * explaining a rejection after it.
 */
public record MarketPlayerResponse(
        UUID playerId, String name, String position, int age, int overall,
        UUID clubId, String clubName, BigDecimal value, BigDecimal wage, BigDecimal wageDemand,
        String listedReason, int contractYears, boolean onLoan) {}
