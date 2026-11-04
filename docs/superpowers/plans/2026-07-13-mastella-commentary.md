# Mateus Mastella-Style Commentary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite live-match commentary into Mateus Mastella's real voice — expand variant coverage across all 13 event categories, and give two of his real, verified catchphrases signature placement tied to real match-state triggers (a comeback goal, a 2-loss half-time streak) instead of random rotation.

**Architecture:** `LiveCommentary.java` stays a pure, stateless function (no DB access) but gains two caller-computed booleans (`comebackGoal`, `losingStreakHalfTime`). The one call site, `LiveMatchService.toState()`, gains two new small package-private static helpers to compute those booleans from the match's revealed event timeline and the managed club's recent fixture history. A small UI caption credits the narrator under the live commentary feed.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), React 19 / MUI / Vitest (frontend), JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-13-mastella-commentary-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task ends by committing its own tested work. `git push` is never run without an explicit user ask.
- The two signature lines are **verbatim quotes** — reproduce them character-for-character, exactly as given in every task below. Never alter, "improve", or re-punctuate them.
- No manufactured profanity anywhere else in the commentary — the one deliberate exception is the losing-streak line, which is a direct quote.
- Mateus Mastella's name is credited **only** in the small UI caption under the commentary feed (Task 5) — never inside commentary text itself.
- Determinism is preserved: for any non-signature line, the same `(minute, EventType)` pair always yields the same text (`Math.floorMod(minute + type.ordinal(), lines.length)`), exactly like today.
- Backend commands run from `backend/`: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` (long timeout, 300000-600000ms).
- Frontend commands run from `frontend/`: `npx tsc --noEmit && npx vitest run`.
- `LiveCommentary` and `EventType` live in package `br.com.carvoeiromanager.match` — no import needed for either from within that package.

---

### Task 1: `LiveCommentary` — new signature, pooled-variant structure, the two signature lines

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java` (full rewrite)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java:399-400`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`

**Interfaces:**
- Produces: `LiveCommentary.of(EventType type, String playerName, String inName, int minute, String detail, boolean comebackGoal, boolean losingStreakHalfTime)` — the new signature every later task and the one caller must use. Every array this task introduces (`GOAL`, `SET_PIECE`, `CHANCE`, `YELLOW`, `RED`, `INJURY`, `PENALTY_GOAL_LINES`, `PENALTY_MISS_LINES`, `FREE_KICK_LINES`, `CORNER_TWO_NAME`, `CORNER_ONE_NAME`, `SUBSTITUTION_LINES`, `HALF_TIME_LINES`, `TACTICS_USER_LINES`, `TACTICS_PUSH`, `TACTICS_ALL_OUT`, `TACTICS_PROTECT`, `TACTICS_PARK_THE_BUS`, `TACTICS_NORMAL`) is the exact name Task 2 appends new variants to — do not rename any of them.

This task is a **structural refactor with zero content change** (every array keeps its exact current single/double line(s) — Task 2 adds more variants later) plus the two brand-new signature lines. Every existing test's asserted TEXT stays identical; only the method signature changes.

- [ ] **Step 1: Replace `LiveCommentary.java` in full**

