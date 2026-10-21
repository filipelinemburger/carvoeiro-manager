package br.com.carvoeiromanager.board;

/**
 * One objective as the frontend sees it. Enums travel as their names and the frontend owns every
 * pt-BR word (spec §10). {@code progress} is live progress for a PENDING objective
 * ({@code ON_TRACK}/{@code AT_RISK}/{@code OFF_TRACK}), null when there is none to show.
 */
public record BoardObjectiveResponse(
        String kind,
        String competitionName,
        Integer tier,
        Integer targetPosition,
        String targetStage,
        Integer currentPosition,
        String progress,
        String outcome) {
}
