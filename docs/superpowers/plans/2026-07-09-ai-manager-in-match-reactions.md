# AI Manager In-Match Reactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The AI opponent in the user's live match deterministically adapts mentality/pressing/formation to score, minute, and red cards, with posture-specific pt-BR commentary in the match feed.

**Architecture:** A pure, RNG-free `AiManagerBrain` (static decision function → `Posture`) is evaluated inside `LiveMatchEngine`'s minute loop for the non-managed side; posture changes swap `side.currentProfile` through the existing dirty-flag strength recompute and emit a `TACTICS_CHANGE` timeline event carrying the posture in a new `detail` field, which `LiveCommentary` maps to posture-specific text. No new persisted state, no migration — the timeline stays a pure function of `LiveMatchConfig`.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 + AssertJ (backend only; no frontend changes).

**Spec:** `docs/superpowers/specs/2026-07-09-ai-manager-in-match-reactions-design.md`

## Global Constraints

- Gradle: run from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew …`. Never run two gradle invocations concurrently (build-dir collisions produce false failures).
- Commit locally on `main` after each task. NEVER `git push`.
- `AiManagerBrain` must be pure and RNG-free: no `Random`, no clock, no Spring dependencies. Determinism of the live-match timeline (pure function of `LiveMatchConfig`) must be preserved.
- Reactions only from minute **46** (minute 1–45 always `NORMAL`, regardless of score or red cards). Every offset-adjusted threshold clamps to a minimum of 46.
- Scope: live-match engine only. `MatchSimulationService` / `FixtureSimulator` (instant AI-vs-AI engine) must remain byte-for-byte untouched.
- pt-BR commentary strings, verbatim:
  - user change (detail null): `Mudança tática do treinador.` (unchanged)
  - PUSH: `O adversário se lança ao ataque.`
  - ALL_OUT: `Tudo ou nada do adversário: 4-2-4 ofensivo!`
  - PROTECT: `O adversário recua para segurar o resultado.`
  - PARK_THE_BUS: `Adversário fecha-se na defesa: 5-4-1.`
  - NORMAL (de-escalation): `O adversário reorganiza a equipe.`
- `TacticalProfile.formation` is a display-code **String** (`"4-2-4"`, `"5-4-1"`), not the `Formation` enum.

---

### Task 1: AiManagerBrain (pure posture engine)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/AiManagerBrain.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/AiManagerBrainTest.java`

