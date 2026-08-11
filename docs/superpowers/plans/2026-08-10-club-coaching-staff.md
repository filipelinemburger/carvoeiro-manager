# Comissão Técnica dos Clubes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every club in the seed starts its career with its real, researched coaching staff already employed — including Criciúma — and those attributes drive each club's gameplay effects.

**Architecture:** A new `club-staff.json` seed carries researched staff per club; `SaveGameService` persists it at career creation. `StaffEffectsService` drops its hardcoded AI baseline and reads real rows for every club. A fifth role `TECNICO` gains a small team-strength multiplier applied onto `TeamMatchInput`. Hiring becomes poaching: candidates include staff employed at rival clubs, and hiring one empties the source slot.

**Tech Stack:** Java 21 / Spring Boot / JPA / Flyway / Postgres (Testcontainers), JUnit 5 + AssertJ + Mockito; React 19 / TypeScript / MUI / TanStack Query / Vitest + React Testing Library; Node ESM for the migration generator script.

**Spec:** `docs/superpowers/specs/2026-08-10-club-coaching-staff-design.md`

## Global Constraints

- **Backend tests:** `cd backend && ./gradlew test --tests "<FQCN>"`. Integration tests (`*IT`) need Docker for Testcontainers.
- **Frontend tests:** `cd frontend && npx vitest run <path>`. Type-check with `npx tsc -b` — `tsc --noEmit` is a no-op in this repo.
- **Concurrent-session note:** a Gradle `EOFException` / `NoSuchFileException` is build contention from another session, not a real failure. Re-run once before investigating.
- **`git diff` needs `--no-ext-diff`** in this environment.
- Attributes are on the **1–20** scale; `staff.primary_attribute` / `secondary_attribute` carry CHECK constraints for that range (`V35__staff_attributes.sql`).
- Staff tier wages, unchanged: REGULAR `20000.00`, BOM `45000.00`, EXCELENTE `80000.00`.
- `StaffQuality.forAverage`: `>= 15.0` EXCELENTE, `>= 12.0` BOM, else REGULAR.
- **Never label a role bare "Técnico" in the four hirable roles' UI copy** — that word already means the manager persona. The new fifth role IS the head coach and is the sole exception.
- Commit after every task. Commit messages in English, `type: subject` form.

---

### Task 1: `TECNICO` role and a shared wage table

Adds the fifth role and removes the wage-table duplication that career creation would otherwise introduce. No DDL: `V27__staff.sql` declares `role VARCHAR(20) NOT NULL` with no CHECK constraint, and `TECNICO` fits.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRole.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffQuality.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java:115-121`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffQualityTest.java` (create)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/StaffPoolSeedTest.java:18-22`

**Interfaces:**
- Consumes: nothing.
- Produces: `StaffRole.TECNICO` (declared first, ordinal 0); `StaffQuality.monthlyWage()` returning `BigDecimal`; `StaffRole.HIRABLE` — `Set<StaffRole>` of the four non-`TECNICO` roles.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/staff/StaffQualityTest.java`:

```java
package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StaffQualityTest {

    @Test
    void eachTierCarriesItsMonthlyWage() {
        assertThat(StaffQuality.REGULAR.monthlyWage()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(StaffQuality.BOM.monthlyWage()).isEqualByComparingTo(new BigDecimal("45000.00"));
        assertThat(StaffQuality.EXCELENTE.monthlyWage()).isEqualByComparingTo(new BigDecimal("80000.00"));
    }

    @Test
    void tecnicoIsNotHirable() {
        assertThat(StaffRole.HIRABLE).doesNotContain(StaffRole.TECNICO);
        assertThat(StaffRole.HIRABLE).containsExactlyInAnyOrder(
                StaffRole.ASSISTENTE, StaffRole.PREPARADOR_FISICO,
                StaffRole.OLHEIRO, StaffRole.FISIOTERAPEUTA);
    }

    @Test
    void tecnicoSortsFirstSoItLeadsTheCommissionListing() {
        assertThat(StaffRole.TECNICO.ordinal()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffQualityTest"
```

Expected: FAIL — compilation error, `monthlyWage()` and `TECNICO` do not exist.

- [ ] **Step 3: Add the role and the wage table**

Replace `StaffRole.java` entirely:

```java
package br.com.carvoeiromanager.staff;

import java.util.Set;

/**
 * The technical-staff roles. {@code TECNICO} is the head coach: it is seeded for AI clubs only —
 * at the managed club the head coach is the player, so no row exists and the slot is synthesised
 * from the save's manager name. The other four are hirable; none of them is ever labeled bare
 * "Técnico" in the UI, because that word already denotes the manager persona.
 */
public enum StaffRole {
    TECNICO, ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA;

    /** The roles the player can hire or poach — everything except the head coach. */
    public static final Set<StaffRole> HIRABLE =
            Set.of(ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA);
}
```

Replace `StaffQuality.java` entirely:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** How good a hired staff member is; the hiring/wage band around the 1-20 attributes. */
public enum StaffQuality {
    REGULAR(new BigDecimal("20000.00")),
    BOM(new BigDecimal("45000.00")),
    EXCELENTE(new BigDecimal("80000.00"));

    private final BigDecimal monthlyWage;

    StaffQuality(BigDecimal monthlyWage) {
        this.monthlyWage = monthlyWage;
    }

    /** The monthly wage a staff member of this tier costs, for hires and for seeded staff alike. */
    public BigDecimal monthlyWage() {
        return monthlyWage;
    }

    /** Tier implied by an attribute average: >=15 EXCELENTE, >=12 BOM, else REGULAR. */
    public static StaffQuality forAverage(double average) {
        if (average >= 15.0) {
            return EXCELENTE;
        }
        return average >= 12.0 ? BOM : REGULAR;
    }
}
```

- [ ] **Step 4: Point the generator at the shared table**

In `StaffCandidateGenerator.java`, delete the private `wageFor` method (lines 115-121) and replace its three call sites (`wageFor(quality)` at lines 64 and 101) with `quality.monthlyWage()`.

- [ ] **Step 5: Fix the pool seed test's role expectation**

`StaffPoolSeedTest.everyRoleIsCovered` asserts the unemployed pool covers `StaffRole.values()`, which now includes `TECNICO`. Head coaches are employed, never in the unemployed pool. Replace the test body:

```java
    @Test
    void everyHirableRoleIsCovered() {
        Set<StaffRole> covered = pool.stream().map(SeedStaff::role).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrderElementsOf(StaffRole.HIRABLE);
    }

    @Test
    void theUnemployedPoolNeverOffersAHeadCoach() {
        assertThat(pool).noneMatch(s -> s.role() == StaffRole.TECNICO);
    }
```

- [ ] **Step 6: Run the affected tests**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.*" --tests "br.com.carvoeiromanager.seed.*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff backend/src/test/java/br/com/carvoeiromanager/staff/StaffQualityTest.java backend/src/test/java/br/com/carvoeiromanager/seed/StaffPoolSeedTest.java
git commit -m "feat: add TECNICO staff role and share the tier wage table"
```

---

### Task 2: `club-staff.json` seed, loader, and validation

Ships the seed plumbing plus Criciúma's real staff — the managed club, and the template Task 3 follows for the remaining 30.

**Files:**
- Create: `backend/src/main/resources/seed/club-staff.json`
- Create: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedClubStaff.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java`
- Create: `backend/src/test/java/br/com/carvoeiromanager/seed/ClubStaffSeedTest.java`

**Interfaces:**
- Consumes: `StaffRole.TECNICO`, `StaffRole.HIRABLE` (Task 1); the existing `SeedStaff(String name, StaffRole role, int primaryAttribute, int secondaryAttribute)` record, unchanged.
- Produces: `SeedClubStaff(String clubShortName, List<SeedStaff> staff)`; `SeedLoader.loadClubStaff()` returning `List<SeedClubStaff>`.

- [ ] **Step 1: Write the failing validation test**

Create `backend/src/test/java/br/com/carvoeiromanager/seed/ClubStaffSeedTest.java`:

```java
package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the researched club-staff seed. Coverage is deliberately uneven (a role with no
 * confirmed source is simply absent, leaving an open slot); what must never happen is a
 * contradiction — the same person employed twice, or employed and simultaneously on the
 * unemployed market.
 */
class ClubStaffSeedTest {

    private final SeedLoader loader = new SeedLoader();
    private final List<SeedClubStaff> clubStaff = loader.loadClubStaff();
    private final List<SeedStaff> unemployedPool = loader.loadStaffPool();

    private List<SeedStaff> allEntries() {
        return clubStaff.stream().flatMap(c -> c.staff().stream()).toList();
    }

    @Test
    void everyClubShortNameResolvesAgainstTheClubPool() {
        Set<String> known = loader.loadCareer().clubPool().stream()
                .map(SeedClub::shortName).collect(Collectors.toSet());
        assertThat(clubStaff).allSatisfy(c -> assertThat(known).contains(c.clubShortName()));
    }

