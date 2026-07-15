# Staff System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager hire one Assistant Manager, one Coach ("Preparador Físico"), one
Scout ("Olheiro"), and one Physio ("Fisioterapeuta"), each a small passive effect on an
existing system, funded by a monthly wage; AI clubs get a fixed baseline effect with no roster.

**Architecture:** A new `Staff` entity (mirrors `Player`'s `clubId`-FK shape) persists only the
managed club's hires — AI clubs never get a row. A `StaffCandidateGenerator` deterministically
rolls 3 on-demand candidates per role (not persisted). A single `StaffEffectsService` facade is
the only place that turns a hired `Staff` row (or the fixed AI baseline) into a numeric effect;
every consumer (`TrainingService`, the injury-chance call sites, `ContractService`,
`FinanceService`) calls through it rather than touching `Staff`/`StaffRepository` directly.

**Tech Stack:** Java 21 / Spring Boot / PostgreSQL / Flyway (backend), React 19 / TypeScript /
MUI / React Query (frontend). JUnit 5 + AssertJ + Testcontainers (backend tests), Vitest + React
Testing Library (frontend tests).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-15-staff-system-design.md` — every value below is copied
  verbatim from it, with two refinements discovered during planning (both noted inline where
  they apply, with the reasoning).
- Four roles: `ASSISTENTE` ("Auxiliar Técnico"), `PREPARADOR_FISICO` ("Preparador Físico"),
  `OLHEIRO` ("Olheiro"), `FISIOTERAPEUTA` ("Fisioterapeuta"). **Never label a role with the bare
  word "Técnico"** — it already means the user's own manager persona
  (`DashboardPage.tsx:79`) and the "Técnico" attribute-group name (`PlayerDetails.tsx:23`).
- Three quality tiers: `REGULAR`, `BOM`, `EXCELENTE`.
- Effect table (Regular / Bom / Excelente):
  - `PREPARADOR_FISICO` → training bonus: `+0 / +1 / +2` (added to `TrainingService`'s existing
    Leve/Normal/Intensa bonus)
  - `FISIOTERAPEUTA` → injury-risk multiplier: `1.0 / 0.85 / 0.7`
  - `OLHEIRO` → wage-demand multiplier: `1.0 / 0.95 / 0.9`
  - `ASSISTENTE` → sponsorship multiplier: `1.0 / 1.05 / 1.1`
- AI-controlled clubs (every club except the save's managed club) never get a persisted `Staff`
  row and never hire; they always receive the fixed baseline — equivalent to `BOM` quality in
  every role.
- One slot per role: hiring a candidate for a role that already has a holder replaces it.
- Candidate wage by quality (fixed, not randomized): `REGULAR = R$20.000`, `BOM = R$45.000`,
  `EXCELENTE = R$80.000` monthly.
- New migration: `V27__staff.sql` (the last migration is `V26`).
- **Refinement 1 (discovered during planning):** the spec's "Olheiro... free-agent signings
  only, not the full transfer-negotiation engine" is implemented as "everywhere
  `ContractService.applyTerms` is called" — that private method is shared verbatim by both
  `signFreeAgent` and `renewContract` (`ContractService.java:93-105`); splitting it to
  discriminate the two callers would duplicate the validation logic it exists to share. The
  transfer-negotiation engine (`TransferNegotiator`, incoming/outgoing club offers) stays
  untouched, matching the spec's actual named exclusion.
- **Refinement 2 (discovered during planning):** `StaffEffectsService`'s four methods take
  `(UUID clubId, boolean managed)`, not `(UUID clubId, UUID managedClubId)` as an earlier draft
  of this plan assumed. Every call site already computes (or trivially can compute) a `managed`
  boolean before calling — `SeasonRolloverService` and `MatchOutcomeApplier.applyInjuries`
  already have one, `LiveMatchEngine` can derive one from `LiveMatchConfig.aiHome()` (see Task
  6), and `ContractService`/`FinanceService` compute one inline from the already-resolved
  `managedClubId`. This avoids needing to add a `managedClubId` field anywhere it doesn't already
  exist (`LiveMatchConfig` in particular has no such field today).
- Backend verification: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
  from `backend/`. Frontend verification: `cd frontend && npx tsc --noEmit && npx vitest run`.

---

## Task 1: Data layer — migration, `StaffRole`/`StaffQuality` enums, `Staff` entity, `StaffRepository`

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__staff.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRole.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffQuality.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/Staff.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRepository.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffTest.java`

**Interfaces:**
- Produces: `StaffRole { ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA }`,
  `StaffQuality { REGULAR, BOM, EXCELENTE }`. `Staff.create(UUID saveGameId, UUID clubId,
  StaffRole role, StaffQuality quality, String name, BigDecimal wage): Staff` plus getters
  `getId/getSaveGameId/getClubId/getRole/getQuality/getName/getWage`.
  `StaffRepository extends JpaRepository<Staff, UUID>` with `findByClubId(UUID clubId):
  List<Staff>`, `findByClubIdAndRole(UUID clubId, StaffRole role): Optional<Staff>`,
  `findBySaveGameId(UUID saveGameId): List<Staff>`, `deleteByClubIdAndRole(UUID clubId, StaffRole
  role): void`. Consumed by every later task.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/staff/StaffTest.java`:

```java
package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffTest {

    @Test
    void createBuildsAStaffMemberWithEveryFieldSet() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        Staff s = Staff.create(saveGameId, clubId, StaffRole.PREPARADOR_FISICO,
                StaffQuality.BOM, "Carlos Andrade", new BigDecimal("45000.00"));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(s.getClubId()).isEqualTo(clubId);
        assertThat(s.getRole()).isEqualTo(StaffRole.PREPARADOR_FISICO);
        assertThat(s.getQuality()).isEqualTo(StaffQuality.BOM);
        assertThat(s.getName()).isEqualTo("Carlos Andrade");
        assertThat(s.getWage()).isEqualByComparingTo("45000.00");
    }

    @Test
    void twoStaffCreatedSeparatelyHaveDifferentIds() {
        Staff a = Staff.create(UUID.randomUUID(), UUID.randomUUID(), StaffRole.OLHEIRO,
                StaffQuality.REGULAR, "A", BigDecimal.TEN);
        Staff b = Staff.create(UUID.randomUUID(), UUID.randomUUID(), StaffRole.OLHEIRO,
                StaffQuality.REGULAR, "B", BigDecimal.TEN);

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffTest"`
Expected: FAIL to compile — the `staff` package doesn't exist yet.

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V27__staff.sql`:

```sql
CREATE TABLE staff (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL,
    quality       VARCHAR(10) NOT NULL,
    name          VARCHAR(80) NOT NULL,
    wage          NUMERIC(14,2) NOT NULL,
    UNIQUE (club_id, role)
);

CREATE INDEX idx_staff_club ON staff(club_id);
CREATE INDEX idx_staff_save_game ON staff(save_game_id);
```

The `UNIQUE (club_id, role)` constraint enforces "one slot per role" at the database level, not
just in application code.

- [ ] **Step 4: Create the two enums**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRole.java`:

```java
package br.com.carvoeiromanager.staff;

/** The four hirable technical-staff roles. Never labeled bare "Técnico" in the UI (see plan). */
public enum StaffRole {
    ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffQuality.java`:

```java
package br.com.carvoeiromanager.staff;

/** How good a hired staff member is; drives both their wage and their effect size. */
public enum StaffQuality {
    REGULAR, BOM, EXCELENTE
}
```

- [ ] **Step 5: Create the `Staff` entity**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/Staff.java`:

```java
package br.com.carvoeiromanager.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A hired technical-staff member. Only ever persisted for the save's managed club — AI clubs
 * never hire and are handled entirely by {@code StaffEffectsService}'s fixed baseline.
 */
@Entity
@Table(name = "staff")
public class Staff {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality", nullable = false, length = 10)
    private StaffQuality quality;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "wage", nullable = false)
    private BigDecimal wage;

    protected Staff() {
        // for JPA
    }

    private Staff(UUID id, UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                  String name, BigDecimal wage) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.role = role;
        this.quality = quality;
        this.name = name;
        this.wage = wage;
    }

    public static Staff create(UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                               String name, BigDecimal wage) {
        return new Staff(UUID.randomUUID(), saveGameId, clubId, role, quality, name, wage);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public StaffRole getRole() { return role; }
    public StaffQuality getQuality() { return quality; }
    public String getName() { return name; }
    public BigDecimal getWage() { return wage; }
}
```

- [ ] **Step 6: Create `StaffRepository`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRepository.java`:

```java
package br.com.carvoeiromanager.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    List<Staff> findByClubId(UUID clubId);
    Optional<Staff> findByClubIdAndRole(UUID clubId, StaffRole role);
    List<Staff> findBySaveGameId(UUID saveGameId);
    void deleteByClubIdAndRole(UUID clubId, StaffRole role);
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffTest"`
Expected: PASS (2 tests)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V27__staff.sql \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffRole.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffQuality.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/Staff.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffRepository.java \
        backend/src/test/java/br/com/carvoeiromanager/staff/StaffTest.java
git commit -m "feat(staff): add Staff entity, role/quality enums, and repository"
```

---

## Task 2: `StaffCandidateGenerator`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidate.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java`

**Interfaces:**
- Consumes: `br.com.carvoeiromanager.seed.SeedLoader.loadNewgenNames(): NewgenNames` (existing,
  `NewgenNames(List<String> firstNames, List<String> lastNames)`), `StaffRole`/`StaffQuality`
  (Task 1).
- Produces: `record StaffCandidate(String name, StaffQuality quality, BigDecimal wage)`.
  `StaffCandidateGenerator.generate(UUID saveGameId, UUID clubId, StaffRole role, LocalDate
  onDate, int count): List<StaffCandidate>`. Consumed by Task 4.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java`:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffCandidateGeneratorTest {

    private final StaffCandidateGenerator generator = new StaffCandidateGenerator(new SeedLoader());

    @Test
    void generatesTheRequestedNumberOfCandidatesWithNonBlankNamesAndPositiveWages() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 3);

        assertThat(candidates).hasSize(3);
        assertThat(candidates).allSatisfy(c -> {
            assertThat(c.name()).isNotBlank();
            assertThat(c.quality()).isNotNull();
            assertThat(c.wage().signum()).isPositive();
        });
    }

    @Test
    void isDeterministicForTheSameInputs() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 5, 10);

        List<StaffCandidate> first = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);
        List<StaffCandidate> second = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differsWhenTheDateAdvances() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        List<StaffCandidate> day1 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 1), 3);
        List<StaffCandidate> day2 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 2), 3);

        assertThat(day2).isNotEqualTo(day1);
    }

    @Test
    void differsAcrossRolesForTheSameClubAndDate() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 3, 1);

        List<StaffCandidate> coach = generator.generate(saveGameId, clubId, StaffRole.PREPARADOR_FISICO, onDate, 3);
        List<StaffCandidate> scout = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);

        assertThat(scout).isNotEqualTo(coach);
    }

    @Test
    void wageMatchesTheFixedTableForEachQualityTier() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.FISIOTERAPEUTA, LocalDate.of(2026, 3, 1), 20);

        assertThat(candidates).allSatisfy(c -> {
            BigDecimalWage:
            switch (c.quality()) {
                case REGULAR -> assertThat(c.wage()).isEqualByComparingTo("20000.00");
                case BOM -> assertThat(c.wage()).isEqualByComparingTo("45000.00");
                case EXCELENTE -> assertThat(c.wage()).isEqualByComparingTo("80000.00");
            }
        });
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffCandidateGeneratorTest"`
Expected: FAIL to compile — `StaffCandidate`/`StaffCandidateGenerator` don't exist yet.

- [ ] **Step 3: Create `StaffCandidate`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidate.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** One rolled, not-yet-hired staff candidate — never persisted. */
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage) {
}
```

- [ ] **Step 4: Create `StaffCandidateGenerator`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Rolls staff candidates on demand — never persisted, unlike {@code FreeAgentGenerator}'s shared
 * pool. Deterministic for a given (saveGameId, clubId, role, onDate): the offer is stable within
 * a game-day and changes only as the season advances, so re-browsing the same role on the same
 * day always shows the same three candidates.
 */
@Component
public class StaffCandidateGenerator {

    private final List<String> firstNames;
    private final List<String> lastNames;

    public StaffCandidateGenerator(SeedLoader seedLoader) {
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public List<StaffCandidate> generate(UUID saveGameId, UUID clubId, StaffRole role,
                                         LocalDate onDate, int count) {
        long seed = saveGameId.getMostSignificantBits()
                ^ clubId.getMostSignificantBits()
                ^ role.ordinal()
                ^ onDate.toEpochDay();
        Random rng = new Random(seed);

        List<StaffCandidate> candidates = new ArrayList<>();
        StaffQuality[] tiers = StaffQuality.values();
        for (int i = 0; i < count; i++) {
            String name = firstNames.get(rng.nextInt(firstNames.size()))
                    + " " + lastNames.get(rng.nextInt(lastNames.size()));
            StaffQuality quality = tiers[rng.nextInt(tiers.length)];
            candidates.add(new StaffCandidate(name, quality, wageFor(quality)));
        }
        return candidates;
    }

    private BigDecimal wageFor(StaffQuality quality) {
        return switch (quality) {
            case REGULAR -> new BigDecimal("20000.00");
            case BOM -> new BigDecimal("45000.00");
            case EXCELENTE -> new BigDecimal("80000.00");
        };
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffCandidateGeneratorTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidate.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java \
        backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java
git commit -m "feat(staff): add StaffCandidateGenerator for on-demand hiring candidates"
```