**Interfaces:**
- Consumes: `br.com.carvoeiromanager.player.Mentality` (DEFENSIVE/BALANCED/ATTACKING), `br.com.carvoeiromanager.player.Pressing` (LOW/MEDIUM/HIGH), `match/TacticalProfile` (record: `Mentality mentality, String formation, Pressing pressing, Marking marking, Map<UUID,PlayerRole> roles, UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId`).
- Produces (Task 2 relies on these exact signatures):
  - `AiManagerBrain.Posture` — nested `public enum Posture { PARK_THE_BUS, PROTECT, NORMAL, PUSH, ALL_OUT }`
  - `public static Posture decide(Mentality identityMentality, int scoreDiff, int minute, boolean downAMan)`
  - `public static TacticalProfile profileFor(Posture posture, TacticalProfile identity)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/AiManagerBrainTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiManagerBrainTest {

    // ---- decide(): the full posture matrix. Column order: identity, scoreDiff, minute, expected.
    // Thresholds: chasing base PUSH/ALL_OUT = 46/70 (losing 2+) and 60/80 (losing 1);
    // protecting base PROTECT/PARK = 75/85 (winning 1) and PROTECT 80 (winning 2+).
    // Offsets: chase ATTACKING -10 / DEFENSIVE +10; protect ATTACKING +5 / DEFENSIVE -10.
    // Every effective threshold clamps to minute >= 46.
    @ParameterizedTest
    @CsvSource({
            // first-half lockout: always NORMAL before 46, whatever the situation
            "BALANCED,   -2, 45, NORMAL",
            "ATTACKING,  -2, 45, NORMAL",
            "DEFENSIVE,   2, 45, NORMAL",
            // losing by 2+ — BALANCED: PUSH from 46, ALL_OUT from 70
            "BALANCED,   -2, 46, PUSH",
            "BALANCED,   -2, 69, PUSH",
            "BALANCED,   -2, 70, ALL_OUT",
            "BALANCED,   -3, 70, ALL_OUT",
            // losing by 2+ — ATTACKING (chase -10, clamped): PUSH from 46 (36->46), ALL_OUT from 60
            "ATTACKING,  -2, 46, PUSH",
            "ATTACKING,  -2, 59, PUSH",
            "ATTACKING,  -2, 60, ALL_OUT",
            // losing by 2+ — DEFENSIVE (chase +10): PUSH from 56, ALL_OUT from 80
            "DEFENSIVE,  -2, 55, NORMAL",
            "DEFENSIVE,  -2, 56, PUSH",
            "DEFENSIVE,  -2, 79, PUSH",
            "DEFENSIVE,  -2, 80, ALL_OUT",
            // losing by 1 — BALANCED: PUSH from 60, ALL_OUT from 80
            "BALANCED,   -1, 59, NORMAL",
            "BALANCED,   -1, 60, PUSH",
            "BALANCED,   -1, 79, PUSH",
            "BALANCED,   -1, 80, ALL_OUT",
            // losing by 1 — ATTACKING: PUSH from 50, ALL_OUT from 70
            "ATTACKING,  -1, 49, NORMAL",
            "ATTACKING,  -1, 50, PUSH",
            "ATTACKING,  -1, 70, ALL_OUT",
            // losing by 1 — DEFENSIVE: PUSH from 70, ALL_OUT from 90
            "DEFENSIVE,  -1, 69, NORMAL",
            "DEFENSIVE,  -1, 70, PUSH",
            "DEFENSIVE,  -1, 89, PUSH",
            "DEFENSIVE,  -1, 90, ALL_OUT",
            // drawing — identity-specific fixed rules (no offsets)
            "BALANCED,    0, 90, NORMAL",
            "ATTACKING,   0, 74, NORMAL",
            "ATTACKING,   0, 75, PUSH",
            "DEFENSIVE,   0, 84, NORMAL",
            "DEFENSIVE,   0, 85, PROTECT",
            // winning by 1 — BALANCED: PROTECT from 75, PARK from 85
            "BALANCED,    1, 74, NORMAL",
            "BALANCED,    1, 75, PROTECT",
            "BALANCED,    1, 84, PROTECT",
            "BALANCED,    1, 85, PARK_THE_BUS",
            // winning by 1 — ATTACKING (protect +5): PROTECT from 80, PARK from 90
            "ATTACKING,   1, 79, NORMAL",
            "ATTACKING,   1, 80, PROTECT",
            "ATTACKING,   1, 89, PROTECT",
            "ATTACKING,   1, 90, PARK_THE_BUS",
            // winning by 1 — DEFENSIVE (protect -10): PROTECT from 65, PARK from 75
            "DEFENSIVE,   1, 64, NORMAL",
            "DEFENSIVE,   1, 65, PROTECT",
            "DEFENSIVE,   1, 75, PARK_THE_BUS",
            // winning by 2+: PROTECT from 80 (+offset), never parks the bus
            "BALANCED,    2, 79, NORMAL",
            "BALANCED,    2, 80, PROTECT",
            "BALANCED,    3, 90, PROTECT",
            "ATTACKING,   2, 84, NORMAL",
            "ATTACKING,   2, 85, PROTECT",
            "DEFENSIVE,   2, 70, PROTECT",
    })
    void postureMatrix(Mentality identity, int scoreDiff, int minute, Posture expected) {
        assertThat(AiManagerBrain.decide(identity, scoreDiff, minute, false)).isEqualTo(expected);
    }

    // ---- decide(): red-card (downAMan) rules
    @ParameterizedTest
    @CsvSource({
            // not losing + down a man -> PROTECT immediately; PARK from clamp(75 + protectOffset) when winning
            "BALANCED,    0, 46, PROTECT",
            "ATTACKING,   0, 80, PROTECT",      // overrides the drawing ATTACKING PUSH-from-75 rule
            "DEFENSIVE,   0, 90, PROTECT",
            "BALANCED,    1, 46, PROTECT",
            "BALANCED,    1, 74, PROTECT",
            "BALANCED,    1, 75, PARK_THE_BUS",
            "BALANCED,    2, 75, PARK_THE_BUS", // winning by 2 down a man DOES park (unlike 11-a-side)
            "ATTACKING,   1, 79, PROTECT",
            "ATTACKING,   1, 80, PARK_THE_BUS", // 75 + 5
            "DEFENSIVE,   1, 65, PARK_THE_BUS", // 75 - 10
            // losing + down a man -> the chase is unaffected
            "BALANCED,   -1, 60, PUSH",
            "BALANCED,   -2, 70, ALL_OUT",
            // first half + down a man -> still NORMAL
            "BALANCED,    1, 45, NORMAL",
    })
    void redCardRules(Mentality identity, int scoreDiff, int minute, Posture expected) {
        assertThat(AiManagerBrain.decide(identity, scoreDiff, minute, true)).isEqualTo(expected);
    }

    // ---- profileFor(): overrides on top of identity; marking/roles/takers always preserved
    private static final Map<UUID, PlayerRole> ROLES =
            Map.of(UUID.randomUUID(), PlayerRole.defaultFor(Position.MID));
    private static final UUID PEN = UUID.randomUUID();
    private static final UUID FK = UUID.randomUUID();
    private static final UUID CORNER = UUID.randomUUID();
    private static final TacticalProfile IDENTITY = new TacticalProfile(
            Mentality.BALANCED, "3-5-2", Pressing.MEDIUM, Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER);

    @Test
    void normalReturnsTheIdentityUnchanged() {
        assertThat(AiManagerBrain.profileFor(Posture.NORMAL, IDENTITY)).isEqualTo(IDENTITY);
    }

    @Test
    void pushOnlyRaisesMentality() {
        assertThat(AiManagerBrain.profileFor(Posture.PUSH, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.ATTACKING, "3-5-2", Pressing.MEDIUM, Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER));
    }

    @Test
    void allOutGoesAttackingHighPressFourTwoFour() {
        assertThat(AiManagerBrain.profileFor(Posture.ALL_OUT, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.ATTACKING, "4-2-4", Pressing.HIGH, Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER));
    }

    @Test
    void protectOnlyLowersMentality() {
        assertThat(AiManagerBrain.profileFor(Posture.PROTECT, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.DEFENSIVE, "3-5-2", Pressing.MEDIUM, Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER));
    }

    @Test
    void parkTheBusGoesDefensiveLowPressFiveFourOne() {
        assertThat(AiManagerBrain.profileFor(Posture.PARK_THE_BUS, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.DEFENSIVE, "5-4-1", Pressing.LOW, Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `backend/`):
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.AiManagerBrainTest"
```
Expected: compilation FAILURE — `AiManagerBrain` does not exist.

