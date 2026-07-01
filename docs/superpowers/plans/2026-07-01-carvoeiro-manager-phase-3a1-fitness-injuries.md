# Phase 3a-1: Fitness & Injuries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persistent, age- and fatigue-linked player fitness plus severity-tiered injuries so squad rotation across a congested calendar becomes a real managerial decision — for the human and AI clubs alike — without breaking the engine's deterministic-replay guarantee.

**Architecture:** A lazy day-based fitness model: `Player` stores a base fitness (0–100) + `last_match_date`; effective fitness is computed on read as `base + daysRested × recoveryRate`, capped at 100. A pure `FitnessCalculator` owns the recovery/decay/multiplier/injury-chance formulas. Fitness feeds match strength (a per-player multiplier), injury risk, and AI XI selection. Age effects are encapsulated in an `AgeBand` enum; injury severity in an `InjurySeverity` enum. All new randomness runs on the existing dedicated `injuryRng`, leaving match/event RNG byte-identical.

**Tech Stack:** Java 25 / Spring Boot, JPA, Flyway, PostgreSQL, JUnit 5 + AssertJ + Testcontainers (backend); React + TypeScript + Vite, MUI, Vitest + React Testing Library, TanStack Query (frontend).

## Global Constraints

- **Determinism:** match/event RNG draws must stay byte-identical. New draws (injury chance, severity, layoff) run only on the separate `injuryRng`; append them after existing draws.
- **Standards:** immutability by default; `BigDecimal` for money; `LocalDate`/`Instant` (never `java.util.Date`); UUID ids; constructor injection; enums with behavior; records for DTOs; Controller→Service→Repository layering; DTOs never expose JPA entities.
- **Coverage:** ≥80%; Given/When/Then; descriptive test names.
- **Fitness constants (single source, in `FitnessCalculator`):** `RECOVERY_BASE = 5.0` pts/day, `DECAY_BASE = 30.0`, `BASE_INJURY = 0.025`, `INJURY_FITNESS_K = 1.5`. `fitnessMult = 0.80 + 0.20 × (fitness/100)`.
- **AgeBand factors:** YOUNG ≤23 (rec 1.15 / inj 1.0 / dec 0.95); PRIME 24–29 (1.0/1.0/1.0); EXPERIENCED 30–32 (0.9/1.2/1.10); VETERAN ≥33 (0.8/1.4/1.20).
- **InjurySeverity layoffs:** KNOCK 1; MINOR 2–4; MAJOR 5–12. Distribution: KNOCK 60% / MINOR 30% / MAJOR 10%.
- **Low-fitness soft-warning threshold:** effective fitness `< 50` (non-blocking).
- **Commits:** per the maintainer's standing rule, the human runs every `git commit`/`git push`. Each task's commit step gives the exact message to use — prepare the commit and hand it over; do not commit automatically.
- **Spec:** `docs/superpowers/specs/2026-07-01-carvoeiro-manager-phase-3a1-fitness-injuries-design.md`.

**Build/test commands:**
- Backend all: `cd backend && ./gradlew test`
- Backend single: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.FitnessCalculatorTest"`
- Frontend all: `cd frontend && npm run test -- --run`
- Frontend single: `cd frontend && npm run test -- --run FitnessBar`

---

### Task 1: `AgeBand` and `InjurySeverity` enums

Pure enums with behavior. No dependencies; fully unit-testable.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/AgeBand.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/InjurySeverity.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/AgeBandTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/InjurySeverityTest.java`

**Interfaces:**
- Produces: `AgeBand.of(int age) -> AgeBand`; instance `double recoveryFactor()`, `double injuryFactor()`, `double decayFactor()`.
- Produces: `InjurySeverity` values `NONE, KNOCK, MINOR, MAJOR`; `InjurySeverity roll(java.util.Random rng)` (distribution 60/30/10, never returns `NONE`); instance `int layoffMatches(java.util.Random rng)` (`NONE` → 0).

- [ ] **Step 1: Write the failing tests**

```java
// AgeBandTest.java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgeBandTest {

    @Test
    void classifiesAgeIntoBands() {
        assertThat(AgeBand.of(19)).isEqualTo(AgeBand.YOUNG);
        assertThat(AgeBand.of(23)).isEqualTo(AgeBand.YOUNG);
        assertThat(AgeBand.of(24)).isEqualTo(AgeBand.PRIME);
        assertThat(AgeBand.of(29)).isEqualTo(AgeBand.PRIME);
        assertThat(AgeBand.of(30)).isEqualTo(AgeBand.EXPERIENCED);
        assertThat(AgeBand.of(32)).isEqualTo(AgeBand.EXPERIENCED);
        assertThat(AgeBand.of(33)).isEqualTo(AgeBand.VETERAN);
        assertThat(AgeBand.of(39)).isEqualTo(AgeBand.VETERAN);
    }

    @Test
    void veteransRecoverSlowerAndAreMoreInjuryProneThanYouth() {
        assertThat(AgeBand.VETERAN.recoveryFactor()).isLessThan(AgeBand.YOUNG.recoveryFactor());
        assertThat(AgeBand.VETERAN.injuryFactor()).isGreaterThan(AgeBand.YOUNG.injuryFactor());
        assertThat(AgeBand.VETERAN.decayFactor()).isGreaterThan(AgeBand.YOUNG.decayFactor());
    }

    @Test
    void primeIsTheNeutralBaseline() {
        assertThat(AgeBand.PRIME.recoveryFactor()).isEqualTo(1.0);
        assertThat(AgeBand.PRIME.injuryFactor()).isEqualTo(1.0);
        assertThat(AgeBand.PRIME.decayFactor()).isEqualTo(1.0);
    }
}
```

```java
// InjurySeverityTest.java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.assertj.core.api.Assertions.assertThat;

class InjurySeverityTest {

    @Test
    void noneHasZeroLayoff() {
        assertThat(InjurySeverity.NONE.layoffMatches(new Random(1))).isZero();
    }

