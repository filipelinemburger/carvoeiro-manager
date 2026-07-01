// FitnessCalculator.java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AgeBand;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure fitness math: lazy day-based recovery, match decay, the match-strength
 * multiplier, and fitness/age-linked injury probability. No repository access.
 */
@Service
public class FitnessCalculator {

    static final double RECOVERY_BASE = 5.0;   // fitness points recovered per rest day (prime age)
    static final double DECAY_BASE = 30.0;     // base fitness lost for a full match (prime, stamina 70)
    static final double BASE_INJURY = 0.025;   // injury chance at full fitness, prime age
    static final double INJURY_FITNESS_K = 1.5;

    /** Effective fitness at {@code onDate}: base plus recovery for days rested, capped at 100. */
    public int effectiveFitness(int base, LocalDate lastMatchDate, LocalDate onDate, int age) {
        if (lastMatchDate == null) {
            return 100;
        }
        long daysRested = Math.max(0, ChronoUnit.DAYS.between(lastMatchDate, onDate));
        double recovered = base + daysRested * RECOVERY_BASE * AgeBand.of(age).recoveryFactor();
        return (int) Math.round(Math.min(100.0, recovered));
    }

    /** New base fitness after playing a full match from the given kickoff fitness. */
    public int decayedBaseFitness(int effectiveAtKickoff, int stamina, int age) {
        double decay = DECAY_BASE * clamp(70.0 / stamina, 0.7, 1.4) * AgeBand.of(age).decayFactor();
        return (int) Math.max(0, Math.round(effectiveAtKickoff - decay));
    }

    /** Match-strength multiplier: 1.0 at 100%, 0.90 at 50%, 0.80 at 0%. */
    public double fitnessMult(int effectiveFitness) {
        return 0.80 + 0.20 * (effectiveFitness / 100.0);
    }

    /** Per-match injury probability, rising as fitness falls and with age. */
    public double injuryChance(int effectiveFitness, int age) {
        double fatigue = 1.0 - effectiveFitness / 100.0;
        return BASE_INJURY * (1.0 + INJURY_FITNESS_K * fatigue) * AgeBand.of(age).injuryFactor();
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
