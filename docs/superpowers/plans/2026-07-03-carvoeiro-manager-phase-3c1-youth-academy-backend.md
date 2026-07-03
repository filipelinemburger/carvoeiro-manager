# Youth Academy — Backend Implementation Plan (Phase 3c-1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend for a managed-club youth academy: prospects (`Player` rows flagged `youth_prospect`) with a hidden `potential`, generated + developed at season rollover, excluded from match selection, and promote/release/list via a REST API.

**Architecture:** Prospects reuse the `Player` entity with two new columns (`youth_prospect`, `potential`). A `YouthDevelopmentService` grows them toward potential and maps potential→stars; `NewgenGenerator` gains a prospect draw; `SeasonRolloverService` gains a youth branch (intake/develop/depart) and stops auto-replacing managed retirees; squad/XI queries filter out prospects; a `YouthAcademyService` + `YouthController` expose list/promote/release.

**Tech Stack:** Java 25 / Spring Boot / JPA / Flyway / Testcontainers-Postgres.

## Global Constraints

- Gradle needs `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`, run from `backend/`.
- New columns: `youth_prospect BOOLEAN NOT NULL DEFAULT false`, `potential INT NOT NULL DEFAULT 0`. Migration file is **`V19__youth_academy.sql`** (V18 is current latest).
- Constants (tunable): `ACADEMY_TARGET = 10`, `MAX_INTAKE_PER_SEASON = 3`, prospect age `15–17`, `POTENTIAL_CAP = 92`, departure age `20`, promotion contract `3` years, development rate `0.35`.
- Potential is **hidden** — never returned in any DTO; only `potentialStars` (1–5) is exposed.
- Managed-club retirees are **no longer auto-replaced** by a newgen; AI clubs keep newgen-on-retirement.
- Youth RNG draws happen AFTER the existing per-player rollover draws (fixed order) so existing rollover results stay reproducible. Jackson is `tools.jackson`. ITs use Testcontainers Postgres.
- Known pre-existing flaky (NOT this feature): `RolloverContractsIT`, `SeedLoaderTest` — confirm no NEW failures.
- **Per-task commits on `main`** authorized. Every commit message ends with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Never `git push`, never `--amend`.

## File Structure

- `player/Player.java` — `youthProspect`+`potential` fields, accessors, `markAsProspect`, `promoteFromYouth` (T1).
- `db/migration/V19__youth_academy.sql` — two columns (T1).
- `player/NewgenGenerator.java` — `generateProspect(...)` + extracted attribute draw (T2).
- `player/YouthDevelopmentService.java` (new) — `potentialStars` + `develop` (T3).
- `player/PlayerRepository.java` + `match/LineupService.java` — youth-exclusion queries + gating (T4).
- `season/RolloverResponse.java` + `season/SeasonRolloverService.java` — youth intake/develop/depart, stop managed replacement (T5).
- `youth/YouthAcademyService.java`, `youth/YouthProspectResponse.java` (new) — list/promote/release (T6).
- `youth/YouthController.java` (new) — REST endpoints (T7).

---

### Task 1: `Player` youth fields + V19 migration

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java` (fields near line 66; accessors near line 134)
- Create: `backend/src/main/resources/db/migration/V19__youth_academy.sql`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerYouthTest.java`

**Interfaces:**
- Produces: `boolean Player.isYouthProspect()`, `int Player.getPotential()`, `void Player.markAsProspect(int potential)` (sets `youthProspect=true`, `potential=potential`), `void Player.promoteFromYouth()` (sets `youthProspect=false`).

- [ ] **Step 1: Write the failing test**

Create `PlayerYouthTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerYouthTest {
    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 16, "Brasil",
                50, 50, 50, 50, 10, 50, 55);
    }

    @Test
    void playersAreNotProspectsByDefault() {
        Player p = player();
        assertThat(p.isYouthProspect()).isFalse();
        assertThat(p.getPotential()).isZero();
    }

    @Test
    void markAsProspectSetsFlagAndPotential() {
        Player p = player();
        p.markAsProspect(82);
        assertThat(p.isYouthProspect()).isTrue();
        assertThat(p.getPotential()).isEqualTo(82);
    }

    @Test
    void promoteFromYouthClearsTheFlag() {
        Player p = player();
        p.markAsProspect(82);
        p.promoteFromYouth();
        assertThat(p.isYouthProspect()).isFalse();
        assertThat(p.getPotential()).isEqualTo(82); // potential is retained, just no longer a prospect
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerYouthTest"`
Expected: FAIL — compile errors (`isYouthProspect`/`getPotential`/`markAsProspect`/`promoteFromYouth` undefined).

