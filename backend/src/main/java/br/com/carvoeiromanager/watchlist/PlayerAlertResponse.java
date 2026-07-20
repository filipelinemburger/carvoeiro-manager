package br.com.carvoeiromanager.watchlist;

/**
 * One watchlist/alert happening, rendered in news cards and the season summary.
 * kind: SOLD (a watched rival moved to another club), LISTED (a watched rival was
 * transfer-listed), FREE_AGENT (a watched player's contract lapsed), OFFER_RECEIVED
 * (an AI club bid on one of the manager's own players). detail carries the fee / offer
 * amount / listing reason as a human string, nullable.
 */
public record PlayerAlertResponse(String kind, String playerName, String clubName, String detail) {}
