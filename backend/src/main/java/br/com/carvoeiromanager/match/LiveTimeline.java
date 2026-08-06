package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The full timeline of a live match, through {@link #finalMinute} — 90 normally, or 120 when
 * {@link LiveMatchEngine#simulate} determined this was a knockout decider level on aggregate.
 * Callers reveal only the past via {@link #eventsThrough(int)}.
 */
public record LiveTimeline(
        int homeGoals,
        int awayGoals,
        List<LiveTimelineEvent> events,
        Map<UUID, Integer> minutesPlayed,
        List<TimedInjury> injuries,
        int finalMinute) {

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
}
