package br.com.elifootcarvoeiro.match;

import java.util.List;

public record SimulatedMatch(int homeGoals, int awayGoals, List<SimulatedGoal> goals) {
}
