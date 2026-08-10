package br.com.carvoeiromanager.match;

/**
 * One simulated minute's ball-possession split and per-side {@link MinuteTeamStats}. Appended one
 * entry per loop iteration to {@link LiveTimeline#minuteStats()}, so index {@code i} there always
 * corresponds to minute {@code i + 1}.
 *
 * @param homePossession home's share of the ball this minute, in whole percent. The away share is
 *                        <b>by definition</b> {@code 100 - homePossession}, so the two can never
 *                        fail to add up to 100 at any revealed minute.
 */
public record MinuteStats(int homePossession, MinuteTeamStats home, MinuteTeamStats away) { }
