# Match Strength-Spread Recalibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make squad quality matter more in match outcomes by widening the talent-gap effect on scoring, recasting home advantage as an orthogonal knob, and extracting the duplicated match-scoring math into one shared utility.

**Architecture:** A new pure static utility `MatchMath` becomes the single home for the scoring/fitness math both engines currently duplicate. Phase 1 extracts it verbatim (suite stays green — proof of no behavior change). Phase 2 replaces the flat `xG = 1.4 + diff` curve with a gained, re-centred curve (`ANCHOR + GAIN·(d − REF)/BASE`, clamped) plus a home-advantage xG term, and adds a real-roster calibration harness that tunes the constants to a "realistic league" target.

**Tech Stack:** Java 25 (Corretto), Spring Boot, JUnit 5 + AssertJ, Gradle (toolchain pins Java 25).

## Running tests

- Full suite (~9 min): `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (run from `backend/`).
- Single class: append `--tests 'br.com.carvoeiromanager.match.MatchMathTest'`.

## Global Constraints

- Backend only. No migration, no API/DTO change, no frontend change, no new dependency.
- `MatchMath` is a pure static utility (the `SetPieces` pattern): final class, private constructor, no Spring, no RNG, no I/O.
- The recalibrated curve reduces to today's formula exactly at `GAIN=1, ANCHOR=0.92, REF=-24, HOME_XG=0.10, XG_CEILING=∞`.
- `TeamStrengthCalculator` aggregation is **not** changed (Approach A).
- Card constants (`BASE_CARD_RATE`, `RED_SHARE`) stay local to each engine — out of scope.
- Both engines must apply **identical** scoring math via `MatchMath` (no per-engine divergence in the formula).
- Phase 1 must leave the full test suite green (proof of no behavior change) before Phase 2 changes any number.
- Calibration targets (tolerance bands): strong-vs-weak favorite win **60–70%**; equal-team home win **42–48%**, draw **24–28%**; mean goals/match **2.4–3.0**.
- Commit locally only (main is shared with a concurrent session; never push). Use explicit pathspecs when staging.

---

## Task 1: Extract `MatchMath` with zero behavior change

Move the current scoring/fitness math verbatim into a new shared utility and delegate from both engines. No number changes; the full suite must stay green.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java`
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/MatchMathTest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java`

**Interfaces:**
- Produces (used by Task 2 and both engines):
  - `static int MatchMath.MINUTES` (= 90)
  - `static double MatchMath.HOME_ADVANTAGE` (= 5.0)
  - `static double MatchMath.fitnessFactor(double decay, int minute)`
  - `static double MatchMath.perMinuteScoreChance(double attack, double defense)` — per-minute chance (already divided by `MINUTES`)

- [ ] **Step 1: Write the failing test**

Create `MatchMathTest.java`:

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatchMathTest {

    @Test
    void equalStrengthYieldsBaselineExpectedGoals() {
        // diff = 0 -> expectedGoals = 1.4 -> per-minute = 1.4 / 90
        assertThat(MatchMath.perMinuteScoreChance(65, 65)).isEqualTo(1.4 / 90.0, within(1e-9));
    }

    @Test
    void hopelessMismatchIsFlooredAtPoint2() {
        // diff = -2 -> 1.4 - 2 = -0.6 -> floored to 0.2 -> 0.2 / 90
        assertThat(MatchMath.perMinuteScoreChance(0, 100)).isEqualTo(0.2 / 90.0, within(1e-9));
    }

    @Test
    void fitnessFactorIsOneWhenFreshAndFlooredWhenExhausted() {
        assertThat(MatchMath.fitnessFactor(0.0, 90)).isEqualTo(1.0, within(1e-9));      // no decay
        assertThat(MatchMath.fitnessFactor(1.0, 90)).isEqualTo(0.75, within(1e-9));     // floored
        assertThat(MatchMath.fitnessFactor(0.15, 45)).isEqualTo(0.9625, within(1e-9));  // frac=0.5
    }

    @Test
    void exposesTheSharedConstants() {
        assertThat(MatchMath.MINUTES).isEqualTo(90);
        assertThat(MatchMath.HOME_ADVANTAGE).isEqualTo(5.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchMathTest'`
