package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Mentality;

/**
 * Pure, RNG-free per-side multiplier on the per-minute scoring chance, rewarding a mentality
 * that plays to the matchup: ATTACKING pays off when the side's attack outguns the opponent's
 * defense (and costs when it doesn't); DEFENSIVE is symmetric, suppressing the opponent's chance
 * when this side's defense outguns the opponent's attack. BALANCED contributes nothing, so a
 * BALANCED-vs-BALANCED matchup yields exactly {@code ChanceMults(1.0, 1.0)} — byte-identical to
 * pre-Task-18 behaviour.
 */
public final class MatchupModifiers {

    private static final double MENTALITY_WEIGHT = 0.15;
    private static final double DIFF_SCALE = 50.0;
    private static final double MULT_FLOOR = 0.80;
    private static final double MULT_CEILING = 1.20;

    private MatchupModifiers() {
    }

    /** One multiplier per side, applied directly to that side's per-minute scoring chance. */
    public record ChanceMults(double home, double away) {
    }

    public static ChanceMults chanceMults(TeamMatchInput home, TeamMatchInput away) {
        double homeMult = 1.0;
        double awayMult = 1.0;

        // Home's mentality effect
        if (home.mentality() == Mentality.ATTACKING) {
            homeMult *= 1 + MENTALITY_WEIGHT * Math.tanh((home.attack() - away.defense()) / DIFF_SCALE);
        } else if (home.mentality() == Mentality.DEFENSIVE) {
            awayMult *= 1 - MENTALITY_WEIGHT * Math.tanh((home.defense() - away.attack()) / DIFF_SCALE);
        }

        // Away's mentality effect
        if (away.mentality() == Mentality.ATTACKING) {
            awayMult *= 1 + MENTALITY_WEIGHT * Math.tanh((away.attack() - home.defense()) / DIFF_SCALE);
        } else if (away.mentality() == Mentality.DEFENSIVE) {
            homeMult *= 1 - MENTALITY_WEIGHT * Math.tanh((away.defense() - home.attack()) / DIFF_SCALE);
        }

        homeMult = clamp(homeMult, MULT_FLOOR, MULT_CEILING);
        awayMult = clamp(awayMult, MULT_FLOOR, MULT_CEILING);

        return new ChanceMults(homeMult, awayMult);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
