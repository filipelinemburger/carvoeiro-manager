# Competition Format Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give competitions a data-driven phase model — round-robin phases plus seeded two-legged knockouts resolved by extra time and penalties — and prove it by converting the Campeonato Catarinense to a 12-club two-phase format.

**Architecture:** A new `competition_phase` table describes each competition's ordered phases. `SeasonCalendarGenerator` materializes round-robin fixtures up front but only *reserves* dated events for knockout phases, because `fixtures.home_club_id` is `NOT NULL` and quarterfinal participants are unknown at generation. A `PhaseResolver` fills those reservations in when the preceding phase completes, seeding from that phase's standings. Both match engines gain a 30-minute extra-time period and a penalty shootout, gated so they consume no RNG unless a knockout tie is actually level.

**Tech Stack:** Java 21 / Spring Boot, Flyway, JPA, Postgres, Testcontainers, JUnit 5; React + TypeScript + MUI + vitest.

**Spec:** `docs/superpowers/specs/2026-08-05-competition-format-engine-design.md`

## Global Constraints

- **`MatchMath.MINUTES` must stay `90`.** It is a *rate divisor* (`xg / MINUTES`, `frac = minute / MINUTES`), not merely a loop bound. Changing it rescales every per-minute probability in the game and invalidates the calibration in `MatchCalibrationTest`. Extra time adds a separate period bound; `MINUTES` is never touched.
- **Determinism contract:** extra-time and shootout code must consume **zero** RNG draws unless it actually fires. A league match at a given seed must be byte-identical before and after this work. Pinned by a regression test in Task 8.
- **No away-goals rule** anywhere.
- Flyway migrations are **forward-only**. This slice adds `V47` and never edits an applied migration.
- Migration is **fully additive**: every new column is nullable, nothing is dropped or renamed.
- **Existing saves are never backfilled.** A competition with no phase rows takes the existing single-phase league path unchanged.
- Money is `BigDecimal`. Dates are `LocalDate`. Ids are `UUID`. Constructor injection only. DTOs are records.
- Backend build/test: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew` from `backend/`.
- Frontend type-check is `npx tsc -b` (`tsc --noEmit` is a no-op in this repo). Tests: `npm test`.
- Gradle may hit contention if another session is building; `EOFException`/`NoSuchFileException` means contention, not a bug — retry.

## File Structure

**New backend files**
- `competition/CompetitionPhase.java` — the phase entity
- `competition/PhaseKind.java` — `ROUND_ROBIN | KNOCKOUT`
- `competition/CompetitionPhaseRepository.java`
- `competition/KnockoutTie.java` — a tie projected from two fixtures (record)
- `competition/TieResolver.java` — aggregate → winner, pure
- `competition/PhaseResolver.java` — materializes the next phase
- `competition/FinalClassification.java` — phase results → single ordering, pure
- `seed/SeedPhase.java` — seed record for a phase
- `match/Shootout.java` — penalty shootout, pure + seeded
- `db/migration/V47__competition_phases.sql`

**Modified backend files**
- `competition/Fixture.java` — `phaseId`, `leg`, `tieId`, `tieOrder`, `penaltyHome`, `penaltyAway`
- `competition/FixtureRepository.java` — `findBySaveGameIdAndPhaseId`, `findByTieId`
- `match/LiveMatchConfig.java` — `knockoutDecider` flag
- `match/LiveCommentary.java` — extra-time and shootout lines
- `season/NewsService.java` — phase-transition news
- `competition/FixtureScheduler.java` — `singleRoundRobin`, `knockoutPairings`
- `competition/StandingsService.java` — per-phase standings
- `competition/CompetitionController.java` — phases + ties endpoints
- `season/SeasonEvent.java` — `phaseId`
- `season/SeasonCalendarGenerator.java` — phase-aware generation
- `season/SeasonAdvanceService.java` — invoke `PhaseResolver`; phase-aware response
- `season/SeasonRolloverService.java` — carry phases forward
- `career/SaveGameService.java` — create phase rows from seed
- `seed/SeedCompetition.java` — `phases`
- `match/MatchMath.java` — period bounds (NOT `MINUTES`)
- `match/FixtureSimulator.java` — extra time + shootout
- `match/LiveMatchEngine.java`, `match/LiveMatchService.java` — extra time + shootout
- `match/EventType.java` — three new values
- `finance/FinanceService.java` — prize from final classification
- `resources/seed/clubs.json` — 12-club Catarinense, phases, 3 new clubs

**New frontend files**
- `features/competition/KnockoutBracketList.tsx` — ties grouped by phase
- `features/competition/PhaseSelector.tsx`

**Modified frontend files**
- `features/competition/TablePage.tsx` — phase switching
- `api/types.ts`, `api/client.ts` — phase + tie types

---

### Task 1: Phase entity, migration, and fixture columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V47__competition_phases.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/PhaseKind.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhase.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhaseRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonEvent.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionPhaseIT.java`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `PhaseKind.{ROUND_ROBIN, KNOCKOUT}`; `CompetitionPhase.create(UUID saveGameId, UUID competitionId, int ordinal, String name, PhaseKind kind, int legs, int rounds, Integer qualifiers)` with getters `getId/getSaveGameId/getCompetitionId/getOrdinal/getName/getKind/getLegs/getRounds/getQualifiers`; `CompetitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(UUID)`, `findBySaveGameId(UUID)`, `deleteBySaveGameId(UUID)`; `Fixture` getters/setters `getPhaseId/setPhaseId`, `getLeg/setLeg`, `getTieId/setTieId`, `getTieOrder/setTieOrder`, `getPenaltyHome/setPenaltyHome`, `getPenaltyAway/setPenaltyAway`; `SeasonEvent.getPhaseId/setPhaseId`, `SeasonEvent.createKnockout(UUID saveGameId, LocalDate eventDate, UUID competitionId, UUID phaseId, int round, int sequence)`; `FixtureRepository.findBySaveGameIdAndPhaseId(UUID, UUID)` and `findByTieId(UUID)`

**Also add to `FixtureRepository`** (needed from Task 6 onward, added here so later tasks can assume it):

```java
    List<Fixture> findBySaveGameIdAndPhaseId(UUID saveGameId, UUID phaseId);

    List<Fixture> findByTieId(UUID tieId);
```

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V47__competition_phases.sql`:

```sql
-- Ordered phases within a competition. A competition with no rows here behaves
-- exactly as before: a single double-round-robin league.
CREATE TABLE competition_phase (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    ordinal        INT  NOT NULL,
    name           VARCHAR(40) NOT NULL,
    kind           VARCHAR(16) NOT NULL,
    legs           INT  NOT NULL,
    rounds         INT  NOT NULL,
    qualifiers     INT
);
CREATE INDEX idx_competition_phase_comp ON competition_phase(competition_id, ordinal);
CREATE INDEX idx_competition_phase_save ON competition_phase(save_game_id);

-- Knockout support on fixtures. All nullable: existing rows stay valid.
ALTER TABLE fixtures ADD COLUMN phase_id     UUID;
ALTER TABLE fixtures ADD COLUMN leg          INT;
ALTER TABLE fixtures ADD COLUMN tie_id       UUID;
-- 0-based pairing index within the phase. tie_id is an opaque UUID and sorts
-- arbitrarily, so this is what preserves seeded bracket order across rounds.
ALTER TABLE fixtures ADD COLUMN tie_order    INT;
ALTER TABLE fixtures ADD COLUMN penalty_home INT;
ALTER TABLE fixtures ADD COLUMN penalty_away INT;
CREATE INDEX idx_fixtures_phase ON fixtures(phase_id);
CREATE INDEX idx_fixtures_tie   ON fixtures(tie_id);

-- A reserved knockout MATCH event knows its phase before it knows its fixtures.
ALTER TABLE season_event ADD COLUMN phase_id UUID;
```

- [ ] **Step 2: Write the failing integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionPhaseIT.java`. Copy the class-level annotations from an existing IT — `backend/src/test/java/br/com/carvoeiromanager/club/StadiumNameIT.java` is the template (there is no shared base class; each IT carries its own):

```java
package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.PostgresTestContainer;
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
class CompetitionPhaseIT {

    @Autowired
    private CompetitionPhaseRepository repository;

    @Test
    void phasesAreReadBackInOrdinalOrder() {
        // Given: three phases of one competition, saved out of order.
        // NOTE: competition_phase.save_game_id has a FK to save_games, so a random UUID
        // would violate it. Create a real save first, the way other ITs in this package do.
        UUID saveGameId = createSaveGame().getId();
        UUID competitionId = UUID.randomUUID();   // no FK on this column
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 2, "Final", PhaseKind.KNOCKOUT, 2, 2, null));
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 0, "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, 11, 8));
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 1, "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null));

        // When
        List<CompetitionPhase> phases = repository.findByCompetitionIdOrderByOrdinalAsc(competitionId);

        // Then
        assertThat(phases).extracting(CompetitionPhase::getName)
                .containsExactly("Primeira Fase", "Semifinal", "Final");
        assertThat(phases.get(0).getKind()).isEqualTo(PhaseKind.ROUND_ROBIN);
        assertThat(phases.get(0).getQualifiers()).isEqualTo(8);
        assertThat(phases.get(2).getQualifiers()).isNull();
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*CompetitionPhaseIT*'
```

Expected: compilation failure — `PhaseKind`, `CompetitionPhase`, `CompetitionPhaseRepository` do not exist.

- [ ] **Step 4: Create `PhaseKind`**

`backend/src/main/java/br/com/carvoeiromanager/competition/PhaseKind.java`:

```java
package br.com.carvoeiromanager.competition;

/** How a competition phase is played. */
public enum PhaseKind {
    /** Every member plays every other; produces a table. */
    ROUND_ROBIN,
    /** Seeded ties; produces winners, no table. */
    KNOCKOUT
}
```

- [ ] **Step 5: Create `CompetitionPhase`**

`backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhase.java`. Follow the `Competition` entity's shape — assigned UUID, private constructor, static factory:

```java
package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One ordered phase of a competition. A competition with no phases is a plain
 * double-round-robin league (the pre-V47 behaviour), so this table is purely additive.
 */
@Entity
@Table(name = "competition_phase")
public class CompetitionPhase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "competition_id", nullable = false)
    private UUID competitionId;

    /** 0-based order within the competition. */
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PhaseKind kind;

    /** 1 or 2. A two-legged knockout round occupies two match-units. */
    @Column(name = "legs", nullable = false)
    private int legs;

    /** How many match-units this phase occupies in the calendar. */
    @Column(name = "rounds", nullable = false)
    private int rounds;

    /** ROUND_ROBIN: how many clubs advance. Null for KNOCKOUT. */
    @Column(name = "qualifiers")
    private Integer qualifiers;

    protected CompetitionPhase() {
        // for JPA
    }

    private CompetitionPhase(UUID id, UUID saveGameId, UUID competitionId, int ordinal, String name,
                             PhaseKind kind, int legs, int rounds, Integer qualifiers) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.competitionId = competitionId;
        this.ordinal = ordinal;
        this.name = name;
        this.kind = kind;
        this.legs = legs;
        this.rounds = rounds;
        this.qualifiers = qualifiers;
    }

    public static CompetitionPhase create(UUID saveGameId, UUID competitionId, int ordinal, String name,
                                          PhaseKind kind, int legs, int rounds, Integer qualifiers) {
        return new CompetitionPhase(UUID.randomUUID(), saveGameId, competitionId, ordinal, name,
                kind, legs, rounds, qualifiers);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getOrdinal() { return ordinal; }
    public String getName() { return name; }
    public PhaseKind getKind() { return kind; }
    public int getLegs() { return legs; }
    public int getRounds() { return rounds; }
    public Integer getQualifiers() { return qualifiers; }
}
```

- [ ] **Step 6: Create the repository**

`backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhaseRepository.java`:

```java
package br.com.carvoeiromanager.competition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CompetitionPhaseRepository extends JpaRepository<CompetitionPhase, UUID> {

    List<CompetitionPhase> findByCompetitionIdOrderByOrdinalAsc(UUID competitionId);

    List<CompetitionPhase> findBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 7: Add the knockout columns to `Fixture`**

In `backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java`, after the `attendance` field (currently line 51-52), add:

```java
    @Column(name = "phase_id")
    private UUID phaseId;

    /** 1 or 2 within a knockout tie; null for league fixtures. */
    @Column(name = "leg")
    private Integer leg;

    /** Shared by the two legs of one knockout tie; null for league fixtures. */
    @Column(name = "tie_id")
    private UUID tieId;

    /** 0-based pairing index within the phase; preserves seeded bracket order. */
    @Column(name = "tie_order")
    private Integer tieOrder;

    @Column(name = "penalty_home")
    private Integer penaltyHome;

    @Column(name = "penalty_away")
    private Integer penaltyAway;
```

And with the other accessors:

```java
    public UUID getPhaseId() { return phaseId; }
    public void setPhaseId(UUID phaseId) { this.phaseId = phaseId; }

    public Integer getLeg() { return leg; }
    public void setLeg(Integer leg) { this.leg = leg; }

    public UUID getTieId() { return tieId; }
    public void setTieId(UUID tieId) { this.tieId = tieId; }

    public Integer getTieOrder() { return tieOrder; }
    public void setTieOrder(Integer tieOrder) { this.tieOrder = tieOrder; }

    public Integer getPenaltyHome() { return penaltyHome; }
    public void setPenaltyHome(Integer penaltyHome) { this.penaltyHome = penaltyHome; }

    public Integer getPenaltyAway() { return penaltyAway; }
    public void setPenaltyAway(Integer penaltyAway) { this.penaltyAway = penaltyAway; }