Expected: FAIL to compile — `MatchMath` does not exist.

- [ ] **Step 3: Create `MatchMath`**

```java
package br.com.carvoeiromanager.match;

/**
 * Shared, pure match-scoring math used by both simulation engines ({@link LiveMatchEngine} and
 * {@link MatchSimulationService}), mirroring the {@link SetPieces} static-utility pattern. Keeping
 * a single copy guarantees the live and instant paths score identically. No RNG, no I/O, no Spring.
 */
final class MatchMath {

    private MatchMath() {
    }

    static final int MINUTES = 90;
    static final double BASE = 50.0;
    static final double FITNESS_FLOOR = 0.75;
    static final double HOME_ADVANTAGE = 5.0;

    /** Fitness multiplier for a decay at a given minute: 1.0 fresh, declining quadratically, floored. */
    static double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    /** Per-minute scoring probability from an attacking and a defending strength. */
    static double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchMathTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Delegate from `LiveMatchEngine`**

In `LiveMatchEngine.java`:
- Delete the private constants `MINUTES`, `HOME_ADVANTAGE`, `BASE`, `FITNESS_FLOOR` (lines ~32–35).
- Delete the private methods `fitnessFactor(double, int)` and `perMinuteScoreChance(double, double)` (lines ~534–543).
- Replace every `MINUTES` reference with `MatchMath.MINUTES` (the `for (int minute = 1; minute <= MatchMath.MINUTES; ...)` loop, `finalizeMinutes`, `putMinutes`).
- Replace the two fitness/chance call sites (lines ~124–130) with:

```java
double homeFf = MatchMath.fitnessFactor(homeInput.fitnessDecay(), minute);
double awayFf = MatchMath.fitnessFactor(awayInput.fitnessDecay(), minute);

double homeChance = MatchMath.perMinuteScoreChance(homeInput.attack() * homeFf + MatchMath.HOME_ADVANTAGE,
        awayInput.defense() * awayFf) * tempo;
double awayChance = MatchMath.perMinuteScoreChance(awayInput.attack() * awayFf,
        homeInput.defense() * homeFf + MatchMath.HOME_ADVANTAGE) * tempo;
```

Note: keep `isGoal(...)` / `fitnessFactor(...)` call shapes identical otherwise — only the ownership of the constants and formulas moves.

- [ ] **Step 6: Delegate from `MatchSimulationService`**

In `MatchSimulationService.java`:
- Delete the private constants `MINUTES`, `HOME_ADVANTAGE`, `BASE`, `FITNESS_FLOOR` (lines ~25–28).
- Delete the private methods `fitnessFactor` and `perMinuteScoreChance` (lines ~107–116).
- Replace the loop bound with `minute <= MatchMath.MINUTES`.
- Replace the fitness/chance block (lines ~45–51) with:

```java
double homeFf = MatchMath.fitnessFactor(home.fitnessDecay(), minute);
double awayFf = MatchMath.fitnessFactor(away.fitnessDecay(), minute);

double homeChance = MatchMath.perMinuteScoreChance(home.attack() * homeFf + MatchMath.HOME_ADVANTAGE,
        away.defense() * awayFf) * tempoFactor;
double awayChance = MatchMath.perMinuteScoreChance(away.attack() * awayFf,
        home.defense() * homeFf + MatchMath.HOME_ADVANTAGE) * tempoFactor;
