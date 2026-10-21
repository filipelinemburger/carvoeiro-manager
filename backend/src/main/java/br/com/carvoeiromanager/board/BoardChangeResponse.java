package br.com.carvoeiromanager.board;

import java.util.Map;

/** One confidence change for the card's "why it moved" list; {@code payload} may be null. */
public record BoardChangeResponse(String date, String reason, int delta, Map<String, Object> payload) {
}