    @Test
    void aClubIsListedAtMostOnce() {
        assertThat(clubStaff.stream().map(SeedClubStaff::clubShortName).distinct().count())
                .isEqualTo(clubStaff.size());
    }

    @Test
    void aClubFillsEachRoleAtMostOnce() {
        assertThat(clubStaff).allSatisfy(c ->
                assertThat(c.staff().stream().map(SeedStaff::role).distinct().count())
                        .isEqualTo(c.staff().size()));
    }

    @Test
    void namesAreUniqueAcrossEveryClub() {
        List<String> names = allEntries().stream().map(SeedStaff::name).toList();
        assertThat(names.stream().distinct().count()).isEqualTo(names.size());
    }

    @Test
    void nobodyIsBothEmployedAndOnTheUnemployedMarket() {
        Set<String> employed = allEntries().stream().map(SeedStaff::name).collect(Collectors.toSet());
        Set<String> unemployed = unemployedPool.stream().map(SeedStaff::name).collect(Collectors.toSet());
        assertThat(employed).doesNotContainAnyElementsOf(unemployed);
    }

    @Test
    void attributesAreWithinBoundsAndNamesNonBlank() {
        assertThat(allEntries()).allSatisfy(s -> {
            assertThat(s.name()).isNotBlank();
            assertThat(s.primaryAttribute()).isBetween(1, 20);
            assertThat(s.secondaryAttribute()).isBetween(1, 20);
        });
    }

