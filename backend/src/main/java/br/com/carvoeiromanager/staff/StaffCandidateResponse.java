package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/**
 * One of the 3 offered candidates for a role, with its index for hiring. {@code currentClubName}
 * is null for a free professional and set for someone who would have to be poached.
 */
public record StaffCandidateResponse(int index, String name, String quality, BigDecimal wage,
                                     int primaryAttribute, int secondaryAttribute,
                                     String currentClubName) {
}
