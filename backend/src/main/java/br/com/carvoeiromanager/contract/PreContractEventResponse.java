package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;

/**
 * One pre-contract happening, rendered in news/advance cards.
 * kind: AGREED_OUT (manager's offer accepted), POACHED_IN (an AI club agreed terms with a
 * managed player), LOCKED_OUT / CANCELLED_OUT (manager's deal locked / broken by counter-renew),
 * LOCKED_IN (the manager's player's departure became final).
 */
public record PreContractEventResponse(String kind, String playerName, String clubName,
                                       BigDecimal wage, Integer contractYears) {}