```java
package br.com.carvoeiromanager.match;

/** Deterministic PT-BR commentary. Variant index derives from the event so replay is stable. */
public final class LiveCommentary {

    private LiveCommentary() {
    }

    private static final String COMEBACK_GOAL_LINE = "Homens de pouca fé... homens de muita fé!";
    private static final String LOSING_STREAK_HALF_TIME_LINE =
            "E não adianta me ligar que eu vou mandar a merda.";

    private static final String[] GOAL = {
            "GOL! %s balança as redes!", "%s marca! Que golaço!", "%s aparece na área e não perdoa!"};
    private static final String[] SET_PIECE = {
            "GOL de bola parada! %s converte!", "%s marca na cobrança!"};
    private static final String[] CHANCE = {
            "%s arrisca e a bola passa perto!", "Grande defesa! %s quase marcou.",
            "%s finaliza, mas para fora!"};
    private static final String[] YELLOW = {"Cartão amarelo para %s.", "%s entra forte e é advertido."};
    private static final String[] RED = {"Cartão vermelho! %s está expulso!", "%s recebe o vermelho e deixa o time com um a menos."};
    private static final String[] INJURY = {"%s cai lesionado no gramado.", "Preocupação: %s sente e precisa de atendimento."};
    private static final String[] PENALTY_GOAL_LINES = {
            "Pênalti! %s converte com categoria!", "GOL DE PÊNALTI! %s não desperdiça!"};
    private static final String[] PENALTY_MISS_LINES = {
            "Pênalti perdido! %s desperdiça — defesa do goleiro!",
            "%s cobra o pênalti... e o goleiro defende!"};
    private static final String[] FREE_KICK_LINES = {
            "GOL DE FALTA! %s acerta o ângulo!", "Falta cobrada por %s... GOL!"};
    private static final String[] CORNER_TWO_NAME = {
            "Escanteio de %s, %s sobe e cabeceia: GOL!"};
    private static final String[] CORNER_ONE_NAME = {
            "GOL de escanteio! %s aparece na área e marca!"};
    private static final String[] SUBSTITUTION_LINES = {"Substituição: entra %s, sai %s."};
    private static final String[] HALF_TIME_LINES = {"Intervalo — fim do primeiro tempo."};
    private static final String[] TACTICS_USER_LINES = {"Mudança tática do treinador."};
    private static final String[] TACTICS_PUSH = {"O adversário se lança ao ataque."};
    private static final String[] TACTICS_ALL_OUT = {"Tudo ou nada do adversário: 4-2-4 ofensivo!"};
    private static final String[] TACTICS_PROTECT = {"O adversário recua para segurar o resultado."};
    private static final String[] TACTICS_PARK_THE_BUS = {"Adversário fecha-se na defesa: 5-4-1."};
    private static final String[] TACTICS_NORMAL = {"O adversário reorganiza a equipe."};

    /**
     * {@code inName} is only used for substitutions and corner goals (the corner taker).
     * {@code detail} only for TACTICS_CHANGE. {@code comebackGoal} true only for a goal-family
     * type that erases a deficit for the managed club — overrides the normal goal-family pool
     * with the signature line. {@code losingStreakHalfTime} true only at HALF_TIME when the
     * managed club has lost each of its last 2 completed fixtures — overrides the normal
     * half-time pool with the signature line.
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean losingStreakHalfTime) {
        if (comebackGoal && type.isGoal()) {
            return COMEBACK_GOAL_LINE;
        }
        if (type == EventType.HALF_TIME && losingStreakHalfTime) {
            return LOSING_STREAK_HALF_TIME_LINE;
        }
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> pick2(SUBSTITUTION_LINES, inName, playerName, minute, type);
            case TACTICS_CHANGE -> tacticsChange(detail, minute);
            case HALF_TIME -> pick0(HALF_TIME_LINES, minute, type);
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName, minute);
        };
    }

    /** Two-name line when the taker is known and distinct; single-name fallback otherwise. */
    private static String corner(String scorerName, String takerName, int minute) {
        if (takerName != null && !takerName.equals(scorerName)) {
            return pick2(CORNER_TWO_NAME, takerName, scorerName, minute, EventType.CORNER_GOAL);
        }
        return pick(CORNER_ONE_NAME, scorerName, minute, EventType.CORNER_GOAL);
    }

    /** Null detail = the user's own change (generic pool); otherwise an AI posture name. */
    private static String tacticsChange(String detail, int minute) {
        if (detail == null) return pick0(TACTICS_USER_LINES, minute, EventType.TACTICS_CHANGE);
        return switch (AiManagerBrain.Posture.valueOf(detail)) {
            case PUSH -> pick0(TACTICS_PUSH, minute, EventType.TACTICS_CHANGE);
            case ALL_OUT -> pick0(TACTICS_ALL_OUT, minute, EventType.TACTICS_CHANGE);
            case PROTECT -> pick0(TACTICS_PROTECT, minute, EventType.TACTICS_CHANGE);
            case PARK_THE_BUS -> pick0(TACTICS_PARK_THE_BUS, minute, EventType.TACTICS_CHANGE);
            case NORMAL -> pick0(TACTICS_NORMAL, minute, EventType.TACTICS_CHANGE);
        };
    }

    private static String pick(String[] lines, String name, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name);
    }

    private static String pick2(String[] lines, String name1, String name2, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name1, name2);
    }

    private static String pick0(String[] lines, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)];
    }
}
```