    @Test
    void theManagedClubHasNoHeadCoachBecauseThePlayerIsTheHeadCoach() {
        assertThat(clubStaff)
                .filteredOn(c -> c.clubShortName().equals("CRI"))
                .allSatisfy(c -> assertThat(c.staff()).noneMatch(s -> s.role() == StaffRole.TECNICO));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.seed.ClubStaffSeedTest"
```

Expected: FAIL — compilation error, `SeedClubStaff` and `loadClubStaff` do not exist.

- [ ] **Step 3: Add the seed record**

Create `backend/src/main/java/br/com/carvoeiromanager/seed/SeedClubStaff.java`:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

/**
 * One club's researched coaching staff (seed/club-staff.json), keyed by the club's shortName.
 * A role with no confirmed real-world source is simply absent from {@code staff} — career
 * creation leaves that slot open rather than inventing someone.
 */
public record SeedClubStaff(String clubShortName, List<SeedStaff> staff) {
}
```

- [ ] **Step 4: Add the loader method**

In `SeedLoader.java`, after `loadStaffPool()`:

```java
    /** The researched coaching staff each club already employs when a career begins. Coverage is
     *  deliberately partial — an absent role means an open slot, not missing data. */
    public List<SeedClubStaff> loadClubStaff() {
        try (InputStream in = new ClassPathResource("seed/club-staff.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedClubStaff>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load club staff seed", e);
        }
    }
```

- [ ] **Step 5: Research and author Criciúma's entry**

Research Criciúma Esporte Clube's current (2026 season) coaching staff for `ASSISTENTE`,
`PREPARADOR_FISICO`, `OLHEIRO`, `FISIOTERAPEUTA`. **No `TECNICO`** — the player holds that job.

Rules, from spec §4.2–§4.4:
- One citable source per name (club's official site or social account, club press release, or a recognised sports outlet). Omit the role rather than guess.
- Attribute band from club reputation, adjusted by track record; `secondaryAttribute` at or one below `primaryAttribute`.
- Any name taken from `staff-pool.json` must be **deleted from that file** in the same commit — the seed test in Step 1 fails otherwise.
- Record each source as a bullet under spec §13.

Create `backend/src/main/resources/seed/club-staff.json` with Criciúma only, in this exact shape:

```json
[
  {
    "clubShortName": "CRI",
    "staff": [
      { "name": "<researched>", "role": "ASSISTENTE", "primaryAttribute": 13, "secondaryAttribute": 12 }
    ]
  }
]
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.seed.*"
```

Expected: PASS, including `StaffPoolSeedTest` (which still requires ≥12 entries per hirable role — if moving Criciúma's staff out of the pool drops a role below 12, that assertion is the signal to lower the floor deliberately rather than silently).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/seed backend/src/main/java/br/com/carvoeiromanager/seed backend/src/test/java/br/com/carvoeiromanager/seed/ClubStaffSeedTest.java docs/superpowers/specs/2026-08-10-club-coaching-staff-design.md
git commit -m "feat: add researched club-staff seed with Criciúma's commission"
```

---

### Task 3: Research the remaining 30 clubs

Data-only. This is the slice's real deliverable and its main risk (spec §12) — do it before the effects change in Task 6, so a bad coverage result can still change that decision.

**Files:**
- Modify: `backend/src/main/resources/seed/club-staff.json`
- Modify: `backend/src/main/resources/seed/staff-pool.json` (remove anyone found employed)
- Modify: `docs/superpowers/specs/2026-08-10-club-coaching-staff-design.md` (§11 coverage table, §13 sources)

**Interfaces:**
- Consumes: the JSON shape and research rules from Task 2.
- Produces: a `club-staff.json` covering all 31 clubs that satisfies `ClubStaffSeedTest`.

- [ ] **Step 1: Research each club**

The 30 remaining `shortName`s come from `clubs.json`'s `clubPool`. Dispatch one research agent per club (they are fully independent — see `superpowers:dispatching-parallel-agents`). Each returns JSON entries plus a source URL per name.

Every club needs five roles attempted: `TECNICO`, `ASSISTENTE`, `PREPARADOR_FISICO`, `OLHEIRO`, `FISIOTERAPEUTA`. Same rules as Task 2 Step 5.

Expect uneven results: near-total coverage on `TECNICO`, sparse on `OLHEIRO`, sparsest across the small Catarinense clubs (Carlos Renaux, Santa Catarina, Barra, Concórdia, Camboriú). Leave the gaps — do **not** invent names to fill the table.

- [ ] **Step 2: Merge and reconcile**

Append every club's entry to `club-staff.json`. Then delete from `staff-pool.json` every name that now appears as employed. Both files must satisfy `ClubStaffSeedTest.nobodyIsBothEmployedAndOnTheUnemployedMarket`.

- [ ] **Step 3: Run the seed tests**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.seed.*"
```

Expected: PASS.

- [ ] **Step 4: Fill in the coverage table**

Complete spec §11 with one row per club and a ✅/— per role, and append every source to §13.

- [ ] **Step 5: Report coverage before continuing**

Spec §12 makes this a decision gate. Report the per-role totals. **If `OLHEIRO` or `FISIOTERAPEUTA` coverage lands below roughly half the clubs, stop and raise it** — the §3.1 decision to delete the AI baseline (Task 6) rests on the AI clubs actually being differentiated, and mostly-empty slots would instead mean a uniform, quiet nerf across the league.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/seed docs/superpowers/specs/2026-08-10-club-coaching-staff-design.md
git commit -m "feat: research coaching staff for all 31 seed clubs"
```

---

### Task 4: Career creation seeds the commission

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java:45-53`
- Create: `backend/src/test/java/br/com/carvoeiromanager/career/ClubStaffSeedingIT.java`

**Interfaces:**
- Consumes: `SeedLoader.loadClubStaff()`, `SeedClubStaff` (Task 2); `StaffQuality.monthlyWage()`, `StaffRole.TECNICO` (Task 1).
- Produces: `staff` rows for every club at career creation; the managed club has no `TECNICO` row.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/career/ClubStaffSeedingIT.java`:

```java
package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubStaffSeedingIT {

    @Autowired SaveGameService saveGameService;
    @Autowired StaffRepository staffRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;

    private SaveGameResponse createCareer() {
        User user = User.create("seedstaff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void theManagedClubStartsWithItsResearchedCommission() {
        SaveGameResponse save = createCareer();

        List<Staff> staff = staffRepository.findByClubId(save.managedClubId());

        assertThat(staff).isNotEmpty();
        assertThat(staff).allSatisfy(s -> {
            assertThat(s.getSaveGameId()).isEqualTo(save.id());
            assertThat(s.getQuality()).isEqualTo(StaffQuality.forAverage(s.attributeAverage()));
            assertThat(s.getWage()).isEqualByComparingTo(s.getQuality().monthlyWage());
        });
    }

    @Test
    void theManagedClubNeverGetsAHeadCoachBecauseThePlayerIsOne() {
        SaveGameResponse save = createCareer();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.TECNICO))
                .isEmpty();
    }

    @Test
    void rivalClubsAlsoStartStaffedAndDifferFromEachOther() {
        SaveGameResponse save = createCareer();

        List<Club> rivals = clubRepository.findBySaveGameId(save.id()).stream()
                .filter(c -> !c.getId().equals(save.managedClubId()))
                .toList();
        List<Staff> rivalStaff = rivals.stream()
                .flatMap(c -> staffRepository.findByClubId(c.getId()).stream())
                .toList();

        assertThat(rivalStaff).isNotEmpty();
        // The point of the whole slice: rivals are no longer mechanically identical.
        assertThat(rivalStaff.stream().map(Staff::attributeAverage).distinct().count())
                .isGreaterThan(1);
    }

    @Test
    void atLeastOneRivalHasAHeadCoach() {
        SaveGameResponse save = createCareer();

        List<Staff> coaches = staffRepository.findBySaveGameId(save.id()).stream()
                .filter(s -> s.getRole() == StaffRole.TECNICO)
                .toList();

        assertThat(coaches).isNotEmpty();
        assertThat(coaches).noneMatch(s -> s.getClubId().equals(save.managedClubId()));
    }
}
```

`ClubRepository.findBySaveGameId(UUID)` already exists (`ClubRepository.java:10`).

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.career.ClubStaffSeedingIT"
```

Expected: FAIL — `staff` is empty, `isNotEmpty()` fails.

- [ ] **Step 3: Seed staff during career creation**

In `SaveGameService.java`, add the import `br.com.carvoeiromanager.seed.SeedClubStaff`, plus
`br.com.carvoeiromanager.staff.Staff`, `StaffQuality`, `StaffRole`.

Insert this call immediately after the club/player loop closes and before the contract-staggering
block (the loop ends at the `clubRepository.save(club);` line; `clubIdByShortName` and
`managedClubId` are both populated by then):

```java
        seedClubStaff(saveGame.getId(), managedClubId, clubIdByShortName);
```

Add the private method:

```java
    /**
     * Persists each club's researched coaching staff. The managed club never receives a TECNICO
     * row — that job is the player's, and {@code StaffService.getRoster} synthesises the slot from
     * the save's manager name instead.
     *
     * <p>An unknown shortName fails the whole career creation rather than being skipped: a career
     * that silently launched without a club's commission would be far harder to notice than a
     * failure here.
     */
    private void seedClubStaff(UUID saveGameId, UUID managedClubId, Map<String, UUID> clubIdByShortName) {
        for (SeedClubStaff entry : seedLoader.loadClubStaff()) {
            UUID clubId = clubIdByShortName.get(entry.clubShortName());
            if (clubId == null) {
                throw new IllegalStateException(
                        "club-staff.json references unknown club shortName: " + entry.clubShortName());
            }
            for (SeedStaff member : entry.staff()) {
                if (member.role() == StaffRole.TECNICO && clubId.equals(managedClubId)) {
                    continue;
                }
                double average = (member.primaryAttribute() + member.secondaryAttribute()) / 2.0;
                StaffQuality quality = StaffQuality.forAverage(average);
                entityManager.persist(Staff.create(saveGameId, clubId, member.role(), quality,
                        member.name(), quality.monthlyWage(),
                        member.primaryAttribute(), member.secondaryAttribute()));
            }
        }
    }
```

- [ ] **Step 4: Update the now-false "empty roster" assumption**

`StaffServiceIT.freshCareerHasAnEmptyRoster` asserts a fresh career has no staff — the opposite of
what this task delivers. Replace it:

```java
    @Test
    void freshCareerStartsWithTheResearchedCommission() {
        Career c = createCareer();

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        // Coverage is researched, so exactly which slots are filled is data, not contract —
        // what matters is that the manager no longer inherits an empty building.
        assertThat(staffRepository.findByClubId(c.clubId())).isNotEmpty();
        assertThat(roster).isNotNull();
    }
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.career.ClubStaffSeedingIT" --tests "br.com.carvoeiromanager.staff.StaffServiceIT"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/career backend/src/test/java/br/com/carvoeiromanager/career backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java
git commit -m "feat: seed each club's coaching staff at career creation"
```

---

### Task 5: The roster exposes `TECNICO`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffRosterResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java:40-46,88-96,113-125`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java`

**Interfaces:**
- Consumes: `StaffRole.TECNICO` (Task 1); seeded rows (Task 4).
- Produces: `StaffRosterResponse(StaffResponse tecnico, StaffResponse assistente, StaffResponse preparadorFisico, StaffResponse olheiro, StaffResponse fisioterapeuta)`; `StaffResponse.quality` and `.wage` are nullable, and both are `null` exactly when the entry is the player's own manager persona.

- [ ] **Step 1: Write the failing test**

Append to `StaffServiceIT`:

```java
    @Test
    void theManagedClubsHeadCoachSlotIsTheManagerThemselves() {
        Career c = createCareer();

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        assertThat(roster.tecnico()).isNotNull();
        assertThat(roster.tecnico().name()).isEqualTo("Técnico");   // createCareer's default manager name
        // Null quality/wage is how the frontend recognises "this is you, not a hire".
        assertThat(roster.tecnico().quality()).isNull();
        assertThat(roster.tecnico().wage()).isNull();
    }

    @Test
    void aRivalClubsHeadCoachSlotIsTheirRealCoach() {
        Career c = createCareer();
        UUID rivalClubId = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == StaffRole.TECNICO)
                .map(Staff::getClubId)
                .findFirst()
                .orElseThrow();

        StaffRosterResponse roster = staffService.getRoster(c.id(), rivalClubId, c.userId());

        assertThat(roster.tecnico()).isNotNull();
        assertThat(roster.tecnico().quality()).isNotNull();
        assertThat(roster.tecnico().wage()).isNotNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffServiceIT"
```

Expected: FAIL — compilation error, `roster.tecnico()` does not exist.

- [ ] **Step 3: Widen the response records**

`StaffRosterResponse.java`:

```java
package br.com.carvoeiromanager.staff;

/**
 * The club's current roster, one slot per role — null means the role is unfilled.
 * {@code tecnico} is special: for the managed club it carries the player's own manager persona
 * (null quality and wage), because at your club the head coach is you.
 */
public record StaffRosterResponse(StaffResponse tecnico, StaffResponse assistente,
                                  StaffResponse preparadorFisico, StaffResponse olheiro,
                                  StaffResponse fisioterapeuta) {
}
```

`StaffResponse.java` — the record shape is unchanged; only the javadoc gains the nullability contract:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/**
 * One filled staff slot. {@code quality} and {@code wage} are null only for the managed club's
 * head-coach slot, which is the player rather than an employee — the frontend keys the
 * "this is you" rendering off that.
 */
public record StaffResponse(String name, String quality, BigDecimal wage,
                            int primaryAttribute, int secondaryAttribute) {
}
```

- [ ] **Step 4: Synthesise the managed club's coach in the service**

In `StaffService.java`, `getRoster` must know whether it is looking at the managed club, so
`toRosterResponse` takes the save. Replace `getRoster` and `toRosterResponse`:

```java
    public StaffRosterResponse getRoster(UUID saveGameId, UUID clubId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));
        return toRosterResponse(byRole, clubId.equals(save.getManagedClubId()), save.getManagerName());
    }
```

```java
    private StaffRosterResponse toRosterResponse(Map<StaffRole, Staff> byRole, boolean managedClub,
                                                 String managerName) {
        // At the managed club the head coach is the player, so no row exists (see
        // SaveGameService.seedClubStaff) and the slot is filled from the manager persona instead.
        StaffResponse tecnico = managedClub
                ? new StaffResponse(managerName, null, null, 0, 0)
                : toStaffResponse(byRole.get(StaffRole.TECNICO));
        return new StaffRosterResponse(
                tecnico,
                toStaffResponse(byRole.get(StaffRole.ASSISTENTE)),
                toStaffResponse(byRole.get(StaffRole.PREPARADOR_FISICO)),
                toStaffResponse(byRole.get(StaffRole.OLHEIRO)),
                toStaffResponse(byRole.get(StaffRole.FISIOTERAPEUTA)));
    }
```

In `hire`, the response is built inline (lines 88-96). It only ever runs for the managed club —
`requireOwned` plus `requireClubInSave` guarantee that — so update its tail:

```java
        return toRosterResponse(byRole, true, save.getManagerName());
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java
git commit -m "feat: expose the head-coach slot on the staff roster"
```

---

### Task 6: Real staff attributes drive every club's effects

**REVISED after Task 3's coverage gate fired.** The original version of this task deleted the 14.0
AI baseline entirely. Measured coverage (`FISIOTERAPEUTA` 4/31, `OLHEIRO` 8/31) showed that would
raise injuries ~20% for 27 of 31 clubs — a league-wide nerf, not differentiation — because an empty
slot records what the internet publishes, not what the club employs. Per design §3.2a the baseline
now survives as a **floor for AI clubs only**.

Consequence: **the four public signatures do not change**, so six of the seven call sites need no
edit at all. Only the fallback inside `averageFor` and `TrainingService`'s single global AI bonus
move.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java:49-60`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java:87-88,113`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java`

`ContractService`, `MatchOutcomeApplier`, `LiveMatchEngine` and `FinanceService` are **untouched**
by this task.

**Interfaces:**
- Consumes: seeded rows for every club (Task 4).
- Produces: unchanged public API — `trainingBonusFor(UUID clubId, boolean managed)`, `injuryRiskMultiplierFor(UUID clubId, boolean managed)`, `wageDemandMultiplierFor(UUID clubId, boolean managed)`, `sponsorshipMultiplierFor(UUID clubId, boolean managed)`. Behaviour changes, not shape.

- [ ] **Step 1: Write the failing test**

`StaffEffectsServiceTest` currently has a test asserting the old contract — that a non-managed club
returns the baseline **without touching the repository** (it uses `verifyNoInteractions`). That
contract is exactly what changes: an AI club is now read first, and only falls back to the baseline
when its slot is empty. Update that test to reflect the new behaviour rather than deleting it.

Keep every existing call's two-argument form; the signatures do not change. Add:

```java
    @Test
    void anAiClubWithRealStaffUsesThoseAttributesInsteadOfTheBaseline() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 18, 18)));

        // The whole point of the slice: a researched physio beats the flat 14.0 baseline (~0.886).
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isLessThan(0.80);
    }

    @Test
    void anAiClubWithAnEmptySlotFallsBackToTheBaseline() {
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        // An empty slot on an AI club records a research gap, not a club without a physio —
        // so it must behave exactly as it did before this slice (design §3.2a).
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isEqualTo(0.8285714285714286);
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);
    }

    @Test
    void theManagedClubWithAnEmptySlotStaysNeutral() {
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        // At your own club an empty slot IS a fact — you have not hired anyone — and hiring is
        // the mechanic that fixes it. No floor here.
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isZero();
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }
```

The literal `0.8285714285714286` is `lerp(14.0, 1.0, 0.70)` = `1.0 - 0.3 * (4.0/7.0)`. Pin the exact
value rather than a range: it is the number an in-progress career is already running on, and this
task's promise is that it does not move.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffEffectsServiceTest"
```

Expected: FAIL — `anAiClubWithRealStaffUsesThoseAttributesInsteadOfTheBaseline` fails because
today's `averageFor` short-circuits to 14.0 for `managed == false` and never reads the row, so the
result is 0.8285…, not below 0.80.

- [ ] **Step 3: Turn the baseline into a floor**

In `StaffEffectsService.java`, the four public methods and the constructor are unchanged. Replace
only the class javadoc, the `AI_BASELINE_AVERAGE` javadoc, and `averageFor`:

```java
/**
 * The single place that turns a hired {@link Staff} row into a numeric effect. Every consumer
 * (training, injury risk, wage-demand discounts, sponsorship, match strength) calls through here
 * instead of touching {@link StaffRepository} directly.
 *
 * <p>Every club is read from its real rows. What differs is the fallback when a role is unfilled:
 * the managed club gets a neutral effect, because there an empty slot is a fact the player can act
 * on by hiring; an AI club gets {@link #AI_BASELINE_AVERAGE}, because there an empty slot only
 * means the seed research found nobody — not that the club employs nobody (design §3.2a).
 */
```

```java
    /**
     * Floor for an AI club whose role the research could not fill. Equivalent to the old flat
     * {@link StaffQuality#BOM}, so an unresearched AI club behaves exactly as it did before club
     * staff existed. Never applied to the managed club.
     */
    private static final double AI_BASELINE_AVERAGE = 14.0;
```

```java
    /**
     * The effective attribute average for a role: the club's real hired staff, or — when the slot
     * is empty — {@code null} for the managed club (neutral effect) and the baseline for an AI one.
     */
    private Double averageFor(UUID clubId, boolean managed, StaffRole role) {
        return staffRepository.findByClubIdAndRole(clubId, role)
                .map(Staff::attributeAverage)
                .orElseGet(() -> managed ? null : AI_BASELINE_AVERAGE);
    }
```

Note the deliberate shape: the old version returned early for `!managed` and never queried. Now the
query always runs and `orElseGet` decides the fallback.

- [ ] **Step 4: Make the training bonus per club**

`TrainingService.java:87-88` resolves two constants for the whole week — one for the managed club
and one "AI bonus" standing in for all 30 other clubs. That second constant stops describing the
league the moment AI clubs differ from each other, which is what Task 4 just made true.

Delete both lines. The `players` list is resolved further down; immediately after it, add:

```java
        // One lookup per club per week, not one per player. A single league-wide "AI bonus" no
        // longer describes anything now that each club has its own preparador físico.
        Map<UUID, Integer> trainingBonusByClub = players.stream()
                .map(Player::getClubId)
                .distinct()
                .collect(Collectors.toMap(
                        clubId -> clubId,
                        clubId -> staffEffectsService.trainingBonusFor(clubId, clubId.equals(managedClubId))));
```

Replace line 113 — the `managed` local is already in scope there but the map keys off the club, so
the ternary goes away entirely:

```java
            int bonus = intensityBonus(intensity) + trainingBonusByClub.getOrDefault(p.getClubId(), 0);
```

Add the imports `java.util.Map` and `java.util.stream.Collectors` if absent.

**Do not touch** `ContractService`, `MatchOutcomeApplier`, `LiveMatchEngine` or `FinanceService` —
they already pass the correct `managed` flag and their behaviour is unchanged by this task.

- [ ] **Step 6: Run the full backend suite**

```bash
cd backend && ./gradlew test
```

Expected: PASS. Seeded-simulation tests must be unaffected — nothing here touches match strength yet.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: drive every club's staff effects from its real roster"
```

---

### Task 7: The head coach influences team strength

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TeamMatchInput.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java:228-229`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:89-96,110-112,141-142,371-381`
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/TeamMatchInputCoachTest.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java`

**Interfaces:**
- Consumes: `StaffRole.TECNICO` (Task 1); the single-argument effects API (Task 6).
- Produces: `StaffEffectsService.coachStrengthMultiplierFor(UUID clubId)` returning `double` in `[0.96, 1.04]`; `TeamMatchInput.withCoachMultiplier(double)` returning `TeamMatchInput`.

**Why this one method takes no `managed` flag**, unlike the four beside it: Task 6 gives an AI club
with an empty slot the 14.0 baseline so a research gap never becomes a penalty. For a multiplier
centred on 1.0, the non-penalising value already *is* 1.0 — an unresearched club fields a
league-average coach. Routing it through the baseline would instead produce 1.0057 — a systematic
+0.57% bonus for every unresearched AI club (this line originally, and wrongly, called it a
rounding artefact), which would perturb existing seeded-simulation results for no gain. Only
CAM and CON reach this branch (`TECNICO` coverage is 28/31, and CRI has no row by design). Put that
reasoning in the method's javadoc — the asymmetry with its four neighbours will otherwise read as
an oversight.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/br/com/carvoeiromanager/match/TeamMatchInputCoachTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMatchInputCoachTest {

    private final TeamMatchInput base = new TeamMatchInput(
            50.0, 40.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.1, Mentality.BALANCED, GameStyle.EQUILIBRADO);

    @Test
    void aNeutralMultiplierLeavesTheInputUntouched() {
        // The determinism contract: clubs without a head coach must produce byte-identical
        // seeded results, so every pre-existing simulation test stays valid.
        assertThat(base.withCoachMultiplier(1.0)).isEqualTo(base);
    }

    @Test
    void theMultiplierScalesBothAttackAndDefenseAndNothingElse() {
        TeamMatchInput scaled = base.withCoachMultiplier(1.04);

        assertThat(scaled.attack()).isEqualTo(52.0);
        assertThat(scaled.defense()).isEqualTo(41.6);
        assertThat(scaled.tempo()).isEqualTo(base.tempo());
        assertThat(scaled.penaltyConversion()).isEqualTo(base.penaltyConversion());
        assertThat(scaled.mentality()).isEqualTo(base.mentality());
        assertThat(scaled.gameStyle()).isEqualTo(base.gameStyle());
    }
}
```

Append to `StaffEffectsServiceTest`:

```java
    @Test
    void aClubWithNoHeadCoachIsExactlyNeutral() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.empty());

        // The managed club always lands here: it never has a TECNICO row, because the player
        // is the head coach and their decisions are already the engine's input.
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(1.0);
    }

    @Test
    void theCoachMultiplierSpansTheTierAnchorsAndClamps() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 10, 10)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(0.96, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 17, 17)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(1.04, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 20, 20)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(1.04, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 1, 1)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(0.96, within(1e-9));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.match.TeamMatchInputCoachTest" --tests "br.com.carvoeiromanager.staff.StaffEffectsServiceTest"
```

Expected: FAIL — `withCoachMultiplier` and `coachStrengthMultiplierFor` do not exist.

- [ ] **Step 3: Add the multiplier to `StaffEffectsService`**

```java
    /**
     * Head-coach strength multiplier for a club, in [0.96, 1.04]. The managed club has no TECNICO
     * row (see SaveGameService.seedClubStaff), so it always receives exactly 1.0 — the player's own
     * tactical decisions are already what the engine reads.
     */
    public double coachStrengthMultiplierFor(UUID clubId) {
        Double a = averageFor(clubId, StaffRole.TECNICO);
        return a == null ? 1.0 : clamp(lerp(a, 0.96, 1.04), 0.96, 1.04);
    }
```

- [ ] **Step 4: Add `withCoachMultiplier` to `TeamMatchInput`**

Append inside the record body:

```java
    /**
     * Scales attack and defense by the head coach's influence. At {@code m == 1.0} this returns an
     * equal record, which is what keeps every seeded simulation result stable for clubs that have
     * no head coach.
     */
    public TeamMatchInput withCoachMultiplier(double m) {
        if (m == 1.0) {
            return this;
        }
        return new TeamMatchInput(attack * m, defense * m, tempo, aggression, cornerThreat,
                freeKickThreat, penaltyConversion, fitnessDecay, mentality, gameStyle);
    }
```

- [ ] **Step 5: Apply it in `FixtureSimulator`**

Replace lines 228-229:

```java
        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile, fitnessMult(homeFitness))
                .withCoachMultiplier(staffEffectsService.coachStrengthMultiplierFor(homeClubId));
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile, fitnessMult(awayFitness))
                .withCoachMultiplier(staffEffectsService.coachStrengthMultiplierFor(awayClubId));
```

`FixtureSimulator` does **not** currently hold a `StaffEffectsService` — it reaches injury effects
through `MatchOutcomeApplier`. Add it: a `private final StaffEffectsService staffEffectsService;`
field alongside the others (after `matchContextResolver`, line 65), a
`StaffEffectsService staffEffectsService` parameter at the end of the constructor parameter list
(after `MatchContextResolver matchContextResolver`, line 81), the matching
`this.staffEffectsService = staffEffectsService;` assignment, and the import
`br.com.carvoeiromanager.staff.StaffEffectsService`. Any test constructing `FixtureSimulator`
by hand needs the extra argument — `grep -rn "new FixtureSimulator(" backend/src/test` to find them.

- [ ] **Step 6: Apply it in `LiveMatchEngine`**

`strength(...)` is called four times (kickoff at 110/112, dirty recompute at 141/142), so resolve
the multiplier once per side. After line 96 (`awayInjuryRisk`), add:

```java
        // Resolved once per side for the same reason as the injury multiplier: the club cannot
        // change mid-match, and strength() is recomputed on every substitution and red card.
        double homeCoachMult = coachMultiplierFor(home);
        double awayCoachMult = coachMultiplierFor(away);
```

Add the helper next to `resolveInjuryRiskMultiplier`:

```java
    private double coachMultiplierFor(Side side) {
        UUID clubId = side.all.values().iterator().next().getClubId();
        return staffEffectsService.coachStrengthMultiplierFor(clubId);
    }
```

Widen `strength` and its four call sites:

```java
    private TeamMatchInput strength(Side side, TacticalProfile profile, LiveMatchConfig cfg,
                                    double coachMultiplier) {
        List<Player> xi = side.onPitch.stream().map(side.all::get).toList();
        Map<UUID, Double> mult = new HashMap<>();
        for (UUID id : side.onPitch) {
            int fit = cfg.fitness().getOrDefault(id, 100);
            double m = fitnessCalculator.fitnessMult(fit);
            if (Boolean.TRUE.equals(side.hobbled.get(id))) m *= HOBBLE_MULT;
            mult.put(id, m);
        }
        return teamStrengthCalculator.compute(xi, profile, mult).withCoachMultiplier(coachMultiplier);
    }
```

- line 110 → `strength(home, home.currentProfile, cfg, homeCoachMult)`
- line 112 → `strength(away, away.currentProfile, cfg, awayCoachMult)`
- line 141 → `homeInput = strength(home, home.currentProfile, cfg, homeCoachMult);`
- line 142 → `awayInput = strength(away, away.currentProfile, cfg, awayCoachMult);`

- [ ] **Step 7: Run the full backend suite**

```bash
cd backend && ./gradlew test
```

Expected: PASS. Any seeded-simulation test that now fails means a club under test has a `TECNICO`
row — confirm the drift is the intended effect and re-baseline that expectation deliberately;
do **not** weaken `withCoachMultiplier`'s neutral-at-1.0 contract to make it green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: let a club's head coach shift its team strength"
```

---

### Task 8: Poaching replaces the closed hiring pool

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidate.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/staff/EmployedStaff.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java`

**Interfaces:**
- Consumes: `StaffRole.HIRABLE`, `StaffQuality.monthlyWage()` (Task 1); seeded rival staff (Task 4).
- Produces: `EmployedStaff(UUID staffId, String name, String clubName, int primaryAttribute, int secondaryAttribute)`; `StaffCandidate` gains `UUID sourceStaffId` and `String currentClubName`; `StaffCandidateResponse` gains `String currentClubName`; `StaffCandidateGenerator.generate(UUID, UUID, StaffRole, LocalDate, int, Set<String>, List<EmployedStaff>)`.

- [ ] **Step 1: Write the failing tests**

Append to `StaffServiceIT`:

```java
    @Test
    void candidatesIncludeStaffEmployedAtRivalClubs() {
        Career c = createCareer();

        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anySatisfy(x -> assertThat(x.currentClubName()).isNotNull());
    }

    @Test
    void poachingEmptiesTheSourceClubsSlot() {
        Career c = createCareer();
        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());
        StaffCandidateResponse poached = candidates.stream()
                .filter(x -> x.currentClubName() != null)
                .findFirst()
                .orElseThrow();
        UUID sourceClubId = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getName().equals(poached.name()))
                .map(Staff::getClubId)
                .findFirst()
                .orElseThrow();

        staffService.hire(c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, poached.index(), c.userId());

        assertThat(staffRepository.findByClubIdAndRole(sourceClubId, StaffRole.PREPARADOR_FISICO))
                .isEmpty();
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.PREPARADOR_FISICO))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo(poached.name()));
    }

    @Test
    void theHeadCoachCanNeverBeBrowsedOrHired() {
        Career c = createCareer();

        assertThatThrownBy(() -> staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.TECNICO, c.userId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> staffService.hire(
                c.id(), c.clubId(), StaffRole.TECNICO, 0, c.userId()))
                .isInstanceOf(ApiException.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.StaffServiceIT"
```

Expected: FAIL — `currentClubName()` does not exist.

- [ ] **Step 3: Add the poaching-target view**

Create `backend/src/main/java/br/com/carvoeiromanager/staff/EmployedStaff.java`:

```java
package br.com.carvoeiromanager.staff;

import java.util.UUID;

/**
 * A staff member currently employed at another club — a poaching target. Resolved by
 * {@link StaffService} and handed to {@link StaffCandidateGenerator}, which must stay free of
 * repository access.
 */
public record EmployedStaff(UUID staffId, String name, String clubName,
                            int primaryAttribute, int secondaryAttribute) {
}
```

- [ ] **Step 4: Widen the candidate records**

`StaffCandidate.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rolled, not-yet-hired staff candidate — never persisted. {@code sourceStaffId} and
 * {@code currentClubName} are both null for an unemployed professional or a generated newgen, and
 * both set when the candidate is a poaching target employed elsewhere.
 */
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage,
                             int primaryAttribute, int secondaryAttribute,
                             UUID sourceStaffId, String currentClubName) {

    /** A candidate who is not currently employed anywhere. */
    public static StaffCandidate free(String name, StaffQuality quality, BigDecimal wage,
                                      int primaryAttribute, int secondaryAttribute) {
        return new StaffCandidate(name, quality, wage, primaryAttribute, secondaryAttribute, null, null);
    }
}
```

`StaffCandidateResponse.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/**
 * One of the 3 offered candidates for a role, with its index for hiring. {@code currentClubName}
 * is null for a free professional and set for someone who would have to be poached.
 */
public record StaffCandidateResponse(int index, String name, String quality, BigDecimal wage,
                                     int primaryAttribute, int secondaryAttribute,
                                     String currentClubName) {
}
```

- [ ] **Step 5: Teach the generator to offer rivals' staff**

In `StaffCandidateGenerator.generate(...)`, add a final `List<EmployedStaff> employedElsewhere`
parameter. After the authored-pool block (which fills `candidates` from `available`) and before the
newgen `while` loop, insert:

```java
        // Poaching targets: real professionals already working at other clubs in this save. Sorted
        // by id before shuffling so the same day always offers the same names.
        if (candidates.size() < count) {
            List<EmployedStaff> poachable = employedElsewhere.stream()
                    .filter(e -> !taken.contains(e.name()))
                    .sorted(Comparator.comparing(EmployedStaff::staffId))
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(poachable, rng);
            for (int i = 0; i < poachable.size() && candidates.size() < count; i++) {
                EmployedStaff e = poachable.get(i);
                double average = (e.primaryAttribute() + e.secondaryAttribute()) / 2.0;
                StaffQuality quality = StaffQuality.forAverage(average);
                candidates.add(new StaffCandidate(e.name(), quality, quality.monthlyWage(),
                        e.primaryAttribute(), e.secondaryAttribute(), e.staffId(), e.clubName()));
                taken.add(e.name());
            }
        }
```

`taken` is currently built *after* the authored-pool block
(`Set<String> taken = new HashSet<>(excludedNames);` followed by
`candidates.forEach(c -> taken.add(c.name()));`). The poaching block needs it earlier, so:

1. move `Set<String> taken = new HashSet<>(excludedNames);` to just above the authored-pool loop;
2. add `taken.add(pro.name());` as the last line inside that loop;
3. delete the now-redundant `candidates.forEach(c -> taken.add(c.name()));` line.

Change the two existing `new StaffCandidate(...)` calls to `StaffCandidate.free(...)`, and add the
`java.util.Comparator` import.

Update the class javadoc to name the three tiers: free professionals, then poachable rivals, then
newgens.

- [ ] **Step 6: Wire the service**

In `StaffService.java`, add a `requireHirable` guard and change both callers of the generator.

```java
    /** The head coach is the player — there is nobody to browse and nobody to hire. */
    private void requireHirable(StaffRole role) {
        if (!StaffRole.HIRABLE.contains(role)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Role " + role + " cannot be hired");
        }
    }
```

**Also close a pre-existing authorization gap, found by Task 5's review.** `requireClubInSave`
(`StaffService.java:105-111`) only checks that `clubId` belongs to the caller's *save game* — not
that it is the caller's *managed club*. `clubId` is a free path variable on
`StaffController.hire`, so today a crafted `PUT .../clubs/{rivalClubId}/staff/OLHEIRO` hires staff
onto a rival, putting the wage bill and the benefit on that rival. It predates this whole slice,
but Task 5 made the consequence visible: `hire` passes `managedClub = true` unconditionally
(`StaffService.java:96`), so the response would show the player's own manager name inside a rival's
roster.

Add alongside `requireHirable`:

```java
    /**
     * Browsing and hiring only ever apply to your own club. {@code requireClubInSave} alone is not
     * enough — it permits any club in the caller's save, which would let a crafted request staff a
     * rival at that rival's expense.
     */
    private void requireManagedClub(SaveGame save, UUID clubId) {
        if (!clubId.equals(save.getManagedClubId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Staff can only be managed for your own club");
        }
    }
```

Call it in both `getCandidates` and `hire`, right after `requireClubInSave`. Both methods already
hold the `SaveGame` from `requireOwned`. With this guard in place, `hire`'s hardcoded
`toRosterResponse(byRole, true, ...)` becomes genuinely true rather than assumed.

Cover it with a test: hiring against a rival club's id, inside your own save, must be rejected.

Replace the `hiredNames` resolution in both `getCandidates` and `hire` with a shared helper. The
exclusion narrows from save-wide to this club only — otherwise every rival's staff is filtered out
and poaching can never happen:

```java
    /**
     * Names already spoken for at the hiring club. Deliberately NOT save-wide: staff employed at
     * other clubs are poaching targets, not exclusions.
     */
    private Set<String> namesAtClub(UUID clubId) {
        return staffRepository.findByClubId(clubId).stream()
                .map(Staff::getName)
                .collect(Collectors.toSet());
    }

    /** Real professionals working elsewhere in this save, for the given role. */
    private List<EmployedStaff> poachTargets(UUID saveGameId, UUID hiringClubId, StaffRole role) {
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        return staffRepository.findBySaveGameId(saveGameId).stream()
                .filter(s -> s.getRole() == role)
                .filter(s -> !s.getClubId().equals(hiringClubId))
                .map(s -> new EmployedStaff(s.getId(), s.getName(),
                        clubNames.getOrDefault(s.getClubId(), "—"),
                        s.getPrimaryAttribute(), s.getSecondaryAttribute()))
                .toList();
    }
```

`ClubRepository.findBySaveGameId(UUID)` already exists (`ClubRepository.java:10`); import
`br.com.carvoeiromanager.club.Club` if it is not already imported in `StaffService`.

`getCandidates` and `hire` both start with `requireHirable(role);` after their ownership checks, and
both pass `namesAtClub(clubId)` and `poachTargets(saveGameId, clubId, role)` into `generate(...)`.

In `hire`, after `StaffCandidate chosen = candidates.get(candidateIndex);` and before the existing
delete, add:

```java
        // Poaching: the rival loses them. Deleted before the insert for the same flush-ordering
        // reason as the replace below — Hibernate runs pending inserts before pending deletes.
        if (chosen.sourceStaffId() != null) {
            staffRepository.deleteById(chosen.sourceStaffId());
        }
```

Update `toCandidateResponses` to pass `c.currentClubName()` as the last argument.

- [ ] **Step 7: Fix `StaffCandidateGeneratorTest`**

Every `generate(...)` call there needs the new final argument. Pass `List.of()` where the test is
about the free pool or newgens, and add:

```java
    @Test
    void aPoachableRivalIsOfferedWithTheirCurrentClub() {
        UUID staffId = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(
                SAVE_ID, CLUB_ID, StaffRole.OLHEIRO, LocalDate.of(2026, 1, 10), 3,
                Set.of(),
                List.of(new EmployedStaff(staffId, "Rival Contratado", "Avaí Futebol Clube", 16, 15)));

        assertThat(candidates).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("Rival Contratado");
            assertThat(c.sourceStaffId()).isEqualTo(staffId);
            assertThat(c.currentClubName()).isEqualTo("Avaí Futebol Clube");
        });
    }
```

Match the test class's existing field names for `SAVE_ID` / `CLUB_ID` / `generator`.

- [ ] **Step 8: Run the staff tests**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.staff.*"
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff backend/src/main/java/br/com/carvoeiromanager/club backend/src/test/java/br/com/carvoeiromanager/staff
git commit -m "feat: let the manager poach staff from rival clubs"
```

---

### Task 9: Backfill migration for existing careers

Without this, Task 6 silently drops every AI club in an in-progress save from the old 14.0 baseline to neutral.

**Files:**
- Create: `backend/scripts/gen-club-staff-backfill.mjs`
- Create: `backend/src/main/resources/db/migration/V50__club_staff.sql` (generated)
- Create: `backend/src/test/java/br/com/carvoeiromanager/career/ClubStaffBackfillIT.java`

**Interfaces:**
- Consumes: `club-staff.json` (Task 3).
- Produces: `V50__club_staff.sql`.

- [ ] **Step 1: Write the failing test**

The migration must be **executed**, not just inspected — a shape-only assertion would pass on SQL
that is syntactically fine and semantically wrong. Flyway cannot be re-run mid-test, but the
generated file can be replayed through `JdbcTemplate` against a career that has been rewound to its
pre-V50 state. PgJDBC executes semicolon-separated statements in a single `Statement.execute`, so
the whole file goes through in one call.

Create `backend/src/test/java/br/com/carvoeiromanager/career/ClubStaffBackfillIT.java`:

```java
package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the generated V50 backfill against a career rewound to its pre-V50 state (all staff
 * deleted), which is exactly what an in-progress save looked like before this slice. Proves the
 * three guards actually hold when the SQL runs, not merely that the text contains them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubStaffBackfillIT {

    @Autowired SaveGameService saveGameService;
    @Autowired StaffRepository staffRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private SaveGameResponse legacyCareer() {
        User user = User.create("backfill_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        // Rewind to the pre-V50 world: a career that never had a commission.
        jdbcTemplate.update("DELETE FROM staff WHERE save_game_id = ?", save.id());
        return save;
    }

    private void runBackfill() {
        try {
            jdbcTemplate.execute(
                    Files.readString(Path.of("src/main/resources/db/migration/V50__club_staff.sql")));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to replay V50__club_staff.sql", e);
        }
    }

    @Test
    void itFillsTheCommissionOfACareerThatNeverHadOne() {
        SaveGameResponse save = legacyCareer();

        runBackfill();

        assertThat(staffRepository.findBySaveGameId(save.id())).isNotEmpty();
    }

    @Test
    void theManagedClubNeverGainsAHeadCoach() {
        SaveGameResponse save = legacyCareer();

        runBackfill();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.TECNICO))
                .isEmpty();
    }