---

## Task 3: `StaffEffectsService`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java`

**Interfaces:**
- Consumes: `StaffRepository.findByClubIdAndRole(UUID, StaffRole): Optional<Staff>` (Task 1).
- Produces: `StaffEffectsService.trainingBonusFor(UUID clubId, boolean managed): int`,
  `.injuryRiskMultiplierFor(UUID clubId, boolean managed): double`,
  `.wageDemandMultiplierFor(UUID clubId, boolean managed): double`,
  `.sponsorshipMultiplierFor(UUID clubId, boolean managed): double`. Consumed by Tasks 5, 6, 7.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java`:

```java
package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class StaffEffectsServiceTest {

    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final StaffEffectsService effects = new StaffEffectsService(staffRepository);

    private Staff staff(StaffRole role, StaffQuality quality) {
        return Staff.create(UUID.randomUUID(), UUID.randomUUID(), role, quality, "Fulano", BigDecimal.TEN);
    }

    @Test
    void trainingBonusIsZeroWhenManagedClubHasNoCoachHired() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.empty());

        assertThat(effects.trainingBonusFor(clubId, true)).isZero();
    }

    @Test
    void trainingBonusMatchesTheHiredCoachsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staff(StaffRole.PREPARADOR_FISICO, StaffQuality.EXCELENTE)));

        assertThat(effects.trainingBonusFor(clubId, true)).isEqualTo(2);
    }

    @Test
    void trainingBonusIsTheFixedAiBaselineForANonManagedClubRegardlessOfRepository() {
        UUID clubId = UUID.randomUUID();

        assertThat(effects.trainingBonusFor(clubId, false)).isEqualTo(1);
        Mockito.verifyNoInteractions(staffRepository);
    }

    @Test
    void injuryRiskMultiplierMatchesTheHiredPhysiosQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staff(StaffRole.FISIOTERAPEUTA, StaffQuality.BOM)));

        assertThat(effects.injuryRiskMultiplierFor(clubId, true)).isEqualTo(0.85);
    }

    @Test
    void injuryRiskMultiplierIsNeutralWhenManagedClubHasNoPhysioHired() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        assertThat(effects.injuryRiskMultiplierFor(clubId, true)).isEqualTo(1.0);
    }

    @Test
    void injuryRiskMultiplierIsTheFixedAiBaselineForANonManagedClub() {
        assertThat(effects.injuryRiskMultiplierFor(UUID.randomUUID(), false)).isEqualTo(0.85);
    }

    @Test
    void wageDemandMultiplierMatchesTheHiredScoutsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staff(StaffRole.OLHEIRO, StaffQuality.EXCELENTE)));

        assertThat(effects.wageDemandMultiplierFor(clubId, true)).isEqualTo(0.9);
    }

    @Test
    void sponsorshipMultiplierMatchesTheHiredAssistantsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staff(StaffRole.ASSISTENTE, StaffQuality.BOM)));

        assertThat(effects.sponsorshipMultiplierFor(clubId, true)).isEqualTo(1.05);
    }

    @Test
    void sponsorshipMultiplierIsTheFixedAiBaselineForANonManagedClub() {
        assertThat(effects.sponsorshipMultiplierFor(UUID.randomUUID(), false)).isEqualTo(1.05);
    }
}
```

Note on `Mockito`: this test uses `Mockito.mock(...)` directly (no `@SpringBootTest`, no
`@MockBean`/`@MockitoBean`) — a plain unit test against a hand-built mock, no Spring context.
`mockito-core` is already a transitive dependency of `spring-boot-starter-test` (already used by
this project's `@SpringBootTest` test classes), so no new build dependency is needed.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffEffectsServiceTest"`
Expected: FAIL to compile — `StaffEffectsService` doesn't exist yet.