```

(`BASE_CARD_RATE`, `RED_SHARE` stay untouched.)

- [ ] **Step 7: Run the full suite to prove no behavior change**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: PASS — same result set as before the change (the pre-existing `finance.FinanceRolloverIT` flake may fail; if so, re-run it in isolation to confirm it passes: `--tests 'br.com.carvoeiromanager.finance.FinanceRolloverIT'`). No other failures.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchMathTest.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java \
        backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java
git commit -m "refactor(match): extract shared scoring math into MatchMath (no behavior change)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Recalibrate the curve (gain + re-centre + home-xg)

Replace the flat curve with the gained, re-centred form and recast home advantage as an orthogonal xG term. Land with initial constant estimates; the full suite stays green (behavioral assertions only — no golden scorelines exist). Final numeric tuning happens in Task 3.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/MatchMathTest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java`

**Interfaces:**
- Consumes: `MatchMath.MINUTES`, `MatchMath.fitnessFactor` (from Task 1).
- Produces (used by Task 3 and both engines):
  - `static double MatchMath.GAIN`, `MatchMath.ANCHOR`, `MatchMath.REF`, `MatchMath.HOME_XG`, `MatchMath.XG_FLOOR`, `MatchMath.XG_CEILING`
  - `static double MatchMath.perMinuteScoreChance(double attack, double defense, boolean attackerIsHome)` (3-arg; replaces the 2-arg form)
  - `MatchMath.HOME_ADVANTAGE` is removed.

- [ ] **Step 1: Rewrite `MatchMathTest` for the new curve (property-based, tuning-robust)**