```

Leave `Fixture.create(...)` unchanged — knockout fields are set after construction by `PhaseResolver`, so no existing call site changes.

- [ ] **Step 8: Add `phaseId` to `SeasonEvent`**

In `backend/src/main/java/br/com/carvoeiromanager/season/SeasonEvent.java` add the column, accessors, and a factory for reserved knockout events:

```java
    @Column(name = "phase_id")
    private UUID phaseId;

    public UUID getPhaseId() { return phaseId; }
    public void setPhaseId(UUID phaseId) { this.phaseId = phaseId; }

    /**
     * A MATCH event for a knockout round: dated and ordered at generation, but with no
     * fixtures until {@code PhaseResolver} materializes them.
     */
    public static SeasonEvent createKnockout(UUID saveGameId, LocalDate eventDate, UUID competitionId,
                                             UUID phaseId, int round, int sequence) {
        SeasonEvent event = createMatch(saveGameId, eventDate, competitionId, round, sequence);
        event.setPhaseId(phaseId);
        return event;
    }
```

- [ ] **Step 9: Run the test**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*CompetitionPhaseIT*'
```

Expected: PASS.

- [ ] **Step 10: Run the full backend suite to confirm nothing regressed**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```

Expected: PASS. The migration is additive and no existing code reads the new columns yet.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V47__competition_phases.sql \
        backend/src/main/java/br/com/carvoeiromanager/competition/PhaseKind.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhase.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionPhaseRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonEvent.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionPhaseIT.java
git commit -m "feat(competition): phase model, V47 migration, knockout fixture columns"
```

---

### Task 2: Scheduler — single round-robin and knockout pairings

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureScheduler.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/FixtureSchedulerTest.java`

**Interfaces:**
- Consumes: `ScheduledFixture(int round, int homeIndex, int awayIndex)` (existing)
- Produces: `FixtureScheduler.singleRoundRobin(int teamCount) -> List<ScheduledFixture>`; `FixtureScheduler.knockoutPairings(int qualifierCount) -> List<int[]>` where each entry is `{higherSeedIndex, lowerSeedIndex}` 0-based on the seeded ordering

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/java/br/com/carvoeiromanager/competition/FixtureSchedulerTest.java` (create it with the standard imports if it does not exist):

```java
    @Test
    void singleRoundRobinPlaysEveryPairExactlyOnce() {
        // Given a 12-club field
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        // Then: 12*11/2 = 66 matches over 11 rounds, each pair once in either orientation
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).extracting(ScheduledFixture::round)
                .containsOnly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        Set<Set<Integer>> pairs = fixtures.stream()
                .map(f -> Set.of(f.homeIndex(), f.awayIndex()))
                .collect(Collectors.toSet());
        assertThat(pairs).hasSize(66);
    }

    @Test
    void singleRoundRobinGivesEveryClubOneMatchPerRound() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        for (int round = 0; round <= 10; round++) {
            final int r = round;
            List<Integer> clubs = fixtures.stream().filter(f -> f.round() == r)
                    .flatMap(f -> Stream.of(f.homeIndex(), f.awayIndex())).sorted().toList();
            assertThat(clubs).as("round %d", r).hasSize(12).doesNotHaveDuplicates();
        }
    }

    @Test
    void singleRoundRobinBalancesHomeAndAwayWithinOne() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().singleRoundRobin(12);

        for (int club = 0; club < 12; club++) {
            final int c = club;
            long home = fixtures.stream().filter(f -> f.homeIndex() == c).count();
            long away = fixtures.stream().filter(f -> f.awayIndex() == c).count();
            assertThat(Math.abs(home - away)).as("club %d home=%d away=%d", c, home, away)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void knockoutPairingsSeedBestAgainstWorst() {
        List<int[]> ties = new FixtureScheduler().knockoutPairings(8);

        assertThat(ties).hasSize(4);
        assertThat(ties.get(0)).containsExactly(0, 7); // 1st v 8th
        assertThat(ties.get(1)).containsExactly(1, 6); // 2nd v 7th
        assertThat(ties.get(2)).containsExactly(2, 5); // 3rd v 6th
        assertThat(ties.get(3)).containsExactly(3, 4); // 4th v 5th
    }

    @Test
    void knockoutPairingsHandlesFourAndTwo() {
        assertThat(new FixtureScheduler().knockoutPairings(4))
                .satisfies(ties -> {
                    assertThat(ties).hasSize(2);
                    assertThat(ties.get(0)).containsExactly(0, 3);
                    assertThat(ties.get(1)).containsExactly(1, 2);
                });
        assertThat(new FixtureScheduler().knockoutPairings(2))
                .satisfies(ties -> {
                    assertThat(ties).hasSize(1);
                    assertThat(ties.get(0)).containsExactly(0, 1);
                });
    }

    @Test
    void knockoutPairingsRejectsNonPowerOfTwo() {
        assertThatThrownBy(() -> new FixtureScheduler().knockoutPairings(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

Imports needed at the top of the file: `java.util.List`, `java.util.Set`, `java.util.stream.Collectors`, `java.util.stream.Stream`, `static org.assertj.core.api.Assertions.assertThat`, `static org.assertj.core.api.Assertions.assertThatThrownBy`.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FixtureSchedulerTest*'
```

Expected: compilation failure — `singleRoundRobin` and `knockoutPairings` do not exist.

- [ ] **Step 3: Implement both methods**

Add to `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureScheduler.java`, keeping `doubleRoundRobin` untouched:

```java
    /**
     * One leg only: every pair meets exactly once over {@code teamCount - 1} rounds. Uses the
     * same circle method as {@link #doubleRoundRobin(int)}, with the round-parity home/away
     * alternation that keeps each club's home and away counts within one of each other.
     */
    public List<ScheduledFixture> singleRoundRobin(int teamCount) {
        if (teamCount < 2 || teamCount % 2 != 0) {
            throw new IllegalArgumentException("teamCount must be even and >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = teamCount;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        for (int round = 0; round < n - 1; round++) {
            for (int i = 0; i < n / 2; i++) {
                int home = arr[i];
                int away = arr[n - 1 - i];
                // Alternate orientation by round AND by slot, so no club is always at home.
                if ((round + i) % 2 == 1) { int tmp = home; home = away; away = tmp; }
                result.add(new ScheduledFixture(round, home, away));
            }
            rotate(arr);
        }
        return result;
    }

    /**
     * Seeded knockout pairings: best against worst, second against second-worst, and so on.
     * Indices are positions in the seeded ordering (0 = top seed). Each entry is
     * {@code {higherSeedIndex, lowerSeedIndex}}; the higher seed hosts the second leg.
     */
    public List<int[]> knockoutPairings(int qualifierCount) {
        if (qualifierCount < 2 || Integer.bitCount(qualifierCount) != 1) {
            throw new IllegalArgumentException("qualifierCount must be a power of two and >= 2");
        }
        List<int[]> ties = new ArrayList<>();
        for (int i = 0; i < qualifierCount / 2; i++) {
            ties.add(new int[] { i, qualifierCount - 1 - i });
        }
        return ties;
    }
```

- [ ] **Step 4: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FixtureSchedulerTest*'
```

Expected: PASS. If `singleRoundRobinBalancesHomeAndAwayWithinOne` fails, the `(round + i) % 2` alternation is the knob — do not "fix" it by relaxing the assertion.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/FixtureScheduler.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/FixtureSchedulerTest.java
git commit -m "feat(competition): single round-robin and seeded knockout pairings"
```

---

### Task 3: Seed model — phases in `clubs.json`, 12-club Catarinense

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedPhase.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedCompetition.java`
- Modify: `backend/src/main/resources/seed/clubs.json`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`

**Interfaces:**
- Consumes: `CompetitionPhase.create(...)`, `PhaseKind` (Task 1)
- Produces: `SeedPhase(String name, String kind, int legs, int rounds, Integer qualifiers)`; `SeedCompetition.phases()` returning `List<SeedPhase>` (may be null/empty for a plain league)

**Data research note:** the three new clubs need real identity data — full name, `shortName`, `commonName`, `nickname`, `stadium`, `city`, `state`, `climateZone`, `stadiumCapacity`, `primaryColor`, `secondaryColor`, `formation`, `mentality`, `pressing`, `marking` — plus a real roster of ~18–22 players with `name`, `position`, `age`, `nationality`, `positions`, `roles` and the six anchor values (`pace, finishing, passing, tackling, goalkeeping, stamina`). Follow the **Catarinense-only convention**: no hand-authored 22-value `attributes` block; `AttributeDeriver` fills the rest. Calibrate anchors *below* the Série B clubs already in the pool — these are state-level sides.

- [ ] **Step 1: Create `SeedPhase`**

`backend/src/main/java/br/com/carvoeiromanager/seed/SeedPhase.java`:

```java
package br.com.carvoeiromanager.seed;

/**
 * One phase of a seeded competition. {@code kind} is "ROUND_ROBIN" or "KNOCKOUT";
 * {@code rounds} is how many match-units it occupies; {@code qualifiers} is how many
 * clubs advance out of a round-robin phase (null for a knockout phase).
 */
public record SeedPhase(
        String name,
        String kind,
        int legs,
        int rounds,
        Integer qualifiers
) {}
```

- [ ] **Step 2: Add `phases` to `SeedCompetition`**

`backend/src/main/java/br/com/carvoeiromanager/seed/SeedCompetition.java`:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

/**
 * A single competition entry within a SeedCareer, carrying its name, type (e.g. "LEAGUE"),
 * season year, the shortNames of the clubs that participate in it, and its ordered phases.
 * A null or empty {@code phases} means a plain double round-robin league (pre-V47 behaviour).
 */
public record SeedCompetition(
        String name,
        String type,
        int seasonYear,
        List<String> memberShortNames,
        List<SeedPhase> phases
) {}
```

Jackson maps a missing `phases` key to `null`, so the Série B entry needs no change.

- [ ] **Step 3: Write the failing seed test**

Add to `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java` (create with the loader's usual wiring if absent):

```java
    @Test
    void catarinenseHasTwelveMembersAndFourPhases() {
        SeedCareer career = seedLoader.load();

        SeedCompetition cat = career.competitions().stream()
                .filter(c -> c.name().equals("Campeonato Catarinense")).findFirst().orElseThrow();

        assertThat(cat.memberShortNames()).hasSize(12)
                .contains("JOI", "CAR", "SCA")
                .doesNotContain("HER");

        assertThat(cat.phases()).extracting(SeedPhase::name)
                .containsExactly("Primeira Fase", "Quartas de final", "Semifinal", "Final");
        assertThat(cat.phases().get(0).kind()).isEqualTo("ROUND_ROBIN");
        assertThat(cat.phases().get(0).rounds()).isEqualTo(11);
        assertThat(cat.phases().get(0).qualifiers()).isEqualTo(8);
        assertThat(cat.phases().get(1).kind()).isEqualTo("KNOCKOUT");
        assertThat(cat.phases().get(1).legs()).isEqualTo(2);
        assertThat(cat.phases().get(1).rounds()).isEqualTo(2);
    }

    @Test
    void hercilioLuzStaysInThePoolAsAnOrphan() {
        SeedCareer career = seedLoader.load();

        assertThat(career.clubPool()).extracting(SeedClub::shortName).contains("HER");

        boolean inAnyCompetition = career.competitions().stream()
                .anyMatch(c -> c.memberShortNames().contains("HER"));
        assertThat(inAnyCompetition).isFalse();
    }

    @Test
    void serieBRemainsAPlainLeagueWithNoPhases() {
        SeedCareer career = seedLoader.load();

        SeedCompetition serieB = career.competitions().stream()
                .filter(c -> c.name().equals("Série B")).findFirst().orElseThrow();

        assertThat(serieB.memberShortNames()).hasSize(20);
        assertThat(serieB.phases()).isNullOrEmpty();
    }
```

- [ ] **Step 4: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeedLoaderTest*'
```

Expected: FAIL — the Catarinense has 10 members and no `phases`.

- [ ] **Step 5: Add the three new clubs to `clubPool`**

Add three entries to the `clubPool` array in `backend/src/main/resources/seed/clubs.json`, each following the exact shape of the existing Catarinense-only clubs (e.g. `BAR`, `CON`). Short names: **`JOI`** (Joinville Esporte Clube), **`CAR`** (Clube Atlético Carlos Renaux, Brusque), **`SCA`** (Santa Catarina, Joinville). All have `"state": "SC"` and `"climateZone": "SUL"`. Use each club's real stadium and a real capacity — the same fields the weather/attendance slice relies on, so a wrong capacity shows up as wrong gate income.

Do **not** add an `attributes` block to any of their players.

- [ ] **Step 6: Update the Catarinense competition entry**

Replace the Catarinense entry's `memberShortNames` with the real 12-club 2026 field and add `phases`:

```json
{
  "name": "Campeonato Catarinense",
  "type": "LEAGUE",
  "seasonYear": 2026,
  "memberShortNames": ["AVA", "CAM", "CON", "MAR", "BRU", "JOI",
                       "CHA", "CAR", "BAR", "FIG", "CRI", "SCA"],
  "phases": [
    { "name": "Primeira Fase",     "kind": "ROUND_ROBIN", "legs": 1, "rounds": 11, "qualifiers": 8 },
    { "name": "Quartas de final",  "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null },
    { "name": "Semifinal",         "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null },
    { "name": "Final",             "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null }
  ]
}
```

`HER` leaves this list but stays in `clubPool`. Leave the Série B entry exactly as it is.

- [ ] **Step 7: Create phase rows at career creation**

