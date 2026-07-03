package br.com.carvoeiromanager.player;

import org.springframework.stereotype.Component;

/**
 * Season-to-season development of youth prospects: grows their attributes toward a hidden potential
 * ceiling (never past it) and maps potential to a 1-5 star display band. Pure and deterministic.
 */
@Component
public class YouthDevelopmentService {

    private static final double DEV_RATE = 0.35;   // fraction of the potential gap closed per season

    private final OverallCalculator overallCalculator;

    public YouthDevelopmentService(OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
    }

    /** 1-5 star band for a hidden potential overall. */
    public int potentialStars(int potential) {
        if (potential >= 85) return 5;
        if (potential >= 78) return 4;
        if (potential >= 70) return 3;
        if (potential >= 62) return 2;
        return 1;
    }

    /**
     * Grows the prospect's six attributes toward its potential. Bumps all attributes by the largest
     * uniform step (up to 35% of the remaining overall gap) that keeps recomputed overall at or below
     * potential; no-op once the prospect is within one point of, or at, its ceiling.
     */
    public void develop(Player p) {
        int gap = p.getPotential() - p.getOverall();
        if (gap <= 0) {
            return;
        }
        int step = Math.max(1, (int) Math.round(gap * DEV_RATE));
        for (int s = step; s >= 1; s--) {
            int pace = clamp(p.getPace() + s);
            int finishing = clamp(p.getFinishing() + s);
            int passing = clamp(p.getPassing() + s);
            int tackling = clamp(p.getTackling() + s);
            int goalkeeping = clamp(p.getGoalkeeping() + s);
            int stamina = clamp(p.getStamina() + s);
            int overall = overallCalculator.compute(p.getPosition(), pace, finishing, passing,
                    tackling, goalkeeping, stamina);
            if (overall <= p.getPotential()) {
                p.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
                return;
            }
        }
        // Even a +1 bump would exceed potential: the prospect is effectively at its ceiling.
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
