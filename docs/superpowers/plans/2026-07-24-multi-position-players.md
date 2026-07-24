# Multi-Position Players + EAFC Roles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Players carry 1–3 playable positions and 1–2 familiar EAFC role archetypes (researched for all 804 real players); the manager can improvise anyone anywhere via a slot picker (engine penalty + amber warn), while best-XI selection is strictly natural-first.

**Architecture:** Two-axis model. `DetailedPosition` grows to the 12 EAFC positions with a distance matrix; `Player` gains `alt_positions` + `familiar_roles` CSVs. New `RoleArchetype` (~30) + `RoleFocus` (10) enums replace `PlayerRole` AND `RoleBehaviour` end-to-end (`RoleAssignment = archetype + focus` persisted on `lineup_players` as `archetype`/`focus`, plus explicit `slot`). Engine: position-fit via distance (1.0/0.92/0.80/0.50) × familiarity (1.0/0.97/0.92) × focus-lean multiplier — all pure multipliers, zero RNG draws added/removed/reordered. Legacy-mapped archetypes keep the exact old `PlayerRole` weights, and every default resolves to the neutral value, so migrated lineups score identically at unit level.

**Tech Stack:** Java 21 / Spring Boot / Flyway / Testcontainers Postgres; React 18 + TS + MUI + TanStack Query + Vitest/RTL.

**Spec:** `docs/superpowers/specs/2026-07-24-multi-position-players-design.md` (the archetype/focus matrix in §2.1 is normative).

## Global Constraints

- Backend tests (from repo root): `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` — 600000ms timeout for the full suite; focused runs via `--tests 'br.com.carvoeiromanager...'`. Before running the full suite, check `ps aux | grep -i gradle | grep -v grep` — another session may be running tests (Testcontainers contention causes EOFException/NoSuchFileException, not real failures).
- Frontend: `cd frontend && npx vitest run` (focused: `npx vitest run <path>`) and `npm run build` for type-check (`tsc --noEmit` is a no-op in this repo).
- Git diffs MUST use `git -c diff.external= diff --no-ext-diff` (broken global `diff.external`).
- Work directly on `main`. A CONCURRENT SESSION may also commit to main: commit ONLY the files each task names (`git add <paths>`), NEVER `git add -A`. Do NOT push; the controller decides pushes.
- Commit per task with conventional messages ending in `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Migrations V33–V39 exist. This plan allocates **V40, V41, V42**. If a concurrent session takes a number first, shift to the next free and update the file names in later tasks accordingly.
- Determinism doctrine: never insert/remove/reorder RNG draws in existing shared streams. New randomness only via per-player `Random`s seeded off the player id (existing precedent: `Player` constructor `new Random(id.getMostSignificantBits() ^ 0xDE7A11L)`). This feature is intentionally NOT byte-identical for existing saves (researched data + new formation slots legitimately change strengths — V36 precedent), but mechanics stay pure: same inputs → same outputs.
- All UI text PT-BR. Known flaky test: `TransferServiceSellingIT.listedManagedPlayersDrawBoostedBids` — re-run isolated before judging.
- The archetype↔position↔focus matrix and all numeric tunings in this plan are FINAL — do not invent different values.

---

## Phase A — Positions core

### Task 1: DetailedPosition 12-value rework + distance + FormationSlots + Player alternates + V40

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/DetailedPosition.java` (full rewrite)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FormationSlots.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Create: `backend/src/main/resources/db/migration/V40__multi_position.sql`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/DetailedPositionTest.java` (extend/adjust existing)
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/MultiPositionMigrationIT.java` (new; follow the existing V39 migration IT pattern in `player/`)
- Adjust any test referencing `PONTA`/`FALSO_9` as a `DetailedPosition` (grep `DetailedPosition.PONTA\|DetailedPosition.FALSO_9` in `src/test`).

**Interfaces (produced — later tasks rely on these exact signatures):**
- `DetailedPosition` values, in declaration order: `GOLEIRO, ZAGUEIRO, LATERAL_DIREITO, LATERAL_ESQUERDO, VOLANTE, MEIA_CENTRAL, MEIA_ARMADOR, MEIA_DIREITA, MEIA_ESQUERDA, PONTA_DIREITA, PONTA_ESQUERDA, ATACANTE`
- `int distance(DetailedPosition other)` — 0 same, 1 adjacent, 2 far, 3 GK↔outfield
- `List<DetailedPosition> adjacent()` — the distance-1 neighbours, declaration order
- `static DetailedPosition defaultFor(Position position, Random rng)` (the `passe` param is GONE)
- `static List<DetailedPosition> alternatesFor(DetailedPosition primary, Random rng)` — 0–2 adjacent alternates
- `Player.positions()` → `List<DetailedPosition>` primary-first; `Player.setPositions(DetailedPosition primary, List<DetailedPosition> alternates)`
- `Player.getAltPositions()` → `List<DetailedPosition>` (parsed CSV, empty when null)

- [ ] **Step 1: Rewrite `DetailedPosition`**

Replace the enum body with (labels/shorts per spec §1.1; keep the class Javadoc, updating the value list):

```java
public enum DetailedPosition {

    GOLEIRO(Position.GK, "Goleiro", "GOL"),
    ZAGUEIRO(Position.DEF, "Zagueiro", "ZAG"),
    LATERAL_DIREITO(Position.DEF, "Lateral direito", "LD"),
    LATERAL_ESQUERDO(Position.DEF, "Lateral esquerdo", "LE"),
    VOLANTE(Position.MID, "Volante", "VOL"),
    MEIA_CENTRAL(Position.MID, "Meia central", "MC"),
    MEIA_ARMADOR(Position.MID, "Meia armador", "MEI"),
    MEIA_DIREITA(Position.MID, "Meia direita", "MD"),
    MEIA_ESQUERDA(Position.MID, "Meia esquerda", "ME"),
    PONTA_DIREITA(Position.ATT, "Ponta direita", "PD"),
    PONTA_ESQUERDA(Position.ATT, "Ponta esquerda", "PE"),
    ATACANTE(Position.ATT, "Atacante", "ATA");
```

Keep `coarse()`, `label()`, `shortLabel()` as-is. Add the adjacency set + distance:

```java
    /** Unordered adjacent (1-step) pairs of the position graph — see spec §4.1. */
    private static final Set<Set<DetailedPosition>> ADJACENT = Set.of(
            Set.of(LATERAL_DIREITO, MEIA_DIREITA), Set.of(LATERAL_ESQUERDO, MEIA_ESQUERDA),
            Set.of(MEIA_DIREITA, PONTA_DIREITA), Set.of(MEIA_ESQUERDA, PONTA_ESQUERDA),
            Set.of(VOLANTE, MEIA_CENTRAL), Set.of(MEIA_CENTRAL, MEIA_ARMADOR),
            Set.of(MEIA_ARMADOR, ATACANTE), Set.of(ZAGUEIRO, VOLANTE),
            Set.of(LATERAL_DIREITO, ZAGUEIRO), Set.of(LATERAL_ESQUERDO, ZAGUEIRO),
            Set.of(PONTA_DIREITA, ATACANTE), Set.of(PONTA_ESQUERDA, ATACANTE));

    /**
     * Tactical distance between positions: 0 = same, 1 = adjacent (natural improvisation),
     * 2 = far, 3 = goalkeeper vs any outfield position (either direction).
     */
    public int distance(DetailedPosition other) {
        if (this == other) {
            return 0;
        }
        if (this == GOLEIRO || other == GOLEIRO) {
            return 3;
        }
        return ADJACENT.contains(Set.of(this, other)) ? 1 : 2;
    }

    /** Distance-1 neighbours in declaration order (never contains GOLEIRO for outfield callers). */
    public List<DetailedPosition> adjacent() {
        return Arrays.stream(values()).filter(p -> distance(p) == 1).toList();
    }
```

Replace `defaultFor` (drop `passe` — FALSO_9 is no longer a position) and add `alternatesFor`:

```java
    public static DetailedPosition defaultFor(Position position, Random rng) {
        return switch (position) {
            case GK -> GOLEIRO;
            case DEF -> {
                int r = rng.nextInt(10);            // 60% ZAG, 20% LD, 20% LE
                if (r < 6) yield ZAGUEIRO;
                yield r < 8 ? LATERAL_DIREITO : LATERAL_ESQUERDO;
            }
            case MID -> {
                int r = rng.nextInt(10);            // 30% VOL, 30% MC, 20% MEI, 10% MD, 10% ME
                if (r < 3) yield VOLANTE;
                if (r < 6) yield MEIA_CENTRAL;
                if (r < 8) yield MEIA_ARMADOR;
                yield r == 8 ? MEIA_DIREITA : MEIA_ESQUERDA;
            }
            case ATT -> {
                int r = rng.nextInt(10);            // 20% PD, 20% PE, 60% ATA
                if (r < 2) yield PONTA_DIREITA;
                if (r < 4) yield PONTA_ESQUERDA;
                yield ATACANTE;
            }
        };
    }

    /**
     * 0–2 alternate positions for a generated player: ~30% one alternate, ~5% two, drawn from the
     * primary's adjacent outfield neighbours. Goalkeepers never get (or are) alternates. Draws come
     * from the caller's per-player Random, so no shared stream is disturbed.
     */
    public static List<DetailedPosition> alternatesFor(DetailedPosition primary, Random rng) {
        if (primary == GOLEIRO) {
            return List.of();
        }
        int roll = rng.nextInt(100);
        int count = roll < 5 ? 2 : roll < 35 ? 1 : 0;
        List<DetailedPosition> pool = new ArrayList<>(primary.adjacent());
        List<DetailedPosition> alts = new ArrayList<>();
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            alts.add(pool.remove(rng.nextInt(pool.size())));
        }
        return alts;
    }
```

(Imports: `java.util.ArrayList`, `java.util.Arrays`, `java.util.List`, `java.util.Set`.)

- [ ] **Step 2: Update `FormationSlots` to the 12-position slot sets**

Replace the slot constants and every formation entry. Slots are now single-position (the old `{ATACANTE, FALSO_9}` striker set collapses to `{ATACANTE}`):