- [ ] **Step 2: Update the one production call site**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail()))).toList();
```

with:

```java
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        false, false /* comebackGoal, losingStreakHalfTime — wired in Tasks 3-4 */))).toList();
```

- [ ] **Step 3: Update every existing test call site and add the two new signature-line tests**

Replace the entire content of `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java` with:

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    @Test
    void userTacticsChangeKeepsTheGenericText() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null, false, false))
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
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, detail, false, false))
                .isEqualTo(expected);
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null, false, false))
                .isEqualTo("Substituição: entra Entra, sai Sai.");
    }

    @Test
    void penaltyGoalMissAndFreeKickLines() {
        // Variant index = minute + type.ordinal() mod array length; assert membership, not index math.
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false)).isIn(
                "Pênalti! Otero converte com categoria!", "GOL DE PÊNALTI! Otero não desperdiça!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null, false, false)).isIn(
                "Pênalti perdido! Otero desperdiça — defesa do goleiro!",
                "Otero cobra o pênalti... e o goleiro defende!");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null, false, false)).isIn(
                "GOL DE FALTA! Otero acerta o ângulo!", "Falta cobrada por Otero... GOL!");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null, false, false))
                .isEqualTo("Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null, false, false))
                .isEqualTo("GOL de escanteio! Zagueiro aparece na área e marca!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null, false, false))
                .isEqualTo("GOL de escanteio! Batedor aparece na área e marca!");
    }

    @Test
    void comebackGoalOverridesTheNormalGoalPoolForEveryGoalFamilyType() {
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, false))
                .isEqualTo("Homens de pouca fé... homens de muita fé!");
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, true, false))
                .isEqualTo("Homens de pouca fé... homens de muita fé!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, true, false))
                .isEqualTo("Homens de pouca fé... homens de muita fé!");
    }

    @Test
    void comebackGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, true, false))
                .isEqualTo("Cartão amarelo para Otero.");
    }

    @Test
    void losingStreakOverridesTheHalfTimeLine() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, true))
                .isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeWithoutAStreakKeepsTheGenericLine() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false))
                .isEqualTo("Intervalo — fim do primeiro tempo.");
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`
Expected: BUILD SUCCESSFUL, all tests pass (11 tests: the original 6 unchanged in behavior, plus 4 new signature-line tests — `aiTacticsChangeGetsPostureSpecificText` counts as 5 parameterized cases).

- [ ] **Step 5: Confirm the rest of the match package still compiles and passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`
Expected: BUILD SUCCESSFUL — `LiveMatchService.java`'s one call site now compiles against the new 7-arg signature.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java
git commit -m "$(cat <<'EOF'
feat(match): add Mateus Mastella's two signature commentary lines

LiveCommentary.of() gains comebackGoal/losingStreakHalfTime flags that
override the normal variant pool with a verbatim signature line. Every
category is also restructured into a pooled-variant array (still holding
today's exact text) so the next task can add variants without further
structural changes.
EOF
)"
```

---

### Task 2: Author 4-6 new variants per category (content only, no structural changes)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java` (array contents only)

**Interfaces:**
- Consumes: the 18 arrays and 3 pick helpers from Task 1 — array names must not change.
- Produces: the same arrays, each with more elements. No signature or method-shape changes.

This task is content authoring, not a technical decision — brief the implementer with the real research and let them write in voice, rather than pre-writing every line here.

- [ ] **Step 1: Dispatch with this brief**

