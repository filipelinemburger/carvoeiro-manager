# Distinct Set-Piece Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the unified `SET_PIECE_GOAL` into corner/free-kick/penalty events with taker-skill-driven probabilities, type-correct takers, a penalty conversion roll (misses possible), and type-specific pt-BR commentary — in both match engines.

**Architecture:** A new pure `SetPieces` utility holds the shared math (share, subtype roll, conversion, header weight, taker-or-fallback). Consumers are extended FIRST (Task 2: enum + every switch/predicate handles the new types while nothing emits them yet), then the producers switch over (Task 3: `TeamMatchInput` reshaped with `cornerThreat`/`freeKickThreat`/`penaltyConversion`, both engines emit the new types). `SET_PIECE_GOAL` stays as a never-again-emitted legacy value so old persisted `match_events` rows keep counting.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 + AssertJ (backend); TypeScript/React (two-file frontend touch).

**Spec:** `docs/superpowers/specs/2026-07-09-distinct-set-piece-events-design.md`

## Global Constraints

- Gradle: run from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew …`, in the foreground; never two gradle invocations concurrently. Frontend: `cd frontend && npx tsc --noEmit && npx vitest run`.
- Commit locally on `main` after each task. NEVER `git push`.
- New enum values are APPENDED at the end of `EventType` (existing ordinals feed `LiveCommentary.pick` variant selection — they must not shift).
- `SET_PIECE_GOAL` remains in the enum, counts as a goal everywhere, and is NEVER emitted after Task 3.
- `PENALTY_MISSED` is NOT a goal: excluded from scores and `match_events` persistence; it reaches ONLY the live feed and the live rating path.
- Probability/skill formulas, verbatim from the spec: share = `clamp(0.18 × (0.5 + (cornerThreat + freeKickThreat)/2), 0.0, 0.6)`; subtype weights CORNER `50 × (0.5 + cornerThreat)`, FREE_KICK `30 × (0.5 + freeKickThreat)`, PENALTY `20` fixed; conversion = `0.70 + 0.25 × (Bola parada / 99)`; corner header weight = positionFactor (ATT 10 / DEF 8 / MID 4 / GK 0) × (Cabeceio + 10).
- Taker fallback rule (spec refinement, applies everywhere): designated taker if present in the pool; otherwise the FIRST player in pool order with the maximal fallback attribute, **skipping goalkeepers** (a GK is used only if the pool is all GKs). A user-designated GK taker id is honored as-is.
- pt-BR commentary strings, verbatim (see Task 2 Step 3 for the full set — e.g. `Pênalti! %s converte com categoria!`, corner two-name `Escanteio de %s, %s sobe e cabeceia: GOL!`).
- `TacticalProfile.formation` and RNG stream discipline: subtype/conversion rolls draw from the match stream (`rng`), scorer picks from the attribution stream (`attrRng`). Timelines stay pure functions of config; cross-version timeline changes for existing seeds are accepted.
- `LiveEventResponse` wire shape is unchanged; frontend changes are ONLY `api/types.ts` (union) and `LiveMatchPage.tsx` (`EVENT_ICON`).

---

### Task 1: `SetPieces` shared math utility

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/SetPieces.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SetPiecesTest.java`

