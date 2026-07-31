# Opponent Analysis + Staff Attributes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the eve of every managed-club fixture (news dated `matchDate − 1`), the Auxiliar Técnico publishes an opponent-analysis news item (standings, form, best players, tactical read, advice), powered by new 1–20 attributes on all 4 staff roles and a researched pool of real Brazilian professionals.

**Architecture:** Attributes become the mechanical truth behind `StaffEffectsService` (continuous linear mappings anchored at a=10 → today's REGULAR and a=17 → today's EXCELENTE values); the quality tier stays as the hiring/wage band. A new `MatchPreviewService` (season package) persists a `MATCH_PREVIEW` news item via the established `persistNewsItem` pattern, gated on an ASSISTENTE being hired, with depth gated by his two attributes. Candidates draw from a new `staff-pool.json` seed of real professionals before falling back to generated names.

**Tech Stack:** Java 21 Spring Boot + Flyway + Testcontainers ITs (backend), React + TypeScript + MUI + vitest (frontend).

**Reference spec:** `docs/superpowers/specs/2026-07-22-opponent-analysis-staff-attributes-design.md`

## Global Constraints

- Attribute range is **1–20**; tier midpoint backfill: `REGULAR → 10`, `BOM → 14`, `EXCELENTE → 17` (both columns).
- Tier bands for generated candidates: `REGULAR 8–12`, `BOM 12–16`, `EXCELENTE 15–19` (uniform, inclusive).
- Tier derivation from average `a`: `a ≥ 15 → EXCELENTE`, `a ≥ 12 → BOM`, else `REGULAR`.
- Continuous effects (a = (primary+secondary)/2.0; lerp anchored at a=10 and a=17):
  sponsor `1.0 + 0.10*(a−10)/7` clamp `[1.0, 1.12]`; training `round(2*(a−10)/7)` clamp `[0, 2]`;
  injury `1.0 − 0.30*(a−10)/7` clamp `[0.65, 1.0]`; wage-demand `1.0 − 0.10*(a−10)/7` clamp `[0.88, 1.0]`.
