package br.com.carvoeiromanager.board;

/**
 * A league objective's target: a worst acceptable table position, or (in a phased division such as
 * Série C) a stage to reach. Exactly one of the two is non-null.
 */
public record LeagueTarget(Integer position, Stage stage) {

    public LeagueTarget {
        if ((position == null) == (stage == null)) {
            throw new IllegalArgumentException("exactly one of position/stage must be set");
        }
    }

    static LeagueTarget position(int position) {
        return new LeagueTarget(position, null);
    }

    static LeagueTarget stage(Stage stage) {
        return new LeagueTarget(null, stage);
    }
}