- [ ] **Step 3: Implement AiManagerBrain**

`backend/src/main/java/br/com/carvoeiromanager/match/AiManagerBrain.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Pressing;

/**
 * Deterministic, RNG-free "manager brain" for the AI side of a live match. Decides a
 * {@link Posture} from score difference (AI's perspective; negative = losing), minute, and
 * whether the AI side is down a man — shaped by the club's tactical-identity mentality
 * (attacking clubs chase 10' earlier and protect 5' later; defensive clubs the reverse).
 *
 * <p>Memoryless by design: the same inputs always yield the same posture, so the engine can
 * re-evaluate every minute during its deterministic re-simulation and the AI's reactions replay
 * identically. If the score swings back, the posture de-escalates naturally.
 *
 * <p>No reactions in the first half: minute 1-45 is always {@code NORMAL}, and every
 * offset-adjusted threshold clamps to minute 46.
 */
public final class AiManagerBrain {

    /** How the AI manager currently wants to play, from most defensive to most attacking. */
    public enum Posture { PARK_THE_BUS, PROTECT, NORMAL, PUSH, ALL_OUT }

    private static final int SECOND_HALF_START = 46;

    private AiManagerBrain() {
    }

    public static Posture decide(Mentality identityMentality, int scoreDiff, int minute, boolean downAMan) {
        if (minute < SECOND_HALF_START) return Posture.NORMAL;
        int chase = chaseOffset(identityMentality);
        int protect = protectOffset(identityMentality);

        if (scoreDiff <= -2) {
            // A red card never stops the chase.
            if (minute >= clamp(70 + chase)) return Posture.ALL_OUT;
            if (minute >= clamp(46 + chase)) return Posture.PUSH;
            return Posture.NORMAL;
        }
        if (scoreDiff == -1) {
            if (minute >= clamp(80 + chase)) return Posture.ALL_OUT;
            if (minute >= clamp(60 + chase)) return Posture.PUSH;
            return Posture.NORMAL;
        }
        if (scoreDiff == 0) {
            if (downAMan) return Posture.PROTECT;
            // Identity-specific fixed rules — the identity already expresses the offset here.
            if (identityMentality == Mentality.ATTACKING && minute >= 75) return Posture.PUSH;
            if (identityMentality == Mentality.DEFENSIVE && minute >= 85) return Posture.PROTECT;
            return Posture.NORMAL;
        }
        // Winning.
        if (downAMan) {
            return minute >= clamp(75 + protect) ? Posture.PARK_THE_BUS : Posture.PROTECT;
        }
        if (scoreDiff == 1) {
            if (minute >= clamp(85 + protect)) return Posture.PARK_THE_BUS;
            if (minute >= clamp(75 + protect)) return Posture.PROTECT;
            return Posture.NORMAL;
        }
        // A two-goal cushion shuts the game down late but never parks the bus.
        return minute >= clamp(80 + protect) ? Posture.PROTECT : Posture.NORMAL;
    }

    /**
     * Maps a posture onto the club's identity profile. Only mentality/pressing/formation are
     * overridden; marking, per-player roles and set-piece takers are always the identity's —
     * the same swap discipline as a user tactics change.
     */
    public static TacticalProfile profileFor(Posture posture, TacticalProfile identity) {
        return switch (posture) {
            case NORMAL -> identity;
            case PUSH -> with(identity, Mentality.ATTACKING, identity.pressing(), identity.formation());
            case ALL_OUT -> with(identity, Mentality.ATTACKING, Pressing.HIGH, "4-2-4");
            case PROTECT -> with(identity, Mentality.DEFENSIVE, identity.pressing(), identity.formation());
            case PARK_THE_BUS -> with(identity, Mentality.DEFENSIVE, Pressing.LOW, "5-4-1");
        };
    }

    private static TacticalProfile with(TacticalProfile identity, Mentality mentality,
                                        Pressing pressing, String formation) {
        return new TacticalProfile(mentality, formation, pressing, identity.marking(),
                identity.roles(), identity.penaltyTakerId(), identity.freekickTakerId(),
                identity.cornerTakerId());
    }

    private static int chaseOffset(Mentality identity) {
        return switch (identity) {
            case ATTACKING -> -10;
            case BALANCED -> 0;
            case DEFENSIVE -> 10;
        };
    }

    private static int protectOffset(Mentality identity) {
        return switch (identity) {
            case ATTACKING -> 5;
            case BALANCED -> 0;
            case DEFENSIVE -> -10;
        };
    }

    private static int clamp(int thresholdMinute) {
        return Math.max(SECOND_HALF_START, thresholdMinute);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.AiManagerBrainTest"
```
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/AiManagerBrain.java backend/src/test/java/br/com/carvoeiromanager/match/AiManagerBrainTest.java
git commit -m "feat(match): AiManagerBrain — deterministic AI posture decisions"
```

---

### Task 2: Engine integration, config/event plumbing, commentary

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java` (add 11th component `Boolean aiHome`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java` (add 7th component `String detail`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java` (running score, red-card flag, AI evaluation hook, `detail` on all event constructions)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java` (`detail` parameter, posture-specific TACTICS_CHANGE texts)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java:356` (`configFor` passes `!lm.isManagedIsHome()`) and `:382` (`toState` passes `e.detail()` to `LiveCommentary.of`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java` (extend), create `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`

**Interfaces:**
- Consumes (from Task 1): `AiManagerBrain.Posture`, `AiManagerBrain.decide(Mentality, int, int, boolean)`, `AiManagerBrain.profileFor(Posture, TacticalProfile)`.
- Produces: `LiveMatchConfig(long seed, List<Player> homeXi, List<Player> awayXi, List<Player> homeBench, List<Player> awayBench, TacticalProfile homeProfile, TacticalProfile awayProfile, Map<UUID,Integer> fitness, List<LiveSub> subSchedule, List<TimedTactics> tacticsSchedule, Boolean aiHome)`; `LiveTimelineEvent(int minute, EventType type, boolean home, UUID playerId, UUID playerInId, String severity, String detail)`; `LiveCommentary.of(EventType type, String playerName, String inName, int minute, String detail)`. `LiveEventResponse` (wire type) is UNCHANGED — no frontend work.

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`:

1. Update the existing `config(...)` factories to pass `null` as the new final `aiHome` argument (existing tests must stay green with AI reactions disabled), and add an AI-enabled factory plus a mismatch fixture. Replace the two existing factory methods with:

```java
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
```

2. Append these tests (imports to add: `br.com.carvoeiromanager.match.AiManagerBrain.Posture`):

```java
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
                    && (e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)).count();
            int awayGoals = (int) t.events().stream().filter(e -> !e.home() && e.minute() < m
                    && (e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)).count();
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
```

3. Create `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`:

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    @Test
    void userTacticsChangeKeepsTheGenericText() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null))
                .isEqualTo("Mudança tática do treinador.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "PUSH;O adversário se lança ao ataque.",
            "ALL_OUT;Tudo ou nada do adversário: 4-2-4 ofensivo!",
            "PROTECT;O adversário recua para segurar o resultado.",
            "PARK_THE_BUS;Adversário fecha-se na defesa: 5-4-1.",
            "NORMAL;O adversário reorganiza a equipe.",
    })
    void aiTacticsChangeGetsPostureSpecificText(String detail, String expected) {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, detail))
                .isEqualTo(expected);
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null))
                .isEqualTo("Substituição: entra Entra, sai Sai.");
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest" --tests "br.com.carvoeiromanager.match.LiveCommentaryTest"
```
Expected: compilation FAILURE (`LiveMatchConfig` has no 11-arg constructor; `LiveTimelineEvent` has no `detail()`; `LiveCommentary.of` has no 5-arg overload).

- [ ] **Step 3: Implement the plumbing and the engine hook**

3a. `LiveMatchConfig.java` — add the component and document it (append to the existing javadoc):

```java
/**
 * Immutable inputs for a full live-match simulation. {@code fitness} is the effective fitness at
 * kickoff for EVERY player who might appear (both XIs and both benches). {@code subSchedule} is the
 * ordered list of substitutions applied so far; the timeline is a pure function of these inputs.
 * {@code tacticsSchedule} is the ordered list of in-match tactics changes; an empty list means the
 * starting tactical profiles are held constant for the full 90 minutes (backward-compatible default).
 * {@code aiHome} names the side under AI-manager control ({@code true} = home) so the engine can
 * produce score-reactive tactics changes for it; {@code null} disables AI reactions entirely.
 */