- AI (non-managed) clubs: no staff rows; effects computed at fixed `a = 14.0`. Managed club with no holder for a role: neutral effect (0 bonus / 1.0 multipliers) — unchanged behavior.
- Wages unchanged: REGULAR 20000.00 / BOM 45000.00 / EXCELENTE 80000.00.
- News type string is exactly `"MATCH_PREVIEW"` (13 chars, fits `VARCHAR(16)`); news `eventDate` and payload `date` are `matchDate.minusDays(1)`.
- **No auxiliar hired → no preview news at all.**
- Depth gates (auxiliar's individual attributes): always → standings + form; Avaliação de Jogadores (secondary): `<12` top-1 player, `12–15` top-3, `≥16` top-3 + topScorer; Conhecimento Tático (primary): `<12` nothing, `12–15` tactical read, `≥16` tactical read + insights.
- Attribute labels: ASSISTENTE = Conhecimento Tático / Avaliação de Jogadores; PREPARADOR_FISICO = Preparação Física / Ciência Esportiva; OLHEIRO = Conhecimento do Mercado / Negociação; FISIOTERAPEUTA = Prevenção de Lesões / Reabilitação. Labels live **only in the frontend map** (no backend consumer exists — YAGNI).
- All new `AdvanceResponse` payload fields nullable; callers null-guard (back-compat with persisted news rows).
- All UI copy in pt-BR.
- Verification commands: backend `cd backend && ./gradlew test` (use `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`; full suite ~10–12 min); frontend `cd frontend && npx tsc -b && npx vitest run` (`tsc --noEmit` is a no-op in this repo — never use it).
- Commit after each green step; never push without the user asking.

---

### Task 1: Staff attribute data layer (V34 + entity + candidates + DTOs)

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__staff_attributes.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/Staff.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffQuality.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidate.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java` (DTO mapping + `Staff.create` call)
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffServiceIT.java`

**Interfaces:**
- Consumes: existing `Staff`, `StaffQuality`, `StaffCandidateGenerator` (read them first).
- Produces (later tasks rely on): `Staff.create(UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality, String name, BigDecimal wage, int primaryAttribute, int secondaryAttribute)`; `Staff.getPrimaryAttribute()`, `Staff.getSecondaryAttribute()`, `Staff.attributeAverage()` (double); `StaffQuality.forAverage(double)`; `StaffCandidate(String name, StaffQuality quality, BigDecimal wage, int primaryAttribute, int secondaryAttribute)`; `StaffResponse(String name, String quality, BigDecimal wage, int primaryAttribute, int secondaryAttribute)`; `StaffCandidateResponse(int index, String name, String quality, BigDecimal wage, int primaryAttribute, int secondaryAttribute)`.

- [ ] **Step 1: Write the migration**

```sql
-- V34__staff_attributes.sql
-- CM-style 1-20 attributes per staff member; meaning is role-dependent (labels live in code).
-- Backfill from the quality tier midpoint so existing hires keep today's effect sizes.
ALTER TABLE staff ADD COLUMN primary_attribute INT;
ALTER TABLE staff ADD COLUMN secondary_attribute INT;

UPDATE staff SET
    primary_attribute = CASE quality
        WHEN 'REGULAR' THEN 10 WHEN 'BOM' THEN 14 WHEN 'EXCELENTE' THEN 17 END,
    secondary_attribute = CASE quality
        WHEN 'REGULAR' THEN 10 WHEN 'BOM' THEN 14 WHEN 'EXCELENTE' THEN 17 END;

ALTER TABLE staff ALTER COLUMN primary_attribute SET NOT NULL;
ALTER TABLE staff ALTER COLUMN secondary_attribute SET NOT NULL;
ALTER TABLE staff ADD CONSTRAINT staff_primary_attribute_range CHECK (primary_attribute BETWEEN 1 AND 20);
ALTER TABLE staff ADD CONSTRAINT staff_secondary_attribute_range CHECK (secondary_attribute BETWEEN 1 AND 20);
```

- [ ] **Step 2: Write failing tests**

Add to `StaffTest.java` (adjust the existing `create` test to the new signature):

```java
@Test
void createCarriesAttributes() {
    Staff staff = Staff.create(UUID.randomUUID(), UUID.randomUUID(), StaffRole.ASSISTENTE,
            StaffQuality.BOM, "Cléber Xavier", new BigDecimal("45000.00"), 16, 14);
    assertThat(staff.getPrimaryAttribute()).isEqualTo(16);
    assertThat(staff.getSecondaryAttribute()).isEqualTo(14);
    assertThat(staff.attributeAverage()).isEqualTo(15.0);
}

@Test
void qualityIsDerivedFromAttributeAverage() {
    assertThat(StaffQuality.forAverage(15.0)).isEqualTo(StaffQuality.EXCELENTE);
    assertThat(StaffQuality.forAverage(14.9)).isEqualTo(StaffQuality.BOM);
    assertThat(StaffQuality.forAverage(12.0)).isEqualTo(StaffQuality.BOM);
    assertThat(StaffQuality.forAverage(11.9)).isEqualTo(StaffQuality.REGULAR);
}
```

Add to `StaffCandidateGeneratorTest.java` (keep existing determinism tests, updating construction as needed):

```java
@Test
void candidateAttributesFallInsideTheTierBand() {
    List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
            StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 50);
    for (StaffCandidate c : candidates) {
        int lo = switch (c.quality()) { case REGULAR -> 8; case BOM -> 12; case EXCELENTE -> 15; };
        int hi = switch (c.quality()) { case REGULAR -> 12; case BOM -> 16; case EXCELENTE -> 19; };
        assertThat(c.primaryAttribute()).isBetween(lo, hi);
        assertThat(c.secondaryAttribute()).isBetween(lo, hi);
    }
}
```

(`generator`, `SAVE_ID`, `CLUB_ID` follow the fixtures already present in that test class — reuse them.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.staff.StaffTest' --tests 'br.com.carvoeiromanager.staff.StaffCandidateGeneratorTest'`
Expected: compilation failure (new signature/methods missing).

- [ ] **Step 4: Implement**

`StaffQuality.java`:

```java
package br.com.carvoeiromanager.staff;

/** How good a hired staff member is; the hiring/wage band around the 1-20 attributes. */
public enum StaffQuality {
    REGULAR, BOM, EXCELENTE;

    /** Tier implied by an attribute average: >=15 EXCELENTE, >=12 BOM, else REGULAR. */
    public static StaffQuality forAverage(double average) {
        if (average >= 15.0) {
            return EXCELENTE;
        }
        return average >= 12.0 ? BOM : REGULAR;
    }
}
```

`Staff.java` — add fields, extend the private constructor and `create`, add getters:

```java
    @Column(name = "primary_attribute", nullable = false)
    private int primaryAttribute;

    @Column(name = "secondary_attribute", nullable = false)
    private int secondaryAttribute;
```

```java
    public static Staff create(UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                               String name, BigDecimal wage, int primaryAttribute, int secondaryAttribute) {
        return new Staff(UUID.randomUUID(), saveGameId, clubId, role, quality, name, wage,
                primaryAttribute, secondaryAttribute);
    }

    public int getPrimaryAttribute() { return primaryAttribute; }
    public int getSecondaryAttribute() { return secondaryAttribute; }

    /** The single number the continuous staff effects are computed from. */
    public double attributeAverage() { return (primaryAttribute + secondaryAttribute) / 2.0; }
```

`StaffCandidate.java`:

```java
package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** One rolled, not-yet-hired staff candidate — never persisted. */
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage,
                             int primaryAttribute, int secondaryAttribute) {
}
```

`StaffCandidateGenerator.java` — roll attributes inside the tier band (same `rng` stream, attributes rolled right after the tier so determinism is per-candidate):

```java
        for (int i = 0; i < count; i++) {
            String name = firstNames.get(rng.nextInt(firstNames.size()))
                    + " " + lastNames.get(rng.nextInt(lastNames.size()));
            StaffQuality quality = tiers[rng.nextInt(tiers.length)];
            int primary = rollAttribute(quality, rng);
            int secondary = rollAttribute(quality, rng);
            candidates.add(new StaffCandidate(name, quality, wageFor(quality), primary, secondary));
        }
```

```java
    /** Uniform roll inside the tier's band: REGULAR 8-12, BOM 12-16, EXCELENTE 15-19. */
    private static int rollAttribute(StaffQuality quality, Random rng) {
        return switch (quality) {
            case REGULAR -> 8 + rng.nextInt(5);
            case BOM -> 12 + rng.nextInt(5);
            case EXCELENTE -> 15 + rng.nextInt(5);
        };
    }
```

`StaffResponse.java` / `StaffCandidateResponse.java`:

```java
public record StaffResponse(String name, String quality, BigDecimal wage,
                            int primaryAttribute, int secondaryAttribute) {
}
```

```java
public record StaffCandidateResponse(int index, String name, String quality, BigDecimal wage,
                                     int primaryAttribute, int secondaryAttribute) {
}
```

`StaffService.java` — update the two mapping helpers and the `hire` save:

```java
        staffRepository.save(Staff.create(saveGameId, clubId, role, chosen.quality(),
                chosen.name(), chosen.wage(), chosen.primaryAttribute(), chosen.secondaryAttribute()));
```

```java
    private StaffResponse toStaffResponse(Staff staff) {
        return staff == null ? null
                : new StaffResponse(staff.getName(), staff.getQuality().name(), staff.getWage(),
                        staff.getPrimaryAttribute(), staff.getSecondaryAttribute());
    }
```

```java
                    return new StaffCandidateResponse(i, c.name(), c.quality().name(), c.wage(),
                            c.primaryAttribute(), c.secondaryAttribute());
```

Fix any other compile errors from the record changes (e.g. `StaffServiceIT` constructions) by adding sensible in-band attribute arguments.

- [ ] **Step 5: Run the staff test classes**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.staff.*'`
Expected: PASS (IT classes need Docker running — they use Testcontainers).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V34__staff_attributes.sql backend/src/main/java/br/com/carvoeiromanager/staff backend/src/test/java/br/com/carvoeiromanager/staff
git commit -m "feat(staff): 1-20 attribute pair per staff member (V34, entity, candidates, DTOs)"
```

---

### Task 2: Continuous StaffEffectsService

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffEffectsServiceTest.java`

**Interfaces:**
- Consumes: `Staff.attributeAverage()` (Task 1).
- Produces: unchanged public signatures — `trainingBonusFor(UUID, boolean)`, `injuryRiskMultiplierFor(UUID, boolean)`, `wageDemandMultiplierFor(UUID, boolean)`, `sponsorshipMultiplierFor(UUID, boolean)` — so no consumer (`TrainingService`, `MatchOutcomeApplier`, `LiveMatchEngine`, `ContractService`, `FinanceService`) changes.

- [ ] **Step 1: Rewrite the test class as failing anchor tests**

Replace the tier-based expectations in `StaffEffectsServiceTest.java` with attribute-anchor ones. Keep the class's existing mock/fixture setup style (it mocks `StaffRepository`); stub `findByClubIdAndRole` to return a `Staff.create(...)` with the attribute pair under test.

```java
    private Staff staffWith(StaffRole role, int primary, int secondary) {
        return Staff.create(SAVE_ID, CLUB_ID, role, StaffQuality.forAverage((primary + secondary) / 2.0),
                "Profissional Teste", new BigDecimal("45000.00"), primary, secondary);
    }

    @Test
    void anchorAverageTenReproducesRegularValues() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staffWith(StaffRole.ASSISTENTE, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staffWith(StaffRole.PREPARADOR_FISICO, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staffWith(StaffRole.OLHEIRO, 10, 10)));

        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(0);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }

    @Test
    void anchorAverageSeventeenReproducesExcelenteValues() {
        // same stubbing shape with (17, 17)
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isCloseTo(1.10, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(2);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isCloseTo(0.70, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isCloseTo(0.90, within(1e-9));
    }

    @Test
    void eliteAveragesAreClampedAtTheCeilings() {
        // stub with (19, 19): lerp overshoots and every effect must clamp
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isCloseTo(1.12, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(2);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isCloseTo(0.65, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isCloseTo(0.88, within(1e-9));
    }

    @Test
    void aiClubsUseTheFixedBaselineAverageFourteen() {
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 + 0.10 * 4 / 7.0, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.30 * 4 / 7.0, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.10 * 4 / 7.0, within(1e-9));
        verifyNoInteractions(staffRepository);
    }

    @Test
    void managedClubWithoutTheRoleGetsNeutralEffects() {
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(0);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.staff.StaffEffectsServiceTest'`
Expected: FAIL (old tier-switch values).

- [ ] **Step 3: Implement the continuous mapping**

Replace the four private tier-switch mappers in `StaffEffectsService.java` with:

```java
    /** Fixed attribute average granted to every AI-controlled club (equivalent to the old flat BOM). */
    private static final double AI_BASELINE_AVERAGE = 14.0;

    public int trainingBonusFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.PREPARADOR_FISICO);
        if (a == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(2, Math.round(lerp(a, 0.0, 2.0))));
    }

    public double injuryRiskMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.FISIOTERAPEUTA);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 0.70), 0.65, 1.0);
    }

    public double wageDemandMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.OLHEIRO);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 0.90), 0.88, 1.0);
    }

    public double sponsorshipMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.ASSISTENTE);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 1.10), 1.0, 1.12);
    }

    /**
     * The effective attribute average for a role: the managed club's hired staff (or {@code null}
     * for a neutral effect when the role is unfilled), or the fixed AI baseline for every other club.
     */
    private Double averageFor(UUID clubId, boolean managed, StaffRole role) {
        if (!managed) {
            return AI_BASELINE_AVERAGE;
        }
        return staffRepository.findByClubIdAndRole(clubId, role)
                .map(Staff::attributeAverage)
                .orElse(null);
    }

    /** Linear map anchored at (10, atRegular) and (17, atExcelente) — today's tier endpoints. */
    private static double lerp(double average, double atRegular, double atExcelente) {
        return atRegular + (atExcelente - atRegular) * (average - 10.0) / 7.0;
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
```

Update the class Javadoc: the baseline is now "a fixed attribute average of 14 (equivalent to the old flat BOM)". Delete the now-unused `qualityFor` and the four tier-switch methods.

- [ ] **Step 4: Run and verify green, plus consumers still compile**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.staff.*' --tests 'br.com.carvoeiromanager.player.TrainingServiceTest'`
Expected: PASS. (If other tests assert exact old BOM-tier effect values — e.g. training/finance tests pinned to 1.05 — update them to the a=14 values from the Global Constraints formulas; a=14 gives sponsor ≈1.0571, training 1, injury ≈0.8286, wage ≈0.9429.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/staff/StaffEffectsService.java backend/src/test/java
git commit -m "feat(staff): continuous attribute-driven effects (anchored at old tier values)"
```

---

### Task 3: Staff pool seed + pool-first candidate generation

**Files:**
- Create: `backend/src/main/resources/seed/staff-pool.json`
- Create: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedStaff.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/seed/StaffPoolSeedTest.java` (new)
- Test: `backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java`

**Interfaces:**
- Consumes: `StaffQuality.forAverage` (Task 1), `SeedLoader` pattern (`loadFreeAgents`).
- Produces: `SeedStaff(String name, StaffRole role, int primaryAttribute, int secondaryAttribute)`; `SeedLoader.loadStaffPool()` → `List<SeedStaff>`; **new generator signature** `generate(UUID saveGameId, UUID clubId, StaffRole role, LocalDate onDate, int count, Set<String> excludedNames)`.

- [ ] **Step 1: Starter seed file**

`staff-pool.json` — a small starter set so the mechanics are testable now; Task 4's research replaces/expands it to 12–15 per role. Real, widely documented professionals for the two public roles; plausible authored names for the two obscure ones (also replaced by research):

```json
[
  { "name": "Cléber Xavier", "role": "ASSISTENTE", "primaryAttribute": 17, "secondaryAttribute": 16 },
  { "name": "Matheus Bachi", "role": "ASSISTENTE", "primaryAttribute": 14, "secondaryAttribute": 14 },
  { "name": "Fábio Mahseredjian", "role": "PREPARADOR_FISICO", "primaryAttribute": 17, "secondaryAttribute": 16 },
  { "name": "Anselmo Sbragia", "role": "PREPARADOR_FISICO", "primaryAttribute": 13, "secondaryAttribute": 13 },
  { "name": "Rodrigo Pastana", "role": "OLHEIRO", "primaryAttribute": 15, "secondaryAttribute": 14 },
  { "name": "Marcelo Segurado", "role": "OLHEIRO", "primaryAttribute": 12, "secondaryAttribute": 13 },
  { "name": "Luiz Rosan", "role": "FISIOTERAPEUTA", "primaryAttribute": 14, "secondaryAttribute": 15 },
  { "name": "Evandro Rogério", "role": "FISIOTERAPEUTA", "primaryAttribute": 12, "secondaryAttribute": 12 }
]
```

- [ ] **Step 2: Write failing tests**

`StaffPoolSeedTest.java` (plain JUnit, no Spring — mirrors the free-agents seed test style):

```java
package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StaffPoolSeedTest {

    private final List<SeedStaff> pool = new SeedLoader().loadStaffPool();

    @Test
    void everyRoleIsCovered() {
        Set<StaffRole> covered = pool.stream().map(SeedStaff::role).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrder(StaffRole.values());
    }

    @Test
    void attributesAreWithinBounds() {
        assertThat(pool).allSatisfy(s -> {
            assertThat(s.primaryAttribute()).isBetween(1, 20);
            assertThat(s.secondaryAttribute()).isBetween(1, 20);
        });
    }

    @Test
    void namesAreUniqueAndNonBlank() {
        assertThat(pool).allSatisfy(s -> assertThat(s.name()).isNotBlank());
        assertThat(pool.stream().map(SeedStaff::name).distinct().count()).isEqualTo(pool.size());
    }
}
```

Add to `StaffCandidateGeneratorTest.java`:

```java
    @Test
    void poolMembersAreOfferedFirstWithDerivedTierAndTierWage() {
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 2, Set.of());
        // The starter pool has exactly 2 ASSISTENTE entries, so both slots come from the pool.
        assertThat(candidates).extracting(StaffCandidate::name)
                .containsExactlyInAnyOrder("Cléber Xavier", "Matheus Bachi");
        StaffCandidate cleber = candidates.stream()
                .filter(c -> c.name().equals("Cléber Xavier")).findFirst().orElseThrow();
        assertThat(cleber.primaryAttribute()).isEqualTo(17);
        assertThat(cleber.secondaryAttribute()).isEqualTo(16);
        assertThat(cleber.quality()).isEqualTo(StaffQuality.EXCELENTE); // avg 16.5 >= 15
        assertThat(cleber.wage()).isEqualByComparingTo("80000.00");
    }

    @Test
    void excludedNamesAreNeverOfferedAndGeneratedNamesFillTheGap() {
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 3,
                Set.of("Cléber Xavier", "Matheus Bachi"));
        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(StaffCandidate::name)
                .doesNotContain("Cléber Xavier", "Matheus Bachi");
    }

    @Test
    void generationIsDeterministicForTheSameInputs() {
        List<StaffCandidate> a = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of());
        List<StaffCandidate> b = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of());
        assertThat(a).isEqualTo(b);
    }
```

Update every pre-existing call to `generator.generate(...)` in the test class to pass a trailing `Set.of()`.

- [ ] **Step 3: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.StaffPoolSeedTest' --tests 'br.com.carvoeiromanager.staff.StaffCandidateGeneratorTest'`
Expected: compilation failure (`SeedStaff`, `loadStaffPool`, new signature missing).

- [ ] **Step 4: Implement**

`SeedStaff.java`:

```java
package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.staff.StaffRole;

/** One hand-authored real professional in the staff hiring pool (seed/staff-pool.json). */
public record SeedStaff(String name, StaffRole role, int primaryAttribute, int secondaryAttribute) {
}
```

`SeedLoader.java` — add:

```java
    /** The real, hand-authored technical-staff hiring pool; candidates draw from it before
     *  falling back to generated names. */
    public List<SeedStaff> loadStaffPool() {
        try (InputStream in = new ClassPathResource("seed/staff-pool.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedStaff>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load staff pool seed", e);
        }
    }
```

`StaffCandidateGenerator.java` — full new shape:

```java
package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedStaff;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rolls staff candidates on demand — never persisted. Real professionals from the seed pool
 * are offered first (their authored attributes fixed, tier derived from the average, wage by
 * tier); when the role's unhired pool runs dry the remaining slots are filled with generated
 * newgen names whose attributes are rolled inside the tier band. Deterministic for a given
 * (saveGameId, clubId, role, onDate, excludedNames): re-browsing the same role on the same
 * day always shows the same candidates.
 */
@Component
public class StaffCandidateGenerator {

    private final List<String> firstNames;
    private final List<String> lastNames;
    private final Map<StaffRole, List<SeedStaff>> poolByRole;

    public StaffCandidateGenerator(SeedLoader seedLoader) {
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
        this.poolByRole = seedLoader.loadStaffPool().stream()
                .collect(Collectors.groupingBy(SeedStaff::role));
    }

    public List<StaffCandidate> generate(UUID saveGameId, UUID clubId, StaffRole role,
                                         LocalDate onDate, int count, Set<String> excludedNames) {
        long seed = saveGameId.getMostSignificantBits()
                ^ clubId.getMostSignificantBits()
                ^ role.ordinal()
                ^ onDate.toEpochDay();
        Random rng = new Random(seed);

        List<StaffCandidate> candidates = new ArrayList<>();

        List<SeedStaff> available = poolByRole.getOrDefault(role, List.of()).stream()
                .filter(s -> !excludedNames.contains(s.name()))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(available, rng);
        for (int i = 0; i < count && i < available.size(); i++) {
            SeedStaff pro = available.get(i);
            double average = (pro.primaryAttribute() + pro.secondaryAttribute()) / 2.0;
            StaffQuality quality = StaffQuality.forAverage(average);
            candidates.add(new StaffCandidate(pro.name(), quality, wageFor(quality),
                    pro.primaryAttribute(), pro.secondaryAttribute()));
        }

        StaffQuality[] tiers = StaffQuality.values();
        while (candidates.size() < count) {
            String name = firstNames.get(rng.nextInt(firstNames.size()))
                    + " " + lastNames.get(rng.nextInt(lastNames.size()));
            StaffQuality quality = tiers[rng.nextInt(tiers.length)];
            int primary = rollAttribute(quality, rng);
            int secondary = rollAttribute(quality, rng);
            candidates.add(new StaffCandidate(name, quality, wageFor(quality), primary, secondary));
        }
        return candidates;
    }

    /** Uniform roll inside the tier's band: REGULAR 8-12, BOM 12-16, EXCELENTE 15-19. */
    private static int rollAttribute(StaffQuality quality, Random rng) {
        return switch (quality) {
            case REGULAR -> 8 + rng.nextInt(5);
            case BOM -> 12 + rng.nextInt(5);
            case EXCELENTE -> 15 + rng.nextInt(5);
        };
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

`StaffService.java` — both `getCandidates` and `hire` build the exclusion set from the save's existing hires (a person holds one job per save, and the current holder of any role is never re-offered):

```java
        Set<String> hiredNames = staffRepository.findBySaveGameId(saveGameId).stream()
                .map(Staff::getName)
                .collect(Collectors.toSet());
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT, hiredNames);
```

(add `import java.util.Set;` — apply the same three lines in both methods).

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.*' --tests 'br.com.carvoeiromanager.staff.*'`
Expected: PASS.

```bash
git add backend/src/main/resources/seed/staff-pool.json backend/src/main/java/br/com/carvoeiromanager/seed backend/src/main/java/br/com/carvoeiromanager/staff backend/src/test/java
git commit -m "feat(staff): real-professionals pool feeds candidate generation (pool-first, no-rehire)"
```

---

### Task 4: Real-professionals research batch (parallel) + pool expansion

**Files:**
- Modify: `backend/src/main/resources/seed/staff-pool.json` (replace with the researched pool)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/StaffPoolSeedTest.java` (raise threshold)

**Interfaces:**
- Consumes: `SeedStaff` JSON shape from Task 3.
- Produces: final pool with **12–15 entries per role** (48–60 total).

**This task is research, not code.** At execution time the controller dispatches **4 parallel research subagents** (one per role) with web search, following the project's roster/free-agents research pattern. Each researcher gets this brief (fill in the role):

> Research 12–15 real Brazilian football professionals for the role **{ROLE}** ({pt-BR label}) who are plausible hires for a Série B / Campeonato Catarinense club in 2026: currently or recently active in Brazilian football, publicly documented in the press or club announcements (club sites, ge.globo, transfer news). Prefer professionals NOT currently at a Série A giant (they must be plausible at this level); long-serving/renowned names are fine as the rare elite options. For each person output one JSON object `{ "name": "...", "role": "{ROLE}", "primaryAttribute": N, "secondaryAttribute": N }` with attributes 1–20 authored from their documented career level: top-flight/renowned background 15–19, established professional 12–15, journeyman/lower-division 10–13. Attribute meanings: ASSISTENTE = Conhecimento Tático / Avaliação de Jogadores; PREPARADOR_FISICO = Preparação Física / Ciência Esportiva; OLHEIRO = Conhecimento do Mercado / Negociação; FISIOTERAPEUTA = Prevenção de Lesões / Reabilitação. Use only public professional information (name, role, career); the ratings are invented game values, same precedent as the game's player ratings. For OLHEIRO and FISIOTERAPEUTA, real named individuals are scarce in the press — fill whatever research cannot support with plausible invented Brazilian names (mark which are invented in your notes, not in the JSON). Names must be unique within your list. Return ONLY the JSON array.

- [ ] **Step 1: Dispatch the 4 researchers in parallel; save each fragment to the scratchpad.**
- [ ] **Step 2: Merge the 4 fragments into `staff-pool.json`** (single array, roles grouped, keep the file pretty-printed with 2-space indent like the starter). Drop cross-role duplicate names if any (keep the first).
- [ ] **Step 3: Raise the seed-test threshold**

Add to `StaffPoolSeedTest.java`:

```java
    @Test
    void everyRoleHasAtLeastTwelveEntries() {
        Map<StaffRole, Long> byRole = pool.stream()
                .collect(Collectors.groupingBy(SeedStaff::role, Collectors.counting()));
        assertThat(byRole).allSatisfy((role, n) -> assertThat(n).isGreaterThanOrEqualTo(12L));
    }
```

- [ ] **Step 4: Run seed + staff tests**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.*' --tests 'br.com.carvoeiromanager.staff.*'`
Expected: PASS. (Task 3's `poolMembersAreOfferedFirstWithDerivedTierAndTierWage` test pins the two starter ASSISTENTE names — if the researched pool changed their attributes or dropped one, update that test to two names that ARE in the final pool, keeping the same assertions on derived tier and wage.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/seed/staff-pool.json backend/src/test/java/br/com/carvoeiromanager/seed/StaffPoolSeedTest.java backend/src/test/java/br/com/carvoeiromanager/staff/StaffCandidateGeneratorTest.java
git commit -m "feat(staff): researched real-professionals pool (12-15 per role)"
```

---

### Task 5: MatchPreviewData payload + insights composer + repository finders

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/MatchPreviewData.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/MatchPreviewInsights.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/NewsItemRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonEventRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java` (4 constructor call sites gain a trailing `null`)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java`, `NewsReadIT.java` (constructor call sites)
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/MatchPreviewInsightsTest.java` (new)

**Interfaces:**
- Produces (Task 6 relies on):
  - `MatchPreviewData` record (below) with nested `StandingSnapshot`, `PreviewPlayer`, `PreviewTopScorer`, `TacticalRead`.
  - `AdvanceResponse` 17th component `MatchPreviewData matchPreview`.
  - `NewsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(UUID saveGameId, String type)` → `NewsItem`.
  - `SeasonEventRepository.findFirstBySaveGameIdAndProcessedFalseAndTypeOrderByEventDateAscSequenceAsc(UUID saveGameId, SeasonEventType type)` → `SeasonEvent`.
  - `MatchPreviewInsights.compose(long seed, String mentality, String pressing, String marking, int overallDiff, boolean home)` → `List<String>` (exactly 3 sentences).

- [ ] **Step 1: The payload record**

`MatchPreviewData.java`:

```java
package br.com.carvoeiromanager.season;

import java.util.List;

/**
 * The Auxiliar Técnico's pre-match opponent analysis, carried by a {@code MATCH_PREVIEW}
 * news item dated the eve of the fixture. Sections beyond the standings/form basics are
 * present only when the auxiliar's attributes unlock them (server-side depth gating):
 * {@code bestPlayers} size and {@code topScorer} depend on Avaliação de Jogadores;
 * {@code tacticalRead} and {@code insights} depend on Conhecimento Tático — absent
 * sections are {@code null} (or a shorter list) and the frontend renders only what exists.
 */
public record MatchPreviewData(
        String fixtureId,
        int round,
        String matchDate,
        boolean home,
        String stadiumName,
        String opponentClubId,
        String opponentName,
        String opponentShortName,
        String assistantName,
        StandingSnapshot opponentStanding,
        StandingSnapshot managedStanding,
        List<String> form,
        List<PreviewPlayer> bestPlayers,
        PreviewTopScorer topScorer,
        TacticalRead tacticalRead,
        List<String> insights) {

    /** One club's line in the standings comparison; {@code position} is 1-based. */
    public record StandingSnapshot(int position, int points, int played, int won, int drawn,
                                   int lost, int goalsFor, int goalsAgainst) {
    }

    public record PreviewPlayer(String name, String position, int overall) {
    }

    public record PreviewTopScorer(String name, int goals) {
    }

    public record TacticalRead(String formation, String mentality, String pressing, String marking) {
    }
}
```

- [ ] **Step 2: Extend `AdvanceResponse`**

Add a 17th component + Javadoc line (after `playerAlerts`):

```java
        List<PlayerAlertResponse> playerAlerts,
        MatchPreviewData matchPreview) {
```

Javadoc addition: `{@code matchPreview} — the Auxiliar Técnico's opponent analysis; only on MATCH_PREVIEW items, null everywhere else and on payloads persisted before the feature existed — callers must null-guard.`

Then append `, null` to the four `new AdvanceResponse(` calls in `SeasonAdvanceService.java` (lines ~135, ~218, ~286, ~308) and to the constructions in `NewsFeedIT.java` and `NewsReadIT.java`.

- [ ] **Step 3: Repository finders**

`NewsItemRepository.java`, add:

```java
    NewsItem findFirstBySaveGameIdAndTypeOrderBySeqDesc(UUID saveGameId, String type);
```

`SeasonEventRepository.java`, add:

```java
    SeasonEvent findFirstBySaveGameIdAndProcessedFalseAndTypeOrderByEventDateAscSequenceAsc(
            UUID saveGameId, SeasonEventType type);
```

- [ ] **Step 4: Write failing insights tests**

`MatchPreviewInsightsTest.java`:

```java
package br.com.carvoeiromanager.season;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPreviewInsightsTest {

    @Test
    void composesExactlyThreeSentences() {
        List<String> insights = MatchPreviewInsights.compose(42L, "BALANCED", "MEDIUM", "ZONAL", 0, true);
        assertThat(insights).hasSize(3);
        assertThat(insights).allSatisfy(s -> assertThat(s).isNotBlank());
    }

    @Test
    void isDeterministicForTheSameSeed() {
        List<String> a = MatchPreviewInsights.compose(7L, "ATTACKING", "HIGH", "MAN_TIGHT", 4, false);
        List<String> b = MatchPreviewInsights.compose(7L, "ATTACKING", "HIGH", "MAN_TIGHT", 4, false);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void highPressingOpponentYieldsCounterAdvice() {
        List<String> insights = MatchPreviewInsights.compose(1L, "BALANCED", "HIGH", "ZONAL", 0, true);
        assertThat(insights.get(0)).containsAnyOf("costas da", "atrás da zaga");
    }

    @Test
    void strongerOpponentYieldsUnderdogTone() {
        List<String> insights = MatchPreviewInsights.compose(1L, "BALANCED", "MEDIUM", "ZONAL", 5, false);
        assertThat(insights.get(2)).containsAnyOf("favoritos", "superior");
    }

    @Test
    void unknownTacticalValuesFallBackToBalancedAdvice() {
        List<String> insights = MatchPreviewInsights.compose(3L, null, null, null, 0, false);
        assertThat(insights).hasSize(3);
    }
}
```

- [ ] **Step 5: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.season.MatchPreviewInsightsTest'`
Expected: compilation failure (`MatchPreviewInsights` missing).

- [ ] **Step 6: Implement the composer**

`MatchPreviewInsights.java`:

```java
package br.com.carvoeiromanager.season;

import java.util.List;
import java.util.Random;

/**
 * Composes the Auxiliar Técnico's 3 tactical-advice sentences from the opponent's tactical
 * identity, the squad-strength gap and the venue. Pure and deterministic per seed (the
 * fixture id), in a professional assistant's voice — intentionally NOT the Mastella
 * commentary tone. Always exactly 3 lines: tactical counter, marking/space, strength/venue.
 */
final class MatchPreviewInsights {

    private MatchPreviewInsights() {
    }

    /**
     * @param overallDiff opponent average overall minus managed average overall
     *                    (positive = they are stronger)
     * @param home        whether the managed club plays at home
     */
    static List<String> compose(long seed, String mentality, String pressing, String marking,
                                int overallDiff, boolean home) {
        Random rng = new Random(seed);
        return List.of(
                pick(rng, tacticalLine(mentality, pressing)),
                pick(rng, markingLine(marking)),
                pick(rng, strengthLine(overallDiff, home)));
    }

    private static String pick(Random rng, String[] variants) {
        return variants[rng.nextInt(variants.length)];
    }

    private static String[] tacticalLine(String mentality, String pressing) {
        if ("HIGH".equals(pressing)) {
            return new String[] {
                    "Eles pressionam a saída de bola — bolas longas nas costas da linha de defesa podem render boas chances.",
                    "A marcação alta deles abre espaço atrás da zaga; velocidade no ataque será decisiva.",
            };
        }
        if ("LOW".equals(pressing)) {
            return new String[] {
                    "Eles recuam e fecham os espaços — precisaremos de paciência na troca de passes para desmontar o bloco baixo.",
                    "Com o bloco baixo deles, chutes de média distância e jogadas de bola parada ganham importância.",
            };
        }
        if ("ATTACKING".equals(mentality)) {
            return new String[] {
                    "Time que se lança ao ataque e deixa espaços — contra-ataques rápidos podem castigá-los.",
                    "Eles arriscam muito à frente; transições rápidas serão nossa melhor arma.",
            };
        }
        if ("DEFENSIVE".equals(mentality)) {
            return new String[] {
                    "Adversário fechado e reativo — precisaremos circular a bola e evitar contra-ataques bobos.",
                    "Eles apostam no erro do rival; atenção total na saída de bola.",
            };
        }
        return new String[] {
                "Equipe equilibrada, sem excessos — o jogo deve se decidir nos detalhes e na bola parada.",
                "Não dão espaços de graça; intensidade desde o primeiro minuto fará diferença.",
        };
    }

    private static String[] markingLine(String marking) {
        if ("MAN_TIGHT".equals(marking)) {
            return new String[] {
                    "A marcação individual deles pode ser explorada com movimentação e trocas de posição.",
                    "Contra marcação homem a homem, tabelas curtas e infiltrações quebram linhas.",
            };
        }
        return new String[] {
                "Na marcação por zona deles, inversões rápidas de jogo criam superioridade nos lados.",
                "Ataques pelos corredores forçam a zona deles a se deslocar e abrir espaços.",
        };
    }

    private static String[] strengthLine(int overallDiff, boolean home) {
        if (overallDiff >= 3) {
            return new String[] {
                    "No papel eles são favoritos — organização defensiva e eficiência nas poucas chances serão fundamentais.",
                    "Elenco superior ao nosso; um jogo disciplinado pode surpreendê-los.",
            };
        }
        if (overallDiff <= -3) {
            return new String[] {
                    "Somos favoritos, mas não podemos subestimá-los — vamos propor o jogo desde o início.",
                    "Temos elenco superior; pressão alta desde cedo pode resolver o jogo.",
            };
        }
        if (home) {
            return new String[] {
                    "Jogando em casa, nossa torcida deve pesar — vamos impor o ritmo desde o apito inicial.",
                    "Equilíbrio no papel; o mando de campo é a nossa vantagem — usar a pressão da torcida.",
            };
        }
        return new String[] {
                "Fora de casa, equilíbrio emocional e compactação nos primeiros minutos serão essenciais.",
                "Jogo parelho fora de casa — segurar o ímpeto inicial deles e crescer no jogo.",
        };
    }
}
```

- [ ] **Step 7: Run and commit**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.season.MatchPreviewInsightsTest' --tests 'br.com.carvoeiromanager.season.NewsFeedIT' --tests 'br.com.carvoeiromanager.season.NewsReadIT'`
Expected: PASS.

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season backend/src/test/java/br/com/carvoeiromanager/season
git commit -m "feat(news): MATCH_PREVIEW payload record, insights composer, repository finders"
```

---

### Task 6: MatchPreviewService + call-site wiring

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/MatchPreviewService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java` (2 hooks)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java` (1 hook)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffService.java` (1 hook)
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/MatchPreviewServiceIT.java` (new)

**Interfaces:**
- Consumes: everything from Task 5; `StaffRepository.findByClubIdAndRole`; `StandingsCalculator.compute(List<Club>, List<Fixture>)`; `PlayerStatsService.aggregateCompetition(UUID saveGameId, UUID competitionId)` → `Map<UUID, CompetitionPlayerAggregate>`; `PlayerRepository.findByClubIdAndYouthProspectFalse(UUID)`; `Club.getDefaultFormation()/getDefaultMentality()/getDefaultPressing()/getDefaultMarking()/getStadiumName()/getShortName()/getCommonName()/getName()`.
- Produces: `MatchPreviewService.maybePersistPreview(UUID saveGameId)` (public, self-guarding, `@Transactional`).

- [ ] **Step 1: Write failing ITs**

`MatchPreviewServiceIT.java` — follow the repo's existing `@SpringBootTest` + Testcontainers IT conventions (copy the harness/setup style from `StaffServiceIT` / `NewsFeedIT`: career creation via the same helpers those tests use). The scenarios:

```java
    @Test
    void noAssistantMeansNoPreview() {
        // fresh career, no staff hired
        matchPreviewService.maybePersistPreview(saveGameId);
        assertThat(newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, "MATCH_PREVIEW"))
                .isNull();
    }

    @Test
    void previewIsPersistedDatedTheEveOfTheMatchWithByline() {
        hireAssistant(18, 18); // helper: staffRepository.save(Staff.create(saveGameId, managedClubId,
                               //   StaffRole.ASSISTENTE, StaffQuality.EXCELENTE, "Auxiliar Teste",
                               //   new BigDecimal("80000.00"), 18, 18))
        matchPreviewService.maybePersistPreview(saveGameId);

        NewsItem item = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, "MATCH_PREVIEW");
        assertThat(item).isNotNull();
        AdvanceResponse payload = deserialize(item); // objectMapper.readValue(item.getPayload(), AdvanceResponse.class)
        assertThat(payload.type()).isEqualTo("MATCH_PREVIEW");
        MatchPreviewData preview = payload.matchPreview();
        assertThat(preview).isNotNull();
        assertThat(preview.assistantName()).isEqualTo("Auxiliar Teste");
        assertThat(preview.opponentName()).isNotBlank();
        assertThat(preview.opponentStanding()).isNotNull();
        assertThat(preview.managedStanding()).isNotNull();
        // eventDate is the eve of the fixture
        LocalDate matchDate = LocalDate.parse(preview.matchDate());
        assertThat(item.getEventDate()).isEqualTo(matchDate.minusDays(1));
        assertThat(payload.date()).isEqualTo(matchDate.minusDays(1).toString());
        // full depth at 18/18: top-3 players, tactical read and 3 insights present
        assertThat(preview.bestPlayers()).hasSize(3);
        assertThat(preview.tacticalRead()).isNotNull();
        assertThat(preview.insights()).hasSize(3);
    }

    @Test
    void secondCallForTheSameFixtureIsIdempotent() {
        hireAssistant(18, 18);
        matchPreviewService.maybePersistPreview(saveGameId);
        matchPreviewService.maybePersistPreview(saveGameId);
        long previews = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId).stream()
                .filter(n -> n.getType().equals("MATCH_PREVIEW")).count();
        assertThat(previews).isEqualTo(1);
    }

    @Test
    void weakAssistantGetsOnlyTheBasics() {
        hireAssistant(10, 10); // CT 10 (<12): no tactical read/insights; AJ 10 (<12): single player
        matchPreviewService.maybePersistPreview(saveGameId);
        MatchPreviewData preview = latestPreview(); // helper: deserialize + .matchPreview()
        assertThat(preview.bestPlayers()).hasSize(1);
        assertThat(preview.topScorer()).isNull();
        assertThat(preview.tacticalRead()).isNull();
        assertThat(preview.insights()).isNull();
    }

    @Test
    void midAssistantGetsTacticalReadButNoInsights() {
        hireAssistant(13, 13); // CT 13 (12-15): read only; AJ 13: top-3, no topScorer
        matchPreviewService.maybePersistPreview(saveGameId);
        MatchPreviewData preview = latestPreview();
        assertThat(preview.bestPlayers()).hasSize(3);
        assertThat(preview.topScorer()).isNull();
        assertThat(preview.tacticalRead()).isNotNull();
        assertThat(preview.insights()).isNull();
    }

    @Test
    void hiringTheAssistantThroughTheServiceDeliversThePendingPreview() {
        // hire via StaffService.hire(...) with a real candidate index and assert the
        // MATCH_PREVIEW item exists afterwards (the hire hook fires)
    }

    @Test
    void advancePersistsThePreviewForTheFollowingRound() {
        hireAssistant(18, 18);
        seasonAdvanceService.advance(saveGameId, userId); // processes the first event
        NewsItem item = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, "MATCH_PREVIEW");
        assertThat(item).isNotNull(); // preview exists for the next managed fixture
    }