- [ ] **Step 3: Implement `StaffEffectsService`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java`:

```java
package br.com.carvoeiromanager.staff;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The single place that turns a hired {@link Staff} row into a numeric effect. Every consumer
 * (training, injury risk, wage-demand discounts, sponsorship) calls through here instead of
 * touching {@link StaffRepository} directly. Non-managed (AI-controlled) clubs never have a
 * {@link Staff} row and always receive the fixed baseline, equivalent to {@link StaffQuality#BOM}
 * in every role — mirrors {@code TrainingService.heuristicFocusFor}'s no-roster AI-parity shape.
 */
@Service
public class StaffEffectsService {

    private final StaffRepository staffRepository;

    public StaffEffectsService(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    public int trainingBonusFor(UUID clubId, boolean managed) {
        return trainingBonus(qualityFor(clubId, managed, StaffRole.PREPARADOR_FISICO));
    }

    public double injuryRiskMultiplierFor(UUID clubId, boolean managed) {
        return injuryRiskMultiplier(qualityFor(clubId, managed, StaffRole.FISIOTERAPEUTA));
    }

    public double wageDemandMultiplierFor(UUID clubId, boolean managed) {
        return wageDemandMultiplier(qualityFor(clubId, managed, StaffRole.OLHEIRO));
    }

    public double sponsorshipMultiplierFor(UUID clubId, boolean managed) {
        return sponsorshipMultiplier(qualityFor(clubId, managed, StaffRole.ASSISTENTE));
    }

    /**
     * Resolves the effective quality for a role: the managed club's hired staff (or {@code null}
     * if none), or the fixed AI baseline ({@link StaffQuality#BOM}) for every other club.
     */
    private StaffQuality qualityFor(UUID clubId, boolean managed, StaffRole role) {
        if (!managed) {
            return StaffQuality.BOM;
        }
        Optional<Staff> hired = staffRepository.findByClubIdAndRole(clubId, role);
        return hired.map(Staff::getQuality).orElse(null);
    }

    private int trainingBonus(StaffQuality quality) {
        if (quality == null) {
            return 0;
        }
        return switch (quality) {
            case REGULAR -> 0;
            case BOM -> 1;
            case EXCELENTE -> 2;
        };
    }

    private double injuryRiskMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 0.85;
            case EXCELENTE -> 0.7;
        };
    }

    private double wageDemandMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 0.95;
            case EXCELENTE -> 0.9;
        };
    }

    private double sponsorshipMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 1.05;
            case EXCELENTE -> 1.1;
        };
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffEffectsServiceTest"`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java \
        backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java
git commit -m "feat(staff): add StaffEffectsService as the single quality-to-effect facade"
```

---

## Task 4: `StaffService` (roster + hiring), `StaffController`, DTOs

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRosterResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/HireStaffRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java`

**Interfaces:**
- Consumes: `StaffRepository` (Task 1), `StaffCandidateGenerator.generate(...)` (Task 2),
  `SaveGameService.requireOwned(UUID, UUID): SaveGame` (existing, same pattern as
  `ContractService`/`LineupService`), `ClubRepository.findById` (existing).
- Produces: `StaffService.getRoster(UUID saveGameId, UUID clubId, UUID userId):
  StaffRosterResponse`, `.getCandidates(UUID saveGameId, UUID clubId, StaffRole role, UUID
  userId): List<StaffCandidateResponse>`, `.hire(UUID saveGameId, UUID clubId, StaffRole role,
  int candidateIndex, UUID userId): StaffRosterResponse`. Endpoints: `GET
  /api/v1/save-games/{saveGameId}/clubs/{clubId}/staff`, `GET .../staff/candidates?role={role}`,
  `PUT .../staff/{role}`. Not consumed by any later backend task; consumed by Task 8 (frontend).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java`:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class StaffServiceIT {

    @Autowired StaffService staffService;
    @Autowired StaffRepository staffRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("staff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void freshCareerHasAnEmptyRoster() {
        Career c = createCareer();

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        assertThat(roster.assistente()).isNull();
        assertThat(roster.preparadorFisico()).isNull();
        assertThat(roster.olheiro()).isNull();
        assertThat(roster.fisioterapeuta()).isNull();
    }

    @Test
    void candidatesForARoleAreThreeAndStableWithinTheSameCall() {
        Career c = createCareer();

        List<StaffCandidateResponse> first = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());
        List<StaffCandidateResponse> second = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());

        assertThat(first).hasSize(3);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void hiringACandidatePersistsThemInTheRoster() {
        Career c = createCareer();
        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId());

        StaffRosterResponse roster = staffService.hire(
                c.id(), c.clubId(), StaffRole.OLHEIRO, 1, c.userId());

        assertThat(roster.olheiro()).isNotNull();
        assertThat(roster.olheiro().name()).isEqualTo(candidates.get(1).name());
        assertThat(roster.olheiro().quality()).isEqualTo(candidates.get(1).quality());
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO)).isPresent();
    }

    @Test
    void hiringANewCandidateForAnAlreadyFilledRoleReplacesTheHolder() {
        Career c = createCareer();
        staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 0, c.userId());

        StaffRosterResponse roster = staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 2, c.userId());

        assertThat(staffRepository.findByClubId(c.clubId())).hasSize(1);
        assertThat(roster.assistente()).isNotNull();
    }

    @Test
    void hiringAnOutOfRangeCandidateIndexIsRejected() {
        Career c = createCareer();

        assertThatThrownBy(() -> staffService.hire(c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA, 3, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffServiceIT"`
Expected: FAIL to compile — `StaffService`, `StaffRosterResponse`, `StaffCandidateResponse`
don't exist yet.

- [ ] **Step 3: Create the response/request DTOs**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffResponse.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

public record StaffResponse(String name, String quality, BigDecimal wage) {
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRosterResponse.java`:

```java
package br.com.carvoeiromanager.staff;

/** The club's current roster, one slot per role — null means the role is unfilled. */
public record StaffRosterResponse(StaffResponse assistente, StaffResponse preparadorFisico,
                                  StaffResponse olheiro, StaffResponse fisioterapeuta) {
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateResponse.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** One of the 3 offered-but-not-yet-hired candidates for a role, with its index for hiring. */
public record StaffCandidateResponse(int index, String name, String quality, BigDecimal wage) {
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/staff/HireStaffRequest.java`:

```java
package br.com.carvoeiromanager.staff;

import jakarta.validation.constraints.NotNull;

public record HireStaffRequest(@NotNull Integer candidateIndex) {
}
```

- [ ] **Step 4: Implement `StaffService`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java`:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StaffService {

    private static final int CANDIDATE_COUNT = 3;

    private final SaveGameService saveGameService;
    private final StaffRepository staffRepository;
    private final StaffCandidateGenerator staffCandidateGenerator;

    public StaffService(SaveGameService saveGameService, StaffRepository staffRepository,
                        StaffCandidateGenerator staffCandidateGenerator) {
        this.saveGameService = saveGameService;
        this.staffRepository = staffRepository;
        this.staffCandidateGenerator = staffCandidateGenerator;
    }

    public StaffRosterResponse getRoster(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));
        return toRosterResponse(byRole);
    }

    public List<StaffCandidateResponse> getCandidates(UUID saveGameId, UUID clubId, StaffRole role,
                                                       UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT);
        return toCandidateResponses(candidates);
    }