public record LiveMatchConfig(
        long seed,
        List<Player> homeXi,
        List<Player> awayXi,
        List<Player> homeBench,
        List<Player> awayBench,
        TacticalProfile homeProfile,
        TacticalProfile awayProfile,
        Map<UUID, Integer> fitness,
        List<LiveSub> subSchedule,
        List<TimedTactics> tacticsSchedule,
        Boolean aiHome) {
}
```

3b. `LiveTimelineEvent.java` — add the component and document it:

```java
/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF).
 * {@code playerInId} is the player coming ON — only for {@code SUBSTITUTION}, else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null. {@code detail} is the {@link AiManagerBrain.Posture} name — only for
 * AI-produced {@code TACTICS_CHANGE}, else null (a null detail on TACTICS_CHANGE means the user's
 * own change).
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity,
        String detail) {
}
```

3c. `LiveMatchEngine.java` changes:

- `Side` gains two fields (below `currentProfile`):

```java
        boolean redCarded = false;                         // true once this side loses a player
        AiManagerBrain.Posture posture = AiManagerBrain.Posture.NORMAL; // AI-manager state (AI side only)
```

- In `simulate`, after the `away.currentProfile = cfg.awayProfile();` line, resolve the AI side:

```java
        // AI-manager reactions: which side (if any) re-evaluates its posture each second-half minute.
        Side aiSide = cfg.aiHome() == null ? null : (cfg.aiHome() ? home : away);
        TacticalProfile aiIdentity = cfg.aiHome() == null ? null
                : (cfg.aiHome() ? cfg.homeProfile() : cfg.awayProfile());