**Interfaces:**
- Consumes: `player/Player` (`getId`, `getPosition`, `attribute(Attribute)`), `player/Attribute` (`CABECEIO`), `player/Position`.
- Produces (Tasks 2–3 rely on these exact signatures):
  - `SetPieces.Kind` — nested `public enum Kind { CORNER, FREE_KICK, PENALTY }`
  - `public static double share(double cornerThreat, double freeKickThreat)`
  - `public static Kind roll(double cornerThreat, double freeKickThreat, Random rng)` (exactly ONE `rng.nextDouble()` draw)
  - `public static double conversionChance(int bolaParada)`
  - `public static int headerWeight(Player p)`
  - `public static Player takerOrFallback(List<Player> pool, UUID takerId, ToIntFunction<Player> fallbackAttribute)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/SetPiecesTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SetPiecesTest {

    /** A stub RNG that returns a fixed sequence of doubles — lets us hit exact roll bands. */
    private static Random fixed(double value) {
        return new Random() {
            @Override public double nextDouble() { return value; }
        };
    }

    private static Player player(Position pos, Attribute attr, int value) {
        Attributes attributes = Attributes.builder().set(attr, value).build();
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T-" + pos, pos, 25, "BRA",
                attributes, 70);
    }

    // ---- share(): same scale as the old single-threat formula, clamped
    @Test
    void shareSpansNineToTwentySevenPercent() {
        assertThat(SetPieces.share(0.0, 0.0)).isEqualTo(0.09, within(1e-9));   // 0.18 * 0.5
        assertThat(SetPieces.share(1.0, 1.0)).isEqualTo(0.27, within(1e-9));   // 0.18 * 1.5
        assertThat(SetPieces.share(1.0, 0.0)).isEqualTo(0.18, within(1e-9));   // blended 0.5
    }

    // ---- roll(): weights CORNER 50*(0.5+c), FREE_KICK 30*(0.5+f), PENALTY 20.
    // With threats (0,0): corner=25, freeKick=15, penalty=20, total=60.
    @Test
    void rollPicksByWeightBands() {
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.2))).isEqualTo(SetPieces.Kind.CORNER);      // 12 < 25
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.5))).isEqualTo(SetPieces.Kind.FREE_KICK);   // 30 in [25,40)
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.9))).isEqualTo(SetPieces.Kind.PENALTY);     // 54 >= 40
    }

    @Test
    void aSkilledCornerTakerShiftsTheMixTowardCorners() {
        // threats (1,0): corner=75, freeKick=15, penalty=20, total=110 — 0.6*110=66 < 75 stays CORNER,
        // whereas with (0,0) the same 0.6 draw (=36) lands in FREE_KICK territory... (36 in [25,40)).
        assertThat(SetPieces.roll(1.0, 0.0, fixed(0.6))).isEqualTo(SetPieces.Kind.CORNER);
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.6))).isEqualTo(SetPieces.Kind.FREE_KICK);
    }

    // ---- conversionChance(): [0.70, 0.95]
    @Test
    void conversionChanceBounds() {
        assertThat(SetPieces.conversionChance(0)).isEqualTo(0.70, within(1e-9));
        assertThat(SetPieces.conversionChance(99)).isEqualTo(0.95, within(1e-9));
    }

    // ---- headerWeight(): position factor x (Cabeceio + 10); keepers never
    @Test
    void headerWeightFavorsAttackersAndDefendersAndExcludesKeepers() {
        assertThat(SetPieces.headerWeight(player(Position.ATT, Attribute.CABECEIO, 50))).isEqualTo(600);
        assertThat(SetPieces.headerWeight(player(Position.DEF, Attribute.CABECEIO, 50))).isEqualTo(480);
        assertThat(SetPieces.headerWeight(player(Position.MID, Attribute.CABECEIO, 50))).isEqualTo(240);
        assertThat(SetPieces.headerWeight(player(Position.GK, Attribute.CABECEIO, 99))).isZero();
    }

    // ---- takerOrFallback()
    @Test
    void designatedTakerWinsWhenInThePool() {
        Player taker = player(Position.MID, Attribute.BOLA_PARADA, 10);
        Player better = player(Position.ATT, Attribute.BOLA_PARADA, 90);
        Player picked = SetPieces.takerOrFallback(List.of(better, taker), taker.getId(),
                p -> p.attribute(Attribute.BOLA_PARADA));
        assertThat(picked).isSameAs(taker);
    }

    @Test
    void missingTakerFallsBackToFirstMaxByAttribute() {
        Player a = player(Position.MID, Attribute.BOLA_PARADA, 60);
        Player b = player(Position.ATT, Attribute.BOLA_PARADA, 90);
        Player c = player(Position.DEF, Attribute.BOLA_PARADA, 90);   // tied with b — b is first
        Player picked = SetPieces.takerOrFallback(List.of(a, b, c), UUID.randomUUID(),
                p -> p.attribute(Attribute.BOLA_PARADA));
        assertThat(picked).isSameAs(b);
        assertThat(SetPieces.takerOrFallback(List.of(a, b, c), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(b);
    }

    @Test
    void fallbackSkipsGoalkeepersUnlessPoolIsAllKeepers() {
        Player gk = player(Position.GK, Attribute.BOLA_PARADA, 99);
        Player mid = player(Position.MID, Attribute.BOLA_PARADA, 40);
        assertThat(SetPieces.takerOrFallback(List.of(gk, mid), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(mid);
        assertThat(SetPieces.takerOrFallback(List.of(gk), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(gk);
    }

    @Test
    void aDesignatedKeeperTakerIsHonored() {
        Player gk = player(Position.GK, Attribute.BOLA_PARADA, 99);
        Player mid = player(Position.MID, Attribute.BOLA_PARADA, 40);
        assertThat(SetPieces.takerOrFallback(List.of(gk, mid), gk.getId(),
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(gk);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.SetPiecesTest"
```
Expected: compilation FAILURE — `SetPieces` does not exist.