    @Transactional
    public StaffRosterResponse hire(UUID saveGameId, UUID clubId, StaffRole role, int candidateIndex,
                                    UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT);
        if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "candidateIndex must be between 0 and " + (candidates.size() - 1));
        }
        StaffCandidate chosen = candidates.get(candidateIndex);

        staffRepository.deleteByClubIdAndRole(clubId, role);
        staffRepository.save(Staff.create(saveGameId, clubId, role, chosen.quality(),
                chosen.name(), chosen.wage()));

        return getRoster(saveGameId, clubId, userId);
    }

    private StaffRosterResponse toRosterResponse(Map<StaffRole, Staff> byRole) {
        return new StaffRosterResponse(
                toStaffResponse(byRole.get(StaffRole.ASSISTENTE)),
                toStaffResponse(byRole.get(StaffRole.PREPARADOR_FISICO)),
                toStaffResponse(byRole.get(StaffRole.OLHEIRO)),
                toStaffResponse(byRole.get(StaffRole.FISIOTERAPEUTA)));
    }

    private StaffResponse toStaffResponse(Staff staff) {
        return staff == null ? null
                : new StaffResponse(staff.getName(), staff.getQuality().name(), staff.getWage());
    }

    private List<StaffCandidateResponse> toCandidateResponses(List<StaffCandidate> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(i -> {
                    StaffCandidate c = candidates.get(i);
                    return new StaffCandidateResponse(i, c.name(), c.quality().name(), c.wage());
                })
                .toList();
    }
}
```

Note: `getCandidates`/`hire` re-derive the same deterministic candidates rather than trusting any
candidate data the client might submit — `HireStaffRequest` only carries `candidateIndex`, never
a name/quality/wage, so a client cannot fabricate an arbitrary high-quality hire.

- [ ] **Step 5: Implement `StaffController`**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/StaffController.java`:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ResponseEntity<StaffRosterResponse> getRoster(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.getRoster(saveGameId, clubId, userId));
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<StaffCandidateResponse>> getCandidates(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId,
            @RequestParam StaffRole role, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.getCandidates(saveGameId, clubId, role, userId));
    }

    @PutMapping("/{role}")
    public ResponseEntity<StaffRosterResponse> hire(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @PathVariable StaffRole role,
            @Valid @RequestBody HireStaffRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.hire(
                saveGameId, clubId, role, request.candidateIndex(), userId));
    }
}
```

An unknown `role` value in the query string or path (e.g. `?role=FOO`) fails Spring's own
`@RequestParam`/`@PathVariable` enum binding before reaching `StaffController`'s body, returning
a 400 automatically — no new `ErrorCode` is needed for that case.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffServiceIT"`
Expected: PASS (5 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff/StaffResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffRosterResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/HireStaffRequest.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java \
        backend/src/main/java/br/com/carvoeiromanager/staff/StaffController.java \
        backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java
git commit -m "feat(staff): add the roster/candidates/hire endpoints"
```

---

## Task 5: Training integration — `TrainingService` gains the coach bonus

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java`

**Interfaces:**
- Consumes: `StaffEffectsService.trainingBonusFor(UUID clubId, boolean managed): int` (Task 3).
- Produces: no new public interface — `TrainingService.applyTraining`'s signature is unchanged.

- [ ] **Step 1: Write the failing test**

Replace the entire contents of `backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java`
with:

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingServiceTest {

    private final StaffEffectsService staffEffectsService = Mockito.mock(StaffEffectsService.class);
    private final TrainingService training = new TrainingService(new OverallCalculator(), staffEffectsService);

    private Player player(Position position, int attr) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", position,
                24, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    @Test
    void doesNothingWhenFocusIsNull() {
        // Given a player with no training focus
        Player p = player(Position.MID, 50);

        // When training is applied with a null focus
        training.applyTraining(p, null, TrainingIntensity.INTENSA, new Random(1), p.getClubId(), false);

        // Then nothing changes: no attribute bump, no injury roll consumed
        assertThat(p.getFinishing()).isEqualTo(50);
        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void leveAddsOneToTheFocusedAttributeOnly() {
        // Given a player with all attributes at 50
        Player p = player(Position.MID, 50);

        // When trained Leve on Finalização
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.LEVE, new Random(1), p.getClubId(), false);

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

        training.applyTraining(normal, Attribute.PASSE, TrainingIntensity.NORMAL, new Random(1), normal.getClubId(), false);
        training.applyTraining(intensa, Attribute.PASSE, TrainingIntensity.INTENSA, new Random(1), intensa.getClubId(), false);

        assertThat(normal.getPassing()).isEqualTo(52);
        assertThat(intensa.getPassing()).isEqualTo(53);
    }

    @Test
    void theBonusIsClampedAtNinetyNine() {
        // Given a player already maxed at 99
        Player p = player(Position.ATT, 99);

        // When trained Intensa (+3 would overflow to 102)
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1), p.getClubId(), false);

        // Then it stays capped at 99
        assertThat(p.getFinishing()).isEqualTo(99);
    }

    @Test
    void overallIsRecomputedAfterTheBonus() {
        Player p = player(Position.ATT, 50);
        int overallBefore = p.getOverall();

        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.INTENSA, new Random(1), p.getClubId(), false);

        assertThat(p.getOverall()).isGreaterThan(overallBefore);
    }

    @Test
    void leveNeverRollsAnInjuryRegardlessOfSeed() {
        // 0% risk at Leve: no seed should ever produce an injury.
        for (int seed = 0; seed < 20; seed++) {
            Player p = player(Position.MID, 50);
            training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.LEVE, new Random(seed), p.getClubId(), false);
            assertThat(p.getInjuryMatches()).isZero();
        }
    }

    @Test
    void intensaCanRollAOneToTwoMatchInjury() {
        // Given seed 4608, verified to draw 0.0520... as its first nextDouble() — under
        // Intensa's 8% risk threshold (new Random(4608).nextDouble() == 0.05202727943619023)
        Player p = player(Position.MID, 50);

        // When trained Intensa with that seed
        training.applyTraining(p, Attribute.FOLEGO, TrainingIntensity.INTENSA, new Random(4608), p.getClubId(), false);

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

    @Test
    void addsTheClubsCoachBonusOnTopOfTheIntensityBonus() {
        // Given a player with a hired Excelente coach (staff bonus +2) on top of Leve (+1)
        Player p = player(Position.MID, 50);
        UUID clubId = p.getClubId();
        Mockito.when(staffEffectsService.trainingBonusFor(clubId, true)).thenReturn(2);

        // When trained Leve on Finalização
        training.applyTraining(p, Attribute.FINALIZACAO, TrainingIntensity.LEVE, new Random(1), clubId, true);

        // Then Finalização rose by Leve's +1 plus the coach's +2 = +3
        assertThat(p.getFinishing()).isEqualTo(53);
    }
}
```

Every pre-existing call now passes `p.getClubId(), false` as trailing arguments (`false` for
`managed`, so `staffEffectsService.trainingBonusFor` is never invoked for those tests — the mock
has no stubbing for the `false` branch and Mockito's default for an unstubbed `int`-returning
method is `0`, which changes nothing when added to the existing bonus, keeping every pre-existing
assertion exactly as it was). Only the new `addsTheClubsCoachBonusOnTopOfTheIntensityBonus` test
stubs and exercises the `true` branch.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: FAIL to compile — `applyTraining` doesn't accept the two new parameters yet.

- [ ] **Step 3: Update `TrainingService`**

In `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java`, add this import:

```java
import br.com.carvoeiromanager.staff.StaffEffectsService;
```

Add a new field and constructor parameter, and update the constructor body:

```java
    private final OverallCalculator overallCalculator;
    private final StaffEffectsService staffEffectsService;

    public TrainingService(OverallCalculator overallCalculator, StaffEffectsService staffEffectsService) {
        this.overallCalculator = overallCalculator;
        this.staffEffectsService = staffEffectsService;
    }
```

Change `applyTraining`'s signature and the bonus computation from:

```java
    public void applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng) {
        if (focus == null) {
            return;
        }

        int bonus = switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };
```

to:

```java
    public void applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng,
                              UUID clubId, boolean managed) {
        if (focus == null) {
            return;
        }

        int bonus = switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };
        bonus += staffEffectsService.trainingBonusFor(clubId, managed);
```

Add `import java.util.UUID;` if it isn't already imported in this file (check first — it may
already be present from an unrelated existing import).

- [ ] **Step 4: Update `SeasonRolloverService`'s two call sites**

In `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`, change:

```java
            if (managed) {
                trainingService.applyTraining(player, player.getTrainingFocus(),
                        player.getTrainingIntensity(), rng);
            } else if (!freeAgent) {
                trainingService.applyTraining(player, TrainingService.heuristicFocusFor(player.getPosition()),
                        TrainingIntensity.NORMAL, rng);
            }
