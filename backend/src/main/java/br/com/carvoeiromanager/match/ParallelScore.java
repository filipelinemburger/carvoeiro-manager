package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One other-fixture's score as it stands at the live match's currently-committed minute — a
 * read-only projection over the pre-simulated {@link ParallelFixtureScore#events()} (only goals
 * with {@code minute <= committedMinute} are counted), exposed on {@link LiveMatchState} so the
 * live view can show the round's other results ticking up in step with the match being played.
 * <p>
 * {@code homeClubId}/{@code awayClubId} are resolved from the fixture row (the snapshot itself has
 * never carried them) and let the client fold this result into the pre-round standings without
 * matching on short names, which are not unique. Both are NULL — never one of the two — when the
 * fixture id no longer resolves; the goals still display, and a null id joins no standings row.
 */
public record ParallelScore(UUID fixtureId, UUID homeClubId, UUID awayClubId,
                            String homeShort, String awayShort,
                            int homeGoals, int awayGoals) {
}