- [ ] **Step 3: Implement SetPieces**

`backend/src/main/java/br/com/carvoeiromanager/match/SetPieces.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Shared set-piece math for both match engines (live and instant). Pure, stateless,
 * deterministic given the caller's RNG: {@link #roll} consumes exactly one draw.
 *
 * <p>Threats are ~[0,1] taker skills computed by {@code TeamStrengthCalculator}
 * (corner taker's Cruzamento, free-kick taker's Bola parada). Penalties carry a fixed
 * subtype weight — they come from fouls, not taker skill — and a conversion chance
 * derived from the penalty taker's Bola parada.
 */
public final class SetPieces {

    /** Which kind of set piece a set-piece scoring chance turned out to be. */
    public enum Kind { CORNER, FREE_KICK, PENALTY }

    static final double SET_PIECE_SHARE = 0.18;
    private static final double CORNER_BASE_WEIGHT = 50.0;
    private static final double FREE_KICK_BASE_WEIGHT = 30.0;
    private static final double PENALTY_WEIGHT = 20.0;
    private static final double CONVERSION_BASE = 0.70;
    private static final double CONVERSION_SKILL_SPAN = 0.25;

    private SetPieces() {
    }

    /** Chance that a scoring event is a set piece — same scale as the pre-split formula. */
    public static double share(double cornerThreat, double freeKickThreat) {
        double blended = (cornerThreat + freeKickThreat) / 2.0;
        return Math.max(0.0, Math.min(0.6, SET_PIECE_SHARE * (0.5 + blended)));
    }

    /** One RNG draw: subtype by effective weight — skilled takers shift the mix toward their type. */
    public static Kind roll(double cornerThreat, double freeKickThreat, Random rng) {
        double corner = CORNER_BASE_WEIGHT * (0.5 + cornerThreat);
        double freeKick = FREE_KICK_BASE_WEIGHT * (0.5 + freeKickThreat);
        double pick = rng.nextDouble() * (corner + freeKick + PENALTY_WEIGHT);
        if (pick < corner) return Kind.CORNER;
        if (pick < corner + freeKick) return Kind.FREE_KICK;
        return Kind.PENALTY;
    }

    /** Penalty conversion chance from the taker's Bola parada: [0.70, 0.95], ~0.82 average. */
    public static double conversionChance(int bolaParada) {
        return CONVERSION_BASE + CONVERSION_SKILL_SPAN * (bolaParada / 99.0);
    }

    /**
     * Corner-scorer weight: headers. Centre-backs and strikers rise for corners; keepers never
     * score. The {@code + 10} floor keeps a poor header able to score occasionally.
     */
    public static int headerWeight(Player p) {
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 10;
            case DEF -> 8;
            case MID -> 4;
            case GK -> 0;
        };
        return positionFactor * (p.attribute(Attribute.CABECEIO) + 10);
    }

    /**
     * The designated taker when present in the pool; otherwise the FIRST player in pool order
     * with the maximal fallback attribute, skipping goalkeepers (a keeper is used only when the
     * pool is all keepers). A designated keeper taker id IS honored — that was the user's choice.
     * Deterministic, no RNG.
     */
    public static Player takerOrFallback(List<Player> pool, UUID takerId,
                                         ToIntFunction<Player> fallbackAttribute) {
        if (takerId != null) {
            for (Player p : pool) {
                if (p.getId().equals(takerId)) return p;
            }
        }
        Player best = null;
        for (Player p : pool) {
            if (p.getPosition() == Position.GK) continue;
            if (best == null || fallbackAttribute.applyAsInt(p) > fallbackAttribute.applyAsInt(best)) {
                best = p;
            }
        }
        return best != null ? best : pool.get(0);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.SetPiecesTest"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SetPieces.java backend/src/test/java/br/com/carvoeiromanager/match/SetPiecesTest.java
git commit -m "feat(match): SetPieces — shared corner/free-kick/penalty math"
```

---

### Task 2: EventType split + every consumer (nothing emits yet)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimeline.java:15-16` (isGoal)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java` (`isGoal`, ~line 360)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (`isCardOrGoal` ~311; `managedRatings` sideEvents filter ~433)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/TopScorersService.java` (~line 64)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerRatingCalculator.java` (goal/miss tallies + malus)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java` (~line 51)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (`saveEvents` switch ~195)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`
- Modify: `frontend/src/api/types.ts` (`LiveEvent.type` union), `frontend/src/features/match/LiveMatchPage.tsx` (`EVENT_ICON`)
- Test: extend `backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java`, `LiveCommentaryTest.java`, `PlayerRatingCalculatorTest.java`, `MatchOutcomeApplierTest.java`

