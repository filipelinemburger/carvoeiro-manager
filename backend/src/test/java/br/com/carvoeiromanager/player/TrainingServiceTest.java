package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingServiceTest {

    private final TrainingService training = new TrainingService(new OverallCalculator());

    private Player player(Position position, int attr) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", position,
                24, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    @Test
    void doesNothingWhenFocusIsNull() {
        // Given a player with no training focus
        Player p = player(Position.MID, 50);

        // When training is applied with a null focus
        training.applyTraining(p, null, TrainingIntensity.INTENSA, new Random(1));

        // Then nothing changes: no attribute bump, no injury roll consumed
        assertThat(p.getFinishing()).isEqualTo(50);
        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void leveAddsOneToTheFocusedAttributeOnly() {
        // Given a player with all attributes at 50
        Player p = player(Position.MID, 50);

        // When trained Leve on Finalização
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.LEVE, new Random(1));

        // Then only Finalização rose, by exactly 1
        assertThat(p.getFinishing()).isEqualTo(51);
        assertThat(p.getPace()).isEqualTo(50);
        assertThat(p.getPassing()).isEqualTo(50);
        assertThat(p.getTackling()).isEqualTo(50);
        assertThat(p.getGoalkeeping()).isEqualTo(50);
        assertThat(p.getStamina()).isEqualTo(50);
    }

    @Test
    void normalAddsTwoAndIntensaAddsThree() {
        Player normal = player(Position.MID, 50);
        Player intensa = player(Position.MID, 50);

        training.applyTraining(normal, Attribute.PASSE, TrainingIntensity.NORMAL, new Random(1));
        training.applyTraining(intensa, Attribute.PASSE, TrainingIntensity.INTENSA, new Random(1));

        assertThat(normal.getPassing()).isEqualTo(52);
        assertThat(intensa.getPassing()).isEqualTo(53);
    }

    @Test
    void theBonusIsClampedAtNinetyNine() {
        // Given a player already maxed at 99
        Player p = player(Position.ATT, 99);

        // When trained Intensa (+3 would overflow to 102)
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1));

        // Then it stays capped at 99
        assertThat(p.getFinishing()).isEqualTo(99);
    }

    @Test
    void overallIsRecomputedAfterTheBonus() {
        Player p = player(Position.ATT, 50);
        int overallBefore = p.getOverall();

        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1));

        assertThat(p.getOverall()).isGreaterThan(overallBefore);
    }

    @Test
    void leveNeverRollsAnInjuryRegardlessOfSeed() {
        // 0% risk at Leve: no seed should ever produce an injury.
        for (int seed = 0; seed < 20; seed++) {
            Player p = player(Position.MID, 50);
            training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.LEVE, new Random(seed));
            assertThat(p.getInjuryMatches()).isZero();
        }
    }

    @Test
    void intensaCanRollAOneToTwoMatchInjury() {
        // Given seed 4608, verified to draw 0.0520... as its first nextDouble() — under
        // Intensa's 8% risk threshold (new Random(4608).nextDouble() == 0.05202727943619023)
        Player p = player(Position.MID, 50);

        // When trained Intensa with that seed
        training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.INTENSA, new Random(4608));

        // Then an injury of 1 or 2 matches was applied
        assertThat(p.getInjuryMatches()).isBetween(1, 2);
    }

    @Test
    void heuristicFocusMapsEachPositionToItsMostValuableAnchor() {
        assertThat(TrainingService.heuristicFocusFor(Position.GK)).isEqualTo(Attribute.REFLEXOS);
        assertThat(TrainingService.heuristicFocusFor(Position.DEF)).isEqualTo(Attribute.DESARME);
        assertThat(TrainingService.heuristicFocusFor(Position.MID)).isEqualTo(Attribute.PASSE);
        assertThat(TrainingService.heuristicFocusFor(Position.ATT)).isEqualTo(Attribute.FINALIZACAO);
    }
}
