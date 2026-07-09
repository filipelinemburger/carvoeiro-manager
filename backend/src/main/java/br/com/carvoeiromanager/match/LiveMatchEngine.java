package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
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
        TacticalProfile currentProfile;                    // updated by tactics changes mid-match
        boolean redCarded = false;                         // true once this side loses a player
        AiManagerBrain.Posture posture = AiManagerBrain.Posture.NORMAL; // AI-manager state (AI side only)

        Side(boolean home) { this.home = home; }
    }

    public LiveTimeline simulate(LiveMatchConfig cfg) {
        Random rng = new Random(cfg.seed());
        Random attrRng = new Random(cfg.seed() ^ 0x9E3779B97F4A7C15L);
        Random injuryRng = new Random(cfg.seed() ^ 0x1A70F1E5L);

        Side home = buildSide(true, cfg.homeXi(), cfg.homeBench());
        Side away = buildSide(false, cfg.awayXi(), cfg.awayBench());

        // Initialise each side's active profile from config; tactics changes update these mid-match.
        home.currentProfile = cfg.homeProfile();
        away.currentProfile = cfg.awayProfile();

        // AI-manager reactions: which side (if any) re-evaluates its posture each second-half minute.
        Side aiSide = cfg.aiHome() == null ? null : (cfg.aiHome() ? home : away);
        TacticalProfile aiIdentity = cfg.aiHome() == null ? null
                : (cfg.aiHome() ? cfg.homeProfile() : cfg.awayProfile());

        List<LiveTimelineEvent> events = new ArrayList<>();
        List<TimedInjury> injuries = new ArrayList<>();

        TeamMatchInput homeInput = strength(home, home.currentProfile, cfg);
        home.dirty = false;
        TeamMatchInput awayInput = strength(away, away.currentProfile, cfg);
        away.dirty = false;

        int homeGoals = 0;
        int awayGoals = 0;

        for (int minute = 1; minute <= MINUTES; minute++) {
            applyTacticChanges(cfg, minute, home, events);
            applyTacticChanges(cfg, minute, away, events);
            applySubs(cfg, minute, home, events);
            applySubs(cfg, minute, away, events);
            if (aiSide != null) {
                int scoreDiff = aiSide.home ? homeGoals - awayGoals : awayGoals - homeGoals;
                reactAiManager(minute, aiSide, aiIdentity, scoreDiff, events);
            }
            if (home.dirty) { homeInput = strength(home, home.currentProfile, cfg); home.dirty = false; }
            if (away.dirty) { awayInput = strength(away, away.currentProfile, cfg); away.dirty = false; }

            double tempo = (homeInput.tempo() + awayInput.tempo()) / 2.0;
            double homeFf = fitnessFactor(homeInput.fitnessDecay(), minute);
            double awayFf = fitnessFactor(awayInput.fitnessDecay(), minute);

            double homeChance = perMinuteScoreChance(homeInput.attack() * homeFf + HOME_ADVANTAGE,
                    awayInput.defense() * awayFf) * tempo;
            double awayChance = perMinuteScoreChance(awayInput.attack() * awayFf,
                    homeInput.defense() * homeFf + HOME_ADVANTAGE) * tempo;

            if (rng.nextDouble() < homeChance) {
                if (resolveScoringEvent(minute, home, homeInput, rng, attrRng, events)) homeGoals++;
            }
            if (rng.nextDouble() < awayChance) {
                if (resolveScoringEvent(minute, away, awayInput, rng, attrRng, events)) awayGoals++;
            }
            maybeCard(minute, home, homeInput, rng, attrRng, events);
            maybeCard(minute, away, awayInput, rng, attrRng, events);

            if (rng.nextDouble() < CHANCE_RATE && !home.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, true,
                        pickByWeight(home, LiveMatchEngine::goalWeight, attrRng), null, null, null));
            }
            if (rng.nextDouble() < CHANCE_RATE && !away.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, false,
                        pickByWeight(away, LiveMatchEngine::goalWeight, attrRng), null, null, null));
            }

            maybeInjury(minute, home, cfg, injuryRng, events, injuries);
            maybeInjury(minute, away, cfg, injuryRng, events, injuries);

            // Emit a half-time marker at the END of minute 45 (after all RNG draws for this minute).
            // Consumes zero RNG — the marker sorts last among minute-45 events when the list is
            // stable-sorted by minute, ensuring determinism is byte-identical to before.
            if (minute == 45) {
                UUID carrier = home.onPitch.isEmpty() ? null : home.onPitch.get(0);
                events.add(new LiveTimelineEvent(45, EventType.HALF_TIME, true, carrier, null, null, null));
            }
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

    // ---- tactics changes ----

    /**
     * Applies any {@link TimedTactics} scheduled for this minute and side.
     * Builds a new {@link TacticalProfile} reusing the existing per-player roles and set-piece takers
     * (those are fixed for the match) but replacing mentality/pressing/marking/formation.
     * Sets {@code side.dirty} so strength is recomputed before this minute's play — exactly like subs.
     * Does NOT draw from any RNG, preserving byte-identical match/attr/injury streams when the
     * tactics schedule is empty.
     *
     * <p>The TACTICS_CHANGE event carries {@code side.onPitch.get(0)} as a harmless playerId carrier
     * so downstream name-lookups in {@link LiveMatchService#toState} (which call {@code nameOf(byId, e.playerId())})
     * do not NPE — the first on-pitch player is always present and the value is never displayed for
     * this event type.
     */
    private void applyTacticChanges(LiveMatchConfig cfg, int minute, Side side, List<LiveTimelineEvent> events) {
        for (TimedTactics t : cfg.tacticsSchedule()) {
            if (t.minute() != minute || t.home() != side.home) continue;
            TacticalProfile old = side.currentProfile;
            side.currentProfile = new TacticalProfile(
                    t.mentality(), t.formation(), t.pressing(), t.marking(),
                    old.roles(), old.penaltyTakerId(), old.freekickTakerId(), old.cornerTakerId());
            side.dirty = true;
            // Use the first on-pitch player as a sentinel playerId — never NPE, never displayed.
            UUID carrier = side.onPitch.isEmpty() ? null : side.onPitch.get(0);
            events.add(new LiveTimelineEvent(minute, EventType.TACTICS_CHANGE, side.home, carrier, null, null, null));
        }
    }

    /**
     * Score-reactive AI-manager evaluation, run every second-half minute for the AI side. The
     * brain is memoryless and RNG-free, so this preserves the timeline's determinism and consumes
     * zero RNG; a change only fires when the decided posture differs from the current one — i.e.
     * at threshold minutes and right after goals/red cards. {@code scoreDiff} reflects events
     * strictly before this minute (this hook runs before the minute's goals/cards are drawn).
     * Same carrier-playerId convention as {@link #applyTacticChanges}.
     */
    private void reactAiManager(int minute, Side ai, TacticalProfile identity, int scoreDiff,
                                List<LiveTimelineEvent> events) {
        AiManagerBrain.Posture next =
                AiManagerBrain.decide(identity.mentality(), scoreDiff, minute, ai.redCarded);
        if (next == ai.posture) return;
        ai.posture = next;
        ai.currentProfile = AiManagerBrain.profileFor(next, identity);
        ai.dirty = true;
        UUID carrier = ai.onPitch.isEmpty() ? null : ai.onPitch.get(0);
        events.add(new LiveTimelineEvent(minute, EventType.TACTICS_CHANGE, ai.home, carrier,
                null, null, next.name()));
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
                    sub.outId(), sub.inId(), null, null));
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
            // Fouls skew toward aggressive tacklers; the +1 keeps everyone eligible (unchanged formula).
            UUID booked = pickByWeight(side, p -> p.getTackling() + 1, attrRng);
            events.add(new LiveTimelineEvent(minute, t, side.home, booked, null, null, null));
            if (t == EventType.RED_CARD) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.redCarded = true;
                side.dirty = true;
            }
        }
    }

    /**
     * Resolves one scoring chance: open play or a distinct set piece. Returns true when a goal
     * was actually scored — a missed penalty emits PENALTY_MISSED and returns false. RNG use:
     * the set-piece?/subtype/conversion rolls draw from the match stream; scorer picks draw from
     * the attribution stream, mirroring the pre-split draw discipline. For CORNER_GOAL the
     * corner taker rides in {@code playerInId} so commentary can name taker and scorer.
     */
    private boolean resolveScoringEvent(int minute, Side side, TeamMatchInput input, Random rng,
                                        Random attrRng, List<LiveTimelineEvent> events) {
        if (rng.nextDouble() >= SetPieces.share(input.cornerThreat(), input.freeKickThreat())) {
            UUID scorer = pickByWeight(side, LiveMatchEngine::goalWeight, attrRng);
            events.add(new LiveTimelineEvent(minute, EventType.GOAL, side.home, scorer, null, null, null));
            return true;
        }
        List<Player> onPitch = side.onPitch.stream().map(side.all::get).toList();
        TacticalProfile profile = side.currentProfile;
        return switch (SetPieces.roll(input.cornerThreat(), input.freeKickThreat(), rng)) {
            case PENALTY -> {
                Player taker = SetPieces.takerOrFallback(onPitch, profile.penaltyTakerId(), Player::getFinishing);
                boolean converted = rng.nextDouble() < input.penaltyConversion();
                events.add(new LiveTimelineEvent(minute,
                        converted ? EventType.PENALTY_GOAL : EventType.PENALTY_MISSED,
                        side.home, taker.getId(), null, null, null));
                yield converted;
            }
            case FREE_KICK -> {
                Player taker = SetPieces.takerOrFallback(onPitch, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                events.add(new LiveTimelineEvent(minute, EventType.FREE_KICK_GOAL, side.home,
                        taker.getId(), null, null, null));
                yield true;
            }
            case CORNER -> {
                UUID scorer = pickByWeight(side, SetPieces::headerWeight, attrRng);
                Player taker = SetPieces.takerOrFallback(onPitch, profile.cornerTakerId(),
                        p -> p.attribute(Attribute.CRUZAMENTO));
                events.add(new LiveTimelineEvent(minute, EventType.CORNER_GOAL, side.home,
                        scorer, taker.getId(), null, null));
                yield true;
            }
        };
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
        events.add(new LiveTimelineEvent(minute, EventType.INJURY, side.home, victim, null, severity.name(), null));
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

    /** Weighted random pick over the on-pitch players. A player with weight 0 is never chosen. */
    private UUID pickByWeight(Side side, ToIntFunction<Player> weight, Random rng) {
        List<UUID> xi = side.onPitch;
        int totalWeight = xi.stream().mapToInt(id -> Math.max(0, weight.applyAsInt(side.all.get(id)))).sum();
        if (totalWeight <= 0) {
            // Degenerate case (e.g. only the keeper left on the pitch): fall back to a uniform pick.
            return xi.get(rng.nextInt(xi.size()));
        }
        int pick = rng.nextInt(totalWeight);
        int acc = 0;
        for (UUID id : xi) {
            acc += Math.max(0, weight.applyAsInt(side.all.get(id)));
            if (pick < acc) return id;
        }
        return xi.get(xi.size() - 1);
    }

    /**
     * How likely a player is to score a goal or create a chance: driven by position (attackers most,
     * midfielders and defenders progressively less, keepers never in open play) and by finishing.
     * Keepers get weight 0 so a goalkeeper does not score from open play. The {@code + 10} floor keeps
     * a poor finisher able to score occasionally while still trailing a clinical one.
     */
    private static int goalWeight(Player p) {
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 12;
            case MID -> 6;
            case DEF -> 2;
            case GK -> 0;
        };
        return positionFactor * (p.getFinishing() + 10);
    }

    // ---- math mirrored from MatchSimulationService ----

    private static boolean isGoal(EventType t) {
        return t.isGoal();
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
