package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Deterministic, substitution-aware live-match engine. A pure function of
 * {@link LiveMatchConfig}: identical {@code (seed, subSchedule)} yields an identical timeline.
 * Kept fully separate from {@link MatchSimulationService} so the instant engine (used for all
 * AI/other-club fixtures) stays byte-for-byte unchanged.
 */
@Service
public class LiveMatchEngine {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;
    private static final double FITNESS_FLOOR = 0.75;
    private static final double SET_PIECE_SHARE = 0.18;
    private static final double BASE_CARD_RATE = 0.02;
    private static final double RED_SHARE = 0.06;
    private static final double CHANCE_RATE = 0.12;   // flavor near-miss/save rate per side per minute
    private static final double HOBBLE_MULT = 0.5;    // an injured player who plays on contributes at half

    private final TeamStrengthCalculator teamStrengthCalculator;
    private final FitnessCalculator fitnessCalculator;

    public LiveMatchEngine(TeamStrengthCalculator teamStrengthCalculator, FitnessCalculator fitnessCalculator) {
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.fitnessCalculator = fitnessCalculator;
    }

    /** Mutable on-pitch bookkeeping for one side. */
    private static final class Side {
        final boolean home;
        final Map<UUID, Player> all = new HashMap<>();     // XI + bench, by id
        final List<UUID> onPitch = new ArrayList<>();      // current 10/11 on the field
        final Map<UUID, Integer> entryMinute = new HashMap<>();
        final Map<UUID, Integer> exitMinute = new HashMap<>();
        final Map<UUID, Boolean> hobbled = new HashMap<>();
        boolean dirty = true;

        Side(boolean home) { this.home = home; }
    }

    public LiveTimeline simulate(LiveMatchConfig cfg) {
        Random rng = new Random(cfg.seed());
        Random attrRng = new Random(cfg.seed() ^ 0x5DEECE66DL);
        Random injuryRng = new Random(cfg.seed() ^ 0x1A70F1E5L);

        Side home = buildSide(true, cfg.homeXi(), cfg.homeBench());
        Side away = buildSide(false, cfg.awayXi(), cfg.awayBench());

        List<LiveTimelineEvent> events = new ArrayList<>();
        List<TimedInjury> injuries = new ArrayList<>();

        TeamMatchInput homeInput = strength(home, cfg.homeProfile(), cfg);
        home.dirty = false;
        TeamMatchInput awayInput = strength(away, cfg.awayProfile(), cfg);
        away.dirty = false;

        for (int minute = 1; minute <= MINUTES; minute++) {
            applySubs(cfg, minute, home, events);
            applySubs(cfg, minute, away, events);
            if (home.dirty) { homeInput = strength(home, cfg.homeProfile(), cfg); home.dirty = false; }
            if (away.dirty) { awayInput = strength(away, cfg.awayProfile(), cfg); away.dirty = false; }

            double tempo = (homeInput.tempo() + awayInput.tempo()) / 2.0;
            double homeFf = fitnessFactor(homeInput.fitnessDecay(), minute);
            double awayFf = fitnessFactor(awayInput.fitnessDecay(), minute);

            double homeChance = perMinuteScoreChance(homeInput.attack() * homeFf + HOME_ADVANTAGE,
                    awayInput.defense() * awayFf) * tempo;
            double awayChance = perMinuteScoreChance(awayInput.attack() * awayFf,
                    homeInput.defense() * homeFf + HOME_ADVANTAGE) * tempo;

            if (rng.nextDouble() < homeChance) {
                EventType t = rng.nextDouble() < setPiece(homeInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, home, cfg.homeProfile(), t, attrRng));
            }
            if (rng.nextDouble() < awayChance) {
                EventType t = rng.nextDouble() < setPiece(awayInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, away, cfg.awayProfile(), t, attrRng));
            }
            maybeCard(minute, home, homeInput, rng, attrRng, events);
            maybeCard(minute, away, awayInput, rng, attrRng, events);

