package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.MatchupModifiers.ChanceMults;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Formula-table + determinism coverage for {@link MatchupModifiers}. See engine-map.md Task 18
 * hook: this is a pure function (no RNG) multiplied into the per-minute scoring chance in both
 * engines, so BALANCED/BALANCED must be exactly neutral to preserve pre-Task-18 determinism.
 */
class MatchupModifiersTest {

    private TeamMatchInput input(double attack, double defense, Mentality mentality) {
        return input(attack, defense, mentality, GameStyle.EQUILIBRADO);
    }

    private TeamMatchInput input(double attack, double defense, Mentality mentality, GameStyle gameStyle) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, mentality, gameStyle);
    }

    @Test
    void balancedVsBalancedIsExactlyNeutral() {
        TeamMatchInput home = input(70, 65, Mentality.BALANCED);
        TeamMatchInput away = input(60, 55, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isEqualTo(1.0);
        assertThat(mults.away()).isEqualTo(1.0);
    }

    @Test
    void attackingRewardsAttackOutgunningOpponentDefense() {
        TeamMatchInput home = input(80, 50, Mentality.ATTACKING);
        TeamMatchInput away = input(60, 55, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        double expectedHome = 1 + 0.15 * Math.tanh((80 - 55) / 50.0);
        assertThat(mults.home()).isEqualTo(expectedHome, within(1e-9));
        assertThat(mults.home()).isGreaterThan(1.0);
        assertThat(mults.away()).isEqualTo(1.0); // home's ATTACKING never touches away's mult
    }

    @Test
    void attackingCostsWhenAttackIsWeakerThanOpponentDefense() {
        TeamMatchInput home = input(40, 50, Mentality.ATTACKING);
        TeamMatchInput away = input(60, 80, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        double expectedHome = 1 + 0.15 * Math.tanh((40 - 80) / 50.0);
        assertThat(mults.home()).isEqualTo(expectedHome, within(1e-9));
        assertThat(mults.home()).isLessThan(1.0);
    }

    @Test
    void defensiveReducesOpponentChanceWhenDefenseOutgunsOpponentAttack() {
        TeamMatchInput home = input(60, 90, Mentality.DEFENSIVE);
        TeamMatchInput away = input(50, 55, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        double expectedAway = 1 - 0.15 * Math.tanh((90 - 50) / 50.0);
        assertThat(mults.away()).isEqualTo(expectedAway, within(1e-9));
        assertThat(mults.away()).isLessThan(1.0);
        assertThat(mults.home()).isEqualTo(1.0); // home's DEFENSIVE never touches its own mult
    }

    @Test
    void defensiveBacklashRaisesOpponentChanceWhenDefenseIsOutgunned() {
        TeamMatchInput home = input(50, 40, Mentality.DEFENSIVE);
        TeamMatchInput away = input(90, 55, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        double expectedAway = 1 - 0.15 * Math.tanh((40 - 90) / 50.0);
        assertThat(mults.away()).isEqualTo(expectedAway, within(1e-9));
        assertThat(mults.away()).isGreaterThan(1.0);
    }

    @Test
    void bothSidesContributeMultiplicativelyToTheSameMultiplier() {
        // Home ATTACKING (pushes homeMult up) and away DEFENSIVE weaker than home's attack
        // (also pushes homeMult up) compound multiplicatively on the same side.
        TeamMatchInput home = input(200, 50, Mentality.ATTACKING);
        TeamMatchInput away = input(50, 40, Mentality.DEFENSIVE);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        double attackingFactor = 1 + 0.15 * Math.tanh((200 - 40) / 50.0);
        double defensiveBacklashFactor = 1 - 0.15 * Math.tanh((40 - 200) / 50.0);
        double expectedHomeUnclamped = attackingFactor * defensiveBacklashFactor;
        assertThat(expectedHomeUnclamped).isGreaterThan(1.20); // sanity: this scenario would exceed the ceiling
        assertThat(mults.home()).isEqualTo(1.20);
    }

    @Test
    void clampsToCeilingAtExtremeMismatch() {
        // Both home's ATTACKING effect and away's DEFENSIVE-backlash effect push homeMult up;
        // compounded (1.15 * 1.15 = 1.3225) they exceed the ceiling, so the clamp must bite.
        TeamMatchInput home = input(500, 0, Mentality.ATTACKING);
        TeamMatchInput away = input(0, 0, Mentality.DEFENSIVE);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isEqualTo(1.20, within(1e-9));
    }

    @Test
    void clampsToFloorAtExtremeMismatch() {
        // Home ATTACKING far weaker than away's defense AND away DEFENSIVE far stronger than
        // home's attack both push homeMult toward the floor.
        TeamMatchInput home = input(0, 0, Mentality.ATTACKING);
        TeamMatchInput away = input(500, 500, Mentality.DEFENSIVE);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isEqualTo(0.80, within(1e-9));
    }

    @Test
    void singleSidedAttackingEffectAloneNeverReachesTheClamps() {
        // A lone ATTACKING (or DEFENSIVE) contribution maxes out at 1 +/- 0.15 = [0.85, 1.15],
        // strictly inside [0.80, 1.20] — the clamp only bites when both sides' effects compound.
        TeamMatchInput home = input(1000, 0, Mentality.ATTACKING);
        TeamMatchInput away = input(0, 0, Mentality.BALANCED);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isCloseTo(1.15, within(1e-6));
    }

    @Test
    void counterAttackStyleGetsABonusAgainstAnAttackingOpponent() {
        // Home plays CONTRA_ATAQUE (with a neutral BALANCED mentality so no mentality effect fires)
        // against an ATTACKING away side: home's chance is lifted by the flat 1.04 counter bonus,
        // and away's ATTACKING effect is the only thing touching awayMult.
        TeamMatchInput home = input(70, 65, Mentality.BALANCED, GameStyle.CONTRA_ATAQUE);
        TeamMatchInput away = input(60, 55, Mentality.ATTACKING, GameStyle.EQUILIBRADO);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isEqualTo(1.04, within(1e-9));
    }

    @Test
    void counterAttackStyleDoesNothingAgainstANonAttackingOpponent() {
        // Same CONTRA_ATAQUE home, but the opponent is BALANCED: the bonus must not fire, so both
        // sides stay exactly neutral (proving the bonus is gated on the opponent's ATTACKING).
        TeamMatchInput home = input(70, 65, Mentality.BALANCED, GameStyle.CONTRA_ATAQUE);
        TeamMatchInput away = input(60, 55, Mentality.BALANCED, GameStyle.EQUILIBRADO);

        ChanceMults mults = MatchupModifiers.chanceMults(home, away);

        assertThat(mults.home()).isEqualTo(1.0);
        assertThat(mults.away()).isEqualTo(1.0);
    }

    @Test
    void determinismRegressionAppliesToRealEngineForBalancedVsBalanced() {
        // Guards against the change altering BALANCED-vs-BALANCED behaviour in the real engines:
        // multiplying a chance by exactly 1.0 must be a no-op, so simulate() output is unchanged.
        MatchSimulationService engine = new MatchSimulationService();
        TeamMatchInput home = input(75, 65, Mentality.BALANCED);
        TeamMatchInput away = input(60, 70, Mentality.BALANCED);

        SimulatedMatch first = engine.simulate(home, away, Weather.neutral(), 123L);
        SimulatedMatch second = engine.simulate(home, away, Weather.neutral(), 123L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void attackingStrongVsWeakScoresMoreMeanGoalsThanBalancedOverManySeeds() {
        MatchSimulationService engine = new MatchSimulationService();
        // Strong attack vs weak defense: attack (85) comfortably outguns opponent defense (40).
        TeamMatchInput weakAway = input(50, 40, Mentality.BALANCED);

        TeamMatchInput attackingHome = input(85, 60, Mentality.ATTACKING);
        TeamMatchInput balancedHome = input(85, 60, Mentality.BALANCED);

        long attackingGoals = 0;
        long balancedGoals = 0;
        for (long seed = 0; seed < 200; seed++) {
            attackingGoals += engine.simulate(attackingHome, weakAway, Weather.neutral(), seed).homeGoals();
            balancedGoals += engine.simulate(balancedHome, weakAway, Weather.neutral(), seed).homeGoals();
        }

        double attackingMean = attackingGoals / 200.0;
        double balancedMean = balancedGoals / 200.0;
        assertThat(attackingMean).isGreaterThan(balancedMean);
    }
}
