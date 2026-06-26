package br.com.elifootcarvoeiro.match;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class MatchSimulationService {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;

    public SimulatedMatch simulate(LineupStrength home, LineupStrength away, long seed) {
        Random rng = new Random(seed);

        double homeChance = perMinuteScoreChance(home.attack() + HOME_ADVANTAGE, away.defense());
        double awayChance = perMinuteScoreChance(away.attack(), home.defense() + HOME_ADVANTAGE);

        List<SimulatedGoal> goals = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;
        for (int minute = 1; minute <= MINUTES; minute++) {
            if (rng.nextDouble() < homeChance) { homeGoals++; goals.add(new SimulatedGoal(minute, true)); }
            if (rng.nextDouble() < awayChance) { awayGoals++; goals.add(new SimulatedGoal(minute, false)); }
        }
        return new SimulatedMatch(homeGoals, awayGoals, List.copyOf(goals));
    }

    // Maps attack-vs-defense differential to a small per-minute scoring probability.
    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;          // roughly -1..+1
        double expectedGoals = Math.max(0.2, 1.4 + diff); // avg goals per team per match
        return expectedGoals / MINUTES;
    }
}