```java
    private static final Slot GK = slot(DetailedPosition.GOLEIRO);
    private static final Slot LD = slot(DetailedPosition.LATERAL_DIREITO);
    private static final Slot LE = slot(DetailedPosition.LATERAL_ESQUERDO);
    private static final Slot ZAG = slot(DetailedPosition.ZAGUEIRO);
    private static final Slot VOL = slot(DetailedPosition.VOLANTE);
    private static final Slot MC = slot(DetailedPosition.MEIA_CENTRAL);
    private static final Slot MEI = slot(DetailedPosition.MEIA_ARMADOR);
    private static final Slot MD = slot(DetailedPosition.MEIA_DIREITA);
    private static final Slot ME = slot(DetailedPosition.MEIA_ESQUERDA);
    private static final Slot PD = slot(DetailedPosition.PONTA_DIREITA);
    private static final Slot PE = slot(DetailedPosition.PONTA_ESQUERDA);
    private static final Slot ST = slot(DetailedPosition.ATACANTE);
```

New `FORMATIONS` map (order mirrors the frontend rows left→right, deepest row first; coarse band counts still match the code digits — the existing sync test enforces this):

```java
    private static final Map<String, List<Slot>> FORMATIONS = Map.ofEntries(
            Map.entry("4-4-2", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, ME, ST, ST)),
            Map.entry("4-3-3", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MEI, PD, ST, PE)),
            Map.entry("3-5-2", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, MEI, ME, ST, ST)),
            Map.entry("5-3-2", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MC, MEI, ST, ST)),
            Map.entry("4-5-1", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, MEI, ME, ST)),
            Map.entry("4-2-3-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, VOL, MD, MEI, ME, ST)),
            Map.entry("4-1-4-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MD, MC, MEI, ME, ST)),
            Map.entry("4-3-2-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, MEI, ST)),
            Map.entry("3-4-3", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, ME, PD, ST, PE)),
            Map.entry("4-4-1-1", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, ME, MEI, ST)),
            Map.entry("5-4-1", List.of(GK, LD, ZAG, ZAG, ZAG, LE, MD, VOL, MC, ME, ST)),
            Map.entry("4-2-4", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, PD, ST, ST, PE)),
            Map.entry("3-4-2-1", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, ME, MEI, MEI, ST)),
            Map.entry("4-1-2-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, ST, ST)),
            Map.entry("5-2-3", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MC, PD, ST, PE)),
            Map.entry("4-3-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, ST, ST)));
```

Update the class Javadoc mapping rules (wide slots in a 4+/flat midfield are MD/ME; front-3 wide slots are PD/PE; the deepest central mid is VOL, then MC, then MEI).

- [ ] **Step 3: Add alternates to `Player`**

New column field + accessors (keep the CSV raw string private; parse on read):

```java
    /** CSV of 0–2 alternate DetailedPosition names (spec §1.2); null = no alternates. */
    @Column(name = "alt_positions", length = 40)
    private String altPositions;
```

```java
    /** Alternate positions (may be empty), parsed from the CSV column. */
    public List<DetailedPosition> getAltPositions() {
        if (altPositions == null || altPositions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(altPositions.split(",")).map(DetailedPosition::valueOf).toList();
    }

    /** All playable positions, primary first. */
    public List<DetailedPosition> positions() {
        List<DetailedPosition> all = new ArrayList<>(1 + getAltPositions().size());
        all.add(detailedPosition);
        all.addAll(getAltPositions());
        return all;
    }

    /** Replaces primary + alternates (researched seed data or migration backfill). */
    public void setPositions(DetailedPosition primary, List<DetailedPosition> alternates) {
        this.detailedPosition = primary;
        this.altPositions = alternates == null || alternates.isEmpty() ? null
                : alternates.stream().map(Enum::name).collect(Collectors.joining(","));
    }
```

In the private constructor, replace the `detailedPosition` assignment block with (same id-seeded Random, now also drawing alternates — a per-player stream, so nothing shared shifts):

```java
        Random posRng = new Random(id.getMostSignificantBits() ^ 0xDE7A11L);
        this.detailedPosition = DetailedPosition.defaultFor(position, posRng);
        List<DetailedPosition> alts = DetailedPosition.alternatesFor(this.detailedPosition, posRng);
        this.altPositions = alts.isEmpty() ? null
                : alts.stream().map(Enum::name).collect(Collectors.joining(","));
```

(Imports: `java.util.ArrayList`, `java.util.Arrays`, `java.util.List`, `java.util.stream.Collectors`.)

- [ ] **Step 4: Write `V40__multi_position.sql`**

```sql
-- Multi-position players (spec §1): 12-value DetailedPosition, alternate positions, and
-- familiar-roles seeding. PONTA splits into PONTA_DIREITA/PONTA_ESQUERDA by id-hash parity;
-- FALSO_9 becomes ATACANTE and keeps its identity as the FALSE_9 familiar archetype.
-- familiar_roles is seeded here (before the remap consumes the legacy values) even though the
-- Java side only starts reading it in a later task — the column must exist with sane data the
-- moment detailed_position loses PONTA/FALSO_9.

ALTER TABLE players ADD COLUMN alt_positions VARCHAR(40);
ALTER TABLE players ADD COLUMN familiar_roles VARCHAR(120);

-- 1. Derive one fully-familiar (++) archetype from the legacy detailed position.
UPDATE players SET familiar_roles = CASE detailed_position
    WHEN 'GOLEIRO' THEN 'GOALKEEPER++'
    WHEN 'ZAGUEIRO' THEN 'DEFENDER++'
    WHEN 'LATERAL_ESQUERDO' THEN 'FULLBACK++'
    WHEN 'LATERAL_DIREITO' THEN 'FULLBACK++'
    WHEN 'VOLANTE' THEN 'HOLDING++'
    WHEN 'MEIA_ARMADOR' THEN 'PLAYMAKER++'
    WHEN 'PONTA' THEN 'WINGER++'
    WHEN 'FALSO_9' THEN 'FALSE_9++'
    WHEN 'ATACANTE' THEN 'POACHER++'
END;

-- 2. A manager's persisted lineup role refines the derived default where it names a more
--    specific archetype that stays valid for the player's position (the only such cases).
UPDATE players p SET familiar_roles = CASE lp.role
        WHEN 'STOPPER' THEN 'STOPPER++'
        WHEN 'BALL_PLAYING_DEF' THEN 'BALL_PLAYING_DEFENDER++'
        WHEN 'TARGET_MAN' THEN 'TARGET_FORWARD++'
    END
FROM lineup_players lp
WHERE lp.player_id = p.id
  AND ((lp.role IN ('STOPPER', 'BALL_PLAYING_DEF') AND p.detailed_position = 'ZAGUEIRO')
       OR (lp.role = 'TARGET_MAN' AND p.detailed_position = 'ATACANTE'));

ALTER TABLE players ALTER COLUMN familiar_roles SET NOT NULL;

-- 3. Remap the two retired position values (deterministic, hash-parity like V39).
UPDATE players SET detailed_position = CASE
    WHEN detailed_position = 'FALSO_9' THEN 'ATACANTE'
    WHEN abs(hashtext(id::text)) % 2 = 0 THEN 'PONTA_DIREITA'
    ELSE 'PONTA_ESQUERDA'
END
WHERE detailed_position IN ('PONTA', 'FALSO_9');
```

- [ ] **Step 5: Update/extend tests**

In `DetailedPositionTest` (adjust existing methods that reference PONTA/FALSO_9/passe, then add):
- `distanceIsSymmetricAndBounded` — for all pairs: `a.distance(b) == b.distance(a)`, 0 iff same, 3 iff exactly one is GOLEIRO.
- `adjacencyMatchesTheSpecGraph` — assert the exact 12 pairs (e.g. `LATERAL_DIREITO.adjacent()` contains `MEIA_DIREITA, ZAGUEIRO` only).
- `defaultForIsCoarseConsistentAndCovers12Values` — 10_000 draws per coarse band with `new Random(42)`: every result `coarse() == band`; DEF yields all of ZAG/LD/LE; MID all five; ATT all three.
- `alternatesAreAdjacentDistinctAndNeverGk` — 10_000 draws for each primary: alternates ⊆ `primary.adjacent()`, no duplicates, size ≤ 2, GOLEIRO yields empty; observed share with ≥1 alternate within [25%, 35%].
- In `Player`-level tests (or a new one here): a constructed player's `positions().get(0) == getDetailedPosition()` and any alternates are adjacent.

In `MultiPositionMigrationIT` (Testcontainers, follow the V39 IT pattern — insert legacy-shaped rows via JDBC before migration or use the standard "raw SQL insert then assert" approach used by the existing migration ITs):
- Rows with `PONTA` end as `PONTA_DIREITA` or `PONTA_ESQUERDA` (both appear across many rows), `FALSO_9` → `ATACANTE` with `familiar_roles = 'FALSE_9++'`.
- Every position maps to the expected `familiar_roles` string; a ZAGUEIRO with a persisted `STOPPER` lineup row ends `STOPPER++`.
- `alt_positions` is NULL everywhere; `familiar_roles` NOT NULL.

Also fix the existing `FormationSlots` sync test (band counts per formation still equal the code digits) and any test constructing `DetailedPosition.PONTA`/`FALSO_9`.

- [ ] **Step 6: Run the backend suite**

Run focused first: `--tests 'br.com.carvoeiromanager.player.*' --tests 'br.com.carvoeiromanager.match.*'`, then the FULL suite. Expected: PASS (test-data drift from new formation slots is possible in match ITs — fix assertions that hard-coded old slot orders, never the engine).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/DetailedPosition.java backend/src/main/java/br/com/carvoeiromanager/match/FormationSlots.java backend/src/main/java/br/com/carvoeiromanager/player/Player.java backend/src/main/resources/db/migration/V40__multi_position.sql backend/src/test/java/br/com/carvoeiromanager/player/DetailedPositionTest.java backend/src/test/java/br/com/carvoeiromanager/player/MultiPositionMigrationIT.java <other-adjusted-test-files>
git commit -m "feat(player): 12 EAFC detailed positions + alternates (V40)"
```

---

## Phase B — Role model

### Task 2: RoleFocus + RoleArchetype enums + futbin sync-guard

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/RoleFocus.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/RoleArchetype.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/RoleArchetypeTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/RoleArchetypeFutbinSyncTest.java`