In `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`, where competitions are created from `SeedCompetition`, set `totalRounds` from the phases when present and persist a `CompetitionPhase` per seed phase. Inject `CompetitionPhaseRepository` via the constructor.

```java
        // Phase-aware competitions: totalRounds is the sum of the phases' rounds.
        // A competition with no seeded phases keeps its existing double-round-robin round count.
        int totalRounds = (seedCompetition.phases() == null || seedCompetition.phases().isEmpty())
                ? (memberIds.size() - 1) * 2
                : seedCompetition.phases().stream().mapToInt(SeedPhase::rounds).sum();

        Competition competition = Competition.create(saveGameId, seedCompetition.name(),
                seedCompetition.type(), seedCompetition.seasonYear(), totalRounds);
        competitionRepository.save(competition);

        if (seedCompetition.phases() != null) {
            int ordinal = 0;
            for (SeedPhase phase : seedCompetition.phases()) {
                competitionPhaseRepository.save(CompetitionPhase.create(
                        saveGameId, competition.getId(), ordinal++, phase.name(),
                        PhaseKind.valueOf(phase.kind()), phase.legs(), phase.rounds(),
                        phase.qualifiers()));
            }
        }
```

Match the surrounding code's existing variable names for `memberIds` and the competition-creation block rather than introducing new ones.

- [ ] **Step 8: Run the seed tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeedLoaderTest*'
```

Expected: PASS.

- [ ] **Step 9: Run the full suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```

Expected: some career-creation tests that assert 10 Catarinense clubs or 28 pool clubs now fail on the new counts (12 and 31). Update those assertions to the new numbers — they are counting, not testing behaviour. Do **not** weaken any assertion that checks fixture generation or standings.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/seed/ \
        backend/src/main/resources/seed/clubs.json \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
        backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java
git commit -m "feat(seed): real 12-club Catarinense field with phases; Joinville, Carlos Renaux, Santa Catarina"
```

---

### Task 4: Phase-aware calendar generation

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorTest.java`

**Interfaces:**
- Consumes: `CompetitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc`, `PhaseKind`, `FixtureScheduler.singleRoundRobin`, `SeasonEvent.createKnockout` (Tasks 1–2)
- Produces: unchanged public signature `generate(UUID saveGameId, int seasonYear, List<Competition> competitions)`

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorTest.java` (an IT if the existing tests there are ITs — follow whatever the file already does):

```java
    @Test
    void aPhasedCompetitionMaterializesTheRoundRobinAndReservesTheKnockout() {
        // Given: a 12-club competition with the four Catarinense phases
        // (built via the existing test helper that creates a save + competition + members)

        // When
        generator.generate(saveGameId, 2026, List.of(competition));

        // Then: the first phase's 66 fixtures exist across rounds 0..10
        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveGameId);
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getRound()).isBetween(0, 10));
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isEqualTo(firstPhaseId));

        // And: rounds 11..16 are reserved as MATCH events carrying a phase but no fixtures
        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .sorted(Comparator.comparing(SeasonEvent::getEventDate)).toList();
        assertThat(events).hasSize(17);
        assertThat(events.subList(11, 17)).allSatisfy(e ->
                assertThat(e.getPhaseId()).isNotNull());
        assertThat(fixtures).noneSatisfy(f -> assertThat(f.getRound()).isGreaterThan(10));
    }

    @Test
    void matchDatesRemainFiveDaysApartAcrossAllSeventeenUnits() {
        generator.generate(saveGameId, 2026, List.of(competition));

        List<LocalDate> dates = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .map(SeasonEvent::getEventDate).sorted().toList();

        assertThat(dates).hasSize(17);
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(dates.get(16)).isEqualTo(LocalDate.of(2026, 1, 15).plusDays(16L * 5));
    }

    @Test
    void aCompetitionWithNoPhasesGeneratesExactlyAsBefore() {
        // Given a 20-club competition with no phase rows (Série B)
        generator.generate(saveGameId, 2026, List.of(serieB));

        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveGameId);
        assertThat(fixtures).hasSize(380);          // 20 clubs, double round-robin
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonCalendarGenerator*'
```

Expected: FAIL — phased competitions currently generate a double round-robin over 12 clubs.

- [ ] **Step 3: Make match-unit building phase-aware**

In `SeasonCalendarGenerator`, inject `CompetitionPhaseRepository` through the constructor alongside `competitionClubRepository`. Extend the `MatchUnit` record with the phase:

```java
    /** A scheduled match-unit: one competition's round, to be dated and persisted. */
    private record MatchUnit(UUID competitionId, UUID phaseId, int round, List<Fixture> fixtures) { }
```

Replace step 1 of `generate` (the per-competition round building) with a phase-aware version. Keep steps 2–4 (interleave, dating, transfer events) exactly as they are, other than passing `phaseId` through:

```java
        for (Competition comp : comps) {
            List<UUID> memberIds = competitionClubRepository.findByCompetitionId(comp.getId()).stream()
                    .map(cc -> cc.getClubId()).sorted().toList();
            List<CompetitionPhase> phases =
                    competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(comp.getId());

            List<MatchUnit> units = phases.isEmpty()
                    ? legacyLeagueUnits(saveGameId, comp, memberIds)
                    : phasedUnits(saveGameId, comp, memberIds, phases);

            roundsByComp.add(units);
            maxRounds = Math.max(maxRounds, units.size());
        }
```

Add the two builders. `legacyLeagueUnits` is the current code moved verbatim so the no-phase path is provably unchanged:

```java
    /** Pre-V47 behaviour: one double round-robin over all members, no phases. */
    private List<MatchUnit> legacyLeagueUnits(UUID saveGameId, Competition comp, List<UUID> memberIds) {
        List<List<Fixture>> byRound = new ArrayList<>();
        for (int r = 0; r < comp.getTotalRounds(); r++) {
            byRound.add(new ArrayList<>());
        }
        fixtureScheduler.doubleRoundRobin(memberIds.size()).forEach(scheduled -> {
            UUID home = memberIds.get(scheduled.homeIndex());
            UUID away = memberIds.get(scheduled.awayIndex());
            byRound.get(scheduled.round()).add(
                    Fixture.create(saveGameId, comp.getId(), scheduled.round(), home, away, null));
        });
        List<MatchUnit> units = new ArrayList<>();
        for (int r = 0; r < byRound.size(); r++) {
            units.add(new MatchUnit(comp.getId(), null, r, byRound.get(r)));
        }
        return units;
    }

    /**
     * Phase-aware units. A ROUND_ROBIN phase materializes its fixtures now; a KNOCKOUT phase
     * only reserves its match-units — participants are unknown until the previous phase ends,
     * and fixtures.home_club_id is NOT NULL so placeholders are impossible. PhaseResolver fills
     * these in during the season.
     */
    private List<MatchUnit> phasedUnits(UUID saveGameId, Competition comp, List<UUID> memberIds,
                                        List<CompetitionPhase> phases) {
        List<MatchUnit> units = new ArrayList<>();
        int roundOffset = 0;
        for (CompetitionPhase phase : phases) {
            if (phase.getKind() == PhaseKind.ROUND_ROBIN) {
                List<List<Fixture>> byRound = new ArrayList<>();
                for (int r = 0; r < phase.getRounds(); r++) {
                    byRound.add(new ArrayList<>());
                }
                List<ScheduledFixture> scheduled = phase.getLegs() == 1
                        ? fixtureScheduler.singleRoundRobin(memberIds.size())
                        : fixtureScheduler.doubleRoundRobin(memberIds.size());
                final int offset = roundOffset;
                scheduled.forEach(s -> {
                    UUID home = memberIds.get(s.homeIndex());
                    UUID away = memberIds.get(s.awayIndex());
                    Fixture fx = Fixture.create(saveGameId, comp.getId(),
                            offset + s.round(), home, away, null);
                    fx.setPhaseId(phase.getId());
                    byRound.get(s.round()).add(fx);
                });
                for (int r = 0; r < byRound.size(); r++) {
                    units.add(new MatchUnit(comp.getId(), phase.getId(), roundOffset + r, byRound.get(r)));
                }
            } else {
                // Reserved: dated and ordered, but no fixtures yet.
                for (int r = 0; r < phase.getRounds(); r++) {
                    units.add(new MatchUnit(comp.getId(), phase.getId(), roundOffset + r, List.of()));
                }
            }
            roundOffset += phase.getRounds();
        }
        return units;
    }
```

- [ ] **Step 3b: Switch `totalRounds` to the phase sum (deferred from Task 3)**

Task 3 deliberately left `SaveGameService` computing `totalRounds = (memberCount - 1) * 2` for **every** competition, phased or not, with a comment explaining why: until this task, the generator materialized every competition as one `doubleRoundRobin(memberCount)`, so a phase-sum of 17 would have undersized the round-bucket list against the 22 rounds `doubleRoundRobin(12)` actually produces and crashed career creation with `IndexOutOfBoundsException`.

Now that `phasedUnits` exists, make the switch in `SaveGameService`:

```java
            // A phased competition's length is the sum of its phases' match-units; an unphased
            // one keeps the legacy double-round-robin count.
            int totalRounds = (seedComp.phases() == null || seedComp.phases().isEmpty())
                    ? (seedComp.memberShortNames().size() - 1) * 2
                    : seedComp.phases().stream().mapToInt(SeedPhase::rounds).sum();
```

Delete the deferral comment when you make the change.

**This flips existing test expectations again.** Task 3 updated the Catarinense assertions to 22 rounds / 132 fixtures (a 12-club double round-robin). With phases live they become **17 match-units** and **66 first-phase fixtures** (12-club single round-robin), with the knockout rounds carrying no fixtures until `PhaseResolver` runs in Task 6. Update `CareerCreationCalendarIT`, `SaveGameServiceIT`, `TwoCompetitionCareerIT`, `SeasonAdvanceServiceIT`, `SeasonHistoryServiceIT` and `SeasonRolloverServiceIT` accordingly — these are count assertions, not behaviour.

Be aware that a season now ends with six unplayable knockout events until Task 6 lands, so any test that advances the Catarinense to completion will not reach `FINISHED` yet. Mark such a test `@Disabled` with a comment naming Task 6, and re-enable it there — do not delete it and do not weaken it.

- [ ] **Step 4: Persist the phase on the event**

In step 3 of `generate`, replace the event creation so a unit carrying a phase produces a phase-stamped event:

```java
            entityManager.persist(unit.phaseId() == null
                    ? SeasonEvent.createMatch(saveGameId, date, unit.competitionId(), unit.round(), 0)
                    : SeasonEvent.createKnockout(saveGameId, date, unit.competitionId(),
                            unit.phaseId(), unit.round(), 0));
```

The surrounding fixture loop is unchanged — a reserved unit simply has an empty fixture list, so nothing is persisted for it.

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonCalendarGenerator*'
```

Expected: PASS, including `aCompetitionWithNoPhasesGeneratesExactlyAsBefore`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorTest.java
git commit -m "feat(season): phase-aware calendar; knockout rounds reserved not materialized"
```

---

### Task 5: Per-phase standings

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/StandingsService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/StandingsServiceTest.java`

