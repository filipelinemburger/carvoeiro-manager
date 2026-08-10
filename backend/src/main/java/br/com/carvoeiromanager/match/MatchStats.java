package br.com.carvoeiromanager.match;

/**
 * Full-match statistics for both sides as of a revealed minute, produced by
 * {@link LiveTimeline#statsThrough(int)}. Doubles as the wire DTO on {@code LiveMatchState} —
 * consistent with {@link KitColors}, {@link ParallelScore} and {@link LiveTactics}, which are
 * package records used directly as {@code LiveMatchState} fields.
 */
public record MatchStats(TeamStats home, TeamStats away) { }
