package br.com.elifootcarvoeiro.match;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {
    private final MatchSimulationService engine = new MatchSimulationService();

    @Test
    void sameSeedAndInputsProduceIdenticalResult() {
        LineupStrength a = new LineupStrength(70, 65);
        LineupStrength b = new LineupStrength(60, 60);

        SimulatedMatch first = engine.simulate(a, b, 42L);
        SimulatedMatch second = engine.simulate(a, b, 42L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void goalListSizeMatchesScoreline() {
        SimulatedMatch m = engine.simulate(new LineupStrength(75, 70), new LineupStrength(55, 50), 7L);

        long homeGoals = m.goals().stream().filter(SimulatedGoal::home).count();
        long awayGoals = m.goals().stream().filter(g -> !g.home()).count();
        assertThat(homeGoals).isEqualTo(m.homeGoals());
        assertThat(awayGoals).isEqualTo(m.awayGoals());
    }

    @Test
    void goalMinutesAreWithinRegulationTime() {
        SimulatedMatch m = engine.simulate(new LineupStrength(80, 75), new LineupStrength(50, 45), 99L);
        assertThat(m.goals()).allSatisfy(g -> assertThat(g.minute()).isBetween(1, 90));
    }

    @Test
    void strongerTeamWinsMoreOftenOverManySeeds() {
        LineupStrength strong = new LineupStrength(85, 80);
        LineupStrength weak = new LineupStrength(45, 40);
        int strongWins = 0;
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(strong, weak, seed);
            if (m.homeGoals() > m.awayGoals()) strongWins++;
        }
        assertThat(strongWins).isGreaterThan(120); // clearly favoured
    }
}