```

(Adapt helper names to the harness actually present in the neighboring ITs — the assertions above are the contract.)

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.season.MatchPreviewServiceIT'`
Expected: compilation failure (`MatchPreviewService` missing).

- [ ] **Step 3: Implement the service**

`MatchPreviewService.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.stats.PlayerStatsService;
import br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists the Auxiliar Técnico's pre-match opponent analysis as a {@code MATCH_PREVIEW}
 * news item dated the eve of the managed club's next fixture. Self-guarding — callers
 * invoke it unconditionally after every advance, at rollover, and on assistant hire:
 * <ul>
 *   <li>no upcoming managed-club MATCH event → no-op;</li>
 *   <li>no ASSISTENTE hired → no-op (the hiring incentive);</li>
 *   <li>a preview for this fixture already exists → no-op (previews are written in fixture
 *       order, so checking the latest one is sufficient).</li>
 * </ul>
 * Analysis depth is gated server-side by the assistant's two attributes (see
 * {@link MatchPreviewData}); the basics (standings + form) are always included.
 */
@Service
public class MatchPreviewService {

    static final String NEWS_TYPE = "MATCH_PREVIEW";
    private static final int TIER_FULL = 16;
    private static final int TIER_MID = 12;

    private final SaveGameRepository saveGameRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final StaffRepository staffRepository;
    private final PlayerStatsService playerStatsService;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;
    private final StandingsCalculator standingsCalculator = new StandingsCalculator();

    public MatchPreviewService(SaveGameRepository saveGameRepository,
                               SeasonEventRepository seasonEventRepository,
                               FixtureRepository fixtureRepository,
                               ClubRepository clubRepository,
                               PlayerRepository playerRepository,
                               StaffRepository staffRepository,
                               PlayerStatsService playerStatsService,
                               NewsItemRepository newsItemRepository,
                               ObjectMapper objectMapper) {
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.staffRepository = staffRepository;
        this.playerStatsService = playerStatsService;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void maybePersistPreview(UUID saveGameId) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElse(null);
        if (save == null) {
            return;
        }
        UUID managedClubId = save.getManagedClubId();

        SeasonEvent nextMatch = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseAndTypeOrderByEventDateAscSequenceAsc(
                        saveGameId, SeasonEventType.MATCH);
        if (nextMatch == null) {
            return;
        }
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, nextMatch.getCompetitionId(), nextMatch.getRound())
                .stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .findFirst()
                .orElse(null);
        if (fixture == null) {
            return;
        }
        Staff assistant = staffRepository.findByClubIdAndRole(managedClubId, StaffRole.ASSISTENTE)
                .orElse(null);
        if (assistant == null) {
            return;
        }
        if (alreadyPreviewed(saveGameId, fixture.getId())) {
            return;
        }

        MatchPreviewData preview = buildPreview(save, nextMatch, fixture, assistant);
        persist(saveGameId, fixture.getMatchDate().minusDays(1), preview);
    }

    private boolean alreadyPreviewed(UUID saveGameId, UUID fixtureId) {
        NewsItem last = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, NEWS_TYPE);
        if (last == null) {
            return false;
        }
        try {
            AdvanceResponse payload = objectMapper.readValue(last.getPayload(), AdvanceResponse.class);
            return payload.matchPreview() != null
                    && Objects.equals(payload.matchPreview().fixtureId(), fixtureId.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private MatchPreviewData buildPreview(SaveGame save, SeasonEvent event, Fixture fixture, Staff assistant) {
        UUID managedClubId = save.getManagedClubId();
        boolean home = managedClubId.equals(fixture.getHomeClubId());
        UUID opponentId = home ? fixture.getAwayClubId() : fixture.getHomeClubId();
        Club opponent = clubRepository.findById(opponentId).orElseThrow();
        Club venueClub = clubRepository.findById(fixture.getHomeClubId()).orElseThrow();

        List<Fixture> competitionFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(save.getId(), event.getCompetitionId());
        List<Fixture> played = competitionFixtures.stream().filter(Fixture::isPlayed).toList();
        List<UUID> clubIds = competitionFixtures.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .distinct()
                .toList();
        List<Club> clubs = clubRepository.findAllById(clubIds);
        List<StandingRow> table = standingsCalculator.compute(clubs, played);

        int conhecimentoTatico = assistant.getPrimaryAttribute();
        int avaliacaoDeJogadores = assistant.getSecondaryAttribute();

        List<Player> opponentPlayers = playerRepository.findByClubIdAndYouthProspectFalse(opponentId).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getName))
                .toList();
        int playerCount = avaliacaoDeJogadores >= TIER_MID ? 3 : 1;
        List<MatchPreviewData.PreviewPlayer> bestPlayers = opponentPlayers.stream()
                .limit(playerCount)
                .map(p -> new MatchPreviewData.PreviewPlayer(p.getName(), p.getPosition().name(), p.getOverall()))
                .toList();

        MatchPreviewData.PreviewTopScorer topScorer = avaliacaoDeJogadores >= TIER_FULL
                ? topScorerFor(save.getId(), event.getCompetitionId(), opponentId)
                : null;

        MatchPreviewData.TacticalRead tacticalRead = conhecimentoTatico >= TIER_MID
                ? new MatchPreviewData.TacticalRead(opponent.getDefaultFormation(),
                        opponent.getDefaultMentality(), opponent.getDefaultPressing(), opponent.getDefaultMarking())
                : null;

        List<String> insights = null;
        if (conhecimentoTatico >= TIER_FULL) {
            int opponentAvg = averageOverall(opponentPlayers);
            int managedAvg = averageOverall(playerRepository.findByClubIdAndYouthProspectFalse(managedClubId));
            long seed = fixture.getId().getMostSignificantBits() ^ fixture.getId().getLeastSignificantBits();
            insights = MatchPreviewInsights.compose(seed, opponent.getDefaultMentality(),
                    opponent.getDefaultPressing(), opponent.getDefaultMarking(),
                    opponentAvg - managedAvg, home);
        }

        return new MatchPreviewData(
                fixture.getId().toString(),
                event.getRound(),
                fixture.getMatchDate().toString(),
                home,
                venueClub.getStadiumName(),
                opponentId.toString(),
                opponent.getCommonName() != null ? opponent.getCommonName() : opponent.getName(),
                opponent.getShortName(),
                assistant.getName(),
                snapshotFor(table, opponentId),
                snapshotFor(table, managedClubId),
                formFor(played, opponentId),
                bestPlayers,
                topScorer,
                tacticalRead,
                insights);
    }

    /** 1-based position + counters for one club; null only if the club is not in the table. */
    private MatchPreviewData.StandingSnapshot snapshotFor(List<StandingRow> table, UUID clubId) {
        for (int i = 0; i < table.size(); i++) {
            StandingRow row = table.get(i);
            if (row.clubId().equals(clubId)) {
                return new MatchPreviewData.StandingSnapshot(i + 1, row.points(), row.played(),
                        row.won(), row.drawn(), row.lost(), row.goalsFor(), row.goalsAgainst());
            }
        }
        return null;
    }

    /** The opponent's last up-to-5 played results, newest first, "V"/"E"/"D" from their POV. */
    private List<String> formFor(List<Fixture> played, UUID clubId) {
        return played.stream()
                .filter(f -> clubId.equals(f.getHomeClubId()) || clubId.equals(f.getAwayClubId()))
                .sorted(Comparator.comparingInt(Fixture::getRound).reversed())
                .limit(5)
                .map(f -> {
                    boolean wasHome = clubId.equals(f.getHomeClubId());
                    int scored = wasHome ? f.getHomeGoals() : f.getAwayGoals();
                    int conceded = wasHome ? f.getAwayGoals() : f.getHomeGoals();
                    return scored > conceded ? "V" : scored < conceded ? "D" : "E";
                })
                .toList();
    }

    private MatchPreviewData.PreviewTopScorer topScorerFor(UUID saveGameId, UUID competitionId, UUID clubId) {
        Map<UUID, CompetitionPlayerAggregate> aggregates =
                playerStatsService.aggregateCompetition(saveGameId, competitionId);
        return aggregates.values().stream()
                .filter(a -> clubId.equals(a.clubId()) && a.goals() > 0)
                .max(Comparator.comparingInt(CompetitionPlayerAggregate::goals))
                .flatMap(a -> playerRepository.findById(a.playerId())
                        .map(p -> new MatchPreviewData.PreviewTopScorer(p.getName(), a.goals())))
                .orElse(null);
    }

    private int averageOverall(List<Player> players) {
        return (int) Math.round(players.stream().mapToInt(Player::getOverall).average().orElse(0));
    }

    /** Serialize and write the news_item row (same seq = count + 1 pattern as every writer). */
    private void persist(UUID saveGameId, LocalDate eventDate, MatchPreviewData preview) {
        AdvanceResponse response = new AdvanceResponse(NEWS_TYPE, eventDate.toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, 0, false, List.of(), List.of(),
                null, List.of(), List.of(), preview);
        try {
            String payload = objectMapper.writeValueAsString(response);
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, eventDate, NEWS_TYPE, payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize match preview news item", e);
        }
    }
}
```