    @Test
    void layoffStaysWithinTierRange() {
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            assertThat(InjurySeverity.KNOCK.layoffMatches(rng)).isEqualTo(1);
            assertThat(InjurySeverity.MINOR.layoffMatches(rng)).isBetween(2, 4);
            assertThat(InjurySeverity.MAJOR.layoffMatches(rng)).isBetween(5, 12);
        }
    }

    @Test
    void rollNeverReturnsNoneAndFollowsDistribution() {
        Random rng = new Random(7);
        int knock = 0, minor = 0, major = 0;
        for (int i = 0; i < 10_000; i++) {
            InjurySeverity s = InjurySeverity.roll(rng);
            assertThat(s).isNotEqualTo(InjurySeverity.NONE);
            switch (s) { case KNOCK -> knock++; case MINOR -> minor++; case MAJOR -> major++; default -> {} }
        }
        assertThat(knock).isGreaterThan(minor);   // ~60% vs ~30%
        assertThat(minor).isGreaterThan(major);   // ~30% vs ~10%
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.player.AgeBandTest" --tests "br.com.carvoeiromanager.player.InjurySeverityTest"`
Expected: FAIL — `AgeBand`/`InjurySeverity` do not exist (compilation error).

- [ ] **Step 3: Write the enums**

```java
// AgeBand.java
package br.com.carvoeiromanager.player;

/** Age-band modifiers for fitness recovery, injury risk, and match fitness decay. */
public enum AgeBand {
    YOUNG(1.15, 1.0, 0.95),
    PRIME(1.0, 1.0, 1.0),
    EXPERIENCED(0.9, 1.2, 1.10),
    VETERAN(0.8, 1.4, 1.20);

    private final double recoveryFactor;
    private final double injuryFactor;
    private final double decayFactor;

    AgeBand(double recoveryFactor, double injuryFactor, double decayFactor) {
        this.recoveryFactor = recoveryFactor;
        this.injuryFactor = injuryFactor;
        this.decayFactor = decayFactor;
    }

    public static AgeBand of(int age) {
        if (age <= 23) return YOUNG;
        if (age <= 29) return PRIME;
        if (age <= 32) return EXPERIENCED;
        return VETERAN;
    }

    public double recoveryFactor() { return recoveryFactor; }
    public double injuryFactor() { return injuryFactor; }
    public double decayFactor() { return decayFactor; }
}
```

```java
// InjurySeverity.java
package br.com.carvoeiromanager.player;

import java.util.Random;

/** Injury severity tiers with their layoff ranges (in matches) and draw distribution. */
public enum InjurySeverity {
    NONE(0, 0),
    KNOCK(1, 1),
    MINOR(2, 4),
    MAJOR(5, 12);

    private final int minMatches;
    private final int maxMatches;

    InjurySeverity(int minMatches, int maxMatches) {
        this.minMatches = minMatches;
        this.maxMatches = maxMatches;
    }

    /** A concrete layoff length within this tier's range. NONE returns 0. */
    public int layoffMatches(Random rng) {
        if (this == NONE) return 0;
        return minMatches + rng.nextInt(maxMatches - minMatches + 1);
    }

    /** Draws an actual injury tier (never NONE): KNOCK 60%, MINOR 30%, MAJOR 10%. */
    public static InjurySeverity roll(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.60) return KNOCK;
        if (r < 0.90) return MINOR;
        return MAJOR;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.player.AgeBandTest" --tests "br.com.carvoeiromanager.player.InjurySeverityTest"`
Expected: PASS.

- [ ] **Step 5: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/AgeBand.java \
        backend/src/main/java/br/com/carvoeiromanager/player/InjurySeverity.java \
        backend/src/test/java/br/com/carvoeiromanager/player/AgeBandTest.java \
        backend/src/test/java/br/com/carvoeiromanager/player/InjurySeverityTest.java
git commit -m "feat(fitness): add AgeBand and InjurySeverity enums"
```

---

### Task 2: `Player` fitness state + migration `V15__fitness.sql`

Add persistent fitness fields and mutators to `Player`; migrate the schema.

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__fitness.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerFitnessTest.java`

**Interfaces:**
- Consumes: `InjurySeverity` (Task 1).
- Produces on `Player`: `int getFitness()`, `LocalDate getLastMatchDate()`, `InjurySeverity getInjurySeverity()`, `void recordAppearance(int newBaseFitness, LocalDate matchDate)`, `void applyInjury(InjurySeverity severity, int matches)`. Modified: `tickInjury()` clears severity at 0; `resetSeasonState()` resets fitness state. New players default `fitness=100`, `lastMatchDate=null`, `injurySeverity=NONE`.

- [ ] **Step 1: Write the failing test**

```java
// PlayerFitnessTest.java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerFitnessTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 25, "BRA",
                70, 70, 70, 70, 10, 70, 72);
    }

    @Test
    void newPlayerStartsFullyFitWithNoLastMatchDateAndNoInjury() {
        Player p = player();
        assertThat(p.getFitness()).isEqualTo(100);
        assertThat(p.getLastMatchDate()).isNull();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
    }

    @Test
    void recordAppearanceStoresBaseFitnessAndMatchDate() {
        Player p = player();
        LocalDate d = LocalDate.of(2026, 3, 1);
        p.recordAppearance(63, d);
        assertThat(p.getFitness()).isEqualTo(63);
        assertThat(p.getLastMatchDate()).isEqualTo(d);
    }

    @Test
    void applyInjuryStampsSeverityAndLayoff() {
        Player p = player();
        p.applyInjury(InjurySeverity.MINOR, 3);
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.MINOR);
        assertThat(p.getInjuryMatches()).isEqualTo(3);
        assertThat(p.isEligible()).isFalse();
    }

    @Test
    void tickInjuryClearsSeverityWhenLayoffReachesZero() {
        Player p = player();
        p.applyInjury(InjurySeverity.KNOCK, 1);
        p.tickInjury();
        assertThat(p.getInjuryMatches()).isZero();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
        assertThat(p.isEligible()).isTrue();
    }

    @Test
    void resetSeasonStateRestoresFullFitnessAndClearsInjury() {
        Player p = player();
        p.recordAppearance(40, LocalDate.of(2026, 5, 5));
        p.applyInjury(InjurySeverity.MAJOR, 8);
        p.resetSeasonState();
        assertThat(p.getFitness()).isEqualTo(100);
        assertThat(p.getLastMatchDate()).isNull();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
        assertThat(p.getInjuryMatches()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerFitnessTest"`
Expected: FAIL — `getFitness`/`recordAppearance`/`applyInjury` undefined.

- [ ] **Step 3: Write the migration**

```sql
-- V15__fitness.sql
ALTER TABLE players ADD COLUMN fitness INT NOT NULL DEFAULT 100;
ALTER TABLE players ADD COLUMN last_match_date DATE;
ALTER TABLE players ADD COLUMN injury_severity VARCHAR(10) NOT NULL DEFAULT 'NONE';
```

- [ ] **Step 4: Add fields, mappings and methods to `Player.java`**

Add imports (`LocalDate` is `java.time.LocalDate`). Add fields near `injuryMatches`:

```java
    @Column(name = "fitness", nullable = false)
    private int fitness = 100;

    @Column(name = "last_match_date")
    private java.time.LocalDate lastMatchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "injury_severity", nullable = false, length = 10)
    private InjurySeverity injurySeverity = InjurySeverity.NONE;
```

Add getters and mutators (place beside the existing injury methods):

```java
    public int getFitness() { return fitness; }
    public java.time.LocalDate getLastMatchDate() { return lastMatchDate; }
    public InjurySeverity getInjurySeverity() { return injurySeverity; }

    /** Persists post-match base fitness and the date the player featured. */
    public void recordAppearance(int newBaseFitness, java.time.LocalDate matchDate) {
        this.fitness = Math.max(0, Math.min(100, newBaseFitness));
        this.lastMatchDate = matchDate;
    }

    /** Stamps an injury: severity tier + layoff length in matches. */
    public void applyInjury(InjurySeverity severity, int matches) {
        this.injurySeverity = severity;
        this.injuryMatches += matches;
    }
```

Update the existing `tickInjury()` to clear severity when the layoff ends:

```java
    public void tickInjury() {
        if (this.injuryMatches > 0) {
            this.injuryMatches--;
            if (this.injuryMatches == 0) {
                this.injurySeverity = InjurySeverity.NONE;
            }
        }
    }
```

Update the existing `resetSeasonState()` to also reset fitness state (keep the existing form/card/suspension/injury resets):

```java
    public void resetSeasonState() {
        this.form = 0;
        this.yellowCards = 0;
        this.suspensionRemaining = 0;
        this.injuryMatches = 0;
        this.injurySeverity = InjurySeverity.NONE;
        this.fitness = 100;
        this.lastMatchDate = null;
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerFitnessTest"`
Expected: PASS.

- [ ] **Step 6: Commit** (hand to maintainer)

```bash
git add backend/src/main/resources/db/migration/V15__fitness.sql \
        backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerFitnessTest.java
git commit -m "feat(fitness): persist player fitness, last-match-date and injury severity"
```

---

### Task 3: `FitnessCalculator` (pure formulas)

The single source for recovery, decay, match multiplier, and injury chance.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FitnessCalculatorTest.java`

**Interfaces:**
- Consumes: `AgeBand` (Task 1).
- Produces: `@Service FitnessCalculator` with
  - `int effectiveFitness(int base, LocalDate lastMatchDate, LocalDate onDate, int age)`
  - `int decayedBaseFitness(int effectiveAtKickoff, int stamina, int age)`
  - `double fitnessMult(int effectiveFitness)`
  - `double injuryChance(int effectiveFitness, int age)`

- [ ] **Step 1: Write the failing test**

```java
// FitnessCalculatorTest.java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class FitnessCalculatorTest {

    private final FitnessCalculator calc = new FitnessCalculator();
    private static final LocalDate D0 = LocalDate.of(2026, 3, 1);

    @Test
    void neverPlayedReportsFullFitness() {
        assertThat(calc.effectiveFitness(100, null, D0, 25)).isEqualTo(100);
    }

    @Test
    void recoveryClimbsWithRestDaysAndCapsAt100() {
        // base 60, prime age, RECOVERY_BASE 5/day: +15 after 3 days -> 75
        assertThat(calc.effectiveFitness(60, D0, D0.plusDays(3), 27)).isEqualTo(75);
        // 20 days would overshoot -> capped at 100
        assertThat(calc.effectiveFitness(60, D0, D0.plusDays(20), 27)).isEqualTo(100);
    }

    @Test
    void veteransRecoverSlowerThanYouthOverTheSameRest() {
        int youth = calc.effectiveFitness(50, D0, D0.plusDays(4), 20);
        int veteran = calc.effectiveFitness(50, D0, D0.plusDays(4), 34);
        assertThat(youth).isGreaterThan(veteran);
    }

    @Test
    void playingDecaysBaseFitnessAndHigherStaminaDecaysLess() {
        int highStamina = calc.decayedBaseFitness(100, 90, 27);
        int lowStamina = calc.decayedBaseFitness(100, 60, 27);
        assertThat(highStamina).isGreaterThan(lowStamina); // fitter engine tires less
        assertThat(highStamina).isLessThan(100);           // still tired
    }

    @Test
    void fitnessMultIsOneAtFullAndBottomsAt080() {
        assertThat(calc.fitnessMult(100)).isEqualTo(1.0);
        assertThat(calc.fitnessMult(50)).isEqualTo(0.90);
        assertThat(calc.fitnessMult(0)).isEqualTo(0.80);
    }

    @Test
    void injuryChanceRisesAsFitnessFallsAndWithAge() {
        double fresh = calc.injuryChance(100, 27);
        double tired = calc.injuryChance(40, 27);
        double tiredVeteran = calc.injuryChance(40, 34);
        assertThat(tired).isGreaterThan(fresh);
        assertThat(tiredVeteran).isGreaterThan(tired);
        assertThat(fresh).isEqualTo(0.025);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.FitnessCalculatorTest"`
Expected: FAIL — `FitnessCalculator` does not exist.

- [ ] **Step 3: Write the implementation**

```java
// FitnessCalculator.java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AgeBand;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure fitness math: lazy day-based recovery, match decay, the match-strength
 * multiplier, and fitness/age-linked injury probability. No repository access.
 */
@Service
public class FitnessCalculator {

    static final double RECOVERY_BASE = 5.0;   // fitness points recovered per rest day (prime age)
    static final double DECAY_BASE = 30.0;     // base fitness lost for a full match (prime, stamina 70)
    static final double BASE_INJURY = 0.025;   // injury chance at full fitness, prime age
    static final double INJURY_FITNESS_K = 1.5;

    /** Effective fitness at {@code onDate}: base plus recovery for days rested, capped at 100. */
    public int effectiveFitness(int base, LocalDate lastMatchDate, LocalDate onDate, int age) {
        if (lastMatchDate == null) {
            return 100;
        }
        long daysRested = Math.max(0, ChronoUnit.DAYS.between(lastMatchDate, onDate));
        double recovered = base + daysRested * RECOVERY_BASE * AgeBand.of(age).recoveryFactor();
        return (int) Math.round(Math.min(100.0, recovered));
    }

    /** New base fitness after playing a full match from the given kickoff fitness. */
    public int decayedBaseFitness(int effectiveAtKickoff, int stamina, int age) {
        double decay = DECAY_BASE * clamp(70.0 / stamina, 0.7, 1.4) * AgeBand.of(age).decayFactor();
        return (int) Math.max(0, Math.round(effectiveAtKickoff - decay));
    }

    /** Match-strength multiplier: 1.0 at 100%, 0.90 at 50%, 0.80 at 0%. */
    public double fitnessMult(int effectiveFitness) {
        return 0.80 + 0.20 * (effectiveFitness / 100.0);
    }

    /** Per-match injury probability, rising as fitness falls and with age. */
    public double injuryChance(int effectiveFitness, int age) {
        double fatigue = 1.0 - effectiveFitness / 100.0;
        return BASE_INJURY * (1.0 + INJURY_FITNESS_K * fatigue) * AgeBand.of(age).injuryFactor();
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.FitnessCalculatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java \
        backend/src/test/java/br/com/carvoeiromanager/match/FitnessCalculatorTest.java
git commit -m "feat(fitness): add FitnessCalculator recovery/decay/injury formulas"
```

---

### Task 4: Fitness multiplier in `TeamStrengthCalculator`

A tired XI is measurably weaker. Non-breaking overload keeps existing callers working.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TeamStrengthCalculator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java` (add a case)

**Interfaces:**
- Produces: `TeamMatchInput compute(List<Player> xi, TacticalProfile profile, Map<UUID,Double> fitnessMultById)`; the existing `compute(xi, profile)` delegates with an empty map (missing player → multiplier 1.0).
- Consumes (in Task 6): `FixtureSimulator` supplies `fitnessMultById`.

- [ ] **Step 1: Write the failing test** (append to `TeamStrengthCalculatorTest`)

```java
    @Test
    void lowFitnessLowersAttackAndDefenseVsFullFitness() {
        List<Player> xi = standardXi();
        TacticalProfile prof = profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi);
        java.util.Map<UUID, Double> tired = new HashMap<>();
        xi.forEach(pl -> tired.put(pl.getId(), 0.80)); // everyone at the 0-fitness multiplier floor

        TeamMatchInput fresh = calc.compute(xi, prof); // defaults to 1.0
        TeamMatchInput drained = calc.compute(xi, prof, tired);

        assertThat(drained.attack()).isLessThan(fresh.attack());
        assertThat(drained.defense()).isLessThan(fresh.defense());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.TeamStrengthCalculatorTest"`
Expected: FAIL — 3-arg `compute` does not exist (compilation error).

- [ ] **Step 3: Modify `TeamStrengthCalculator`**

Add the overload and thread the per-player multiplier through the fold. Replace the existing `compute` method with:

```java
    public TeamMatchInput compute(List<Player> xi, TacticalProfile profile) {
        return compute(xi, profile, java.util.Map.of());
    }

    public TeamMatchInput compute(List<Player> xi, TacticalProfile profile,
                                  java.util.Map<UUID, Double> fitnessMultById) {
        double attackSum = 0.0;
        double outfieldDefenseSum = 0.0;
        double gkDefense = 0.0;
        double staminaSum = 0.0;
        int outfieldCount = 0;

        for (Player p : xi) {
            PlayerRole role = resolveRole(profile, p);
            double fitnessMult = fitnessMultById.getOrDefault(p.getId(), 1.0);
            double mult = (1.0 + 0.02 * p.getForm()) * fitnessMult;
            attackSum += role.attackContribution(p) * mult;
            staminaSum += p.getStamina();
            double def = role.defenseContribution(p) * mult;
            if (p.getPosition() == Position.GK) {
                gkDefense = def;
            } else {
                outfieldDefenseSum += def;
                outfieldCount++;
            }
        }

        double attack = attackSum / xi.size();
        double outfieldDefense = outfieldCount > 0 ? outfieldDefenseSum / outfieldCount : 0.0;
        double defense = 0.6 * outfieldDefense + 0.4 * gkDefense;

        Formation formation = Formation.fromCode(profile.formation());
        attack *= formation.attackMult() * profile.mentality().attackMult();
        defense *= formation.defenseMult() * profile.mentality().defenseMult() * profile.marking().defenseMult();

        double tempo = profile.pressing().tempoMult();
        double aggression = 1.0 + profile.pressing().aggressionAdd() + profile.marking().aggressionAdd();

        double staminaAvg = staminaSum / xi.size();
        double fitnessDecay = DECAY_BASE * profile.pressing().decayMult() * clamp(70.0 / staminaAvg, 0.7, 1.4);

        double setPieceThreat = setPieceThreat(xi, profile);

        return new TeamMatchInput(attack, defense, tempo, aggression, setPieceThreat, fitnessDecay);
    }
```

Add `import java.util.Map;` if not already present. (The existing `formMult` local is now folded into `mult`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.TeamStrengthCalculatorTest"`
Expected: PASS (new case + all existing cases still green).

- [ ] **Step 5: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/TeamStrengthCalculator.java \
        backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java
git commit -m "feat(fitness): apply per-player fitness multiplier to team strength"
```

---

### Task 5: Fitness/age-linked injuries in `MatchOutcomeApplier`

Replace the flat 3% / 1–3-match roll with a fitness- and age-driven chance plus severity tiers.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/InjuryResponse.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java` (constructor + a case)

**Interfaces:**
- Consumes: `FitnessCalculator` (Task 3), `InjurySeverity` (Task 1).
- Produces: `MatchOutcomeApplier(FitnessCalculator fitnessCalculator)` (constructor injection); `void applyInjuries(List<Player> playedXi, Map<UUID,Integer> effectiveFitnessById, Random rng, boolean managed, Summary acc)`; `InjuryResponse(UUID playerId, String playerName, int matches, String severity)`.

- [ ] **Step 1: Update the test to the new constructor and add a severity case**

Change the field initializer at the top of `MatchOutcomeApplierTest`:

```java
    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());
```

Append this test:

```java
    @Test
    void exhaustedPlayersAreInjuredWithASeverityTierAndLayoff() {
        Player p = player(Position.MID);
        java.util.Map<java.util.UUID, Integer> fitness = java.util.Map.of(p.getId(), 1); // near-zero fitness
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        // A fixed seed that is known to trigger at least one injury over the roll.
        applier.applyInjuries(List.of(p), fitness, new java.util.Random(1), true, acc);

        if (p.getInjuryMatches() > 0) {
            assertThat(p.getInjurySeverity()).isNotEqualTo(br.com.carvoeiromanager.player.InjurySeverity.NONE);
            assertThat(acc.newInjuries()).hasSize(1);
            assertThat(acc.newInjuries().get(0).severity())
                    .isEqualTo(p.getInjurySeverity().name());
        } else {
            assertThat(acc.newInjuries()).isEmpty();
        }
    }

    @Test
    void injuryChanceIsHigherForExhaustedThanFreshOverManyRolls() {
        int freshInjuries = countInjuries(100);
        int tiredInjuries = countInjuries(10);
        assertThat(tiredInjuries).isGreaterThan(freshInjuries);
    }

    private int countInjuries(int fitnessValue) {
        int injured = 0;
        java.util.Random rng = new java.util.Random(99);
        for (int i = 0; i < 5000; i++) {
            Player p = player(Position.MID);
            applier.applyInjuries(List.of(p), java.util.Map.of(p.getId(), fitnessValue), rng, false,
                    new MatchOutcomeApplier.Summary());
            if (p.getInjuryMatches() > 0) injured++;
        }
        return injured;
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest"`
Expected: FAIL — constructor takes no args / `applyInjuries` old signature / `InjuryResponse.severity()` missing (compilation error).

- [ ] **Step 3: Update `InjuryResponse`**

```java
// InjuryResponse.java
package br.com.carvoeiromanager.match;

import java.util.UUID;

public record InjuryResponse(UUID playerId, String playerName, int matches, String severity) {
}
```

- [ ] **Step 4: Update `MatchOutcomeApplier`**

Add the field + constructor at the top of the class body:

```java
    private final FitnessCalculator fitnessCalculator;

    public MatchOutcomeApplier(FitnessCalculator fitnessCalculator) {
        this.fitnessCalculator = fitnessCalculator;
    }
```

Replace the whole `applyInjuries` method with:

```java
    /**
     * Rolls injuries for players who played, using a separate {@link Random} seeded off the match
     * seed so match/event RNG is unaffected. Chance rises as effective fitness falls and with age;
     * on a hit a severity tier and its layoff are drawn. Newly injured managed players are recorded.
     */
    public void applyInjuries(List<Player> playedXi, Map<UUID, Integer> effectiveFitnessById,
                              Random rng, boolean managed, Summary acc) {
        for (Player p : playedXi) {
            int fitness = effectiveFitnessById.getOrDefault(p.getId(), 100);
            double chance = fitnessCalculator.injuryChance(fitness, p.getAge());
            if (rng.nextDouble() < chance) {
                br.com.carvoeiromanager.player.InjurySeverity severity =
                        br.com.carvoeiromanager.player.InjurySeverity.roll(rng);
                int matches = severity.layoffMatches(rng);
                p.applyInjury(severity, matches);
                if (managed) {
                    acc.newInjuries().add(new InjuryResponse(p.getId(), p.getName(), matches, severity.name()));
                }
            }
        }
    }
```

(Leave `applyFixture`, `applyTeam`, and `applyNonPlayers` unchanged.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest"`
Expected: PASS.

- [ ] **Step 6: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java \
        backend/src/main/java/br/com/carvoeiromanager/match/InjuryResponse.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java
git commit -m "feat(fitness): fitness/age-linked injury chance with severity tiers"
```

---

### Task 6: Wire fitness into `FixtureSimulator` (decay on play + injury inputs)

Resolve effective fitness at the fixture date, feed strength + injuries, and persist post-match decay.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FitnessLifecycleIT.java` (new)

**Interfaces:**
- Consumes: `FitnessCalculator` (Task 3), `TeamStrengthCalculator.compute(xi, profile, fitnessMultById)` (Task 4), `MatchOutcomeApplier.applyInjuries(xi, effectiveFitnessById, rng, managed, acc)` (Task 5), `LineupService.bestXi(clubId, onDate)` (Task 7).
- Produces: after a fixture, every player who featured has decayed `fitness` and `lastMatchDate == fixture.matchDate`.

> **Sequencing note:** this task references `bestXi(clubId, matchDate)` from Task 7. Implement Task 7's `bestXi` signature change together with this task, or stub the 2-arg overload first. The commit for this task and Task 7 may be combined if the reviewer prefers.

- [ ] **Step 1: Write the failing integration test**

This IT is the reusable scaffold Tasks 7–9 copy (autowires + `createCareer()` + `advanceOneMatch()`), lifted from `InjuryEngineIT`.

```java
// FitnessLifecycleIT.java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FitnessLifecycleIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("fitness_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH is played, skipping any TRANSFER stops. */
    private AdvanceResponse advanceOneMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.save().id(), c.userId());
            if ("MATCH".equals(r.type())) return r;
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void playersWhoFeatureLoseFitnessAndRecordTheMatchDate() {
        // Given a fresh career: everyone is fully fit with no last-match date
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        assertThat(playerRepository.findBySaveGameId(saveGameId))
                .allSatisfy(p -> {
                    assertThat(p.getFitness()).isEqualTo(100);
                    assertThat(p.getLastMatchDate()).isNull();
                });

        // When a few matchdays are played (guarantees the managed club has featured)
        for (int i = 0; i < 3; i++) advanceOneMatch(career);

        // Then at least some players have lost fitness and recorded a match date...
        List<Player> played = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getLastMatchDate() != null)
                .toList();
        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(p -> assertThat(p.getFitness()).isLessThan(100));

        // ...while players who never featured keep full stored fitness.
        assertThat(playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getLastMatchDate() == null))
                .allSatisfy(p -> assertThat(p.getFitness()).isEqualTo(100));
    }

    @Test
    void aFatiguedStarterEndsUpLessFitThanAnUnusedPlayer() {
        // Given a fresh career, capture the managed club's first-choice XI
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        List<UUID> firstXi = lineupService.bestXi(clubId).stream().map(Player::getId).toList();
        Player benched = playerRepository.findByClubId(clubId).stream()
                .filter(p -> !firstXi.contains(p.getId()))
                .findFirst().orElseThrow();

        // When several matchdays are played (the first-choice XI keeps starting via bestXi)
        for (int i = 0; i < 4; i++) advanceOneMatch(career);

        // Then a repeat starter is more fatigued than the player who never featured
        Player benchedReloaded = playerRepository.findById(benched.getId()).orElseThrow();
        List<Player> repeatStarters = playerRepository.findByClubId(clubId).stream()
                .filter(p -> firstXi.contains(p.getId()) && p.getLastMatchDate() != null)
                .toList();
        assertThat(repeatStarters).isNotEmpty();
        assertThat(repeatStarters).anySatisfy(starter ->
                assertThat(starter.getFitness()).isLessThan(benchedReloaded.getFitness()));
    }
}
```

> Note: the deterministic "3 games in a week drains harder than a 10-day gap" congestion effect is exercised in the `FitnessCalculatorTest` unit (Task 3) and confirmed live in the Task 14 manual playthrough; the IT above asserts the durable, non-flaky consequences (repeat starters tire, unused players stay fresh).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.FitnessLifecycleIT"`
Expected: FAIL — fitness stays 100 (no decay wired yet).

- [ ] **Step 3: Inject `FitnessCalculator` and thread fitness through the fixture sim**

Add `FitnessCalculator` to the constructor (add the field, parameter, and assignment alongside the existing dependencies).

In `simulateFixture`, after `resolveXi(...)` for both sides and before building `TeamMatchInput`, resolve effective fitness at the fixture date:

```java
        final LocalDate matchDate = fixture.getMatchDate();
        Map<UUID, Integer> homeFitness = effectiveFitness(homeXi, matchDate);
        Map<UUID, Integer> awayFitness = effectiveFitness(awayXi, matchDate);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile, fitnessMult(homeFitness));
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile, fitnessMult(awayFitness));
```

Return the two fitness maps from `simulateFixture` (extend the `FixtureSim` record with `Map<UUID,Integer> homeFitness, Map<UUID,Integer> awayFitness`) so `simulateRound` can use them for injuries and decay.

Add these private helpers to `FixtureSimulator`:

```java
    private Map<UUID, Integer> effectiveFitness(List<Player> xi, LocalDate onDate) {
        Map<UUID, Integer> map = new java.util.HashMap<>();
        for (Player p : xi) {
            map.put(p.getId(),
                    fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge()));
        }
        return map;
    }

    private Map<UUID, Double> fitnessMult(Map<UUID, Integer> effectiveFitnessById) {
        Map<UUID, Double> map = new java.util.HashMap<>();
        effectiveFitnessById.forEach((id, fit) -> map.put(id, fitnessCalculator.fitnessMult(fit)));
        return map;
    }

    private void applyFitnessDecay(List<Player> xi, Map<UUID, Integer> effectiveFitnessById, LocalDate matchDate) {
        for (Player p : xi) {
            int eff = effectiveFitnessById.getOrDefault(p.getId(), 100);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge());
            p.recordAppearance(newBase, matchDate);
        }
    }
```

In `simulateRound`, update the injury calls to pass the fitness maps and add decay right after (using `sim.homeFitness()` / `sim.awayFitness()` and `fixture.getMatchDate()`):

```java
            matchOutcomeApplier.applyInjuries(sim.homeXi(), sim.homeFitness(), injuryRng, homeManaged, summary);
            matchOutcomeApplier.applyInjuries(sim.awayXi(), sim.awayFitness(), injuryRng, awayManaged, summary);

            applyFitnessDecay(sim.homeXi(), sim.homeFitness(), fixture.getMatchDate());
            applyFitnessDecay(sim.awayXi(), sim.awayFitness(), fixture.getMatchDate());
```

The players are already added to `toPersist` and saved by the existing `saveAll`, so decayed fitness persists with no extra write.

Add imports: `java.time.LocalDate`, `java.util.Map`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.FitnessLifecycleIT"`
Expected: PASS.

- [ ] **Step 5: Run the full match-package suite for regressions**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.*"`
Expected: PASS — match scores/events unchanged (injuryRng separate); fitness fields now populated.

- [ ] **Step 6: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
        backend/src/test/java/br/com/carvoeiromanager/match/FitnessLifecycleIT.java
git commit -m "feat(fitness): decay fitness on appearance and feed it to strength + injuries"
```

---

### Task 7: Fitness-adjusted `bestXi` (AI rotation)

AI clubs bench drained players for fresher ones during congestion.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (call site)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java` (add a case)

**Interfaces:**
- Consumes: `FitnessCalculator` (Task 3).
- Produces: `List<Player> bestXi(UUID clubId, LocalDate onDate)`; the existing `bestXi(UUID clubId)` delegates with the club's save `currentDate` (or, where no date is meaningful, ranks on raw overall). `FixtureSimulator.resolveXi` passes `fixture.getMatchDate()`.

- [ ] **Step 1: Write the failing test** (add to `LineupServiceIT`, following its existing seeding style)

```java
    // GIVEN two equally-rated outfield players, one freshly rested (fitness ~100) and one just
    //       played into low fitness on the fixture date
    // WHEN bestXi(clubId, matchDate) is computed
    // THEN the fresher player is selected and the drained one is left out.
    // Assert: bestXi(clubId, date).stream().anyMatch(freshId) == true
    //         bestXi(clubId, date).stream().anyMatch(drainedId) == false
```

Implement concretely: seed a club with 12 outfield players where #11 and #12 share the top overall; set one's `fitness` low with `lastMatchDate` = the query date (no recovery) and the other at 100; assert the fresh one starts and the drained one is benched.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT"`
Expected: FAIL — `bestXi(clubId, date)` does not exist / ranking ignores fitness.

- [ ] **Step 3: Modify `LineupService`**

Inject `FitnessCalculator` (add field, constructor param, assignment).

Add the date-aware overload and make the existing one delegate. Replace the `bestXi(UUID clubId)` signature usage by introducing:

```java
    public List<Player> bestXi(UUID clubId) {
        return bestXi(clubId, null);
    }

    public List<Player> bestXi(UUID clubId, java.time.LocalDate onDate) {
        List<Player> allPlayers = playerRepository.findByClubId(clubId);
        List<Player> eligible = allPlayers.stream().filter(Player::isEligible).toList();

        java.util.Comparator<Player> byRating =
                java.util.Comparator.comparingDouble((Player p) -> rating(p, onDate)).reversed();

        Player bestGk = eligible.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(byRating)
                .orElseGet(() -> allPlayers.stream()
                        .filter(p -> p.getPosition() == Position.GK)
                        .max(byRating)
                        .orElseThrow(() -> new ApiException(
                                "NO_GK_FOUND",
                                "Club " + clubId + " has no goalkeeper at all",
                                HttpStatus.UNPROCESSABLE_CONTENT)));

        List<Player> eligibleOutfield = eligible.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(bestGk.getId()))
                .sorted(byRating)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        List<Player> topOutfield;
        if (eligibleOutfield.size() >= 10) {
            topOutfield = eligibleOutfield.subList(0, 10);
        } else {
            java.util.Set<UUID> usedIds = new java.util.HashSet<>();
            eligibleOutfield.forEach(p -> usedIds.add(p.getId()));
            usedIds.add(bestGk.getId());
            List<Player> ineligibleOutfield = allPlayers.stream()
                    .filter(p -> !usedIds.contains(p.getId()) && p.getPosition() != Position.GK)
                    .sorted(byRating)
                    .toList();
            int needed = 10 - eligibleOutfield.size();
            List<Player> combined = new java.util.ArrayList<>(eligibleOutfield);
            combined.addAll(ineligibleOutfield.subList(0, Math.min(needed, ineligibleOutfield.size())));
            topOutfield = combined;
        }

        return new java.util.ArrayList<>(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(bestGk), topOutfield.stream())
                        .toList());
    }

    /** Fitness-adjusted rating used to rank candidates; raw overall when no date is supplied. */
    private double rating(Player p, java.time.LocalDate onDate) {
        if (onDate == null) {
            return p.getOverall();
        }
        int fit = fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge());
        return p.getOverall() * fitnessCalculator.fitnessMult(fit);
    }
```

(Delete the old `bestXi(UUID clubId)` body that ranked purely by `overall`; the two methods above replace it.)

- [ ] **Step 4: Update the call site in `FixtureSimulator.resolveXi`**

```java
    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId, LocalDate matchDate) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId, matchDate);
    }
```

Update its two callers in `simulateFixture` to pass `fixture.getMatchDate()`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT" --tests "br.com.carvoeiromanager.match.FitnessLifecycleIT"`
Expected: PASS.

- [ ] **Step 6: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java
git commit -m "feat(fitness): AI bestXi ranks on fitness-adjusted rating"
```

---

### Task 8: Expose fitness + injury severity on the squad API

`PlayerResponse` gains effective fitness (at the save's current date) and injury severity.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (`getSquad`, `toPlayerResponse`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SquadContractInfoIT.java` (add a case) — or a new `SquadFitnessIT`.

**Interfaces:**
- Produces: `PlayerResponse` with two new fields appended: `int fitness`, `String injurySeverity`.
- Consumes: `FitnessCalculator` (already injected in Task 7), `SaveGame.getCurrentDate()`.

- [ ] **Step 1: Write the failing test** (add to `SquadContractInfoIT`, reusing its save/club seeding)

```java
    // GIVEN a seeded squad where one starter has been played (fitness < 100, injury_severity NONE)
    //       and one player is injured with severity MINOR
    // WHEN getSquad(saveGameId, clubId, userId) is called
    // THEN the played player's PlayerResponse.fitness() reflects effective fitness at currentDate
    //      (recovering from the stored base) and injurySeverity() == "NONE"
    // AND the injured player's injurySeverity() == "MINOR" and eligible() == false.
```

Implement concretely: after seeding, set one player's `recordAppearance(60, save.getCurrentDate().minusDays(2))` and another's `applyInjury(InjurySeverity.MINOR, 3)`, save, then assert on the mapped `PlayerResponse` fields (fitness ≈ `60 + 2×5 = 70` for prime age; severity strings).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.SquadContractInfoIT"`
Expected: FAIL — `PlayerResponse.fitness()`/`injurySeverity()` do not exist.

- [ ] **Step 3: Update `PlayerResponse`**

Append two fields (keep all existing fields in order):

```java
public record PlayerResponse(
        UUID id,
        String name,
        String position,
        int age,
        int overall,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        int form,
        int yellowCards,
        int suspensionRemaining,
        int injuryMatches,
        boolean eligible,
        String role,
        int contractYears,
        BigDecimal wageDemand,
        int fitness,
        String injurySeverity
) {
}
```

- [ ] **Step 4: Update `getSquad` and `toPlayerResponse` in `LineupService`**

Resolve the save's current date once in `getSquad` and pass it down:

```java
    public SquadResponse getSquad(UUID saveGameId, UUID clubId, UUID userId) {
        java.time.LocalDate onDate = saveGameService.requireOwned(saveGameId, userId).getCurrentDate();
        requireOwnedClub(saveGameId, clubId, userId);
        Map<UUID, PlayerRole> roleByPlayer = currentRoles(saveGameId, clubId);
        List<PlayerResponse> responses = playerRepository.findByClubId(clubId).stream()
                .map(p -> toPlayerResponse(p, roleByPlayer.get(p.getId()), onDate))
                .toList();
        return new SquadResponse(responses);
    }
```

Change `toPlayerResponse` to take the date and populate the new fields:

```java
    private PlayerResponse toPlayerResponse(Player p, PlayerRole role, java.time.LocalDate onDate) {
        int fitness = fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge());
        return new PlayerResponse(
                p.getId(),
                p.getName(),
                p.getPosition().name(),
                p.getAge(),
                p.getOverall(),
                p.getPace(),
                p.getFinishing(),
                p.getPassing(),
                p.getTackling(),
                p.getGoalkeeping(),
                p.getStamina(),
                p.getForm(),
                p.getYellowCards(),
                p.getSuspensionRemaining(),
                p.getInjuryMatches(),
                p.isEligible(),
                role != null ? role.name() : null,
                p.getContractYears(),
                contractValuation.wageDemand(p.getOverall(), p.getAge()),
                fitness,
                p.getInjurySeverity().name());
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.SquadContractInfoIT"`
Expected: PASS.

- [ ] **Step 6: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/test/java/br/com/carvoeiromanager/match/SquadContractInfoIT.java
git commit -m "feat(fitness): expose effective fitness and injury severity on squad API"
```

---

### Task 9: Soft low-fitness starter warning in the advance preview

Non-blocking nudge to rotate: list saved starters below the fitness threshold before a match.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvancePreviewResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvancePreviewService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (new query)
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/AdvancePreviewServiceIT.java` (add a case; if the file does not exist, create it modelled on other season ITs)

**Interfaces:**
- Produces: `LineupService.lowFitnessStarters(UUID saveGameId, UUID clubId, LocalDate onDate, int threshold) -> List<LowFitnessStarterResponse>` where `LowFitnessStarterResponse(String playerId, String playerName, int fitness)` is nested in `AdvancePreviewResponse`.
- Produces: `AdvancePreviewResponse` gains `List<LowFitnessStarterResponse> lowFitnessStarters` (last field).

- [ ] **Step 1: Write the failing test** (add to the season advance-preview IT)

```java
    // GIVEN the managed club plays next and a saved starter is at effective fitness 30 (< 50)
    // WHEN preview(saveGameId, userId) is called
    // THEN response.lowFitnessStarters() contains that player with fitness 30
    // AND the low-fitness starter does NOT populate ineligibleStarters (fitness never blocks).
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.season.AdvancePreviewServiceIT"`
Expected: FAIL — `lowFitnessStarters` missing.

- [ ] **Step 3: Add the nested DTO field to `AdvancePreviewResponse`**

Add the record component `List<LowFitnessStarterResponse> lowFitnessStarters` as the final field, and the nested record:

```java
    /** A saved starter below the fitness soft-warning threshold. Non-blocking. */
    public record LowFitnessStarterResponse(String playerId, String playerName, int fitness) {
    }
```

Update the constructor call in `AdvancePreviewService.preview(...)` to pass the new list as the final argument.

- [ ] **Step 4: Add the `LineupService.lowFitnessStarters` query**

```java
    /** Saved starters whose effective fitness at {@code onDate} is below {@code threshold}. */
    public List<AdvancePreviewLowFitness> lowFitnessStarters(UUID saveGameId, UUID clubId,
                                                             java.time.LocalDate onDate, int threshold) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                        .filter(LineupPlayer::isStarting)
                        .map(lp -> playerRepository.findById(lp.getPlayerId()).orElse(null))
                        .filter(p -> p != null)
                        .map(p -> new AdvancePreviewLowFitness(p.getId().toString(), p.getName(),
                                fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge())))
                        .filter(row -> row.fitness() < threshold)
                        .toList())
                .orElseGet(List::of);
    }
```

To avoid a `season → match` package cycle, return a small shared record `AdvancePreviewLowFitness(String playerId, String playerName, int fitness)` in the `match` package, and have `AdvancePreviewService` map it to `AdvancePreviewResponse.LowFitnessStarterResponse`. Create:

```java
// backend/src/main/java/br/com/carvoeiromanager/match/AdvancePreviewLowFitness.java
package br.com.carvoeiromanager.match;

public record AdvancePreviewLowFitness(String playerId, String playerName, int fitness) {
}
```

- [ ] **Step 5: Populate the field in `AdvancePreviewService.preview`**

Inside the `if (managedClubPlaysNext)` block, after computing `ineligibleStarters`:

```java
                lowFitnessStarters = lineupService.lowFitnessStarters(saveGameId, managedClubId, currentDate, 50).stream()
                        .map(r -> new AdvancePreviewResponse.LowFitnessStarterResponse(r.playerId(), r.playerName(), r.fitness()))
                        .toList();
```

Declare `List<AdvancePreviewResponse.LowFitnessStarterResponse> lowFitnessStarters = List.of();` next to `ineligibleStarters`, and pass it as the final constructor argument.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "br.com.carvoeiromanager.season.AdvancePreviewServiceIT"`
Expected: PASS.

- [ ] **Step 7: Run the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: PASS (≥80% coverage maintained).

- [ ] **Step 8: Commit** (hand to maintainer)

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/AdvancePreviewResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/AdvancePreviewService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/AdvancePreviewLowFitness.java \
        backend/src/test/java/br/com/carvoeiromanager/season/AdvancePreviewServiceIT.java
git commit -m "feat(fitness): soft low-fitness starter warning in advance preview"
```

---

### Task 10: Frontend API types

Mirror the backend DTO changes in the typed client.

**Files:**
- Modify: `frontend/src/api/types.ts`

**Interfaces:**
- Produces: `PlayerResponse` gains `fitness: number`, `injurySeverity: 'NONE' | 'KNOCK' | 'MINOR' | 'MAJOR'`. `Injury` gains `severity: string`. `AdvancePreviewResponse` gains `lowFitnessStarters: LowFitnessStarter[]`; new `LowFitnessStarter` type.

- [ ] **Step 1: Edit `types.ts`**

In `PlayerResponse`, after `wageDemand: number` add:

```ts
  fitness: number
  injurySeverity: 'NONE' | 'KNOCK' | 'MINOR' | 'MAJOR'
```

In `Injury`, after `matches: number` add:

```ts
  severity: 'KNOCK' | 'MINOR' | 'MAJOR'
```

Add the new type and extend `AdvancePreviewResponse`:

```ts
export type LowFitnessStarter = {
  playerId: string
  playerName: string
  fitness: number
}
```

In `AdvancePreviewResponse`, after `defaultStandingsCompetitionId: string | null` add:

```ts
  lowFitnessStarters: LowFitnessStarter[]
```

- [ ] **Step 2: Type-check**

Run: `cd frontend && npm run build`
Expected: PASS (tsc has no errors). If `npm run build` is heavy, run `cd frontend && npx tsc --noEmit`.

- [ ] **Step 3: Commit** (hand to maintainer)

```bash
git add frontend/src/api/types.ts
git commit -m "feat(fitness): add fitness/severity fields to API types"
```

---

### Task 11: `FitnessBar` component

A small reusable green→amber→red bar with the percentage.

**Files:**
- Create: `frontend/src/features/squad/FitnessBar.tsx`
- Test: `frontend/src/features/squad/FitnessBar.test.tsx`

**Interfaces:**
- Produces: `FitnessBar({ fitness }: { fitness: number })` — renders a labelled bar; color `error` < 40, `warning` < 70, else `success`. Exposes `aria-label={`Condição ${fitness}%`}` and shows the numeric `%`.

- [ ] **Step 1: Write the failing test**

```tsx
// FitnessBar.test.tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { FitnessBar } from './FitnessBar'

describe('FitnessBar', () => {
  it('shows the percentage and an accessible label', () => {
    render(<FitnessBar fitness={73} />)
    expect(screen.getByText('73%')).toBeInTheDocument()
    expect(screen.getByLabelText('Condição 73%')).toBeInTheDocument()
  })

  it('uses the danger color when fitness is very low', () => {
    render(<FitnessBar fitness={25} />)
    const bar = screen.getByLabelText('Condição 25%')
    expect(bar.getAttribute('data-severity')).toBe('error')
  })

  it('uses the success color when fully fit', () => {
    render(<FitnessBar fitness={95} />)
    expect(screen.getByLabelText('Condição 95%').getAttribute('data-severity')).toBe('success')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- --run FitnessBar`
Expected: FAIL — module `./FitnessBar` not found.

- [ ] **Step 3: Write the component**

```tsx
// FitnessBar.tsx
import { Box, LinearProgress, Typography } from '@mui/material'

type Severity = 'error' | 'warning' | 'success'

function severityFor(fitness: number): Severity {
  if (fitness < 40) return 'error'
  if (fitness < 70) return 'warning'
  return 'success'
}

export function FitnessBar({ fitness }: { fitness: number }) {
  const clamped = Math.max(0, Math.min(100, fitness))
  const severity = severityFor(clamped)
  return (
    <Box
      aria-label={`Condição ${clamped}%`}
      data-severity={severity}
      sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 72 }}
    >
      <LinearProgress
        variant="determinate"
        value={clamped}
        color={severity}
        sx={{ flex: 1, height: 6, borderRadius: 3 }}
      />
      <Typography variant="caption" sx={{ minWidth: 30, textAlign: 'right' }}>{clamped}%</Typography>
    </Box>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm run test -- --run FitnessBar`
Expected: PASS.

- [ ] **Step 5: Commit** (hand to maintainer)

```bash
git add frontend/src/features/squad/FitnessBar.tsx frontend/src/features/squad/FitnessBar.test.tsx
git commit -m "feat(fitness): add reusable FitnessBar component"
```

---

### Task 12: Show fitness + injury severity on the Squad screen

Add a "Condição" column (desktop table + mobile card) and severity in the availability badge.

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Modify: `frontend/src/features/squad/SquadPage.test.tsx` (add a case)

**Interfaces:**
- Consumes: `FitnessBar` (Task 11), `PlayerResponse.fitness`/`injurySeverity` (Task 10).

- [ ] **Step 1: Write the failing test** (add to `SquadPage.test.tsx`, reusing its existing query-client + mock-fetch harness and player fixtures)

```tsx
  // Extend the existing squad fixture so one player has fitness: 45 and injurySeverity: 'NONE',
  // and one has injuryMatches: 3, injurySeverity: 'MINOR', eligible: false.
  it('renders a fitness bar and injury severity for players', async () => {
    // ...render SquadPage with the mocked squad...
    expect(await screen.findByLabelText('Condição 45%')).toBeInTheDocument()
    expect(screen.getByText(/Lesionado \(Leve\) 3/)).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm run test -- --run SquadPage`
Expected: FAIL — no fitness bar / severity label rendered.

- [ ] **Step 3: Update `SquadPage.tsx`**

Add the import:

```tsx
import { FitnessBar } from './FitnessBar'
```

Add a severity label helper near `formLabel`:

```tsx
const SEVERITY_LABEL: Record<string, string> = {
  KNOCK: 'Pancada',
  MINOR: 'Leve',
  MAJOR: 'Grave',
}
```

Update `availabilityBadge` so injuries include severity:

```tsx
  function availabilityBadge(player: PlayerResponse) {
    if (player.suspensionRemaining > 0) {
      return <Typography component="span" color="error" sx={{ ml: 1, fontSize: '0.85em' }}>(Suspenso {player.suspensionRemaining})</Typography>
    }
    if (player.injuryMatches > 0) {
      const tier = SEVERITY_LABEL[player.injurySeverity] ?? ''
      const label = tier ? `(Lesionado (${tier}) ${player.injuryMatches})` : `(Lesionado ${player.injuryMatches})`
      return <Typography component="span" color="error" sx={{ ml: 1, fontSize: '0.85em' }}>{label}</Typography>
    }
    return null
  }
```

In the desktop table head, add a header cell after `Fôlego`:

```tsx
                  <TableCell align="center">Condição</TableCell>
```

In the desktop table body row, add a cell after the `Fôlego` (`player.stamina`) cell:

```tsx
                      <TableCell align="center"><FitnessBar fitness={player.fitness} /></TableCell>
```

In the mobile card `Stack`, add the bar under the OVR chip (inside the row `Stack`, after the OVR `Chip`):

```tsx
                      <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm run test -- --run SquadPage`
Expected: PASS.

- [ ] **Step 5: Commit** (hand to maintainer)

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(fitness): show fitness bar and injury severity on squad screen"
```

---

### Task 13: Injury severity in news/advance report + low-fitness advance warning

Surface severity in the post-advance injury list and show the soft rotation nudge.

**Files:**
- Modify: the advance-report component that renders `AdvanceResponse.newInjuries` (locate with grep below)
- Modify: the advance component that consumes `AdvancePreviewResponse` (the "Avançar" flow)
- Test: the corresponding `*.test.tsx` for whichever component renders injuries / the advance preview

**Interfaces:**
- Consumes: `Injury.severity`, `AdvancePreviewResponse.lowFitnessStarters` (Task 10).

- [ ] **Step 1: Locate the components**

Run:
```bash
cd frontend && grep -rln "newInjuries\|ineligibleStarters\|lowFitnessStarters\|Lesionado" src
```
Expected: identifies the advance-report and advance-preview components (e.g. under `src/features/season` / `src/features/news`).

- [ ] **Step 2: Write the failing test**

In the advance-report component's test, extend the mocked `AdvanceResponse.newInjuries` with `severity: 'MAJOR'` and assert the rendered text includes the Portuguese severity (e.g. `Grave`). In the advance-preview test, add a `lowFitnessStarters` entry and assert a non-blocking warning renders (e.g. `screen.getByText(/pouca condição|baixa condição/i)`), and that the "Avançar" button remains **enabled** (fitness never blocks).

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd frontend && npm run test -- --run` (target the two touched test files)
Expected: FAIL.

- [ ] **Step 4: Implement**

In the injury list rendering, map severity via the same label table as Task 12 (extract `SEVERITY_LABEL` into a small shared module, e.g. `frontend/src/features/squad/injurySeverity.ts`, and import it in both places to stay DRY):

```ts
// frontend/src/features/squad/injurySeverity.ts
export const SEVERITY_LABEL: Record<string, string> = {
  KNOCK: 'Pancada',
  MINOR: 'Leve',
  MAJOR: 'Grave',
}
```

Update Task 12's `SquadPage.tsx` import to use this shared module (replace the inline `SEVERITY_LABEL` const with `import { SEVERITY_LABEL } from './injurySeverity'`).

Render each injury as e.g. `{injury.playerName} — {SEVERITY_LABEL[injury.severity]} ({injury.matches} jogos)`.

In the advance-preview flow, when `preview.lowFitnessStarters.length > 0`, render a non-blocking `Alert severity="warning"` listing the names (e.g. `Jogadores com pouca condição: <names>`), and ensure this warning does **not** feed the advance-disable condition (only `ineligibleStarters`, `windowClosingAfterAdvance` + `pendingTransfers` gate the button, as today).

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm run test -- --run`
Expected: PASS.

- [ ] **Step 6: Full frontend suite + build**

Run: `cd frontend && npm run test -- --run && npm run build`
Expected: PASS.

- [ ] **Step 7: Commit** (hand to maintainer)

```bash
git add frontend/src
git commit -m "feat(fitness): injury severity in reports + soft low-fitness advance warning"
```

---

### Task 14: End-to-end verification

Full manual playthrough proving the loop.

- [ ] **Step 1: Backend green**

Run: `cd backend && ./gradlew test`
Expected: PASS, coverage ≥80%.

- [ ] **Step 2: Frontend green**

Run: `cd frontend && npm run test -- --run`
Expected: PASS.

- [ ] **Step 3: Manual playthrough**

Run: `docker-compose up` (Postgres), start backend + frontend (`npm run dev`). Create a Criciúma career and:
1. Play a congested run of fixtures with the same XI; confirm those players' Condição bars drop and recover on rest days.
2. Confirm at least one injury appears with a severity label in the advance report and squad screen, and the player becomes ineligible for the layoff.
3. Confirm the advance preview shows the soft low-fitness warning when a tired starter is selected, but still lets you advance.
4. Confirm AI clubs rotate (spot-check a rival's XI changes across congested rounds).

- [ ] **Step 4: Final commit** (hand to maintainer)

```bash
git add -A
git commit -m "chore(fitness): phase 3a-1 fitness & injuries complete"
```

---

## Self-Review notes (author)

- **Spec coverage:** persistent lazy fitness (Tasks 2–3, 6), performance effect (Task 4), fitness+age injuries with severity (Tasks 1, 5), AI rotation (Task 7), squad API + UI (Tasks 8, 10–12), news + soft warning (Tasks 9, 13), determinism preserved (injuryRng untouched by match/event draws — Tasks 5–6), multi-season reset (Task 2). All spec sections map to a task.
- **Deferred (3a-2), intentionally absent:** bench, in-match subs, live match view, partial-minutes decay, minute-timed injury events.
- **Type consistency:** `effectiveFitness`, `decayedBaseFitness`, `fitnessMult`, `injuryChance`, `recordAppearance`, `applyInjury`, `bestXi(clubId, onDate)`, `applyInjuries(xi, effectiveFitnessById, rng, managed, acc)`, `PlayerResponse(...fitness, injurySeverity)`, `InjuryResponse(...matches, severity)` are used identically across tasks.