```

to:

```java
            if (managed) {
                trainingService.applyTraining(player, player.getTrainingFocus(),
                        player.getTrainingIntensity(), rng, player.getClubId(), true);
            } else if (!freeAgent) {
                trainingService.applyTraining(player, TrainingService.heuristicFocusFor(player.getPosition()),
                        TrainingIntensity.NORMAL, rng, player.getClubId(), false);
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: PASS (9 tests — the 8 pre-existing plus the new one)

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL — confirms `SeasonRolloverServiceIT`'s existing training-rollover
tests (which pin `managed` clubs with no staff hired, so `trainingBonusFor` returns `0`) still
pass unmodified, and every other Spring-context test that constructs `TrainingService` picks it
up via autowiring (no manual `new TrainingService(...)` call exists anywhere outside tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java
git commit -m "feat(staff): add the coach's training bonus to TrainingService"
```

---

## Task 6: Injury-risk integration — `MatchOutcomeApplier` + `LiveMatchEngine`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java`
  (already exists — do not create a new file)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTimelineTest.java`
  (already exists)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`
  (already exists)

**Interfaces:**
- Consumes: `StaffEffectsService.injuryRiskMultiplierFor(UUID clubId, boolean managed): double`
  (Task 3).
- Produces: no new public interface.

**Why three existing test files need changes, not one new file:** all three already construct
`MatchOutcomeApplier`/`LiveMatchEngine` manually (`new MatchOutcomeApplier(new
FitnessCalculator())`, `new LiveMatchEngine(new TeamStrengthCalculator(), new
FitnessCalculator())`) — this was confirmed by reading all three files directly during planning,
not assumed. Once these constructors gain a `StaffEffectsService` parameter, all three fail to
compile until updated. Two of the three (`MatchOutcomeApplierTest`,
`MatchOutcomeApplierTimelineTest` via `LiveMatchEngineTest`'s indirect `simulate()` call) also
have **existing** tests that depend on the injury-chance math being unmodified — an unstubbed
Mockito mock's default return for a `double`-returning method is `0.0`, not `1.0`, which would
silently zero out every injury roll and break those existing assertions. Every mock in this task
must be explicitly stubbed to return `1.0` unconditionally (the "no staff hired" neutral value)
for `injuryRiskMultiplierFor`, not left unstubbed.

- [ ] **Step 1: Write the failing test**

In `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java`, add these
two imports alongside the existing ones:

```java
import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.mockito.Mockito;
```

Change the field declaration from:

```java
    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());
```

to:

```java
    private final StaffEffectsService staffEffectsService = Mockito.mock(StaffEffectsService.class);
    private final MatchOutcomeApplier applier =
            new MatchOutcomeApplier(new FitnessCalculator(), staffEffectsService);
```

Add this test to the end of the class, right before the closing `}`:

```java
    @Test
    void aZeroPhysioMultiplierMeansAnInjuryNeverRolls() {
        Player p = player(Position.MID);
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), true)).thenReturn(0.0);
        java.util.Map<java.util.UUID, Integer> fitness = java.util.Map.of(p.getId(), 1); // near-zero fitness
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        // Even at near-zero fitness (a high real injury chance), a 0.0 multiplier must guarantee
        // no injury ever rolls — run enough seeds that a real bug would show up reliably.
        for (int seed = 0; seed < 200; seed++) {
            applier.applyInjuries(List.of(p), fitness, new java.util.Random(seed), true, acc);
        }

        assertThat(p.getInjuryMatches()).isZero();
    }
```

Now every OTHER (pre-existing) test in this file that calls `applier.applyInjuries(...)` needs
`staffEffectsService` stubbed to the neutral `1.0` so their existing assertions/thresholds stay
correct. Add this line inside the two pre-existing test methods that call `applyInjuries`
(`exhaustedPlayersAreInjuredWithASeverityTierAndLayoff` and the private helper
`countInjuries`, both already in this file), as the first line of each method body:

```java
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
```

(`org.mockito.ArgumentMatchers.any()`/`.anyBoolean()` match any club id/managed value — every
pre-existing call in this file passes different players/booleans, so a broad stub is simpler and
equally correct here than stubbing each exact `(clubId, managed)` pair individually.)

In `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTimelineTest.java`,
change the field declaration from:

```java
    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());
```

to:

```java
    private final MatchOutcomeApplier applier =
            new MatchOutcomeApplier(new FitnessCalculator(), org.mockito.Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class));
```

No stubbing is needed here — every test in this file calls `applyInjuriesFromTimeline(...)`,
which applies pre-decided injuries directly and never calls `injuryRiskMultiplierFor` at all; the
mock only needs to exist to satisfy the constructor.

In `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`, add this
import alongside the existing ones:

```java
import org.mockito.Mockito;
```

Change the field declaration from:

```java
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator());
```

to:

```java
    private final br.com.carvoeiromanager.staff.StaffEffectsService staffEffectsService =
            Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class);
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staffEffectsService);
```

Add this static-initializer-style stub right after the field declaration (`engine.simulate(...)`
runs the full match loop, which calls the injury-roll methods internally on every existing test
in this file, even though none of them calls the private `maybeInjury`/`pickInjured` methods by
name):

```java
    {
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
    }
```

(An instance initializer block runs once per test instance, before each `@Test` method — the
same effect as repeating the stub in every test method, without the repetition.)

- [ ] **Step 2: Run the tests to verify the new one fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest" --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTimelineTest" --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest"`
Expected: FAIL to compile — `MatchOutcomeApplier`'s and `LiveMatchEngine`'s constructors don't
accept a `StaffEffectsService` yet.

- [ ] **Step 3: Update `MatchOutcomeApplier`**

In `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java`, add this
import:

```java
import br.com.carvoeiromanager.staff.StaffEffectsService;
```

Change the field/constructor from:

```java
    private final FitnessCalculator fitnessCalculator;

    public MatchOutcomeApplier(FitnessCalculator fitnessCalculator) {
        this.fitnessCalculator = fitnessCalculator;
    }
```

to:

```java
    private final FitnessCalculator fitnessCalculator;
    private final StaffEffectsService staffEffectsService;

    public MatchOutcomeApplier(FitnessCalculator fitnessCalculator, StaffEffectsService staffEffectsService) {
        this.fitnessCalculator = fitnessCalculator;
        this.staffEffectsService = staffEffectsService;
    }
```

Change `applyInjuries`'s chance computation from:

```java
    public void applyInjuries(List<Player> playedXi, Map<UUID, Integer> effectiveFitnessById,
                              Random rng, boolean managed, Summary acc) {
        for (Player p : playedXi) {
            int fitness = effectiveFitnessById.getOrDefault(p.getId(), 100);
            double chance = fitnessCalculator.injuryChance(fitness, p.getAge());
            if (rng.nextDouble() < chance) {
```

to:

```java
    public void applyInjuries(List<Player> playedXi, Map<UUID, Integer> effectiveFitnessById,
                              Random rng, boolean managed, Summary acc) {
        for (Player p : playedXi) {
            int fitness = effectiveFitnessById.getOrDefault(p.getId(), 100);
            double chance = fitnessCalculator.injuryChance(fitness, p.getAge())
                    * staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), managed);
            if (rng.nextDouble() < chance) {
```

- [ ] **Step 4: Update `LiveMatchEngine`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`, add this import:

```java
import br.com.carvoeiromanager.staff.StaffEffectsService;
```

Change the field/constructor from:

```java
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final FitnessCalculator fitnessCalculator;

    public LiveMatchEngine(TeamStrengthCalculator teamStrengthCalculator, FitnessCalculator fitnessCalculator) {
```

to:

```java
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final FitnessCalculator fitnessCalculator;
    private final StaffEffectsService staffEffectsService;

    public LiveMatchEngine(TeamStrengthCalculator teamStrengthCalculator, FitnessCalculator fitnessCalculator,
                           StaffEffectsService staffEffectsService) {
```

(keep the existing body's two assignments, and add `this.staffEffectsService = staffEffectsService;`
as a third line inside the constructor body).

Change `maybeInjury` from:

```java
    private void maybeInjury(int minute, Side side, LiveMatchConfig cfg, Random injuryRng,
                             List<LiveTimelineEvent> events, List<TimedInjury> injuries) {
        if (side.onPitch.isEmpty()) return;
        // Aggregate the per-player match injury chance into a per-minute team chance, preserving the
        // low-fitness/age bias when picking who gets hurt.
        double teamChance = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            teamChance += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge());
        }
        teamChance /= MINUTES;
```

to:

```java
    private void maybeInjury(int minute, Side side, LiveMatchConfig cfg, Random injuryRng,
                             List<LiveTimelineEvent> events, List<TimedInjury> injuries) {
        if (side.onPitch.isEmpty()) return;
        // side.home == Boolean.TRUE.equals(cfg.aiHome()) means this side is the AI-controlled one
        // (LiveMatchService always passes aiHome = !managedIsHome, see LiveMatchService.java:397).
        boolean sideManaged = side.home != Boolean.TRUE.equals(cfg.aiHome());
        // Aggregate the per-player match injury chance into a per-minute team chance, preserving the
        // low-fitness/age bias when picking who gets hurt.
        double teamChance = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            teamChance += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge())
                    * staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), sideManaged);
        }
        teamChance /= MINUTES;
```

Change `pickInjured` from:

```java
    private UUID pickInjured(Side side, LiveMatchConfig cfg, Random rng) {
        double total = 0.0;
        for (UUID id : side.onPitch) {
            total += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
        }
        double pick = rng.nextDouble() * total;
        double acc = 0.0;
        for (UUID id : side.onPitch) {
            acc += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
            if (pick < acc) return id;
        }
        return side.onPitch.get(side.onPitch.size() - 1);
    }
```

to:

```java
    private UUID pickInjured(Side side, LiveMatchConfig cfg, Random rng) {
        boolean sideManaged = side.home != Boolean.TRUE.equals(cfg.aiHome());
        double total = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            total += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge())
                    * staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), sideManaged);
        }
        double pick = rng.nextDouble() * total;
        double acc = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            acc += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge())
                    * staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), sideManaged);
            if (pick < acc) return id;
        }
        return side.onPitch.get(side.onPitch.size() - 1);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest" --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTimelineTest" --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest"`
