package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.match.TimedTactics;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchEngineTest {

    private final br.com.carvoeiromanager.staff.StaffEffectsService staffEffectsService =
            Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class);
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staffEffectsService);

    {
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
        // Neutral coach multiplier: this mock has no stub without it, and Mockito's unstubbed
        // double default (0.0) would zero out attack/defense, breaking every golden signature below.
        Mockito.when(staffEffectsService.coachStrengthMultiplierFor(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1.0);
    }

    /** Reuse the same factory pattern as TeamStrengthCalculatorTest. */
    private static Player p(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all rated 70. */
    private static List<Player> buildXi() {
        return new ArrayList<>(List.of(
                p(Position.GK, 70),
                p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70),
                p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70),
                p(Position.ATT, 70), p(Position.ATT, 70)));
    }

    private static TacticalProfile defaultProfile(List<Player> xi) {
        // Legacy archetype + canonical focus + familiarity 2 — the regression contract that keeps
        // the fixed-seed golden signatures identical to the retired PlayerRole model.
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(xi);
        return new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());
    }

    /** A full-profile player with BOLA_PARADA 0 (so penaltyConversion is exactly 0.70) but
     *  otherwise decent all-round attributes, per the set-piece attribution test recipe. */
    private static Player fullProfilePlayer(Position pos) {
        Attributes attributes = Attributes.builder()
                .set(Attribute.VELOCIDADE, 70)
                .set(Attribute.FINALIZACAO, 70)
                .set(Attribute.PASSE, 70)
                .set(Attribute.DESARME, 70)
                .set(Attribute.REFLEXOS, pos == Position.GK ? 70 : 10)
                .set(Attribute.FOLEGO, 70)
                .set(Attribute.BOLA_PARADA, 0)
                .set(Attribute.CRUZAMENTO, 50)
                .set(Attribute.CABECEIO, 50)
                .build();
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA", attributes, 70);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all built via the full-profile factory above. */
    private static List<Player> fullProfileXi() {
        return new ArrayList<>(List.of(
                fullProfilePlayer(Position.GK),
                fullProfilePlayer(Position.DEF), fullProfilePlayer(Position.DEF),
                fullProfilePlayer(Position.DEF), fullProfilePlayer(Position.DEF),
                fullProfilePlayer(Position.MID), fullProfilePlayer(Position.MID),
                fullProfilePlayer(Position.MID), fullProfilePlayer(Position.MID),
                fullProfilePlayer(Position.ATT), fullProfilePlayer(Position.ATT)));
    }

    private static TacticalProfile profileWithTakers(List<Player> xi, UUID penaltyTakerId,
                                                      UUID freekickTakerId, UUID cornerTakerId) {
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(xi);
        return new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO, "4-4-2",
                Pressing.MEDIUM, Marking.ZONAL, roles, penaltyTakerId, freekickTakerId, cornerTakerId, Map.of(), Map.of());
    }

    private static Map<UUID, Integer> fullFitness(List<Player> homeXi, List<Player> awayXi) {
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
        awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
        return fitness;
    }

    // Fixed XI and bench so test assertions are stable across invocations
    private final List<Player> homeXi = buildXi();
    private final List<Player> awayXi = buildXi();
    private final List<Player> homeBench = List.of(
            p(Position.MID, 65),
            p(Position.ATT, 65),
            p(Position.DEF, 65));

    private LiveMatchConfig config(long seed, List<LiveSub> subs) {
        return config(seed, subs, List.of());
    }

    private LiveMatchConfig config(long seed, List<LiveSub> subs, List<TimedTactics> tactics) {
        return config(seed, subs, tactics, null);
    }

    private LiveMatchConfig config(long seed, List<LiveSub> subs, List<TimedTactics> tactics, Boolean aiHome) {
        // fitness map: every player (both XIs + home bench) at 100
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
        awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
        homeBench.forEach(pl -> fitness.put(pl.getId(), 100));

        TacticalProfile homeProfile = defaultProfile(homeXi);
        TacticalProfile awayProfile = defaultProfile(awayXi);

        return new LiveMatchConfig(seed, homeXi, awayXi, homeBench, List.of(),
                homeProfile, awayProfile, fitness, subs, tactics, aiHome, Weather.neutral(), false, null, null);
    }

    @Test
    void openPlayGoalsAreSometimesAssistedAndSometimesNot() {
        // Across many seeds, roughly ASSIST_CHANCE (~65%) of plain GOAL events should carry
        // a non-null playerInId (the assister). PENALTY_GOAL/FREE_KICK_GOAL never do (checked below).
        int assisted = 0;
        int total = 0;
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.GOAL) {
                    total++;
                    if (e.playerInId() != null) assisted++;
                }
            }
        }
        assertThat(total).isGreaterThan(50);
        double rate = (double) assisted / total;
        assertThat(rate).isBetween(0.5, 0.8);
    }

    @Test
    void anAssistNeverCreditsTheScorerThemself() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.GOAL && e.playerInId() != null) {
                    assertThat(e.playerInId()).isNotEqualTo(e.playerId());
                }
            }
        }
    }

    @Test
    void penaltiesAndFreeKicksAreNeverAssisted() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream()
                    .filter(e -> e.type() == EventType.PENALTY_GOAL || e.type() == EventType.FREE_KICK_GOAL)
                    .forEach(e -> assertThat(e.playerInId()).isNull());
        }
    }

    @Test
    void assistAndFoulOutcomesAreDeterministicForAFixedSeed() {
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
    }

    @Test
    void allMistoGoalAttributionMatchesTheGoldenBaseline() {
        // Byte-identity regression for AttackZone: the default profile is all-MISTO/all-EQUILIBRADO,
        // whose scorer/assist weights equal the pre-AttackZone formulas, so the goal scorer/assister
        // attribution for a fixed seed must match this golden signature captured from the neutral run.
        // A drift here means MISTO stopped being the neutral no-op (seeded results would shift).
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline t = engine.simulate(cfg);
        assertThat(goalSignature(t)).isEqualTo("42:H:GOAL:8:9|63:H:GOAL:5:2");
    }

    /**
     * A compact, UUID-independent signature of every goal event: {@code minute:side:type:scorerIdx:assisterIdx}
     * where the indices are the scorer/assister positions within their side's XI list (stable for a fixed
     * seed regardless of the random UUIDs), joined by {@code |}. {@code assisterIdx} is {@code -} when
     * the goal is unassisted.
     */
    private String goalSignature(LiveTimeline t) {
        return t.events().stream()
                .filter(e -> e.type().isGoal())
                .map(e -> {
                    List<Player> xi = e.home() ? homeXi : awayXi;
                    return e.minute() + ":" + (e.home() ? "H" : "A") + ":" + e.type().name()
                            + ":" + indexOf(xi, e.playerId()) + ":" + indexOf(xi, e.playerInId());
                })
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String indexOf(List<Player> xi, UUID id) {
        if (id == null) return "-";
        for (int i = 0; i < xi.size(); i++) {
            if (xi.get(i).getId().equals(id)) return Integer.toString(i);
        }
        return "?";
    }

    @Test
    void eventsAreByteIdenticalAfterTheStatsStreamWasAdded() {
        // Full-event-list byte-identity golden — unlike allMistoGoalAttributionMatchesTheGoldenBaseline
        // (goals only), this one covers every event type a new RNG stream could leak into: cards,
        // substitutions, fouls, tactics changes and chances, in addition to goals. Per Task 2 brief
        // step 6a, this was pinned on the engine BEFORE the statsRng stream existed, then re-confirmed
        // unchanged immediately after wiring it in. If this ever needs its golden value edited, that
        // means the stats stream is drawing from an existing RNG stream — find the leak, don't touch
        // the golden.
        List<LiveSub> subs = List.of(new LiveSub(60, true, homeXi.get(5).getId(), homeBench.get(0).getId()));
        List<TimedTactics> tactics = List.of(
                new TimedTactics(30, true, Mentality.ATTACKING, null, null, Pressing.HIGH, Marking.ZONAL, "4-3-3"));
        LiveMatchConfig cfg = config(555L, subs, tactics);
        LiveTimeline t = engine.simulate(cfg);

        List<Player> homePool = new ArrayList<>(homeXi);
        homePool.addAll(homeBench);

        assertThat(fullEventSignature(t, homePool, awayXi)).isEqualTo(
                "1:H:FOUL:7|1:A:CHANCE:5|4:H:YELLOW_CARD:9|4:H:CHANCE:1|5:H:FOUL:3|5:A:FOUL:10|6:H:FOUL:3"
                        + "|9:A:INJURY:0|10:A:YELLOW_CARD:10|11:H:FOUL:4|11:A:CHANCE:9|12:H:FOUL:7|12:H:CHANCE:7"
                        + "|13:A:FOUL:3|13:H:CHANCE:7|19:H:CHANCE:9|25:H:RED_CARD:8|25:A:CHANCE:10"
                        + "|30:H:TACTICS_CHANGE:0|30:A:FOUL:10|30:H:CHANCE:9|32:H:FOUL:0|33:A:FOUL:0"
                        + "|33:H:CHANCE:10|34:H:GOAL:10|34:A:FOUL:1|37:H:YELLOW_CARD:3|38:H:CHANCE:9|39:H:GOAL:10"
                        + "|41:H:FOUL:3|41:A:FOUL:7|43:A:YELLOW_CARD:5|43:H:FOUL:3|43:A:CHANCE:9|44:H:FOUL:2"
                        + "|45:A:GOAL:8|45:H:FOUL:7|45:H:HALF_TIME:0|46:A:FOUL:6|47:H:FOUL:0|47:A:FOUL:0"
                        + "|50:H:FOUL:2|54:A:FOUL:9|57:H:FOUL:9|58:A:CHANCE:8|60:H:SUBSTITUTION:5|62:H:FOUL:3"
                        + "|62:A:FOUL:6|63:A:CHANCE:5|64:H:FOUL:6|66:A:CHANCE:10|66:H:INJURY:2|68:H:FOUL:11"
                        + "|69:A:CHANCE:7|72:A:CHANCE:10|73:H:FOUL:1|78:H:FOUL:3|78:H:CHANCE:9|79:A:FOUL:3"
                        + "|86:A:FOUL:8|86:A:CHANCE:8|90:H:FOUL:10");
    }

    /**
     * A compact, UUID-independent signature of the FULL event list (every type, not just goals):
     * {@code minute:side:type:playerIdx} joined by {@code |}, where {@code playerIdx} is the
     * event's {@code playerId}'s position within its side's XI+bench pool (stable for a fixed seed
     * regardless of the random UUIDs). {@code -} means a null playerId, {@code ?} means not found
     * in the pool.
     */
    private String fullEventSignature(LiveTimeline t, List<Player> homePool, List<Player> awayPool) {
        return t.events().stream()
                .map(e -> {
                    List<Player> pool = e.home() ? homePool : awayPool;
                    return e.minute() + ":" + (e.home() ? "H" : "A") + ":" + e.type().name()
                            + ":" + indexOf(pool, e.playerId());
                })
                .collect(java.util.stream.Collectors.joining("|"));
    }

    @Test
    void foulsOccurAtARealisticPerTeamRateAndAreNeverAttributedToAnEmptyPitch() {
        // ~10-13 fouls per team per match; sample many seeds and check the per-seed average
        // lands in a broad realistic band (loose bounds — this is a calibration sanity check,
        // not an exact-value assertion).
        int totalHomeFouls = 0;
        int seeds = 150;
        for (long seed = 0; seed < seeds; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            List<LiveTimelineEvent> homeFouls = t.events().stream()
                    .filter(e -> e.type() == EventType.FOUL && e.home()).toList();
            totalHomeFouls += homeFouls.size();
            homeFouls.forEach(e -> assertThat(e.playerId()).isNotNull());
        }
        double avgPerTeamPerMatch = (double) totalHomeFouls / seeds;
        assertThat(avgPerTeamPerMatch).isBetween(5.0, 20.0);
    }

    @Test
    void foulsNeverAppearInTheGoalCountingOrCardStreams() {
        // FOUL must never satisfy isGoal() and must be a distinct type from cards, so downstream
        // consumers that don't explicitly handle it (match_events persistence, ratings) skip it safely.
        for (long seed = 0; seed < 50; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream().filter(e -> e.type() == EventType.FOUL)
                    .forEach(e -> assertThat(e.type().isGoal()).isFalse());
        }
    }

    @Test
    void sameInputsProduceIdenticalTimelines() {
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
        assertThat(a.homeGoals()).isEqualTo(b.homeGoals());
    }

    @Test
    void aStrongHeadCoachScoresMoreGoalsOverManySeeds() {
        // Guards the WIRING, not the arithmetic — TeamMatchInputCoachTest already covers the record
        // itself. Without this test, deleting the withCoachMultiplier call from the engine leaves
        // the whole backend suite green: every other simulation test stubs the multiplier to 1.0,
        // and these mocks are lenient (plain Mockito.mock, no MockitoExtension), so the now-unused
        // stub raises nothing. This is the only assertion that the feature reaches the pitch.
        //
        // Measured over many seeds rather than one. The multiplier shifts a per-minute scoring
        // probability, so on any single seed it usually flips no draw at all — a one-seed
        // assertion looks like a wiring failure when it is really just sampling noise.
        int neutralGoals = 0;
        for (long seed = 0; seed < 200; seed++) {
            neutralGoals += engine.simulate(config(seed, List.of())).homeGoals();
        }

        // Only the HOME side gets the good coach: a symmetric multiplier merely rescales
        // attack - opponentDefense and barely moves the goal model. Each test player carries its
        // own random clubId and Side.all is a HashMap of XI PLUS bench, so rather than guess which
        // player the engine samples, stub every home clubId — bench included. A specific stub beats
        // the any() stub from the initializer, leaving the away side neutral.
        Stream.concat(homeXi.stream(), homeBench.stream()).forEach(pl -> Mockito.when(
                staffEffectsService.coachStrengthMultiplierFor(pl.getClubId())).thenReturn(1.04));

        int coachedGoals = 0;
        for (long seed = 0; seed < 200; seed++) {
            coachedGoals += engine.simulate(config(seed, List.of())).homeGoals();
        }

        assertThat(coachedGoals).isGreaterThan(neutralGoals);
    }

    @Test
    void aSubChangesTheFutureButNotThePast() {
        LiveTimeline base = engine.simulate(config(99L, List.of()));
        UUID out = homeXi.get(5).getId();
        UUID in = homeBench.get(0).getId();
        LiveTimeline subbed = engine.simulate(config(99L, List.of(new LiveSub(60, true, out, in))));

        assertThat(subbed.eventsThrough(59)).isEqualTo(base.eventsThrough(59));
        assertThat(subbed.events()).anyMatch(e ->
                e.type() == EventType.SUBSTITUTION && e.minute() == 60
                        && e.playerId().equals(out) && e.playerInId().equals(in));
        assertThat(subbed.minutesPlayed().get(out)).isEqualTo(60);
        assertThat(subbed.minutesPlayed().get(in)).isEqualTo(30);
        assertThat(subbed.events()).isNotEqualTo(base.events());
    }

    @Test
    void startersWhoPlayTheWholeMatchLogNinetyMinutes() {
        LiveTimeline t = engine.simulate(config(7L, List.of()));
        assertThat(t.minutesPlayed().get(homeXi.get(0).getId())).isEqualTo(90);
    }

    @Test
    void injuriesAreTimedAndCarryASeverity() {
        // Seed 2 is verified to produce at least one injury (MINOR at minute 69, layoff 4).
        LiveTimeline t = engine.simulate(config(2L, List.of()));
        assertThat(t.injuries()).isNotEmpty();
        t.injuries().forEach(inj -> {
            assertThat(inj.minute()).isBetween(1, 90);
            assertThat(inj.layoffMatches()).isGreaterThan(0);
        });
        t.events().stream().filter(e -> e.type() == EventType.INJURY)
                .forEach(e -> assertThat(e.severity()).isNotNull());
    }

    @Test
    void emptyTacticsScheduleProducesIdenticalTimelineToLegacyConfig() {
        // An empty tacticsSchedule must be byte-identical to a config with no tacticsSchedule arg (backward compat).
        LiveTimeline base = engine.simulate(config(1234L, List.of()));
        LiveTimeline withEmptyTactics = engine.simulate(config(1234L, List.of(), List.of()));
        assertThat(withEmptyTactics.events()).isEqualTo(base.events());
        assertThat(withEmptyTactics.homeGoals()).isEqualTo(base.homeGoals());
        assertThat(withEmptyTactics.awayGoals()).isEqualTo(base.awayGoals());
    }

    @Test
    void halfTimeMarkerEmittedAtMinute45() {
        // Every simulated timeline must contain exactly one HALF_TIME event at minute 45.
        for (long seed : new long[]{1L, 2L, 7L, 42L, 99L, 1234L}) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            long halfTimeCount = t.events().stream()
                    .filter(e -> e.type() == EventType.HALF_TIME)
                    .count();
            assertThat(halfTimeCount)
                    .as("seed %d should have exactly one HALF_TIME event", seed)
                    .isEqualTo(1);
            assertThat(t.events().stream().filter(e -> e.type() == EventType.HALF_TIME).findFirst())
                    .get()
                    .satisfies(e -> assertThat(e.minute()).isEqualTo(45));
        }
    }

    @Test
    void halfTimeDeterminism() {
        // Both runs include the HALF_TIME marker — determinism must hold.
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
    }

    @Test
    void keepersNeverScoreFromOpenPlayAndAttackersOutscoreDefenders() {
        // Every player in buildXi() has finishing 70, so a position-blind, finishing-only weighting
        // would let the keeper score ~1/11 of goals. Scoring must instead be driven by position.
        Map<UUID, Player> byId = new HashMap<>();
        homeXi.forEach(p -> byId.put(p.getId(), p));
        awayXi.forEach(p -> byId.put(p.getId(), p));
        homeBench.forEach(p -> byId.put(p.getId(), p));

        Map<Position, Integer> goalsByPosition = new EnumMap<>(Position.class);
        for (long seed = 0; seed < 200; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type().isGoal()) {
                    Position pos = byId.get(e.playerId()).getPosition();
                    goalsByPosition.merge(pos, 1, Integer::sum);
                }
            }
        }

        assertThat(goalsByPosition.getOrDefault(Position.GK, 0))
                .as("keepers must not score from open play")
                .isZero();
        assertThat(goalsByPosition.getOrDefault(Position.ATT, 0))
                .as("attackers should outscore defenders")
                .isGreaterThan(goalsByPosition.getOrDefault(Position.DEF, 0));
    }

    @Test
    void aTacticsChangeAppearsAtItsMinuteAndChangesTheFuture() {
        long seed = 42L;
        LiveTimeline base = engine.simulate(config(seed, List.of()));

        TimedTactics change = new TimedTactics(60, true, Mentality.ATTACKING, null, null, Pressing.HIGH, Marking.ZONAL, "4-3-3");
        LiveTimeline withTactics = engine.simulate(config(seed, List.of(), List.of(change)));

        // Events up to and including minute 59 must be identical (tactics not yet applied).
        assertThat(withTactics.eventsThrough(59)).isEqualTo(base.eventsThrough(59));

        // A TACTICS_CHANGE event exists at minute 60 for the home side.
        assertThat(withTactics.events()).anyMatch(e ->
                e.type() == EventType.TACTICS_CHANGE && e.minute() == 60 && e.home());

        // The full event lists differ (tactics change influences strength from minute 60 onward).
        assertThat(withTactics.events()).isNotEqualTo(base.events());

        // Determinism: same tactics schedule → identical timelines.
        LiveTimeline withTacticsAgain = engine.simulate(config(seed, List.of(), List.of(change)));
        assertThat(withTacticsAgain.events()).isEqualTo(withTactics.events());
    }

    // ---- AI manager reactions ----

    @Test
    void nullAiHomeProducesNoAiReactions() {
        for (long seed : new long[]{1L, 7L, 42L, 99L, 1234L}) {
            LiveTimeline t = engine.simulate(config(seed, List.of(), List.of(), null));
            // Scoped to TACTICS_CHANGE: a second-yellow RED_CARD also carries a non-null detail
            // (SECOND_YELLOW_DETAIL), independent of AI reactions.
            assertThat(t.events()).noneMatch(e -> e.type() == EventType.TACTICS_CHANGE && e.detail() != null);
        }
    }

    @Test
    void aiReactionsMatchTheBrainAppliedToTheRunningScore() {
        // Oracle test: reconstruct the expected reaction sequence from the timeline itself using
        // the production brain, and compare it to the AI-side TACTICS_CHANGE events. Verifies the
        // engine's score counting, per-minute evaluation, change-only emission, and detail values.
        for (long seed = 1; seed <= 20; seed++) {
            LiveMatchConfig cfg = config(seed, List.of(), List.of(), false); // away side is the AI
            LiveTimeline t = engine.simulate(cfg);

            List<LiveTimelineEvent> actual = t.events().stream()
                    .filter(e -> e.type() == EventType.TACTICS_CHANGE && !e.home())
                    .toList();
            List<LiveTimelineEvent> expected = expectedAwayReactions(t, cfg.awayProfile());

            assertThat(actual).as("seed %d", seed)
                    .extracting(LiveTimelineEvent::minute, LiveTimelineEvent::detail)
                    .isEqualTo(expected.stream()
                            .map(e -> org.assertj.core.groups.Tuple.tuple(e.minute(), e.detail()))
                            .toList());
        }
    }

    /**
     * Walks minutes 46..90 replaying what the engine's AI hook must have seen: the score and
     * red-card state from events strictly BEFORE the minute under evaluation (the hook runs
     * before that minute's goals/cards are drawn). Emits an expected event whenever the brain's
     * posture changes.
     */
    private List<LiveTimelineEvent> expectedAwayReactions(LiveTimeline t,
                                                          TacticalProfile awayIdentity) {
        List<LiveTimelineEvent> expected = new ArrayList<>();
        Posture posture = Posture.NORMAL;
        for (int minute = 46; minute <= 90; minute++) {
            final int m = minute;
            int homeGoals = (int) t.events().stream().filter(e -> e.home() && e.minute() < m
                    && e.type().isGoal()).count();
            int awayGoals = (int) t.events().stream().filter(e -> !e.home() && e.minute() < m
                    && e.type().isGoal()).count();
            boolean downAMan = t.events().stream()
                    .anyMatch(e -> !e.home() && e.minute() < m && e.type() == EventType.RED_CARD);
            Posture next = AiManagerBrain.decide(awayIdentity.mentality(),
                    awayGoals - homeGoals, m, downAMan);
            if (next != posture) {
                posture = next;
                expected.add(new LiveTimelineEvent(m, EventType.TACTICS_CHANGE, false,
                        null, null, null, next.name()));
            }
        }
        return expected;
    }

    @Test
    void anAttackingAiAlwaysReactsByMinuteEightyFive() {
        // With an ATTACKING identity, minute 85 is non-NORMAL for EVERY score line:
        // losing -> PUSH/ALL_OUT (thresholds <= 70), drawing -> PUSH (75),
        // winning by 1 -> PROTECT (80), winning by 2+ -> PROTECT (85). So a reaction event must
        // exist for every seed.
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(awayXi);
        TacticalProfile attackingAway = new TacticalProfile(Mentality.ATTACKING, GameStyle.EQUILIBRADO,
                AttackZone.MISTO, "4-3-3", Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());

        for (long seed = 1; seed <= 20; seed++) {
            Map<UUID, Integer> fitness = new HashMap<>();
            homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
            awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
            LiveMatchConfig cfg = new LiveMatchConfig(seed, homeXi, awayXi, List.of(), List.of(),
                    defaultProfile(homeXi), attackingAway, fitness, List.of(), List.of(), false, Weather.neutral(),
                    false, null, null);
            LiveTimeline t = engine.simulate(cfg);
            assertThat(t.events()).as("seed %d", seed)
                    .anyMatch(e -> e.type() == EventType.TACTICS_CHANGE && !e.home()
                            && e.detail() != null);
        }
    }

    @Test
    void aiReactionsDoNotChangeThePastAndAreDeterministic() {
        long seed = 42L;
        LiveTimeline base = engine.simulate(config(seed, List.of(), List.of(), null));
        LiveTimeline withAi = engine.simulate(config(seed, List.of(), List.of(), false));

        int firstReaction = withAi.events().stream()
                .filter(e -> e.detail() != null)
                .mapToInt(LiveTimelineEvent::minute).min().orElse(91);
        assertThat(withAi.eventsThrough(firstReaction - 1))
                .isEqualTo(base.eventsThrough(firstReaction - 1));

        LiveTimeline again = engine.simulate(config(seed, List.of(), List.of(), false));
        assertThat(again.events()).isEqualTo(withAi.events());
        assertThat(again.homeGoals()).isEqualTo(withAi.homeGoals());
    }

    @Test
    void userTacticsAndAiReactionsCoexist() {
        // Managed home side schedules a change at 60; away side is the AI. The user's event keeps
        // a null detail; any AI event carries a posture detail; the managed side never gets one.
        TimedTactics change = new TimedTactics(60, true, Mentality.ATTACKING, null, null, Pressing.HIGH,
                Marking.ZONAL, "4-3-3");
        boolean sawUserChange = false;
        for (long seed = 1; seed <= 20; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of(), List.of(change), false));
            sawUserChange |= t.events().stream().anyMatch(e ->
                    e.type() == EventType.TACTICS_CHANGE && e.home() && e.minute() == 60
                            && e.detail() == null);
            // Scoped to TACTICS_CHANGE: a second-yellow RED_CARD on the managed (home) side also
            // carries a non-null detail (SECOND_YELLOW_DETAIL), independent of AI reactions.
            assertThat(t.events()).noneMatch(e ->
                    e.home() && e.type() == EventType.TACTICS_CHANGE && e.detail() != null);
        }
        assertThat(sawUserChange).isTrue();
    }

    // ---- distinct set pieces ----

    @Test
    void distinctSetPieceTypesOccurAndLegacyNever() {
        Set<EventType> seen = EnumSet.noneOf(EventType.class);
        for (long seed = 0; seed < 300; seed++) {
            engine.simulate(config(seed, List.of())).events().forEach(e -> seen.add(e.type()));
        }
        assertThat(seen).contains(EventType.PENALTY_GOAL, EventType.FREE_KICK_GOAL, EventType.CORNER_GOAL);
        assertThat(seen).doesNotContain(EventType.SET_PIECE_GOAL);
    }

    @Test
    void scoreboardCountsMatchGoalEventsExactly() {
        for (long seed = 0; seed < 50; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            long homeGoalEvents = t.events().stream().filter(e -> e.home() && e.type().isGoal()).count();
            long awayGoalEvents = t.events().stream().filter(e -> !e.home() && e.type().isGoal()).count();
            assertThat(t.homeGoals()).isEqualTo((int) homeGoalEvents);
            assertThat(t.awayGoals()).isEqualTo((int) awayGoalEvents);
        }
    }

    @Test
    void penaltiesCanBeMissedAndTheTakerIsTheActor() {
        // BOLA_PARADA 0 everywhere -> penaltyConversion() is exactly SetPieces.conversionChance(0)
        // == 0.70, so ~30% of penalties miss; over 300 seeds a miss is certain (P(none) < 1e-4).
        List<Player> penHomeXi = fullProfileXi();
        List<Player> penAwayXi = fullProfileXi();
        Player penaltyTaker = penHomeXi.get(5); // a MID in the XI
        TacticalProfile homeProfile = profileWithTakers(penHomeXi, penaltyTaker.getId(), null, null);
        TacticalProfile awayProfile = profileWithTakers(penAwayXi, null, null, null);
        Map<UUID, Integer> fitness = fullFitness(penHomeXi, penAwayXi);

        boolean sawGoal = false;
        boolean sawMiss = false;
        for (long seed = 0; seed < 300; seed++) {
            LiveMatchConfig cfg = new LiveMatchConfig(seed, penHomeXi, penAwayXi, List.of(), List.of(),
                    homeProfile, awayProfile, fitness, List.of(), List.of(), null, Weather.neutral(),
                    false, null, null);
            LiveTimeline t = engine.simulate(cfg);
            for (LiveTimelineEvent e : t.events()) {
                if (!e.home()) continue;
                if (e.type() == EventType.PENALTY_GOAL) {
                    sawGoal = true;
                    assertThat(e.playerId()).isEqualTo(penaltyTaker.getId());
                } else if (e.type() == EventType.PENALTY_MISSED) {
                    sawMiss = true;
                    assertThat(e.playerId()).isEqualTo(penaltyTaker.getId());
                }
            }
        }
        assertThat(sawGoal).as("at least one penalty must be scored across 300 seeds").isTrue();
        assertThat(sawMiss).as("at least one penalty must be missed across 300 seeds").isTrue();
    }

    @Test
    void takersAreAttributedByType() {
        // Three DISTINCT designated takers, all in the XI.
        List<Player> attHomeXi = fullProfileXi();
        List<Player> attAwayXi = fullProfileXi();
        Player penaltyTaker = attHomeXi.get(5);
        Player freekickTaker = attHomeXi.get(6);
        Player cornerTaker = attHomeXi.get(7);
        TacticalProfile homeProfile = profileWithTakers(attHomeXi,
                penaltyTaker.getId(), freekickTaker.getId(), cornerTaker.getId());
        TacticalProfile awayProfile = profileWithTakers(attAwayXi, null, null, null);
        Map<UUID, Integer> fitness = fullFitness(attHomeXi, attAwayXi);

        Map<UUID, Player> byId = new HashMap<>();
        attHomeXi.forEach(pl -> byId.put(pl.getId(), pl));

        for (long seed = 0; seed < 300; seed++) {
            LiveMatchConfig cfg = new LiveMatchConfig(seed, attHomeXi, attAwayXi, List.of(), List.of(),
                    homeProfile, awayProfile, fitness, List.of(), List.of(), null, Weather.neutral(),
                    false, null, null);
            LiveTimeline t = engine.simulate(cfg);
            // A designated taker who is sent off (RED_CARD, incl. a second yellow) leaves the pitch,
            // and SetPieces.takerOrFallback then deliberately picks the best available substitute — so
            // only assert taker-equality while the designated taker is still on the pitch at the
            // event's minute (a same-minute send-off is applied *after* that minute's set piece).
            Map<UUID, Integer> homeSendOffMinute = new HashMap<>();
            t.events().stream()
                    .filter(e -> e.home() && e.type() == EventType.RED_CARD)
                    .forEach(e -> homeSendOffMinute.merge(e.playerId(), e.minute(), Math::min));
            for (LiveTimelineEvent e : t.events()) {
                if (!e.home()) continue;
                switch (e.type()) {
                    case FREE_KICK_GOAL -> {
                        if (e.minute() <= homeSendOffMinute.getOrDefault(freekickTaker.getId(), Integer.MAX_VALUE)) {
                            assertThat(e.playerId()).isEqualTo(freekickTaker.getId());
                        }
                    }
                    case CORNER_GOAL -> {
                        if (e.minute() <= homeSendOffMinute.getOrDefault(cornerTaker.getId(), Integer.MAX_VALUE)) {
                            assertThat(e.playerInId()).isEqualTo(cornerTaker.getId());
                        }
                        assertThat(byId.get(e.playerId()).getPosition()).isNotEqualTo(Position.GK);
                    }
                    case PENALTY_GOAL, PENALTY_MISSED -> {
                        if (e.minute() <= homeSendOffMinute.getOrDefault(penaltyTaker.getId(), Integer.MAX_VALUE)) {
                            assertThat(e.playerId()).isEqualTo(penaltyTaker.getId());
                        }
                    }
                    default -> { }
                }
            }
        }
    }

    // ---- AI substitutions ----

    /** 1 GK + 2 DEF + 2 MID + 2 ATT, all rated 65 — cover for every on-pitch position. */
    private static List<Player> awayBenchWithCover() {
        return new ArrayList<>(List.of(
                p(Position.GK, 65),
                p(Position.DEF, 65), p(Position.DEF, 65),
                p(Position.MID, 65), p(Position.MID, 65),
                p(Position.ATT, 65), p(Position.ATT, 65)));
    }

    private LiveMatchConfig configWithAwayBench(long seed, List<Player> awayBench, List<LiveSub> subs,
                                                Boolean aiHome) {
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
        awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
        homeBench.forEach(pl -> fitness.put(pl.getId(), 100));
        awayBench.forEach(pl -> fitness.put(pl.getId(), 100));

        TacticalProfile homeProfile = defaultProfile(homeXi);
        TacticalProfile awayProfile = defaultProfile(awayXi);

        return new LiveMatchConfig(seed, homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs, List.of(), aiHome, Weather.neutral(), false, null, null);
    }

    @Test
    void aHobbledAiPlayerWithCoverIsSubstituted() {
        // Away side is the AI (aiHome = false) with a bench containing cover for every position.
        // For every away INJURY event (to an original-XI player, so minutesPlayed is simply the
        // sub minute) at minute m where the away side still had subs left and bench cover for
        // the victim's position at that instant, assert the victim is eventually subbed off.
        List<Player> awayBench = awayBenchWithCover();
        Map<Position, List<UUID>> benchByPosition = new EnumMap<>(Position.class);
        awayBench.forEach(pl -> benchByPosition
                .computeIfAbsent(pl.getPosition(), k -> new ArrayList<>()).add(pl.getId()));
        Set<UUID> awayXiIds = new HashSet<>();
        awayXi.forEach(pl -> awayXiIds.add(pl.getId()));
        Map<UUID, Player> byId = new HashMap<>();
        awayXi.forEach(pl -> byId.put(pl.getId(), pl));

        for (long seed = 0; seed < 300; seed++) {
            LiveMatchConfig cfg = configWithAwayBench(seed, awayBench, List.of(), false);
            LiveTimeline t = engine.simulate(cfg);

            List<LiveTimelineEvent> awaySubs = t.events().stream()
                    .filter(e -> e.type() == EventType.SUBSTITUTION && !e.home())
                    .sorted(Comparator.comparingInt(LiveTimelineEvent::minute))
                    .toList();
            // Minute 90 is the match's last minute: there is no later minute for the AI to react
            // in, so an injury there can never be structurally followed by a substitution.
            List<LiveTimelineEvent> awayInjuries = t.events().stream()
                    .filter(e -> e.type() == EventType.INJURY && !e.home() && e.minute() < 90
                            && awayXiIds.contains(e.playerId()))
                    .sorted(Comparator.comparingInt(LiveTimelineEvent::minute))
                    .toList();

            Set<UUID> usedBench = new HashSet<>();
            int subsUsedSoFar = 0;
            int subIdx = 0;

            for (LiveTimelineEvent inj : awayInjuries) {
                int m = inj.minute();
                while (subIdx < awaySubs.size() && awaySubs.get(subIdx).minute() <= m) {
                    usedBench.add(awaySubs.get(subIdx).playerInId());
                    subsUsedSoFar++;
                    subIdx++;
                }
                if (subsUsedSoFar >= AiSubstitutionBrain.MAX_SUBS) continue;

                Position pos = byId.get(inj.playerId()).getPosition();
                boolean coverAvailable = benchByPosition.getOrDefault(pos, List.of()).stream()
                        .anyMatch(id -> !usedBench.contains(id));
                if (!coverAvailable) continue;

                LiveTimelineEvent subEvent = awaySubs.stream()
                        .filter(s -> s.minute() > m && inj.playerId().equals(s.playerId()))
                        .findFirst().orElse(null);
                assertThat(subEvent)
                        .as("seed %d: injured victim at minute %d should eventually be subbed", seed, m)
                        .isNotNull();
                assertThat(t.minutesPlayed().get(inj.playerId()))
                        .as("seed %d: victim's minutes should end at the sub minute", seed)
                        .isEqualTo(subEvent.minute());
            }
        }
    }

    @Test
    void aiSubsNeverExceedTheCapAndOnlyBenchPlayersComeOn() {
        List<Player> awayBench = awayBenchWithCover();
        Set<UUID> benchIds = new HashSet<>();
        awayBench.forEach(pl -> benchIds.add(pl.getId()));

        for (long seed = 0; seed < 300; seed++) {
            LiveMatchConfig cfg = configWithAwayBench(seed, awayBench, List.of(), false);
            LiveTimeline t = engine.simulate(cfg);

            List<LiveTimelineEvent> awaySubs = t.events().stream()
                    .filter(e -> e.type() == EventType.SUBSTITUTION && !e.home())
                    .toList();
            assertThat(awaySubs.size()).as("seed %d", seed).isLessThanOrEqualTo(5);

            Set<UUID> seenIn = new HashSet<>();
            for (LiveTimelineEvent e : awaySubs) {
                assertThat(benchIds).as("seed %d: incoming player must be a bench player", seed)
                        .contains(e.playerInId());
                assertThat(seenIn.add(e.playerInId()))
                        .as("seed %d: a bench player must not come on twice", seed)
                        .isTrue();
            }
        }
    }

    @Test
    void anEmptyAiBenchMeansNoAiSubstitutions() {
        // aiHome = false, away bench = List.of() (the old-save shape, produced by the shared
        // config() factory): sweep seeds and assert zero away-side SUBSTITUTION events.
        for (long seed = 0; seed < 50; seed++) {
            LiveMatchConfig cfg = config(seed, List.of(), List.of(), false);
            LiveTimeline t = engine.simulate(cfg);
            assertThat(t.events()).as("seed %d", seed)
                    .noneMatch(e -> e.type() == EventType.SUBSTITUTION && !e.home());
        }
    }

    @Test
    void aiSubstitutionsAreDeterministic() {
        LiveMatchConfig cfg = configWithAwayBench(17L, awayBenchWithCover(), List.of(), false);
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
        assertThat(a.minutesPlayed()).isEqualTo(b.minutesPlayed());
    }

    @Test
    void managedScheduleSubsAndAiSubsCoexist() {
        // Home (managed) has a scheduled LiveSub at minute 60; away is the AI with a bench.
        // The home SUBSTITUTION appears exactly as scheduled and the home side never gets a
        // brain-produced sub — any additional SUBSTITUTION events are away-side by construction
        // (each event carries a single home flag), verified structurally in the sibling tests.
        UUID homeOut = homeXi.get(5).getId();
        UUID homeIn = homeBench.get(0).getId();
        List<LiveSub> homeSchedule = List.of(new LiveSub(60, true, homeOut, homeIn));
        List<Player> awayBench = awayBenchWithCover();

        for (long seed = 0; seed < 20; seed++) {
            LiveMatchConfig cfg = configWithAwayBench(seed, awayBench, homeSchedule, false);
            LiveTimeline t = engine.simulate(cfg);

            List<LiveTimelineEvent> homeSubs = t.events().stream()
                    .filter(e -> e.type() == EventType.SUBSTITUTION && e.home())
                    .toList();
            assertThat(homeSubs).as("seed %d: home has exactly its one scheduled sub", seed).hasSize(1);
            assertThat(homeSubs.get(0).minute()).isEqualTo(60);
            assertThat(homeSubs.get(0).playerId()).isEqualTo(homeOut);
            assertThat(homeSubs.get(0).playerInId()).isEqualTo(homeIn);
        }
    }

    // ---- second yellow = red ----

    @Test
    void aSecondYellowAlwaysComesWithASameMinuteSecondYellowRedAndEndsThePlayersMatch() {
        boolean sawSecondYellow = false;
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            Map<UUID, List<LiveTimelineEvent>> yellowsByPlayer = new HashMap<>();
            Map<UUID, List<LiveTimelineEvent>> redsByPlayer = new HashMap<>();
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.YELLOW_CARD) {
                    yellowsByPlayer.computeIfAbsent(e.playerId(), k -> new ArrayList<>()).add(e);
                }
                if (e.type() == EventType.RED_CARD) {
                    redsByPlayer.computeIfAbsent(e.playerId(), k -> new ArrayList<>()).add(e);
                }
            }
            for (Map.Entry<UUID, List<LiveTimelineEvent>> en : yellowsByPlayer.entrySet()) {
                List<LiveTimelineEvent> yellows = en.getValue();
                assertThat(yellows.size())
                        .as("seed %d: a player can never collect more than two yellows", seed)
                        .isLessThanOrEqualTo(2);
                if (yellows.size() == 2) {
                    sawSecondYellow = true;
                    List<LiveTimelineEvent> reds = redsByPlayer.getOrDefault(en.getKey(), List.of());
                    assertThat(reds).as("seed %d: two yellows must carry exactly one red", seed).hasSize(1);
                    assertThat(reds.get(0).minute())
                            .as("seed %d: the red lands in the second yellow's minute", seed)
                            .isEqualTo(yellows.get(1).minute());
                    assertThat(reds.get(0).detail())
                            .isEqualTo(LiveTimelineEvent.SECOND_YELLOW_DETAIL);
                    assertThat(t.minutesPlayed().get(en.getKey()))
                            .as("seed %d: the send-off ends the player's minutes", seed)
                            .isEqualTo(reds.get(0).minute());
                }
            }
            for (Map.Entry<UUID, List<LiveTimelineEvent>> en : redsByPlayer.entrySet()) {
                assertThat(en.getValue()).as("seed %d: at most one red per player", seed).hasSize(1);
                int redMinute = en.getValue().get(0).minute();
                UUID sentOff = en.getKey();
                assertThat(t.events()).as("seed %d: no involvement after a send-off", seed)
                        .noneMatch(e -> e.minute() > redMinute
                                && (sentOff.equals(e.playerId()) || sentOff.equals(e.playerInId())));
            }
        }
        assertThat(sawSecondYellow)
                .as("300 seeds must produce at least one second-yellow send-off")
                .isTrue();
    }

    @Test
    void aStraightRedCarriesNoSecondYellowDetail() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream().filter(e -> e.type() == EventType.RED_CARD).forEach(e -> {
                long yellows = t.events().stream()
                        .filter(y -> y.type() == EventType.YELLOW_CARD && y.playerId().equals(e.playerId()))
                        .count();
                if (yellows < 2) {
                    assertThat(e.detail()).as("straight red keeps a null detail").isNull();
                } else {
                    assertThat(e.detail()).isEqualTo(LiveTimelineEvent.SECOND_YELLOW_DETAIL);
                }
            });
        }
    }

    // --- Extra time (live engine) ------------------------------------------------------------
    //
    // The live engine reaches extra time through a mutated loop bound rather than a second
    // simulate() call, so it needs its own coverage: nothing else in the suite constructs a
    // LiveMatchConfig with knockoutDecider = true.

    /** A knockout second leg. The higher seed hosts leg 2, so THIS match's home side was away in leg 1. */
    private LiveMatchConfig deciderConfig(long seed, int firstLegHome, int firstLegAway) {
        LiveMatchConfig base = config(seed, List.of());
        return new LiveMatchConfig(base.seed(), base.homeXi(), base.awayXi(), base.homeBench(),
                base.awayBench(), base.homeProfile(), base.awayProfile(), base.fitness(),
                base.subSchedule(), base.tacticsSchedule(), base.aiHome(), base.weather(),
                true, firstLegHome, firstLegAway);
    }

    @Test
    void aKnockoutDeciderLevelOnAggregateRunsToOneHundredTwenty() {
        long seed = 4242L;
        // Learn this seed's 90-minute score, then pick a first leg that makes the tie exactly level.
        // Aggregate crossover: this match's home club was the away club in leg 1, so choosing
        // firstLegHome = h and firstLegAway = a gives both sides h + a.
        LiveTimeline ninety = engine.simulate(config(seed, List.of()));
        int h = ninety.homeGoals();
        int a = ninety.awayGoals();

        LiveTimeline extended = engine.simulate(deciderConfig(seed, h, a));

        // The live path signals extra time through finalMinute, NOT through an EXTRA_TIME_START
        // event: that marker is match_events-only, and LiveCommentary throws if it ever reaches
        // the live timeline (see LiveCommentary's EXTRA_TIME_START branch).
        assertThat(extended.finalMinute()).isEqualTo(120);
        assertThat(extended.events()).noneMatch(e -> e.type() == EventType.EXTRA_TIME_START);
        assertThat(extended.events()).allSatisfy(e -> assertThat(e.minute()).isLessThanOrEqualTo(120));
    }

    @Test
    void extraTimeIsActuallySimulatedAndNotJustReportedAsOneHundredTwenty() {
        // finalMinute alone would pass even if minutes 91-120 were never played. Across a spread
        // of seeds, at least some deciders must produce real events after minute 90.
        int seedsWithExtraTimeEvents = 0;
        for (long seed = 0; seed < 40; seed++) {
            LiveTimeline ninety = engine.simulate(config(seed, List.of()));
            LiveTimeline extended =
                    engine.simulate(deciderConfig(seed, ninety.homeGoals(), ninety.awayGoals()));
            assertThat(extended.finalMinute()).as("seed %d", seed).isEqualTo(120);
            if (extended.events().stream().anyMatch(e -> e.minute() > 90)) {
                seedsWithExtraTimeEvents++;
            }
        }
        assertThat(seedsWithExtraTimeEvents).isGreaterThan(0);
    }

    @Test
    void extraTimeLeavesTheFirstNinetyMinutesUntouched() {
        long seed = 4242L;
        LiveTimeline ninety = engine.simulate(config(seed, List.of()));
        LiveTimeline extended = engine.simulate(deciderConfig(seed, ninety.homeGoals(), ninety.awayGoals()));

        // Everything up to 90 must be identical -- extra time appends, it never re-rolls the match.
        assertThat(extended.eventsThrough(90)).isEqualTo(ninety.events());
        assertThat(extended.homeGoalsAt(90)).isEqualTo(ninety.homeGoals());
        assertThat(extended.awayGoalsAt(90)).isEqualTo(ninety.awayGoals());
    }

    @Test
    void aKnockoutDeciderAlreadyDecidedOnAggregateStopsAtNinety() {
        long seed = 4242L;
        LiveTimeline ninety = engine.simulate(config(seed, List.of()));
        // Give the away side a three-goal first-leg cushion: the tie cannot be level at 90.
        LiveTimeline decided = engine.simulate(
                deciderConfig(seed, ninety.homeGoals() + 3, ninety.awayGoals()));

        assertThat(decided.finalMinute()).isEqualTo(90);
        assertThat(decided.events()).noneMatch(e -> e.type() == EventType.EXTRA_TIME_START);
        assertThat(decided.events()).isEqualTo(ninety.events());
    }

    @Test
    void aLeagueMatchNeverReachesExtraTimeAtAnySeed() {
        for (long seed = 0; seed < 200; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            assertThat(t.finalMinute()).as("seed %d", seed).isEqualTo(90);
            assertThat(t.events()).noneMatch(e -> e.type() == EventType.EXTRA_TIME_START);
        }
    }

    // --- Match statistics (statsRng) ----------------------------------------------------------

    @Test
    void statsAreDeterministicForAFixedSeed() {
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.minuteStats()).isEqualTo(b.minuteStats());
    }

    @Test
    void statsInvariantsHoldAtEveryRevealedMinute() {
        // Over many seeds and every revealed minute, statsThrough's structural invariants must
        // hold and every counter (other than possession, which is an average, not a running total)
        // must be monotonically non-decreasing as more of the match is revealed.
        for (long seed = 0; seed < 100; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            // statsThrough clamps rather than throws when asked for a minute beyond minuteStats'
            // size, so a dropped entry would silently under-report instead of failing loudly — pin
            // the one-entry-per-minute invariant across this same 100-seed spread, not just the two
            // seeds in minuteStatsHasOneEntryPerSimulatedMinute.
            assertThat(t.minuteStats()).as("seed %d minuteStats size", seed).hasSize(t.finalMinute());
            TeamStats prevHome = t.statsThrough(0).home();
            TeamStats prevAway = t.statsThrough(0).away();
            for (int minute = 0; minute <= t.finalMinute(); minute++) {
                MatchStats stats = t.statsThrough(minute);
                TeamStats homeStats = stats.home();
                TeamStats awayStats = stats.away();
                String ctx = "seed " + seed + " minute " + minute;

                assertThat(homeStats.shots()).as(ctx + ": home shots >= shotsOnTarget")
                        .isGreaterThanOrEqualTo(homeStats.shotsOnTarget());
                assertThat(awayStats.shots()).as(ctx + ": away shots >= shotsOnTarget")
                        .isGreaterThanOrEqualTo(awayStats.shotsOnTarget());
                assertThat(homeStats.shotsOnTarget()).as(ctx + ": home shotsOnTarget >= goals")
                        .isGreaterThanOrEqualTo(t.homeGoalsAt(minute));
                assertThat(awayStats.shotsOnTarget()).as(ctx + ": away shotsOnTarget >= goals")
                        .isGreaterThanOrEqualTo(t.awayGoalsAt(minute));
                assertThat(homeStats.corners()).as(ctx + ": home corners >= cornerGoals")
                        .isGreaterThanOrEqualTo(countEvents(t, minute, EventType.CORNER_GOAL, true));
                assertThat(awayStats.corners()).as(ctx + ": away corners >= cornerGoals")
                        .isGreaterThanOrEqualTo(countEvents(t, minute, EventType.CORNER_GOAL, false));
                assertThat(homeStats.saves()).as(ctx + ": home saves >= 0").isGreaterThanOrEqualTo(0);
                assertThat(awayStats.saves()).as(ctx + ": away saves >= 0").isGreaterThanOrEqualTo(0);
                assertThat(homeStats.possession() + awayStats.possession())
                        .as(ctx + ": possession sums to 100").isEqualTo(100);

                assertMonotonicallyNonDecreasing(prevHome, homeStats, ctx + " home");
                assertMonotonicallyNonDecreasing(prevAway, awayStats, ctx + " away");
                prevHome = homeStats;
                prevAway = awayStats;
            }
        }
    }

    private void assertMonotonicallyNonDecreasing(TeamStats prev, TeamStats current, String ctx) {
        assertThat(current.shots()).as(ctx + ": shots monotonic").isGreaterThanOrEqualTo(prev.shots());
        assertThat(current.shotsOnTarget()).as(ctx + ": shotsOnTarget monotonic")
                .isGreaterThanOrEqualTo(prev.shotsOnTarget());
        assertThat(current.saves()).as(ctx + ": saves monotonic").isGreaterThanOrEqualTo(prev.saves());
        assertThat(current.corners()).as(ctx + ": corners monotonic").isGreaterThanOrEqualTo(prev.corners());
        assertThat(current.offsides()).as(ctx + ": offsides monotonic").isGreaterThanOrEqualTo(prev.offsides());
        assertThat(current.fouls()).as(ctx + ": fouls monotonic").isGreaterThanOrEqualTo(prev.fouls());
        assertThat(current.yellowCards()).as(ctx + ": yellowCards monotonic")
                .isGreaterThanOrEqualTo(prev.yellowCards());
        assertThat(current.redCards()).as(ctx + ": redCards monotonic").isGreaterThanOrEqualTo(prev.redCards());
    }

    private static int countEvents(LiveTimeline t, int throughMinute, EventType type, boolean home) {
        return (int) t.eventsThrough(throughMinute).stream()
                .filter(e -> e.home() == home && e.type() == type).count();
    }

    @Test
    void everyChanceAndMissedPenaltyIsCountedAsAShot() {
        // Product requirement, as an executable assertion: a user who watched 9 near-miss lances
        // must never see "Finalizacoes: 3" -- every narrated CHANCE and every missed penalty must
        // count toward the final shot tally (statsThrough sums extraShots on top of these, so the
        // inequality rather than an equality is the correct check).
        for (long seed = 0; seed < 200; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            MatchStats stats = t.statsThrough(t.finalMinute());

            int homeChances = countEvents(t, t.finalMinute(), EventType.CHANCE, true);
            int awayChances = countEvents(t, t.finalMinute(), EventType.CHANCE, false);
            int homePenaltyMissed = countEvents(t, t.finalMinute(), EventType.PENALTY_MISSED, true);
            int awayPenaltyMissed = countEvents(t, t.finalMinute(), EventType.PENALTY_MISSED, false);

            assertThat(stats.home().shots()).as("seed %d home", seed)
                    .isGreaterThanOrEqualTo(homeChances + t.homeGoals() + homePenaltyMissed);
            assertThat(stats.away().shots()).as("seed %d away", seed)
                    .isGreaterThanOrEqualTo(awayChances + t.awayGoals() + awayPenaltyMissed);
        }
    }

    @Test
    void statsLandInRealisticPerNinetyBands() {
        // Loose calibration sanity check, mirroring the deliberately loose style of
        // foulsOccurAtARealisticPerTeamRateAndAreNeverAttributedToAnEmptyPitch: not exact-value
        // assertions, just a broad realistic band per 90 minutes.
        int seeds = 150;
        long totalShots = 0;
        long totalOnTarget = 0;
        long totalCorners = 0;
        long totalOffsides = 0;
        for (long seed = 0; seed < seeds; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            TeamStats home = t.statsThrough(t.finalMinute()).home();
            totalShots += home.shots();
            totalOnTarget += home.shotsOnTarget();
            totalCorners += home.corners();
            totalOffsides += home.offsides();
        }
        assertThat((double) totalShots / seeds).isBetween(8.0, 18.0);
        assertThat((double) totalOnTarget / seeds).isBetween(2.5, 7.0);
        assertThat((double) totalCorners / seeds).isBetween(3.0, 8.0);
        assertThat((double) totalOffsides / seeds).isBetween(1.0, 5.0);
    }

    @Test
    void possessionAveragesNearFiftyForIdenticalSquads() {
        // buildXi() fields identically-rated squads on both sides, so mean home possession over
        // many seeds must land close to 50 -- pins that rollPossession carries no home-advantage term.
        int seeds = 150;
        double totalHomePossession = 0;
        for (long seed = 0; seed < seeds; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            totalHomePossession += t.statsThrough(t.finalMinute()).home().possession();
        }
        assertThat(totalHomePossession / seeds).isBetween(45.0, 55.0);
    }

    @Test
    void possessionVariesFromMatchToMatchForIdenticalSquads() {
        // The companion to possessionAveragesNearFiftyForIdenticalSquads, which would also pass with
        // ZERO variance: the per-minute POSSESSION_JITTER averages out to ~+-0.7pp over 90 minutes, so
        // without a per-match offset every identical-squad fixture reads a near-deterministic 50-50.
        // Both halves matter -- the SD catches a collapse back to that determinism, and the outlier
        // check catches a change that widens the SD without ever producing an interesting scoreline.
        int seeds = 150;
        double[] possessions = new double[seeds];
        for (int seed = 0; seed < seeds; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            possessions[seed] = t.statsThrough(t.finalMinute()).home().possession();
        }
        double mean = Arrays.stream(possessions).average().orElseThrow();
        double sd = Math.sqrt(Arrays.stream(possessions).map(v -> (v - mean) * (v - mean)).sum() / seeds);
        double maxDeviationFromFifty = Arrays.stream(possessions).map(v -> Math.abs(v - 50.0)).max().orElseThrow();
        String sample = String.format("mean %.2f, SD %.2f, min %.0f, max %.0f", mean, sd,
                Arrays.stream(possessions).min().orElseThrow(),
                Arrays.stream(possessions).max().orElseThrow());

        assertThat(sd).as("home possession SD over %d seeds (%s)", seeds, sample).isGreaterThan(2.0);
        assertThat(maxDeviationFromFifty)
                .as("largest home possession deviation from 50 over %d seeds (%s)", seeds, sample)
                .isGreaterThan(5.0);
    }

    @Test
    void possessionIsStillDeterministicForAFixedSeed() {
        // Pins that the per-match possession offset is drawn from a stream seeded off cfg.seed(),
        // not from anything ambient: the same config must replay the same possession figures.
        LiveMatchConfig cfg = config(9871L, List.of());
        LiveTimeline first = engine.simulate(cfg);
        LiveTimeline second = engine.simulate(cfg);

        assertThat(first.minuteStats().stream().map(MinuteStats::homePossession).toList())
                .isEqualTo(second.minuteStats().stream().map(MinuteStats::homePossession).toList());
        assertThat(first.statsThrough(first.finalMinute()).home().possession())
                .isEqualTo(second.statsThrough(second.finalMinute()).home().possession());
    }

    @Test
    void aStrongerSideHoldsMorePossession() {
        List<Player> strongHomeXi = buildXiRated(90);
        List<Player> weakAwayXi = buildXiRated(40);
        TacticalProfile strongProfile = defaultProfile(strongHomeXi);
        TacticalProfile weakProfile = defaultProfile(weakAwayXi);
        Map<UUID, Integer> fitness = fullFitness(strongHomeXi, weakAwayXi);

        int seeds = 150;
        double totalHomePossession = 0;
        for (long seed = 0; seed < seeds; seed++) {
            LiveMatchConfig cfg = new LiveMatchConfig(seed, strongHomeXi, weakAwayXi, List.of(), List.of(),
                    strongProfile, weakProfile, fitness, List.of(), List.of(), null, Weather.neutral(),
                    false, null, null);
            LiveTimeline t = engine.simulate(cfg);
            totalHomePossession += t.statsThrough(t.finalMinute()).home().possession();
        }
        assertThat(totalHomePossession / seeds).isGreaterThan(55.0);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all rated {@code rating} -- for building a strong/weak XI. */
    private static List<Player> buildXiRated(int rating) {
        return new ArrayList<>(List.of(
                p(Position.GK, rating),
                p(Position.DEF, rating), p(Position.DEF, rating), p(Position.DEF, rating), p(Position.DEF, rating),
                p(Position.MID, rating), p(Position.MID, rating), p(Position.MID, rating), p(Position.MID, rating),
                p(Position.ATT, rating), p(Position.ATT, rating)));
    }

    @Test
    void minuteStatsHasOneEntryPerSimulatedMinute() {
        LiveTimeline normal = engine.simulate(config(7L, List.of()));
        assertThat(normal.minuteStats()).hasSize(normal.finalMinute());

        long seed = 4242L;
        LiveTimeline ninety = engine.simulate(config(seed, List.of()));
        LiveTimeline extended = engine.simulate(deciderConfig(seed, ninety.homeGoals(), ninety.awayGoals()));
        assertThat(extended.finalMinute()).isEqualTo(120);
        assertThat(extended.minuteStats()).hasSize(120);
    }
}
