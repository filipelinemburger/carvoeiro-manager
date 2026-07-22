package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;

/** One hand-authored real professional in the staff hiring pool (seed/staff-pool.json). */
public record SeedStaff(String name, StaffRole role, int primaryAttribute, int secondaryAttribute) {
}
