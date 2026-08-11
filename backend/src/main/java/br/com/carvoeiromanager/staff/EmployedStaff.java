package br.com.carvoeiromanager.staff;

import java.util.UUID;

/**
 * A staff member currently employed at another club — a poaching target. Resolved by
 * {@link StaffService} and handed to {@link StaffCandidateGenerator}, which must stay free of
 * repository access.
 */
public record EmployedStaff(UUID staffId, String name, String clubName,
                            int primaryAttribute, int secondaryAttribute) {
}