- [ ] **Step 3: Add fields + accessors**

In `Player.java`, add fields after `fitness` (near line 84):

```java
    @Column(name = "youth_prospect", nullable = false)
    private boolean youthProspect;

    @Column(name = "potential", nullable = false)
    private int potential;
```

Add accessors near `getAge()` (around line 134):

```java
    public boolean isYouthProspect() { return youthProspect; }
    public int getPotential() { return potential; }

    public void markAsProspect(int potential) {
        this.youthProspect = true;
        this.potential = potential;
    }

    public void promoteFromYouth() {
        this.youthProspect = false;
    }
```

- [ ] **Step 4: Create the migration**

Create `backend/src/main/resources/db/migration/V19__youth_academy.sql`:

```sql
ALTER TABLE players ADD COLUMN youth_prospect BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE players ADD COLUMN potential INT NOT NULL DEFAULT 0;
```

- [ ] **Step 5: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerYouthTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/main/resources/db/migration/V19__youth_academy.sql \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerYouthTest.java
git commit -m "feat(youth): Player youth_prospect + potential fields + V19 migration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `NewgenGenerator.generateProspect`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/NewgenGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/NewgenGeneratorProspectTest.java`

**Interfaces:**
- Consumes: `Player.markAsProspect(int)` (T1), `OverallCalculator.compute(...)`.
- Produces: `Player NewgenGenerator.generateProspect(UUID saveGameId, UUID clubId, Position position, Random rng)` — a `Player` with `youthProspect=true`, age 15–17, `potential = min(92, overall + headroom)` (headroom ≥ 4, low-skewed), `contractYears=0`.

- [ ] **Step 1: Write the failing test**