**Interfaces:**
- Consumes: `CompetitionPhaseRepository`, `PhaseKind` (Task 1)
- Produces: `StandingsService.computeStandings(SaveGame save, UUID competitionId, UUID phaseId, LocalDate asOfDate)` — a null `phaseId` means "the whole competition", preserving today's behaviour; `StandingsService.getStandings(UUID saveGameId, UUID competitionId, UUID phaseId, UUID userId)`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void standingsForAPhaseCountOnlyThatPhasesFixtures() {
        // Given: a competition whose first phase is complete and whose quarterfinals are played
        // When: standings are computed for the first phase
        List<StandingRow> rows = standingsService.computeStandings(save, competitionId, firstPhaseId, asOf);

        // Then: every club has played exactly 11 matches — knockout results are excluded
        assertThat(rows).hasSize(12);
        assertThat(rows).allSatisfy(r -> assertThat(r.played()).isEqualTo(11));
    }

    @Test
    void standingsWithANullPhaseIdCoverTheWholeCompetition() {
        // Given a plain league competition (Série B, no phases)
        List<StandingRow> rows = standingsService.computeStandings(save, serieBId, null, asOf);

        assertThat(rows).hasSize(20);
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*StandingsServiceTest*'
```

Expected: compilation failure — `computeStandings` has no `phaseId` parameter.

- [ ] **Step 3: Add the phase filter**

In `StandingsService`, add the overload and filter the fixtures the calculator sees. Keep the existing 3-arg `computeStandings(save, competitionId, asOfDate)` delegating with `null` so no existing caller changes:

```java
    public List<StandingRow> computeStandings(SaveGame save, UUID competitionId, LocalDate asOfDate) {
        return computeStandings(save, competitionId, null, asOfDate);
    }

    /**
     * Standings over a single phase's fixtures, or over the whole competition when
     * {@code phaseId} is null (a plain league, or a caller that wants the aggregate).
     * A knockout phase has no meaningful table; callers should render its ties instead.
     */
    public List<StandingRow> computeStandings(SaveGame save, UUID competitionId, UUID phaseId,
                                              LocalDate asOfDate) {
        // ... existing lookup of clubs and played fixtures, then:
        List<Fixture> scoped = phaseId == null
                ? playedFixtures
                : playedFixtures.stream().filter(f -> phaseId.equals(f.getPhaseId())).toList();
        return standingsCalculator.compute(clubs, scoped);
    }
```

Mirror the same optional `phaseId` on `getStandings`, threading it from the controller.

- [ ] **Step 4: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*Standings*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/StandingsService.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/StandingsServiceTest.java
git commit -m "feat(competition): per-phase standings"
```

---

### Task 6: `PhaseResolver` — materialize the quarterfinals

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/PhaseResolver.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/PhaseResolverTest.java`

**Interfaces:**
- Consumes: `CompetitionPhaseRepository`, `FixtureScheduler.knockoutPairings`, `StandingsService.computeStandings(save, competitionId, phaseId, asOfDate)` (Tasks 1, 2, 5)
- Produces: `PhaseResolver.resolveIfPhaseComplete(SaveGame save, SeasonEvent processedEvent) -> boolean` (true when it materialized a phase)

This task handles **round-robin → knockout** only. Knockout → knockout needs tie winners, which need extra time and penalties; Task 9 adds it.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void materializesQuarterfinalsFromTheFirstPhaseTable() {
        // Given: the first phase is fully played; the QF events are reserved and empty
        // When
        boolean resolved = phaseResolver.resolveIfPhaseComplete(save, lastFirstPhaseEvent);

        // Then: 4 ties = 8 fixtures across the two reserved QF rounds
        assertThat(resolved).isTrue();
        List<Fixture> qf = fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, quarterFinalPhaseId);
        assertThat(qf).hasSize(8);
        assertThat(qf).extracting(Fixture::getTieId).doesNotContainNull();
        assertThat(new HashSet<>(qf.stream().map(Fixture::getTieId).toList())).hasSize(4);
    }

    @Test
    void seedsTopAgainstBottomWithTheHigherSeedHostingTheSecondLeg() {
        phaseResolver.resolveIfPhaseComplete(save, lastFirstPhaseEvent);

        List<StandingRow> table = standingsService.computeStandings(save, competitionId, firstPhaseId, asOf);
        UUID first = table.get(0).clubId();
        UUID eighth = table.get(7).clubId();

        List<Fixture> tie = fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, quarterFinalPhaseId)
                .stream()
                .filter(f -> f.getHomeClubId().equals(first) || f.getAwayClubId().equals(first))
                .sorted(Comparator.comparing(Fixture::getLeg)).toList();

        assertThat(tie).hasSize(2);
        // Leg 1 away for the top seed, leg 2 at home
        assertThat(tie.get(0).getHomeClubId()).isEqualTo(eighth);
        assertThat(tie.get(0).getAwayClubId()).isEqualTo(first);
        assertThat(tie.get(1).getHomeClubId()).isEqualTo(first);
        assertThat(tie.get(1).getAwayClubId()).isEqualTo(eighth);
    }

    @Test
    void resolvingTwiceDoesNotDuplicateFixtures() {
        phaseResolver.resolveIfPhaseComplete(save, lastFirstPhaseEvent);
        boolean second = phaseResolver.resolveIfPhaseComplete(save, lastFirstPhaseEvent);

        assertThat(second).isFalse();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, quarterFinalPhaseId))
                .hasSize(8);
    }

    @Test
    void doesNothingWhileThePhaseIsStillRunning() {
        boolean resolved = phaseResolver.resolveIfPhaseComplete(save, midFirstPhaseEvent);

        assertThat(resolved).isFalse();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, quarterFinalPhaseId))
                .isEmpty();
    }

    @Test
    void identicalStandingsProduceIdenticalPairings() {
        phaseResolver.resolveIfPhaseComplete(save, lastFirstPhaseEvent);
        List<String> first = pairingSignature(saveGameId, quarterFinalPhaseId);

        // Re-run against a second save seeded with identical results
        phaseResolver.resolveIfPhaseComplete(otherSave, otherLastFirstPhaseEvent);
        List<String> second = pairingSignature(otherSaveGameId, otherQuarterFinalPhaseId);

        assertThat(second).isEqualTo(first);
    }
```

`FixtureRepository.findBySaveGameIdAndPhaseId` was added in Task 1 — no repository change is needed here.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*PhaseResolverTest*'
```

Expected: compilation failure — `PhaseResolver` does not exist.

- [ ] **Step 3: Implement `PhaseResolver`**

`backend/src/main/java/br/com/carvoeiromanager/competition/PhaseResolver.java`:

```java
package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Fills in a knockout phase's fixtures once the phase before it has finished. Knockout rounds
 * are reserved (dated, no fixtures) at generation because fixtures.home_club_id is NOT NULL and
 * the participants are unknown until the preceding phase ends.
 *
 * <p>Pairings are a pure function of the previous phase's standings — no RNG, no wall-clock — so
 * a replayed season produces an identical bracket. Materialization is idempotent: it runs only
 * when the target phase has no fixtures yet.
 */
@Service
public class PhaseResolver {

    private static final Logger log = LoggerFactory.getLogger(PhaseResolver.class);

    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final StandingsService standingsService;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public PhaseResolver(CompetitionPhaseRepository competitionPhaseRepository,
                         FixtureRepository fixtureRepository,
                         SeasonEventRepository seasonEventRepository,
                         StandingsService standingsService) {
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.standingsService = standingsService;
    }

    /**
     * If {@code processedEvent} was the last unprocessed event of its phase, materialize the
     * next phase's fixtures. Returns true when fixtures were created.
     */
    @Transactional
    public boolean resolveIfPhaseComplete(SaveGame save, SeasonEvent processedEvent) {
        UUID phaseId = processedEvent.getPhaseId();
        if (phaseId == null) {
            return false; // a plain league round; nothing to resolve
        }
        List<CompetitionPhase> phases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(processedEvent.getCompetitionId());

        CompetitionPhase current = phases.stream().filter(p -> p.getId().equals(phaseId))
                .findFirst().orElse(null);
        if (current == null || !phaseComplete(save.getId(), current, processedEvent.getId())) {
            return false;
        }
        CompetitionPhase next = phases.stream()
                .filter(p -> p.getOrdinal() == current.getOrdinal() + 1).findFirst().orElse(null);
        if (next == null || next.getKind() != PhaseKind.KNOCKOUT) {
            return false;
        }
        if (!fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), next.getId()).isEmpty()) {
            return false; // already materialized — idempotent
        }
        if (current.getKind() != PhaseKind.ROUND_ROBIN) {
            return false; // knockout -> knockout is handled by TieResolver (see PhaseResolver#advanceWinners)
        }

        List<UUID> seeds = standingsService
                .computeStandings(save, current.getCompetitionId(), current.getId(), processedEvent.getEventDate())
                .stream().map(StandingRow::clubId).limit(current.getQualifiers()).toList();

        materialize(save.getId(), current.getCompetitionId(), next, seeds);
        log.info("Materialized {} for competition {} from {} qualifiers",
                next.getName(), current.getCompetitionId(), seeds.size());
        return true;
    }

    /** True when every MATCH event of this phase other than the one just handled is processed. */
    private boolean phaseComplete(UUID saveGameId, CompetitionPhase phase, UUID justProcessedId) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> phase.getId().equals(e.getPhaseId()))
                .allMatch(e -> e.isProcessed() || e.getId().equals(justProcessedId));
    }

    /**
     * Create one tie per pairing across the phase's reserved rounds. The higher seed hosts the
     * second leg. Fixture dates come from the reserved events, which are already dated.
     */
    private void materialize(UUID saveGameId, UUID competitionId, CompetitionPhase phase, List<UUID> seeds) {
        List<SeasonEvent> reserved = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> phase.getId().equals(e.getPhaseId()))
                .sorted(Comparator.comparing(SeasonEvent::getEventDate)).toList();

        List<int[]> pairings = fixtureScheduler.knockoutPairings(seeds.size());
        List<Fixture> created = new ArrayList<>();
        for (int order = 0; order < pairings.size(); order++) {
            int[] pairing = pairings.get(order);
            UUID higher = seeds.get(pairing[0]);
            UUID lower = seeds.get(pairing[1]);
            UUID tieId = UUID.randomUUID();
            created.add(leg(saveGameId, competitionId, phase, reserved.get(0), tieId, order, 1, lower, higher));
            created.add(leg(saveGameId, competitionId, phase, reserved.get(1), tieId, order, 2, higher, lower));
        }
        fixtureRepository.saveAll(created);
    }

    private Fixture leg(UUID saveGameId, UUID competitionId, CompetitionPhase phase, SeasonEvent event,
                        UUID tieId, int tieOrder, int leg, UUID homeClubId, UUID awayClubId) {
        Fixture fixture = Fixture.create(saveGameId, competitionId, event.getRound(),
                homeClubId, awayClubId, event.getEventDate());
        fixture.setPhaseId(phase.getId());
        fixture.setTieId(tieId);
        fixture.setTieOrder(tieOrder);
        fixture.setLeg(leg);
        return fixture;
    }
}
```

`UUID.randomUUID()` for `tieId` is safe for determinism **because `tie_order` carries the ordering**: the tie id is an opaque grouping key, never an input to results, pairings, or sort order. Pairings and leg orientation come only from standings; bracket position comes only from `tie_order`.

Add `Fixture.findBySaveGameIdAndPhaseId` usage here; it was declared in Task 1.

- [ ] **Step 4: Wire it into the advance**

In `SeasonAdvanceService.recordMatchRound`, inject `PhaseResolver` and call it after the event is marked processed and before the season-finished check reads the remaining events:

```java
        phaseResolver.resolveIfPhaseComplete(save, event);
```

Place it immediately after the existing `financeService.processMatchday(...)` call. It must run before `noMoreMatchEvents(...)` so a newly materialized knockout does not read as "season over".

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*PhaseResolverTest*' --tests '*SeasonAdvance*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/PhaseResolver.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/FixtureRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/PhaseResolverTest.java
git commit -m "feat(competition): PhaseResolver materializes seeded quarterfinals"
```

---

### Task 7: Extra time in both engines

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/ExtraTimeTest.java`

**Interfaces:**
- Consumes: `PhaseKind`, `Fixture.getLeg/getTieId/getPhaseId` (Task 1)
- Produces: `MatchMath.EXTRA_TIME_END = 120`; `EventType.EXTRA_TIME_START`; `FixtureSimulator` and `LiveMatchEngine` simulate minutes 91–120 when and only when asked

**CRITICAL:** `MatchMath.MINUTES` stays `90`. It divides `xg` and normalizes `fitnessFactor`. Changing it rescales every probability in the game.

- [ ] **Step 1: Write the failing determinism + trigger tests**

`backend/src/test/java/br/com/carvoeiromanager/match/ExtraTimeTest.java`:

`FixtureSim` is a **private** record inside `FixtureSimulator`, so tests assert through the public path: `simulateRound(save, competitionId, fixtures)` returns a `RoundOutcome`, and the fixtures themselves are mutated with the final score. Read results from the `Fixture` rows and from `MatchEventRepository`.

```java
    @Test
    void aLeagueRoundIsByteIdenticalToTheGoldenResult() {
        // Given: the exact save, competition and round used to capture GOLDEN below,
        // simulated with the same seed. GOLDEN was captured from the engine BEFORE this task.
        simulator.simulateRound(save, competitionId, leagueFixtures);

        List<String> actual = leagueFixtures.stream()
                .map(f -> f.getHomeGoals() + "-" + f.getAwayGoals()).toList();
        assertThat(actual).isEqualTo(GOLDEN_LEAGUE_SCORES);
    }

    @Test
    void aDrawnLeagueFixtureStaysADrawAndDrawsNoExtraTime() {
        simulator.simulateRound(save, competitionId, List.of(drawnLeagueFixture));

        assertThat(drawnLeagueFixture.getHomeGoals()).isEqualTo(drawnLeagueFixture.getAwayGoals());
        assertThat(drawnLeagueFixture.getPenaltyHome()).isNull();
        assertThat(eventTypesOf(drawnLeagueFixture)).doesNotContain(EventType.EXTRA_TIME_START.name());
    }

    @Test
    void aLevelSecondLegGoesToExtraTime() {
        // Leg 1 finished 1-1, so this second leg is level on aggregate at 90
        simulator.simulateRound(save, competitionId, List.of(levelSecondLeg));

        assertThat(eventTypesOf(levelSecondLeg)).contains(EventType.EXTRA_TIME_START.name());
        assertThat(minutesOf(levelSecondLeg)).allSatisfy(m -> assertThat(m).isLessThanOrEqualTo(120));
    }

    @Test
    void aDecidedSecondLegDoesNotGoToExtraTime() {
        // Leg 1 ended 3-0, so the tie cannot be level regardless of tonight's score
        simulator.simulateRound(save, competitionId, List.of(decidedSecondLeg));

        assertThat(eventTypesOf(decidedSecondLeg)).doesNotContain(EventType.EXTRA_TIME_START.name());
    }

    @Test
    void aFirstLegNeverGoesToExtraTime() {
        simulator.simulateRound(save, competitionId, List.of(firstLeg));

        assertThat(eventTypesOf(firstLeg)).doesNotContain(EventType.EXTRA_TIME_START.name());
    }

    /** MatchEvent.type is a String column — compare against EventType.name(). */
    private List<String> eventTypesOf(Fixture fixture) {
        return matchEventRepository.findByFixtureId(fixture.getId()).stream()
                .map(MatchEvent::getType).toList();
    }

    private List<Integer> minutesOf(Fixture fixture) {
        return matchEventRepository.findByFixtureId(fixture.getId()).stream()
                .map(MatchEvent::getMinute).toList();
    }
```

`GOLDEN_LEAGUE_SCORES` must be captured by running the **current** engine before any change in this task and pasting the literal scores in. Do not synthesize them — a fabricated golden proves nothing.

- [ ] **Step 2: Capture the golden values and confirm the tests fail**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ExtraTimeTest*'
```

Expected: FAIL — `EventType.EXTRA_TIME_START` does not exist.

- [ ] **Step 3: Add the period bound to `MatchMath`**

