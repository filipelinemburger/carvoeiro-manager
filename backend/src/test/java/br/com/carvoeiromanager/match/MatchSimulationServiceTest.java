package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    // attack, defense, tempo, aggression, setPieceThreat, fitnessDecay
    private TeamMatchInput input(double attack, double defense) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.15);
    }

    @Test
    void sameSeedAndInputsProduceIdenticalResult() {
        TeamMatchInput a = input(70, 65);
        TeamMatchInput b = input(60, 60);
        assertThat(engine.simulate(a, b, 42L)).isEqualTo(engine.simulate(a, b, 42L));
    }

    @Test
    void goalEventCountMatchesScoreline() {
        SimulatedMatch m = engine.simulate(input(75, 70), input(55, 50), 7L);
        long homeGoals = m.events().stream()
                .filter(SimulatedEvent::home)
                .filter(e -> e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)
                .count();
        long awayGoals = m.events().stream()
                .filter(e -> !e.home())
                .filter(e -> e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)
                .count();
        assertThat(homeGoals).isEqualTo(m.homeGoals());
        assertThat(awayGoals).isEqualTo(m.awayGoals());
    }

    @Test
    void eventMinutesAreWithinRegulationTime() {
        SimulatedMatch m = engine.simulate(input(80, 75), input(50, 45), 99L);
        assertThat(m.events()).allSatisfy(e -> assertThat(e.minute()).isBetween(1, 90));
    }

    @Test
    void strongerTeamWinsMoreOftenOverManySeeds() {
        TeamMatchInput strong = input(85, 80);
        TeamMatchInput weak = input(45, 40);
        int strongWins = 0;
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(strong, weak, seed);
            if (m.homeGoals() > m.awayGoals()) strongWins++;
        }
        assertThat(strongWins).isGreaterThan(120);
    }

    @Test
    void highDecayTeamScoresLargerShareOfGoalsInFirstHalf() {
        // Two evenly-matched teams; the "home" side has high fitness decay.
        TeamMatchInput tired = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.40);
        TeamMatchInput fresh = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.05);
        int firstHalf = 0;
        int secondHalf = 0;
        for (long seed = 0; seed < 400; seed++) {
            SimulatedMatch m = engine.simulate(tired, fresh, seed);
            for (SimulatedEvent e : m.events()) {
                if (e.home() && (e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)) {
                    if (e.minute() <= 45) firstHalf++; else secondHalf++;
                }
            }
        }
        assertThat(firstHalf).isGreaterThan(secondHalf);
    }

    @Test
    void higherAggressionProducesMoreCardsOverManySeeds() {
        TeamMatchInput calm = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.15);
        TeamMatchInput fiery = new TeamMatchInput(70, 65, 1.0, 2.0, 0.5, 0.15);
        long calmCards = countCards(calm);
        long fieryCards = countCards(fiery);
        assertThat(fieryCards).isGreaterThan(calmCards);
    }

    private long countCards(TeamMatchInput side) {
        long total = 0;
        TeamMatchInput opp = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.15);
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(side, opp, seed);
            total += m.events().stream()
                    .filter(SimulatedEvent::home)
                    .filter(e -> e.type() == EventType.YELLOW_CARD || e.type() == EventType.RED_CARD)
                    .count();
        }
        return total;
    }
}
