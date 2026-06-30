package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverallCalculatorTest {

    private final OverallCalculator calculator = new OverallCalculator();

    @Test
    void goalkeeperOverallIsDominatedByGoalkeeping() {
        // Given a GK with goalkeeping 80, others 40
        // GK weights: gk .60, stamina .15, passing .15, pace .10
        // 80*.60 + 40*.15 + 40*.15 + 40*.10 = 48 + 6 + 6 + 4 = 64
        int overall = calculator.compute(Position.GK, 40, 40, 40, 40, 80, 40);
        assertThat(overall).isEqualTo(64);
    }

    @Test
    void attackerOverallIsDominatedByFinishingAndPace() {
        // ATT weights: finishing .40, pace .25, passing .15, stamina .10, tackling .10
        // pace 70, finishing 80, passing 60, tackling 50, gk 10, stamina 60
        // 80*.40 + 70*.25 + 60*.15 + 60*.10 + 50*.10 = 32 + 17.5 + 9 + 6 + 5 = 69.5 -> 70
        int overall = calculator.compute(Position.ATT, 70, 80, 60, 50, 10, 60);
        assertThat(overall).isEqualTo(70);
    }

    @Test
    void defenderWeightsTacklingHighest() {
        // DEF weights: tackling .40, pace .20, stamina .15, passing .15, finishing .10
        // pace 60, finishing 40, passing 55, tackling 75, gk 10, stamina 65
        // 75*.40 + 60*.20 + 65*.15 + 55*.15 + 40*.10 = 30 + 12 + 9.75 + 8.25 + 4 = 64 -> 64
        int overall = calculator.compute(Position.DEF, 60, 40, 55, 75, 10, 65);
        assertThat(overall).isEqualTo(64);
    }

    @Test
    void midfielderWeightsPassingHighest() {
        // MID weights: passing .35, stamina .20, tackling .20, pace .15, finishing .10
        // pace 65, finishing 50, passing 78, tackling 60, gk 10, stamina 70
        // 78*.35 + 70*.20 + 60*.20 + 65*.15 + 50*.10 = 27.3 + 14 + 12 + 9.75 + 5 = 68.05 -> 68
        int overall = calculator.compute(Position.MID, 65, 50, 78, 60, 10, 70);
        assertThat(overall).isEqualTo(68);
    }
}
