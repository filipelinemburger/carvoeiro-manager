package br.com.carvoeiromanager.board;

/**
 * Maps a competition's knockout phases to {@link Stage}s by position, never by phase name: the
 * last knockout phase is the FINAL, the one before it the SEMIFINAL, and so on back.
 */
public final class StageLadder {

    private StageLadder() { }

    /** Knockout phase {@code index} (0-based) of {@code knockoutCount} → the stage it represents. */
    public static Stage stageOfKnockout(int index, int knockoutCount) {
        if (knockoutCount > Stage.CHAMPION.ordinal() - Stage.QUARTERFINAL.ordinal()) {
            throw new IllegalStateException(knockoutCount + " knockout phases, but Stage only names "
                    + "quarterfinals onwards — extend Stage (e.g. ROUND_OF_16) before seeding this format");
        }
        if (index < 0 || index >= knockoutCount) {
            throw new IllegalArgumentException("knockout index " + index + " outside 0.." + (knockoutCount - 1));
        }
        return Stage.values()[Stage.CHAMPION.ordinal() - (knockoutCount - index)];
    }

    /** The stage a club has reached once it wins knockout phase {@code index}. */
    public static Stage stageAfterWinning(int index, int knockoutCount) {
        return index == knockoutCount - 1 ? Stage.CHAMPION : stageOfKnockout(index + 1, knockoutCount);
    }
}
