package br.com.carvoeiromanager.stats;

public record SeasonStatsLine(
        int seasonYear,
        String competitionName,
        int matchesPlayed,
        int minutesPlayed,
        int goals,
        int assists,
        int fouls,
        int yellowCards,
        int redCards) {
}
