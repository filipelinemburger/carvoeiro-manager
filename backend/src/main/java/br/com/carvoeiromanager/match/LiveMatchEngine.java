package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Deterministic, substitution-aware live-match engine. A pure function of
 * {@link LiveMatchConfig}: identical {@code (seed, subSchedule)} yields an identical timeline —
 * including AI-manager tactics reactions and AI bench substitutions, both derived purely from
 * the config with zero RNG. Kept fully separate from {@link MatchSimulationService} so the
 * instant engine (used for all AI/other-club fixtures) stays byte-for-byte unchanged.
 */
@Service
public class LiveMatchEngine {

    private static final double BASE_CARD_RATE = 0.02;
    private static final double RED_SHARE = 0.06;
    private static final double CHANCE_RATE = 0.12;   // flavor near-miss/save rate per side per minute
    private static final double HOBBLE_MULT = 0.5;    // an injured player who plays on contributes at half
    private static final double ASSIST_CHANCE = 0.65;
    private static final double BASE_FOUL_RATE = 0.13;   // ~11-12 fouls/team/90min at aggression 1.0

    private final TeamStrengthCalculator teamStrengthCalculator;
    private final FitnessCalculator fitnessCalculator;
    private final StaffEffectsService staffEffectsService;

    public LiveMatchEngine(TeamStrengthCalculator teamStrengthCalculator, FitnessCalculator fitnessCalculator,
                           StaffEffectsService staffEffectsService) {
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.fitnessCalculator = fitnessCalculator;
        this.staffEffectsService = staffEffectsService;
    }

    /** Mutable on-pitch bookkeeping for one side. */
    private static final class Side {
        final boolean home;
        final Map<UUID, Player> all = new HashMap<>();     // XI + bench, by id
        final List<UUID> onPitch = new ArrayList<>();      // current 10/11 on the field
        final Map<UUID, Integer> entryMinute = new HashMap<>();
        final Map<UUID, Integer> exitMinute = new HashMap<>();
        final Map<UUID, Boolean> hobbled = new HashMap<>();
        final Set<UUID> booked = new LinkedHashSet<>();    // players shown a yellow this match
        boolean dirty = true;
        TacticalProfile currentProfile;                    // updated by tactics changes mid-match
        boolean redCarded = false;                         // true once this side loses a player
        AiManagerBrain.Posture posture = AiManagerBrain.Posture.NORMAL; // AI-manager state (AI side only)
        int aiSubsMade = 0;                                // engine-made AI subs this match
        int aiFatigueSubsMade = 0;
        boolean aiPostureSubMade = false;

        Side(boolean home) { this.home = home; }
    }

