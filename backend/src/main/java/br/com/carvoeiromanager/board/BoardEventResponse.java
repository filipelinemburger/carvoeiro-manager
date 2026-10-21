package br.com.carvoeiromanager.board;

import java.util.List;

/**
 * The board's reaction, carried on {@code AdvanceResponse.board}. On a MATCH response it is the
 * match's confidence change (plus the season-end review on the last round); on a {@code BOARD_*}
 * news item it is that item's whole payload. {@code objectives} is filled only on objectives and
 * review items, and empty elsewhere.
 */
public record BoardEventResponse(
        int confidence,
        int delta,
        boolean ultimatumIssued,
        boolean ultimatumLifted,
        boolean sacked,
        boolean sackingAverted,
        List<BoardObjectiveResponse> objectives) {
}
