package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.UUID;

/**
 * One other-fixture's pre-simulated result, serialized (as a JSON list) into
 * {@link LiveMatch#getParallelScores()} at live-match kickoff and replayed — not
 * re-simulated — at finish, so the persisted results are identical either way.
 * {@code events} reuses {@link SimulatedEvent}'s exact (minute, type, home) shape, letting the
 * finish path reconstruct the original {@link SimulatedMatch} without any translation.
 * {@code homeShort}/{@code awayShort} are carried for a future scores-as-of-minute display.
 * <p>
 * {@code homeXiIds}/{@code awayXiIds} capture the EXACT XI (player ids, in {@code bestXi} pick
 * order) that {@link FixtureSimulator#simulatePureWithXi} resolved to compute this score. Finish
 * replays these ids directly instead of re-resolving via {@code bestXi} for attribution, so the
 * result is immune to any player-state mutation between kickoff and finish (e.g. the weekly
 * training tick's evolutions/injuries, applied in {@code SeasonAdvanceService.completeUserMatch}
 * before the round is replayed) that could otherwise make {@code bestXi} pick a different player
 * than it picked at kickoff. Null on entries serialized before this field existed (in-flight
 * matches from an older deploy) — callers fall back to a fresh {@code bestXi} resolution then,
 * exactly as before this fix (no worse than the pre-existing behaviour for those matches).
 */
public record ParallelFixtureScore(UUID fixtureId, String homeShort, String awayShort,
                                   int homeGoals, int awayGoals, List<SimulatedEvent> events,
                                   List<UUID> homeXiIds, List<UUID> awayXiIds) {
}