Create `NewgenGeneratorProspectTest.java`:

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NewgenGeneratorProspectTest {

    @Autowired NewgenGenerator generator;

    @Test
    void generatesAYoungProspectWithAHiddenPotentialAboveCurrentAbility() {
        Player p = generator.generateProspect(UUID.randomUUID(), UUID.randomUUID(), Position.MID, new Random(1L));
        assertThat(p.isYouthProspect()).isTrue();
        assertThat(p.getAge()).isBetween(15, 17);
        assertThat(p.getPotential()).isGreaterThanOrEqualTo(p.getOverall());
        assertThat(p.getPotential()).isLessThanOrEqualTo(92);
        assertThat(p.getContractYears()).isZero();
    }

    @Test
    void isDeterministicForAGivenSeed() {
        UUID sg = UUID.randomUUID(), club = UUID.randomUUID();
        Player a = generator.generateProspect(sg, club, Position.ATT, new Random(42L));
        Player b = generator.generateProspect(sg, club, Position.ATT, new Random(42L));
        assertThat(a.getOverall()).isEqualTo(b.getOverall());
        assertThat(a.getPotential()).isEqualTo(b.getPotential());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.NewgenGeneratorProspectTest"`
Expected: FAIL — `generateProspect` undefined.

- [ ] **Step 3: Extract the attribute draw and add `generateProspect`**

In `NewgenGenerator.java`, extract the per-position band draw used by `generate` into a private helper and reuse it. Add this record + helper (place the helper near `band`):

```java
    private record Attrs(int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina) { }

    private Attrs drawAttributes(Position position, Random rng) {
        return switch (position) {
            case GK -> new Attrs(band(30, 20, rng), band(20, 15, rng), band(35, 20, rng),
                    band(25, 15, rng), band(45, 20, rng), band(40, 20, rng));
            case DEF -> new Attrs(band(40, 20, rng), band(25, 15, rng), band(35, 20, rng),
                    band(45, 20, rng), band(1, 8, rng), band(40, 20, rng));
            case MID -> new Attrs(band(40, 20, rng), band(35, 20, rng), band(45, 20, rng),
                    band(40, 20, rng), band(1, 8, rng), band(45, 20, rng));
            case ATT -> new Attrs(band(45, 20, rng), band(45, 20, rng), band(40, 20, rng),
                    band(25, 15, rng), band(1, 8, rng), band(35, 20, rng));
        };
    }
```

Refactor the existing `generate(...)` to draw its attributes via `drawAttributes` (keep its age `16 + rng.nextInt(4)` and the rest of its body unchanged — it must remain byte-identical in RNG consumption: draw name, then age, then the attributes in the same order). Then add:

```java
    /** Generates a youth-academy prospect: young (15-17), flagged, with a hidden potential ceiling. */
    public Player generateProspect(UUID saveGameId, UUID clubId, Position position, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 15 + rng.nextInt(3); // 15..17
        Attrs a = drawAttributes(position, rng);
        int overall = overallCalculator.compute(position, a.pace(), a.finishing(), a.passing(),
                a.tackling(), a.goalkeeping(), a.stamina());
        // Low-skewed headroom (nextDouble squared): most prospects modest, a few gems. Floor of 4.
        int headroom = 4 + (int) (rng.nextDouble() * rng.nextDouble() * 32);
        int potential = Math.min(92, overall + headroom);
        Player prospect = Player.create(saveGameId, clubId, name, position, age, "Brasil",
                a.pace(), a.finishing(), a.passing(), a.tackling(), a.goalkeeping(), a.stamina(), overall);
        prospect.setWage(financeCalculator.weeklyWage(overall, age));
        prospect.setContractYears(0);
        prospect.markAsProspect(potential);
        return prospect;
    }
```

> Note: when refactoring `generate` to use `drawAttributes`, verify the attribute draw order per position matches the original exactly, so `generate`'s output stays deterministic for existing rollover tests.

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.NewgenGeneratorProspectTest"`
Expected: PASS (2 tests). Also run `--tests "br.com.carvoeiromanager.season.*Rollover*"` to confirm the `generate` refactor didn't change existing rollover behavior (allowing for the known-flaky `RolloverContractsIT`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/NewgenGenerator.java \
        backend/src/test/java/br/com/carvoeiromanager/player/NewgenGeneratorProspectTest.java
git commit -m "feat(youth): NewgenGenerator.generateProspect (age 15-17 + hidden potential)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `YouthDevelopmentService` (stars + growth)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/YouthDevelopmentService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/YouthDevelopmentServiceTest.java`

**Interfaces:**
- Consumes: `OverallCalculator.compute(...)`, `Player.evolveAttributes(...)`, `Player.getPotential()`.
- Produces: `int YouthDevelopmentService.potentialStars(int potential)` (1–5); `void YouthDevelopmentService.develop(Player p)` (grows toward potential, never exceeding it; no-op at/over the ceiling; pure — consumes no RNG).

- [ ] **Step 1: Write the failing test**

Create `YouthDevelopmentServiceTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class YouthDevelopmentServiceTest {

    private final OverallCalculator overall = new OverallCalculator();
    private final YouthDevelopmentService dev = new YouthDevelopmentService(overall);

    private Player prospect(int potential) {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "P", Position.MID, 16, "Brasil",
                50, 50, 50, 50, 10, 50, ov);
        p.markAsProspect(potential);
        return p;
    }

    @Test
    void potentialStarsMapToBands() {
        assertThat(dev.potentialStars(90)).isEqualTo(5);
        assertThat(dev.potentialStars(85)).isEqualTo(5);
        assertThat(dev.potentialStars(80)).isEqualTo(4);
        assertThat(dev.potentialStars(72)).isEqualTo(3);
        assertThat(dev.potentialStars(65)).isEqualTo(2);
        assertThat(dev.potentialStars(50)).isEqualTo(1);
    }

    @Test
    void aProspectGrowsTowardPotentialButNeverPastIt() {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player q = prospect(ov + 25);
        int before = q.getOverall();
        dev.develop(q);
        assertThat(q.getOverall()).isGreaterThan(before);
        assertThat(q.getOverall()).isLessThanOrEqualTo(ov + 25);
    }

    @Test
    void aProspectAtItsCeilingDoesNotGrow() {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player q = prospect(ov); // potential == current overall
        int before = q.getOverall();
        dev.develop(q);
        assertThat(q.getOverall()).isEqualTo(before);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.YouthDevelopmentServiceTest"`
Expected: FAIL — `YouthDevelopmentService` does not exist.

- [ ] **Step 3: Implement the service**

Create `YouthDevelopmentService.java`:

```java
package br.com.carvoeiromanager.player;

import org.springframework.stereotype.Component;

/**
 * Season-to-season development of youth prospects: grows their attributes toward a hidden potential
 * ceiling (never past it) and maps potential to a 1-5 star display band. Pure and deterministic.
 */
@Component
public class YouthDevelopmentService {

    private static final double DEV_RATE = 0.35;   // fraction of the potential gap closed per season

    private final OverallCalculator overallCalculator;

    public YouthDevelopmentService(OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
    }

    /** 1-5 star band for a hidden potential overall. */
    public int potentialStars(int potential) {
        if (potential >= 85) return 5;
        if (potential >= 78) return 4;
        if (potential >= 70) return 3;
        if (potential >= 62) return 2;
        return 1;
    }

    /**
     * Grows the prospect's six attributes toward its potential. Bumps all attributes by the largest
     * uniform step (up to 35% of the remaining overall gap) that keeps recomputed overall at or below
     * potential; no-op once the prospect is within one point of, or at, its ceiling.
     */
    public void develop(Player p) {
        int gap = p.getPotential() - p.getOverall();
        if (gap <= 0) {
            return;
        }
        int step = Math.max(1, (int) Math.round(gap * DEV_RATE));
        for (int s = step; s >= 1; s--) {
            int pace = clamp(p.getPace() + s);
            int finishing = clamp(p.getFinishing() + s);
            int passing = clamp(p.getPassing() + s);
            int tackling = clamp(p.getTackling() + s);
            int goalkeeping = clamp(p.getGoalkeeping() + s);
            int stamina = clamp(p.getStamina() + s);
            int overall = overallCalculator.compute(p.getPosition(), pace, finishing, passing,
                    tackling, goalkeeping, stamina);
            if (overall <= p.getPotential()) {
                p.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
                return;
            }
        }
        // Even a +1 bump would exceed potential: the prospect is effectively at its ceiling.
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.YouthDevelopmentServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/YouthDevelopmentService.java \
        backend/src/test/java/br/com/carvoeiromanager/player/YouthDevelopmentServiceTest.java
git commit -m "feat(youth): YouthDevelopmentService — grow toward potential + star bands

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Exclude prospects from squad + XI selection

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/PlayerRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (`getSquad` line 67; `bestXi(UUID, LocalDate)` line 246)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/YouthExclusionIT.java`

**Interfaces:**
- Consumes: `Player.isYouthProspect()` (T1).
- Produces: `List<Player> PlayerRepository.findByClubIdAndYouthProspectFalse(UUID clubId)` and `findByClubIdAndYouthProspectTrue(UUID clubId)`. `getSquad`/`bestXi` return seniors only.

- [ ] **Step 1: Write the failing test**

Create `YouthExclusionIT.java` (uses the same career-setup pattern as `LineupServiceIT` — an autowired `lineupService`, `playerRepository`, and a helper to create a career; adapt to the file's conventions):

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class YouthExclusionIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    @Test
    void youthProspectsAreExcludedFromTheSquadAndBestXi() {
        User user = User.create("youth_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID clubId = save.managedClubId();

        int seniorCountBefore = lineupService.getSquad(save.id(), clubId, user.getId()).players().size();

        Player prospect = Player.create(save.id(), clubId, "Jóia da Base", Position.ATT, 16, "Brasil",
                60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        playerRepository.save(prospect);

        // The squad must not include the prospect...
        assertThat(lineupService.getSquad(save.id(), clubId, user.getId()).players())
                .noneMatch(pr -> pr.id().equals(prospect.getId()));
        assertThat(lineupService.getSquad(save.id(), clubId, user.getId()).players()).hasSize(seniorCountBefore);
        // ...and neither must bestXi.
        assertThat(lineupService.bestXi(clubId)).noneMatch(p -> p.getId().equals(prospect.getId()));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.match.YouthExclusionIT"`
Expected: FAIL — the prospect currently appears in `getSquad`/`bestXi` (repository method also undefined → may fail to compile first; add the repo method in Step 3).

- [ ] **Step 3: Add the queries + filter**

In `PlayerRepository.java`, add:

```java
    List<Player> findByClubIdAndYouthProspectFalse(UUID clubId);
    List<Player> findByClubIdAndYouthProspectTrue(UUID clubId);
```

In `LineupService.getSquad` (line 67) change `playerRepository.findByClubId(clubId)` to `playerRepository.findByClubIdAndYouthProspectFalse(clubId)`. In `bestXi(UUID clubId, LocalDate onDate)` (line 246) change `playerRepository.findByClubId(clubId)` to `playerRepository.findByClubIdAndYouthProspectFalse(clubId)`.

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.match.YouthExclusionIT"`
Expected: PASS. Also run `--tests "br.com.carvoeiromanager.match.LineupServiceIT"` to confirm senior-squad behavior is unchanged (all seeded players have `youth_prospect=false`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/PlayerRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/test/java/br/com/carvoeiromanager/match/YouthExclusionIT.java
git commit -m "feat(youth): exclude prospects from squad + bestXi selection

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Season-rollover youth integration

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java` (rollover loop lines 184–232; response line 252; add `YouthDevelopmentService` + `NewgenGenerator` deps — `newgenGenerator` is already injected)
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/YouthRolloverIT.java`

**Interfaces:**
- Consumes: `NewgenGenerator.generateProspect(...)` (T2), `YouthDevelopmentService.develop(Player)` (T3), `PlayerRepository.findByClubIdAndYouthProspectTrue(clubId)` (T4), `Player.isYouthProspect()`.
- Produces: `RolloverResponse` with two new lists `youthIntake` and `youthDepartures` (both `List<SquadChangeResponse>`); managed academy topped up each rollover; managed retirees no longer newgen-replaced.

- [ ] **Step 1: Write the failing test**

Create `YouthRolloverIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class YouthRolloverIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    @Test
    void rolloverStocksTheManagedAcademyWithProspects() {
        User user = User.create("yr_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID clubId = save.managedClubId();

        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId)).isEmpty();

        RolloverResponse resp = rolloverService.rollover(save.id(), user.getId());

        assertThat(resp.youthIntake()).isNotEmpty();
        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId)).isNotEmpty();
        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId))
                .allSatisfy(p -> { assertThat(p.isYouthProspect()).isTrue(); assertThat(p.getAge()).isBetween(16, 18); });
    }
}
```

(Prospects are generated at ages 15–17, then the rollover ages them +1 in the same pass → 16–18. If the implementation tops up the academy *after* the aging pass, they will be 15–17 instead — adjust the assertion to whichever the implementation does, and state it in the report.)

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.season.YouthRolloverIT"`
Expected: FAIL — `RolloverResponse.youthIntake()` undefined (compile), then (once added) the academy is empty after rollover.

- [ ] **Step 3: Add the RolloverResponse fields**

Replace the `RolloverResponse` record body:

```java
public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> newcomers,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures
) {}
```

- [ ] **Step 4: Wire the rollover**

In `SeasonRolloverService`: add a `private final YouthDevelopmentService youthDevelopmentService;` field, constructor param, and assignment (follow the existing DI pattern). Add `MAX_INTAKE_PER_SEASON = 3`, `ACADEMY_TARGET = 10`, `DEPARTURE_AGE = 20` constants near the other constants. Declare two lists next to the others (near line 180):

```java
        List<SquadChangeResponse> youthIntake = new ArrayList<>();
        List<SquadChangeResponse> youthDepartures = new ArrayList<>();
```

At the very top of the per-player loop body (right after `for (Player player : players) {`, before `player.setAge(...)`), add the youth branch:

```java
            if (player.isYouthProspect()) {
                player.setAge(player.getAge() + 1);
                boolean managedYouth = !player.isFreeAgent() && player.getClubId().equals(managedClubId);
                if (player.getAge() >= DEPARTURE_AGE) {
                    if (managedYouth) {
                        youthDepartures.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                    }
                    toDelete.add(player);
                } else {
                    youthDevelopmentService.develop(player);
                    toSave.add(player);
                }
                continue;
            }
```

In the retirement branch, guard the newgen replacement so managed retirees are NOT replaced. Change:

```java
                Player newgen = newgenGenerator.generate(
                        saveGameId, player.getClubId(), player.getPosition(), rng);
                newgen.setContractYears(2 + rng.nextInt(3));
                toSave.add(newgen);
                if (managed) {
                    newcomers.add(new SquadChangeResponse(
                            newgen.getName(), newgen.getPosition().name(), newgen.getAge()));
                }
                toDelete.add(player);
                continue;
```

to:

```java
                if (!managed) {
                    // AI clubs keep newgen-on-retirement; the managed club fills gaps from its academy.
                    Player newgen = newgenGenerator.generate(
                            saveGameId, player.getClubId(), player.getPosition(), rng);
                    newgen.setContractYears(2 + rng.nextInt(3));
                    toSave.add(newgen);
                }
                toDelete.add(player);
                continue;
```

After `playerRepository.saveAll(toSave);` and the free-agent replenish block, add the managed academy top-up (drawing from `rng` after all existing draws — deterministic):

```java
        // Top up the managed club's youth academy toward the target.
        int academySize = playerRepository.findByClubIdAndYouthProspectTrue(managedClubId).size();
        int intake = Math.min(MAX_INTAKE_PER_SEASON, Math.max(0, ACADEMY_TARGET - academySize));
        Position[] positions = Position.values();
        for (int i = 0; i < intake; i++) {
            Position pos = positions[rng.nextInt(positions.length)];
            Player prospect = newgenGenerator.generateProspect(saveGameId, managedClubId, pos, rng);
            playerRepository.save(prospect);
            youthIntake.add(new SquadChangeResponse(
                    prospect.getName(), prospect.getPosition().name(), prospect.getAge()));
        }
```

Update the response construction (line ~252) to pass the two new lists:

```java
        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers), List.copyOf(expiredContracts),
                List.copyOf(youthIntake), List.copyOf(youthDepartures));
```

- [ ] **Step 5: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.season.YouthRolloverIT"`
Expected: PASS. Then run `--tests "br.com.carvoeiromanager.season.*"` — existing rollover tests that assumed managed retirees are auto-replaced must be updated to the new behavior (managed retiree → no replacement); update those assertions and note them in the report. Treat `RolloverContractsIT`/`SeedLoaderTest` flakiness as pre-existing (re-run in isolation to confirm).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/YouthRolloverIT.java
git commit -m "feat(youth): rollover intake + development + departures; stop managed newgen-on-retirement

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

> If updating existing rollover tests, add them to the same commit.

---

### Task 6: `YouthAcademyService` + `YouthProspectResponse`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/youth/YouthProspectResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/youth/YouthAcademyService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/youth/YouthAcademyServiceIT.java`

**Interfaces:**
- Consumes: `PlayerRepository.findByClubIdAndYouthProspectTrue/False` (T4), `Player.promoteFromYouth()` (T1), `YouthDevelopmentService.potentialStars(int)` (T3), `SaveGameService.requireOwned(...)` (owner check — mirror `LineupService`), `FinanceCalculator.weeklyWage(overall, age)`.
- Produces: `YouthAcademyService.listProspects(saveGameId, clubId, userId) → List<YouthProspectResponse>`, `promote(saveGameId, prospectId, userId) → YouthProspectResponse`, `release(saveGameId, prospectId, userId)`.

- [ ] **Step 1: Write the failing test**

Create `YouthAcademyServiceIT.java`:

```java
package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class YouthAcademyServiceIT {

    @Autowired YouthAcademyService youthAcademyService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private UUID saveId, clubId, userId, prospectId;

    private void setup() {
        User user = User.create("yas_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();
        SaveGameResponse save = saveGameService.createCareer(userId);
        saveId = save.id();
        clubId = save.managedClubId();
        Player prospect = Player.create(saveId, clubId, "Promessa", Position.ATT, 16, "Brasil",
                60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        playerRepository.save(prospect);
        prospectId = prospect.getId();
    }

    @Test
    void listsProspectsWithStarsButNeverRawPotential() {
        setup();
        var list = youthAcademyService.listProspects(saveId, clubId, userId);
        assertThat(list).extracting(YouthProspectResponse::id).contains(prospectId);
        assertThat(list).allSatisfy(pr -> assertThat(pr.potentialStars()).isBetween(1, 5));
    }

    @Test
    void promotingMovesTheProspectIntoTheSeniorSquad() {
        setup();
        youthAcademyService.promote(saveId, prospectId, userId);
        Player promoted = playerRepository.findById(prospectId).orElseThrow();
        assertThat(promoted.isYouthProspect()).isFalse();
        assertThat(promoted.getContractYears()).isEqualTo(3);
        assertThat(lineupService.getSquad(saveId, clubId, userId).players())
                .anyMatch(pr -> pr.id().equals(prospectId));
        assertThat(youthAcademyService.listProspects(saveId, clubId, userId))
                .noneMatch(pr -> pr.id().equals(prospectId));
    }

    @Test
    void releasingRemovesTheProspect() {
        setup();
        youthAcademyService.release(saveId, prospectId, userId);
        assertThat(playerRepository.findById(prospectId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.youth.YouthAcademyServiceIT"`
Expected: FAIL — `YouthAcademyService`/`YouthProspectResponse` don't exist.

- [ ] **Step 3: Create the DTO**

Create `YouthProspectResponse.java`:

```java
package br.com.carvoeiromanager.youth;

import java.util.UUID;

public record YouthProspectResponse(
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
        int potentialStars
) {}
```

- [ ] **Step 4: Create the service**

Create `YouthAcademyService.java`:

```java
package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.YouthDevelopmentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Manages the managed club's youth academy: listing prospects, promoting them, and releasing them. */
@Service
public class YouthAcademyService {

    private static final int PROMOTION_CONTRACT_YEARS = 3;

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final YouthDevelopmentService youthDevelopmentService;
    private final FinanceCalculator financeCalculator;

    public YouthAcademyService(SaveGameService saveGameService, PlayerRepository playerRepository,
                               YouthDevelopmentService youthDevelopmentService,
                               FinanceCalculator financeCalculator) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.youthDevelopmentService = youthDevelopmentService;
        this.financeCalculator = financeCalculator;
    }

    @Transactional(readOnly = true)
    public List<YouthProspectResponse> listProspects(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return playerRepository.findByClubIdAndYouthProspectTrue(clubId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public YouthProspectResponse promote(UUID saveGameId, UUID prospectId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Player p = requireProspect(saveGameId, prospectId);
        p.promoteFromYouth();
        p.setContractYears(PROMOTION_CONTRACT_YEARS);
        p.setWage(financeCalculator.weeklyWage(p.getOverall(), p.getAge()));
        playerRepository.save(p);
        return toResponse(p);
    }

    @Transactional
    public void release(UUID saveGameId, UUID prospectId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Player p = requireProspect(saveGameId, prospectId);
        playerRepository.delete(p);
    }

    private Player requireProspect(UUID saveGameId, UUID prospectId) {
        Player p = playerRepository.findById(prospectId)
                .orElseThrow(() -> new ApiException("PROSPECT_NOT_FOUND",
                        "Youth prospect not found", HttpStatus.NOT_FOUND));
        if (!p.getSaveGameId().equals(saveGameId) || !p.isYouthProspect()) {
            throw new ApiException("PROSPECT_NOT_FOUND", "Youth prospect not found", HttpStatus.NOT_FOUND);
        }
        return p;
    }

    private YouthProspectResponse toResponse(Player p) {
        return new YouthProspectResponse(p.getId(), p.getName(), p.getPosition().name(), p.getAge(),
                p.getOverall(), p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina(), youthDevelopmentService.potentialStars(p.getPotential()));
    }
}
```

> Verify `SaveGameService.requireOwned(saveGameId, userId)` exists with that signature (used across services like `LineupService`); if the method name differs, use the same owner-check the other services use. Verify `FinanceCalculator.weeklyWage(int overall, int age)` — it is what `NewgenGenerator` calls.

- [ ] **Step 5: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.youth.YouthAcademyServiceIT"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/youth/
git add backend/src/test/java/br/com/carvoeiromanager/youth/YouthAcademyServiceIT.java
git commit -m "feat(youth): YouthAcademyService — list/promote/release prospects

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: `YouthController` REST endpoints

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/youth/YouthController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/youth/YouthControllerIT.java`

**Interfaces:**
- Consumes: `YouthAcademyService.listProspects/promote/release` (T6), `@CurrentUser` (mirror `LineupService`'s controllers, e.g. `SquadController`/`TacticsController`).
- Produces: `GET /youth-prospects?clubId={id}` → `List<YouthProspectResponse>`; `POST /youth-prospects/{id}/promote` → `YouthProspectResponse`; `POST /youth-prospects/{id}/release` → `204`.

- [ ] **Step 1: Write the failing test**

Create `YouthControllerIT.java` — a full HTTP-lifecycle test using `MockMvc` (mirror the setup of an existing controller IT such as `SquadController`/`LiveMatchControllerIT`: register a user, create a career, obtain the JWT, seed a prospect via `playerRepository`, then hit the endpoints). Assert:
- `GET /youth-prospects?clubId=` returns 200 with the prospect and a `potentialStars` field (and no `potential` field in the JSON).
- `POST /youth-prospects/{id}/promote` returns 200 and the prospect then appears in `GET /squad`.
- `POST /youth-prospects/{id}/release` returns 204.

(Match the concrete MockMvc/JWT helper style already used by the project's controller ITs; keep the query-param + path-variable shapes above.)

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.youth.YouthControllerIT"`
Expected: FAIL — `YouthController` does not exist (404s / no bean).

- [ ] **Step 3: Create the controller**

Create `YouthController.java`:

```java
package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class YouthController {

    private final YouthAcademyService youthAcademyService;

    public YouthController(YouthAcademyService youthAcademyService) {
        this.youthAcademyService = youthAcademyService;
    }

    @GetMapping("/youth-prospects")
    public List<YouthProspectResponse> list(@RequestParam("clubId") UUID clubId,
                                            @RequestParam("saveGameId") UUID saveGameId,
                                            @CurrentUser UUID userId) {
        return youthAcademyService.listProspects(saveGameId, clubId, userId);
    }

    @PostMapping("/youth-prospects/{id}/promote")
    public YouthProspectResponse promote(@PathVariable("id") UUID prospectId,
                                         @RequestParam("saveGameId") UUID saveGameId,
                                         @CurrentUser UUID userId) {
        return youthAcademyService.promote(saveGameId, prospectId, userId);
    }

    @PostMapping("/youth-prospects/{id}/release")
    public ResponseEntity<Void> release(@PathVariable("id") UUID prospectId,
                                        @RequestParam("saveGameId") UUID saveGameId,
                                        @CurrentUser UUID userId) {
        youthAcademyService.release(saveGameId, prospectId, userId);
        return ResponseEntity.noContent().build();
    }
}
```

> Confirm the `@CurrentUser` resolver type (`UUID` vs a user object) matches how existing controllers (e.g. `SquadController`) inject the current user, and align the endpoint's auth accordingly. `saveGameId` is a required query param on all three (the service needs it for the owner check).

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.youth.YouthControllerIT"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/youth/YouthController.java \
        backend/src/test/java/br/com/carvoeiromanager/youth/YouthControllerIT.java
git commit -m "feat(youth): YouthController — list/promote/release endpoints

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final verification (after all tasks)

- [ ] Backend: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.player.*" --tests "br.com.carvoeiromanager.match.*" --tests "br.com.carvoeiromanager.season.*" --tests "br.com.carvoeiromanager.youth.*"` — green apart from the known-flaky `RolloverContractsIT`/`SeedLoaderTest` (re-run those in isolation to confirm they are not newly broken by the rollover change).
- [ ] Manual smoke (optional): create a career, roll a season, `GET /youth-prospects` shows a stocked academy; promote one and confirm it appears in `GET /squad`.

The frontend (Base screen, star rating, promote/release UI, nav entry, rollover-summary fields) is a **separate plan (Phase 3c-2)**, written once this backend API is in place.