            if (rng.nextDouble() < CHANCE_RATE && !home.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, true,
                        pickWeighted(home, Player::getFinishing, attrRng), null, null));
            }
            if (rng.nextDouble() < CHANCE_RATE && !away.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, false,
                        pickWeighted(away, Player::getFinishing, attrRng), null, null));
            }

            maybeInjury(minute, home, cfg, injuryRng, events, injuries);
            maybeInjury(minute, away, cfg, injuryRng, events, injuries);
        }

        finalizeMinutes(home);
        finalizeMinutes(away);
        events.sort(Comparator.comparingInt(LiveTimelineEvent::minute));

        int hg = (int) events.stream().filter(e -> e.home() && isGoal(e.type())).count();
        int ag = (int) events.stream().filter(e -> !e.home() && isGoal(e.type())).count();

        Map<UUID, Integer> minutes = new LinkedHashMap<>();
        putMinutes(home, minutes);
        putMinutes(away, minutes);
        return new LiveTimeline(hg, ag, List.copyOf(events), Map.copyOf(minutes), List.copyOf(injuries));
    }

    // ---- side setup & minutes ----

    private Side buildSide(boolean home, List<Player> xi, List<Player> bench) {
        Side s = new Side(home);
        xi.forEach(p -> { s.all.put(p.getId(), p); s.onPitch.add(p.getId()); s.entryMinute.put(p.getId(), 0); });
        bench.forEach(p -> s.all.put(p.getId(), p));
        return s;
    }

    private void finalizeMinutes(Side s) {
        for (UUID id : s.entryMinute.keySet()) {
            s.exitMinute.putIfAbsent(id, MINUTES);
        }
    }

    private void putMinutes(Side s, Map<UUID, Integer> out) {
        for (UUID id : s.entryMinute.keySet()) {
            int played = Math.max(0, s.exitMinute.getOrDefault(id, MINUTES) - s.entryMinute.get(id));
            out.put(id, played);
        }
    }

    // ---- substitutions ----

    private void applySubs(LiveMatchConfig cfg, int minute, Side side, List<LiveTimelineEvent> events) {
        for (LiveSub sub : cfg.subSchedule()) {
            if (sub.minute() != minute || sub.home() != side.home) continue;
            if (!side.onPitch.contains(sub.outId()) || !side.all.containsKey(sub.inId())) continue;
            int idx = side.onPitch.indexOf(sub.outId());
            side.onPitch.set(idx, sub.inId());
            side.exitMinute.put(sub.outId(), minute);
            side.entryMinute.put(sub.inId(), minute);
            side.dirty = true;
            events.add(new LiveTimelineEvent(minute, EventType.SUBSTITUTION, side.home,
                    sub.outId(), sub.inId(), null));
        }
    }

    // ---- strength (recomputed on change), hobble x0.5, red-card = fewer players ----

    private TeamMatchInput strength(Side side, TacticalProfile profile, LiveMatchConfig cfg) {
        List<Player> xi = side.onPitch.stream().map(side.all::get).toList();
        Map<UUID, Double> mult = new HashMap<>();
        for (UUID id : side.onPitch) {
            int fit = cfg.fitness().getOrDefault(id, 100);
            double m = fitnessCalculator.fitnessMult(fit);
            if (Boolean.TRUE.equals(side.hobbled.get(id))) m *= HOBBLE_MULT;
            mult.put(id, m);
        }
        return teamStrengthCalculator.compute(xi, profile, mult);
    }

    // ---- cards, goals, injuries, attribution ----

    private void maybeCard(int minute, Side side, TeamMatchInput input, Random rng, Random attrRng,
                           List<LiveTimelineEvent> events) {
        if (rng.nextDouble() < BASE_CARD_RATE * input.aggression() && !side.onPitch.isEmpty()) {
            EventType t = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
            UUID booked = pickWeighted(side, Player::getTackling, attrRng);
            events.add(new LiveTimelineEvent(minute, t, side.home, booked, null, null));
            if (t == EventType.RED_CARD) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.dirty = true;
            }
        }
    }

    private LiveTimelineEvent goal(int minute, Side side, TacticalProfile profile, EventType type, Random attrRng) {
        UUID scorer = type == EventType.SET_PIECE_GOAL ? setPieceTaker(side, profile, attrRng)
                : pickWeighted(side, Player::getFinishing, attrRng);
        return new LiveTimelineEvent(minute, type, side.home, scorer, null, null);
    }

    private void maybeInjury(int minute, Side side, LiveMatchConfig cfg, Random injuryRng,
                             List<LiveTimelineEvent> events, List<TimedInjury> injuries) {
        if (side.onPitch.isEmpty()) return;
        // Aggregate the per-player match injury chance into a per-minute team chance, preserving the
        // low-fitness/age bias when picking who gets hurt.
        double teamChance = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            teamChance += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge());
        }
        teamChance /= MINUTES;
        if (injuryRng.nextDouble() >= teamChance) return;

        UUID victim = pickInjured(side, cfg, injuryRng);
        if (Boolean.TRUE.equals(side.hobbled.get(victim))) return; // already carrying a knock
        InjurySeverity severity = InjurySeverity.roll(injuryRng);
        int layoff = severity.layoffMatches(injuryRng);
        side.hobbled.put(victim, true);
        side.dirty = true;
        injuries.add(new TimedInjury(victim, side.home, minute, severity, layoff));
        events.add(new LiveTimelineEvent(minute, EventType.INJURY, side.home, victim, null, severity.name()));
    }

    private UUID pickInjured(Side side, LiveMatchConfig cfg, Random rng) {
        double total = 0.0;
        for (UUID id : side.onPitch) {
            total += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
        }
        double pick = rng.nextDouble() * total;
        double acc = 0.0;
        for (UUID id : side.onPitch) {
            acc += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
            if (pick < acc) return id;
        }
        return side.onPitch.get(side.onPitch.size() - 1);
    }

    private UUID setPieceTaker(Side side, TacticalProfile profile, Random rng) {
        UUID takerId = profile.penaltyTakerId() != null ? profile.penaltyTakerId() : profile.freekickTakerId();
        if (takerId != null && side.onPitch.contains(takerId)) return takerId;
        return pickWeighted(side, Player::getFinishing, rng);
    }

    private UUID pickWeighted(Side side, ToIntFunction<Player> attribute, Random rng) {
        List<UUID> xi = side.onPitch;
        int totalWeight = xi.stream().mapToInt(id -> attribute.applyAsInt(side.all.get(id)) + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int acc = 0;
        for (UUID id : xi) {
            acc += attribute.applyAsInt(side.all.get(id)) + 1;
            if (pick < acc) return id;
        }
        return xi.get(xi.size() - 1);
    }

    // ---- math mirrored from MatchSimulationService ----

    private static boolean isGoal(EventType t) {
        return t == EventType.GOAL || t == EventType.SET_PIECE_GOAL;
    }

    private double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    private double setPiece(TeamMatchInput team) {
        return Math.max(0.0, Math.min(0.6, SET_PIECE_SHARE * (0.5 + team.setPieceThreat())));
    }

    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
