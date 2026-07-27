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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchEngineTest {

    private final br.com.carvoeiromanager.staff.StaffEffectsService staffEffectsService =
            Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class);
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staffEffectsService);

    {
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
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
                homeProfile, awayProfile, fitness, subs, tactics, aiHome);
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
                    defaultProfile(homeXi), attackingAway, fitness, List.of(), List.of(), false);
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
                    homeProfile, awayProfile, fitness, List.of(), List.of(), null);
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
                    homeProfile, awayProfile, fitness, List.of(), List.of(), null);
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
                homeProfile, awayProfile, fitness, subs, List.of(), aiHome);
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
}