```

- In `simulate`, declare running score counters before the loop (`List<TimedInjury> injuries...` block):

```java
        int homeGoals = 0;
        int awayGoals = 0;
```

- In the minute loop, insert the AI hook between the `applySubs` calls and the dirty-recompute lines:

```java
            if (aiSide != null) {
                int scoreDiff = aiSide.home ? homeGoals - awayGoals : awayGoals - homeGoals;
                reactAiManager(minute, aiSide, aiIdentity, scoreDiff, events);
            }
```

- Increment the counters where goal events are appended (both branches):

```java
            if (rng.nextDouble() < homeChance) {
                EventType t = rng.nextDouble() < setPiece(homeInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, home, home.currentProfile, t, attrRng));
                homeGoals++;
            }
            if (rng.nextDouble() < awayChance) {
                EventType t = rng.nextDouble() < setPiece(awayInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, away, away.currentProfile, t, attrRng));
                awayGoals++;
            }
```

(The post-loop `hg`/`ag` stream tally stays as-is — the counters exist for the hook, not the result.)

- New private method (place after `applyTacticChanges`):

```java
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
```

(Note: `AiManagerBrain.decide` returns `NORMAL` for `minute < 46`, and `ai.posture` starts at `NORMAL`, so no explicit first-half guard is needed here — no event can fire before 46.)

- In `maybeCard`, set the flag inside the red-card branch:

```java
            if (t == EventType.RED_CARD) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.redCarded = true;
                side.dirty = true;
            }