**Interfaces (produced):**
- `RoleFocus`: `label()`, `double attackLean()`, `double defenseLean()`
- `RoleArchetype`: `label()`, `boolean validFor(DetailedPosition)`, `Set<DetailedPosition> validPositions()`, `List<RoleFocus> focusesFor(DetailedPosition)`, `RoleFocus defaultFocusFor(DetailedPosition)`, `RoleFocus canonicalDefaultFocus()`, `Map<Attribute,Double> attackWeights()/defenseWeights()`, `double attackContribution(Player)/defenseContribution(Player)`, `static RoleArchetype defaultFor(DetailedPosition)`, `boolean isWideCreator()/isCentralCreator()`, `static double weighted(Map<Attribute,Double>, Attributes)` (moved from PlayerRole)

- [ ] **Step 1: Write `RoleFocus`**

```java
package br.com.carvoeiromanager.player;

/**
 * The EAFC focus dimension of a role (spec §2.2). Each focus carries a small attack/defense
 * "lean"; the engine multiplies a role's contributions by (1 + lean(focus) − lean(canonical
 * default focus)), so an archetype played at its canonical default focus is EXACTLY its base
 * profile — the determinism contract inherited from RoleBehaviour.
 */
public enum RoleFocus {
    DEFEND("Defesa", -0.08, 0.08),
    BALANCED("Equilibrado", 0.0, 0.0),
    ATTACK("Ataque", 0.08, -0.08),
    BUILD_UP("Construção", 0.03, -0.01),
    SUPPORT("Apoio", 0.04, -0.02),
    ROAMING("Livre", 0.05, -0.04),
    VERSATILE("Versátil", 0.02, 0.02),
    AGGRESSIVE("Agressivo", 0.0, 0.05),
    BALL_WINNING("Combativo", -0.03, 0.06),
    WIDE("Aberto", 0.02, -0.02);

    private final String label;
    private final double attackLean;
    private final double defenseLean;

    RoleFocus(String label, double attackLean, double defenseLean) {
        this.label = label;
        this.attackLean = attackLean;
        this.defenseLean = defenseLean;
    }

    public String label() { return label; }
    public double attackLean() { return attackLean; }
    public double defenseLean() { return defenseLean; }
}
```

- [ ] **Step 2: Write `RoleArchetype`**

Enum constants take `(label, canonicalDefaultFocus, attackWeights, defenseWeights)`. The 10 legacy-mapped archetypes carry the EXACT old `PlayerRole` weights (regression contract). Values:

```java
public enum RoleArchetype {
    // --- legacy-mapped (weights identical to the retired PlayerRole) ---
    GOALKEEPER("Goleiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.REFLEXOS, 1.0)),
    STOPPER("Xerife", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.FINALIZACAO, 0.1), Map.of(Attribute.DESARME, 1.0)),
    FULLBACK("Lateral", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.2), Map.of(Attribute.DESARME, 0.8)),
    BALL_PLAYING_DEFENDER("Zagueiro construtor", RoleFocus.DEFEND,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.PASSE, 0.4), Map.of(Attribute.DESARME, 0.85)),
    HOLDING("Primeiro volante", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.3), Map.of(Attribute.DESARME, 0.8)),
    BOX_TO_BOX("Box-to-box", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.3, Attribute.PASSE, 0.3),
            Map.of(Attribute.DESARME, 0.5)),
    PLAYMAKER("Armador", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.6), Map.of(Attribute.DESARME, 0.2)),
    POACHER("Oportunista", RoleFocus.ATTACK,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.8), Map.of()),
    TARGET_FORWARD("Pivô", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.6, Attribute.PASSE, 0.2), Map.of(Attribute.DESARME, 0.1)),
    WINGER("Ponta", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.6, Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.2),
            Map.of(Attribute.DESARME, 0.05)),

    // --- new archetypes (same value ranges as the legacy set) ---
    SWEEPER_KEEPER("Goleiro-líbero", RoleFocus.BALANCED,
            Map.of(), Map.of(Attribute.REFLEXOS, 0.85, Attribute.SAIDA_DE_GOL, 0.15)),
    BALL_PLAYING_KEEPER("Goleiro construtor", RoleFocus.BUILD_UP,
            Map.of(), Map.of(Attribute.REFLEXOS, 0.8, Attribute.JOGO_COM_OS_PES, 0.2)),
    WINGBACK("Ala", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.35, Attribute.CRUZAMENTO, 0.25), Map.of(Attribute.DESARME, 0.65)),
    FALSEBACK("Falso lateral", RoleFocus.BALANCED,
            Map.of(Attribute.PASSE, 0.35, Attribute.VISAO, 0.15),
            Map.of(Attribute.DESARME, 0.75, Attribute.MARCACAO, 0.1)),
    ATTACKING_WINGBACK("Ala ofensivo", RoleFocus.SUPPORT,
            Map.of(Attribute.VELOCIDADE, 0.4, Attribute.CRUZAMENTO, 0.3), Map.of(Attribute.DESARME, 0.5)),
    INVERTED_WINGBACK("Ala invertido", RoleFocus.BUILD_UP,
            Map.of(Attribute.PASSE, 0.4, Attribute.VISAO, 0.2), Map.of(Attribute.DESARME, 0.6)),
    DEFENDER("Zagueiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.DESARME, 0.65, Attribute.MARCACAO, 0.35)),
    WIDE_BACK("Zagueiro ala", RoleFocus.DEFEND,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.CRUZAMENTO, 0.1),
            Map.of(Attribute.DESARME, 0.7, Attribute.MARCACAO, 0.1)),
    DEEP_LYING_PLAYMAKER("Volante armador", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.5, Attribute.VISAO, 0.3), Map.of(Attribute.DESARME, 0.45)),
    CENTRE_HALF("Terceiro zagueiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.DESARME, 0.8, Attribute.MARCACAO, 0.2)),
    WIDE_HALF("Volante ala", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.3, Attribute.CRUZAMENTO, 0.1), Map.of(Attribute.DESARME, 0.7)),
    BOX_CRASHER("Volante de chegada", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.4, Attribute.CHUTE_LONGE, 0.2, Attribute.VELOCIDADE, 0.1),
            Map.of(Attribute.DESARME, 0.45)),
    HALF_WINGER("Meia-ala", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.3, Attribute.DRIBLE, 0.2),
            Map.of(Attribute.DESARME, 0.3)),
    SHADOW_STRIKER("Segundo atacante", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.5, Attribute.POSICIONAMENTO, 0.2, Attribute.VELOCIDADE, 0.2),
            Map.of(Attribute.DESARME, 0.1)),
    CLASSIC_10("Camisa 10", RoleFocus.ATTACK,
            Map.of(Attribute.PASSE, 0.5, Attribute.VISAO, 0.3, Attribute.FINALIZACAO, 0.2), Map.of()),
    WIDE_PLAYMAKER("Armador aberto", RoleFocus.ATTACK,
            Map.of(Attribute.PASSE, 0.4, Attribute.VISAO, 0.2, Attribute.CRUZAMENTO, 0.2),
            Map.of(Attribute.DESARME, 0.1)),
    WIDE_MIDFIELDER("Meia de lado", RoleFocus.SUPPORT,
            Map.of(Attribute.CRUZAMENTO, 0.3, Attribute.PASSE, 0.2, Attribute.VELOCIDADE, 0.1),
            Map.of(Attribute.DESARME, 0.45)),
    INSIDE_FORWARD("Atacante por dentro", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.4, Attribute.VELOCIDADE, 0.3, Attribute.DRIBLE, 0.2),
            Map.of(Attribute.DESARME, 0.05)),
    ADVANCED_FORWARD("Atacante móvel", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.5, Attribute.VELOCIDADE, 0.35, Attribute.DRIBLE, 0.1), Map.of()),
    FALSE_9("Falso 9", RoleFocus.BUILD_UP,
            Map.of(Attribute.PASSE, 0.35, Attribute.FINALIZACAO, 0.3, Attribute.VISAO, 0.25), Map.of());
```

