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
 */
public record ParallelFixtureScore(UUID fixtureId, String homeShort, String awayShort,
                                   int homeGoals, int awayGoals, List<SimulatedEvent> events) {
}