````text
You are expanding a football-manager game's live-match commentary to sound like real Brazilian
narrator Mateus Mastella (Criciúma's actual radio/stream narrator). Work from repo root
<repo-root>.

CONTEXT ON HIS REAL STYLE (researched, not invented):
- Native of Criciúma, SC. Started narrating spontaneously from his father's living room; known
  for "raiz" (raw, grassroots, unfiltered) delivery — irreverent, emotionally passionate,
  fan-first ("de torcedor para torcedor" — from fan to fan, not neutral broadcaster distance).
  Source: https://radioararangua.com.br/mastella-o-narrador-que-comecou-na-sala-dos-pais-e-viralizou-na-argentina-espanha-e-italia/
- He is NOT a calm, professional-distance narrator. He gets loud, dramatic, sometimes
  overwhelmed with emotion — genuine exclamation, not manufactured hype.
- His two real signature catchphrases are ALREADY wired into this codebase with special
  condition-gated logic (do not touch or duplicate them in the regular pools):
  "Homens de pouca fé... homens de muita fé!" and
  "E não adianta me ligar que eu vou mandar a merda."

YOUR TASK: edit ONLY the array literals in
backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java — add new String
elements to each array listed below, written in Mastella's voice, in Brazilian Portuguese.
Do NOT change array names, the method signature, the switch statement, or any other structure.
Do NOT add profanity anywhere in this task (the one profane line already exists from Task 1 and
is out of scope here).

Each line must contain the SAME %s placeholder count and order as the array's existing line(s)
(one %s for player-name arrays, two %s in "first-arg, second-arg" order for two-name arrays,
zero %s for nameless arrays) — copy the existing line's placeholder shape exactly.

Add variants until each array reaches AT LEAST this many total elements (existing + new):

| Array | Placeholders | Target total elements |
|---|---|---|
| GOAL | 1 (%s) | 6 |
| CHANCE | 1 (%s) | 5 |
| YELLOW | 1 (%s) | 4 |
| RED | 1 (%s) | 4 |
| INJURY | 1 (%s) | 4 |
| PENALTY_GOAL_LINES | 1 (%s) | 4 |
| PENALTY_MISS_LINES | 1 (%s) | 4 |
| FREE_KICK_LINES | 1 (%s) | 4 |
| CORNER_TWO_NAME | 2 (%s, %s = taker, scorer) | 4 |
| CORNER_ONE_NAME | 1 (%s) | 4 |
| SUBSTITUTION_LINES | 2 (%s, %s = entering, leaving) | 4 |
| HALF_TIME_LINES | 0 | 4 |
| TACTICS_USER_LINES | 0 | 3 |
| TACTICS_PUSH | 0 | 3 |
| TACTICS_ALL_OUT | 0 | 3 |
| TACTICS_PROTECT | 0 | 3 |
| TACTICS_PARK_THE_BUS | 0 | 3 |
| TACTICS_NORMAL | 0 | 3 |

Do NOT touch `SET_PIECE` — it is legacy (never emitted by the current match engine, kept only
so old persisted rows don't break), expanding it has no player-visible effect.

Each array's new lines should escalate/vary in energy the way a real narrator would across a
season (not all lines maximally dramatic — some are matter-of-fact, some are explosive), but
every line must be clearly enthusiastic/passionate in tone, never flat or corporate.

VALIDATE before finishing: run
`cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew compileJava`
and confirm it compiles (catches any stray %s mismatch immediately, since a wrong placeholder
count throws a runtime MissingFormatArgumentException the first time that line is picked — but
compilation alone won't catch it; also grep each array's lines by hand against the placeholder
count in the table above before finishing).

RETURN: the final element count for every array in the table, confirming each meets its target.
````

- [ ] **Step 2: Verify every array meets its target count**

Run:
```bash
cd <repo-root>
grep -c '"' backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java
```
Then manually open the file and confirm each of the 18 arrays from the table has at least its
target element count (a quick visual count per array — the arrays are short, this is faster than
scripting a parser for one-off verification).

- [ ] **Step 3: Run the full match test package**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`
Expected: BUILD SUCCESSFUL. The `isIn(...)`-style membership tests in `LiveCommentaryTest`
(`penaltyGoalMissAndFreeKickLines`) assert membership in the ORIGINAL 2-line sets — since Task 2
only ADDS elements after the existing ones (never removes/reorders), those original 2 lines are
still valid members of the now-larger array, so membership assertions keep passing without
modification. Signature-line tests are unaffected (they never reach the pool).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java
git commit -m "$(cat <<'EOF'
feat(match): expand commentary variety across every category

New Mastella-voiced variants for goals, chances, cards, injuries,
substitutions, half-time, penalties, free kicks, corners, and every
AI tactics posture — for freshness across a season of matches.
EOF
)"
```

---

### Task 3: Comeback-goal detection

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java`

**Interfaces:**
- Consumes: `LiveCommentary.of(..., boolean comebackGoal, boolean losingStreakHalfTime)` (Task 1). `EventType.isGoal()` (already exists).
- Produces: `static boolean LiveMatchService.isComebackGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome)` — package-private, used by this task's wiring and directly by its unit test. Task 4 does not depend on this method.

- [ ] **Step 1: Write the failing unit test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java`:

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchServiceCommentaryTest {

    private LiveTimelineEvent goal(int minute, boolean home) {
        return new LiveTimelineEvent(minute, EventType.GOAL, home, UUID.randomUUID(), null, null, null);
    }

    @Test
    void aGoalThatErasesADeficitForTheManagedSideIsAComeback() {
        LiveTimelineEvent opponentGoal = goal(10, false);
        LiveTimelineEvent managedEqualizer = goal(30, true);
        List<LiveTimelineEvent> events = List.of(opponentGoal, managedEqualizer);

        assertThat(LiveMatchService.isComebackGoal(events, managedEqualizer, true)).isTrue();
    }

    @Test
    void aGoalThatExtendsALeadIsNotAComeback() {
        LiveTimelineEvent managedFirst = goal(10, true);
        LiveTimelineEvent managedSecond = goal(30, true);
        List<LiveTimelineEvent> events = List.of(managedFirst, managedSecond);

        assertThat(LiveMatchService.isComebackGoal(events, managedSecond, true)).isFalse();
    }

    @Test
    void anOpponentGoalIsNeverAComebackForTheManagedSide() {
        LiveTimelineEvent opponentFirst = goal(10, false);
        LiveTimelineEvent opponentSecond = goal(30, false);
        List<LiveTimelineEvent> events = List.of(opponentFirst, opponentSecond);

        assertThat(LiveMatchService.isComebackGoal(events, opponentSecond, true)).isFalse();
    }

    @Test
    void aNonGoalEventIsNeverAComeback() {
        LiveTimelineEvent card = new LiveTimelineEvent(10, EventType.YELLOW_CARD, true, UUID.randomUUID(), null, null, null);
        assertThat(LiveMatchService.isComebackGoal(List.of(card), card, true)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest'`
Expected: compile error — `isComebackGoal` does not exist yet on `LiveMatchService`.

- [ ] **Step 3: Add the helper to `LiveMatchService.java`**

Add this method anywhere in the class (e.g. immediately after the private `toState(LiveMatch, SaveGame, LiveMatchConfig, LiveTimeline)` method, around line 433):

```java
    /**
     * True only when {@code event} is a goal for the managed side that erases a deficit — the
     * managed club's goals strictly trailed the opponent's immediately before this event.
     */
    static boolean isComebackGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                } else {
                    opponentGoals++;
                }
            }
        }
        return managedGoals < opponentGoals;
    }
```

- [ ] **Step 4: Wire it into `toState()`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        false, false /* comebackGoal, losingStreakHalfTime — wired in Tasks 3-4 */))).toList();
```

with:

```java
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()),
                        false /* losingStreakHalfTime — wired in Task 4 */))).toList();
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest' --tests 'br.com.carvoeiromanager.match.*'`
Expected: BUILD SUCCESSFUL, all tests pass, including every pre-existing `LiveMatch*IT` test (this wiring change never fires `comebackGoal=true` unless a real deficit-erasing goal happens — existing tests don't assert exact commentary text for random matches, so they're unaffected).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java
git commit -m "$(cat <<'EOF'
feat(match): wire the comeback-goal signature line into live commentary

