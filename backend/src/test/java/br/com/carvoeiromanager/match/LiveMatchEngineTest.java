package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.match.TimedTactics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchEngineTest {

    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator());

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
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        return new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null);
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
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        return new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles,
                penaltyTakerId, freekickTakerId, cornerTakerId);
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

        TimedTactics change = new TimedTactics(60, true, Mentality.ATTACKING, Pressing.HIGH, Marking.ZONAL, "4-3-3");
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
            assertThat(t.events()).noneMatch(e -> e.detail() != null);
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
        Map<UUID, PlayerRole> roles = new HashMap<>();
        awayXi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        TacticalProfile attackingAway = new TacticalProfile(Mentality.ATTACKING, "4-3-3",
                Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null);

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
        TimedTactics change = new TimedTactics(60, true, Mentality.ATTACKING, Pressing.HIGH,
                Marking.ZONAL, "4-3-3");
        boolean sawUserChange = false;
        for (long seed = 1; seed <= 20; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of(), List.of(change), false));
            sawUserChange |= t.events().stream().anyMatch(e ->
                    e.type() == EventType.TACTICS_CHANGE && e.home() && e.minute() == 60
                            && e.detail() == null);
            assertThat(t.events()).noneMatch(e -> e.home() && e.detail() != null);
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
            for (LiveTimelineEvent e : t.events()) {
                if (!e.home()) continue;
                switch (e.type()) {
                    case FREE_KICK_GOAL -> assertThat(e.playerId()).isEqualTo(freekickTaker.getId());
                    case CORNER_GOAL -> {
                        assertThat(e.playerInId()).isEqualTo(cornerTaker.getId());
                        assertThat(byId.get(e.playerId()).getPosition()).isNotEqualTo(Position.GK);
                    }
                    case PENALTY_GOAL, PENALTY_MISSED -> assertThat(e.playerId()).isEqualTo(penaltyTaker.getId());
                    default -> { }
                }
            }
        }
    }
}
