# Training System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager assign each graduated (non-prospect) player a training focus attribute
and intensity, which biases that player's existing season-end attribute change and carries a
small, intensity-scaled injury-risk trade-off; AI clubs get an automatic position-based heuristic.

**Architecture:** A new `TrainingService` (mirroring the existing `PlayerAgingService`) runs
immediately after `PlayerAgingService.applyAgeCurve` inside `SeasonRolloverService`'s existing
per-player rollover loop, consuming the same shared seeded `Random`. Two new nullable/defaulted
columns on `players` persist the managed club's per-player assignment; AI clubs' assignments are
never persisted, they're computed fresh each rollover from a fixed position→attribute heuristic.
A new bulk `PUT .../training` endpoint and a new "Treino" tab on `SquadPage.tsx` let the manager
set assignments, reusing the exact hydrate-once/local-edit-map/bulk-save-mutation pattern already
used for lineup role assignment.

**Tech Stack:** Java 21 / Spring Boot / PostgreSQL / Flyway (backend), React 19 / TypeScript /
MUI / React Query (frontend). JUnit 5 + AssertJ + Testcontainers (backend tests), Vitest + React
Testing Library (frontend tests).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-14-training-system-design.md` — every value below is
  copied verbatim from it.
- Training only applies to non-prospect players (`Player.isYouthProspect() == false`).
- The 6 trainable anchor attributes are exactly: `Attribute.VELOCIDADE`, `FINALIZACAO`, `PASSE`,
  `DESARME`, `REFLEXOS`, `FOLEGO`. No other attribute is ever a valid training focus.
- Intensity bonus (added to the focused attribute, on top of that season's age-curve delta,
  clamped to `[1, 99]`): `LEVE +1`, `NORMAL +2`, `INTENSA +3`.
- Injury risk (rolled once per trained player per rollover, `rng.nextDouble() < risk`, then
  `player.addInjury(1 + rng.nextInt(2))`): `LEVE 0%`, `NORMAL 3%`, `INTENSA 8%`.
- AI-club heuristic (fixed, never persisted, always `NORMAL` intensity):
  `GK → REFLEXOS, DEF → DESARME, MID → PASSE, ATT → FINALIZACAO`.
- Free agents (`Player.isFreeAgent() == true`) never train — no persisted focus, no heuristic.
- A managed player's `trainingFocus`/`trainingIntensity` is a standing instruction: it persists
  across rollovers until the manager changes it. `trainingFocus == null` means "not training" and
  must produce byte-for-byte the same rollover outcome as before this feature existed.
- New migration file: `V26__player_training.sql` (the last migration is `V25`).
- New `ErrorCode.INVALID_TRAINING_FOCUS` uses `HttpStatus.UNPROCESSABLE_CONTENT` (422), matching
  the project's existing `INVALID_*` codes.
- Backend verification: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
  from `backend/`. Frontend verification: `cd frontend && npx tsc --noEmit && npx vitest run`.

---

## Task 1: Data layer — migration, `TrainingIntensity` enum, `Player` entity fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V26__player_training.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingIntensity.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerTest.java`