isComebackGoal() tracks the managed club's running goal count against
the event timeline to detect a deficit-erasing goal, without persisting
any new state — recomputed fresh from the timeline every time, same
"replay is stable" guarantee LiveCommentary already had.
EOF
)"
```

---

### Task 4: Losing-streak detection

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java`
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchLosingStreakIT.java`

**Interfaces:**
- Consumes: `LiveCommentary.of(...)` (Task 1). `Fixture.isPlayed()`, `Fixture.getHomeClubId()`, `Fixture.getAwayClubId()`, `Fixture.getHomeGoals()`, `Fixture.getAwayGoals()`, `Fixture.getMatchDate()`, `Fixture.getRound()` (all already exist).
- Produces: `FixtureRepository.findBySaveGameId(UUID)`. `static boolean LiveMatchService.isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId)` — package-private, pure, used by this task's unit test directly. `private boolean LiveMatchService.losingStreakHalfTime(UUID saveGameId, UUID managedClubId)` — the repository-querying wrapper, used only by `toState()`.

- [ ] **Step 1: Add the repository method**

In `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureRepository.java`, add inside the interface:

```java
    List<Fixture> findBySaveGameId(UUID saveGameId);
```

- [ ] **Step 2: Write the failing unit tests**

Append to `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java` (inside the existing class, after the comeback-goal tests from Task 3), and add the needed imports at the top of the file:

```java
import br.com.carvoeiromanager.competition.Fixture;
```

New tests:

```java
    @Test
    void twoConsecutiveLossesTriggersTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture mostRecent = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        mostRecent.recordResult(0, 1);
        Fixture secondMostRecent = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        secondMostRecent.recordResult(2, 0);

        assertThat(LiveMatchService.isLosingStreak(List.of(mostRecent, secondMostRecent), managedClubId)).isTrue();
    }

    @Test
    void aWinOrDrawMixedInBreaksTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture loss = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        loss.recordResult(0, 1);
        Fixture draw = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        draw.recordResult(1, 1);

        assertThat(LiveMatchService.isLosingStreak(List.of(loss, draw), managedClubId)).isFalse();
    }

    @Test
    void fewerThanTwoPlayedFixturesNeverTriggersTheStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture onlyOne = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        onlyOne.recordResult(0, 1);

        assertThat(LiveMatchService.isLosingStreak(List.of(onlyOne), managedClubId)).isFalse();
    }
