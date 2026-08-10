package br.com.carvoeiromanager.match;

/**
 * One side's cumulative match statistics as of a revealed minute, produced by
 * {@link LiveTimeline#statsThrough(int)} — see that method's Javadoc for exactly how each
 * component is derived and the structural invariants it guarantees ({@code shots >=
 * shotsOnTarget}, {@code shotsOnTarget >= goals}, {@code corners >= cornerGoals}, {@code saves >=
 * 0}, and {@code home.possession() + away.possession() == 100}).
 */
public record TeamStats(int possession, int shots, int shotsOnTarget, int saves,
                        int corners, int offsides, int fouls, int yellowCards, int redCards) { }
