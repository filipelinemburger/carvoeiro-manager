package br.com.carvoeiromanager.board;

/**
 * When the board warns and when it sacks (spec §7). Pure: the caller passes the state and applies
 * the verdict. The ultimatum guarantees the manager always gets a warning, and three more matches to
 * respond, before a mid-season sacking.
 */
public final class BoardRules {

    public static final int ULTIMATUM_AT = 30;
    public static final int LIFT_AT = 45;
    public static final int SACK_AT = 15;
    public static final int SEASON_END_SACK_BELOW = 25;
    public static final int MATCHES_AFTER_ULTIMATUM = 3;
    /** A new manager gets time to settle: no mid-season sacking before the league is this old. */
    public static final int LEAGUE_MATCHES_BEFORE_SACKING = 6;
    /** Where an averted sacking leaves confidence, so the displeasure shows without repeating. */
    public static final int AVERTED_FLOOR = 30;

    public enum Verdict { NONE, ISSUE_ULTIMATUM, LIFT_ULTIMATUM, SACK, SACKING_AVERTED }

    private BoardRules() { }

    /**
     * Checked after every managed match, in the spec's order: ultimatum, recovery, sacking.
     * {@code matchesSinceUltimatum} is the count <em>after</em> this match's increment, and the
     * match that issues an ultimatum can never also sack.
     *
     * @param canSack false with sacking disabled or in the grace season; the sacking conditions
     *                then yield {@code SACKING_AVERTED} instead
     */
    public static Verdict afterMatch(int confidence, boolean ultimatumActive, int matchesSinceUltimatum,
                                     int leagueMatchesPlayed, boolean canSack) {
        if (!ultimatumActive) {
            return confidence <= ULTIMATUM_AT ? Verdict.ISSUE_ULTIMATUM : Verdict.NONE;
        }
        if (confidence >= LIFT_AT) {
            return Verdict.LIFT_ULTIMATUM;
        }
        boolean sackable = confidence <= SACK_AT
                && matchesSinceUltimatum >= MATCHES_AFTER_ULTIMATUM
                && leagueMatchesPlayed >= LEAGUE_MATCHES_BEFORE_SACKING;
        if (!sackable) {
            return Verdict.NONE;
        }
        return canSack ? Verdict.SACK : Verdict.SACKING_AVERTED;
    }

    /** The season-end review needs no prior ultimatum: the end of a season is when boards decide. */
    public static Verdict atSeasonEnd(int confidence, boolean canSack) {
        if (confidence >= SEASON_END_SACK_BELOW) {
            return Verdict.NONE;
        }
        return canSack ? Verdict.SACK : Verdict.SACKING_AVERTED;
    }
}