(If `PlayerStatsService.CompetitionPlayerAggregate`'s accessors differ, adapt to the real record component names — read `stats/PlayerStatsService.java:61` first. If `playerRepository.findById` returns players from other saves, it cannot — ids are global UUIDs.)

- [ ] **Step 4: Wire the four call sites**

`SeasonAdvanceService.java` — inject `MatchPreviewService matchPreviewService` (constructor + field, following the existing style). In `advance(...)`, immediately after `persistNewsItem(saveGameId, event.getEventDate(), response);`:

```java
        // The next managed fixture's opponent analysis lands right after each advance,
        // dated the eve of that match (self-guarding: assistant + idempotency inside).
        matchPreviewService.maybePersistPreview(saveGameId);
```

Same two lines in `completeUserMatch(...)` after its `persistNewsItem(saveGameId, eventDate, response);`.

`SeasonRolloverService.java` — inject `MatchPreviewService`; immediately before the final `return new RolloverResponse(...)` (~line 340):

```java
        // Round 1 of the new season gets its opponent analysis right away (staff carries over).
        matchPreviewService.maybePersistPreview(saveGameId);
```

`StaffService.java` — inject `MatchPreviewService`; in `hire(...)`, right before the final `return toRosterResponse(byRole);`:

```java
        if (role == StaffRole.ASSISTENTE) {
            // A newly hired auxiliar immediately delivers the pending pre-match analysis.
            matchPreviewService.maybePersistPreview(saveGameId);
        }
```

(This makes `staff` depend on `season` — same already-existing precedent as `contract`/`watchlist` writing news items; there is no Spring constructor cycle because `MatchPreviewService` depends on `StaffRepository`, not `StaffService`.)

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.season.MatchPreviewServiceIT' --tests 'br.com.carvoeiromanager.season.SeasonAdvanceServiceIT' --tests 'br.com.carvoeiromanager.staff.StaffServiceIT'`
(Substitute the actual advance IT class names present in the repo.)
Expected: PASS.

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(news): assistant's opponent-analysis MATCH_PREVIEW on the eve of every fixture"
```

---

### Task 7: Frontend — MATCH_PREVIEW news rendering

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/features/news/newsMeta.tsx`
- Modify: `frontend/src/features/match/AdvanceEventCard.tsx`
- Test: `frontend/src/features/news/newsMeta.test.ts` (or `.tsx` — match the existing file)
- Test: `frontend/src/features/match/AdvanceEventCard.test.tsx`

**Interfaces:**
- Consumes: backend payload shape from Task 5 (field names verbatim).
- Produces: `MatchPreviewData` TS type; `'MATCH_PREVIEW'` in the `AdvanceResponse.type` union; `matchPreview?: MatchPreviewData | null` field.

- [ ] **Step 1: types.ts**

Add before `AdvanceResponse` (near `TrainingReportResponse`):

```typescript
export type PreviewStandingSnapshot = {
  position: number
  points: number
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
}

export type MatchPreviewData = {
  fixtureId: string
  round: number
  matchDate: string
  home: boolean
  stadiumName: string | null
  opponentClubId: string
  opponentName: string
  opponentShortName: string | null
  assistantName: string
  opponentStanding: PreviewStandingSnapshot | null
  managedStanding: PreviewStandingSnapshot | null
  form: string[]
  bestPlayers: { name: string; position: string; overall: number }[]
  topScorer: { name: string; goals: number } | null
  tacticalRead: { formation: string; mentality: string; pressing: string; marking: string } | null
  insights: string[] | null
}
```

In `AdvanceResponse`: extend the union to `'MATCH_PREVIEW'` and add:

```typescript
  /** The Auxiliar Técnico's opponent analysis; only on MATCH_PREVIEW items, null everywhere
   * else and on payloads persisted before the feature existed — always null-guard it. */
  matchPreview?: MatchPreviewData | null
```

- [ ] **Step 2: Failing tests**

`newsMeta.test`:

```typescript
it('titles a match preview with the opponent name', () => {
  const payload = { type: 'MATCH_PREVIEW', matchPreview: { opponentName: 'Avaí' } } as AdvanceResponse
  expect(newsTitle(payload)).toBe('Análise do adversário — Avaí')
})
```

`AdvanceEventCard.test.tsx` (follow the file's existing render helpers/payload factories):

```typescript
const previewResult: AdvanceResponse = {
  ...basePayload,
  type: 'MATCH_PREVIEW',
  date: '2026-03-04',
  matchPreview: {
    fixtureId: 'fx-1', round: 4, matchDate: '2026-03-05', home: true,
    stadiumName: 'Heriberto Hülse',
    opponentClubId: 'club-2', opponentName: 'Avaí', opponentShortName: 'AVA',
    assistantName: 'Cléber Xavier',
    opponentStanding: { position: 3, points: 7, played: 3, won: 2, drawn: 1, lost: 0, goalsFor: 6, goalsAgainst: 2 },
    managedStanding: { position: 5, points: 5, played: 3, won: 1, drawn: 2, lost: 0, goalsFor: 4, goalsAgainst: 3 },
    form: ['V', 'E', 'V'],
    bestPlayers: [{ name: 'Marquinhos Gabriel', position: 'MID', overall: 74 }],
    topScorer: { name: 'Gaspar', goals: 4 },
    tacticalRead: { formation: '4-3-3', mentality: 'ATTACKING', pressing: 'HIGH', marking: 'ZONAL' },
    insights: ['Eles pressionam a saída de bola — bolas longas nas costas da linha de defesa podem render boas chances.'],
  },
}

it('renders the opponent analysis with byline, standings, form and insights', () => {
  renderCard(previewResult)
  expect(screen.getByText('Análise do Adversário')).toBeInTheDocument()
  expect(screen.getByText(/por Cléber Xavier, Auxiliar Técnico/)).toBeInTheDocument()
  expect(screen.getByText('Avaí')).toBeInTheDocument()
  expect(screen.getByText('Marquinhos Gabriel')).toBeInTheDocument()
  expect(screen.getByText(/Gaspar/)).toBeInTheDocument()
  expect(screen.getByText(/4-3-3/)).toBeInTheDocument()
  expect(screen.getByText(/bolas longas nas costas/)).toBeInTheDocument()
})

it('renders a gated (basic) preview without the absent sections', () => {
  const basic = { ...previewResult, matchPreview: { ...previewResult.matchPreview!,
    topScorer: null, tacticalRead: null, insights: null } }
  renderCard(basic)
  expect(screen.queryByText(/4-3-3/)).not.toBeInTheDocument()
})
```

Run: `cd frontend && npx vitest run src/features/news/newsMeta.test.ts src/features/match/AdvanceEventCard.test.tsx` → expect FAIL.

- [ ] **Step 3: newsMeta.tsx**

Add to `newsTitle`:

```typescript
    case 'MATCH_PREVIEW':
      return `Análise do adversário — ${payload.matchPreview?.opponentName ?? ''}`
```

Add to `newsIcon` (import `QueryStatsIcon from '@mui/icons-material/QueryStats'`):

```typescript
    case 'MATCH_PREVIEW': return <QueryStatsIcon fontSize="small" />
```

- [ ] **Step 4: AdvanceEventCard.tsx**

Imports: add `MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL` from `'../squad/tacticsLabels'`, `Table, TableBody, TableCell, TableHead, TableRow` from `@mui/material`, and `type { Mentality, Pressing, Marking }` from `'../../api/types'`.

Header block (alongside the other type conditionals):

```tsx
          {result.type === 'MATCH_PREVIEW' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Análise do Adversário
            </Typography>
          )}
```

Body block (after the TRAINING_REPORT block, inside the timeline `<Box>`):

```tsx
          {result.type === 'MATCH_PREVIEW' && result.matchPreview && (() => {
            const p = result.matchPreview
            const formColor = (r: string) =>
              r === 'V' ? 'var(--cec-green)' : r === 'D' ? '#EF4444' : '#9CA3AF'
            return (
              <TimelineNode dot="🔎">
                <Typography component="strong" sx={{ fontWeight: 700 }}>
                  {p.opponentName}
                  {' — '}
                  {p.home ? 'em casa' : 'fora'}
                  {p.stadiumName ? ` · ${p.stadiumName}` : ''}
                </Typography>
                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                  por {p.assistantName}, Auxiliar Técnico
                </Typography>

                {(p.opponentStanding || p.managedStanding) && (
                  <Table size="small" sx={{ maxWidth: 420, mb: 1 }}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Clube</TableCell>
                        <TableCell align="right">Pos</TableCell>
                        <TableCell align="right">Pts</TableCell>
                        <TableCell align="right">J</TableCell>
                        <TableCell align="right">SG</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {p.opponentStanding && (
                        <TableRow>
                          <TableCell>{p.opponentName}</TableCell>
                          <TableCell align="right">{p.opponentStanding.position}º</TableCell>
                          <TableCell align="right">{p.opponentStanding.points}</TableCell>
                          <TableCell align="right">{p.opponentStanding.played}</TableCell>
                          <TableCell align="right">{p.opponentStanding.goalsFor - p.opponentStanding.goalsAgainst}</TableCell>
                        </TableRow>
                      )}
                      {p.managedStanding && (
                        <TableRow>
                          <TableCell>Nós</TableCell>
                          <TableCell align="right">{p.managedStanding.position}º</TableCell>
                          <TableCell align="right">{p.managedStanding.points}</TableCell>
                          <TableCell align="right">{p.managedStanding.played}</TableCell>
                          <TableCell align="right">{p.managedStanding.goalsFor - p.managedStanding.goalsAgainst}</TableCell>
                        </TableRow>
                      )}
                    </TableBody>
                  </Table>
                )}

                {p.form.length > 0 && (
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', mb: 1 }}>
                    <Typography variant="body2" sx={{ color: 'text.secondary', mr: 0.5 }}>Forma:</Typography>
                    {p.form.map((r, i) => (
                      <Chip key={i} label={r} size="small"
                        sx={{ fontWeight: 700, color: '#111', bgcolor: formColor(r), height: 20 }} />
                    ))}
                  </Stack>
                )}

                {p.bestPlayers.length > 0 && (
                  <Box sx={{ mb: 1 }}>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>Destaques</Typography>
                    <List dense disablePadding>
                      {p.bestPlayers.map(pl => (
                        <ListItem key={pl.name} disablePadding>
                          {pl.name} · {pl.position} · OVR {pl.overall}
                        </ListItem>
                      ))}
                      {p.topScorer && (
                        <ListItem disablePadding>
                          ⚽ Artilheiro: {p.topScorer.name} ({p.topScorer.goals} gol{p.topScorer.goals === 1 ? '' : 's'})
                        </ListItem>
                      )}
                    </List>
                  </Box>
                )}

                {p.tacticalRead && (
                  <Typography variant="body2" sx={{ mb: 1 }}>
                    Costumam jogar no <strong>{p.tacticalRead.formation}</strong>
                    {' · '}{MENTALITY_LABEL[p.tacticalRead.mentality as Mentality] ?? p.tacticalRead.mentality}
                    {' · pressão '}{(PRESSING_LABEL[p.tacticalRead.pressing as Pressing] ?? p.tacticalRead.pressing).toLowerCase()}
                    {' · marcação '}{(MARKING_LABEL[p.tacticalRead.marking as Marking] ?? p.tacticalRead.marking).toLowerCase()}
                  </Typography>
                )}

                {(p.insights?.length ?? 0) > 0 && (
                  <Box sx={{ borderLeft: '3px solid var(--cec-yellow)', pl: 1.5 }}>
                    {p.insights!.map((s, i) => (
                      <Typography key={i} variant="body2" sx={{ fontStyle: 'italic', mb: 0.5 }}>
                        “{s}”
                      </Typography>
                    ))}
                  </Box>
                )}
              </TimelineNode>
            )
          })()}
```

- [ ] **Step 5: Run and commit**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: PASS (all suites — the type-union change can surface exhaustiveness errors elsewhere; fix them).

```bash
git add frontend/src
git commit -m "feat(news): render the assistant's opponent-analysis card"
```

---

### Task 8: Frontend — staff attribute badges

**Files:**
- Modify: `frontend/src/api/types.ts` (`StaffMember`, `StaffCandidate`)
- Modify: `frontend/src/features/staff/StaffPage.tsx`
- Test: `frontend/src/features/staff/StaffPage.test.tsx`

**Interfaces:**
- Consumes: backend `StaffResponse`/`StaffCandidateResponse` now include `primaryAttribute`/`secondaryAttribute` (Task 1).

- [ ] **Step 1: types**

```typescript
export type StaffMember = {
  name: string
  quality: StaffQuality
  wage: number
  primaryAttribute: number
  secondaryAttribute: number
}

export type StaffCandidate = {
  index: number
  name: string
  quality: StaffQuality
  wage: number
  primaryAttribute: number
  secondaryAttribute: number
}
```

- [ ] **Step 2: Failing test**

Add to `StaffPage.test.tsx` (reuse its existing msw/mock + render harness; extend the mocked roster payloads with the two attribute fields):

```typescript
it('shows the two labeled attributes of a hired staff member', async () => {
  // roster mock: assistente = { name: 'Cléber Xavier', quality: 'EXCELENTE', wage: 80000,
  //                             primaryAttribute: 17, secondaryAttribute: 16 }
  renderPage()
  expect(await screen.findByText(/Conhecimento Tático: 17/)).toBeInTheDocument()
  expect(screen.getByText(/Avaliação de Jogadores: 16/)).toBeInTheDocument()
})
```

Run: `cd frontend && npx vitest run src/features/staff/StaffPage.test.tsx` → FAIL. (Existing tests in the file will also fail to type-check until their mock payloads gain the two new fields — update them.)

- [ ] **Step 3: Implement**

`StaffPage.tsx` — add the label map and a badge row. After `QUALITY_LABEL`:

```typescript
const ATTRIBUTE_LABELS: Record<StaffRole, [string, string]> = {
  ASSISTENTE: ['Conhecimento Tático', 'Avaliação de Jogadores'],
  PREPARADOR_FISICO: ['Preparação Física', 'Ciência Esportiva'],
  OLHEIRO: ['Conhecimento do Mercado', 'Negociação'],
  FISIOTERAPEUTA: ['Prevenção de Lesões', 'Reabilitação'],
}

/** Color tier for a 1-20 staff attribute: >=16 gold, >=12 green, else grey (mirrors OVR tiers). */
function attributeChipSx(value: number) {
  if (value >= 16) return { bgcolor: 'var(--cec-yellow)', color: '#111', fontWeight: 700 }
  if (value >= 12) return { bgcolor: 'rgba(16,185,129,0.16)', color: '#10B981', fontWeight: 700 }
  return { bgcolor: 'rgba(255,255,255,0.08)', color: 'text.secondary', fontWeight: 600 }
}

function AttributeChips({ role, primary, secondary }: { role: StaffRole; primary: number; secondary: number }) {
  const [primaryLabel, secondaryLabel] = ATTRIBUTE_LABELS[role]
  return (
    <Stack direction="row" spacing={1} sx={{ mt: 0.5, flexWrap: 'wrap', rowGap: 0.5 }}>
      <Chip size="small" label={`${primaryLabel}: ${primary}`} sx={attributeChipSx(primary)} />
      <Chip size="small" label={`${secondaryLabel}: ${secondary}`} sx={attributeChipSx(secondary)} />
    </Stack>
  )
}
```

In the hired-holder branch, after the existing name/quality/wage `Stack`:

```tsx
                        <AttributeChips role={role} primary={holder.primaryAttribute} secondary={holder.secondaryAttribute} />
```

In the candidate dialog's `ListItemText`, replace `secondary` with a richer node:

```tsx
                <ListItemText
                  primary={c.name}
                  secondary={
                    <>
                      {QUALITY_LABEL[c.quality]} · {formatBRL(c.wage)}/mês
                      <AttributeChips role={browsingRole!} primary={c.primaryAttribute} secondary={c.secondaryAttribute} />
                    </>
                  }
                  secondaryTypographyProps={{ component: 'div' }}
                />
```

- [ ] **Step 4: Run and commit**

Run: `cd frontend && npx tsc -b && npx vitest run src/features/staff/StaffPage.test.tsx`
Expected: PASS.

```bash
git add frontend/src
git commit -m "feat(staff): attribute badges on Comissão Técnica (roster + candidates)"
```

---

### Task 9: Full suites + consistency sweep

**Files:** none new — verification and any fallout fixes.

- [ ] **Step 1: Backend full suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: PASS (~10–12 min; Docker must be running for ITs). Known-flaky ITs (`TransferServiceSellingIT`, `RolloverContractsIT`) that fail in the full run must pass when re-run in isolation before being called flakes.

- [ ] **Step 2: Frontend full suite**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: PASS.

- [ ] **Step 3: Consistency sweep**

- `grep -rn "MATCH_PREVIEW" backend/src frontend/src` — type string identical everywhere.
- `grep -rn "new AdvanceResponse(" backend/src` — every construction has 17 arguments.
- Confirm `docs/superpowers/specs/2026-07-22-opponent-analysis-staff-attributes-design.md` gates match the implemented thresholds (12/16).

- [ ] **Step 4: Commit any fixes**

```bash
git add -A backend/src frontend/src
git commit -m "test: full-suite fallout fixes for opponent-analysis feature"
```

(Skip the commit if nothing changed.)