Expected: PASS — `MatchOutcomeApplierTest` (11 tests: 10 pre-existing + 1 new),
`MatchOutcomeApplierTimelineTest` (3 pre-existing, unmodified behavior), `LiveMatchEngineTest`
(every pre-existing test, unmodified behavior thanks to the unconditional `1.0` stub).

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL. Every OTHER existing test that constructs `MatchOutcomeApplier` or
`LiveMatchEngine` manually (search `grep -rn "new MatchOutcomeApplier(\|new LiveMatchEngine("
backend/src/test` — three files were found and fixed above; confirm no others exist, and fix any
this search turns up that weren't anticipated) will fail to compile until updated the same way.
For every OTHER Spring-context test (`FixtureSimulatorIT` and friends) that constructs these
classes via `@Autowired` rather than manually, no change is needed — Spring wires the real
`StaffEffectsService` bean automatically, and with no staff hired for any club by default, it
returns `1.0` for the managed club (neutral) or `0.85` for every AI club (the fixed baseline).
The AI baseline is a genuine, intentional behavior change (AI clubs' injury risk drops slightly)
— confirm no existing test asserts an exact injury COUNT for a specific AI-club fixture; none is
expected to (existing tests assert determinism/reproducibility and general injury-mechanics
shape, not exact AI-side counts, per the same grep-verified absence of exact-value assertions
established during the Training plan), but this must be confirmed empirically by this full-suite
run, not assumed.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTimelineTest.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java
git commit -m "feat(staff): apply the physio's injury-risk multiplier in both match engines"
```

---

## Task 7: Finance integration — sponsorship multiplier, staff wages, scout discount

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceTransactionType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceServiceStaffIT.java`
  (new file)
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceStaffIT.java`
  (new file)

**Interfaces:**
- Consumes: `StaffEffectsService.sponsorshipMultiplierFor(...)`, `.wageDemandMultiplierFor(...)`
  (Task 3); `StaffRepository.findBySaveGameId(UUID): List<Staff>` (Task 1).
- Produces: no new public interface.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceServiceStaffIT.java`:

```java
package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceServiceStaffIT {

    @Autowired FinanceService financeService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired StaffRepository staffRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("financestaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void aHiredAssistantBoostsMonthlySponsorshipAndTheirWageIsDeducted() {
        Career c = createCareer();
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.ASSISTENTE,
                StaffQuality.EXCELENTE, "Roberto Lima", new BigDecimal("80000.00")));

        financeService.processMonth(c.id(), LocalDate.of(2026, 3, 1));

        // Assert on the recorded ledger rows directly rather than the club's overall balance —
        // the balance also moves from the unrelated (unmodified) player-wage and upkeep charges,
        // so only the ledger rows isolate the two staff-specific effects being tested here.
        List<FinanceTransactionResponse> transactions =
                financeService.getFinances(c.id(), c.clubId(), c.userId()).transactions();

        FinanceTransactionResponse staffWageTx = transactions.stream()
                .filter(t -> t.type().equals("STAFF_WAGES")).findFirst().orElseThrow();
        assertThat(staffWageTx.amount()).isEqualByComparingTo("-80000.00");

        FinanceTransactionResponse sponsorTx = transactions.stream()
                .filter(t -> t.type().equals("SPONSOR")).findFirst().orElseThrow();
        // R$100 000 x reputation x Excelente's 1.1 multiplier (see FinanceCalculator.monthlySponsorship
        // and the Global Constraints' effect table).
        BigDecimal expectedSponsorship = new BigDecimal("100000")
                .multiply(BigDecimal.valueOf(club.getReputation()))
                .multiply(new BigDecimal("1.1"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(sponsorTx.amount()).isEqualByComparingTo(expectedSponsorship);
    }
}
```

Add `import java.util.List;` alongside this file's existing imports —
`FinanceTransactionResponse` is already in the same `br.com.carvoeiromanager.finance` package as
this test file, so it needs no import of its own.

Create `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceStaffIT.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceStaffIT {

    @Autowired ContractService contractService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired StaffRepository staffRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("contractstaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player freeAgent(UUID saveGameId) {
        return Player.create(saveGameId, null, "Livre Um", Position.MID,
                26, "Brasil", 60, 60, 60, 60, 60, 60, 60);
    }

    @Test
    void anExcelenteScoutLowersTheEffectiveWageDemandNeededToSign() {
        Career c = createCareer();
        Player target = freeAgent(c.id());
        playerRepository.save(target);
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.OLHEIRO,
                StaffQuality.EXCELENTE, "Marcos Vieira", new BigDecimal("80000.00")));

        // For a 60-overall, 26-year-old (prime) player: weeklyWage = 60*60*20*1.00 = 72 000.00;
        // undiscounted demand = 72 000.00 * 1.15 = 82 800.00; Excelente's x0.9 scout discount
        // brings it to 74 520.00. An offer of 78 000.00 sits strictly between the two — it must
        // be REJECTED without the discount and ACCEPTED with it, so this offer genuinely proves
        // the multiplier is wired in, not just that some discount exists.
        BigDecimal offer = new BigDecimal("78000.00");

        PlayerContractResponse response = contractService.signFreeAgent(
                c.id(), c.userId(), target.getId(), offer, 3);

        assertThat(response.wage()).isEqualByComparingTo(offer);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.finance.FinanceServiceStaffIT" --tests "br.com.carvoeiromanager.contract.ContractServiceStaffIT"`
Expected: FAIL — `Staff.create`/`StaffRepository` compile fine (from Task 1), but the finance/
contract behavior doesn't yet apply any staff effect, so `ContractServiceStaffIT` fails
(`PLAYER_REJECTS_TERMS` thrown instead of a successful signing) and `FinanceServiceStaffIT`'s
balance assertion may pass vacuously (any nonzero net change) — if so, that test isn't yet
proving the RIGHT thing; note this and proceed, it will become meaningful once Step 3 lands.

- [ ] **Step 3: Add `STAFF_WAGES` and wire `FinanceService`**

In `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceTransactionType.java`, change:

```java
public enum FinanceTransactionType {
    GATE, MATCH_COST, WAGES, SPONSOR, UPKEEP, PRIZE, TRANSFER_IN, TRANSFER_OUT
}
```

to:

```java
public enum FinanceTransactionType {
    GATE, MATCH_COST, WAGES, SPONSOR, UPKEEP, PRIZE, TRANSFER_IN, TRANSFER_OUT, STAFF_WAGES
}
```

In `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java`, add this import:

```java
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.staff.StaffRepository;
```

Add two new fields and constructor parameters (alongside the existing ones):

```java
    private final StaffRepository staffRepository;
    private final StaffEffectsService staffEffectsService;
```

add `StaffRepository staffRepository, StaffEffectsService staffEffectsService,` as new
constructor parameters (append after the existing `FinanceCalculator financeCalculator`
parameter), and assign both in the constructor body (`this.staffRepository = staffRepository;
this.staffEffectsService = staffEffectsService;`).

Change `processMonth`'s per-club loop from:

```java
        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Map<UUID, BigDecimal> wageBillByClub = monthlyWageBills(saveGameId);

        for (Club club : clubs) {
            BigDecimal wages = wageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal sponsorship = financeCalculator.monthlySponsorship(club.getReputation());
            BigDecimal upkeep = financeCalculator.monthlyUpkeep(club.getStadiumCapacity());

            BigDecimal net = sponsorship.subtract(wages).subtract(upkeep);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (club.getId().equals(managedClubId)) {
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio mensal", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários mensais", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção mensal do estádio", monthDate);
            }
        }
```

to:

```java
        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Map<UUID, BigDecimal> wageBillByClub = monthlyWageBills(saveGameId);
        Map<UUID, BigDecimal> staffWageBillByClub = monthlyStaffWageBills(saveGameId);

        for (Club club : clubs) {
            boolean managed = club.getId().equals(managedClubId);
            BigDecimal wages = wageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal staffWages = staffWageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal sponsorshipMult = BigDecimal.valueOf(
                    staffEffectsService.sponsorshipMultiplierFor(club.getId(), managed));
            BigDecimal sponsorship = financeCalculator.monthlySponsorship(club.getReputation())
                    .multiply(sponsorshipMult).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal upkeep = financeCalculator.monthlyUpkeep(club.getStadiumCapacity());

            BigDecimal net = sponsorship.subtract(wages).subtract(upkeep).subtract(staffWages);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (managed) {
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio mensal", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários mensais", monthDate);
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção mensal do estádio", monthDate);
                if (staffWages.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.STAFF_WAGES, staffWages.negate(),
                            "Salários da comissão técnica", monthDate);
                }
            }
        }
```

Add a new private helper right after the existing `monthlyWageBills` helper:

```java
    private Map<UUID, BigDecimal> monthlyStaffWageBills(UUID saveGameId) {
        return staffRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.groupingBy(Staff::getClubId,
                        Collectors.reducing(BigDecimal.ZERO, Staff::getWage, BigDecimal::add)));
    }
```

Note: AI clubs never have `Staff` rows (per this feature's design), so
`monthlyStaffWageBills` naturally contributes zero for them without any special-casing — the
`ASSISTENTE`/etc. sponsorship multiplier still applies to AI clubs via the fixed baseline, but
their wage bill genuinely stays zero since they never hire.

- [ ] **Step 4: Wire the scout discount into `ContractService`**

In `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java`, add this
import:

```java
import br.com.carvoeiromanager.staff.StaffEffectsService;
```

Add a new field and constructor parameter:

```java
    private final StaffEffectsService staffEffectsService;
```

add `StaffEffectsService staffEffectsService` as a new constructor parameter (append after the
existing `TransferWindow transferWindow` parameter) and assign it in the constructor body.

Change `applyTerms` from:

```java
    void applyTerms(Player player, BigDecimal wage, int contractYears) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
        if (!contractValuation.acceptsWage(player.getOverall(), player.getAge(), wage)) {
            BigDecimal demand = contractValuation.wageDemand(player.getOverall(), player.getAge());
            throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects these terms; demands at least " + demand);
        }
        player.setWage(wage);
        player.setContractYears(contractYears);
    }
```

to:

```java
    void applyTerms(Player player, BigDecimal wage, int contractYears, UUID managedClubId) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
        BigDecimal scoutMult = BigDecimal.valueOf(
                staffEffectsService.wageDemandMultiplierFor(managedClubId, true));
        BigDecimal demand = contractValuation.wageDemand(player.getOverall(), player.getAge())
                .multiply(scoutMult).setScale(2, java.math.RoundingMode.HALF_UP);
        if (wage.compareTo(demand) < 0) {
            throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects these terms; demands at least " + demand);
        }
        player.setWage(wage);
        player.setContractYears(contractYears);
    }
```

Update both call sites — `renewContract`:

```java
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
        applyTerms(player, wage, contractYears);
```

becomes:

```java
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
        applyTerms(player, wage, contractYears, save.getManagedClubId());
```

and `signFreeAgent`:

```java
        applyTerms(player, wage, contractYears);   // validates length/wage + acceptance
```

becomes:

```java
        applyTerms(player, wage, contractYears, save.getManagedClubId());   // validates length/wage + acceptance
```

Also apply the same discount to the free-agent listing so the displayed price matches the real
threshold. Change `getFreeAgents`:

```java
    public List<FreeAgentResponse> getFreeAgents(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).stream()
                .map(p -> new FreeAgentResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())))
                .sorted(Comparator.comparing(FreeAgentResponse::overall).reversed())
                .toList();
    }
```

to:

```java
    public List<FreeAgentResponse> getFreeAgents(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        BigDecimal scoutMult = BigDecimal.valueOf(
                staffEffectsService.wageDemandMultiplierFor(save.getManagedClubId(), true));
        return playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).stream()
                .map(p -> new FreeAgentResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())
                                .multiply(scoutMult).setScale(2, java.math.RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(FreeAgentResponse::overall).reversed())
                .toList();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.finance.FinanceServiceStaffIT" --tests "br.com.carvoeiromanager.contract.ContractServiceStaffIT"`
Expected: PASS (both new tests)

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL. Every existing `ContractServiceTest`/`ContractServiceIT`/
`FinanceServiceTest`/`FinanceServiceIT` that constructs these services directly (rather than via
`@Autowired`) will fail to compile until updated for the new constructor parameters — search for
`new ContractService(` and `new FinanceService(` across `backend/src/test` and update each call
site to pass a real or mocked `StaffEffectsService`/`StaffRepository` (whichever the class now
needs), matching how that specific test already constructs its other dependencies (real bean vs.
mock). This is expected, mechanical fallout from a constructor signature change — fix every
instance, do not skip any.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/FinanceTransactionType.java \
        backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java \
        backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java \
        backend/src/test/java/br/com/carvoeiromanager/finance/FinanceServiceStaffIT.java \
        backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceStaffIT.java
git commit -m "feat(staff): apply the assistant's sponsorship boost, staff wages, and the scout's wage discount"
```

---

## Task 8: Frontend — "Comissão Técnica" page

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/components/Layout.tsx`
- Create: `frontend/src/features/staff/StaffPage.tsx`
- Create: `frontend/src/features/staff/StaffPage.test.tsx`

**Interfaces:**
- Consumes: `GET .../staff` (Task 4, returns `StaffRosterResponse`-shaped JSON), `GET
  .../staff/candidates?role=X` (Task 4), `PUT .../staff/{role}` (Task 4).
- Produces: no new interface consumed elsewhere — this is the final task.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/staff/StaffPage.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StaffPage } from './StaffPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getStaffRoster: vi.fn(),
  getStaffCandidates: vi.fn(),
  hireStaff: vi.fn(),
}))