    @Test
    void aStaffMemberTheUserPaidForIsNeverOverwritten() {
        SaveGameResponse save = legacyCareer();
        staffRepository.save(Staff.create(save.id(), save.managedClubId(),
                StaffRole.PREPARADOR_FISICO, StaffQuality.EXCELENTE, "Contratado Do Usuário",
                new BigDecimal("80000.00"), 18, 17));

        runBackfill();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.PREPARADOR_FISICO))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo("Contratado Do Usuário"));
    }

    @Test
    void nobodyEndsUpEmployedAtTwoClubsAtOnce() {
        SaveGameResponse save = legacyCareer();
        // Someone the research places at a rival, but whom this save's user already hired.
        String alreadyHired = firstSeededRivalName();
        staffRepository.save(Staff.create(save.id(), save.managedClubId(), StaffRole.OLHEIRO,
                StaffQuality.BOM, alreadyHired, new BigDecimal("45000.00"), 13, 12));

        runBackfill();

        List<Staff> sameName = staffRepository.findBySaveGameId(save.id()).stream()
                .filter(s -> s.getName().equals(alreadyHired))
                .toList();
        assertThat(sameName).hasSize(1);
        assertThat(sameName.get(0).getClubId()).isEqualTo(save.managedClubId());
    }

    /** Any name the seed assigns to a club — the collision guard is name-based, role-agnostic. */
    private String firstSeededRivalName() {
        return new br.com.carvoeiromanager.seed.SeedLoader().loadClubStaff().stream()
                .flatMap(c -> c.staff().stream())
                .map(br.com.carvoeiromanager.seed.SeedStaff::name)
                .findFirst()
                .orElseThrow();
    }
}
```


- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.career.ClubStaffBackfillIT"
```

