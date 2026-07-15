package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** One of the 3 offered-but-not-yet-hired candidates for a role, with its index for hiring. */
public record StaffCandidateResponse(int index, String name, String quality, BigDecimal wage) {
}
