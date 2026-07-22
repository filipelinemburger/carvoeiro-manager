package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    // attack, defense, tempo, aggression, cornerThreat, freeKickThreat, penaltyConversion, fitnessDecay, mentality
    private TeamMatchInput input(double attack, double defense) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED);
    }

    private TeamMatchInput input() {
        return input(70, 65);
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
                .filter(e -> e.type().isGoal())
                .count();
        long awayGoals = m.events().stream()
                .filter(e -> !e.home())
                .filter(e -> e.type().isGoal())
                .count();
        assertThat(homeGoals).isEqualTo(m.homeGoals());
        assertThat(awayGoals).isEqualTo(m.awayGoals());
    }

    @Test
    void instantEngineEmitsDistinctSetPieceGoalsAndNeverTheLegacyType() {
        Set<EventType> seen = EnumSet.noneOf(EventType.class);
        for (long seed = 0; seed < 300; seed++) {
            SimulatedMatch m = engine.simulate(input(), input(), seed);
            m.events().forEach(e -> seen.add(e.type()));
            long homeGoalEvents = m.events().stream().filter(e -> e.home() && e.type().isGoal()).count();
            assertThat(m.homeGoals()).isEqualTo((int) homeGoalEvents);   // a missed pen leaves no event and no goal
        }
        assertThat(seen).contains(EventType.PENALTY_GOAL, EventType.FREE_KICK_GOAL, EventType.CORNER_GOAL);
        assertThat(seen).doesNotContain(EventType.SET_PIECE_GOAL, EventType.PENALTY_MISSED);
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
        TeamMatchInput tired = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.40, Mentality.BALANCED);
        TeamMatchInput fresh = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.05, Mentality.BALANCED);
        int firstHalf = 0;
        int secondHalf = 0;
        for (long seed = 0; seed < 400; seed++) {
            SimulatedMatch m = engine.simulate(tired, fresh, seed);
            for (SimulatedEvent e : m.events()) {
                if (e.home() && e.type().isGoal()) {
                    if (e.minute() <= 45) firstHalf++; else secondHalf++;
                }
            }
        }
        assertThat(firstHalf).isGreaterThan(secondHalf);
    }

    @Test
    void higherAggressionProducesMoreCardsOverManySeeds() {
        TeamMatchInput calm = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED);
        TeamMatchInput fiery = new TeamMatchInput(70, 65, 1.0, 2.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED);
        long calmCards = countCards(calm);
        long fieryCards = countCards(fiery);
        assertThat(fieryCards).isGreaterThan(calmCards);
    }

    private long countCards(TeamMatchInput side) {
        long total = 0;
        TeamMatchInput opp = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED);
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
