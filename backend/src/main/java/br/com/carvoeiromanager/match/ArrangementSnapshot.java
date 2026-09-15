package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.RoleAssignment;

import java.util.Map;
import java.util.UUID;

/**
 * One side's live arrangement from {@code minute} onward: who holds which slot index of
 * {@code formation}, and each on-pitch player's role. {@link LiveMatchEngine} records one at kickoff
 * (minute 0) and one at every change — a substitution, a red card, a manager's formation relocation, a
 * scheduled {@link TimedSlots}. A side whose profile carried no slot indexes records none.
 */
public record ArrangementSnapshot(int minute, boolean home, String formation,
                                  Map<UUID, Integer> slotIndexes, Map<UUID, RoleAssignment> roles) {
}
