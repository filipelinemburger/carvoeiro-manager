package br.com.carvoeiromanager.player;

import java.util.List;

/**
 * The Preparador Físico's weekly digest of notable training events at the managed club,
 * produced by {@link TrainingService#processWeek}. {@code quality} is the physio's
 * {@link br.com.carvoeiromanager.staff.StaffQuality} name; the season layer maps this to its
 * own news DTO (kept here as plain strings so the season package owns the API shape).
 */
public record TrainingWeekReport(String staffName, String quality, List<Entry> entries) {

    public record Entry(String playerName, String message) {
    }
}
