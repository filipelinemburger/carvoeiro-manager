package br.com.carvoeiromanager.board;

/** Why confidence moved. Each row in {@code board_confidence_changes} carries exactly one. */
public enum BoardChangeReason {
    MATCH_RESULT,
    HEAVY_DEFEAT,
    TABLE_POSITION,
    NEGATIVE_BALANCE,
    STATE_MET,
    STATE_FAILED,
    TITLE_WON,
    LEAGUE_MET,
    LEAGUE_FAILED,
    FINANCE_MET,
    FINANCE_FAILED,
    NEW_SEASON,
    SACKING_AVERTED
}
