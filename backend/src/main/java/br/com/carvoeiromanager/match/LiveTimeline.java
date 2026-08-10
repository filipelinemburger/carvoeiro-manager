package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The full timeline of a live match, through {@link #finalMinute} — 90 normally, or 120 when
 * {@link LiveMatchEngine#simulate} determined this was a knockout decider level on aggregate.
 * Callers reveal only the past via {@link #eventsThrough(int)} and {@link #statsThrough(int)}.
 *
 * @param minuteStats one entry per simulated minute of shot/possession activity not already
 *                    derivable from {@code events} (see {@link MinuteStats}); index {@code i}
 *                    corresponds to minute {@code i + 1}. Rolled by {@link LiveMatchEngine}'s
 *                    dedicated {@code statsRng} stream, so size is always exactly
 *                    {@link #finalMinute}.
 */
public record LiveTimeline(
        int homeGoals,
        int awayGoals,
        List<LiveTimelineEvent> events,
        Map<UUID, Integer> minutesPlayed,
        List<TimedInjury> injuries,
        int finalMinute,
        List<MinuteStats> minuteStats) {

    private static boolean isGoal(EventType t) {
        return t.isGoal();
    }

    public List<LiveTimelineEvent> eventsThrough(int throughMinute) {
        return events.stream().filter(e -> e.minute() <= throughMinute).toList();
    }

    public int homeGoalsAt(int minute) {
        return (int) events.stream()
                .filter(e -> e.home() && isGoal(e.type()) && e.minute() <= minute).count();
    }

    public int awayGoalsAt(int minute) {
        return (int) events.stream()
                .filter(e -> !e.home() && isGoal(e.type()) && e.minute() <= minute).count();
    }

    /**
     * Minute of the first event strictly after {@code fromMinute}, or {@link #finalMinute} when
     * none remain (whether that's 90 for a normal/decided match or 120 for one that genuinely
     * went to extra time — the engine never emits events past the minute it actually simulated).
     */
    public int nextEventMinute(int fromMinute) {
        return events.stream().map(LiveTimelineEvent::minute)
                .filter(m -> m > fromMinute).min(Integer::compareTo).orElse(finalMinute);
    }

    /**
     * Cumulative match statistics for both sides, as of {@code throughMinute} — the single place
     * that composes {@link MinuteStats} (per-minute shot/possession activity) with the revealed
     * {@link LiveTimelineEvent} stream. This is the ONLY method that knows these composition
     * rules; nothing else should re-derive statistics from either source.
     *
     * <p>All event-derived counts (goals via {@link EventType#isGoal()}, {@code PENALTY_MISSED},
     * {@code CHANCE}, {@code CORNER_GOAL}, {@code FOUL}, {@code YELLOW_CARD}, {@code RED_CARD})
     * are taken over {@link #eventsThrough(int)} — only revealed events. {@code extraShots},
     * {@code openPlayOnTarget}, rolled corners and {@code offsides} are folded over
     * {@code minuteStats[0 .. m)}, where {@code m = max(0, min(throughMinute,
     * minuteStats.size()))} — clamped rather than thrown on out-of-range input. Recall
     * {@code minuteStats} index {@code i} corresponds to minute {@code i + 1}.
     *
     * <pre>
     * shots         = chances + extraShots + goals + penaltyMissed
     * shotsOnTarget = openPlayOnTarget + goals + penaltyMissed
     * corners       = rolledCorners + cornerGoals
     * saves(side)   = shotsOnTarget(otherSide) - goals(otherSide)
     * possession(home) = round(mean of homePossession over the revealed minutes), or 50 when none
     * possession(away) = 100 - possession(home)
     * </pre>
     *
     * <p>A missed penalty counts as on target (and therefore as a save for the opposing keeper) —
     * the choice that makes the invariants below hold with no special case. {@code saves} is
     * always derived, never rolled: it is the football convention, is non-negative by
     * construction, and can never contradict the on-target count.
     *
     * <p>Structural invariants (true by construction of the values fed in, not asserted here):
     * <pre>
     * shots >= shotsOnTarget          (openPlayOnTarget <= chances + extraShots by construction)
     * shotsOnTarget >= goals          (goals is a summand)
     * corners >= cornerGoals          (cornerGoals is a summand)
     * saves >= 0
     * home.possession() + away.possession() == 100
     * </pre>
     *
     * <p>A second yellow makes the engine emit both a {@code YELLOW_CARD} and a {@code RED_CARD}
     * for the same player at the same minute ({@link LiveMatchEngine#maybeCard}), so a naive count
     * gives {@code yellow=1, red=1} for that minute — which is correct for a statistics panel.
     */
    public MatchStats statsThrough(int throughMinute) {
        List<LiveTimelineEvent> revealed = eventsThrough(throughMinute);

        int homeGoals = countGoals(revealed, true);
        int awayGoals = countGoals(revealed, false);
        int homePenaltyMissed = count(revealed, true, EventType.PENALTY_MISSED);
        int awayPenaltyMissed = count(revealed, false, EventType.PENALTY_MISSED);
        int homeChances = count(revealed, true, EventType.CHANCE);
        int awayChances = count(revealed, false, EventType.CHANCE);
        int homeCornerGoals = count(revealed, true, EventType.CORNER_GOAL);
        int awayCornerGoals = count(revealed, false, EventType.CORNER_GOAL);
        int homeFouls = count(revealed, true, EventType.FOUL);
        int awayFouls = count(revealed, false, EventType.FOUL);
        int homeYellow = count(revealed, true, EventType.YELLOW_CARD);
        int awayYellow = count(revealed, false, EventType.YELLOW_CARD);
        int homeRed = count(revealed, true, EventType.RED_CARD);
        int awayRed = count(revealed, false, EventType.RED_CARD);

        int m = Math.max(0, Math.min(throughMinute, minuteStats.size()));
        List<MinuteStats> rolled = minuteStats.subList(0, m);

        int homeExtraShots = rolled.stream().mapToInt(ms -> ms.home().extraShots()).sum();
        int awayExtraShots = rolled.stream().mapToInt(ms -> ms.away().extraShots()).sum();
        int homeOpenPlayOnTarget = rolled.stream().mapToInt(ms -> ms.home().openPlayOnTarget()).sum();
        int awayOpenPlayOnTarget = rolled.stream().mapToInt(ms -> ms.away().openPlayOnTarget()).sum();
        int homeRolledCorners = rolled.stream().mapToInt(ms -> ms.home().corners()).sum();
        int awayRolledCorners = rolled.stream().mapToInt(ms -> ms.away().corners()).sum();
        int homeOffsides = rolled.stream().mapToInt(ms -> ms.home().offsides()).sum();
        int awayOffsides = rolled.stream().mapToInt(ms -> ms.away().offsides()).sum();

        int homePossession = m == 0 ? 50
                : (int) Math.round(rolled.stream().mapToInt(MinuteStats::homePossession).average().orElseThrow());
        int awayPossession = 100 - homePossession;

        int homeShots = homeChances + homeExtraShots + homeGoals + homePenaltyMissed;
        int awayShots = awayChances + awayExtraShots + awayGoals + awayPenaltyMissed;
        int homeShotsOnTarget = homeOpenPlayOnTarget + homeGoals + homePenaltyMissed;
        int awayShotsOnTarget = awayOpenPlayOnTarget + awayGoals + awayPenaltyMissed;
        int homeCorners = homeRolledCorners + homeCornerGoals;
        int awayCorners = awayRolledCorners + awayCornerGoals;
        int homeSaves = awayShotsOnTarget - awayGoals;
        int awaySaves = homeShotsOnTarget - homeGoals;

        TeamStats home = new TeamStats(homePossession, homeShots, homeShotsOnTarget, homeSaves,
                homeCorners, homeOffsides, homeFouls, homeYellow, homeRed);
        TeamStats away = new TeamStats(awayPossession, awayShots, awayShotsOnTarget, awaySaves,
                awayCorners, awayOffsides, awayFouls, awayYellow, awayRed);
        return new MatchStats(home, away);
    }

    private static int count(List<LiveTimelineEvent> events, boolean home, EventType type) {
        return (int) events.stream().filter(e -> e.home() == home && e.type() == type).count();
    }

    private static int countGoals(List<LiveTimelineEvent> events, boolean home) {
        return (int) events.stream().filter(e -> e.home() == home && isGoal(e.type())).count();
    }
}