Expected: FAIL — `V50__club_staff.sql not found`.

- [ ] **Step 3: Write the generator script**

Create `backend/scripts/gen-club-staff-backfill.mjs`:

```javascript
#!/usr/bin/env node
// Generates the V50 club-staff backfill migration from seed/club-staff.json and seed/clubs.json.
//
//   node backend/scripts/gen-club-staff-backfill.mjs [migrationVersion]
//
// Existing careers were created before clubs had a commission; this carries the researched staff
// onto them. Three guards keep it safe (see the design spec §9):
//   (a) never fill a slot that already has someone — respects UNIQUE (club_id, role) and, more
//       importantly, never overwrites a hire the player paid for;
//   (b) never insert a person who already exists somewhere in that save — a pool professional the
//       player hired must not also appear in a rival's dugout;
//   (c) TECNICO only: skip the save's managed club, because there the head coach is the player.
//       Expressed as a SQL predicate, not resolved here: managed_club_id is per-save runtime state.

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const SEED_DIR = path.join(SCRIPT_DIR, "..", "src", "main", "resources", "seed");
const MIGRATION_DIR = path.join(SCRIPT_DIR, "..", "src", "main", "resources", "db", "migration");

const migrationVersion = process.argv[2] ? Number(process.argv[2]) : 50;
if (!Number.isInteger(migrationVersion) || migrationVersion <= 0) {
    throw new Error(`Invalid migration version: ${process.argv[2]}`);
}

const sqlString = (v) => `'${v.replace(/'/g, "''")}'`;