```

- Update ALL 8 existing `new LiveTimelineEvent(...)` constructions in the engine to pass `null` as the new final `detail` argument (goal(), maybeCard, maybeInjury, applySubs, applyTacticChanges, the two CHANCE events, and the HALF_TIME marker).

3d. `LiveCommentary.java` — new parameter and posture texts:

```java
    /** {@code inName} is only used for substitutions; {@code detail} only for TACTICS_CHANGE. */
    public static String of(EventType type, String playerName, String inName, int minute, String detail) {
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> "Substituição: entra %s, sai %s.".formatted(inName, playerName);
            case TACTICS_CHANGE -> tacticsChange(detail);
            case HALF_TIME -> "Intervalo — fim do primeiro tempo.";
        };
    }

    /** Null detail = the user's own change (generic text); otherwise an AI posture name. */
    private static String tacticsChange(String detail) {
        if (detail == null) return "Mudança tática do treinador.";
        return switch (AiManagerBrain.Posture.valueOf(detail)) {
            case PUSH -> "O adversário se lança ao ataque.";
            case ALL_OUT -> "Tudo ou nada do adversário: 4-2-4 ofensivo!";
            case PROTECT -> "O adversário recua para segurar o resultado.";
            case PARK_THE_BUS -> "Adversário fecha-se na defesa: 5-4-1.";
            case NORMAL -> "O adversário reorganiza a equipe.";
        };
    }
```

3e. `LiveMatchService.java` — two one-line changes:

- `configFor` (line ~356): the AI side is the non-managed one:

```java
        return new LiveMatchConfig(lm.getSeed(), homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs, tacticsSchedule, !lm.isManagedIsHome());
```

- `toState` event mapping (line ~382): pass the detail through:

```java
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail()))).toList();
```

- [ ] **Step 4: Run the targeted tests, then the whole match package**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest" --tests "br.com.carvoeiromanager.match.LiveCommentaryTest" --tests "br.com.carvoeiromanager.match.AiManagerBrainTest"
```
Expected: BUILD SUCCESSFUL. Then the full match package (live-match ITs now run WITH AI reactions enabled via `configFor` — they must stay green; they assert flow/user events, not exact scores):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL. If a live-match IT fails on an assumption like "the only TACTICS_CHANGE events are the user's", fix the TEST to filter by side/detail (`e.home() == managedIsHome` or `detail == null`) — the production behavior is the feature.

- [ ] **Step 5: Full backend suite**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL (~430+ tests). Known pre-existing flakes were fixed in `334794e`; a failure in an unrelated package should be re-run once before investigating.

- [ ] **Step 6: Commit**

```bash
git add -A backend
git commit -m "feat(match): AI manager in-match reactions — opponent adapts tactics to the score"
```

---

## Verification (end-to-end sanity, after both tasks)

Start a live match in the UI, take a lead (or fall behind), and advance into the second half: the "Lances" feed should show 📋 entries with posture-specific pt-BR text for the opponent at sensible minutes (e.g. trailing opponent: "O adversário se lança ao ataque." around 60'; late: "Tudo ou nada do adversário: 4-2-4 ofensivo!"). The user's own tactic changes still read "Mudança tática do treinador.". No frontend build needed — `LiveEventResponse` is unchanged.
