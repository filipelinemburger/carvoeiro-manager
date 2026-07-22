package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One other-fixture's score as it stands at the live match's currently-committed minute — a
 * read-only projection over the pre-simulated {@link ParallelFixtureScore#events()} (only goals
 * with {@code minute <= committedMinute} are counted), exposed on {@link LiveMatchState} so the
 * live view can show the round's other results ticking up in step with the match being played.
 */
public record ParallelScore(UUID fixtureId, String homeShort, String awayShort,
                            int homeGoals, int awayGoals) {
}