// Must stay identical to StaffQuality.forAverage / StaffQuality.monthlyWage.
function tierFor(primary, secondary) {
    const average = (primary + secondary) / 2;
    if (average >= 15) return { quality: "EXCELENTE", wage: "80000.00" };
    if (average >= 12) return { quality: "BOM", wage: "45000.00" };
    return { quality: "REGULAR", wage: "20000.00" };
}

const clubs = JSON.parse(readFileSync(path.join(SEED_DIR, "clubs.json"), "utf8")).clubPool;
const clubStaff = JSON.parse(readFileSync(path.join(SEED_DIR, "club-staff.json"), "utf8"));
const clubNameByShortName = new Map(clubs.map((c) => [c.shortName, c.name]));

const statements = [];
for (const entry of clubStaff) {
    const clubName = clubNameByShortName.get(entry.clubShortName);
    if (!clubName) {
        throw new Error(`Unknown club shortName in club-staff.json: ${entry.clubShortName}`);
    }
    for (const member of entry.staff) {
        const { quality, wage } = tierFor(member.primaryAttribute, member.secondaryAttribute);
        const managedGuard = member.role === "TECNICO"
            ? `\n  AND c.id IS DISTINCT FROM (SELECT g.managed_club_id FROM save_games g` +
              `\n                              WHERE g.id = c.save_game_id)`
            : "";
        statements.push(
            `INSERT INTO staff (id, save_game_id, club_id, role, quality, name, wage,\n` +
            `                   primary_attribute, secondary_attribute)\n` +
            `SELECT gen_random_uuid(), c.save_game_id, c.id, ${sqlString(member.role)}, ` +
            `${sqlString(quality)}, ${sqlString(member.name)}, ${wage}, ` +
            `${member.primaryAttribute}, ${member.secondaryAttribute}\n` +
            `FROM clubs c\n` +
            `WHERE c.name = ${sqlString(clubName)}\n` +
            `  AND NOT EXISTS (SELECT 1 FROM staff s\n` +
            `                   WHERE s.club_id = c.id AND s.role = ${sqlString(member.role)})\n` +
            `  AND NOT EXISTS (SELECT 1 FROM staff s\n` +
            `                   WHERE s.save_game_id = c.save_game_id AND s.name = ${sqlString(member.name)})` +
            `${managedGuard};`
        );
    }
}