**Interfaces:**
- Consumes (Task 1): `SetPieces.takerOrFallback(List<Player>, UUID, ToIntFunction<Player>)`, `SetPieces.headerWeight(Player)`.
- Produces (Task 3 relies on): `EventType.PENALTY_GOAL / FREE_KICK_GOAL / CORNER_GOAL / PENALTY_MISSED`; instance method `boolean EventType.isGoal()`; static `List<String> EventType.goalTypeNames()`; `LiveCommentary.of(...)` handling the four new cases.

- [ ] **Step 1: Write the failing tests**

1. Extend `backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java` — add:

```java
    @Test
    void goalTypesAreExactlyTheScoringOnes() {
        assertThat(EventType.GOAL.isGoal()).isTrue();
        assertThat(EventType.SET_PIECE_GOAL.isGoal()).isTrue();   // legacy, still counted
        assertThat(EventType.PENALTY_GOAL.isGoal()).isTrue();
        assertThat(EventType.FREE_KICK_GOAL.isGoal()).isTrue();
        assertThat(EventType.CORNER_GOAL.isGoal()).isTrue();
        assertThat(EventType.PENALTY_MISSED.isGoal()).isFalse();
        assertThat(EventType.YELLOW_CARD.isGoal()).isFalse();
        assertThat(EventType.CHANCE.isGoal()).isFalse();
    }

    @Test
    void goalTypeNamesDriveTheDatabaseQueries() {
        assertThat(EventType.goalTypeNames()).containsExactlyInAnyOrder(
                "GOAL", "SET_PIECE_GOAL", "PENALTY_GOAL", "FREE_KICK_GOAL", "CORNER_GOAL");
    }

    @Test
    void newValuesAreAppendedSoExistingOrdinalsAreStable() {
        // LiveCommentary.pick derives variant choice from ordinal — pre-split values must not shift.
        assertThat(EventType.GOAL.ordinal()).isZero();
        assertThat(EventType.HALF_TIME.ordinal()).isEqualTo(8);
    }
```

2. Extend `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java` — add:

```java
    @Test
    void penaltyGoalMissAndFreeKickLines() {
        // Variant index = minute + type.ordinal() mod array length; assert membership, not index math.
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null)).isIn(
                "Pênalti! Otero converte com categoria!", "GOL DE PÊNALTI! Otero não desperdiça!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null)).isIn(
                "Pênalti perdido! Otero desperdiça — defesa do goleiro!",
                "Otero cobra o pênalti... e o goleiro defende!");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null)).isIn(
                "GOL DE FALTA! Otero acerta o ângulo!", "Falta cobrada por Otero... GOL!");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null))
                .isEqualTo("Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null))
                .isEqualTo("GOL de escanteio! Zagueiro aparece na área e marca!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null))
                .isEqualTo("GOL de escanteio! Batedor aparece na área e marca!");
    }
```

3. Extend `backend/src/test/java/br/com/carvoeiromanager/match/PlayerRatingCalculatorTest.java` — add tests following the file's existing fixture style (a played XI, `AttributedEvent` lists, `rate(...)`; adapt constructor calls to the file's existing helpers):

```java
    @Test
    void newGoalTypesCreditGoalsLikePlainGoals() {
        // one PENALTY_GOAL + one FREE_KICK_GOAL + one CORNER_GOAL for the same player
        // -> rating equals what three GOAL events would produce, and PlayerRating.goals() == 3.
    }

    @Test
    void aMissedPenaltyCostsHalfAPoint() {
        // identical fixtures, one with a PENALTY_MISSED event for the player, one without:
        // rating difference is exactly 0.5 (full-minutes player, no other events).
    }
```
Write these as REAL assertions using the file's existing setup helpers (read the file first); the two comments above describe the required scenario, and the expected values must be computed from the file's existing constants (`BASE`, `GOAL_BONUS`, etc. are private — assert via difference between two `rate(...)` calls, which needs no constant access).

4. Extend `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java` — add one test in the file's existing style: an `AttributedEvent` of type `PENALTY_GOAL` produces the same form/morale effect as `GOAL` (assert via the same mechanism the file's existing goal test uses), and `PENALTY_MISSED` produces none.

- [ ] **Step 2: Run to verify failures**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.EventTypeTest" --tests "br.com.carvoeiromanager.match.LiveCommentaryTest" --tests "br.com.carvoeiromanager.match.PlayerRatingCalculatorTest" --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest"
```
Expected: compilation FAILURE (`PENALTY_GOAL` does not exist).

- [ ] **Step 3: Implement the enum and all consumers**

3a. `EventType.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.Arrays;
import java.util.List;

