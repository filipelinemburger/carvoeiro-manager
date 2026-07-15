package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * Manager-directed training: biases one of a player's six anchor attributes beyond what the
 * season's automatic age curve already produced, at an intensity-scaled injury-risk cost.
 * Called from {@link br.com.carvoeiromanager.season.SeasonRolloverService} right after
 * {@link PlayerAgingService#applyAgeCurve}, consuming the same shared seeded {@link Random} so
 * a rollover stays reproducible.
 */
@Component
public class TrainingService {

    private final OverallCalculator overallCalculator;
    private final StaffEffectsService staffEffectsService;

    public TrainingService(OverallCalculator overallCalculator, StaffEffectsService staffEffectsService) {
        this.overallCalculator = overallCalculator;
        this.staffEffectsService = staffEffectsService;
    }

    /** No-op when {@code focus} is null — "not training" must leave the player untouched. */
    public void applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng,
                              UUID clubId, boolean managed) {
        if (focus == null) {
            return;
        }

        int bonus = switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };
        bonus += staffEffectsService.trainingBonusFor(clubId, managed);

        int pace = player.getPace();
        int finishing = player.getFinishing();
        int passing = player.getPassing();
        int tackling = player.getTackling();
        int goalkeeping = player.getGoalkeeping();
        int stamina = player.getStamina();

        switch (focus) {
            case VELOCIDADE -> pace = clamp(pace + bonus);
            case FINALIZACAO -> finishing = clamp(finishing + bonus);
            case PASSE -> passing = clamp(passing + bonus);
            case DESARME -> tackling = clamp(tackling + bonus);
            case REFLEXOS -> goalkeeping = clamp(goalkeeping + bonus);
            case FOLEGO -> stamina = clamp(stamina + bonus);
            default -> throw new IllegalArgumentException("Attribute is not trainable: " + focus);
        }

        int overall = overallCalculator.compute(player.getPosition(),
                pace, finishing, passing, tackling, goalkeeping, stamina);
        player.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);

        double risk = switch (intensity) {
            case LEVE -> 0.0;
            case NORMAL -> 0.03;
            case INTENSA -> 0.08;
        };
        if (rng.nextDouble() < risk) {
            player.addInjury(1 + rng.nextInt(2));
        }
    }

    /** Fixed position-based focus used for every AI-controlled club's players. */
    public static Attribute heuristicFocusFor(Position position) {
        return switch (position) {
            case GK -> Attribute.REFLEXOS;
            case DEF -> Attribute.DESARME;
            case MID -> Attribute.PASSE;
            case ATT -> Attribute.FINALIZACAO;
        };
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