Replace the body of `MatchMathTest` with assertions that survive Task 3's constant tuning (they reference the constants, not hard-coded outputs):

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatchMathTest {

    @Test
    void homeSideGetsExactlyTheHomeXgEdgeOverAnIdenticalAwaySide() {
        double home = MatchMath.perMinuteScoreChance(50, 50, true);
        double away = MatchMath.perMinuteScoreChance(50, 50, false);
        // home xg - away xg == 2 * HOME_XG (per full match), divided by MINUTES per minute
        assertThat(home - away).isEqualTo(2 * MatchMath.HOME_XG / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void strongerAttackerScoresMoreWhenUnclamped() {
        double strong = MatchMath.perMinuteScoreChance(60, 50, false);
        double weak = MatchMath.perMinuteScoreChance(40, 50, false);
        assertThat(strong).isGreaterThan(weak);
    }

    @Test
    void hopelessMismatchIsFlooredAtXgFloor() {
        assertThat(MatchMath.perMinuteScoreChance(0, 100, false))
                .isEqualTo(MatchMath.XG_FLOOR / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void hugeMismatchIsCappedAtXgCeiling() {
        assertThat(MatchMath.perMinuteScoreChance(100, 0, true))
                .isEqualTo(MatchMath.XG_CEILING / MatchMath.MINUTES, within(1e-9));
    }

    @Test
    void fitnessFactorIsOneWhenFreshAndFlooredWhenExhausted() {
        assertThat(MatchMath.fitnessFactor(0.0, 90)).isEqualTo(1.0, within(1e-9));
        assertThat(MatchMath.fitnessFactor(1.0, 90)).isEqualTo(0.75, within(1e-9));
        assertThat(MatchMath.fitnessFactor(0.15, 45)).isEqualTo(0.9625, within(1e-9));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchMathTest'`
Expected: FAIL to compile — the 3-arg `perMinuteScoreChance` and the new constants don't exist yet.

- [ ] **Step 3: Implement the recalibrated curve in `MatchMath`**

In `MatchMath.java`: remove `HOME_ADVANTAGE`; add the new constants; replace the 2-arg `perMinuteScoreChance` with the 3-arg form. Keep `MINUTES`, `BASE`, `FITNESS_FLOOR`, and `fitnessFactor` unchanged.

```java
    static final int MINUTES = 90;
    static final double BASE = 50.0;
    static final double FITNESS_FLOOR = 0.75;

    // --- Recalibrated scoring curve (initial estimates; finalised by MatchCalibrationTest) ---
    static final double GAIN = 2.2;         // talent-spread multiplier (1.0 == legacy)
    static final double ANCHOR = 1.15;      // xG of an average (d == REF) matchup, home bonus excluded
    static final double REF = -24.0;        // average-matchup differential across seeded squads
    static final double HOME_XG = 0.15;     // home-edge xG bonus (home +, away -)
    static final double XG_FLOOR = 0.2;     // lower clamp (unchanged)
    static final double XG_CEILING = 3.5;   // upper clamp (new; guards extreme mismatches)

    /**
     * Per-minute scoring probability from an attacking and a defending strength. The strength
     * differential is amplified by {@link #GAIN} around the average matchup ({@link #REF}); the
     * home side receives a fixed {@link #HOME_XG} bonus and the away side the mirror penalty, so
     * talent spread and home advantage are independent knobs.
     */
    static double perMinuteScoreChance(double attack, double defense, boolean attackerIsHome) {
        double d = attack - defense;
        double xg = ANCHOR + GAIN * (d - REF) / BASE;
        xg += attackerIsHome ? HOME_XG : -HOME_XG;
        xg = Math.max(XG_FLOOR, Math.min(XG_CEILING, xg));
        return xg / MINUTES;
    }
```

- [ ] **Step 4: Update `LiveMatchEngine` call sites to the 3-arg form**

Replace the chance block (from Task 1) with:

```java
double homeChance = MatchMath.perMinuteScoreChance(homeInput.attack() * homeFf,
        awayInput.defense() * awayFf, true) * tempo;
double awayChance = MatchMath.perMinuteScoreChance(awayInput.attack() * awayFf,
        homeInput.defense() * homeFf, false) * tempo;
```

- [ ] **Step 5: Update `MatchSimulationService` call sites to the 3-arg form**

Replace the chance block (from Task 1) with:

```java
double homeChance = MatchMath.perMinuteScoreChance(home.attack() * homeFf,
        away.defense() * awayFf, true) * tempoFactor;
double awayChance = MatchMath.perMinuteScoreChance(away.attack() * awayFf,
        home.defense() * homeFf, false) * tempoFactor;
```

- [ ] **Step 6: Run `MatchMathTest`**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchMathTest'`
Expected: PASS (5 tests).

- [ ] **Step 7: Run the full suite and fix any legitimately-shifted behavioral assertions**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: PASS. The suite has **no golden-scoreline assertions** — every simulation-tied test is a behavioral invariant (determinism via re-simulation, `homeGoals == goalEvents`, champion captured-then-compared, `totalScored == totalGoalsFor`, stronger-team-wins-more, goals-by-position, cards-scale-with-aggression). All still hold under a wider spread. If any statistical band fails (e.g. a "stronger wins more" threshold that is now comfortably exceeded), confirm the new behavior is *more* correct and widen/adjust that band with a one-line comment; do **not** loosen a determinism or conservation invariant. Known pre-existing flake: `finance.FinanceRolloverIT` (re-run in isolation to confirm).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchMathTest.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java \
        backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java
git commit -m "feat(match): widen talent spread with gained xG curve + orthogonal home-advantage term

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Calibration harness + tune constants to the target bands

Add a permanent, DB-free calibration test that builds real seeded rosters, simulates thousands of instant matches deterministically, and asserts the "realistic league" bands. Tune `GAIN`/`HOME_XG`/`ANCHOR`/`REF` until the bands pass.

**Files:**
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/MatchCalibrationTest.java`
- Modify (tuning only): `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java`

**Interfaces:**
- Consumes: `MatchMath.perMinuteScoreChance(double, double, boolean)` (via `MatchSimulationService`), `SeedLoader.loadCareer()` → `SeedCareer.clubPool()`, `SeedClub.players()`, `SeedPlayer.attributes().toAttributes(...)`, `OverallCalculator.compute(...)`, `TeamStrengthCalculator.compute(xi, profile)`, `Player.create(saveGameId, clubId, name, position, age, nationality, Attributes, overall)`.

- [ ] **Step 1: Write the calibration harness (drives tuning)**

Create `MatchCalibrationTest.java`. It is a plain unit test (no Spring, no DB): it loads the seed pool, converts each `SeedPlayer` to a `Player`, builds a best-XI (best GK + top 10 outfield by overall), folds it into a `TeamMatchInput` via `TeamStrengthCalculator`, and runs the instant engine over a deterministic seed sequence.

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration harness AND regression guard for the recalibrated scoring math. Builds real seeded
 * rosters, runs the deterministic instant engine over thousands of matchups, and asserts the
 * "realistic league" target bands. Pure unit test — no Spring context, no database.
 */
class MatchCalibrationTest {

    private static final int SIMS = 2000;

    private final SeedLoader seedLoader = new SeedLoader();
    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final TeamStrengthCalculator strengthCalc = new TeamStrengthCalculator();
    private final MatchSimulationService engine = new MatchSimulationService();

    private List<SeedClub> rankedByStrength() {
        return seedLoader.loadCareer().clubPool().stream()
                .sorted(Comparator.comparingDouble(this::squadAvgOverall).reversed())
                .toList();
    }

    private double squadAvgOverall(SeedClub club) {
        return club.players().stream().mapToInt(this::overallOf).average().orElse(0);
    }

    private int overallOf(SeedPlayer sp) {
        return overallCalculator.compute(Position.valueOf(sp.position()),
                sp.pace(), sp.finishing(), sp.passing(), sp.tackling(), sp.goalkeeping(), sp.stamina());
    }

    private Player toPlayer(SeedPlayer sp) {
        Position pos = Position.valueOf(sp.position());
        // Every seeded club carries hand-authored attributes (Catarinense data pass), so this is non-null.
        Attributes attrs = sp.attributes().toAttributes(sp.pace(), sp.finishing(), sp.passing(),
                sp.tackling(), sp.goalkeeping(), sp.stamina());
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), sp.name(), pos,
                sp.age(), sp.nationality(), attrs, overallOf(sp));
    }

    /** Best GK + top 10 outfield by overall — a strong, valid XI without formation plumbing. */
    private TeamMatchInput strength(SeedClub club) {
        List<Player> squad = club.players().stream().map(this::toPlayer).toList();
        Player gk = squad.stream().filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        List<Player> xi = new ArrayList<>();
        xi.add(gk);
        squad.stream().filter(p -> p.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .limit(10)
                .forEach(xi::add);

        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(p -> roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition())));
        TacticalProfile profile = new TacticalProfile(Mentality.BALANCED, "4-4-2",
                Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null);
        return strengthCalc.compute(xi, profile);
    }

    @Test
    void favoriteBeatsUnderdogInRealisticBand() {
        List<SeedClub> ranked = rankedByStrength();
        TeamMatchInput strong = strength(ranked.get(0));
        TeamMatchInput weak = strength(ranked.get(ranked.size() - 1));

        int favWins = 0;
        int games = 0;
        for (int i = 0; i < SIMS; i++) {
            var atHome = engine.simulate(strong, weak, 1000L + i);   // favorite home
            if (atHome.homeGoals() > atHome.awayGoals()) favWins++;
            games++;
            var away = engine.simulate(weak, strong, 5000L + i);     // favorite away
            if (away.awayGoals() > away.homeGoals()) favWins++;
            games++;
        }
        double rate = favWins / (double) games;
        assertThat(rate).as("favorite win-rate").isBetween(0.60, 0.70);
    }

    @Test
    void equalTeamsHaveRealisticHomeEdge() {
        List<SeedClub> ranked = rankedByStrength();
        TeamMatchInput mid = strength(ranked.get(ranked.size() / 2));

        int homeWins = 0;
        int draws = 0;
        for (int i = 0; i < SIMS; i++) {
            var m = engine.simulate(mid, mid, 9000L + i);
            if (m.homeGoals() > m.awayGoals()) homeWins++;
            else if (m.homeGoals() == m.awayGoals()) draws++;
        }
        assertThat(homeWins / (double) SIMS).as("home win-rate").isBetween(0.42, 0.48);
        assertThat(draws / (double) SIMS).as("draw-rate").isBetween(0.24, 0.28);
    }

    @Test
    void meanGoalsPerMatchAreRealistic() {
        List<SeedClub> ranked = rankedByStrength();
        List<TeamMatchInput> teams = List.of(
                strength(ranked.get(0)),
                strength(ranked.get(ranked.size() / 2)),
                strength(ranked.get(ranked.size() - 1)));

        long totalGoals = 0;
        int games = 0;
        for (int a = 0; a < teams.size(); a++) {
            for (int b = 0; b < teams.size(); b++) {
                for (int i = 0; i < SIMS / 4; i++) {
                    var m = engine.simulate(teams.get(a), teams.get(b), 13000L + a * 100_000L + b * 1000L + i);
                    totalGoals += m.homeGoals() + m.awayGoals();
                    games++;
                }
            }
        }
        double mean = totalGoals / (double) games;
        assertThat(mean).as("mean goals per match").isBetween(2.4, 3.0);
    }
}
```

- [ ] **Step 2: Run the harness and read the actual rates**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchCalibrationTest'`
Expected: it may FAIL on one or more bands with the initial constants. Note the reported actual values (AssertJ prints e.g. `favorite win-rate ... 0.55`).

- [ ] **Step 3: Tune the constants in `MatchMath` to hit the bands**

Adjust only the four knobs in `MatchMath`, each targeting one band (they are near-orthogonal):
- **Favorite win-rate** too low → raise `GAIN` (e.g. 2.2 → 2.6); too high → lower it.
- **Home win / draw split** off → raise/lower `HOME_XG` (more home wins ⇒ larger `HOME_XG`).
- **Mean goals** too high → lower `ANCHOR`; too low → raise it.
- If goals and spread fight each other, `REF` is the pivot: nudge `REF` toward the harness's average matchup differential (temporarily print `strong`/`weak`/`mid` `attack()`/`defense()` to find it), then re-tune `ANCHOR`/`GAIN`.

Re-run Step 2 after each change until all three tests pass. Keep every constant a plain literal in the table.

If the three bands prove **mutually unsatisfiable** with the real roster spread (e.g. the strongest and weakest seeded squads are too close for a 60–70% favorite rate without pushing goals or the home edge out of band), stop tuning and escalate: report the closest achievable rates for all three bands. This is a genuine finding about the seed data / targets, not a tuning failure — the human decides whether to widen a band or accept the closest fit.

- [ ] **Step 4: Run the full suite (final calibration must not break behavioral tests)**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: PASS. If a statistical band elsewhere shifted, apply the same judgement as Task 2 Step 7 (widen a now-comfortably-exceeded band; never loosen a determinism/conservation invariant). Confirm `MatchMathTest` still passes (it is property-based and constant-relative, so tuning does not break it). Re-run `FinanceRolloverIT` in isolation if it flakes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/match/MatchCalibrationTest.java \
        backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java
git commit -m "test(match): calibration harness pins the realistic-league targets; final tuning

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Notes for the executor

- **Determinism is preserved throughout.** Changing threshold values does not change the number or order of RNG draws; the same seed still yields one deterministic match (a different one than before the recalibration). This is why there are no golden-scoreline expectations to regenerate.
- **`MatchMath` visibility:** package-private is sufficient — both engines and both new tests live in `br.com.carvoeiromanager.match`.
- **Do not touch** `TeamStrengthCalculator`, card/injury/foul/set-piece logic, any DTO, or the frontend. Those are out of scope for this sub-feature.
- **Never push.** Local commits only; main is shared.