public enum EventType {
    GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME,
    // Appended (ordinals above feed LiveCommentary variant selection and must not shift).
    PENALTY_GOAL, FREE_KICK_GOAL, CORNER_GOAL, PENALTY_MISSED;

    /**
     * True for every type that counts on the scoreboard. SET_PIECE_GOAL is legacy: no longer
     * emitted, still counted so old persisted match_events rows keep working.
     */
    public boolean isGoal() {
        return this == GOAL || this == SET_PIECE_GOAL || this == PENALTY_GOAL
                || this == FREE_KICK_GOAL || this == CORNER_GOAL;
    }

    /** Names of every goal-counting type — the single source for DB queries over match_events.type. */
    public static List<String> goalTypeNames() {
        return Arrays.stream(values()).filter(EventType::isGoal).map(Enum::name).toList();
    }
}
```

3b. `LiveTimeline.java` — replace the private helper body:

```java
    private static boolean isGoal(EventType t) {
        return t.isGoal();
    }
```

3c. `LiveMatchEngine.java` — replace the private `isGoal` body identically (`return t.isGoal();`).

3d. `LiveMatchService.java`:
- `isCardOrGoal` (~line 311):

```java
    private boolean isCardOrGoal(EventType t) {
        return t.isGoal() || t == EventType.YELLOW_CARD || t == EventType.RED_CARD;
    }
```
- `managedRatings` sideEvents filter (~line 433) — a missed penalty must reach the rating
  calculator (it is NOT persisted; the finish()/persist loops keep plain `isCardOrGoal`):

```java
        List<AttributedEvent> sideEvents = timeline.eventsThrough(minute).stream()
                .filter(e -> e.home() == managedIsHome
                        && (isCardOrGoal(e.type()) || e.type() == EventType.PENALTY_MISSED))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();
```

3e. `TopScorersService.java` (~line 64):

```java
        List<MatchEvent> goals = matchEventRepository.findByFixtureIdInAndTypeIn(
                fixtureIds, br.com.carvoeiromanager.match.EventType.goalTypeNames());
```
(use a normal import if the file doesn't already import from `match`.)

3f. `PlayerRatingCalculator.java` — tally loop and rating line:

```java
        Map<UUID, Integer> goals = new HashMap<>();
        Map<UUID, Integer> missedPenalties = new HashMap<>();
        Map<UUID, Integer> yellows = new HashMap<>();
        Set<UUID> reds = new HashSet<>();
        for (AttributedEvent e : sideEvents) {
            if (e.type().isGoal()) {
                goals.merge(e.playerId(), 1, Integer::sum);
                continue;
            }
            switch (e.type()) {
                case PENALTY_MISSED -> missedPenalties.merge(e.playerId(), 1, Integer::sum);
                case YELLOW_CARD -> yellows.merge(e.playerId(), 1, Integer::sum);
                case RED_CARD -> reds.add(e.playerId());
                default -> { }
            }
        }
```
New constant next to the existing penalties: `private static final double PENALTY_MISS_PENALTY = -0.5;`
Rating line (the miss counts in full, like a goal — NOT muted for cameos):

```java
            double raw = BASE + GOAL_BONUS * scoredGoals
                    + PENALTY_MISS_PENALTY * missedPenalties.getOrDefault(p.getId(), 0)
                    + nonGoal;
```

3g. `MatchOutcomeApplier.java` `applyFixture` loop:

```java
        for (AttributedEvent e : events) {
            if (e.type().isGoal()) {
                goals.merge(e.playerId(), 1, Integer::sum);
                continue;
            }
            switch (e.type()) {
                case YELLOW_CARD -> yellows.merge(e.playerId(), 1, Integer::sum);
                case RED_CARD -> reds.add(e.playerId());
                default -> { }
            }
        }
