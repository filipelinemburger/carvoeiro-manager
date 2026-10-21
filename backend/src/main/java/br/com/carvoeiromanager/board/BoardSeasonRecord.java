package br.com.carvoeiromanager.board;

import java.util.List;

/** One season of the manager's record with the board: what was asked, and how it went. */
public record BoardSeasonRecord(int seasonYear, List<BoardObjectiveResponse> objectives) {
}
