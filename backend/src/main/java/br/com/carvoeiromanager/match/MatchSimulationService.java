package br.com.carvoeiromanager.match;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, seeded match engine. Pure function: simulate(home, away, seed).
 *
 * Per-minute RNG draw order (fixed for reproducibility):
 *   home-goal, [home-set-piece?, home-subtype, home-conversion?], away-goal, [...],
 *   home-card, home-card-type, away-card, away-card-type — bracketed draws occur only when the
 *   preceding roll hits.
 *
 * Effective strength declines over the match via fitnessDecay; combined tempo
 * scales the total number of chances; aggression drives the card rate; a share
 * of goals are set-piece goals, split into corner/free-kick/penalty subtypes (typed for
 * downstream scorer attribution).
 */
@Service
public class MatchSimulationService {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;
    private static final double FITNESS_FLOOR = 0.75;
    private static final double BASE_CARD_RATE = 0.02;
    private static final double RED_SHARE = 0.06;

    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, long seed) {
        Random rng = new Random(seed);
        double tempoFactor = (home.tempo() + away.tempo()) / 2.0;
        double homeSetPiece = SetPieces.share(home.cornerThreat(), home.freeKickThreat());
        double awaySetPiece = SetPieces.share(away.cornerThreat(), away.freeKickThreat());
        double homeCardChance = BASE_CARD_RATE * home.aggression();
        double awayCardChance = BASE_CARD_RATE * away.aggression();

        List<SimulatedEvent> events = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;

        for (int minute = 1; minute <= MINUTES; minute++) {
            double homeFf = fitnessFactor(home.fitnessDecay(), minute);
            double awayFf = fitnessFactor(away.fitnessDecay(), minute);

            double homeChance = perMinuteScoreChance(home.attack() * homeFf + HOME_ADVANTAGE,
                    away.defense() * awayFf) * tempoFactor;
            double awayChance = perMinuteScoreChance(away.attack() * awayFf,
                    home.defense() * homeFf + HOME_ADVANTAGE) * tempoFactor;

            if (rng.nextDouble() < homeChance) {
                if (rng.nextDouble() < homeSetPiece) {
                    SetPieces.Kind kind = SetPieces.roll(home.cornerThreat(), home.freeKickThreat(), rng);
                    if (kind == SetPieces.Kind.PENALTY) {
                        if (rng.nextDouble() < home.penaltyConversion()) {
                            homeGoals++;
                            events.add(new SimulatedEvent(minute, EventType.PENALTY_GOAL, true));
                        }
                        // A missed penalty leaves no trace in the instant path: the goal simply
                        // doesn't happen (misses are surfaced only in the live match feed).
                    } else {
                        homeGoals++;
                        events.add(new SimulatedEvent(minute,
                                kind == SetPieces.Kind.CORNER ? EventType.CORNER_GOAL : EventType.FREE_KICK_GOAL,
                                true));
                    }
                } else {
                    homeGoals++;
                    events.add(new SimulatedEvent(minute, EventType.GOAL, true));
                }
            }
            if (rng.nextDouble() < awayChance) {
                if (rng.nextDouble() < awaySetPiece) {
                    SetPieces.Kind kind = SetPieces.roll(away.cornerThreat(), away.freeKickThreat(), rng);
                    if (kind == SetPieces.Kind.PENALTY) {
                        if (rng.nextDouble() < away.penaltyConversion()) {
                            awayGoals++;
                            events.add(new SimulatedEvent(minute, EventType.PENALTY_GOAL, false));
                        }
                        // A missed penalty leaves no trace in the instant path: the goal simply
                        // doesn't happen (misses are surfaced only in the live match feed).
                    } else {
                        awayGoals++;
                        events.add(new SimulatedEvent(minute,
                                kind == SetPieces.Kind.CORNER ? EventType.CORNER_GOAL : EventType.FREE_KICK_GOAL,
                                false));
                    }
                } else {
                    awayGoals++;
                    events.add(new SimulatedEvent(minute, EventType.GOAL, false));
                }
            }
            if (rng.nextDouble() < homeCardChance) {
                EventType type = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, true));
            }
            if (rng.nextDouble() < awayCardChance) {
                EventType type = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, false));
            }
        }
        return new SimulatedMatch(homeGoals, awayGoals, List.copyOf(events));
    }

    private double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