const header =
    `-- GENERATED by backend/scripts/gen-club-staff-backfill.mjs from seed/club-staff.json — do not hand-edit.\n` +
    `--\n` +
    `-- Backfills each club's researched coaching staff onto EXISTING careers. New careers already\n` +
    `-- get this via SaveGameService.seedClubStaff. Without this migration, dropping the old flat\n` +
    `-- AI baseline would silently push every AI club in an in-progress save to neutral effects.\n` +
    `--\n` +
    `-- Guards: (a) never overwrite an occupied slot, (b) never duplicate someone already employed\n` +
    `-- in that save, (c) never give the managed club a head coach — that job is the player's.\n` +
    `--\n` +
    `-- ${statements.length} statement(s).\n\n`;

const target = path.join(MIGRATION_DIR, `V${migrationVersion}__club_staff.sql`);
writeFileSync(target, header + statements.join("\n\n") + "\n");
console.log(`Wrote ${statements.length} statement(s) to ${target}`);
```

- [ ] **Step 4: Generate the migration**

```bash
node backend/scripts/gen-club-staff-backfill.mjs
```

Expected: `Wrote N statement(s) to .../V50__club_staff.sql`. If another session already claimed V50,
re-run with the next free version number and rename the test's path accordingly.

- [ ] **Step 5: Run the tests**

```bash
cd backend && ./gradlew test --tests "br.com.carvoeiromanager.career.*"
```

Expected: PASS. `ClubStaffSeedingIT` also re-runs here, proving Flyway still applies cleanly from
scratch with V50 present.

- [ ] **Step 6: Commit**

```bash
git add backend/scripts backend/src/main/resources/db/migration backend/src/test/java/br/com/carvoeiromanager/career
git commit -m "feat: backfill club coaching staff onto existing careers"
```

---

### Task 10: Staff page shows the head coach and flags poaching

**Files:**
- Modify: `frontend/src/api/types.ts:742-765`
- Modify: `frontend/src/features/staff/StaffPage.tsx`
- Modify: `frontend/src/features/staff/StaffPage.test.tsx`

**Interfaces:**
- Consumes: the roster with `tecnico` (Task 5) and candidates with `currentClubName` (Task 8).
- Produces: the `StaffRole` union including `'TECNICO'`; `StaffMember.quality` and `.wage` widened to nullable; `StaffCandidate.currentClubName`.

- [ ] **Step 1: Write the failing test**

This file uses a no-argument `renderPage()` plus `mockGetStaffRoster` / `mockGetStaffCandidates`
set in `beforeEach`, and drives the UI with `fireEvent` and `within` (not `userEvent`). Match that.

**First**, every existing roster literal in the file — the `beforeEach` default and each
`mockHireStaff.mockResolvedValue({...})` — needs `tecnico: null` added, and every candidate literal
needs `currentClubName: null`. TypeScript fails the build otherwise. Then append:

```tsx
  it('shows the manager themselves in the head-coach slot, with nothing to hire', async () => {
    mockGetStaffRoster.mockResolvedValue({
      tecnico: { name: 'Filipe Linemburger', quality: null, wage: null, primaryAttribute: 0, secondaryAttribute: 0 },
      assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Filipe Linemburger'))
    expect(within(screen.getByTestId('staff-role-TECNICO')).queryByRole('button')).not.toBeInTheDocument()
  })

  it('marks an employed candidate with the club they would be poached from', async () => {
    mockGetStaffCandidates.mockResolvedValue([
      { index: 0, name: 'Livre da Silva', quality: 'BOM', wage: 45000, primaryAttribute: 13, secondaryAttribute: 12, currentClubName: null },
      { index: 1, name: 'Empregado Souza', quality: 'BOM', wage: 45000, primaryAttribute: 13, secondaryAttribute: 12, currentClubName: 'Avaí Futebol Clube' },
    ])
    renderPage()

    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))

    await waitFor(() => screen.getByText('Empregado Souza'))
    expect(screen.getByText('Livre')).toBeInTheDocument()
    expect(screen.getByText('Avaí Futebol Clube')).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/features/staff/StaffPage.test.tsx
```

Expected: FAIL — no `staff-role-TECNICO` element.

- [ ] **Step 3: Widen the frontend types**

In `frontend/src/api/types.ts`:

```ts
export type StaffRole = 'TECNICO' | 'ASSISTENTE' | 'PREPARADOR_FISICO' | 'OLHEIRO' | 'FISIOTERAPEUTA'
export type StaffQuality = 'REGULAR' | 'BOM' | 'EXCELENTE'

export type StaffMember = {
  name: string
  /** Null only for the managed club's head-coach slot — that entry is the player, not a hire. */
  quality: StaffQuality | null
  wage: number | null
  primaryAttribute: number
  secondaryAttribute: number
}

export type StaffRoster = {
  tecnico: StaffMember | null
  assistente: StaffMember | null
  preparadorFisico: StaffMember | null
  olheiro: StaffMember | null
  fisioterapeuta: StaffMember | null
}
```

And on `StaffCandidate`, add:

```ts
  /** Set when this candidate would have to be poached from another club. */
  currentClubName: string | null
```

Keep the existing `StaffMember` field list otherwise intact — only `quality` and `wage` become
nullable.

- [ ] **Step 4: Render the head coach and the poaching chip**

In `StaffPage.tsx`:

```tsx
const ROLE_LABEL: Record<StaffRole, string> = {
  TECNICO: 'Técnico',
  ASSISTENTE: 'Auxiliar Técnico',
  PREPARADOR_FISICO: 'Preparador Físico',
  OLHEIRO: 'Olheiro',
  FISIOTERAPEUTA: 'Fisioterapeuta',
}

const ATTRIBUTE_LABELS: Record<StaffRole, [string, string]> = {
  TECNICO: ['Trabalho Tático', 'Motivação'],
  ASSISTENTE: ['Conhecimento Tático', 'Avaliação de Jogadores'],
  PREPARADOR_FISICO: ['Preparação Física', 'Ciência Esportiva'],
  OLHEIRO: ['Conhecimento do Mercado', 'Negociação'],
  FISIOTERAPEUTA: ['Prevenção de Lesões', 'Reabilitação'],
}

/** The head coach leads the listing but is informational — the player holds that job themselves. */
const ROLES: StaffRole[] = ['TECNICO', 'ASSISTENTE', 'PREPARADOR_FISICO', 'OLHEIRO', 'FISIOTERAPEUTA']
```

Extend `holderFor` with `case 'TECNICO': return roster.data?.tecnico ?? null`.

In the card body, a null `quality` means "this is you" — render the name alone:

```tsx
                    {holder ? (
                      <Box>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                          <Typography sx={{ fontWeight: 600 }}>{holder.name}</Typography>
                          {holder.quality && <Chip size="small" label={QUALITY_LABEL[holder.quality]} />}
                          {holder.wage !== null && (
                            <Typography variant="body2" color="text.secondary">{formatBRL(holder.wage)}/mês</Typography>
                          )}
                        </Stack>
                        {holder.quality && (
                          <AttributeChips role={role} primary={holder.primaryAttribute} secondary={holder.secondaryAttribute} />
                        )}
                      </Box>
                    ) : (
                      <Typography color="text.secondary" sx={{ mt: 0.5 }}>Vaga em aberto</Typography>
                    )}
```

Guard the button so the head-coach card has none:

```tsx
                  {role !== 'TECNICO' && (
                    <Button variant="outlined" onClick={() => setBrowsingRole(role)}>
                      {holder ? 'Substituir' : 'Contratar'}
                    </Button>
                  )}
```

In the candidate dialog's `ListItemText` secondary block, add the origin chip above the attributes:

```tsx
                      <Chip
                        size="small"
                        label={c.currentClubName ?? 'Livre'}
                        sx={{ mb: 0.5, mr: 0.5 }}
                        color={c.currentClubName ? 'warning' : 'default'}
                      />
```

- [ ] **Step 5: Run test and type-check**

```bash
cd frontend && npx vitest run src/features/staff/StaffPage.test.tsx && npx tsc -b
```

Expected: PASS, no type errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/staff
git commit -m "feat(front): show the head coach and flag poachable candidates"
```

---

### Task 11: The rival club page shows its commission

Without this the research is invisible outside your own club.

**Files:**
- Modify: `frontend/src/features/club/ClubViewPage.tsx`
- Modify: `frontend/src/features/club/ClubViewPage.test.tsx`

**Interfaces:**
- Consumes: `getStaffRoster` (existing — already ownership-checked per club, so no new endpoint is needed) and the `StaffRole` / `StaffRoster` / `StaffMember` types widened in Task 10.

- [ ] **Step 1: Write the failing test**

`ClubViewPage.test.tsx` mocks `../../api/career` with an explicit factory listing every function the
page imports. Add `getStaffRoster: vi.fn()` to that factory (alongside `getSaveGame`, `getClubs`,
`getSquad`, `makeOffer`), add it to the `import { ... } from '../../api/career'` line below, and
declare `const mockGetStaffRoster = vi.mocked(getStaffRoster)`. Without the factory entry the page's
import is `undefined` at runtime and every existing test in the file breaks.

Give it a default in the file's `beforeEach` so the pre-existing tests keep passing:

```tsx
    mockGetStaffRoster.mockResolvedValue({
      tecnico: null, assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
```

Then append the test. `renderPage(clubId)` defaults to `OTHER_CLUB_ID` — the rival, which is the
case that matters here:

```tsx
  it("shows the club's coaching staff", async () => {
    mockGetStaffRoster.mockResolvedValue({
      tecnico: { name: 'Cléber Xavier', quality: 'BOM', wage: 45000, primaryAttribute: 14, secondaryAttribute: 13 },
      assistente: null,
      preparadorFisico: { name: 'Léo Cupertino', quality: 'EXCELENTE', wage: 80000, primaryAttribute: 17, secondaryAttribute: 16 },
      olheiro: null,
      fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Comissão Técnica'))
    expect(screen.getByText('Cléber Xavier')).toBeInTheDocument()
    expect(screen.getByText('Léo Cupertino')).toBeInTheDocument()
    // An unresearched slot is omitted, not rendered as an empty row.
    expect(screen.queryByText('Olheiro')).not.toBeInTheDocument()
  })
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/features/club/ClubViewPage.test.tsx
```

Expected: FAIL — "Comissão Técnica" not found.

- [ ] **Step 3: Render the commission**

In `ClubViewPage.tsx`, add the query alongside the existing ones:

```tsx
  const staffRoster = useQuery({
    queryKey: ['staffRoster', saveGameId, clubId],
    queryFn: () => getStaffRoster(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })
```

Reuse the same `queryKey` shape `StaffPage` uses so both share one cache entry.

Add a small presentational block after the squad table. Roles with no holder are skipped.

> **CORRECTED after implementation.** This step originally justified the skip with "an empty slot
> is real information about a small club" — the exact premise §3.2a of the spec refutes. An
> unfilled slot on an AI club records what the research could find, not what the club employs, so
> rendering "Vaga em aberto" on a rival's profile would assert something false. The behaviour
> below (skip) is right; only that reason was wrong, and it was removed from the shipped code
> comment in commit `924b6c3`.
>
> Two further deviations shipped deliberately. **(a)** The block below renders only the role label
> and the name; the shipped `Commission` also renders the quality chip, the wage and two attribute
> chips, guarded on the null fields so the managed club's head-coach slot stays a bare name — with
> tests for both. **(b)** The label maps below live in the components here, but shipped in
> `frontend/src/features/staff/staffLabels.ts`, shared by this screen and `StaffPage`; two copies
> that must agree is a divergence no test would catch, since each screen asserts against its own.
> The same applies to Task 10's copy of these maps.

```tsx
const COMMISSION_LABEL: Record<StaffRole, string> = {
  TECNICO: 'Técnico',
  ASSISTENTE: 'Auxiliar Técnico',
  PREPARADOR_FISICO: 'Preparador Físico',
  OLHEIRO: 'Olheiro',
  FISIOTERAPEUTA: 'Fisioterapeuta',
}

const COMMISSION_ORDER: StaffRole[] = [
  'TECNICO', 'ASSISTENTE', 'PREPARADOR_FISICO', 'OLHEIRO', 'FISIOTERAPEUTA',
]

function Commission({ roster }: { roster: StaffRoster | undefined }) {
  if (!roster) return null
  const holderFor = (role: StaffRole): StaffMember | null => ({
    TECNICO: roster.tecnico,
    ASSISTENTE: roster.assistente,
    PREPARADOR_FISICO: roster.preparadorFisico,
    OLHEIRO: roster.olheiro,
    FISIOTERAPEUTA: roster.fisioterapeuta,
  })[role]
  const filled = COMMISSION_ORDER.filter(r => holderFor(r) !== null)
  if (filled.length === 0) return null

  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>Comissão Técnica</Typography>
      <Stack spacing={0.5}>
        {filled.map(role => {
          const holder = holderFor(role)!
          return (
            <Stack key={role} direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
              <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>
                {COMMISSION_LABEL[role]}
              </Typography>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{holder.name}</Typography>
            </Stack>
          )
        })}
      </Stack>
    </Box>
  )
}
```

Render it with `<Commission roster={staffRoster.data} />`, and import `getStaffRoster` from
`../../api/career` plus `StaffRole`, `StaffRoster`, `StaffMember` from `../../api/types`.

- [ ] **Step 4: Run test and type-check**

```bash
cd frontend && npx vitest run src/features/club/ClubViewPage.test.tsx && npx tsc -b
```

Expected: PASS.

- [ ] **Step 5: Run the full suites**

```bash
cd frontend && npx vitest run
```

```bash
cd backend && ./gradlew test
```

Expected: PASS on both.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/club
git commit -m "feat(front): show a club's coaching staff on its profile"
```