```java
    static final int MINUTES = 90;

    /**
     * Last minute of extra time. NOTE: {@link #MINUTES} stays 90 — it is the per-minute rate
     * divisor for {@link #perMinuteScoreChance} and the normalizer in {@link #fitnessFactor},
     * not a loop bound. A minute of extra time carries the same scoring rate as a minute of
     * normal time, and fitnessFactor keeps decaying past 90 (frac > 1) down to FITNESS_FLOOR.
     */
    static final int EXTRA_TIME_END = 120;
```

- [ ] **Step 4: Add the event type**

In `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`, add `EXTRA_TIME_START` to the enum constant list.

- [ ] **Step 5: Make the instant path period-aware**

**Correction to this plan (2026-08-06).** An earlier draft said the instant scoring loop lives in `FixtureSimulator` at the `minute <= 90` line. That is wrong — that line is inside `foulCounts`, a *separate* per-minute foul draw. There are three minute loops in the match package:

| Location | What it is |
|---|---|
| `MatchSimulationService:48` | **the instant scoring loop** — this is the one extra time extends |
| `LiveMatchEngine:123` | the live scoring loop |
| `FixtureSimulator:593` | `foulCounts` — a separate foul draw, not scoring |

`FixtureSimulator` does not score matches itself; it calls `matchSimulationService.simulate(home, away, weather, seed)` at line 227.

`MatchSimulationService.simulate` has no access to the fixture or its tie, so it cannot decide whether extra time is needed. Give it the bound instead, and let the caller decide:

```java
    /** Existing entry point: a normal 90-minute match. */
    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, Weather weather, long seed) {
        return simulate(home, away, weather, seed, MatchMath.MINUTES);
    }

    /**
     * @param finalMinute the last minute to simulate: {@link MatchMath#MINUTES} normally, or
     *                    {@link MatchMath#EXTRA_TIME_END} for a knockout decider that is level
     *                    at 90. Minutes past 90 use the same per-minute rates — MINUTES stays
     *                    the rate divisor and is never changed.
     */
    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, Weather weather,
                                   long seed, int finalMinute) {
```

Then change only the loop bound, `for (int minute = 1; minute <= finalMinute; minute++)`, leaving every `MatchMath.MINUTES` reference inside the body untouched — those are rate normalizers.

**The determinism contract falls out of this shape for free:** the 4-arg overload passes `MINUTES`, so every league match runs the identical loop with the identical RNG draws as before. Extra time cannot perturb a league result because it never executes.

`FixtureSimulator` decides whether to ask for it:

```java
        int finalMinute = needsExtraTime(fixture) ? MatchMath.EXTRA_TIME_END : MatchMath.MINUTES;
        SimulatedMatch match = matchSimulationService.simulate(
                homeInput, awayInput, weather, seed, finalMinute);
```

**Note on `foulCounts`:** it draws fouls over a hard-coded 1..90 and is keyed to that range. Extending it to 120 would change foul totals for extra-time matches only. Leave it at 90 for this task and say so in your report — an extra-time match simply records no fouls from minutes 91–120. Fixing that is a follow-up, not a silent side effect of this task.

```java
    /**
     * True when this fixture must produce a winner: a knockout tie that is level after 90.
     * League fixtures and decided ties return false, so no extra-time RNG is ever drawn for
     * them and their results stay byte-identical to the pre-knockout engine.
     */
    private boolean needsExtraTime(Fixture fixture, int homeGoals, int awayGoals) {
        if (fixture.getTieId() == null) {
            return false;                       // league fixture
        }
        if (fixture.getLeg() == null || fixture.getLeg() == 1) {
            return false;                       // first legs never go to extra time
        }
        Fixture firstLeg = fixtureRepository.findByTieId(fixture.getTieId()).stream()
                .filter(f -> f.getLeg() != null && f.getLeg() == 1)
                .findFirst().orElse(null);
        if (firstLeg == null || !firstLeg.isPlayed()) {
            return false;
        }
        // Crossover: this fixture's home club was the AWAY club in leg 1.
        int aggHome = homeGoals + firstLeg.getAwayGoals();
        int aggAway = awayGoals + firstLeg.getHomeGoals();
        return aggHome == aggAway;
    }
```

`FixtureSimulator` needs `FixtureRepository` injected for this; it is a Spring bean, so add it to the constructor.

Then extract the existing loop body. **Do not duplicate it** — pull minutes 1–90's body into a private method and call it from both ranges, so extra time cannot drift from normal time:

```java
        // Existing normal-time loop, now delegating to the extracted body.
        for (int minute = 1; minute <= MatchMath.MINUTES; minute++) {
            simulateMinute(minute, /* the exact parameter list the current loop body closes over */);
        }

        if (needsExtraTime(fixture, homeGoals, awayGoals)) {
            events.add(MatchEvent.create(fixture.getId(), MatchMath.MINUTES,
                    EventType.EXTRA_TIME_START.name(), null, null));
            for (int minute = MatchMath.MINUTES + 1; minute <= MatchMath.EXTRA_TIME_END; minute++) {
                simulateMinute(minute, /* same arguments */);
            }
        }
```

`MatchEvent.create(UUID fixtureId, int minute, String type, UUID playerId, UUID clubId)` is the real factory and `type` is a **String** — pass `EventType.X.name()`, never the enum.

Every RNG draw stays inside the `if`, so a league match consumes exactly the draws it consumed before. Extracting the body is a pure refactor: run the full suite after the extraction and **before** adding the extra-time call, to prove the refactor alone changed nothing.

- [ ] **Step 6: Make the live path period-aware**

First give `LiveMatchConfig` the flag. It is built at kickoff, where the fixture and its tie are known:

```java
    /**
     * True when this match must produce a winner tonight: the second leg of a knockout tie.
     * Whether extra time actually happens depends on the score at 90; this only says the
     * match is allowed to run past it.
     */
    private final boolean knockoutDecider;

    public boolean isKnockoutDecider() { return knockoutDecider; }
```

Set it where `LiveMatchConfig` is constructed in `LiveMatchService#kickoff`: `fixture.getTieId() != null && fixture.getLeg() != null && fixture.getLeg() == 2`.

Then in `LiveMatchService`, `MINUTES = 90` is the full-time boundary for the *live* flow. Add:

```java
    static final int MINUTES = 90;
    static final int EXTRA_TIME_END = 120;

    /**
     * The last minute this match can reach. A knockout decider that is level on aggregate at 90
     * runs to 120; everything else stops at 90.
     */
    private int finalMinuteFor(LiveMatch lm, LiveMatchConfig cfg) {
        if (!cfg.isKnockoutDecider() || lm.getCommittedMinute() < MINUTES) {
            return cfg.isKnockoutDecider() ? EXTRA_TIME_END : MINUTES;
        }
        return aggregateLevel(lm, cfg) ? EXTRA_TIME_END : MINUTES;
    }
```

Replace each `MINUTES` comparison that means "is the match over" (lines 194, 227, 241, 252, 316, 343, 383) with `finalMinuteFor(lm, cfg)`. Leave line 337's `MINUTES / 2` (half-time) and line 472's `managedRatings(..., MINUTES)` alone — those mean "the 90-minute mark", not "the end".

Update the phase string at line 987:

```java
        if (minute >= EXTRA_TIME_END) return "FULL_TIME";
        if (minute > MINUTES) return "EXTRA_TIME";
        if (minute >= MINUTES) return cfg.needsExtraTime() ? "EXTRA_TIME_PENDING" : "FULL_TIME";
```

In `LiveMatchEngine`, line 123's `for (int minute = 1; minute <= MatchMath.MINUTES; minute++)` becomes bounded by the config's final minute. Line 586's `teamChance /= MatchMath.MINUTES` **stays `MINUTES`** — it is the rate divisor. Lines 216 and 222 (`exitMinute` defaults) should use the match's final minute so a player who plays all of extra time is credited 120 minutes.

- [ ] **Step 7: Raise the substitution limit in extra time**

In `LiveMatchService`, replace the flat `MAX_SUBS` check at line 205:

```java
    /** Five substitutions in normal time; a sixth becomes available once extra time begins. */
    private int maxSubsAt(int minute) {
        return minute > MINUTES ? MAX_SUBS + 1 : MAX_SUBS;
    }
```

and use `maxSubsAt(lm.getCommittedMinute())` at the check and at line 678's remaining-substitutions calculation.

- [ ] **Step 8: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ExtraTimeTest*'
```

Expected: PASS.

- [ ] **Step 9: Run the calibration test — this is the gate**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*MatchCalibrationTest*'
```

Expected: PASS **with no recalibration**. If it fails, extra-time code is drawing RNG on league matches — fix the gating, do not retune `ANCHOR`.

- [ ] **Step 10: Run the full suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ \
        backend/src/test/java/br/com/carvoeiromanager/match/ExtraTimeTest.java
git commit -m "feat(match): 30-minute extra time for level knockout ties, no RNG when unused"
```

---

### Task 8: Penalty shootout

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/Shootout.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/ShootoutTest.java`

**Interfaces:**
- Consumes: `MatchMath.EXTRA_TIME_END` (Task 7)
- Produces: `Shootout.decide(List<Player> homeTakers, List<Player> awayTakers, Player homeKeeper, Player awayKeeper, long seed) -> Shootout.Result(int homeScore, int awayScore, List<Kick> kicks)`; `EventType.PENALTY_SHOOTOUT_SCORED`, `EventType.PENALTY_SHOOTOUT_MISSED`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void aShootoutAlwaysProducesAWinner() {
        for (long seed = 0; seed < 200; seed++) {
            Shootout.Result result = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, seed);
            assertThat(result.homeScore()).isNotEqualTo(result.awayScore());
        }
    }

    @Test
    void theSameSeedProducesTheSameShootout() {
        Shootout.Result a = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, 42L);
        Shootout.Result b = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, 42L);

        assertThat(b.homeScore()).isEqualTo(a.homeScore());
        assertThat(b.awayScore()).isEqualTo(a.awayScore());
        assertThat(b.kicks()).isEqualTo(a.kicks());
    }

    @Test
    void betterFinishersConvertMoreOften() {
        int eliteGoals = 0;
        int poorGoals = 0;
        for (long seed = 0; seed < 500; seed++) {
            eliteGoals += Shootout.decide(eliteTakers, poorTakers, avgGk, avgGk, seed).homeScore();
            poorGoals  += Shootout.decide(poorTakers, eliteTakers, avgGk, avgGk, seed).homeScore();
        }
        assertThat(eliteGoals).isGreaterThan(poorGoals);
    }

    @Test
    void theFirstFiveRoundsEndEarlyWhenTheOutcomeIsSettled() {
        // A 5-0 lead after three rounds cannot be caught: at most 8 kicks are taken
        Shootout.Result result = Shootout.decide(eliteTakers, hopelessTakers, avgGk, worldClassGk, 7L);

        assertThat(result.kicks().size()).isLessThanOrEqualTo(10);
    }

    @Test
    void suddenDeathTerminates() {
        // Identical squads, worst case for early resolution
        Shootout.Result result = Shootout.decide(homeTakers, homeTakers, homeGk, homeGk, 99L);

        assertThat(result.kicks().size()).isLessThan(60);
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ShootoutTest*'
```

Expected: compilation failure — `Shootout` does not exist.

- [ ] **Step 3: Implement `Shootout`**

`backend/src/main/java/br/com/carvoeiromanager/match/Shootout.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Penalty shootout: best of five, then sudden death. Pure and seeded — no Spring, no I/O.
 * Takers are the players on the pitch at the end of 120 minutes, best finishers first, then
 * cycling in the same order once the list is exhausted.
 *
 * <p>Shootout conversions are NOT goals: they decide who advances and never enter standings,
 * top scorers, or player statistics.
 */
public final class Shootout {

    /** Conversion odds for an average finisher against an average keeper. */
    private static final double BASE_CONVERSION = 0.76;
    private static final double FINISHING_WEIGHT = 0.0035;
    private static final double KEEPER_WEIGHT = 0.0030;
    private static final double MIN_CONVERSION = 0.35;
    private static final double MAX_CONVERSION = 0.95;

    /** Guards against a pathological infinite sudden death. */
    private static final int MAX_ROUNDS = 25;

    private Shootout() {
    }

    public record Kick(boolean home, UUID takerId, int round, boolean scored) { }

    public record Result(int homeScore, int awayScore, List<Kick> kicks) { }

    public static Result decide(List<Player> homeTakers, List<Player> awayTakers,
                                Player homeKeeper, Player awayKeeper, long seed) {
        Random rng = new Random(seed ^ 0x5EED9E4A17L);
        List<Player> home = orderedTakers(homeTakers);
        List<Player> away = orderedTakers(awayTakers);

        List<Kick> kicks = new ArrayList<>();
        int homeScore = 0;
        int awayScore = 0;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            boolean suddenDeath = round > 5;

            if (!suddenDeath && decided(homeScore, awayScore, round, true)) break;
            Player homeTaker = home.get((round - 1) % home.size());
            boolean homeScored = convert(homeTaker, awayKeeper, rng);
            kicks.add(new Kick(true, homeTaker.getId(), round, homeScored));
            if (homeScored) homeScore++;

            if (!suddenDeath && decided(homeScore, awayScore, round, false)) break;
            Player awayTaker = away.get((round - 1) % away.size());
            boolean awayScored = convert(awayTaker, homeKeeper, rng);
            kicks.add(new Kick(false, awayTaker.getId(), round, awayScored));
            if (awayScored) awayScore++;

            if (suddenDeath && homeScore != awayScore) break;
        }

        // MAX_ROUNDS reached while still level: the better cumulative finisher advances.
        if (homeScore == awayScore) {
            homeScore += averageFinishing(home) >= averageFinishing(away) ? 1 : 0;
            awayScore += averageFinishing(home) >= averageFinishing(away) ? 0 : 1;
        }
        return new Result(homeScore, awayScore, List.copyOf(kicks));
    }

    /** Best finishers first; ties broken by id so the order is stable across runs. */
    private static List<Player> orderedTakers(List<Player> takers) {
        return takers.stream()
                .sorted(Comparator.comparingInt(Player::getFinishing).reversed()
                        .thenComparing(Player::getId))
                .toList();
    }

    private static boolean convert(Player taker, Player keeper, Random rng) {
        double p = BASE_CONVERSION
                + FINISHING_WEIGHT * (taker.getFinishing() - 50)
                - KEEPER_WEIGHT * (keeper.getGoalkeeping() - 50);
        return rng.nextDouble() < Math.max(MIN_CONVERSION, Math.min(MAX_CONVERSION, p));
    }

    /**
     * True when the remaining kicks of the best-of-five cannot change the outcome.
     * {@code beforeHomeKick} distinguishes the two halves of a round.
     */
    private static boolean decided(int homeScore, int awayScore, int round, boolean beforeHomeKick) {
        int homeRemaining = 5 - (round - 1);
        int awayRemaining = 5 - (round - 1) - (beforeHomeKick ? 0 : 1);
        return homeScore > awayScore + awayRemaining || awayScore > homeScore + homeRemaining;
    }

    private static double averageFinishing(List<Player> takers) {
        return takers.stream().mapToInt(Player::getFinishing).average().orElse(0);
    }
}
```

- [ ] **Step 4: Add the event types**

Add `PENALTY_SHOOTOUT_SCORED` and `PENALTY_SHOOTOUT_MISSED` to `EventType`.

- [ ] **Step 5: Call it from `FixtureSimulator`**

After the extra-time block from Task 7, when the tie is *still* level:

```java
    /** Salt keeping the shootout's RNG stream distinct from the match's. */
    private static final long SHOOTOUT_SEED_SALT = 0x5A17E9C3L;

        if (extraTimePlayed && needsExtraTime(fixture, homeGoals, awayGoals)) {
            Shootout.Result shootout = Shootout.decide(
                    onPitchHome, onPitchAway, homeKeeper, awayKeeper, seed ^ SHOOTOUT_SEED_SALT);
            fixture.setPenaltyHome(shootout.homeScore());
            fixture.setPenaltyAway(shootout.awayScore());
            for (Shootout.Kick kick : shootout.kicks()) {
                events.add(MatchEvent.create(fixture.getId(), MatchMath.EXTRA_TIME_END,
                        (kick.scored() ? EventType.PENALTY_SHOOTOUT_SCORED
                                       : EventType.PENALTY_SHOOTOUT_MISSED).name(),
                        kick.takerId(), null));
            }
        }