Then the position→focus matrix as ONE static map (this IS the futbin EAFC 26 taxonomy, spec §2.1 — first focus in each list is that position's default). Use a static block building `Map<RoleArchetype, Map<DetailedPosition, List<RoleFocus>>> MATRIX` with entries (import static `DetailedPosition.*`, `RoleFocus.*` for brevity):

```java
    /** archetype → (valid position → focuses, default first). Mirrors futbin.com/26/roles exactly. */
    private static final Map<RoleArchetype, Map<DetailedPosition, List<RoleFocus>>> MATRIX = Map.ofEntries(
        Map.entry(GOALKEEPER, Map.of(GOLEIRO, List.of(DEFEND, BALANCED))),
        Map.entry(SWEEPER_KEEPER, Map.of(GOLEIRO, List.of(BALANCED, BUILD_UP))),
        Map.entry(BALL_PLAYING_KEEPER, Map.of(GOLEIRO, List.of(BUILD_UP))),
        Map.entry(FULLBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, DEFEND, VERSATILE),
                LATERAL_ESQUERDO, List.of(BALANCED, DEFEND, VERSATILE))),
        Map.entry(WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, SUPPORT),
                LATERAL_ESQUERDO, List.of(BALANCED, SUPPORT))),
        Map.entry(FALSEBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, DEFEND),
                LATERAL_ESQUERDO, List.of(BALANCED, DEFEND))),
        Map.entry(ATTACKING_WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(SUPPORT, ATTACK),
                LATERAL_ESQUERDO, List.of(SUPPORT, ATTACK))),
        Map.entry(INVERTED_WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(BUILD_UP, ATTACK),
                LATERAL_ESQUERDO, List.of(BUILD_UP, ATTACK))),
        Map.entry(DEFENDER, Map.of(ZAGUEIRO, List.of(DEFEND, BALANCED))),
        Map.entry(STOPPER, Map.of(ZAGUEIRO, List.of(BALANCED, AGGRESSIVE))),
        Map.entry(BALL_PLAYING_DEFENDER, Map.of(ZAGUEIRO, List.of(DEFEND, BUILD_UP, AGGRESSIVE))),
        Map.entry(WIDE_BACK, Map.of(ZAGUEIRO, List.of(DEFEND, SUPPORT, AGGRESSIVE))),
        Map.entry(HOLDING, Map.of(
                VOLANTE, List.of(DEFEND, ROAMING, BALL_WINNING),
                MEIA_CENTRAL, List.of(DEFEND, BALL_WINNING))),
        Map.entry(DEEP_LYING_PLAYMAKER, Map.of(
                VOLANTE, List.of(DEFEND, ROAMING, BUILD_UP),
                MEIA_CENTRAL, List.of(DEFEND, BUILD_UP))),
        Map.entry(CENTRE_HALF, Map.of(VOLANTE, List.of(DEFEND))),
        Map.entry(WIDE_HALF, Map.of(VOLANTE, List.of(DEFEND, BUILD_UP))),
        Map.entry(BOX_CRASHER, Map.of(VOLANTE, List.of(BALANCED))),
        Map.entry(BOX_TO_BOX, Map.of(MEIA_CENTRAL, List.of(BALANCED, BALL_WINNING))),
        Map.entry(PLAYMAKER, Map.of(
                MEIA_CENTRAL, List.of(ATTACK, ROAMING),
                MEIA_ARMADOR, List.of(BALANCED, ROAMING, BUILD_UP))),
        Map.entry(HALF_WINGER, Map.of(
                MEIA_CENTRAL, List.of(BALANCED, ATTACK, SUPPORT),
                MEIA_ARMADOR, List.of(BALANCED, ATTACK, ROAMING))),
        Map.entry(SHADOW_STRIKER, Map.of(MEIA_ARMADOR, List.of(ATTACK))),
        Map.entry(CLASSIC_10, Map.of(MEIA_ARMADOR, List.of(ATTACK, WIDE, VERSATILE))),
        Map.entry(WINGER, Map.of(
                MEIA_DIREITA, List.of(BALANCED, ATTACK),
                MEIA_ESQUERDA, List.of(BALANCED, ATTACK),
                PONTA_DIREITA, List.of(BALANCED, ATTACK, VERSATILE),
                PONTA_ESQUERDA, List.of(BALANCED, ATTACK, VERSATILE))),
        Map.entry(WIDE_PLAYMAKER, Map.of(
                MEIA_DIREITA, List.of(ATTACK, BUILD_UP),
                MEIA_ESQUERDA, List.of(ATTACK, BUILD_UP),
                PONTA_DIREITA, List.of(ATTACK, BUILD_UP),
                PONTA_ESQUERDA, List.of(ATTACK, BUILD_UP))),
        Map.entry(WIDE_MIDFIELDER, Map.of(
                MEIA_DIREITA, List.of(SUPPORT, DEFEND, BUILD_UP),
                MEIA_ESQUERDA, List.of(SUPPORT, DEFEND, BUILD_UP))),
        Map.entry(INSIDE_FORWARD, Map.of(
                MEIA_DIREITA, List.of(BALANCED, ATTACK),
                MEIA_ESQUERDA, List.of(BALANCED, ATTACK),
                PONTA_DIREITA, List.of(BALANCED, ATTACK, ROAMING),
                PONTA_ESQUERDA, List.of(BALANCED, ATTACK, ROAMING))),
        Map.entry(ADVANCED_FORWARD, Map.of(ATACANTE, List.of(ATTACK, VERSATILE, SUPPORT))),
        Map.entry(TARGET_FORWARD, Map.of(ATACANTE, List.of(ATTACK, BALANCED, WIDE))),
        Map.entry(FALSE_9, Map.of(ATACANTE, List.of(BUILD_UP, ATTACK))),
        Map.entry(POACHER, Map.of(ATACANTE, List.of(ATTACK, SUPPORT, VERSATILE))));
```

Methods:

```java
    public boolean validFor(DetailedPosition position) { return MATRIX.get(this).containsKey(position); }
    public Set<DetailedPosition> validPositions() { return MATRIX.get(this).keySet(); }
    public List<RoleFocus> focusesFor(DetailedPosition position) {
        return MATRIX.get(this).getOrDefault(position, List.of(canonicalDefault));
    }
    public RoleFocus defaultFocusFor(DetailedPosition position) { return focusesFor(position).get(0); }
    public RoleFocus canonicalDefaultFocus() { return canonicalDefault; }

    public double attackContribution(Player p) { return weighted(attackWeights, p.attributes()); }
    public double defenseContribution(Player p) { return weighted(defenseWeights, p.attributes()); }
    // weighted(...) — move the static helper verbatim from PlayerRole.

    /** The vanilla archetype a position falls back to when nothing is persisted/familiar. */
    public static RoleArchetype defaultFor(DetailedPosition position) {
        return switch (position) {
            case GOLEIRO -> GOALKEEPER;
            case ZAGUEIRO -> DEFENDER;
            case LATERAL_DIREITO, LATERAL_ESQUERDO -> FULLBACK;
            case VOLANTE -> HOLDING;
            case MEIA_CENTRAL -> BOX_TO_BOX;
            case MEIA_ARMADOR -> PLAYMAKER;
            case MEIA_DIREITA, MEIA_ESQUERDA, PONTA_DIREITA, PONTA_ESQUERDA -> WINGER;
            case ATACANTE -> ADVANCED_FORWARD;
        };
    }

    /** Wide creators boosted by AttackZone.LATERAIS (legacy: WINGER, FULL_BACK). */
    public boolean isWideCreator() {
        return this == WINGER || this == FULLBACK || this == WINGBACK
                || this == ATTACKING_WINGBACK || this == WIDE_PLAYMAKER || this == WIDE_MIDFIELDER;
    }

    /** Central creators boosted by AttackZone.CENTRO (legacy: PLAYMAKER, BOX_TO_BOX). */
    public boolean isCentralCreator() {
        return this == PLAYMAKER || this == BOX_TO_BOX || this == DEEP_LYING_PLAYMAKER
                || this == CLASSIC_10 || this == FALSE_9;
    }
```

Note: the static-import style for the MATRIX may need qualification (`DetailedPosition.GOLEIRO` etc.) since enum constants can't be statically imported into their own file's static initializer ambiguously — qualify as needed; the CONTENT is what's normative.

- [ ] **Step 3: Sync-guard + unit tests**

`RoleArchetypeFutbinSyncTest` — a checked-in copy of the futbin EAFC 26 taxonomy asserted against the enum. Encode the expectation as a plain data table in the test (position shorts × archetype × focus lists exactly as spec §2.1's table) and assert for every archetype: `validPositions()` and each `focusesFor(pos)` (order-sensitive, default first) equal the table. Also assert every position has ≥3 archetypes and `defaultFor(pos).validFor(pos)`.

`RoleArchetypeTest`:
- `legacyMappedArchetypesKeepTheOldPlayerRoleWeights` — assert the 10 legacy archetypes' weight maps equal the literal old values (copy the maps from the old `PlayerRole` into the test as expected values; e.g. POACHER attack `{VELOCIDADE 0.2, FINALIZACAO 0.8}`, defense empty).
- `contributionsMatchWeightedSums` — port the old `PlayerRoleTest` contribution assertions (POACHER 82.0 etc.) onto the mapped archetypes.
- `canonicalDefaultFocusIsValidSomewhere` — for each archetype, `canonicalDefaultFocus()` appears in at least one position's focus list.
- `focusLeansAreSmall` — every `RoleFocus` lean within [−0.1, 0.1].

- [ ] **Step 4: Run focused tests, then commit**

Run: `--tests 'br.com.carvoeiromanager.player.RoleArchetype*'`. Expected: PASS. Commit:

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/RoleFocus.java backend/src/main/java/br/com/carvoeiromanager/player/RoleArchetype.java backend/src/test/java/br/com/carvoeiromanager/player/RoleArchetypeTest.java backend/src/test/java/br/com/carvoeiromanager/player/RoleArchetypeFutbinSyncTest.java
git commit -m "feat(player): RoleArchetype + RoleFocus (EAFC 26 taxonomy) with futbin sync-guard"
```

### Task 3: Familiar roles on Player + generation

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/RoleArchetype.java` (add `defaultFamiliarFor`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerFamiliarRolesTest.java` (new)

**Interfaces (produced):**
- `Player.familiarRoles()` → `Map<RoleArchetype, Integer>` (2 = `++`, 1 = `+`), insertion-ordered
- `Player.familiarity(RoleArchetype)` → `int` 0/1/2
- `Player.setFamiliarRoles(List<String> tokens)` — tokens like `"WINGBACK++"`, validated
- `static List<String> RoleArchetype.defaultFamiliarFor(DetailedPosition primary, Attributes attrs, Random rng)` — 1 `++` (+ ~25% a second `+`)

- [ ] **Step 1: Failing tests**

`PlayerFamiliarRolesTest`:
- `constructedPlayersAlwaysHaveOneFullyFamiliarRoleValidForTheirPosition` — build 500 players via `Player.create(...)` across all four coarse positions; each has ≥1 familiarity-2 archetype and every familiar archetype is `validFor` one of `positions()`.
- `aboutAQuarterGetASecondSomewhatFamiliarRole` — share of players with a familiarity-1 entry within [18%, 32%].
- `highPassingMidfieldersLeanPlaymakerish` — call `RoleArchetype.defaultFamiliarFor(MEIA_CENTRAL, attrs, new Random(1))` directly with PASSE = 75 attributes: first token is `"DEEP_LYING_PLAYMAKER++"`; with PASSE = 50: `"BOX_TO_BOX++"`.
- `familiarityParsesAndRoundTrips` — `setFamiliarRoles(List.of("WINGBACK++","FULLBACK+"))` → `familiarity(WINGBACK)==2`, `familiarity(FULLBACK)==1`, `familiarity(POACHER)==0`; invalid token throws `IllegalArgumentException`.

Run: `--tests '...PlayerFamiliarRolesTest'` — expected COMPILATION FAILURE (methods absent).

- [ ] **Step 2: Implement**

`RoleArchetype.defaultFamiliarFor` (deterministic given the rng; one algorithm, documented):

```java
    /**
     * Generation-time familiar roles for a player: always one fully-familiar (++) archetype —
     * the position default, upgraded to a playmaker-type when the profile clearly leans that way —
     * plus a ~25% chance of a second, somewhat-familiar (+) archetype among the position's others.
     * Draws only from the caller's per-player Random.
     */
    public static List<String> defaultFamiliarFor(DetailedPosition primary, Attributes attrs, Random rng) {
        RoleArchetype picked = defaultFor(primary);
        int passe = attrs.get(Attribute.PASSE);
        if (passe >= 68) {
            if (primary == DetailedPosition.MEIA_CENTRAL || primary == DetailedPosition.VOLANTE) {
                picked = DEEP_LYING_PLAYMAKER;
            } else if (primary == DetailedPosition.ATACANTE) {
                picked = FALSE_9;
            }
        }
        final RoleArchetype first = picked;
        List<String> out = new ArrayList<>(2);
        out.add(first.name() + "++");
        List<RoleArchetype> others = Arrays.stream(values())
                .filter(a -> a.validFor(primary) && a != first)
                .toList();
        if (!others.isEmpty() && rng.nextInt(100) < 25) {
            out.add(others.get(rng.nextInt(others.size())).name() + "+");
        }
        return out;
    }
```

(The `out_first_placeholder` marker just means: filter `a != first` via a final local — write it properly.)

`Player`: add the field mapping the V40 column and accessors:

```java
    /** CSV of familiar role tokens, e.g. "WINGBACK++,FULLBACK+" (spec §2.3). */
    @Column(name = "familiar_roles", nullable = false, length = 120)
    private String familiarRoles = "";
```

```java
    public Map<RoleArchetype, Integer> familiarRoles() {
        Map<RoleArchetype, Integer> out = new LinkedHashMap<>();
        if (familiarRoles == null || familiarRoles.isBlank()) {
            return out;
        }
        for (String token : familiarRoles.split(",")) {
            int level = token.endsWith("++") ? 2 : 1;
            out.put(RoleArchetype.valueOf(token.substring(0, token.length() - (level == 2 ? 2 : 1))), level);
        }
        return out;
    }

    public int familiarity(RoleArchetype archetype) {
        return familiarRoles().getOrDefault(archetype, 0);
    }

    /** Replaces the familiar-role tokens (validated: parseable, level suffix required). */
    public void setFamiliarRoles(List<String> tokens) {
        for (String t : tokens) {
            if (!t.endsWith("+")) {
                throw new IllegalArgumentException("Familiar role token missing +/++ suffix: " + t);
            }
            RoleArchetype.valueOf(t.replaceAll("\\+", ""));   // throws on unknown archetype
        }
        this.familiarRoles = String.join(",", tokens);
    }
```

In the private constructor, after the alternates block, append (same `posRng`):

```java
        this.familiarRoles = String.join(",",
                RoleArchetype.defaultFamiliarFor(this.detailedPosition, attributes, posRng));
```

- [ ] **Step 3: Run tests, full player package, commit**

Run: `--tests 'br.com.carvoeiromanager.player.*'`. Expected: PASS.

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/Player.java backend/src/main/java/br/com/carvoeiromanager/player/RoleArchetype.java backend/src/test/java/br/com/carvoeiromanager/player/PlayerFamiliarRolesTest.java
git commit -m "feat(player): familiar role archetypes (++/+) with id-seeded generation"
```

### Task 4: The role-model swap — replace PlayerRole/RoleBehaviour end-to-end (V41)

The single big-churn task (precedent: GameStyle's "one big churn commit"). After it, `PlayerRole` and `RoleBehaviour` no longer exist. Neutral-settings behavior is preserved exactly: legacy-mapped archetype + canonical default focus + full familiarity + natural position ⇒ identical contributions and identical attribution weights.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/RoleAssignment.java`
- Create: `backend/src/main/resources/db/migration/V41__role_archetypes.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupPlayer.java`, `LineupStarter.java`, `LineupService.java`, `TacticalProfile.java`, `TacticsService.java`, `TeamStrengthCalculator.java`, `LiveMatchEngine.java`, `FixtureSimulator.java`, `LiveMatchService.java`, `LivePlayerResponse.java`, `PlayerResponse.java`
- Delete: `backend/src/main/java/br/com/carvoeiromanager/player/PlayerRole.java`, `RoleBehaviour.java`, `backend/src/test/java/br/com/carvoeiromanager/player/PlayerRoleTest.java`, `RoleBehaviourTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/RoleAssignmentContributionTest.java` (new), `backend/src/test/java/br/com/carvoeiromanager/match/RoleMigrationIT.java` (new), plus updating every test that references `PlayerRole` (list below).

**Interfaces (produced):**
- `public record RoleAssignment(RoleArchetype archetype, RoleFocus focus)` in package `player`, with:
  - `double attackContribution(Player p)` = `archetype.attackContribution(p) * focusAttackMult() * familiarityMult(p)`
  - `double defenseContribution(Player p)` = symmetric
  - `double focusAttackMult()` = `1.0 + focus.attackLean() - archetype.canonicalDefaultFocus().attackLean()` (defense symmetric)
  - `static double familiarityMult(Player p, RoleArchetype a)` → 2→1.00, 1→0.97, 0→0.92
  - `static RoleAssignment defaultFor(DetailedPosition pos)` = `new RoleAssignment(RoleArchetype.defaultFor(pos), RoleArchetype.defaultFor(pos).canonicalDefaultFocus())`
  - `static RoleAssignment resolve(Map<UUID, RoleAssignment> roles, Player p)` — persisted entry when present (NO validity re-check — write-time validation only, so legacy/improvised rows keep scoring with what the manager set), else `defaultFor(p.getDetailedPosition())`
- `TacticalProfile.roles()` becomes `Map<UUID, RoleAssignment>`
- DTO `LineupStarter(UUID playerId, String role, String focus, String instruction, Integer slot)` — `role` carries the ARCHETYPE name (wire-compat name), `focus` optional (null → default for the archetype), `slot` optional explicit slot index (null = auto). Keep a convenience constructor `(playerId, role)` for tests.
- `LineupPlayer` maps `archetype VARCHAR(24) NOT NULL`, `focus VARCHAR(16) NOT NULL`, `slot SMALLINT NULL`; `getArchetype()/getFocus()/getSlot()`, `create(lineupId, playerId, starting, RoleArchetype, RoleFocus)`.
- `PlayerResponse` gains `String focus`, `List<String> altPositions`, `List<String> familiarRoles` (raw tokens) — appended after the existing `role` field.

- [ ] **Step 1: `V41__role_archetypes.sql`**

```sql
-- Role archetype + focus replace the legacy PlayerRole on lineup rows (spec §2.4), and each row
-- gains an optional explicit formation slot (NULL = auto-assigned). Focus is seeded with each
-- archetype's canonical default so migrated lineups score byte-identically at unit level.
ALTER TABLE lineup_players ADD COLUMN archetype VARCHAR(24);
ALTER TABLE lineup_players ADD COLUMN focus VARCHAR(16);
ALTER TABLE lineup_players ADD COLUMN slot SMALLINT;

UPDATE lineup_players SET
    archetype = CASE role
        WHEN 'FULL_BACK' THEN 'FULLBACK'
        WHEN 'TARGET_MAN' THEN 'TARGET_FORWARD'
        WHEN 'HOLDING_MID' THEN 'HOLDING'
        WHEN 'BALL_PLAYING_DEF' THEN 'BALL_PLAYING_DEFENDER'
        ELSE role END,
    focus = CASE role
        WHEN 'GOALKEEPER' THEN 'DEFEND'
        WHEN 'BALL_PLAYING_DEF' THEN 'DEFEND'
        WHEN 'HOLDING_MID' THEN 'DEFEND'
        WHEN 'POACHER' THEN 'ATTACK'
        WHEN 'TARGET_MAN' THEN 'ATTACK'
        ELSE 'BALANCED' END;

ALTER TABLE lineup_players ALTER COLUMN archetype SET NOT NULL;
ALTER TABLE lineup_players ALTER COLUMN focus SET NOT NULL;
ALTER TABLE lineup_players DROP COLUMN role;
```

(The focus CASE must equal each mapped archetype's `canonicalDefaultFocus()` — `RoleMigrationIT` asserts this pairing via `RoleArchetype.valueOf(archetype).canonicalDefaultFocus().name()`.)

- [ ] **Step 2: Write `RoleAssignment` + `RoleAssignmentContributionTest` FIRST (they compile standalone)**

Key regression tests:
- `legacyDefaultsReproduceOldPlayerRoleContributionsExactly` — for each of the 10 legacy archetypes at canonical default focus and familiarity 2 (use `setFamiliarRoles(List.of(name + "++"))`), `attackContribution`/`defenseContribution` equal the hand-computed old `PlayerRole` weighted sums (port the numbers from the deleted `PlayerRoleTest`: POACHER attack 82.0 for VELOCIDADE 50/FINALIZACAO 90, HOLDING defense 64.0 for DESARME 80, GOALKEEPER defense 85.0/attack 0.0 for REFLEXOS 85).
- `unfamiliarArchetypeCosts8Percent` / `somewhatFamiliarCosts3Percent` — same player, familiarity 0 → ×0.92, 1 → ×0.97.
- `nonDefaultFocusShiftsContributionsByTheLeanDifference` — WINGER (canonical BALANCED) at ATTACK: attack ×1.08, defense ×0.92 vs BALANCED.
- `resolveFallsBackToPositionDefault` — empty map → `defaultFor(detailedPosition)`; persisted entry returned verbatim even if not valid for the player's position (grandfathering contract).

- [ ] **Step 3: Mechanical sweep**

Work through, in order (compile-driven):

1. `LineupPlayer`: swap `role` field for `archetype`/`focus`/`slot` per the interface block. The no-role `create(lineupId, playerId, starting)` factory defaults to `(RoleArchetype.BOX_TO_BOX, BALANCED)` — hmm, no: default to `(HOLDING, RoleFocus.DEFEND)`? **Decision: keep the old behavior shape** — the 3-arg factory is only used by legacy tests; make it `(RoleArchetype.BOX_TO_BOX, RoleFocus.BALANCED)` (the direct successor of the old `PlayerRole.BOX_TO_BOX` default).
2. `LineupStarter` + `SetLineupRequest`: per interface block; `SetLineupRequest` unchanged apart from the starter shape.
3. `LineupService`:
   - `validateAndParseRoles` → `validateAndParseAssignments(List<LineupStarter>, List<Player>, String formation)` returning `Map<UUID, RoleAssignment>`: parse archetype (`ErrorCode.INVALID_ROLE`, message `"Função desconhecida: X"`); parse focus when present else `archetype.canonicalDefaultFocus()`; validate the pair — when the starter has an explicit `slot`, the archetype must be `validFor` at least one accepted position of `FormationSlots.of(formation).get(slot)` and the focus must be in `focusesFor` that position (`"Função X não é válida para a posição Y"` / `"Foco X não é válido para a função Y"`); with NO explicit slot, archetype must be valid for ≥1 of the player's `positions()`.
   - New `validateSlots(List<LineupStarter> starters, String formation)`: explicit slots must be in `[0, 10]` and unique — PT-BR 422s via `ErrorCode.INVALID_LINEUP` (`"Posição no campo inválida"`, `"Dois jogadores na mesma posição do campo"`). Any player MAY take the GK slot (spec §5.1 allows improvising in goal).
   - Relax `validateGkCount` from "exactly 1" to "AT MOST 1" GK among starters (message: `"A escalação pode ter no máximo 1 goleiro, mas encontrou N"`). A lineup with 0 goalkeepers (outfielder improvised in goal) is now legal — the engine already degrades it naturally (`gkDefense` stays 0.0 and the improviser's fit is ×0.50). Two GK players in the XI stays blocked (deliberate limit: it breaks the engine's single-keeper defense split).
   - Persist: `LineupPlayer.create(lineupId, id, starting, assignment.archetype(), assignment.focus())` + `lp.setSlot(starter.slot())`.
   - `toLineupStarter`: `new LineupStarter(lp.getPlayerId(), lp.getArchetype().name(), lp.getFocus().name(), lp.getInstruction() == null ? null : lp.getInstruction().name(), lp.getSlot())`.
   - `currentRoles` → returns `Map<UUID, RoleAssignment>`; `toPlayerResponse` gains `focus` + `altPositions` (names) + `familiarRoles` (raw tokens) fields.
4. `TacticalProfile`: `Map<UUID, RoleAssignment> roles` (Javadoc update). `TacticsService.fromLineup`: build `RoleAssignment(lp.getArchetype(), lp.getFocus())`; fill missing XI members with `RoleAssignment.defaultFor(p.getDetailedPosition())`. `defaultProfile`: same default fill (AI archetype auto-pick upgrades in Task 5).
5. `TeamStrengthCalculator`: `RoleAssignment role = RoleAssignment.resolve(profile.roles(), p);` then `role.attackContribution(p)` / `role.defenseContribution(p)` (focus + familiarity now INSIDE RoleAssignment — do not double-apply). Replace `positionFitMult` with the distance version:

```java
    /**
     * Position fit from the tactical distance between the assigned slot and the player's playable
     * positions (spec §4.1): natural x1.00, adjacent x0.92, far x0.80, GK-cross x0.50. No slot
     * assignment (legacy/empty) is the x1.0 neutral no-op. Multi-position aware: the BEST of the
     * player's positions counts.
     */
    private double positionFitMult(Player p, TacticalProfile profile) {
        FormationSlots.Slot slot = profile.slotAssignments() == null
                ? null : profile.slotAssignments().get(p.getId());
        if (slot == null) {
            return 1.00;
        }
        int best = Integer.MAX_VALUE;
        for (DetailedPosition mine : p.positions()) {
            for (DetailedPosition accepted : slot.accepted()) {
                best = Math.min(best, mine.distance(accepted));
            }
        }
        return switch (best) {
            case 0 -> 1.00;
            case 1 -> 0.92;
            case 2 -> 0.80;
            default -> 0.50;
        };
    }
```

6. `LiveMatchEngine` + `FixtureSimulator` `assistWeight`: signature `(Player p, AttackZone zone, RoleArchetype role, PlayerInstruction instr)`; wide branch `if (role.isWideCreator())`, central branch `if (role.isCentralCreator())`. Call sites: `RoleAssignment.resolve(roles, p).archetype()`.
7. `LiveMatchService.livePlayer`: `RoleAssignment role = RoleAssignment.resolve(profile.roles(), p)`; pass `role.archetype().name()` into `LivePlayerResponse.role` (record unchanged).
8. Delete `PlayerRole.java`, `RoleBehaviour.java` and their two test files.
9. Test sweep — every file from this grep list needs its role literals mapped (`FULL_BACK→FULLBACK`, `TARGET_MAN→TARGET_FORWARD`, `HOLDING_MID→HOLDING`, `BALL_PLAYING_DEF→BALL_PLAYING_DEFENDER`, others unchanged; `Map<UUID, PlayerRole>` → `Map<UUID, RoleAssignment>` built via `RoleAssignment.defaultFor` or explicit pairs): `LiveMatchOpponentVisibilityIT, LiveMatchServiceOpponentGoalCommentaryIT, LiveMatchAdvanceToIT, YouthExclusionIT, LiveMatchLosingStreakIT, AiManagerBrainTest, TacticsServiceIT, LineupServiceBenchTest, LiveMatchAdvanceIT, TeamStrengthCalculatorTest, LineupServiceIT, TacticsApiIT, LiveMatchSubstituteIT, PlayerInstructionDeterminismTest, LineupPlayerInstructionIT, PlayerInstructionWeightTest, LiveMatchTacticsIT, LiveMatchParallelSimulationIT, LiveMatchServiceKickoffIT, MatchCalibrationTest, InjuryEngineIT, LiveMatchFinishIT, TacticsEntityIT, LiveMatchHalfTimeIT, AttackZoneWeightTest, LiveMatchFoulVisibilityIT, LiveMatchParallelScoresStateIT, LiveMatchEngineTest, TransferExecutorIT, SeasonAdvanceServiceRefactorTest, AdvancePreviewIT, SeasonRolloverServiceIT`.
   IMPORTANT: where a test asserted a strength/score value that now shifts because default familiarity multipliers apply (a test player whose familiar roles don't match the assigned archetype), prefer constructing the player so familiarity is 2 (`setFamiliarRoles(List.of("<archetype>++"))`) to keep the old expected numbers — only recompute expectations where that's unnatural.
10. `RoleMigrationIT`: pre-insert lineup_players rows with each legacy role (raw SQL, pre-V41 shape) → after migration: archetype/focus pairs match the mapping table AND `focus == RoleArchetype.valueOf(archetype).canonicalDefaultFocus().name()`; `slot` NULL; `role` column gone.

- [ ] **Step 4: Full backend suite**

Run the FULL suite (600000ms). Expected: PASS. Determinism spot-check: `PlayerInstructionDeterminismTest` and `MatchCalibrationTest` must pass UNCHANGED where their squads are natural/familiar (that's the byte-identical contract for mechanics).

- [ ] **Step 5: Commit**

```bash
git add -u backend/src backend/src/main/resources/db/migration/V41__role_archetypes.sql backend/src/main/java/br/com/carvoeiromanager/player/RoleAssignment.java backend/src/test/java/br/com/carvoeiromanager/match/RoleAssignmentContributionTest.java backend/src/test/java/br/com/carvoeiromanager/match/RoleMigrationIT.java
git commit -m "feat(match): RoleArchetype+RoleFocus replace PlayerRole/RoleBehaviour end-to-end (V41)"
```

(`git add -u backend/src` is acceptable here ONLY because this task touches dozens of tracked files under backend/src and deletes two — verify with `git status` that nothing unrelated (seed JSONs, docs) is staged before committing.)

### Task 5: Natural-first selection + AI role auto-pick + explicit-slot profiles

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (bestXi path unchanged in shape — SlotAssigner does the work)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerNaturalFirstTest.java` (new), extend `TacticsServiceIT`

**Interfaces:**
- `SlotAssigner.assign(...)` same signature; tiering becomes: tier 1 = NATURAL (any of `p.positions()` ∈ slot accepted), fallback = smallest `min-distance(p.positions(), slot.accepted())`, then byRating. Scarcity ordering now counts natural candidates.
- `SlotAssigner.assignToSlots(List<Player> xi, String formation, Map<UUID, Integer> explicitSlots)` — new overload honoring explicit slot indices first, auto-filling the rest (old 2-arg overload delegates with `Map.of()`).
- `TacticsService` produces AI/default archetypes via best familiarity.

- [ ] **Step 1: Failing tests**

`SlotAssignerNaturalFirstTest` (build players with explicit `setPositions`/`setFamiliarRoles`; use `FormationSlots.of("4-4-2")`):
- `aNaturalPlayerAlwaysBeatsAHigherRatedImproviser` — LE slot: natural LE overall 60 wins over ZAG overall 90.
- `alternatePositionsCountAsNatural` — primary ZAG + alternate LE (overall 70) beats pure ZAG 90 for the LE slot.
- `fallbackPicksTheSmallestDistance` — no LE anywhere: ZAG (distance 1) chosen over PONTA_ESQUERDA... careful: PE↔LE distance is 2 (not adjacent) — use ZAG (1) vs ATACANTE (2): ZAG wins even at lower overall.
- `deterministicTieBreakByRatingThenId` — two equal candidates: higher overall, then smaller id.
- `explicitSlotsPinPlayersAndAutoFillsTheRest` — explicit map {playerX → slot 5} keeps X there even when a better natural exists; remaining slots fill natural-first without X.
- `wellFormedNaturalXiAssignsAllExact` — 11 players matching 4-4-2's slots 1:1 → every assignment natural (grades to 1.0).

`TacticsServiceIT` additions:
- `aiProfilePicksTheBestFamiliarArchetypeValidForTheAssignedSlot` — an AI XI player with `WINGBACK++` on an LD slot gets archetype WINGBACK (not FULLBACK), focus = WINGBACK's default for LD (BALANCED).
- `managedProfileHonoursExplicitSlots` — lineup rows with `slot` set produce `slotAssignments` honoring them.

- [ ] **Step 2: Implement**

`SlotAssigner.pickForSlot` becomes:

```java
    private static Player pickForSlot(List<Player> available, FormationSlots.Slot slot, Comparator<Player> byRating) {
        return available.stream().filter(p -> isNatural(p, slot)).max(byRating)
                .orElseGet(() -> available.stream()
                        .min(Comparator.comparingInt((Player p) -> distanceTo(p, slot))
                                .thenComparing(byRating.reversed()))
                        .orElseThrow());
    }

    private static boolean isNatural(Player p, FormationSlots.Slot slot) {
        return p.positions().stream().anyMatch(slot.accepted()::contains);
    }

    private static int distanceTo(Player p, FormationSlots.Slot slot) {
        int best = Integer.MAX_VALUE;
        for (DetailedPosition mine : p.positions()) {
            for (DetailedPosition acc : slot.accepted()) {
                best = Math.min(best, mine.distance(acc));
            }
        }
        return best;
    }
```

Scarcity in `assign(...)`: `tier1Count` counts `isNatural`. New `assignToSlots` overload: place explicit entries first (skip invalid indices defensively), remove them from the pool, run the greedy fill over remaining slots, merge.

`TacticsService`: extract

```java
    /** The best archetype for a player in a slot: highest familiarity among archetypes valid for the
     *  slot's position (ties by familiar-CSV order), else the slot position's default. Focus = that
     *  archetype's default for the slot position. A null slot falls back to the player's primary. */
    static RoleAssignment bestAssignmentFor(Player p, FormationSlots.Slot slot) {
        DetailedPosition pos = slot != null ? slot.accepted().iterator().next() : p.getDetailedPosition();
        RoleArchetype best = null;
        int bestLevel = 0;
        for (Map.Entry<RoleArchetype, Integer> e : p.familiarRoles().entrySet()) {   // insertion order
            if (e.getKey().validFor(pos) && e.getValue() > bestLevel) {
                best = e.getKey();
                bestLevel = e.getValue();
            }
        }
        if (best == null) {
            best = RoleArchetype.defaultFor(pos);
        }
        return new RoleAssignment(best, best.defaultFocusFor(pos));
    }
```

Use it in `defaultProfile` (AI) for every XI member after `slotAssignments` is computed; in `fromLineup`, only for XI members missing a persisted row. `fromLineup` builds `explicitSlots` from `lp.getSlot()` and calls the new overload; `defaultProfile` passes `Map.of()`.

- [ ] **Step 3: Run match-package tests + full suite, commit**

Some ITs' expected XIs may legitimately change (natural-first vs old detailed-tier) — update ONLY selection-order expectations, never engine numbers.

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerNaturalFirstTest.java backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java <adjusted-ITs>
git commit -m "feat(match): natural-first slot assignment, AI familiar-role auto-pick, explicit slots"
```

---

## Phase C — Real-player research

### Task 6: Research all 804 players + seed schema + validation test

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (add `"positions"` + `"roles"` per player)
- Modify: `backend/src/main/resources/seed/free-agents.json` (same)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedPlayer.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedResearchValidationTest.java` (new)
- Scratch: research fragments in the session scratchpad (NOT committed)

**Interfaces:**
- `SeedPlayer` gains `List<String> positions, List<String> roles` (both nullable for robustness; the validation test enforces presence in the shipped files).
- Seed JSON per player gains: `"positions": ["PONTA_ESQUERDA", "MEIA_ESQUERDA"], "roles": ["INSIDE_FORWARD++", "WINGER+"]`.

- [ ] **Step 1: Add the schema + wiring (before research, so the validation test is ready)**

`SeedPlayer`: append the two components. `SaveGameService` — in BOTH the club-squad loop and the free-agent loop, right after `Player.create(...)` (order of operations preserved; setters override the constructor's id-seeded defaults deterministically):

```java
                if (seedPlayer.positions() != null && !seedPlayer.positions().isEmpty()) {
                    List<DetailedPosition> researched = seedPlayer.positions().stream()
                            .map(DetailedPosition::valueOf).toList();
                    player.setPositions(researched.get(0), researched.subList(1, researched.size()));
                }
                if (seedPlayer.roles() != null && !seedPlayer.roles().isEmpty()) {
                    player.setFamiliarRoles(seedPlayer.roles());
                }
```

`SeedResearchValidationTest` (plain unit test, Jackson-load both seed files):
- Every one of the 804 players has `positions` (1–3 entries) and `roles` (1–2 entries).
- `positions[0]`'s `coarse()` equals the player's `position` field; no entry is GOLEIRO unless `position == GK`; GK players have exactly `["GOLEIRO"]`; no duplicates.
- Every role token parses (`ARCHETYPE++` or `ARCHETYPE+`), the first token is `++`, and each archetype is `validFor` at least one of the player's positions.
- Counts: clubs.json players == 625 across 28 clubs; free-agents == 179.

Run it now — expected FAIL ("player X missing positions") until research lands.

- [ ] **Step 2: Research (parallel subagents)**

Dispatch parallel research subagents (superpowers:dispatching-parallel-agents; ~7 agents × 4 clubs + 2 agents for free agents split ~90/89). Each agent receives its clubs' player lists (name, position, age, club) extracted from the seed JSON and writes ONE fragment JSON to the scratchpad: `{"<club name>": {"<player name>": {"positions": [...], "roles": [...]}}}`.

Research rubric (give verbatim to every agent):
1. These are real 2026 Brazilian Série A/B squads. Use web sources (Transfermarkt, Sofascore, oGol, futbin) plus your own knowledge of each player.
2. `positions`: 1–3 `DetailedPosition` enum names, primary FIRST. The primary MUST be coarse-consistent with the given `position` (GK/DEF/MID/ATT) — if real life disagrees with the seed's coarse position, pick the closest detailed position INSIDE the seed band and put the real one as an alternate only if it's outfield. Alternates = positions the player genuinely plays (e.g. a right-back who plays RM). GK → exactly `["GOLEIRO"]`.
3. `roles`: 1–2 archetype tokens; first with `++` (their defining style), optional second with `+`. Must be valid for one of their positions per this matrix: <paste the spec §2.1 table>. Pick by playing style (e.g. a crossing full-back → `WINGBACK++`; a ball-playing CB → `BALL_PLAYING_DEFENDER++`; a poacher → `POACHER++`).
4. Unknown/obscure players: single position (from their seed detailed data if unsure, else band default) + the position-default archetype `++` (defaults: GOL→GOALKEEPER, ZAG→DEFENDER, LD/LE→FULLBACK, VOL→HOLDING, MC→BOX_TO_BOX, MEI→PLAYMAKER, MD/ME/PD/PE→WINGER, ATA→ADVANCED_FORWARD). NEVER invent exotic combos for players you can't verify.
5. Output STRICT JSON, enum names only, every listed player present.

- [ ] **Step 3: Merge fragments into the seed files**

Write a one-off Node script in the scratchpad (`merge-research.mjs`): load `clubs.json` + `free-agents.json` + all fragments; for each player match by (club, name) / name; insert `positions` and `roles` keys after `nationality`; NO other reformatting (preserve existing key order/format by operating on the parsed objects and re-serializing with the file's existing 2-space style — verify with `git -c diff.external= diff --no-ext-diff --stat` that only the two seed files changed and the diff is additive). Fail loudly on any unmatched player.

- [ ] **Step 4: Validate + full suite + commit**

Run `SeedResearchValidationTest` → PASS. Run the full backend suite (SaveGame ITs load the seed). Commit ONLY:

```bash
git add backend/src/main/resources/seed/clubs.json backend/src/main/resources/seed/free-agents.json backend/src/main/java/br/com/carvoeiromanager/seed/SeedPlayer.java backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java backend/src/test/java/br/com/carvoeiromanager/seed/SeedResearchValidationTest.java
git commit -m "feat(seed): researched real positions + role archetypes for all 804 players"
```

### Task 7: V42 research backfill for existing saves

**Files:**
- Create: `backend/scripts/gen-research-backfill.mjs` (committed — regenerable)
- Create: `backend/src/main/resources/db/migration/V42__research_backfill.sql` (generated output, committed)
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/ResearchBackfillMigrationIT.java` (new)

- [ ] **Step 1: Generator script**

Node script reading the two seed JSONs and emitting SQL: for every club player one statement (escape `'` by doubling):

```sql
UPDATE players p SET detailed_position = '<pos0>',
    alt_positions = <NULL or '<p1,p2>'>, familiar_roles = '<tokens>'
FROM clubs c
WHERE p.club_id = c.id AND c.name = '<club name>' AND p.name = '<player name>';
```

For free agents (only names appearing EXACTLY ONCE in free-agents.json — skip ambiguous):

```sql
UPDATE players SET detailed_position = ..., alt_positions = ..., familiar_roles = ...
WHERE club_id IS NULL AND name = '<player name>';
```

Header comment in the generated file: `-- GENERATED by backend/scripts/gen-research-backfill.mjs from the seed research data — do not hand-edit. Unmatched players (newgens, transfers, renames) keep their V40-derived values (spec §3.1c).`

- [ ] **Step 2: `ResearchBackfillMigrationIT`**

Testcontainers: insert a save with a club named exactly as a seed club + a player named as one of its researched players (legacy detailed_position) + one newgen-named player → migrate → researched player has the researched triple; the newgen keeps its prior values; a free agent with a unique seed name gets updated.

- [ ] **Step 3: Full suite, commit**

```bash
git add backend/scripts/gen-research-backfill.mjs backend/src/main/resources/db/migration/V42__research_backfill.sql backend/src/test/java/br/com/carvoeiromanager/player/ResearchBackfillMigrationIT.java
git commit -m "feat(player): V42 name+club-matched research backfill for existing saves"
```

---

## Phase D — Frontend

### Task 8: Frontend foundation — types, labels, pitchSlots, shared slot assignment

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/features/players/positionLabels.ts`
- Modify: `frontend/src/features/pitch/pitchSlots.ts`
- Create: `frontend/src/features/players/roleLabels.ts`
- Create: `frontend/src/features/pitch/assignSlots.ts`
- Test: `frontend/src/features/pitch/pitchSlots.test.ts` (update), `frontend/src/features/pitch/assignSlots.test.ts` (new), `frontend/src/features/players/roleLabels.test.ts` (new)
- Plus compile fixes everywhere `PlayerRole`/old `DetailedPosition` unions are referenced (`npm run build` drives the list; expect `SquadPage.tsx`, `LiveMatchPage` player lists, transfer/free-agent pages).

**Interfaces:**
- `types.ts`: `DetailedPosition` = 12-name union; `RoleArchetype` = 30-name union; `RoleFocus` = 10-name union; `PlayerRole` type DELETED (alias `RoleArchetype` where the wire field is still called `role`); `LineupStarter` gains `focus: RoleFocus | null` and `slot: number | null`; `PlayerResponse` gains `focus: string | null`, `altPositions: DetailedPosition[]`, `familiarRoles: string[]`.
- `positionLabels.ts`: the three records extended to 12 keys (labels/shorts exactly as Task 1's enum); add `POSITION_DISTANCE`-lite helper `isNatural(p: {detailedPosition?, altPositions?}, accepted: DetailedPosition[]): boolean`.
- `roleLabels.ts`: `ARCHETYPE_LABEL: Record<RoleArchetype, string>` (PT-BR labels from Task 2), `FOCUS_LABEL: Record<RoleFocus, string>`, `ARCHETYPES_FOR_POSITION: Record<DetailedPosition, RoleArchetype[]>` and `FOCUSES_FOR: (a: RoleArchetype, pos: DetailedPosition) => RoleFocus[]` — a hand-mirrored copy of the backend MATRIX (the test is the sync-guard).
- `assignSlots.ts`: `assignSlots(players: PlacedPlayer[], formation: string, overrides: Record<number, string>): (PlacedPlayer | null)[]` — TS mirror of SlotAssigner's natural-first greedy (same scarcity ordering, natural tier, distance fallback, overall-then-id tie-break) returning one entry per slot index.

- [ ] **Step 1:** Update `pitchSlots.ts`: new consts (`mc, md, me, pd, pe`; `st` becomes `['ATACANTE']`), rewrite every LAYOUTS `acc` array to mirror Task 1's `FORMATIONS` exactly (same left→right, deepest-first order; e.g. `'4-4-2'` MID row `acc: [md, vol, mc, me]`, `'4-3-3'` ATT row `acc: [pd, st, pe]`). DELETE the per-line `DEFAULT_ROLE` record (a single archetype per coarse line can no longer be valid — FULLBACK is invalid for a ZAG); `roleLabels.ts` instead exports `defaultArchetypeFor(pos: DetailedPosition): RoleArchetype` mirroring the backend `RoleArchetype.defaultFor` switch (GOLEIRO→GOALKEEPER, ZAGUEIRO→DEFENDER, LD/LE→FULLBACK, VOLANTE→HOLDING, MC→BOX_TO_BOX, MEI→PLAYMAKER, MD/ME/PD/PE→WINGER, ATACANTE→ADVANCED_FORWARD). SquadPage/pitchSlots `DEFAULT_ROLE` usages switch to `defaultArchetypeFor(player.detailedPosition ?? bandFallback)` as a mechanical compile fix now (full UX rework is Task 9); `Slot.defaultRole` becomes `defaultArchetypeFor(accepted[0])` computed in `pitchSlots()`.
- [ ] **Step 2:** `pitchSlots.test.ts` — per formation: band counts equal the code digits AND the flattened accepted-position list matches a hard-coded expectation table copied from Task 1's backend map (the cross-stack sync-guard).
- [ ] **Step 3:** `roleLabels.test.ts` — assert the full ARCHETYPES_FOR_POSITION/FOCUSES_FOR tables against the same spec §2.1 data (mirrors the backend futbin sync test; drift on either side now fails somewhere).
- [ ] **Step 4:** `assignSlots.test.ts` — port the scenarios of `SlotAssignerNaturalFirstTest` (natural beats stronger improviser; override pins; deterministic).
- [ ] **Step 5:** `npm run build` clean + `npx vitest run` green. Commit (named files + whatever pages needed mechanical union renames):

```bash
git add frontend/src/api/types.ts frontend/src/features/players/positionLabels.ts frontend/src/features/players/roleLabels.ts frontend/src/features/pitch/pitchSlots.ts frontend/src/features/pitch/assignSlots.ts frontend/src/features/pitch/pitchSlots.test.ts frontend/src/features/pitch/assignSlots.test.ts frontend/src/features/players/roleLabels.test.ts <compile-fix files>
git commit -m "feat(front): 12-position + archetype/focus foundation with cross-stack sync-guards"
```

### Task 9: Slot picker + role/focus selects on SquadPage

**Files:**
- Create: `frontend/src/features/squad/SlotPickerDialog.tsx`
- Modify: `frontend/src/features/squad/SquadPage.tsx`, `frontend/src/features/pitch/Pitch.tsx`
- Test: `frontend/src/features/squad/SlotPickerDialog.test.tsx` (new), extend `SquadPage` tests

**Behavior (spec §5.1/§5.2):**
- `Pitch` gains optional `onSlotClick?: (slotIndex: number) => void` and renders tokens from a caller-supplied per-slot assignment (new optional prop `assigned?: (PlacedPlayer | null)[]`; when absent, keep the legacy coarse-line fill for read-only usages like the live match).
- SquadPage state: `slotOverrides: Record<number, string>`; display assignment = `assignSlots(selectedPlayers, formation, slotOverrides)`.
- Tapping a slot opens `SlotPickerDialog`: header "Quem joga aqui? ({slot short label})"; list = whole squad, natural players for the slot first (by overall desc), then the rest each flagged with a warning chip `Improvisado` + their natural shorts (e.g. "PE · ME"); currently-assigned player highlighted; picking sets the override (and adds the player to `selected` if absent, swapping out the displaced occupant); an "Automático" action clears that slot's override. The GK slot lists goalkeepers first but allows anyone; OUTFIELD slot pickers exclude GK-position players (the backend allows at most 1 GK in the XI, so a keeper improvised outfield would always 422).
- Improvised tokens (slot accepted ∌ any of player's positions incl. alternates — use `isNatural`) get the existing amber `outOfPosition` tint; tooltip "Improvisado — posição natural: {labels}".
- "Selecionar melhores" resets: `slotOverrides = {}`, picks natural-first XI via `assignSlots` over the whole eligible squad, sets each picked player's archetype to their best familiar valid for their assigned slot (parse `familiarRoles` tokens; ties → first token) and focus to that archetype's default (`FOCUSES_FOR(...)[0]`).
- Role/focus selects (replacing the old role select wherever it lives in the Plantel table): "Função" options = `ARCHETYPES_FOR_POSITION[slotPosition]` (familiar ones first with ★★/★ suffixes), "Foco" options = `FOCUSES_FOR(archetype, slotPosition)`; changing Função resets Foco to its default. For bench players (no slot) use the player's primary position.
- Save payload: starters map to `{ playerId, role, focus, instruction, slot: slotOverrides-inverse[playerId] ?? null }`.

- [ ] **Step 1:** RTL tests first: picker ordering (natural first), improvisado flag, override → warn tint, "Automático" clears, best-XI reset clears overrides and sets familiar archetypes, focus resets on archetype change, save payload carries slot/focus.
- [ ] **Step 2:** Implement; `npx vitest run` + `npm run build` green.
- [ ] **Step 3:** Commit:

```bash
git add frontend/src/features/squad/SlotPickerDialog.tsx frontend/src/features/squad/SlotPickerDialog.test.tsx frontend/src/features/squad/SquadPage.tsx frontend/src/features/pitch/Pitch.tsx <squad test files>
git commit -m "feat(front): slot picker with improvisation warnings + role/focus selects"
```

### Task 10: PlayerDetails + remaining surfaces

**Files:**
- Modify: `frontend/src/features/squad/PlayerDetails.tsx` (+ its tests)
- Touch-up: any page showing position labels that should now show multi-position info (transfer market, free agents, live match lineups — display only).

**Behavior (spec §5.2):** PlayerDetails gains two read-only lines under the position: **"Posições"** — shorts joined by " · " (primary first, e.g. "PE · ME"); **"Funções conhecidas"** — `ARCHETYPE_LABEL` with ★★ for `++` and ★ for `+` (e.g. "Atacante por dentro ★★, Ponta ★"). Rendered wherever the modal appears (fields come from `PlayerResponse` — no new fetches).

- [ ] **Step 1:** RTL: both lines render from `altPositions`/`familiarRoles`; absent alternates → single short; tokens parse.
- [ ] **Step 2:** Implement + vitest + build. Commit:

```bash
git add frontend/src/features/squad/PlayerDetails.tsx <test files> <touched pages>
git commit -m "feat(front): positions + known-roles lines in player details"
```

---

## Phase E — Verification

### Task 11: Full verification + e2e

- [ ] **Step 1:** Full backend suite (check for concurrent gradle first) + full frontend `npx vitest run` + `npm run build`. All green.
- [ ] **Step 2:** E2E in the browser (preview tools; dev servers via launch config, never Bash): new career → Elenco: open a player → sees Posições/Funções conhecidas; pitch → tap a slot → picker shows naturals first → improvise a ZAG at LE → amber tint + tooltip; set Função/Foco; save; reload → persists; "Selecionar melhores" → resets to natural XI, no amber tokens; play a live match → no console errors, opponent lineup renders.
- [ ] **Step 3:** Report results to the controller (screenshots of pitch with an improvised token + picker dialog). No commit unless fixes were needed.

---

## Cross-cutting rules

1. **Numbers are final:** position-fit 1.00/0.92/0.80/0.50; familiarity 1.00/0.97/0.92; focus leans as Task 2's table; archetype weights as Task 2's code. Do not re-tune.
2. **Byte-identical contract is UNIT-level, not save-level:** legacy-mapped archetype + canonical default focus + familiarity 2 + natural slot ⇒ identical contributions/attribution weights to the old model. Whole-save results legitimately shift (new slots, researched data) — never "fix" that by touching engine draws.
3. **Grandfathering:** `RoleAssignment.resolve` never re-validates persisted rows; validation is write-time only.
4. **Three-way formation sync** (backend `FormationSlots` / frontend `LAYOUTS` acc / `FORMATION_SHAPE`) is guarded by tests on both sides — change all together or not at all.
5. **Concurrent session:** before each task, `git fetch && git log --oneline -3 origin/main`; rebase surprises → stop and report, don't force anything.
6. In-flight lineups mid-deploy: nullable `slot`, focus fallback to canonical default, and resolve-fallback cover every legacy row; never assume a new column is populated.

## Execution notes

- Execute via superpowers:subagent-driven-development: fresh implementer subagent per task, independent reviewer per task, ledger at `.git/sdd/progress.md`; Task 6's research batches use parallel subagents as described inline.
- Suggested implementer effort: Tasks 1, 2, 4, 5, 9 are the heavy ones; Tasks 3, 7, 10 are light.
- User must be asked before any push. Commit locally per task as specified.