```

- [ ] **Step 3: Run to see them fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest'`
Expected: compile error — `isLosingStreak` does not exist yet.

- [ ] **Step 4: Add the helpers to `LiveMatchService.java`**

Add these two methods next to `isComebackGoal` (Task 3):

```java
    /** True when {@code managedClubId}'s two most recent completed fixtures were both losses. */
    static boolean isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        if (mostRecentFirst.size() < 2) {
            return false;
        }
        return mostRecentFirst.stream().limit(2).allMatch(f -> lostBy(f, managedClubId));
    }

    private static boolean lostBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals < opponentGoals;
    }

    private boolean losingStreakHalfTime(UUID saveGameId, UUID managedClubId) {
        List<Fixture> recent = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .sorted(java.util.Comparator.comparing(Fixture::getMatchDate)
                        .thenComparing(Fixture::getRound).reversed())
                .toList();
        return isLosingStreak(recent, managedClubId);
    }
```

- [ ] **Step 5: Wire it into `toState()`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()),
                        false /* losingStreakHalfTime — wired in Task 4 */))).toList();
```

with:

```java
        boolean losingStreak = losingStreakHalfTime(lm.getSaveGameId(), save.getManagedClubId());
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()), losingStreak))).toList();
```

- [ ] **Step 6: Write the integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchLosingStreakIT.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchLosingStreakIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired FixtureRepository fixtureRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_streak_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    private UUID anOpponentClubId() {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(managedClubId))
                .findFirst().orElseThrow();
    }

    @Test
    void halfTimeShowsTheStreakLineAfterTwoConsecutiveLosses() {
        UUID opponentId = anOpponentClubId();
        Fixture olderLoss = Fixture.create(saveGameId, UUID.randomUUID(), 1, managedClubId, opponentId, null);
        olderLoss.recordResult(0, 2);
        fixtureRepository.save(olderLoss);
        Fixture recentLoss = Fixture.create(saveGameId, UUID.randomUUID(), 2, opponentId, managedClubId, null);
        recentLoss.recordResult(3, 1);
        fixtureRepository.save(recentLoss);

        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"HALF_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }

        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("HALF_TIME");
        LiveEventResponse halfTime = state.events().stream()
                .filter(e -> "HALF_TIME".equals(e.type())).findFirst().orElseThrow();
        assertThat(halfTime.text()).isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeKeepsTheGenericLineWithoutAStreak() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"HALF_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }

        assertThat(state).isNotNull();
        LiveEventResponse halfTime = state.events().stream()
                .filter(e -> "HALF_TIME".equals(e.type())).findFirst().orElseThrow();
        assertThat(halfTime.text()).isEqualTo("Intervalo — fim do primeiro tempo.");
    }
}
```