```

Two things that will bite otherwise:

- **Do not spell the salt as a word.** `0x5H00700L` ("shoot") and similar are not valid hex — `H` and `O` are not hex digits and the file will not compile. `SHOOTOUT_SEED_SALT` above uses real hex digits only.
- Reusing `needsExtraTime(...)` here is correct: after extra time the aggregate is recomputed from the *updated* `homeGoals`/`awayGoals`, so it returns true only if the tie is still level.

`homeGoals`/`awayGoals` are untouched by the shootout — the 120-minute score stands as the match result.

- [ ] **Step 6: Call it from the live path**

In `LiveMatchService`, at the point where the match is finalised (the existing full-time path that calls `completeUserMatch`), add the shootout when the decider is still level after 120:

```java
        if (cfg.isKnockoutDecider() && lm.getCommittedMinute() >= EXTRA_TIME_END
                && aggregateLevel(lm, cfg)) {
            Shootout.Result shootout = Shootout.decide(
                    onPitch(lm, true), onPitch(lm, false),
                    keeperOf(lm, true), keeperOf(lm, false),
                    lm.getSeed() ^ SHOOTOUT_SEED_SALT);
            fixture.setPenaltyHome(shootout.homeScore());
            fixture.setPenaltyAway(shootout.awayScore());
            for (Shootout.Kick kick : shootout.kicks()) {
                timeline.add(new LiveTimelineEvent(EXTRA_TIME_END,
                        (kick.scored() ? EventType.PENALTY_SHOOTOUT_SCORED
                                       : EventType.PENALTY_SHOOTOUT_MISSED).name(),
                        kick.takerId(), kick.home()));
            }
        }
```

Match `LiveTimelineEvent`'s actual constructor rather than the shape sketched here — check it before writing. The kicks go on the timeline in order so the UI can play them back one at a time.

Then add Mastella lines in `LiveCommentary.java` for the three new event types, following the pt-BR voice already there — one for extra time starting, and separate scored/missed lines for shootout kicks. Without these the shootout plays out silently, which reads as a bug.

- [ ] **Step 7: Run the tests, then the calibration gate**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ShootoutTest*' --tests '*MatchCalibrationTest*' --tests '*ExtraTimeTest*'
```

Expected: PASS, calibration unchanged.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ \
        backend/src/test/java/br/com/carvoeiromanager/match/ShootoutTest.java
git commit -m "feat(match): seeded penalty shootout with sudden death"
```

---

### Task 9: Tie resolution and knockout-to-knockout advancement

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/KnockoutTie.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/TieResolver.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/PhaseResolver.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/TieResolverTest.java`

**Interfaces:**
- Consumes: `Fixture` knockout columns (Task 1), `PhaseResolver` (Task 6)
- Produces: `KnockoutTie(UUID tieId, UUID higherSeedClubId, UUID lowerSeedClubId, Fixture firstLeg, Fixture secondLeg)`; `TieResolver.winner(KnockoutTie tie) -> UUID`; `TieResolver.tiesOf(List<Fixture> phaseFixtures) -> List<KnockoutTie>`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void theAggregateWinnerAdvances() {
        // Leg 1: A 2-1 B. Leg 2: B 1-1 A. Aggregate A 3-2.
        assertThat(TieResolver.winner(tie(clubA, clubB, 2, 1, 1, 1))).isEqualTo(clubA);
    }

    @Test
    void awayGoalsDoNotBreakALevelAggregate() {
        // Leg 1: A 0-0 B. Leg 2: B 1-1 A. Aggregate 1-1; A scored away, B did not.
        // Without an away-goals rule this must go to penalties, not to A.
        KnockoutTie level = tieWithPenalties(clubA, clubB, 0, 0, 1, 1, /* pens */ 2, 4);
        assertThat(TieResolver.winner(level)).isEqualTo(clubB);
    }

    @Test
    void penaltiesDecideALevelAggregate() {
        assertThat(TieResolver.winner(tieWithPenalties(clubA, clubB, 1, 1, 2, 2, 5, 3)))
                .isEqualTo(clubB);   // clubB was the leg-2 home side and won the shootout 5-3
    }

    @Test
    void anUnplayedTieHasNoWinner() {
        assertThatThrownBy(() -> TieResolver.winner(unplayedTie))
                .isInstanceOf(IllegalStateException.class);
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*TieResolverTest*'
```

Expected: compilation failure.

- [ ] **Step 3: Implement `KnockoutTie` and `TieResolver`**

`KnockoutTie`:

```java
package br.com.carvoeiromanager.competition;

import java.util.UUID;

/**
 * Two fixtures forming one knockout tie. The higher seed hosts the second leg, so
 * {@code secondLeg.getHomeClubId()} is always the higher seed. The aggregate is computed,
 * never stored.
 */
public record KnockoutTie(UUID tieId, UUID higherSeedClubId, UUID lowerSeedClubId,
                          Fixture firstLeg, Fixture secondLeg) { }
```

`TieResolver`:

```java
package br.com.carvoeiromanager.competition;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides a knockout tie: aggregate over both legs, then the shootout recorded on the second
 * leg. No away-goals rule — it has been abolished in every competition this models.
 */
public final class TieResolver {

    private TieResolver() {
    }

    /** Groups a phase's fixtures into ties by {@code tie_id}, legs ordered 1 then 2. */
    public static List<KnockoutTie> tiesOf(List<Fixture> phaseFixtures) {
        Map<UUID, List<Fixture>> byTie = phaseFixtures.stream()
                .filter(f -> f.getTieId() != null)
                .collect(Collectors.groupingBy(Fixture::getTieId));

        return byTie.entrySet().stream()
                .map(entry -> {
                    List<Fixture> legs = entry.getValue().stream()
                            .sorted(Comparator.comparing(Fixture::getLeg)).toList();
                    Fixture first = legs.get(0);
                    Fixture second = legs.size() > 1 ? legs.get(1) : null;
                    // The higher seed hosts the second leg (PhaseResolver's convention).
                    UUID higher = second != null ? second.getHomeClubId() : first.getHomeClubId();
                    UUID lower = second != null ? second.getAwayClubId() : first.getAwayClubId();
                    return new KnockoutTie(entry.getKey(), higher, lower, first, second);
                })
                // tie_order preserves seeded bracket position; tie_id is opaque and would
                // sort arbitrarily, scrambling the seeding when winners advance.
                .sorted(Comparator.comparing(t -> t.firstLeg().getTieOrder()))
                .toList();
    }

    /**
     * The club that advances. Aggregate first; if level, the shootout on the second leg.
     *
     * @throws IllegalStateException if either leg is unplayed, or the tie is level with no
     *                               shootout recorded — both indicate a bug upstream, not a
     *                               user-reachable state.
     */
    public static UUID winner(KnockoutTie tie) {
        Fixture first = tie.firstLeg();
        Fixture second = tie.secondLeg();
        if (second == null || !first.isPlayed() || !second.isPlayed()) {
            throw new IllegalStateException("Tie " + tie.tieId() + " is not fully played");
        }
        // The higher seed is away in leg 1 and home in leg 2.
        int higherAggregate = first.getAwayGoals() + second.getHomeGoals();
        int lowerAggregate = first.getHomeGoals() + second.getAwayGoals();

        if (higherAggregate != lowerAggregate) {
            return higherAggregate > lowerAggregate ? tie.higherSeedClubId() : tie.lowerSeedClubId();
        }
        if (second.getPenaltyHome() == null || second.getPenaltyAway() == null) {
            throw new IllegalStateException("Level tie " + tie.tieId() + " has no shootout result");
        }
        return second.getPenaltyHome() > second.getPenaltyAway()
                ? tie.higherSeedClubId() : tie.lowerSeedClubId();
    }
}
```

- [ ] **Step 4: Extend `PhaseResolver` to knockout → knockout**

Replace the early return for a non-round-robin current phase with winner advancement. Winners keep their original seeding order, then re-pair best against worst:

```java
        List<UUID> seeds;
        if (current.getKind() == PhaseKind.ROUND_ROBIN) {
            seeds = standingsService
                    .computeStandings(save, current.getCompetitionId(), current.getId(),
                            processedEvent.getEventDate())
                    .stream().map(StandingRow::clubId).limit(current.getQualifiers()).toList();
        } else {
            // Winners of the phase just finished, in that phase's tie order — which is itself
            // seeded best-to-worst, so index 0 is the best surviving seed.
            List<Fixture> played =
                    fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), current.getId());
            seeds = TieResolver.tiesOf(played).stream().map(TieResolver::winner).toList();
        }
```

This works because `tiesOf` orders by `tie_order`, so `seeds.get(0)` is the winner of the top pairing — the best surviving seed. `knockoutPairings` then re-pairs best against worst: with four semifinalists, index 0 plays index 3 and index 1 plays index 2, exactly as the spec requires.

Add a test that pins the re-pairing:

```java
    @Test
    void semifinalistsArePairedBestAgainstWorstSurvivingSeed() {
        // Given: QF winners are the 1st, 3rd, 6th and 8th seeds of the first phase
        phaseResolver.resolveIfPhaseComplete(save, lastQuarterFinalEvent);

        List<KnockoutTie> semis = TieResolver.tiesOf(
                fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, semiFinalPhaseId));

        // Best surviving (1st) meets worst surviving (8th); 3rd meets 6th
        assertThat(semis.get(0).higherSeedClubId()).isEqualTo(firstSeed);
        assertThat(semis.get(0).lowerSeedClubId()).isEqualTo(eighthSeed);
        assertThat(semis.get(1).higherSeedClubId()).isEqualTo(thirdSeed);
        assertThat(semis.get(1).lowerSeedClubId()).isEqualTo(sixthSeed);
    }
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*TieResolverTest*' --tests '*PhaseResolverTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/ \
        backend/src/test/java/br/com/carvoeiromanager/competition/TieResolverTest.java
git commit -m "feat(competition): tie resolution and knockout-to-knockout advancement"
```

---

### Task 10: Final classification, archive, and prize money

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/FinalClassification.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/FinalClassificationTest.java`

