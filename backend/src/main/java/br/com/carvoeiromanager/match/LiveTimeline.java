package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The full 90' of a live match. Callers reveal only the past via {@link #eventsThrough(int)}. */
public record LiveTimeline(
        int homeGoals,
        int awayGoals,
        List<LiveTimelineEvent> events,
        Map<UUID, Integer> minutesPlayed,
        List<TimedInjury> injuries) {

    private static boolean isGoal(EventType t) {
        return t == EventType.GOAL || t == EventType.SET_PIECE_GOAL;
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

    /** Minute of the first event strictly after {@code fromMinute}, or 90 when none remain. */
    public int nextEventMinute(int fromMinute) {
        return events.stream().map(LiveTimelineEvent::minute)
                .filter(m -> m > fromMinute).min(Integer::compareTo).orElse(90);
    }
}
