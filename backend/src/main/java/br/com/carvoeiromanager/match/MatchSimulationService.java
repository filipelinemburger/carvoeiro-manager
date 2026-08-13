package br.com.carvoeiromanager.match;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, seeded match engine. Pure function: simulate(home, away, weather, seed).
 *
 * Per-minute RNG draw order (fixed for reproducibility):
 *   home-goal, [home-set-piece?, home-subtype, home-conversion?], away-goal, [...],
 *   home-card, home-card-type, away-card, away-card-type — bracketed draws occur only when the
 *   preceding roll hits.
 *
 * Effective strength declines over the match via fitnessDecay; combined tempo
 * scales the total number of chances; aggression drives the card rate; a share
 * of goals are set-piece goals, split into corner/free-kick/penalty subtypes (typed for
 * downstream scorer attribution). {@link WeatherEffects#of} resolves the match's weather into a
 * per-side chance/decay multiplier plus a shared set-piece multiplier, applied identically to
 * both sides; neutral weather ({@link Weather#neutral()}) is guaranteed a no-op, so a
 * fixed-seed match under neutral weather is byte-identical to the pre-weather engine.
 */
@Service
public class MatchSimulationService {

    // BASE_CARD_RATE and RED_SHARE live in MatchMath — shared with LiveMatchEngine so the instant
    // and live paths book cards at the identical rate by construction.

    /** Existing entry point: a normal 90-minute match. */
    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, Weather weather, long seed) {
        return simulate(home, away, weather, seed, MatchMath.MINUTES);
    }

    /**
     * @param finalMinute the last minute to simulate: {@link MatchMath#MINUTES} normally, or
     *                    {@link MatchMath#EXTRA_TIME_END} for a knockout decider that is level
     *                    at 90. Minutes past 90 use the same per-minute rates — MINUTES stays
     *                    the rate divisor and is never changed. The 4-arg overload always passes
     *                    {@code MatchMath.MINUTES}, so a league match runs the IDENTICAL loop
     *                    with the IDENTICAL RNG draws as before this parameter existed.
     */
    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, Weather weather,
                                   long seed, int finalMinute) {
        Random rng = new Random(seed);
        WeatherEffects.Impact weatherImpact = WeatherEffects.of(weather, home, away);
        double tempoFactor = (home.tempo() + away.tempo()) / 2.0;
        double homeSetPiece = SetPieces.share(
                home.cornerThreat() * weatherImpact.setPieceMult(),
                home.freeKickThreat() * weatherImpact.setPieceMult());
        double awaySetPiece = SetPieces.share(
                away.cornerThreat() * weatherImpact.setPieceMult(),
                away.freeKickThreat() * weatherImpact.setPieceMult());
        double homeCardChance = MatchMath.BASE_CARD_RATE * home.aggression();
        double awayCardChance = MatchMath.BASE_CARD_RATE * away.aggression();

        List<SimulatedEvent> events = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;

        for (int minute = 1; minute <= finalMinute; minute++) {
            double homeFf = MatchMath.fitnessFactor(
                    home.fitnessDecay() * weatherImpact.homeDecayMult(), minute);
            double awayFf = MatchMath.fitnessFactor(
                    away.fitnessDecay() * weatherImpact.awayDecayMult(), minute);

            double homeChance = MatchMath.perMinuteScoreChance(home.attack() * homeFf,
                    away.defense() * awayFf, true) * tempoFactor;
            double awayChance = MatchMath.perMinuteScoreChance(away.attack() * awayFf,
                    home.defense() * homeFf, false) * tempoFactor;

            MatchupModifiers.ChanceMults chanceMults = MatchupModifiers.chanceMults(home, away);
            homeChance *= chanceMults.home() * weatherImpact.homeChanceMult();
            awayChance *= chanceMults.away() * weatherImpact.awayChanceMult();

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
                EventType type = rng.nextDouble() < MatchMath.RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, true));
            }
            if (rng.nextDouble() < awayCardChance) {
                EventType type = rng.nextDouble() < MatchMath.RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, false));
            }

            // Marks the extension into extra time. Consumes zero RNG (plain arithmetic on the
            // score already drawn this minute) so it can never perturb the draw order. Only
            // reachable when the caller asked for finalMinute > MINUTES (i.e. via the 5-arg
            // overload with EXTRA_TIME_END) — the 4-arg overload always passes MINUTES, so this
            // block never executes for a league match.
            if (minute == MatchMath.MINUTES && finalMinute > MatchMath.MINUTES) {
                events.add(new SimulatedEvent(MatchMath.MINUTES, EventType.EXTRA_TIME_START, true));
            }
        }
        return new SimulatedMatch(homeGoals, awayGoals, List.copyOf(events));
    }
}