    public LiveTimeline simulate(LiveMatchConfig cfg) {
        Random rng = new Random(cfg.seed());
        Random attrRng = new Random(cfg.seed() ^ 0x9E3779B97F4A7C15L);
        Random injuryRng = new Random(cfg.seed() ^ 0x1A70F1E5L);
        Random assistRng = new Random(cfg.seed() ^ 0x5A551571L);
        Random foulRng = new Random(cfg.seed() ^ 0xF0011E5L);

        Side home = buildSide(true, cfg.homeXi(), cfg.homeBench());
        Side away = buildSide(false, cfg.awayXi(), cfg.awayBench());

        // Resolved once per side: the multiplier depends only on the side's club and whether it is
        // AI- or user-managed, neither of which changes mid-match, so per-minute recomputation would
        // be a redundant, uncached repository query per player per minute (see resolveInjuryRiskMultiplier).
        double homeInjuryRisk = resolveInjuryRiskMultiplier(home, cfg);
        double awayInjuryRisk = resolveInjuryRiskMultiplier(away, cfg);

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

        for (int minute = 1; minute <= MatchMath.MINUTES; minute++) {
            applyTacticChanges(cfg, minute, home, events);
            applyTacticChanges(cfg, minute, away, events);
            applySubs(cfg, minute, home, events);
            applySubs(cfg, minute, away, events);
            if (aiSide != null) {
                int scoreDiff = aiSide.home ? homeGoals - awayGoals : awayGoals - homeGoals;
                reactAiManager(minute, aiSide, aiIdentity, scoreDiff, events);
                maybeAiSubstitution(minute, aiSide, cfg, events);
            }
            if (home.dirty) { homeInput = strength(home, home.currentProfile, cfg); home.dirty = false; }
            if (away.dirty) { awayInput = strength(away, away.currentProfile, cfg); away.dirty = false; }

            double tempo = (homeInput.tempo() + awayInput.tempo()) / 2.0;
            double homeFf = MatchMath.fitnessFactor(homeInput.fitnessDecay(), minute);
            double awayFf = MatchMath.fitnessFactor(awayInput.fitnessDecay(), minute);

            double homeChance = MatchMath.perMinuteScoreChance(homeInput.attack() * homeFf,
                    awayInput.defense() * awayFf, true) * tempo;
            double awayChance = MatchMath.perMinuteScoreChance(awayInput.attack() * awayFf,
                    homeInput.defense() * homeFf, false) * tempo;

            MatchupModifiers.ChanceMults chanceMults = MatchupModifiers.chanceMults(homeInput, awayInput);
            homeChance *= chanceMults.home();
            awayChance *= chanceMults.away();

            if (rng.nextDouble() < homeChance) {
                if (resolveScoringEvent(minute, home, homeInput, rng, attrRng, assistRng, events)) homeGoals++;
            }
            if (rng.nextDouble() < awayChance) {
                if (resolveScoringEvent(minute, away, awayInput, rng, attrRng, assistRng, events)) awayGoals++;
            }
            maybeCard(minute, home, homeInput, rng, attrRng, events);
            maybeCard(minute, away, awayInput, rng, attrRng, events);
            maybeFoul(minute, home, homeInput, foulRng, events);
            maybeFoul(minute, away, awayInput, foulRng, events);

            if (rng.nextDouble() < CHANCE_RATE && !home.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, true,
                        pickByWeight(home, LiveMatchEngine::goalWeight, attrRng), null, null, null));
            }
            if (rng.nextDouble() < CHANCE_RATE && !away.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, false,
                        pickByWeight(away, LiveMatchEngine::goalWeight, attrRng), null, null, null));
            }

            maybeInjury(minute, home, cfg, homeInjuryRisk, injuryRng, events, injuries);
            maybeInjury(minute, away, cfg, awayInjuryRisk, injuryRng, events, injuries);

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
            s.exitMinute.putIfAbsent(id, MatchMath.MINUTES);
        }
    }

    private void putMinutes(Side s, Map<UUID, Integer> out) {
        for (UUID id : s.entryMinute.keySet()) {
            int played = Math.max(0, s.exitMinute.getOrDefault(id, MatchMath.MINUTES) - s.entryMinute.get(id));
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
            // TimedTactics does not carry gameStyle (Task 23), so keep the old profile's style
            // instead of resetting it when a mid-match mentality/pressing/formation change lands.
            side.currentProfile = new TacticalProfile(
                    t.mentality(), old.gameStyle(), t.formation(), t.pressing(), t.marking(),
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

    /**
     * AI bench decision, evaluated once per minute right after the posture evaluation (so a
     * posture escalation and its personnel change land in the same minute). Deterministic and
     * RNG-free: reads only pitch/bench/fitness state, so re-simulations replay identical subs.
     * Applies at most one substitution per minute with the same bookkeeping as
     * {@link #applySubs}; an empty bench (pre-feature saves) means no decisions, ever.
     */
    private void maybeAiSubstitution(int minute, Side ai, LiveMatchConfig cfg,
                                     List<LiveTimelineEvent> events) {
        List<Player> benchAvailable = (ai.home ? cfg.homeBench() : cfg.awayBench()).stream()
                .filter(p -> !ai.entryMinute.containsKey(p.getId()))
                .toList();
        if (benchAvailable.isEmpty()) return;

        List<Player> onPitch = ai.onPitch.stream().map(ai.all::get).toList();
        Set<UUID> hobbledIds = new LinkedHashSet<>();
        for (UUID id : ai.onPitch) {
            if (Boolean.TRUE.equals(ai.hobbled.get(id))) hobbledIds.add(id);
        }

        Optional<AiSubstitutionBrain.SubDecision> decision = AiSubstitutionBrain.decide(
                minute, ai.posture, ai.aiPostureSubMade, ai.aiFatigueSubsMade, ai.aiSubsMade,
                onPitch, hobbledIds, benchAvailable, cfg.fitness());
        if (decision.isEmpty()) return;

        AiSubstitutionBrain.SubDecision d = decision.get();
        int idx = ai.onPitch.indexOf(d.outId());
        ai.onPitch.set(idx, d.inId());
        ai.exitMinute.put(d.outId(), minute);
        ai.entryMinute.put(d.inId(), minute);
        ai.dirty = true;
        ai.aiSubsMade++;
        switch (d.kind()) {
            case POSTURE -> ai.aiPostureSubMade = true;
            case FATIGUE -> ai.aiFatigueSubsMade++;
            case INJURY -> { }
        }
        events.add(new LiveTimelineEvent(minute, EventType.SUBSTITUTION, ai.home,
                d.outId(), d.inId(), null, null));
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
            boolean secondYellow = t == EventType.YELLOW_CARD && !side.booked.add(booked);
            events.add(new LiveTimelineEvent(minute, t, side.home, booked, null, null, null));
            if (secondYellow) {
                events.add(new LiveTimelineEvent(minute, EventType.RED_CARD, side.home, booked, null, null,
                        LiveTimelineEvent.SECOND_YELLOW_DETAIL));
            }
            if (t == EventType.RED_CARD || secondYellow) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.redCarded = true;
                side.dirty = true;
            }
        }
    }

    /**
     * Silent foul simulation: a per-minute per-side chance, scaled by aggression exactly like
     * {@link #maybeCard}, drawn entirely from the dedicated foul stream (chance roll AND
     * attribution pick both come from {@code foulRng}) so the match/attribution/injury streams
     * are untouched. FOUL events are never rendered in the live feed (filtered in
     * {@code LiveMatchService.toState}) and never persisted to match_events — they exist only so
     * {@code LiveMatchService.finish()} can count them into fixture_appearance.fouls.
     */
    private void maybeFoul(int minute, Side side, TeamMatchInput input, Random foulRng,
                          List<LiveTimelineEvent> events) {
        if (foulRng.nextDouble() < BASE_FOUL_RATE * input.aggression() && !side.onPitch.isEmpty()) {
            UUID fouler = pickByWeight(side, LiveMatchEngine::foulWeight, foulRng);
            events.add(new LiveTimelineEvent(minute, EventType.FOUL, side.home, fouler, null, null, null));
        }
    }

    /**
     * Weighted random pick over the on-pitch players EXCLUDING one id (keeps an assister
     * distinct from the scorer). Returns null if no eligible teammate remains on the pitch
     * (e.g. a heavily red-carded side) — the goal is simply left unassisted in that case.
     */
    private UUID pickByWeightExcluding(Side side, UUID exclude, ToIntFunction<Player> weight, Random rng) {
        List<UUID> pool = side.onPitch.stream().filter(id -> !id.equals(exclude)).toList();
        if (pool.isEmpty()) {
            return null;
        }
        int totalWeight = pool.stream().mapToInt(id -> Math.max(0, weight.applyAsInt(side.all.get(id)))).sum();
        if (totalWeight <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int pick = rng.nextInt(totalWeight);
        int acc = 0;
        for (UUID id : pool) {
            acc += Math.max(0, weight.applyAsInt(side.all.get(id)));
            if (pick < acc) return id;
        }
        return pool.get(pool.size() - 1);
    }

    /** Creative-passing composite for assist attribution: playmakers and wide players lead. */
    private static int assistWeight(Player p) {
        return p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                + p.attribute(Attribute.CRUZAMENTO) + 10;
    }

    /** Foul propensity composite: aggressive, tackle-heavy players commit more fouls. */
    private static int foulWeight(Player p) {
        return p.attribute(Attribute.AGRESSIVIDADE) + p.attribute(Attribute.DESARME) + 10;
    }

    /**
     * Resolves one scoring chance: open play or a distinct set piece. Returns true when a goal
     * was actually scored — a missed penalty emits PENALTY_MISSED and returns false. RNG use:
     * the set-piece?/subtype/conversion rolls draw from the match stream; scorer picks draw from
     * the attribution stream; the open-play assist chance+pick draws from the dedicated assist
     * stream, never disturbing the match/attribution/injury streams. For CORNER_GOAL the corner
     * taker rides in {@code playerInId} so commentary can name taker and scorer, and — per the
     * player-stats feature — that taker also counts as the assist at persistence time.
     */
    private boolean resolveScoringEvent(int minute, Side side, TeamMatchInput input, Random rng,
                                        Random attrRng, Random assistRng, List<LiveTimelineEvent> events) {
        if (rng.nextDouble() >= SetPieces.share(input.cornerThreat(), input.freeKickThreat())) {
            UUID scorer = pickByWeight(side, LiveMatchEngine::goalWeight, attrRng);
            UUID assister = assistRng.nextDouble() < ASSIST_CHANCE
                    ? pickByWeightExcluding(side, scorer, LiveMatchEngine::assistWeight, assistRng)
                    : null;
            events.add(new LiveTimelineEvent(minute, EventType.GOAL, side.home, scorer, assister, null, null));
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

    /**
     * Resolves this side's injury-risk multiplier once, up front, instead of once per player per
     * minute. The multiplier depends only on the side's club and whether it is AI- or user-managed
     * (side.home == Boolean.TRUE.equals(cfg.aiHome()) means this side is the AI-controlled one —
     * LiveMatchService always passes aiHome = !managedIsHome, see LiveMatchService.java:397) —
     * neither changes over the course of a match, so a single lookup per side is representative for
     * every player on that side, on the pitch or not, all match long.
     */
    private double resolveInjuryRiskMultiplier(Side side, LiveMatchConfig cfg) {
        boolean sideManaged = side.home != Boolean.TRUE.equals(cfg.aiHome());
        UUID clubId = side.all.values().iterator().next().getClubId();
        return staffEffectsService.injuryRiskMultiplierFor(clubId, sideManaged);
    }

    private void maybeInjury(int minute, Side side, LiveMatchConfig cfg, double riskMultiplier, Random injuryRng,
                             List<LiveTimelineEvent> events, List<TimedInjury> injuries) {
        if (side.onPitch.isEmpty()) return;
        // Aggregate the per-player match injury chance into a per-minute team chance, preserving the
        // low-fitness/age bias when picking who gets hurt.
        double teamChance = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            teamChance += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge())
                    * riskMultiplier;
        }
        teamChance /= MatchMath.MINUTES;
        if (injuryRng.nextDouble() >= teamChance) return;

        UUID victim = pickInjured(side, cfg, injuryRng, riskMultiplier);
        if (Boolean.TRUE.equals(side.hobbled.get(victim))) return; // already carrying a knock
        InjurySeverity severity = InjurySeverity.roll(injuryRng);
        int layoff = severity.layoffMatches(injuryRng);
        side.hobbled.put(victim, true);
        side.dirty = true;
        injuries.add(new TimedInjury(victim, side.home, minute, severity, layoff));
        events.add(new LiveTimelineEvent(minute, EventType.INJURY, side.home, victim, null, severity.name(), null));
    }

    private UUID pickInjured(Side side, LiveMatchConfig cfg, Random rng, double riskMultiplier) {
        double total = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            total += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge()) * riskMultiplier;
        }
        double pick = rng.nextDouble() * total;
        double acc = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            acc += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge()) * riskMultiplier;
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

    private static boolean isGoal(EventType t) {
        return t.isGoal();
    }
}
