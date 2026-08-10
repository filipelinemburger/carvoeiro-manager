package br.com.carvoeiromanager.seed;

import java.util.List;

/**
 * One club's researched coaching staff (seed/club-staff.json), keyed by the club's shortName.
 * A role with no confirmed real-world source is simply absent from {@code staff} — career
 * creation leaves that slot open rather than inventing someone.
 */
public record SeedClubStaff(String clubShortName, List<SeedStaff> staff) {
}