**Interfaces:**
- Consumes: `TieResolver`, `StandingRow`, `CompetitionPhase` (Tasks 5, 9)
- Produces: `FinalClassification.compute(List<StandingRow> firstPhaseTable, List<CompetitionPhase> knockoutPhases, Map<UUID, List<Fixture>> fixturesByPhase) -> List<UUID>` — club ids in final order, index 0 = champion

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void championThenRunnerUpThenSemifinalistsThenQuarterfinalists() {
        List<UUID> order = FinalClassification.compute(firstPhaseTable, knockoutPhases, fixturesByPhase);

        assertThat(order).hasSize(12);
        assertThat(order.get(0)).isEqualTo(champion);
        assertThat(order.get(1)).isEqualTo(runnerUp);
        assertThat(order.subList(2, 4)).containsExactlyInAnyOrderElementsOf(losingSemifinalists);
        assertThat(order.subList(4, 8)).containsExactlyInAnyOrderElementsOf(losingQuarterfinalists);
        assertThat(order.subList(8, 12)).containsExactlyElementsOf(nonQualifiersInTableOrder);
    }

    @Test
    void tiedGroupsBreakOnFirstPhasePosition() {
        List<UUID> order = FinalClassification.compute(firstPhaseTable, knockoutPhases, fixturesByPhase);

        int betterSemifinalist = order.indexOf(semifinalistWhoFinishedSecond);
        int worseSemifinalist = order.indexOf(semifinalistWhoFinishedSixth);
        assertThat(betterSemifinalist).isLessThan(worseSemifinalist);
    }

    @Test
    void aCompetitionWithNoKnockoutPhasesReturnsTheTableOrder() {
        List<UUID> order = FinalClassification.compute(serieBTable, List.of(), Map.of());

        assertThat(order).containsExactlyElementsOf(
                serieBTable.stream().map(StandingRow::clubId).toList());
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FinalClassificationTest*'
```

Expected: compilation failure.

- [ ] **Step 3: Implement `FinalClassification`**

`backend/src/main/java/br/com/carvoeiromanager/competition/FinalClassification.java`:

```java
package br.com.carvoeiromanager.competition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a competition's phase results into one ordering — the Brazilian
 * <em>classificação final</em>. Champion, runner-up, then each knockout round's losers as a
 * band ordered by first-phase position, then everyone who never qualified in table order.
 *
 * <p>A competition with no knockout phases returns its table order unchanged, so plain
 * leagues behave exactly as before.
 */
public final class FinalClassification {

    private FinalClassification() {
    }

    public static List<UUID> compute(List<StandingRow> firstPhaseTable,
                                     List<CompetitionPhase> knockoutPhases,
                                     Map<UUID, List<Fixture>> fixturesByPhase) {
        List<UUID> tableOrder = firstPhaseTable.stream().map(StandingRow::clubId).toList();
        if (knockoutPhases.isEmpty()) {
            return tableOrder;
        }
        // First-phase position, used to order every band internally.
        Comparator<UUID> byTablePosition = Comparator.comparingInt(tableOrder::indexOf);

        List<CompetitionPhase> ordered = knockoutPhases.stream()
                .sorted(Comparator.comparingInt(CompetitionPhase::getOrdinal)).toList();

        Set<UUID> placed = new LinkedHashSet<>();
        List<UUID> result = new ArrayList<>();

        // The last knockout phase is the final: its winner is 1st, its loser 2nd.
        CompetitionPhase last = ordered.get(ordered.size() - 1);
        List<KnockoutTie> finalTies = TieResolver.tiesOf(fixturesByPhase.get(last.getId()));
        KnockoutTie decider = finalTies.get(0);
        UUID champion = TieResolver.winner(decider);
        UUID runnerUp = champion.equals(decider.higherSeedClubId())
                ? decider.lowerSeedClubId() : decider.higherSeedClubId();
        result.add(champion);
        result.add(runnerUp);
        placed.add(champion);
        placed.add(runnerUp);

        // Earlier knockout rounds, latest first: each round's losers form the next band.
        for (int i = ordered.size() - 2; i >= 0; i--) {
            List<UUID> losers = new ArrayList<>();
            for (KnockoutTie tie : TieResolver.tiesOf(fixturesByPhase.get(ordered.get(i).getId()))) {
                UUID winner = TieResolver.winner(tie);
                UUID loser = winner.equals(tie.higherSeedClubId())
                        ? tie.lowerSeedClubId() : tie.higherSeedClubId();
                if (placed.add(loser)) {
                    losers.add(loser);
                }
            }
            losers.sort(byTablePosition);
            result.addAll(losers);
        }

        // Everyone who never reached the knockout, in first-phase order.
        tableOrder.stream().filter(id -> !placed.contains(id)).forEach(result::add);
        return List.copyOf(result);
    }
}
```

- [ ] **Step 4: Use it for the archive**

In `SeasonRolloverService`, where `Season.create(...)` is called, set `championClubId` to `order.get(0)` and `managedClubPosition` to `order.indexOf(managedClubId) + 1`. `SeasonStanding` keeps archiving the first-phase table — pass the first phase's id to `computeStandings`.

- [ ] **Step 5: Use it for prize money**

In `FinanceService.processSeasonEnd`, replace the standings-position lookup with the final classification, keeping the `financeCalculator.prizeMoney(position, clubCount)` call unchanged.

- [ ] **Step 6: Run the tests and the season-solvency test**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FinalClassification*' --tests '*Finance*' --tests '*Season*'
```

Expected: PASS. If season solvency shifts, it is because a cup run now pays differently — check the number is defensible before adjusting any constant.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/FinalClassification.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/FinalClassificationTest.java
git commit -m "feat(season): final classification drives archive champion and prize money"
```

---

### Task 11: Rollover carries phases; orphan club coverage

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/OrphanClubIT.java`

**Interfaces:**
- Consumes: `CompetitionPhaseRepository`, `SeasonCalendarGenerator` (Tasks 1, 4)
- Produces: no new public API

- [ ] **Step 1: Write the failing tests**

```java
    // SeasonRolloverIT
    @Test
    void rolloverKeepsPhasesAndRegeneratesOnlyFixturesAndEvents() {
        List<UUID> before = phaseRepository.findByCompetitionIdOrderByOrdinalAsc(catarinenseId)
                .stream().map(CompetitionPhase::getId).toList();

        rolloverService.rollover(saveGameId, userId);

        List<UUID> after = phaseRepository.findByCompetitionIdOrderByOrdinalAsc(catarinenseId)
                .stream().map(CompetitionPhase::getId).toList();
        assertThat(after).isEqualTo(before);

        // The new season's first phase is materialized again, the knockout reserved again
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, after.get(0))).hasSize(66);
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, after.get(1))).isEmpty();
    }

    // OrphanClubIT
    @Test
    void anOrphanClubIsCreatedWithItsSquadButNoFixtures() {
        UUID saveGameId = createCareer();

        Club her = clubRepository.findBySaveGameIdAndShortName(saveGameId, "HER").orElseThrow();
        assertThat(playerRepository.findByClubId(her.getId())).isNotEmpty();
        assertThat(fixtureRepository.findBySaveGameIdAndClubId(saveGameId, her.getId())).isEmpty();
    }

    @Test
    void aFullSeasonRunsToCompletionWithAnOrphanClubPresent() {
        UUID saveGameId = createCareer();

        advanceToSeasonEnd(saveGameId);

        // Finance, standings and rollover all ignore the orphan rather than failing on it
        assertThat(saveGameRepository.findById(saveGameId).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.FINISHED);
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonRolloverIT*' --tests '*OrphanClubIT*'
```

Expected: rollover fails (phases deleted or fixtures wrong); orphan tests may already pass — if so, they are still worth keeping as regression cover.

- [ ] **Step 3: Make rollover phase-safe**

In `SeasonRolloverService`, ensure the purge deletes fixtures and `season_event` rows but **not** `competition_phase` rows, then let `SeasonCalendarGenerator.generate` rebuild from the surviving phases. If a purge helper currently deletes "everything for the save", add an explicit exclusion and a comment explaining that phases are competition structure, not season state.

- [ ] **Step 4: Fix whatever the orphan tests surface**

Any path that assumes a club has fixtures — `ClubViewPage`'s next-fixture lookup, opponent analysis, per-matchday finance — must degrade to empty rather than throw. Fix each at the source; do not special-case `"HER"`.

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonRolloverIT*' --tests '*OrphanClubIT*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverIT.java \
        backend/src/test/java/br/com/carvoeiromanager/career/OrphanClubIT.java
git commit -m "feat(season): rollover preserves phases; orphan clubs are harmless"
```

---

### Task 12: API surface — phases, ties, phase-aware advance

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionController.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/PhaseResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/KnockoutTieResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/CompetitionSummary.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionControllerIT.java`

**Interfaces:**
- Consumes: `TieResolver.tiesOf`, `CompetitionPhaseRepository`, per-phase `StandingsService` (Tasks 5, 9)
- Produces:
  - `GET /v1/competitions/{competitionId}/phases?saveGameId=` → `List<PhaseResponse>`
  - `GET /v1/competitions/{competitionId}/ties?saveGameId=&phaseId=` → `List<KnockoutTieResponse>`
  - `GET /v1/standings?saveGameId=&competitionId=&phaseId=` — `phaseId` optional
  - `PhaseResponse(String id, int ordinal, String name, String kind, int legs, int rounds, Integer qualifiers, boolean complete)`
  - `KnockoutTieResponse(String tieId, String higherSeedClubId, String higherSeedClubName, String lowerSeedClubId, String lowerSeedClubName, Integer firstLegHomeGoals, Integer firstLegAwayGoals, Integer secondLegHomeGoals, Integer secondLegAwayGoals, Integer penaltyHome, Integer penaltyAway, String winnerClubId)`

**Wire-format warning:** the weather slice shipped a bug where the backend sent a pt-BR label (`"Chuva forte"`) and the frontend keyed on the enum name (`CHUVA_FORTE`), which no test caught. `kind` here must carry the **enum name** (`"ROUND_ROBIN"`, `"KNOCKOUT"`); `name` carries the pt-BR display string. Assert both explicitly.

- [ ] **Step 1: Write the failing controller test**

```java
    @Test
    void phasesEndpointReturnsEnumNamesNotLabels() throws Exception {
        mockMvc.perform(get("/v1/competitions/{id}/phases", catarinenseId)
                        .param("saveGameId", saveGameId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].kind").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$[0].name").value("Primeira Fase"))
                .andExpect(jsonPath("$[1].kind").value("KNOCKOUT"))
                .andExpect(jsonPath("$[1].name").value("Quartas de final"));
    }

    @Test
    void tiesEndpointReturnsAggregateAndWinner() throws Exception {
        mockMvc.perform(get("/v1/competitions/{id}/ties", catarinenseId)
                        .param("saveGameId", saveGameId.toString())
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].winnerClubId").exists())
                .andExpect(jsonPath("$[0].higherSeedClubName").exists());
    }

    @Test
    void standingsAcceptAnOptionalPhaseId() throws Exception {
        mockMvc.perform(get("/v1/standings")
                        .param("saveGameId", saveGameId.toString())
                        .param("competitionId", catarinenseId.toString())
                        .param("phaseId", firstPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].played").value(11));
    }
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*CompetitionControllerIT*'
```

Expected: 404s — the endpoints do not exist.

- [ ] **Step 3: Add the response records and endpoints**

```java
package br.com.carvoeiromanager.competition;

/** A competition phase as the frontend sees it. {@code kind} carries the ENUM NAME. */
public record PhaseResponse(
        String id,
        int ordinal,
        String name,
        String kind,
        int legs,
        int rounds,
        Integer qualifiers,
        boolean complete
) {}
```

```java
package br.com.carvoeiromanager.competition;

/**
 * One knockout tie. Goal fields are null until the corresponding leg is played;
 * {@code winnerClubId} is null until the tie is decided.
 */
public record KnockoutTieResponse(
        String tieId,
        int tieOrder,
        String higherSeedClubId,
        String higherSeedClubName,
        String lowerSeedClubId,
        String lowerSeedClubName,
        Integer firstLegHomeGoals,
        Integer firstLegAwayGoals,
        Integer secondLegHomeGoals,
        Integer secondLegAwayGoals,
        Integer penaltyHome,
        Integer penaltyAway,
        String winnerClubId
) {}
```

Add the two `@GetMapping`s to `CompetitionController`, following the controller's existing auth and `saveGameId` validation pattern. Build `kind` with `phase.getKind().name()`. A tie whose legs are not both played returns null goal fields and a null `winnerClubId` — catch `IllegalStateException` from `TieResolver.winner` and map it to null rather than letting it escape as a 500.

- [ ] **Step 4: Make the advance response phase-aware**

Add `phaseName` (nullable) to `AdvanceResponse` and populate it in `recordMatchRound` from the event's phase. The frontend uses it for "Quartas de final — Volta" instead of "Rodada 12".

- [ ] **Step 5: Write the failing news test**

Spec §7.5 requires phase transitions to generate news. `NewsItem.create(UUID saveGameId, int seq, LocalDate eventDate, String type, String payload)` is the factory; `type` is a String column.

```java
    @Test
    void qualifyingForTheKnockoutGeneratesNews() {
        advanceThroughFirstPhase(saveGameId);

        List<NewsItem> news = newsItemRepository.findBySaveGameId(saveGameId);
        assertThat(news).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("PHASE_QUALIFIED");
            assertThat(n.getPayload()).contains("Quartas de final");
        });
    }

    @Test
    void missingOutOnTheKnockoutGeneratesNews() {
        advanceThroughFirstPhaseFinishingNinth(saveGameId);

        assertThat(newsItemRepository.findBySaveGameId(saveGameId))
                .anySatisfy(n -> assertThat(n.getType()).isEqualTo("PHASE_ELIMINATED"));
    }

    @Test
    void theChampionIsAnnounced() {
        advanceToSeasonEnd(saveGameId);

        // "CHAMPION", not "COMPETITION_CHAMPION" — news_item.type is VARCHAR(16).
        assertThat(newsItemRepository.findBySaveGameId(saveGameId))
                .anySatisfy(n -> assertThat(n.getType()).isEqualTo("CHAMPION"));
    }
```

- [ ] **Step 6: Emit the news**

In `PhaseResolver`, after materializing a phase, emit one item per managed-club-relevant transition through `NewsService`:

- `PHASE_QUALIFIED` / `PHASE_ELIMINATED` when a round-robin phase completes — whichever applies to the managed club.
- `KNOCKOUT_DRAW` naming the managed club's opponent, when its tie is created.
- `CHAMPION` when the final's winner is known.

**`news_item.type` is `VARCHAR(16)`** — every type string above fits (`PHASE_ELIMINATED` is exactly 16). Do not use `COMPETITION_CHAMPION`; at 20 characters it overflows the column and fails at insert. Update the Step 5 test to assert `"CHAMPION"` accordingly.

Reuse `NewsService`'s existing sequence handling rather than assigning `seq` by hand.

- [ ] **Step 7: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ControllerIT*' --tests '*Advance*' --tests '*News*'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/competition/ \
        backend/src/main/java/br/com/carvoeiromanager/season/ \
        backend/src/main/java/br/com/carvoeiromanager/career/CompetitionSummary.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionControllerIT.java
git commit -m "feat(api): phases and ties endpoints, phase-aware advance, phase-transition news"
```

---

### Task 13: Frontend — phase-aware competition page

**Files:**
- Create: `frontend/src/features/competition/KnockoutBracketList.tsx`
- Create: `frontend/src/features/competition/KnockoutBracketList.test.tsx`
- Create: `frontend/src/features/competition/PhaseSelector.tsx`
- Modify: `frontend/src/features/competition/TablePage.tsx`
- Modify: `frontend/src/features/competition/TablePage.test.tsx`
- Modify: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`

**Interfaces:**
- Consumes: `GET /v1/competitions/{id}/phases`, `GET /v1/competitions/{id}/ties`, `GET /v1/standings?phaseId=` (Task 12)
- Produces: `Phase` and `KnockoutTie` TS types; `getPhases(saveGameId, competitionId)`, `getTies(saveGameId, competitionId, phaseId)`

- [ ] **Step 1: Add the API types**

In `frontend/src/api/types.ts`:

```typescript
export type PhaseKind = 'ROUND_ROBIN' | 'KNOCKOUT'

export type Phase = {
  id: string
  ordinal: number
  name: string
  kind: PhaseKind
  legs: number
  rounds: number
  qualifiers: number | null
  complete: boolean
}

export type KnockoutTie = {
  tieId: string
  tieOrder: number
  higherSeedClubId: string
  higherSeedClubName: string
  lowerSeedClubId: string
  lowerSeedClubName: string
  firstLegHomeGoals: number | null
  firstLegAwayGoals: number | null
  secondLegHomeGoals: number | null
  secondLegAwayGoals: number | null
  penaltyHome: number | null
  penaltyAway: number | null
  winnerClubId: string | null
}
```

`kind` is a string union of the **enum names** — matching the backend exactly. This is the seam the weather bug went through.

- [ ] **Step 2: Write the failing component test**

`frontend/src/features/competition/KnockoutBracketList.test.tsx`:

```typescript
describe('KnockoutBracketList', () => {
  it('renders each tie with both legs and the aggregate', () => {
    render(<KnockoutBracketList phaseName="Quartas de final" ties={[tie]} managedClubId="club-1" />)

    expect(screen.getByText('Quartas de final')).toBeInTheDocument()
    expect(screen.getByText('Criciúma')).toBeInTheDocument()
    expect(screen.getByText('Avaí')).toBeInTheDocument()
    expect(screen.getByText('3 x 2')).toBeInTheDocument()   // aggregate
  })

  it('shows the shootout score when a tie went to penalties', () => {
    render(<KnockoutBracketList phaseName="Final" ties={[penaltyTie]} managedClubId="club-1" />)

    expect(screen.getByText(/5 x 3 nos pênaltis/)).toBeInTheDocument()
  })

  it('renders an unplayed tie without scores and without crashing', () => {
    render(<KnockoutBracketList phaseName="Semifinal" ties={[unplayedTie]} managedClubId="club-1" />)

    expect(screen.getByText('Semifinal')).toBeInTheDocument()
    expect(screen.queryByText('x')).not.toBeInTheDocument()
  })

  it('highlights the managed club', () => {
    render(<KnockoutBracketList phaseName="Final" ties={[tie]} managedClubId="club-1" />)

    expect(screen.getByTestId('tie-club-club-1')).toHaveAttribute('data-managed', 'true')
  })
})
```

- [ ] **Step 3: Run to confirm failure**

```bash
cd frontend && npm test -- KnockoutBracketList
```

Expected: FAIL — the component does not exist.

- [ ] **Step 4: Build `KnockoutBracketList` and `PhaseSelector`**

`frontend/src/features/competition/KnockoutBracketList.tsx`:

```tsx
import { Box, Card, CardContent, Stack, Typography } from '@mui/material'
import type { KnockoutTie } from '../../api/types'

type Props = {
  phaseName: string
  ties: KnockoutTie[]
  managedClubId: string
}

/** Aggregate over both legs from the higher seed's perspective (it hosts leg 2). */
const aggregate = (tie: KnockoutTie): { higher: number; lower: number } | null => {
  const { firstLegHomeGoals: h1, firstLegAwayGoals: a1 } = tie
  const { secondLegHomeGoals: h2, secondLegAwayGoals: a2 } = tie
  if (h1 == null || a1 == null || h2 == null || a2 == null) return null
  // The higher seed is away in leg 1, home in leg 2.
  return { higher: a1 + h2, lower: h1 + a2 }
}

export const KnockoutBracketList = ({ phaseName, ties, managedClubId }: Props) => (
  <Stack spacing={1.5}>
    <Typography variant="h6">{phaseName}</Typography>
    {[...ties].sort((a, b) => a.tieOrder - b.tieOrder).map((tie) => {
      const agg = aggregate(tie)
      return (
        <Card key={tie.tieId} variant="outlined">
          <CardContent>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
              <Box>
                <Typography
                  data-testid={`tie-club-${tie.higherSeedClubId}`}
                  data-managed={String(tie.higherSeedClubId === managedClubId)}
                  fontWeight={tie.winnerClubId === tie.higherSeedClubId ? 700 : 400}
                >
                  {tie.higherSeedClubName}
                </Typography>
                <Typography
                  data-testid={`tie-club-${tie.lowerSeedClubId}`}
                  data-managed={String(tie.lowerSeedClubId === managedClubId)}
                  fontWeight={tie.winnerClubId === tie.lowerSeedClubId ? 700 : 400}
                >
                  {tie.lowerSeedClubName}
                </Typography>
              </Box>
              <Box textAlign={{ xs: 'left', sm: 'right' }}>
                {agg && <Typography>{`${agg.higher} x ${agg.lower}`}</Typography>}
                {tie.penaltyHome != null && tie.penaltyAway != null && (
                  <Typography variant="body2" color="text.secondary">
                    {`${tie.penaltyHome} x ${tie.penaltyAway} nos pênaltis`}
                  </Typography>
                )}
              </Box>
            </Stack>
          </CardContent>
        </Card>
      )
    })}
  </Stack>
)
```

`frontend/src/features/competition/PhaseSelector.tsx`:

```tsx
import { Tab, Tabs } from '@mui/material'
import type { Phase } from '../../api/types'

type Props = {
  phases: Phase[]
  selectedPhaseId: string
  onSelect: (phaseId: string) => void
}

export const PhaseSelector = ({ phases, selectedPhaseId, onSelect }: Props) => (
  <Tabs
    value={selectedPhaseId}
    onChange={(_, value: string) => onSelect(value)}
    variant="scrollable"
    scrollButtons="auto"
  >
    {[...phases]
      .sort((a, b) => a.ordinal - b.ordinal)
      .map((phase) => (
        <Tab key={phase.id} value={phase.id} label={phase.name} />
      ))}
  </Tabs>
)
```

Note the penalties line reads `5 x 3 nos pênaltis` — matching the test in Step 2 exactly. If you change the wording, change the test with it.

- [ ] **Step 5: Wire `TablePage` to switch on phase kind**

Fetch the phases alongside the standings. Render `PhaseSelector`; when the selected phase is `ROUND_ROBIN` render the existing table (fetching standings with `phaseId`), when `KNOCKOUT` render `KnockoutBracketList` from the ties endpoint. A competition with **no phases** renders exactly as today — no selector, no phase param — so Série B is visually untouched.

- [ ] **Step 6: Run the frontend tests and typecheck**

```bash
cd frontend && npm test -- competition && npx tsc -b
```

Expected: PASS, no type errors. (`tsc --noEmit` is a no-op in this repo — use `tsc -b`.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/competition/ frontend/src/api/
git commit -m "feat(front): phase-aware competition page with knockout tie list"
```

---

### Task 14: Full verification

**Files:**
- Create: `backend/src/test/java/br/com/carvoeiromanager/season/PhasedSeasonIT.java`
- Modify: none

**Interfaces:**
- Consumes: everything above
- Produces: no production code

This task writes no features. It proves the slice end to end, including the seams no test suite covers.

- [ ] **Step 1: Write the full-season integration test**

`backend/src/test/java/br/com/carvoeiromanager/season/PhasedSeasonIT.java`:

```java
    @Test
    void aFullCatarinenseSeasonRunsFromFirstPhaseToChampion() {
        UUID saveGameId = createCareer();

        // Advance until the save is FINISHED
        while (saveGameRepository.findById(saveGameId).orElseThrow().getStatus() != SaveGameStatus.FINISHED) {
            advanceService.advance(saveGameId, userId);
        }

        // 66 first-phase fixtures + 8 QF + 4 SF + 2 F = 80 Catarinense fixtures, all played
        List<Fixture> catarinense = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinenseId);
        assertThat(catarinense).hasSize(80);
        assertThat(catarinense).allSatisfy(f -> assertThat(f.isPlayed()).isTrue());

        // Every knockout tie has a winner and no tie is level without penalties
        List<CompetitionPhase> phases =
                phaseRepository.findByCompetitionIdOrderByOrdinalAsc(catarinenseId);
        for (CompetitionPhase phase : phases.subList(1, 4)) {
            List<KnockoutTie> ties = TieResolver.tiesOf(
                    fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, phase.getId()));
            assertThat(ties).isNotEmpty();
            assertThat(ties).allSatisfy(t -> assertThat(TieResolver.winner(t)).isNotNull());
        }

        // The archive records the knockout winner as champion
        Season archived = seasonRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinenseId).orElseThrow();
        UUID finalWinner = TieResolver.winner(TieResolver.tiesOf(
                fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, phases.get(3).getId())).get(0));
        assertThat(archived.getChampionClubId()).isEqualTo(finalWinner);

        // Prize paid exactly once
        assertThat(financeTransactionRepository.findBySaveGameId(saveGameId).stream()
                .filter(t -> t.getType() == FinanceTransactionType.PRIZE)
                .filter(t -> t.getDescription().contains("Catarinense")).count()).isEqualTo(1);
    }

    @Test
    void serieBIsUnaffectedByThePhaseEngine() {
        UUID saveGameId = createCareer();

        List<Fixture> serieB = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, serieBId);
        assertThat(serieB).hasSize(380);
        assertThat(serieB).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
        assertThat(serieB).allSatisfy(f -> assertThat(f.getTieId()).isNull());
    }