**Interfaces:**
- Produces: `TrainingIntensity` enum with 3 constants (`LEVE`, `NORMAL`, `INTENSA`). `Player`
  gains `getTrainingFocus(): Attribute` (nullable), `getTrainingIntensity(): TrainingIntensity`
  (never null, defaults to `NORMAL`), and `setTrainingAssignment(Attribute focus,
  TrainingIntensity intensity): void`. These are consumed by Task 2 (`TrainingService`), Task 3
  (`SeasonRolloverService`), and Task 4 (`LineupService`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/player/PlayerTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Teste", Position.MID,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
    }

    @Test
    void defaultsToNoTrainingFocusAndNormalIntensity() {
        Player p = player();

        assertThat(p.getTrainingFocus()).isNull();
        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.NORMAL);
    }

    @Test
    void settingATrainingAssignmentPersistsBothFields() {
        Player p = player();

        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.INTENSA);

        assertThat(p.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
    }

    @Test
    void settingANullIntensityFallsBackToNormal() {
        Player p = player();

        p.setTrainingAssignment(Attribute.DESARME, null);

        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.NORMAL);
    }

    @Test
    void settingANullFocusClearsIt() {
        Player p = player();
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);

        p.setTrainingAssignment(null, TrainingIntensity.LEVE);

        assertThat(p.getTrainingFocus()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerTest"`
Expected: FAIL to compile — `getTrainingFocus`, `getTrainingIntensity`, `setTrainingAssignment`,
and `TrainingIntensity` don't exist yet.

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V26__player_training.sql`:

```sql
ALTER TABLE players ADD COLUMN training_focus VARCHAR(20);
ALTER TABLE players ADD COLUMN training_intensity VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
```

- [ ] **Step 4: Create the `TrainingIntensity` enum**

Create `backend/src/main/java/br/com/carvoeiromanager/player/TrainingIntensity.java`:

```java
package br.com.carvoeiromanager.player;

/** How hard a player trains: scales both the season-end attribute bonus and injury risk. */
public enum TrainingIntensity {
    LEVE, NORMAL, INTENSA
}
```

- [ ] **Step 5: Add the fields to `Player`**

In `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`, add these two fields
right after the existing `listedReason` field (line 96, just before `season_appearances`):

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "training_focus", length = 20)
    private Attribute trainingFocus;

    @Enumerated(EnumType.STRING)
    @Column(name = "training_intensity", nullable = false, length = 10)
    private TrainingIntensity trainingIntensity = TrainingIntensity.NORMAL;
```

Then add these three methods right after `getListedReason()` (after line 268):

```java
    public Attribute getTrainingFocus() { return trainingFocus; }
    public TrainingIntensity getTrainingIntensity() { return trainingIntensity; }

    /** Sets the manager's standing training instruction for this player; a null focus opts out. */
    public void setTrainingAssignment(Attribute focus, TrainingIntensity intensity) {
        this.trainingFocus = focus;
        this.trainingIntensity = intensity == null ? TrainingIntensity.NORMAL : intensity;
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V26__player_training.sql \
        backend/src/main/java/br/com/carvoeiromanager/player/TrainingIntensity.java \
        backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerTest.java
git commit -m "feat(player): add training focus/intensity fields"
```

---

## Task 2: `TrainingService` — bonus math, injury roll, AI heuristic

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java`

**Interfaces:**
- Consumes: `Player.getPace/getFinishing/getPassing/getTackling/getGoalkeeping/getStamina()`,
  `Player.evolveAttributes(...)`, `Player.addInjury(int)` (all from `Player.java`, unchanged this
  task); `OverallCalculator.compute(Position, int, int, int, int, int, int): int` (existing,
  unchanged); `TrainingIntensity` (Task 1).
- Produces: `TrainingService.applyTraining(Player player, Attribute focus, TrainingIntensity
  intensity, Random rng): void` and `static TrainingService.heuristicFocusFor(Position
  position): Attribute`. Both consumed by Task 3 (`SeasonRolloverService`).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingServiceTest {

    private final TrainingService training = new TrainingService(new OverallCalculator());

    private Player player(Position position, int attr) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", position,
                24, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    @Test
    void doesNothingWhenFocusIsNull() {
        // Given a player with no training focus
        Player p = player(Position.MID, 50);

        // When training is applied with a null focus
        training.applyTraining(p, null, TrainingIntensity.INTENSA, new Random(1));

        // Then nothing changes: no attribute bump, no injury roll consumed
        assertThat(p.getFinishing()).isEqualTo(50);
        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void leveAddsOneToTheFocusedAttributeOnly() {
        // Given a player with all attributes at 50
        Player p = player(Position.MID, 50);

        // When trained Leve on Finalização
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.LEVE, new Random(1));

        // Then only Finalização rose, by exactly 1
        assertThat(p.getFinishing()).isEqualTo(51);
        assertThat(p.getPace()).isEqualTo(50);
        assertThat(p.getPassing()).isEqualTo(50);
        assertThat(p.getTackling()).isEqualTo(50);
        assertThat(p.getGoalkeeping()).isEqualTo(50);
        assertThat(p.getStamina()).isEqualTo(50);
    }

    @Test
    void normalAddsTwoAndIntensaAddsThree() {
        Player normal = player(Position.MID, 50);
        Player intensa = player(Position.MID, 50);

        training.applyTraining(normal, Attribute.PASSE, TrainingIntensity.NORMAL, new Random(1));
        training.applyTraining(intensa, Attribute.PASSE, TrainingIntensity.INTENSA, new Random(1));

        assertThat(normal.getPassing()).isEqualTo(52);
        assertThat(intensa.getPassing()).isEqualTo(53);
    }

    @Test
    void theBonusIsClampedAtNinetyNine() {
        // Given a player already maxed at 99
        Player p = player(Position.ATT, 99);

        // When trained Intensa (+3 would overflow to 102)
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1));

        // Then it stays capped at 99
        assertThat(p.getFinishing()).isEqualTo(99);
    }

    @Test
    void overallIsRecomputedAfterTheBonus() {
        Player p = player(Position.ATT, 50);
        int overallBefore = p.getOverall();

        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1));

        assertThat(p.getOverall()).isGreaterThan(overallBefore);
    }

    @Test
    void leveNeverRollsAnInjuryRegardlessOfSeed() {
        // 0% risk at Leve: no seed should ever produce an injury.
        for (int seed = 0; seed < 20; seed++) {
            Player p = player(Position.MID, 50);
            training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.LEVE, new Random(seed));
            assertThat(p.getInjuryMatches()).isZero();
        }
    }

    @Test
    void intensaCanRollAOneToTwoMatchInjury() {
        // Given seed 4608, verified to draw 0.0520... as its first nextDouble() — under
        // Intensa's 8% risk threshold (new Random(4608).nextDouble() == 0.05202727943619023)
        Player p = player(Position.MID, 50);

        // When trained Intensa with that seed
        training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.INTENSA, new Random(4608));

        // Then an injury of 1 or 2 matches was applied
        assertThat(p.getInjuryMatches()).isBetween(1, 2);
    }

    @Test
    void heuristicFocusMapsEachPositionToItsMostValuableAnchor() {
        assertThat(TrainingService.heuristicFocusFor(Position.GK)).isEqualTo(Attribute.REFLEXOS);
        assertThat(TrainingService.heuristicFocusFor(Position.DEF)).isEqualTo(Attribute.DESARME);
        assertThat(TrainingService.heuristicFocusFor(Position.MID)).isEqualTo(Attribute.PASSE);
        assertThat(TrainingService.heuristicFocusFor(Position.ATT)).isEqualTo(Attribute.FINALIZACAO);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: FAIL to compile — `TrainingService` doesn't exist yet.

- [ ] **Step 3: Implement `TrainingService`**

Create `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java`:

```java
package br.com.carvoeiromanager.player;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Manager-directed training: biases one of a player's six anchor attributes beyond what the
 * season's automatic age curve already produced, at an intensity-scaled injury-risk cost.
 * Called from {@link br.com.carvoeiromanager.season.SeasonRolloverService} right after
 * {@link PlayerAgingService#applyAgeCurve}, consuming the same shared seeded {@link Random} so
 * a rollover stays reproducible.
 */
@Component
public class TrainingService {

    private final OverallCalculator overallCalculator;

    public TrainingService(OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
    }

    /** No-op when {@code focus} is null — "not training" must leave the player untouched. */
    public void applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng) {
        if (focus == null) {
            return;
        }

        int bonus = switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };

        int pace = player.getPace();
        int finishing = player.getFinishing();
        int passing = player.getPassing();
        int tackling = player.getTackling();
        int goalkeeping = player.getGoalkeeping();
        int stamina = player.getStamina();

        switch (focus) {
            case VELOCIDADE -> pace = clamp(pace + bonus);
            case FINALIZACAO -> finishing = clamp(finishing + bonus);
            case PASSE -> passing = clamp(passing + bonus);
            case DESARME -> tackling = clamp(tackling + bonus);
            case REFLEXOS -> goalkeeping = clamp(goalkeeping + bonus);
            case FOLEGO -> stamina = clamp(stamina + bonus);
            default -> throw new IllegalArgumentException("Attribute is not trainable: " + focus);
        }

        int overall = overallCalculator.compute(player.getPosition(),
                pace, finishing, passing, tackling, goalkeeping, stamina);
        player.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);

        double risk = switch (intensity) {
            case LEVE -> 0.0;
            case NORMAL -> 0.03;
            case INTENSA -> 0.08;
        };
        if (rng.nextDouble() < risk) {
            player.addInjury(1 + rng.nextInt(2));
        }
    }

    /** Fixed position-based focus used for every AI-controlled club's players. */
    public static Attribute heuristicFocusFor(Position position) {
        return switch (position) {
            case GK -> Attribute.REFLEXOS;
            case DEF -> Attribute.DESARME;
            case MID -> Attribute.PASSE;
            case ATT -> Attribute.FINALIZACAO;
        };
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java \
        backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java
git commit -m "feat(player): add TrainingService bonus/injury math and AI heuristic"
```

---

## Task 3: Wire `TrainingService` into `SeasonRolloverService`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java`

**Interfaces:**
- Consumes: `TrainingService.applyTraining(...)` and `TrainingService.heuristicFocusFor(...)`
  (Task 2); `Player.getTrainingFocus()/getTrainingIntensity()` (Task 1).
- Produces: no new public interface — this task only changes `SeasonRolloverService`'s internal
  rollover behavior. Task 4 and Task 5 do not depend on anything new from this task.

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `SeasonRolloverServiceIT` class (right before the final
closing `}` of the class, after `rolloverArchivesSeasonPlayerStatsAndPurgesFixtureAppearance`),
and add these three imports alongside the existing `import br.com.carvoeiromanager.player.Player;`
line:

```java
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.TrainingIntensity;
```

```java
    @Test
    void trainingFocusBoostsAManagedPlayerAndPersistsAcrossRollover() {
        // Given a career with a young (age 20) managed player trained on Finalização at Intensa
        Career c = createCareer();
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setAge(20);
        trainee.setContractYears(5);
        trainee.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.INTENSA);
        int finishingBefore = trainee.getFinishing();
        playerRepository.save(trainee);

        List<Player> others = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.getId().equals(trainee.getId())).toList();
        others.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(others);

        finishSeason(c);

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then Finalização rose by at least the Intensa bonus (+3): the age-21 curve delta is
        // always >= 0 (see PlayerAgingService.delta), so the combined result is a floor, not an
        // exact value. The assignment itself is a standing instruction and survives unchanged.
        Player after = playerRepository.findById(trainee.getId()).orElseThrow();
        assertThat(after.getFinishing()).isGreaterThanOrEqualTo(Math.min(99, finishingBefore + 3));
        assertThat(after.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
        assertThat(after.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
    }

    @Test
    void aiClubPlayersGetTheHeuristicFocusEvenWithoutManagerInput() {
        // Given a career with an AI club's young (age 20) attacker, never given an assignment
        Career c = createCareer();
        Player aiAttacker = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null && !p.getClubId().equals(c.clubId())
                        && !p.isYouthProspect() && p.getPosition() == Position.ATT)
                .findFirst().orElseThrow();
        aiAttacker.setAge(20);
        aiAttacker.setContractYears(5);
        int finishingBefore = aiAttacker.getFinishing();
        playerRepository.save(aiAttacker);

        List<Player> others = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.getId().equals(aiAttacker.getId())).toList();
        others.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(others);

        finishSeason(c);

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then Finalização (the heuristic focus for ATT) rose by at least Normal's +2 bonus,
        // even though no manager ever set a training assignment for this AI-club player.
        Player after = playerRepository.findById(aiAttacker.getId()).orElseThrow();
        assertThat(after.getFinishing()).isGreaterThanOrEqualTo(Math.min(99, finishingBefore + 2));
    }

    @Test
    void aManagedPlayerWithNoFocusKeepsItNullAfterRollover() {
        // Given a career whose managed players are never given a training assignment
        Career c = createCareer();

        finishSeason(c);

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then no managed-club player was auto-assigned a training focus
        List<Player> managed = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(managed).allMatch(p -> p.getTrainingFocus() == null);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT"`
Expected: the two positive tests (`trainingFocusBoosts...`, `aiClubPlayersGetTheHeuristic...`)
FAIL because `TrainingService` is never called yet — Finalização only rose by the plain age-curve
delta (0-3 for a 21-year-old), which can be less than the asserted +3/+2 floor.
`aManagedPlayerWithNoFocusKeepsItNullAfterRollover` passes trivially already (nothing sets it) —
that's expected; it stays green through this task as a guard against a future regression.

- [ ] **Step 3: Wire `TrainingService` into the rollover loop**

In `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`:

Add this import alongside the existing `import br.com.carvoeiromanager.player.PlayerAgingService;`
line (line 27):

```java
import br.com.carvoeiromanager.player.TrainingIntensity;
import br.com.carvoeiromanager.player.TrainingService;
```

Add a new field right after `private final PlayerAgingService playerAgingService;` (line 70):

```java
    private final TrainingService trainingService;
```

Add a new constructor parameter right after `PlayerAgingService playerAgingService,` (line 105)
and assign it right after `this.playerAgingService = playerAgingService;` (line 131):

```java
                                 TrainingService trainingService,
```
```java
        this.trainingService = trainingService;
```

In the `rollover(...)` method's per-player loop, change this block (lines 247-262):

```java
            // Carried over: reset per-season state, then tick the contract.
            player.resetSeasonState();
            if (!freeAgent) {
                player.setContractYears(player.getContractYears() - 1);
                if (player.getContractYears() <= 0) {
                    if (managed) {
                        expiredContracts.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                        player.setClubId(null);          // lapses into the free-agent pool
                        player.setContractYears(0);
                    } else {
                        player.setContractYears(2 + rng.nextInt(3)); // AI auto-renews
                    }
                }
            }
            toSave.add(player);
```

to:

```java
            // Carried over: reset per-season state, then apply training, then tick the contract.
            player.resetSeasonState();
            if (managed) {
                trainingService.applyTraining(player, player.getTrainingFocus(),
                        player.getTrainingIntensity(), rng);
            } else if (!freeAgent) {
                trainingService.applyTraining(player, TrainingService.heuristicFocusFor(player.getPosition()),
                        TrainingIntensity.NORMAL, rng);
            }
            if (!freeAgent) {
                player.setContractYears(player.getContractYears() - 1);
                if (player.getContractYears() <= 0) {
                    if (managed) {
                        expiredContracts.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                        player.setClubId(null);          // lapses into the free-agent pool
                        player.setContractYears(0);
                    } else {
                        player.setContractYears(2 + rng.nextInt(3)); // AI auto-renews
                    }
                }
            }
            toSave.add(player);
```

Note: `trainingService.applyTraining` must run **after** `player.resetSeasonState()` — that call
zeroes `injuryMatches` (see `Player.resetSeasonState()`), so an injury applied before it would be
silently wiped. Placing it after is what makes the injury-risk trade-off actually stick.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT"`
Expected: PASS (all tests in the file, including the 3 new ones).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL — confirms no other rollover-adjacent test (contracts, youth, finance,
calendar, transfer-listing rollover ITs) broke. None of those files assert exact post-rollover
attribute/overall values (verified during planning via a repo-wide grep for
`getOverall()`/`getPace()`/etc. across every `season`/`career` test file — zero matches), so no
existing test is expected to need adjustment.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java
git commit -m "feat(season): apply training during rollover for managed and AI clubs"
```

---

## Task 4: Backend API — `PlayerResponse` fields, `LineupService.setTraining`, endpoint

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/SetTrainingRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/TrainingAssignment.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SquadController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java`

**Interfaces:**
- Consumes: `Player.getTrainingFocus()/getTrainingIntensity()/setTrainingAssignment(...)` (Task
  1); `Attribute`, `TrainingIntensity` enums (Task 1, existing `Attribute`).
- Produces: `PlayerResponse.trainingFocus(): String` (nullable) and
  `PlayerResponse.trainingIntensity(): String`, consumed by Task 5's frontend types.
  `LineupService.setTraining(UUID saveGameId, UUID clubId, SetTrainingRequest req, UUID userId):
  SquadResponse` and `PUT /api/v1/save-games/{saveGameId}/clubs/{clubId}/training`, consumed by
  Task 5's `career.ts`.

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `LineupServiceIT` class (right before the final closing
`}`), and add this import alongside the existing imports:

```java
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.TrainingIntensity;
```

```java
    @Test
    void settingTrainingAssignmentsPersistsAndReturnsTheUpdatedSquad() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();
        Player target = playerRepository.findByClubIdAndYouthProspectFalse(clubId).get(0);

        // When
        SquadResponse response = lineupService.setTraining(saveGameId, clubId,
                new SetTrainingRequest(List.of(new TrainingAssignment(target.getId(), "FINALIZACAO", "INTENSA"))),
                userId);

        // Then the response reflects the new assignment
        PlayerResponse updated = response.players().stream()
                .filter(p -> p.id().equals(target.getId())).findFirst().orElseThrow();
        assertThat(updated.trainingFocus()).isEqualTo("FINALIZACAO");
        assertThat(updated.trainingIntensity()).isEqualTo("INTENSA");

        // And it's persisted
        Player persisted = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(persisted.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
        assertThat(persisted.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
    }

    @Test
    void settingAnUntrainableAttributeAsFocusIsRejected() {
        // Given
        Career career = createCareer();
        Player target = playerRepository.findByClubIdAndYouthProspectFalse(career.managedClubId()).get(0);

        // When setting a non-anchor attribute (DRIBLE is not one of the 6 trainable anchors)
        // Then a 422 INVALID_TRAINING_FOCUS is thrown
        assertThatThrownBy(() -> lineupService.setTraining(career.id(), career.managedClubId(),
                new SetTrainingRequest(List.of(new TrainingAssignment(target.getId(), "DRIBLE", "NORMAL"))),
                career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INVALID_TRAINING_FOCUS);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void settingTrainingFocusForAPlayerOutsideTheClubIsRejected() {
        // Given two independent careers
        Career owner = createCareer();
        Career other = createCareer();
        Player foreignPlayer = playerRepository.findByClubIdAndYouthProspectFalse(other.managedClubId()).get(0);

        // When targeting a player who belongs to a different club
        // Then a 404 PLAYER_NOT_FOUND is thrown
        assertThatThrownBy(() -> lineupService.setTraining(owner.id(), owner.managedClubId(),
                new SetTrainingRequest(List.of(new TrainingAssignment(foreignPlayer.getId(), "PASSE", "LEVE"))),
                owner.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT"`
Expected: FAIL to compile — `setTraining`, `SetTrainingRequest`, `TrainingAssignment`, and
`ErrorCode.INVALID_TRAINING_FOCUS` don't exist yet.

- [ ] **Step 3: Add the new `ErrorCode`**

In `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`, insert this line
between `INVALID_TOKEN(HttpStatus.UNAUTHORIZED),` and `INVALID_TRANSFER(HttpStatus.UNPROCESSABLE_CONTENT),`:

```java
    INVALID_TRAINING_FOCUS(HttpStatus.UNPROCESSABLE_CONTENT),
```

- [ ] **Step 4: Add the two new fields to `PlayerResponse`**

In `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`, add two fields at
the end of the record, right after `String listedReason`:

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
        int morale,
        int yellowCards,
        int suspensionRemaining,
        int injuryMatches,
        boolean eligible,
        String role,
        int contractYears,
        BigDecimal value,
        BigDecimal wageDemand,
        int fitness,
        String injurySeverity,
        List<AttributeView> attributes,
        boolean transferListed,
        String listedReason,
        String trainingFocus,
        String trainingIntensity
) {
}
```

- [ ] **Step 5: Create `SetTrainingRequest` and `TrainingAssignment`**

Create `backend/src/main/java/br/com/carvoeiromanager/match/TrainingAssignment.java`:

```java
package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One player's training assignment; {@code focus} is null to opt the player out of training. */
public record TrainingAssignment(@NotNull UUID playerId, String focus, @NotBlank String intensity) {
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/match/SetTrainingRequest.java`:

```java
package br.com.carvoeiromanager.match;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetTrainingRequest(@NotEmpty @Valid List<TrainingAssignment> assignments) {
}
```

- [ ] **Step 6: Add `setTraining` to `LineupService`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java`, add one new import,
`br.com.carvoeiromanager.player.TrainingIntensity`, alongside the existing
`import br.com.carvoeiromanager.player.Player;` line. No other new imports are needed:
`Attribute` is already imported (line 9), and `java.util.Set` is already imported (it's used by
existing code in this file).

Update `toPlayerResponse` (lines 483-515) to pass the two new fields — change the final two lines
of the `return new PlayerResponse(...)` call from:

```java
                attributes,
                p.isTransferListed(),
                p.getListedReason() != null ? p.getListedReason().name() : null);
```

to:

```java
                attributes,
                p.isTransferListed(),
                p.getListedReason() != null ? p.getListedReason().name() : null,
                p.getTrainingFocus() != null ? p.getTrainingFocus().name() : null,
                p.getTrainingIntensity().name());
```

Add this new method and its two private helpers right after `setLineup` (after line 155, before
the `getLineup` method):

```java
    private static final Set<Attribute> TRAINABLE_ATTRIBUTES = Set.of(
            Attribute.VELOCIDADE, Attribute.FINALIZACAO, Attribute.PASSE,
            Attribute.DESARME, Attribute.REFLEXOS, Attribute.FOLEGO);

    /**
     * Bulk-sets each listed player's training focus + intensity for the club within a save game.
     * Every player must already belong to this club (as returned by
     * {@code findByClubIdAndYouthProspectFalse} — youth prospects are never trainable).
     */
    @Transactional
    public SquadResponse setTraining(UUID saveGameId, UUID clubId, SetTrainingRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        Map<UUID, Player> clubPlayers = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));
        for (TrainingAssignment assignment : req.assignments()) {
            Player player = clubPlayers.get(assignment.playerId());
            if (player == null) {
                throw new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player " + assignment.playerId() + " not found in this club");
            }
            Attribute focus = parseTrainingFocus(assignment.focus());
            TrainingIntensity intensity;
            try {
                intensity = TrainingIntensity.valueOf(assignment.intensity());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS,
                        "Unknown training intensity: " + assignment.intensity());
            }
            player.setTrainingAssignment(focus, intensity);
        }
        playerRepository.saveAll(clubPlayers.values());

        return getSquad(saveGameId, clubId, userId);
    }

    private Attribute parseTrainingFocus(String raw) {
        if (raw == null) {
            return null;
        }
        Attribute attribute;
        try {
            attribute = Attribute.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS, "Unknown attribute: " + raw);
        }
        if (!TRAINABLE_ATTRIBUTES.contains(attribute)) {
            throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS, "Attribute is not trainable: " + raw);
        }
        return attribute;
    }
```

- [ ] **Step 7: Add the endpoint to `SquadController`**

In `backend/src/main/java/br/com/carvoeiromanager/match/SquadController.java`, add this method
right after `setLineup` (after line 61, before the class's closing `}`):

```java

    /**
     * Bulk-sets the training focus + intensity for each listed player in the club's squad.
     * Returns the updated squad so the frontend can replace its cached view in one round trip.
     */
    @PutMapping("/training")
    public ResponseEntity<SquadResponse> setTraining(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @Valid @RequestBody SetTrainingRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.setTraining(saveGameId, clubId, request, userId));
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT"`
Expected: PASS (all tests in the file, including the 3 new ones).

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL — the `PlayerResponse` constructor signature change is additive
(2 new trailing fields) and every other production call site (`toPlayerResponse`) was already
updated in this task, so no other test should need adjustment.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java \
        backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/match/SetTrainingRequest.java \
        backend/src/main/java/br/com/carvoeiromanager/match/TrainingAssignment.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/SquadController.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java
git commit -m "feat(match): add the bulk training-assignment endpoint"
```

---

## Task 5: Frontend — types, API client, "Treino" tab on `SquadPage`

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: `PUT /api/v1/save-games/{saveGameId}/clubs/{clubId}/training` (Task 4), returning a
  `SquadResponse` (existing type, unchanged shape besides the 2 new `PlayerResponse` fields).
- Produces: no new interface consumed elsewhere — this is the final task.

- [ ] **Step 1: Write the failing test**

Add `setTraining: vi.fn(),` to the `vi.mock('../../api/career', ...)` factory at the top of
`frontend/src/features/squad/SquadPage.test.tsx` (alongside the existing `setLineup: vi.fn(),`
line), add `setTraining` to the destructured import on the line below it (`import { getSquad,
getLineup, setLineup, getTactics, renewContract, listPlayer, unlistPlayer } from
'../../api/career'` → append `, setTraining`), and add `const mockSetTraining =
vi.mocked(setTraining)` alongside the other `mockX` consts.

Then add this test anywhere inside the `describe('SquadPage', ...)` block, e.g. right after the
existing `'starts on the Plantel tab with the squad table and no visible pitch'` test:

```tsx
  it('lets the user set a training focus/intensity and saves them for the whole squad', async () => {
    mockSetTraining.mockResolvedValue(squad)
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))

    fireEvent.change(
      screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` }),
      { target: { value: 'REFLEXOS' } },
    )
    fireEvent.change(
      screen.getByRole('combobox', { name: `Intensidade de treino de ${GK.name}` }),
      { target: { value: 'INTENSA' } },
    )
    fireEvent.click(screen.getByRole('button', { name: /salvar treino/i }))

    await waitFor(() =>
      expect(mockSetTraining).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({
          assignments: expect.arrayContaining([
            expect.objectContaining({ playerId: GK.id, focus: 'REFLEXOS', intensity: 'INTENSA' }),
          ]),
        }),
        'test-token',
      ),
    )
  })
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: FAIL — there is no "Treino" tab yet.

- [ ] **Step 3: Add the new types**

In `frontend/src/api/types.ts`, add these two fields to `PlayerResponse` right after the existing
`listedReason?: ListedReason | null` line:

```ts
  /** One of the 6 trainable anchor attributes, or null if the player isn't currently training. */
  trainingFocus?: TrainableAttribute | null
  trainingIntensity?: TrainingIntensity
```

Then add these new top-level types right after the `SquadResponse` type:

```ts
export type TrainableAttribute = 'VELOCIDADE' | 'FINALIZACAO' | 'PASSE' | 'DESARME' | 'REFLEXOS' | 'FOLEGO'
export type TrainingIntensity = 'LEVE' | 'NORMAL' | 'INTENSA'

export type TrainingAssignment = {
  playerId: string
  focus: TrainableAttribute | null
  intensity: TrainingIntensity
}

export type SetTrainingRequest = {
  assignments: TrainingAssignment[]
}
```

- [ ] **Step 4: Add the API client function**

In `frontend/src/api/career.ts`, add `SetTrainingRequest` to the type-only import block from
`'./types'` (alongside the existing `PlayerResponse` entry), and add this function right after
`getPlayer`:

```ts
export function setTraining(
  saveGameId: string,
  clubId: string,
  req: SetTrainingRequest,
  token: string,
): Promise<SquadResponse> {
  return apiPut<SquadResponse>(`/save-games/${saveGameId}/clubs/${clubId}/training`, req, token)
}
```

- [ ] **Step 5: Add the "Treino" tab to `SquadPage.tsx`**

In `frontend/src/features/squad/SquadPage.tsx`, change the import line:

```ts
import { getSquad, getLineup, setLineup, renewContract, listPlayer, unlistPlayer } from '../../api/career'
```

to:

```ts
import { getSquad, getLineup, setLineup, setTraining, renewContract, listPlayer, unlistPlayer } from '../../api/career'
```

and change:

```ts
import type { PlayerResponse, PlayerRole, LineupStarter } from '../../api/types'
```

to:

```ts
import type { PlayerResponse, PlayerRole, LineupStarter, TrainableAttribute, TrainingIntensity, TrainingAssignment } from '../../api/types'
```

Add these two pt-BR label maps right after the existing `ROLE_LABEL` constant (after line 93):

```ts
const TRAINABLE_ATTRIBUTE_LABEL: Record<TrainableAttribute, string> = {
  VELOCIDADE: 'Velocidade',
  FINALIZACAO: 'Finalização',
  PASSE: 'Passe',
  DESARME: 'Desarme',
  REFLEXOS: 'Reflexos',
  FOLEGO: 'Fôlego',
}

const INTENSITY_LABEL: Record<TrainingIntensity, string> = {
  LEVE: 'Leve',
  NORMAL: 'Normal',
  INTENSA: 'Intensa',
}
```

Add two new state variables right after `const [tab, setTab] = useState(0)` (line 163):

```ts
  const [trainingAssignments, setTrainingAssignments] = useState<
    Record<string, { focus: TrainableAttribute | null; intensity: TrainingIntensity }>
  >({})
  const [trainingSaveSuccess, setTrainingSaveSuccess] = useState(false)
```

Add a hydration effect right after the existing lineup-hydration `useEffect` (after line 199, the
one guarded by the `hydrated` ref):

```ts
  // Seed the training tab from the squad query exactly once, the same way the lineup form is
  // seeded from its own query above — a separate ref so the two hydrations don't interfere.
  const trainingHydrated = useRef(false)
  useEffect(() => {
    if (trainingHydrated.current || !data) return
    setTrainingAssignments(Object.fromEntries(data.players.map(p => [
      p.id,
      { focus: p.trainingFocus ?? null, intensity: p.trainingIntensity ?? 'NORMAL' },
    ])))
    trainingHydrated.current = true
  }, [data])
```

Add a new mutation right after the existing `mutation` (lineup save) block (after line 220):

```ts
  const trainingMutation = useMutation({
    mutationFn: () => {
      const assignments: TrainingAssignment[] = displayPlayers.map(p => ({
        playerId: p.id,
        focus: trainingAssignments[p.id]?.focus ?? null,
        intensity: trainingAssignments[p.id]?.intensity ?? 'NORMAL',
      }))
      return setTraining(saveGameId, clubId, { assignments }, token ?? '')
    },
    onSuccess: () => {
      setTrainingSaveSuccess(true)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
    },
  })
```

Add two change handlers and two select builders right after `changeRole` (after line 320):

```ts
  function changeTrainingFocus(player: PlayerResponse, focus: TrainableAttribute | ''): void {
    setTrainingAssignments(prev => ({
      ...prev,
      [player.id]: { focus: focus === '' ? null : focus, intensity: prev[player.id]?.intensity ?? 'NORMAL' },
    }))
  }

  function changeTrainingIntensity(player: PlayerResponse, intensity: TrainingIntensity): void {
    setTrainingAssignments(prev => ({
      ...prev,
      [player.id]: { focus: prev[player.id]?.focus ?? null, intensity },
    }))
  }
```

Add these two select builders right after `roleSelect` (after line 447):

```ts
  function trainingFocusSelect(player: PlayerResponse) {
    const value = trainingAssignments[player.id]?.focus ?? ''
    return (
      <NativeSelect
        value={value}
        onChange={e => changeTrainingFocus(player, e.target.value as TrainableAttribute | '')}
        inputProps={{ 'aria-label': `Foco de treino de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        <option value="">Nenhum</option>
        {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
          <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
        ))}
      </NativeSelect>
    )
  }

  function trainingIntensitySelect(player: PlayerResponse) {
    const value = trainingAssignments[player.id]?.intensity ?? 'NORMAL'
    return (
      <NativeSelect
        value={value}
        onChange={e => changeTrainingIntensity(player, e.target.value as TrainingIntensity)}
        inputProps={{ 'aria-label': `Intensidade de treino de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
          <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
        ))}
      </NativeSelect>
    )
  }
```

Add a third `Tab` right after the existing `<Tab label="Táticas de jogo" .../>` (line 508):

```tsx
        <Tab label="Treino" id="squad-tab-2" aria-controls="squad-tabpanel-2" />
```

Add a new tabpanel right after the existing `tabpanel-1` `</Box>` (after line 613, before
`<PlayerDetails`):

```tsx
      <Box role="tabpanel" hidden={tab !== 2} id="squad-tabpanel-2" aria-labelledby="squad-tab-2">
        <TableContainer component={Paper} variant="outlined" sx={{ mb: 2, borderRadius: 2 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Nome</TableCell>
                <TableCell align="center">Pos</TableCell>
                <TableCell align="center">Foco</TableCell>
                <TableCell align="center">Intensidade</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {displayPlayers.map(player => (
                <TableRow key={player.id}>
                  <TableCell>{playerNameButton(player)}</TableCell>
                  <TableCell align="center">{positionLabel(player.position)}</TableCell>
                  <TableCell align="center">{trainingFocusSelect(player)}</TableCell>
                  <TableCell align="center">{trainingIntensitySelect(player)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <Button
          color="secondary"
          disabled={trainingMutation.isPending}
          onClick={() => trainingMutation.mutate()}
        >
          {trainingMutation.isPending ? 'Salvando…' : 'Salvar treino'}
        </Button>
      </Box>
```

Add a new `Snackbar` right after the existing lineup-save `Snackbar` (after line 641, before the
listing-outcome `Snackbar`):

```tsx
      {/* Training save outcome, shown the same way as the lineup save toast above. */}
      <Snackbar
        open={trainingSaveSuccess || trainingMutation.isError}
        autoHideDuration={4000}
        onClose={() => { setTrainingSaveSuccess(false); trainingMutation.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={trainingMutation.isError ? 'error' : 'success'}
          variant="filled"
          onClose={() => { setTrainingSaveSuccess(false); trainingMutation.reset() }}
          sx={{ width: '100%' }}
        >
          {trainingMutation.isError ? 'Erro ao salvar treino.' : 'Treino salvo com sucesso!'}
        </Alert>
      </Snackbar>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: PASS (all tests in the file, including the new one).

- [ ] **Step 7: Run the full frontend suite**

Run: `cd frontend && npx tsc --noEmit && npx vitest run`
Expected: no type errors, all tests green. `PlayerResponse.trainingFocus`/`trainingIntensity`
are optional (`?:`), so no other test fixture that builds a `PlayerResponse` literal needs
updating.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts \
        frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(squad): add the Treino tab for per-player training assignment"
```

---

## Self-Review Notes

- **Spec coverage:** every section of `docs/superpowers/specs/2026-07-14-training-system-design.md`
  maps to a task — data model → Task 1, resolution logic + AI heuristic → Tasks 2-3, API →
  Task 4, frontend → Task 5. The spec's "known test-fixture consequence" was investigated during
  planning (Task 3, Step 5) and found not to apply to any existing test (verified by grep — no
  rollover-adjacent test asserts exact post-season attribute values), so no separate fixture-audit
  task was needed.
- **Type consistency:** `TrainingService.applyTraining(Player, Attribute, TrainingIntensity,
  Random)` (Task 2) is called identically in Task 3; `LineupService.setTraining(...)` (Task 4)
  returns `SquadResponse`, matching what Task 5's `setTraining` API client function and
  `trainingMutation`'s `mutationFn` expect. `PlayerResponse.trainingFocus`/`trainingIntensity`
  (Task 4, backend) match `TrainableAttribute | null` / `TrainingIntensity` (Task 5, frontend)
  field names exactly.
- **Placeholder scan:** no TBD/TODO markers. Every seeded-`Random` value used in a test (Task 2's
  injury-roll seed `4608`) was verified by actually running `java.util.Random` rather than
  guessed, so no step depends on an unconfirmed magic number.
