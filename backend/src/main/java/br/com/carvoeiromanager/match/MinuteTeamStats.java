package br.com.carvoeiromanager.match;

/**
 * One side's per-minute shot/set-piece activity that is <b>not</b> already derivable by replaying
 * the revealed {@link LiveTimelineEvent} stream. Goals, missed penalties, {@code CHANCE}s, corner
 * goals, fouls and cards live in the event stream and are read back out of it by
 * {@link LiveTimeline#statsThrough(int)} — storing them here too would let the two drift.
 *
 * @param extraShots       attempts this minute that produced no narrated event at all.
 * @param openPlayOnTarget how many of (this minute's {@code CHANCE}s + {@code extraShots}) were on
 *                         target. Always {@code <=} that total, because the engine draws exactly
 *                         one Bernoulli per such shot — so {@code shots >= shotsOnTarget} holds
 *                         <b>by construction</b>, not by assertion.
 * @param corners          corners won that did <b>not</b> produce a {@code CORNER_GOAL}.
 *                         {@link LiveTimeline#statsThrough(int)} adds corner goals back, so a
 *                         corner goal always implies a corner.
 * @param offsides         offside calls against this side's attack.
 */
public record MinuteTeamStats(int extraShots, int openPlayOnTarget, int corners, int offsides) {
    static final MinuteTeamStats EMPTY = new MinuteTeamStats(0, 0, 0, 0);
}