```

- [ ] **Step 2: Run it**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*PhasedSeasonIT*'
```

Expected: PASS.

- [ ] **Step 3: Run everything**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
cd ../frontend && npm test && npx tsc -b
```

Expected: PASS on both. Report the actual pass/fail counts — do not claim success without the output.

- [ ] **Step 4: Migration check against the real dev database**

The dev database `carvoeiro-manager-db` (Postgres 16, port 5435, user/db `carvoeiro-manager`) sits at an older Flyway version with real careers in it.

**Back it up first — this migrates in place:**

```bash
docker exec carvoeiro-manager-db pg_dump -U carvoeiro-manager carvoeiro-manager > /tmp/carvoeiro-pre-v47.sql
```

Then boot the backend via the `backend` entry in `.claude/launch.json` and confirm:
- V47 applies cleanly on top of whatever version the database is at.
- Existing careers still load, still show their Catarinense, and still advance.
- Those careers' Catarinense has **no** phase rows and keeps its original round count — they are not backfilled.

- [ ] **Step 5: Live end-to-end click-through**

Start both dev servers from `.claude/launch.json` (`backend`, `frontend`). Create a **new** career and verify in the browser:

1. The Catarinense table shows **12** clubs and the first phase runs 11 rounds.
2. When the first phase ends, the quarterfinals appear with 4 ties, top seed against 8th.
3. The competition page switches to the tie list, and the phase selector can return to the first-phase table.
4. Play a knockout tie **live** through to a level aggregate — confirm extra time starts, the sixth substitution becomes available, and the shootout plays out kick by kick.
5. The champion is crowned and the season archive shows the knockout winner, not the table leader.

This step is mandatory. `LiveMatchEngine` is the riskiest surface in the slice, and a wire-format bug in the weather work passed 574 frontend tests and the whole backend suite while throwing on first render. Test suites do not cover this seam.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/season/PhasedSeasonIT.java
git commit -m "test(competition): full phased season, Série B regression, live verification"
```

---

## Verification Summary

Before declaring the slice done, all of these must be true **with output you have actually seen**:

- [ ] `./gradlew test` passes in full
- [ ] `MatchCalibrationTest` passes with **no** constant retuning
- [ ] The league-determinism regression test passes (Task 7, Step 1)
- [ ] `npm test` and `npx tsc -b` pass
- [ ] V47 applied to the real dev database with existing careers intact and unbackfilled
- [ ] A knockout tie played live through extra time and penalties in the browser
- [ ] Série B still generates 380 fixtures with null `phase_id`