import { getStaffRoster, getStaffCandidates, hireStaff } from '../../api/career'

const mockGetStaffRoster = vi.mocked(getStaffRoster)
const mockGetStaffCandidates = vi.mocked(getStaffCandidates)
const mockHireStaff = vi.mocked(hireStaff)

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/staff?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <StaffPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('StaffPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStaffRoster.mockResolvedValue({
      assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
    mockGetStaffCandidates.mockResolvedValue([
      { index: 0, name: 'Carlos Andrade', quality: 'REGULAR', wage: 20000 },
      { index: 1, name: 'Marcos Vieira', quality: 'BOM', wage: 45000 },
      { index: 2, name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000 },
    ])
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows an empty slot for each unfilled role, then hires a browsed candidate', async () => {
    mockHireStaff.mockResolvedValue({
      assistente: null, preparadorFisico: null,
      olheiro: { name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000 },
      fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))

    await waitFor(() => screen.getByText('Roberto Lima'))
    fireEvent.click(screen.getByRole('button', { name: /roberto lima/i }))

    await waitFor(() =>
      expect(mockHireStaff).toHaveBeenCalledWith('sg-1', 'club-1', 'OLHEIRO', 2, 'test-token'),
    )
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/staff/StaffPage.test.tsx`
Expected: FAIL — `StaffPage` doesn't exist yet, and `getStaffRoster`/`getStaffCandidates`/
`hireStaff` don't exist in `career.ts` yet.

- [ ] **Step 3: Add the types**

In `frontend/src/api/types.ts`, add these new types (anywhere near the other role-scoped types,
e.g. right after the `FreeAgent`/`SignFreeAgentRequest`/`PlayerContract` block):

```ts
export type StaffRole = 'ASSISTENTE' | 'PREPARADOR_FISICO' | 'OLHEIRO' | 'FISIOTERAPEUTA'
export type StaffQuality = 'REGULAR' | 'BOM' | 'EXCELENTE'

export type StaffMember = {
  name: string
  quality: StaffQuality
  wage: number
}

export type StaffRoster = {
  assistente: StaffMember | null
  preparadorFisico: StaffMember | null
  olheiro: StaffMember | null
  fisioterapeuta: StaffMember | null
}

export type StaffCandidate = {
  index: number
  name: string
  quality: StaffQuality
  wage: number
}
```

- [ ] **Step 4: Add the API client functions**

In `frontend/src/api/career.ts`, add `StaffRole`, `StaffRoster`, `StaffCandidate` to the
type-only import block from `'./types'`, and add these three functions right after
`renewContract`:

```ts
export function getStaffRoster(saveGameId: string, clubId: string, token: string): Promise<StaffRoster> {
  return apiGet<StaffRoster>(`/save-games/${saveGameId}/clubs/${clubId}/staff`, token)
}

export function getStaffCandidates(
  saveGameId: string,
  clubId: string,
  role: StaffRole,
  token: string,
): Promise<StaffCandidate[]> {
  return apiGet<StaffCandidate[]>(
    `/save-games/${saveGameId}/clubs/${clubId}/staff/candidates?role=${role}`, token)
}

export function hireStaff(
  saveGameId: string,
  clubId: string,
  role: StaffRole,
  candidateIndex: number,
  token: string,
): Promise<StaffRoster> {
  return apiPut<StaffRoster>(
    `/save-games/${saveGameId}/clubs/${clubId}/staff/${role}`, { candidateIndex }, token)
}
```

- [ ] **Step 5: Create `StaffPage.tsx`**

Create `frontend/src/features/staff/StaffPage.tsx`:

```tsx
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogContent, DialogTitle,
  IconButton, List, ListItemButton, ListItemText, Stack, Typography,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import { getStaffRoster, getStaffCandidates, hireStaff } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import type { StaffRole, StaffMember, StaffQuality } from '../../api/types'

const ROLE_LABEL: Record<StaffRole, string> = {
  ASSISTENTE: 'Auxiliar Técnico',
  PREPARADOR_FISICO: 'Preparador Físico',
  OLHEIRO: 'Olheiro',
  FISIOTERAPEUTA: 'Fisioterapeuta',
}

const QUALITY_LABEL: Record<StaffQuality, string> = {
  REGULAR: 'Regular',
  BOM: 'Bom',
  EXCELENTE: 'Excelente',
}

const ROLES: StaffRole[] = ['ASSISTENTE', 'PREPARADOR_FISICO', 'OLHEIRO', 'FISIOTERAPEUTA']

export function StaffPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''
  const [browsingRole, setBrowsingRole] = useState<StaffRole | null>(null)

  const enabled = Boolean(saveGameId && clubId && token)
  const roster = useQuery({
    queryKey: ['staffRoster', saveGameId, clubId],
    queryFn: () => getStaffRoster(saveGameId, clubId, token ?? ''),
    enabled,
  })

  const candidates = useQuery({
    queryKey: ['staffCandidates', saveGameId, clubId, browsingRole],
    queryFn: () => getStaffCandidates(saveGameId, clubId, browsingRole!, token ?? ''),
    enabled: enabled && browsingRole !== null,
  })

  const hireMut = useMutation({
    mutationFn: (candidateIndex: number) =>
      hireStaff(saveGameId, clubId, browsingRole!, candidateIndex, token ?? ''),
    onSuccess: () => {
      setBrowsingRole(null)
      void queryClient.invalidateQueries({ queryKey: ['staffRoster', saveGameId, clubId] })
    },
  })

  if (roster.isLoading) return <Typography>Carregando comissão técnica…</Typography>
  if (roster.error) return <Typography color="error">Erro ao carregar comissão técnica.</Typography>

  const holderFor = (role: StaffRole): StaffMember | null => {
    switch (role) {
      case 'ASSISTENTE': return roster.data?.assistente ?? null
      case 'PREPARADOR_FISICO': return roster.data?.preparadorFisico ?? null
      case 'OLHEIRO': return roster.data?.olheiro ?? null
      case 'FISIOTERAPEUTA': return roster.data?.fisioterapeuta ?? null
    }
  }

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Comissão Técnica</Typography>
      <Stack spacing={1.5}>
        {ROLES.map(role => {
          const holder = holderFor(role)
          return (
            <Card key={role} variant="outlined" data-testid={`staff-role-${role}`}>
              <CardContent>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Box>
                    <Typography variant="overline" color="text.secondary">{ROLE_LABEL[role]}</Typography>
                    {holder ? (
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                        <Typography sx={{ fontWeight: 600 }}>{holder.name}</Typography>
                        <Chip size="small" label={QUALITY_LABEL[holder.quality]} />
                        <Typography variant="body2" color="text.secondary">{formatBRL(holder.wage)}/mês</Typography>
                      </Stack>
                    ) : (
                      <Typography color="text.secondary" sx={{ mt: 0.5 }}>Vaga em aberto</Typography>
                    )}
                  </Box>
                  <Button variant="outlined" onClick={() => setBrowsingRole(role)}>
                    {holder ? 'Substituir' : 'Contratar'}
                  </Button>
                </Stack>
              </CardContent>
            </Card>
          )
        })}
      </Stack>

      <Dialog open={browsingRole !== null} onClose={() => setBrowsingRole(null)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {browsingRole ? ROLE_LABEL[browsingRole] : ''}
          <IconButton onClick={() => setBrowsingRole(null)} size="small"><CloseIcon fontSize="small" /></IconButton>
        </DialogTitle>
        <DialogContent>
          {hireMut.isError && <Alert severity="error" sx={{ mb: 2 }}>Não foi possível contratar.</Alert>}
          {candidates.isLoading && <Typography>Carregando candidatos…</Typography>}
          <List>
            {(candidates.data ?? []).map(c => (
              <ListItemButton
                key={c.index}
                disabled={hireMut.isPending}
                onClick={() => hireMut.mutate(c.index)}
                aria-label={c.name}
              >
                <ListItemText
                  primary={c.name}
                  secondary={`${QUALITY_LABEL[c.quality]} · ${formatBRL(c.wage)}/mês`}
                />
              </ListItemButton>
            ))}
          </List>
        </DialogContent>
      </Dialog>
    </Box>
  )
}
```

- [ ] **Step 6: Wire the route and nav entry**

In `frontend/src/router.tsx`, add this import alongside the existing feature imports:

```ts
import { StaffPage } from './features/staff/StaffPage'
```

Add a new `<Route>` right after the `/finances` route:

```tsx
      <Route
        path="/staff"
        element={
          <ProtectedRoute>
            <StaffPage />
          </ProtectedRoute>
        }
      />
