package br.com.carvoeiromanager.board;

/**
 * How far a club got in a phased competition, in order. {@code NOT_QUALIFIED} is "out in the
 * round-robin phase"; a stage is <em>reached</em> by winning the phase before it, so CHAMPION means
 * winning the final. Comparisons go by {@link #ordinal()}, so the declaration order is load-bearing.
 */
public enum Stage {
    NOT_QUALIFIED,
    QUARTERFINAL,
    SEMIFINAL,
    FINAL,
    CHAMPION
}