```

3h. `FixtureSimulator.java` `saveEvents` switch — add the new cases (imports: `SetPieces`, `Attribute`):

```java
            Player player = switch (event.type()) {
                case GOAL -> pickByWeight(xi, FixtureSimulator::goalWeight, rng);
                case SET_PIECE_GOAL -> setPieceTaker(xi, profile, rng);   // legacy emission until Task 3
                case PENALTY_GOAL -> SetPieces.takerOrFallback(xi, profile.penaltyTakerId(), Player::getFinishing);
                case FREE_KICK_GOAL -> SetPieces.takerOrFallback(xi, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                case CORNER_GOAL -> pickByWeight(xi, SetPieces::headerWeight, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
```

3i. `LiveCommentary.java` — new line arrays + cases + corner helper; update the `of` javadoc
(`{@code inName} is only used for substitutions and corner goals (the corner taker).`):

```java
    private static final String[] PENALTY_GOAL_LINES = {
            "Pênalti! %s converte com categoria!", "GOL DE PÊNALTI! %s não desperdiça!"};
    private static final String[] PENALTY_MISS_LINES = {
            "Pênalti perdido! %s desperdiça — defesa do goleiro!",
            "%s cobra o pênalti... e o goleiro defende!"};
    private static final String[] FREE_KICK_LINES = {
            "GOL DE FALTA! %s acerta o ângulo!", "Falta cobrada por %s... GOL!"};
```
Cases in the switch:

```java
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName);
```
Helper:

```java
    /** Two-name line when the taker is known and distinct; single-name fallback otherwise. */
    private static String corner(String scorerName, String takerName) {
        if (takerName != null && !takerName.equals(scorerName)) {
            return "Escanteio de %s, %s sobe e cabeceia: GOL!".formatted(takerName, scorerName);
        }
        return "GOL de escanteio! %s aparece na área e marca!".formatted(scorerName);
    }
```

3j. Frontend — `frontend/src/api/types.ts` `LiveEvent.type` union becomes:

```typescript
  type: 'GOAL' | 'SET_PIECE_GOAL' | 'YELLOW_CARD' | 'RED_CARD' | 'CHANCE' | 'INJURY' | 'SUBSTITUTION' | 'TACTICS_CHANGE' | 'HALF_TIME' | 'PENALTY_GOAL' | 'FREE_KICK_GOAL' | 'CORNER_GOAL' | 'PENALTY_MISSED'
```
`frontend/src/features/match/LiveMatchPage.tsx` `EVENT_ICON`:

```typescript
const EVENT_ICON: Record<LiveEvent['type'], string> = {
  GOAL: '⚽', SET_PIECE_GOAL: '⚽', YELLOW_CARD: '🟨', RED_CARD: '🟥',
  CHANCE: '🎯', INJURY: '🚑', SUBSTITUTION: '🔁', TACTICS_CHANGE: '📋', HALF_TIME: '⏸️',
  PENALTY_GOAL: '⚽', FREE_KICK_GOAL: '⚽', CORNER_GOAL: '⚽', PENALTY_MISSED: '🧤',
}
```

- [ ] **Step 4: Run backend tests + frontend checks**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*" --tests "br.com.carvoeiromanager.competition.*"
```
Expected: BUILD SUCCESSFUL (engines still emit only old types; consumers now tolerate new ones). Then:

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck, all tests green.

- [ ] **Step 5: Commit**

```bash
git add -A backend frontend
git commit -m "feat(match): distinct set-piece event types — consumers first"
```

---

### Task 3: Engines emit — threats, subtype roll, penalty conversion

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TeamMatchInput.java` (replace `setPieceThreat` with 3 fields)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TeamStrengthCalculator.java` (threats + conversion; delete `setPieceThreat` helper)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java` (subtype/conversion in the loop; delete `SET_PIECE_SHARE` + `setPieceProbability`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java` (goal branches → `resolveScoringEvent`; delete `SET_PIECE_SHARE`, `setPiece`, `setPieceTaker`, old `goal()`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (SET_PIECE_GOAL case → legacy throw; delete its `setPieceTaker`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java` (javadoc only: `playerInId` also carries the corner taker for `CORNER_GOAL`)
- Test: `TeamStrengthCalculatorTest.java`, `MatchSimulationServiceTest.java`, `LiveMatchEngineTest.java` (all extended/updated; any test constructing `TeamMatchInput` directly must adopt the new shape)

**Interfaces:**
- Consumes (Tasks 1–2): `SetPieces.share/roll/conversionChance/headerWeight/takerOrFallback`; `EventType.PENALTY_GOAL/FREE_KICK_GOAL/CORNER_GOAL/PENALTY_MISSED`; `EventType.isGoal()`.
- Produces: `record TeamMatchInput(double attack, double defense, double tempo, double aggression, double cornerThreat, double freeKickThreat, double penaltyConversion, double fitnessDecay)`.

- [ ] **Step 1: Write/adjust the failing tests**

1. `TeamStrengthCalculatorTest.java` — replace any `setPieceThreat` assertions and add (using the file's existing player-factory conventions; build players with specific attributes via `Attributes.builder()` + the full-profile `Player.create(saveGameId, clubId, name, pos, age, nationality, attributes, overall)`):

```java
    @Test
    void cornerThreatComesFromTheCornerTakersCrossing() {
        // XI where the designated corner taker has CRUZAMENTO 80 and someone else has 99:
        // cornerThreat == 80/99.0 (the taker governs, not the best crosser).
    }

    @Test
    void threatsFallBackToTheBestInTheXiWhenNoTakerIsSet() {
        // no cornerTakerId/freekickTakerId: cornerThreat == maxCruzamento/99.0,
        // freeKickThreat == maxBolaParada/99.0.
    }

    @Test
    void penaltyConversionComesFromThePenaltyTakersSetPieceSkill() {
        // designated penalty taker with BOLA_PARADA 99 -> penaltyConversion == 0.95;
        // no taker set -> the best FINISHER's (non-GK) BOLA_PARADA feeds the formula.
    }
```
Write these as real assertions (`assertThat(input.cornerThreat()).isEqualTo(80 / 99.0, within(1e-9))` etc.) — the comments describe the scenario to build.

2. `LiveMatchEngineTest.java` — update `expectedAwayReactions` (the AI-reaction oracle) to count goals with `e.type().isGoal()` instead of `GOAL || SET_PIECE_GOAL`, update `keepersNeverScoreFromOpenPlayAndAttackersOutscoreDefenders` to filter with `e.type().isGoal()`, and add:

```java
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
        // XI built via the full-profile factory with BOLA_PARADA 0 everywhere -> conversion
        // is exactly 0.70, so ~30% of penalties miss; over 300 seeds a miss is certain
        // (P(none) < 1e-4). Designate a penalty taker and assert every PENALTY_GOAL and
        // PENALTY_MISSED for that side carries the taker's id.
    }

    @Test
    void takersAreAttributedByType() {
        // Profile with three DISTINCT designated takers (penalty/freekick/corner, all in the XI).
        // Sweep 300 seeds: every FREE_KICK_GOAL playerId == freekick taker id; every CORNER_GOAL
        // playerInId == corner taker id AND the scorer is never a GK; every PENALTY_GOAL /
        // PENALTY_MISSED playerId == penalty taker id.
    }
```
The two commented tests must be written out fully: build the players with
`Attributes.builder().set(Attribute.VELOCIDADE, 70).set(Attribute.FINALIZACAO, 70).set(Attribute.PASSE, 70).set(Attribute.DESARME, 70).set(Attribute.REFLEXOS, gk ? 70 : 10).set(Attribute.FOLEGO, 70).set(Attribute.BOLA_PARADA, 0).set(Attribute.CRUZAMENTO, 50).set(Attribute.CABECEIO, 50).build()`
and a `TacticalProfile` whose taker ids point at chosen XI members (roles map as in the existing `defaultProfile` helper).

3. `MatchSimulationServiceTest.java` — update every direct `new TeamMatchInput(...)` to the new 8-field shape (e.g. `new TeamMatchInput(attack, defense, tempo, aggression, 0.5, 0.5, 0.82, decay)`), fix any assertion that references `SET_PIECE_GOAL` emission, and add:

```java
    @Test
    void instantEngineEmitsDistinctSetPieceGoalsAndNeverTheLegacyType() {
        Set<EventType> seen = EnumSet.noneOf(EventType.class);
        for (long seed = 0; seed < 300; seed++) {
            SimulatedMatch m = service.simulate(input(), input(), seed);   // use the file's input helper
            m.events().forEach(e -> seen.add(e.type()));
            long homeGoalEvents = m.events().stream().filter(e -> e.home() && e.type().isGoal()).count();
            assertThat(m.homeGoals()).isEqualTo((int) homeGoalEvents);   // a missed pen leaves no event and no goal
        }
        assertThat(seen).contains(EventType.PENALTY_GOAL, EventType.FREE_KICK_GOAL, EventType.CORNER_GOAL);
        assertThat(seen).doesNotContain(EventType.SET_PIECE_GOAL, EventType.PENALTY_MISSED);
    }
```
(Adapt the helper name to the file's existing conventions; use `penaltyConversion` around 0.8 so misses occur but are silent.)

- [ ] **Step 2: Run to verify failures**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.TeamStrengthCalculatorTest" --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest" --tests "br.com.carvoeiromanager.match.MatchSimulationServiceTest"
```
Expected: compilation FAILURE (`TeamMatchInput` has no 8-field constructor / no `cornerThreat()`).

- [ ] **Step 3: Implement**

3a. `TeamMatchInput.java`:

```java
package br.com.carvoeiromanager.match;

/**
 * Enriched, engine-ready strength of one team for one match.
 * {@code fitnessDecay} is precomputed from pressing + average stamina; the engine applies it per
 * minute. {@code cornerThreat}/{@code freeKickThreat} are ~[0,1] taker-skill threats (corner
 * taker's Cruzamento, free-kick taker's Bola parada). {@code penaltyConversion} is the
 * precomputed chance a penalty is scored, in [0.70, 0.95].
 */
public record TeamMatchInput(
        double attack,
        double defense,
        double tempo,
        double aggression,
        double cornerThreat,
        double freeKickThreat,
        double penaltyConversion,
        double fitnessDecay) {
}
```

3b. `TeamStrengthCalculator.java` — replace the `setPieceThreat` computation and helper:

```java
        double cornerThreat = takerThreat(xi, profile.cornerTakerId(), Attribute.CRUZAMENTO);
        double freeKickThreat = takerThreat(xi, profile.freekickTakerId(), Attribute.BOLA_PARADA);
        double penaltyConversion = SetPieces.conversionChance(
                SetPieces.takerOrFallback(xi, profile.penaltyTakerId(), Player::getFinishing)
                        .attribute(Attribute.BOLA_PARADA));

        return new TeamMatchInput(attack, defense, tempo, aggression,
                cornerThreat, freeKickThreat, penaltyConversion, fitnessDecay);
```
Replace the old private `setPieceThreat` method with:

```java
    /** The designated taker's attribute when in the XI, else the best in the XI — scaled to ~[0,1]. */
    private double takerThreat(List<Player> xi, UUID takerId, Attribute attribute) {
        if (takerId != null) {
            for (Player p : xi) {
                if (p.getId().equals(takerId)) {
                    return p.attribute(attribute) / 99.0;
                }
            }
        }
        return xi.stream().mapToInt(p -> p.attribute(attribute)).max().orElse(0) / 99.0;
    }
```
(import `br.com.carvoeiromanager.player.Attribute`.)

3c. `MatchSimulationService.java` — remove `SET_PIECE_SHARE` and `setPieceProbability`; precompute
`double homeSetPiece = SetPieces.share(home.cornerThreat(), home.freeKickThreat());` (and away);
replace both goal branches (home shown; away is symmetric with `false` and `away`):

```java
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
```
Update the class javadoc's RNG-order comment to:
`home-goal, [home-set-piece?, home-subtype, home-conversion?], away-goal, [...], home-card, home-card-type, away-card, away-card-type — bracketed draws occur only when the preceding roll hits.`

3d. `LiveMatchEngine.java`:
- Delete `SET_PIECE_SHARE`, `setPiece(TeamMatchInput)`, `setPieceTaker(...)`, and the old
  `goal(...)` method.
- Replace both goal branches in the loop (home shown; away symmetric; the `homeGoals++`
  counters are the AI-reaction running score from the previous feature — a missed penalty must
  NOT increment them):

```java
            if (rng.nextDouble() < homeChance) {
                if (resolveScoringEvent(minute, home, homeInput, rng, attrRng, events)) homeGoals++;
            }
            if (rng.nextDouble() < awayChance) {
                if (resolveScoringEvent(minute, away, awayInput, rng, attrRng, events)) awayGoals++;
            }
```
- New method (imports: `SetPieces`, `Attribute`):

```java
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
```

3e. `FixtureSimulator.java` — the instant engine no longer emits the legacy type: move
`SET_PIECE_GOAL` into the throw group and delete the now-unused private `setPieceTaker`:

```java
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED, SET_PIECE_GOAL ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
```

3f. `LiveTimelineEvent.java` — javadoc only: `{@code playerInId} is the player coming ON (SUBSTITUTION) or the corner taker (CORNER_GOAL); else null.`

- [ ] **Step 4: Run targeted tests, then the full backend suite, then frontend**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL. Any other test in the suite that constructed `TeamMatchInput` directly or asserted `SET_PIECE_GOAL` emission must be updated to the new shape/types (behavior-preserving edits only — flag anything that looks like a real regression instead of papering over it). Then:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL (~520+ tests). Then `cd frontend && npx tsc --noEmit && npx vitest run` — clean.

- [ ] **Step 5: Commit**

```bash
git add -A backend frontend
git commit -m "feat(match): corners, free kicks and penalties — engines emit distinct set pieces"
```

---

## Verification (end-to-end sanity, after all tasks)

Play a live match: the feed should show typed set-piece goals ("GOL DE FALTA! …", "Escanteio de X, Y sobe e cabeceia: GOL!") and occasionally "Pênalti perdido! …" with a 🧤 icon; your designated takers (Táticas de jogo → Pênaltis/Faltas/Escanteios) should be the ones taking them. Advance a round: News/top scorers keep working; old saves' historical top scorers still include pre-split set-piece goals.
