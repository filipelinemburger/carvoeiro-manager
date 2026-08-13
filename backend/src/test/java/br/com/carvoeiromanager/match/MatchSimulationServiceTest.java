package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    // attack, defense, tempo, aggression, cornerThreat, freeKickThreat, penaltyConversion, fitnessDecay, mentality, gameStyle
    private TeamMatchInput input(double attack, double defense) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15,
                Mentality.BALANCED, GameStyle.EQUILIBRADO);
    }

    private TeamMatchInput input() {
        return input(70, 65);
    }

    @Test
    void sameSeedAndInputsProduceIdenticalResult() {
        TeamMatchInput a = input(70, 65);
        TeamMatchInput b = input(60, 60);
        assertThat(engine.simulate(a, b, Weather.neutral(), 42L))
                .isEqualTo(engine.simulate(a, b, Weather.neutral(), 42L));
    }

    @Test
    void goalEventCountMatchesScoreline() {
        SimulatedMatch m = engine.simulate(input(75, 70), input(55, 50), Weather.neutral(), 7L);
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
            SimulatedMatch m = engine.simulate(input(), input(), Weather.neutral(), seed);
            m.events().forEach(e -> seen.add(e.type()));
            long homeGoalEvents = m.events().stream().filter(e -> e.home() && e.type().isGoal()).count();
            assertThat(m.homeGoals()).isEqualTo((int) homeGoalEvents);   // a missed pen leaves no event and no goal
        }
        assertThat(seen).contains(EventType.PENALTY_GOAL, EventType.FREE_KICK_GOAL, EventType.CORNER_GOAL);
        assertThat(seen).doesNotContain(EventType.SET_PIECE_GOAL, EventType.PENALTY_MISSED);
    }

    @Test
    void eventMinutesAreWithinRegulationTime() {
        SimulatedMatch m = engine.simulate(input(80, 75), input(50, 45), Weather.neutral(), 99L);
        assertThat(m.events()).allSatisfy(e -> assertThat(e.minute()).isBetween(1, 90));
    }

    @Test
    void strongerTeamWinsMoreOftenOverManySeeds() {
        TeamMatchInput strong = input(85, 80);
        TeamMatchInput weak = input(45, 40);
        int strongWins = 0;
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(strong, weak, Weather.neutral(), seed);
            if (m.homeGoals() > m.awayGoals()) strongWins++;
        }
        assertThat(strongWins).isGreaterThan(120);
    }

    @Test
    void highDecayTeamScoresLargerShareOfGoalsInFirstHalf() {
        // Two evenly-matched teams; the "home" side has high fitness decay.
        TeamMatchInput tired = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.40, Mentality.BALANCED, GameStyle.EQUILIBRADO);
        TeamMatchInput fresh = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.05, Mentality.BALANCED, GameStyle.EQUILIBRADO);
        int firstHalf = 0;
        int secondHalf = 0;
        for (long seed = 0; seed < 400; seed++) {
            SimulatedMatch m = engine.simulate(tired, fresh, Weather.neutral(), seed);
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
        TeamMatchInput calm = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED, GameStyle.EQUILIBRADO);
        TeamMatchInput fiery = new TeamMatchInput(70, 65, 1.0, 2.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED, GameStyle.EQUILIBRADO);
        long calmCards = countCards(calm);
        long fieryCards = countCards(fiery);
        assertThat(fieryCards).isGreaterThan(calmCards);
    }

    @Test
    void cardsPerSidePerMatchSitAroundOneAndAThird() {
        // Calibration guard (spec 2026-08-13 §9): ~1.35 cards per side per 90 at aggression 1.0,
        // so ~2.7 per match. Asserted as a band over 200 seeds, wide enough to absorb RNG noise
        // but tight enough that a changed BASE_CARD_RATE trips it.
        TeamMatchInput neutral = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15,
                Mentality.BALANCED, GameStyle.EQUILIBRADO);
        long homeCards = countCards(neutral);
        double perSidePerMatch = homeCards / 200.0;
        assertThat(perSidePerMatch).isBetween(1.15, 1.55);
    }

    private long countCards(TeamMatchInput side) {
        long total = 0;
        TeamMatchInput opp = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15, Mentality.BALANCED, GameStyle.EQUILIBRADO);
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(side, opp, Weather.neutral(), seed);
            total += m.events().stream()
                    .filter(SimulatedEvent::home)
                    .filter(e -> e.type() == EventType.YELLOW_CARD || e.type() == EventType.RED_CARD)
                    .count();
        }
        return total;
    }
}
