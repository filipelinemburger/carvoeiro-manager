package br.com.carvoeiromanager.match;

import java.util.List;

public record SimulatedMatch(int homeGoals, int awayGoals, List<SimulatedEvent> events) {
}
