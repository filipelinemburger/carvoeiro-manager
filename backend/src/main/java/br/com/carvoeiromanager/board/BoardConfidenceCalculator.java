package br.com.carvoeiromanager.board;

import java.util.ArrayList;
import java.util.List;

/**
 * All of the board's arithmetic (spec §6). Pure and deterministic: no RNG, no repositories. The
 * constants are the starting tuning and live here together so playtesting can adjust them in one
 * place; the shape of each rule is the design.
 */
public final class BoardConfidenceCalculator {

    public static final int START = 60;
    public static final int MIN = 0;
    public static final int MAX = 100;

    // Expected points: 1.35 for equals, ±0.05 per point of strength difference, ±0.25 home/away.
    // Fitted to the match engine (two full-pyramid seasons, ~4,000 club-matches: base 1.37, slope
    // 0.046, home 0.25). The spec's first guess of 0.08/0.30 was twice as steep as the engine, so a
    // favourite sitting on its target still bled confidence every week. Refit if the engine's
    // strength-to-result curve changes.
    static final double BASE_EXPECTED = 1.35;
    static final double PER_STRENGTH_POINT = 0.05;
    static final double HOME_ADVANTAGE = 0.25;
    static final double EXPECTED_MIN = 0.30;
    static final double EXPECTED_MAX = 2.70;
    /**
     * Confidence per point above/below expectation. The engine's per-match noise is about ±1.26
     * points, so at 2 a run of pure luck rarely carries a manager from 60 to the ultimatum on its own.
     */
    static final int RESULT_SCALE = 2;
    static final int HEAVY_DEFEAT_MARGIN = 4;
    static final int HEAVY_DEFEAT = -3;
    /** Positions below the reference that turn the trajectory nudge negative. */
    static final int TRAJECTORY_BAND = 5;
    static final int NEGATIVE_BALANCE = -1;
    /** One match can never move confidence by more than this, either way. */
    static final int MATCH_CLAMP = 8;

    public static final int TITLE_WON = 4;

    private BoardConfidenceCalculator() { }

    public record Part(BoardChangeReason reason, int delta) { }

    /**
     * {@code leaguePosition}/{@code leagueReference} are null when the trajectory rule does not
     * apply: not a national-league match, no round-robin table running, or the league is fewer than
     * seven matches old. The caller decides that; this class only applies the arithmetic.
     */
    public record MatchInput(double ownStrength, double opponentStrength, boolean home,
                             int goalsFor, int goalsAgainst, boolean stateCompetition,
                             Integer leaguePosition, Integer leagueReference, boolean negativeBalance) { }

    /**
     * The match's parts in a fixed order (result, heavy defeat, table, balance), zero parts
     * dropped, and the total clamped to ±{@value #MATCH_CLAMP}. A clamp trims from the last part
     * backwards, so the result row — the one the manager cares about — survives intact.
     */
    public static List<Part> afterMatch(MatchInput in) {
        List<Part> parts = new ArrayList<>();

        double diff = in.ownStrength() - in.opponentStrength();
        double expected = Math.clamp(BASE_EXPECTED + PER_STRENGTH_POINT * diff
                + (in.home() ? HOME_ADVANTAGE : -HOME_ADVANTAGE), EXPECTED_MIN, EXPECTED_MAX);
        int points = in.goalsFor() > in.goalsAgainst() ? 3 : in.goalsFor() == in.goalsAgainst() ? 1 : 0;
        int result = (int) Math.round((points - expected) * RESULT_SCALE);
        if (in.stateCompetition()) {
            // The board cares most about the league: state matches count half.
            result = (int) Math.round(result / 2.0);
        }
        add(parts, BoardChangeReason.MATCH_RESULT, result);

        if (in.goalsAgainst() - in.goalsFor() >= HEAVY_DEFEAT_MARGIN) {
            add(parts, BoardChangeReason.HEAVY_DEFEAT, HEAVY_DEFEAT);
        }

        if (in.leaguePosition() != null && in.leagueReference() != null) {
            int below = in.leaguePosition() - in.leagueReference();
            add(parts, BoardChangeReason.TABLE_POSITION, below <= 0 ? 1 : below >= TRAJECTORY_BAND ? -1 : 0);
        }

        if (in.negativeBalance()) {
            add(parts, BoardChangeReason.NEGATIVE_BALANCE, NEGATIVE_BALANCE);
        }

        return clampTotal(parts);
    }

    private static void add(List<Part> parts, BoardChangeReason reason, int delta) {
        if (delta != 0) {
            parts.add(new Part(reason, delta));
        }
    }

    private static List<Part> clampTotal(List<Part> parts) {
        int total = parts.stream().mapToInt(Part::delta).sum();
        int excess = total > MATCH_CLAMP ? total - MATCH_CLAMP : total < -MATCH_CLAMP ? total + MATCH_CLAMP : 0;
        for (int i = parts.size() - 1; i >= 0 && excess != 0; i--) {
            Part part = parts.get(i);
            // Only trim a part pointing the same way as the excess; trimming toward zero, never past it.
            if (Integer.signum(part.delta()) != Integer.signum(excess)) {
                continue;
            }
            int take = Integer.signum(excess) * Math.min(Math.abs(part.delta()), Math.abs(excess));
            excess -= take;
            int left = part.delta() - take;
            if (left == 0) {
                parts.remove(i);
            } else {
                parts.set(i, new Part(part.reason(), left));
            }
        }
        return List.copyOf(parts);
    }

    /**
     * A league objective with a position target, at the end of its table. Finishing in the Z-4 of a
     * division with a relegation zone (Série A and B) overrides the place-count rows.
     */
    public static int leaguePositionDelta(int finalPosition, int target, boolean relegationZone, int fieldSize) {
        if (relegationZone && finalPosition > fieldSize - 4) {
            return -35;
        }
        if (finalPosition <= target) {
            return target - finalPosition >= 4 ? 20 : 12;
        }
        return finalPosition - target <= 3 ? -12 : -25;
    }

    /**
     * A league objective with a stage target. It resolves the moment the target is reached, so
     * going further never earns more than meeting it.
     */
    public static int leagueStageDelta(Stage reached, Stage target) {
        int diff = reached.ordinal() - target.ordinal();
        if (diff >= 0) {
            return 12;
        }
        return diff == -1 ? -12 : -25;
    }

    public static int stateDelta(boolean met) {
        return met ? 6 : -8;
    }

    public static int financeDelta(boolean met) {
        return met ? 4 : -10;
    }

    /**
     * Between seasons confidence moves halfway back toward {@value #START}. The {@code + 1} rounds
     * the move up, so an odd gap lands one point nearer 60 rather than one point further away.
     */
    public static int regress(int confidence) {
        int gap = START - confidence;
        return confidence + Integer.signum(gap) * ((Math.abs(gap) + 1) / 2);
    }

    public static int clamp(int confidence) {
        return Math.clamp(confidence, MIN, MAX);
    }
}
