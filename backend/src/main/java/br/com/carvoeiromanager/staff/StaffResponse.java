package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/**
 * One filled staff slot. {@code quality} and {@code wage} are null only for the managed club's
 * head-coach slot, which is the player rather than an employee — the frontend keys the
 * "this is you" rendering off that.
 */
public record StaffResponse(String name, String quality, BigDecimal wage,
                            int primaryAttribute, int secondaryAttribute) {
}
