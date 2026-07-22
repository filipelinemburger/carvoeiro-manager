package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.UUID;

/**
 * A round's other-fixture pre-simulated at live-match kickoff: the {@link SimulatedMatch}
 * (scoreline + events) plus the exact home/away XI (player ids, in {@code bestXi} pick order)
 * that {@link FixtureSimulator#simulatePureWithXi} resolved to produce it.
 * <p>
 * Threaded from {@code LiveMatchService#kickoff} (built into {@link ParallelFixtureScore}, then
 * serialized into {@code parallel_scores}) through {@code LiveMatchService#finish} and
 * {@code SeasonAdvanceService#completeUserMatch} into {@link FixtureSimulator#simulateRound}, so
 * finish-time attribution REPLAYS the captured XI by id lookup instead of re-resolving via
 * {@code LineupService#bestXi}. This makes attribution immune to any player-state mutation
 * between kickoff and finish — notably the weekly training tick's evolutions/injuries, applied in
 * {@code SeasonAdvanceService.completeUserMatch} BEFORE the round is replayed, which can otherwise
 * legitimately change which player {@code bestXi} ranks into the XI (a rating change or a newly
 * ineligible player) and silently misattribute the pre-simulated goals to the wrong player.
 * <p>
 * {@code homeXiIds}/{@code awayXiIds} are null when reconstructed from a {@link ParallelFixtureScore}
 * serialized before this field existed (in-flight matches from an older deploy); callers fall back
 * to a fresh {@code resolveXi}/{@code bestXi} resolution in that case, exactly as before this fix.
 */
public record PrecomputedFixture(SimulatedMatch match, List<UUID> homeXiIds, List<UUID> awayXiIds) {
}
