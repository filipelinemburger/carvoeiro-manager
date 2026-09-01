package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

/** What a termination cost and what it left in the bank — enough for the UI to report it. */
public record TerminationResponse(UUID playerId, String playerName, BigDecimal fee,
                                  BigDecimal newBalance) {
}