```

In `frontend/src/components/Layout.tsx`, add a new nav entry to the `saveLinks` array (right
after the `/finances` entry, line 192):

```ts
        { to: `/staff?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Comissão Técnica' },
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/staff/StaffPage.test.tsx`
Expected: PASS (1 test)

- [ ] **Step 8: Run the full frontend suite**

Run: `cd frontend && npx tsc --noEmit && npx vitest run`
Expected: no type errors, all tests green. If `Layout.test.tsx` (or any snapshot/nav-count test)
asserts an exact count or exact list of nav links, update it to include the new "Comissão
Técnica" entry — check for such a test before concluding this step; don't assume none exists.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts frontend/src/router.tsx \
        frontend/src/components/Layout.tsx frontend/src/features/staff/StaffPage.tsx \
        frontend/src/features/staff/StaffPage.test.tsx
git commit -m "feat(staff): add the Comissão Técnica page"
```

---

## Self-Review Notes

- **Spec coverage:** every section of `docs/superpowers/specs/2026-07-15-staff-system-design.md`
  maps to a task — data model → Task 1, candidate generation → Task 2, effect facade → Task 3,
  hiring API → Task 4, the four integration points → Tasks 5-7, frontend → Task 8. Both
  discovered refinements (shared `applyTerms`, the `(clubId, managed)` signature) are called out
  in Global Constraints with the reasoning, not silently substituted.
- **Type consistency:** `StaffEffectsService`'s four method signatures (Task 3) are used
  identically at every call site (Tasks 5-7): `(UUID clubId, boolean managed)` throughout, never
  `(UUID clubId, UUID managedClubId)`. `StaffRole`/`StaffQuality` enum constant names match
  exactly between backend (Task 1) and frontend (Task 8: `'ASSISTENTE' | 'PREPARADOR_FISICO' |
  'OLHEIRO' | 'FISIOTERAPEUTA'`, `'REGULAR' | 'BOM' | 'EXCELENTE'`). `StaffRosterResponse`'s four
  field names (`assistente`, `preparadorFisico`, `olheiro`, `fisioterapeuta`, Task 4) match
  `StaffRoster`'s frontend fields (Task 8) exactly.
- **Placeholder scan:** no TBD/TODO markers. One step (Task 7's `ContractServiceStaffIT`)
  deliberately contains a planning correction inline (the nonexistent `saveGameIdForTest` helper)
  with the actual fix given immediately after, rather than silently shipping wrong example code —
  flagged explicitly so the implementer applies the corrected version, not the broken one.