- [ ] **Step 7: Run everything**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*' --tests 'br.com.carvoeiromanager.competition.*'`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/FixtureRepository.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchLosingStreakIT.java
git commit -m "$(cat <<'EOF'
feat(match): wire the losing-streak signature line into half-time

isLosingStreak() checks the managed club's two most recent completed
fixtures (any competition, by match date) — both losses fires the
signature line at half-time instead of the generic one.
EOF
)"
```

---

### Task 5: Attribution caption on the live commentary feed

**Files:**
- Modify: `frontend/src/features/match/LiveMatchPage.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Consumes: nothing from earlier tasks (frontend, independent of the backend changes — the commentary text itself already flows through unchanged existing props).
- Produces: nothing consumed elsewhere — this is the final task.

- [ ] **Step 1: Write the failing test**

In `frontend/src/features/match/LiveMatchPage.test.tsx`, add this test inside the `describe('LiveMatchPage', ...)` block, after the existing `'shows the score and the commentary feed'` test:

```tsx
  it('credits Mateus Mastella under the commentary feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/Narração no estilo de Mateus Mastella/)).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: FAIL — the caption text is not in the document yet.

- [ ] **Step 3: Add the caption**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
      {feedTab === 0 && (
        <Stack spacing={0.5} sx={{ mt: 1 }}>
          {feed.map((e, i) => (
```

with:

```tsx
      {feedTab === 0 && (
        <Stack spacing={0.5} sx={{ mt: 1 }}>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Narração no estilo de Mateus Mastella
          </Typography>
          {feed.map((e, i) => (
```

- [ ] **Step 4: Run the tests and typecheck**

Run: `cd frontend && npx tsc --noEmit && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: tsc clean; all `LiveMatchPage.test.tsx` tests pass, including the new one.

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: all tests pass — this caption doesn't change any existing DOM structure other tests key off.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/match/LiveMatchPage.tsx frontend/src/features/match/LiveMatchPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(match): credit Mateus Mastella under the live commentary feed

Small caption on the Lances tab only — his name never appears inside
commentary text itself, per the design's attribution scoping.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** signature-line wording and triggers (Task 1, 3, 4), regular-category expansion (Task 2), CRI-only scope (no task needed — already true structurally, confirmed in the spec's Background and unchanged by this plan), attribution caption (Task 5), determinism preserved (Task 1's `pick`/`pick2`/`pick0` all keep the exact `Math.floorMod` mechanism). Every spec section maps to a task.
- **Verified, not assumed:** every method name, field accessor, and constructor signature referenced in this plan (`EventType.isGoal()`, `LiveTimelineEvent`'s record shape, `Fixture.recordResult()`/`isPlayed()`/`getMatchDate()`, `SaveGame.getManagedClubId()`, `LiveMatchState.events()`, `LiveEventResponse.text()`) was read directly from the current source during plan-writing, not guessed. The "every LiveMatch is inherently a Criciúma match" claim from the spec was independently re-confirmed by reading `LiveMatch.isManagedIsHome()`.
- **Known judgment call:** Task 2's content isn't pre-written in this plan — per this project's established pattern for creative/research tasks (the Catarinense data-realism plan), a detailed brief with real research citations and hard structural constraints (exact array names, exact placeholder counts, minimum counts) is handed to the implementer instead, since the actual line text requires creative judgment during execution, not a plan-time decision.
- **Type consistency:** `LiveCommentary.of()`'s 7-arg signature is used identically across Tasks 1, 3, and 4's edits to the same call site (`LiveMatchService.java`) — each task's "replace X with Y" step's `X` textually matches the prior task's `Y`, so the tasks apply cleanly in sequence.
