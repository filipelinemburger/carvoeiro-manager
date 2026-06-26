package br.com.elifootcarvoeiro.player;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Deterministic season-to-season player evolution. The caller increments the
 * player's age first, then calls {@link #applyAgeCurve}; randomness comes from a
 * shared, seeded RNG consumed in a fixed order so a rollover is reproducible.
 */
@Component
public class PlayerAgingService {

    private final OverallCalculator overallCalculator;

    public PlayerAgingService(OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
    }

    /** Applies the age curve to the player's six attributes (based on its current age) and recomputes overall. */
    public void applyAgeCurve(Player player, Random rng) {
        int age = player.getAge();
        int pace = clamp(player.getPace() + delta(age, rng));
        int finishing = clamp(player.getFinishing() + delta(age, rng));
        int passing = clamp(player.getPassing() + delta(age, rng));
        int tackling = clamp(player.getTackling() + delta(age, rng));
        int goalkeeping = clamp(player.getGoalkeeping() + delta(age, rng));
        int stamina = clamp(player.getStamina() + delta(age, rng));

        if (age >= 33) {
            pace = clamp(pace - 1);
            stamina = clamp(stamina - 1);
        }

        int overall = overallCalculator.compute(player.getPosition(),
                pace, finishing, passing, tackling, goalkeeping, stamina);
        player.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    /** Probability (0..1) that a player of the given age retires at season rollover. */
    public double retirementProbability(int age) {
        if (age >= 38) return 1.0;
        return switch (age) {
            case 37 -> 0.70;
            case 36 -> 0.45;
            case 35 -> 0.25;
            default -> 0.0;
        };
    }

    private int delta(int age, Random rng) {
        if (age <= 21) return rng.nextInt(4);       // 0..+3
        if (age <= 29) return rng.nextInt(3) - 1;   // -1..+1
        if (age <= 32) return rng.nextInt(3) - 2;   // -2..0
        return -(1 + rng.nextInt(4));               // -4..-1
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
