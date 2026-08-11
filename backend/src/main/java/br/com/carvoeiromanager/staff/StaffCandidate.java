package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rolled, not-yet-hired staff candidate — never persisted. {@code sourceStaffId} and
 * {@code currentClubName} are both null for an unemployed professional or a generated newgen, and
 * both set when the candidate is a poaching target employed elsewhere.
 */
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage,
                             int primaryAttribute, int secondaryAttribute,
                             UUID sourceStaffId, String currentClubName) {

    /** A candidate who is not currently employed anywhere. */
    public static StaffCandidate free(String name, StaffQuality quality, BigDecimal wage,
                                      int primaryAttribute, int secondaryAttribute) {
        return new StaffCandidate(name, quality, wage, primaryAttribute, secondaryAttribute, null, null);
    }
}
