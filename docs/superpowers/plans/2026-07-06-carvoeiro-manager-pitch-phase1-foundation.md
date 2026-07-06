# Pitch View — Phase 1 (Foundation + Formations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the reusable, kit-coloured `Pitch` component + the 16-formation coordinate map, and render it read-only on the Squad screen from the saved lineup.

**Architecture:** A pure `pitchSlots(formation)` map and pure `kit.ts` colour helpers feed a presentational `Pitch` (SVG field) that positions DOM `PlayerToken`s. Backend adds the 10 new formations to the `Formation` enum and exposes the managed club's kit colours on the squad endpoint. No live-match or drag work in this phase.

**Tech Stack:** Backend Java 25 / Spring Boot / JUnit5 + AssertJ. Frontend React 19, MUI 9, TanStack Query 5, Vitest + React Testing Library, TypeScript (`tsc -b` deploy guard).

## Global Constraints

- Determinism: do NOT touch `LiveMatchEngine`, `FixtureSimulator`, or any RNG/draw-order code. New formation multipliers only feed the existing (tested) `TeamStrengthCalculator` path.
- Hidden data: the pitch consumes only already-exposed player fields (no raw youth `potential`, no engine internals).
- Formation shape convention (matches existing `4-2-3-1 → 4/5/1`): count by intrinsic Position — attacking-mid slots count as MID, wing-backs as DEF, withdrawn/second strikers as ATT.
- Formation multipliers stay within the existing band **0.88–1.14**. Exact values in the table below (from the spec).
- Frontend deploy guard: every frontend commit must be green under `tsc -b` (it type-checks `.test.tsx`), not just Vitest.
- Kit colours: token fill = club `primaryColor`; number/trim = club `secondaryColor`, falling back to black/white by luminance when contrast is poor.
- Token shirt numbers are a stable 1–11 derived from slot order (GK=1, then DEF/MID/ATT left-to-right) — players carry no squad number.
- JDK for gradle: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`; run gradle from `backend/`.
- Commit style: end messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Never push, never `--amend`. Scoped `git add` (name the files).
- Known pre-existing flaky (NOT this work): `RolloverContractsIT`, `SeedLoaderTest`.

**Formation catalogue (backend enum values + frontend shapes):**

| code | attackMult | defenseMult | shape DEF/MID/ATT | status |
|------|-----------|-------------|-------------------|--------|
| 4-4-2 | 1.00 | 1.00 | 4/4/2 | existing |
| 4-3-3 | 1.10 | 0.94 | 4/3/3 | existing |
| 3-5-2 | 1.06 | 0.96 | 3/5/2 | existing |
| 5-3-2 | 0.92 | 1.10 | 5/3/2 | existing |
| 4-5-1 | 0.96 | 1.06 | 4/5/1 | existing |
| 4-2-3-1 | 1.04 | 1.00 | 4/5/1 | existing |
| 4-1-4-1 | 0.98 | 1.05 | 4/5/1 | NEW |
| 4-3-2-1 | 1.00 | 1.04 | 4/5/1 | NEW |
| 3-4-3 | 1.12 | 0.90 | 3/4/3 | NEW |
| 4-4-1-1 | 0.99 | 1.02 | 4/4/2 | NEW |
| 5-4-1 | 0.90 | 1.12 | 5/4/1 | NEW |
| 4-2-4 | 1.14 | 0.88 | 4/2/4 | NEW |
| 3-4-2-1 | 1.06 | 0.95 | 3/6/1 | NEW |
| 4-1-2-1-2 | 1.02 | 1.00 | 4/4/2 | NEW |
| 5-2-3 | 1.02 | 1.02 | 5/2/3 | NEW |
| 4-3-1-2 | 1.03 | 0.99 | 4/4/2 | NEW |

---

## Task 1: Expand the backend `Formation` enum to 16

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Formation.java:4-9`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/FormationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: 10 new `Formation` enum constants, each with `.code()`, `.attackMult()`, `.defenseMult()`. `Formation.fromCode(code)` now resolves the 10 new codes; unknown still → `F_4_4_2`.

- [ ] **Step 1: Add the failing tests** — append to `FormationTest.java` (inside the class):

```java
    @Test
    void mapsAllSixteenCodes() {
        String[] codes = {
            "4-4-2", "4-3-3", "3-5-2", "5-3-2", "4-5-1", "4-2-3-1",
            "4-1-4-1", "4-3-2-1", "3-4-3", "4-4-1-1", "5-4-1", "4-2-4",
            "3-4-2-1", "4-1-2-1-2", "5-2-3", "4-3-1-2",
        };
        for (String code : codes) {
            assertThat(Formation.fromCode(code).code()).isEqualTo(code);
        }
        assertThat(Formation.values()).hasSize(16);
    }

    @Test
    void everyFormationMultiplierStaysInBand() {
        for (Formation f : Formation.values()) {
            assertThat(f.attackMult()).isBetween(0.88, 1.14);
            assertThat(f.defenseMult()).isBetween(0.88, 1.14);
        }
    }

    @Test
    void extremeFormationsBiasCorrectly() {
        assertThat(Formation.F_4_2_4.attackMult()).isGreaterThan(Formation.F_4_4_2.attackMult());
        assertThat(Formation.F_5_4_1.defenseMult()).isGreaterThan(Formation.F_4_4_2.defenseMult());
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home && cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.player.FormationTest'`
Expected: FAIL — `F_4_2_4`/`F_5_4_1` symbols don't exist (compile error) and `values()` has 6.

- [ ] **Step 3: Add the 10 enum constants** — replace the enum constant block `Formation.java:4-9` (the six `F_...` lines ending with `F_4_2_3_1("4-2-3-1", 1.04, 1.00);`) with all sixteen:

```java
    F_4_4_2("4-4-2", 1.00, 1.00),
    F_4_3_3("4-3-3", 1.10, 0.94),
    F_3_5_2("3-5-2", 1.06, 0.96),
    F_5_3_2("5-3-2", 0.92, 1.10),
    F_4_5_1("4-5-1", 0.96, 1.06),
    F_4_2_3_1("4-2-3-1", 1.04, 1.00),
    F_4_1_4_1("4-1-4-1", 0.98, 1.05),
    F_4_3_2_1("4-3-2-1", 1.00, 1.04),
    F_3_4_3("3-4-3", 1.12, 0.90),
    F_4_4_1_1("4-4-1-1", 0.99, 1.02),
    F_5_4_1("5-4-1", 0.90, 1.12),
    F_4_2_4("4-2-4", 1.14, 0.88),
    F_3_4_2_1("3-4-2-1", 1.06, 0.95),
    F_4_1_2_1_2("4-1-2-1-2", 1.02, 1.00),
    F_5_2_3("5-2-3", 1.02, 1.02),
    F_4_3_1_2("4-3-1-2", 1.03, 0.99);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'br.com.carvoeiromanager.player.FormationTest'`
Expected: PASS (all FormationTest tests, including the pre-existing three).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/Formation.java backend/src/test/java/br/com/carvoeiromanager/player/FormationTest.java
git commit -m "$(cat <<'EOF'
feat(pitch): expand Formation catalogue 6 -> 16

Adds 4-1-4-1, 4-3-2-1, 3-4-3, 4-4-1-1, 5-4-1, 4-2-4, 3-4-2-1, 4-1-2-1-2,
5-2-3, 4-3-1-2 with attack/defence multipliers feeding TeamStrengthCalculator.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Expose the managed club's kit colours on the squad endpoint

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SquadResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java:60-70` (the `getSquad` method — it already loads `Club club`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java` (add one test; file exists)

**Interfaces:**
- Consumes: `Club.getPrimaryColor()`, `Club.getSecondaryColor()` (already exist).
- Produces: `SquadResponse(List<PlayerResponse> players, String primaryColor, String secondaryColor)` — the squad endpoint now carries the club's two kit colours.

- [ ] **Step 1: Add the failing test** — append to `LineupServiceIT` (inside the class). It already provides a `createCareer()` helper returning a `Career` record with `.id()`, `.managedClubId()`, `.userId()`; `SquadResponse` is in the same `match` package and `assertThat` is already imported — no new imports needed:

```java
    @Test
    void squadResponseCarriesTheManagedClubKitColours() {
        Career c = createCareer();

        SquadResponse squad = lineupService.getSquad(c.id(), c.managedClubId(), c.userId());

        // Criciúma's seeded kit is yellow/black.
        assertThat(squad.primaryColor()).isEqualTo("#f7d417");
        assertThat(squad.secondaryColor()).isEqualTo("#111111");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.match.LineupServiceIT.squadResponseCarriesTheManagedClubKitColours'`
Expected: FAIL — `SquadResponse` has no `primaryColor()` accessor (compile error).

- [ ] **Step 3: Widen the record** — replace the whole body of `SquadResponse.java` with:

```java
package br.com.carvoeiromanager.match;

import java.util.List;

public record SquadResponse(List<PlayerResponse> players, String primaryColor, String secondaryColor) {
}
```

- [ ] **Step 4: Populate it** — in `LineupService.getSquad` (`LineupService.java`), the method already has `Club club = clubRepository.findById(clubId)...`. Change the final `return new SquadResponse(responses);` to:

```java
        return new SquadResponse(responses, club.getPrimaryColor(), club.getSecondaryColor());
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'br.com.carvoeiromanager.match.LineupServiceIT'`
Expected: PASS (new test + all existing LineupServiceIT tests — the record change is source-compatible with its only other construction site, which is this method).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SquadResponse.java backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java
git commit -m "$(cat <<'EOF'
feat(pitch): expose managed club kit colours on the squad endpoint

SquadResponse now carries primaryColor/secondaryColor so the pitch can paint
tokens in the club's kit. getSquad already loads the Club.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Pure kit-colour helpers (`kit.ts`)

**Files:**
- Create: `frontend/src/features/pitch/kit.ts`
- Test: `frontend/src/features/pitch/kit.test.ts`

**Interfaces:**
- Produces:
  - `type Kit = { primary: string; secondary: string }`
  - `type TokenColors = { fill: string; text: string }`
  - `readableTextColor(hexBg: string): '#000000' | '#ffffff'`
  - `tokenColors(kit: Kit): TokenColors` — fill = `primary`; text = `secondary` when it contrasts enough with `primary`, else `readableTextColor(primary)`.
  - `kitsClash(aPrimary: string, bPrimary: string): boolean` — true when two primaries are perceptually too close (used by Phase 2).

- [ ] **Step 1: Write the failing tests** — `kit.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { readableTextColor, tokenColors, kitsClash } from './kit'

describe('readableTextColor', () => {
  it('returns white on a dark background', () => {
    expect(readableTextColor('#111111')).toBe('#ffffff')
    expect(readableTextColor('#003399')).toBe('#ffffff')
  })
  it('returns black on a light background', () => {
    expect(readableTextColor('#f7d417')).toBe('#000000')
    expect(readableTextColor('#ffffff')).toBe('#000000')
  })
})

describe('tokenColors', () => {
  it('uses the secondary colour when it contrasts with the primary', () => {
    // Criciúma yellow primary / black secondary -> readable
    expect(tokenColors({ primary: '#f7d417', secondary: '#111111' })).toEqual({ fill: '#f7d417', text: '#111111' })
  })
  it('falls back to a luminance-picked colour when secondary lacks contrast', () => {
    // near-identical primary/secondary -> secondary rejected, fall back
    expect(tokenColors({ primary: '#003399', secondary: '#0033aa' })).toEqual({ fill: '#003399', text: '#ffffff' })
  })
})

describe('kitsClash', () => {
  it('flags two near-identical primaries as a clash', () => {
    expect(kitsClash('#000000', '#000000')).toBe(true)
    expect(kitsClash('#cc0000', '#e30613')).toBe(true)
  })
  it('accepts clearly distinct primaries', () => {
    expect(kitsClash('#f7d417', '#003399')).toBe(false)
  })
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/pitch/kit.test.ts`
Expected: FAIL — module `./kit` not found.

- [ ] **Step 3: Implement `kit.ts`:**

```ts
export type Kit = { primary: string; secondary: string }
export type TokenColors = { fill: string; text: string }

function toRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '').slice(0, 6)
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

// Relative luminance (0..1), WCAG formula.
function luminance(hex: string): number {
  const [r, g, b] = toRgb(hex).map(v => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

// WCAG contrast ratio (1..21).
function contrastRatio(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}

export function readableTextColor(hexBg: string): '#000000' | '#ffffff' {
  return luminance(hexBg) > 0.5 ? '#000000' : '#ffffff'
}

export function tokenColors(kit: Kit): TokenColors {
  const usableSecondary = contrastRatio(kit.primary, kit.secondary) >= 2.5
  return { fill: kit.primary, text: usableSecondary ? kit.secondary : readableTextColor(kit.primary) }
}

// Euclidean distance in RGB; small distance == the two kits look alike on the pitch.
export function kitsClash(aPrimary: string, bPrimary: string): boolean {
  const [ar, ag, ab] = toRgb(aPrimary)
  const [br, bg, bb] = toRgb(bPrimary)
  const dist = Math.sqrt((ar - br) ** 2 + (ag - bg) ** 2 + (ab - bb) ** 2)
  return dist < 80
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/features/pitch/kit.test.ts`
Expected: PASS (8 assertions across 3 describes).

- [ ] **Step 5: Type-check and commit**

Run: `npm run build`  (must be green — `tsc -b` then vite build)
Expected: no type errors.

```bash
git add frontend/src/features/pitch/kit.ts frontend/src/features/pitch/kit.test.ts
git commit -m "$(cat <<'EOF'
feat(pitch): pure kit-colour helpers (contrast + clash)

readableTextColor/tokenColors pick a legible number colour from a club's kit;
kitsClash flags near-identical primaries for the Phase 2 away-strip swap.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Formation → slot-coordinate map (`pitchSlots.ts`)

**Files:**
- Create: `frontend/src/features/pitch/pitchSlots.ts`
- Test: `frontend/src/features/pitch/pitchSlots.test.ts`

**Interfaces:**
- Consumes: `PlayerRole` from `../../api/types`.
- Produces:
  - `type Line = 'GK' | 'DEF' | 'MID' | 'ATT'`
  - `type Slot = { line: Line; x: number; y: number; defaultRole: PlayerRole }`
  - `pitchSlots(formation: string): Slot[]` — exactly 11 slots, `x,y` in `[0,1]` in a "team attacks upward from the bottom" frame (GK `y≈0.94`, forwards `y≈0.3`). Unknown code → 4-4-2 slots.
  - `FORMATION_CODES: string[]` — the 16 codes, in menu order.

- [ ] **Step 1: Write the failing tests** — `pitchSlots.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { pitchSlots, FORMATION_CODES } from './pitchSlots'

const EXPECTED_SHAPE: Record<string, { DEF: number; MID: number; ATT: number }> = {
  '4-4-2': { DEF: 4, MID: 4, ATT: 2 }, '4-3-3': { DEF: 4, MID: 3, ATT: 3 },
  '3-5-2': { DEF: 3, MID: 5, ATT: 2 }, '5-3-2': { DEF: 5, MID: 3, ATT: 2 },
  '4-5-1': { DEF: 4, MID: 5, ATT: 1 }, '4-2-3-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-1-4-1': { DEF: 4, MID: 5, ATT: 1 }, '4-3-2-1': { DEF: 4, MID: 5, ATT: 1 },
  '3-4-3': { DEF: 3, MID: 4, ATT: 3 }, '4-4-1-1': { DEF: 4, MID: 4, ATT: 2 },
  '5-4-1': { DEF: 5, MID: 4, ATT: 1 }, '4-2-4': { DEF: 4, MID: 2, ATT: 4 },
  '3-4-2-1': { DEF: 3, MID: 6, ATT: 1 }, '4-1-2-1-2': { DEF: 4, MID: 4, ATT: 2 },
  '5-2-3': { DEF: 5, MID: 2, ATT: 3 }, '4-3-1-2': { DEF: 4, MID: 4, ATT: 2 },
}

describe('pitchSlots', () => {
  it('exposes all 16 formation codes', () => {
    expect(FORMATION_CODES).toHaveLength(16)
    expect(new Set(FORMATION_CODES).size).toBe(16)
  })

  it.each(FORMATION_CODES)('%s has 11 slots with the correct line counts', code => {
    const slots = pitchSlots(code)
    expect(slots).toHaveLength(11)
    const count = (l: string) => slots.filter(s => s.line === l).length
    expect(count('GK')).toBe(1)
    expect(count('DEF')).toBe(EXPECTED_SHAPE[code].DEF)
    expect(count('MID')).toBe(EXPECTED_SHAPE[code].MID)
    expect(count('ATT')).toBe(EXPECTED_SHAPE[code].ATT)
  })

  it.each(FORMATION_CODES)('%s keeps every coordinate inside the pitch and non-overlapping', code => {
    const slots = pitchSlots(code)
    for (const s of slots) {
      expect(s.x).toBeGreaterThanOrEqual(0)
      expect(s.x).toBeLessThanOrEqual(1)
      expect(s.y).toBeGreaterThanOrEqual(0)
      expect(s.y).toBeLessThanOrEqual(1)
    }
    const keys = slots.map(s => `${s.x.toFixed(3)},${s.y.toFixed(3)}`)
    expect(new Set(keys).size).toBe(11)
  })

  it('falls back to 4-4-2 for an unknown code', () => {
    expect(pitchSlots('9-0-1')).toEqual(pitchSlots('4-4-2'))
  })
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/pitch/pitchSlots.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `pitchSlots.ts`** (rows are grouped by line; a line may span multiple rows for the deeper shapes):

```ts
import type { PlayerRole } from '../../api/types'

export type Line = 'GK' | 'DEF' | 'MID' | 'ATT'
export type Slot = { line: Line; x: number; y: number; defaultRole: PlayerRole }

type Row = { line: Line; y: number; xs: number[] }

const DEFAULT_ROLE: Record<Line, PlayerRole> = {
  GK: 'GOALKEEPER', DEF: 'FULL_BACK', MID: 'BOX_TO_BOX', ATT: 'POACHER',
}

// Each layout lists rows from own-goal (bottom, y≈0.94) up to the opposition goal (top).
// Multiple rows may share a line (e.g. a double pivot + an attacking-mid band are both MID).
const LAYOUTS: Record<string, Row[]> = {
  '4-4-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.32, xs: [0.34, 0.66] },
  ],
  '4-3-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.56, xs: [0.26, 0.5, 0.74] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '3-5-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64] },
  ],
  '5-3-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.26, 0.5, 0.74] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64] },
  ],
  '4-5-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'ATT', y: 0.3, xs: [0.5] },
  ],
  '4-2-3-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.62, xs: [0.36, 0.64] },
    { line: 'MID', y: 0.44, xs: [0.2, 0.5, 0.8] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-1-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.64, xs: [0.5] },
    { line: 'MID', y: 0.48, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-3-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.6, xs: [0.26, 0.5, 0.74] },
    { line: 'MID', y: 0.44, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '3-4-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '4-4-1-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.56, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.4, xs: [0.5] },
    { line: 'ATT', y: 0.26, xs: [0.5] },
  ],
  '5-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.3, xs: [0.5] },
  ],
  '4-2-4': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.32, xs: [0.14, 0.38, 0.62, 0.86] },
  ],
  '3-4-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.58, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.42, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-1-2-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.64, xs: [0.5] },
    { line: 'MID', y: 0.54, xs: [0.24, 0.76] },
    { line: 'MID', y: 0.44, xs: [0.5] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64] },
  ],
  '5-2-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '4-3-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.58, xs: [0.26, 0.5, 0.74] },
    { line: 'MID', y: 0.44, xs: [0.5] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64] },
  ],
}

export const FORMATION_CODES = Object.keys(LAYOUTS)

export function pitchSlots(formation: string): Slot[] {
  const rows = LAYOUTS[formation] ?? LAYOUTS['4-4-2']
  return rows.flatMap(row =>
    row.xs.map(x => ({ line: row.line, x, y: row.y, defaultRole: DEFAULT_ROLE[row.line] })),
  )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/features/pitch/pitchSlots.test.ts`
Expected: PASS (16 formations × 2 parametrised checks + the code-list + fallback tests).

- [ ] **Step 5: Type-check and commit**

Run: `npm run build`
Expected: no type errors.

```bash
git add frontend/src/features/pitch/pitchSlots.ts frontend/src/features/pitch/pitchSlots.test.ts
git commit -m "$(cat <<'EOF'
feat(pitch): formation -> slot-coordinate map for all 16 formations

Pure pitchSlots(formation) returns 11 normalized [0,1] slots per formation
(team attacks upward from the bottom); deeper shapes use multi-row lines.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `PlayerToken` component

**Files:**
- Create: `frontend/src/features/pitch/PlayerToken.tsx`
- Test: `frontend/src/features/pitch/PlayerToken.test.tsx`

**Interfaces:**
- Consumes: nothing from earlier tasks (positioning is the parent's job).
- Produces:
  - `type TokenBadges = { goal?: boolean; card?: 'YELLOW' | 'RED'; injured?: boolean }`
  - `PlayerToken(props: { number: number; surname?: string; fill: string; text: string; fitness: number; badges?: TokenBadges }): JSX.Element` — a circular kit-coloured token with the number, an optional surname label beneath, a fitness ring, and optional goal/card/injury badges. Root element has `data-testid="player-token"`.

- [ ] **Step 1: Write the failing tests** — `PlayerToken.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PlayerToken } from './PlayerToken'

describe('PlayerToken', () => {
  it('renders the number and surname', () => {
    render(<PlayerToken number={9} surname="Gamalho" fill="#f7d417" text="#111111" fitness={80} />)
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('Gamalho')).toBeInTheDocument()
  })

  it('paints the kit fill colour on the token', () => {
    render(<PlayerToken number={1} fill="#003399" text="#ffffff" fitness={90} />)
    const token = screen.getByTestId('player-token')
    // jsdom normalizes hex to rgb
    expect(token.querySelector('[data-role="disc"]')).toHaveStyle({ backgroundColor: 'rgb(0, 51, 153)' })
  })

  it('shows a goal badge when scored', () => {
    render(<PlayerToken number={9} fill="#f7d417" text="#111111" fitness={70} badges={{ goal: true }} />)
    expect(screen.getByLabelText('Gol')).toBeInTheDocument()
  })

  it('shows a card badge when booked', () => {
    render(<PlayerToken number={8} fill="#f7d417" text="#111111" fitness={70} badges={{ card: 'YELLOW' }} />)
    expect(screen.getByLabelText('Cartão amarelo')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/pitch/PlayerToken.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `PlayerToken.tsx`** (fitness ring bands mirror `FitnessBar`: `<40` red, `<70` amber, else green):

```tsx
import { Box, Typography } from '@mui/material'

export type TokenBadges = { goal?: boolean; card?: 'YELLOW' | 'RED'; injured?: boolean }

function ringColor(fitness: number): string {
  if (fitness < 40) return '#d32f2f'
  if (fitness < 70) return '#ed6c02'
  return '#2e7d32'
}

export function PlayerToken(props: {
  number: number
  surname?: string
  fill: string
  text: string
  fitness: number
  badges?: TokenBadges
}) {
  const { number, surname, fill, text, fitness, badges } = props
  const ring = ringColor(Math.max(0, Math.min(100, fitness)))
  return (
    <Box
      data-testid="player-token"
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: 46, userSelect: 'none' }}
    >
      <Box sx={{ position: 'relative', width: 30, height: 30 }}>
        <Box
          data-role="disc"
          sx={{
            width: 30, height: 30, borderRadius: '50%', backgroundColor: fill,
            border: `2px solid ${ring}`, display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 1px 2px rgba(0,0,0,0.4)',
          }}
        >
          <Typography sx={{ color: text, fontSize: 13, fontWeight: 800, lineHeight: 1 }}>{number}</Typography>
        </Box>
        {badges?.goal && (
          <Box aria-label="Gol" sx={{ position: 'absolute', top: -6, right: -6, fontSize: 13 }}>⚽</Box>
        )}
        {badges?.card && (
          <Box
            aria-label={badges.card === 'YELLOW' ? 'Cartão amarelo' : 'Cartão vermelho'}
            sx={{
              position: 'absolute', top: -5, left: -5, width: 8, height: 11, borderRadius: '1px',
              backgroundColor: badges.card === 'YELLOW' ? '#ffd200' : '#d32f2f', border: '1px solid #111',
            }}
          />
        )}
        {badges?.injured && (
          <Box aria-label="Lesionado" sx={{ position: 'absolute', bottom: -6, right: -6, color: '#d32f2f', fontSize: 13, fontWeight: 900, lineHeight: 1 }}>✚</Box>
        )}
      </Box>
      {surname && (
        <Typography sx={{ mt: 0.25, fontSize: 9, fontWeight: 600, color: '#fff', textShadow: '0 1px 2px rgba(0,0,0,0.8)', maxWidth: 46, textAlign: 'center', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {surname}
        </Typography>
      )}
    </Box>
  )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/features/pitch/PlayerToken.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Type-check and commit**

Run: `npm run build`
Expected: no type errors.

```bash
git add frontend/src/features/pitch/PlayerToken.tsx frontend/src/features/pitch/PlayerToken.test.tsx
git commit -m "$(cat <<'EOF'
feat(pitch): PlayerToken — kit-coloured disc with number, fitness ring, badges

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `Pitch` component (SVG field + positioned tokens)

**Files:**
- Create: `frontend/src/features/pitch/Pitch.tsx`
- Test: `frontend/src/features/pitch/Pitch.test.tsx`

**Interfaces:**
- Consumes: `pitchSlots`, `Slot`, `Line` from `./pitchSlots`; `PlayerToken`, `TokenBadges` from `./PlayerToken`; `tokenColors`, `Kit` from `./kit`.
- Produces:
  - `type PlacedPlayer = { id: string; surname: string; position: 'GK' | 'DEF' | 'MID' | 'ATT'; fitness: number; badges?: TokenBadges }`
  - `type TeamOnPitch = { formation: string; kit: Kit; players: PlacedPlayer[]; showNames?: boolean }`
  - `Pitch(props: { home: TeamOnPitch; away?: TeamOnPitch }): JSX.Element` — renders the SVG field once and positions each team's players into their formation slots (home in the bottom half / full pitch; `away` mirrored into the top half). Shirt numbers derive from slot order (GK first). Root has `data-testid="pitch"`.

**Placement rule:** group a team's `players` by `line` (GK/DEF/MID/ATT); group `pitchSlots(formation)` by line; within each line, zip players to slots in array order; a player's shirt number is its slot's global index+1 (slots ordered GK, then DEF, MID, ATT as returned by `pitchSlots`). Players beyond their line's slot count are dropped (a valid saved XI always matches).

- [ ] **Step 1: Write the failing tests** — `Pitch.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Pitch } from './Pitch'
import type { TeamOnPitch } from './Pitch'

function xi(): TeamOnPitch {
  const mk = (i: number, position: 'GK' | 'DEF' | 'MID' | 'ATT') =>
    ({ id: `p${i}`, surname: `Jog${i}`, position, fitness: 80 })
  return {
    formation: '4-4-2',
    kit: { primary: '#f7d417', secondary: '#111111' },
    players: [
      mk(1, 'GK'),
      mk(2, 'DEF'), mk(3, 'DEF'), mk(4, 'DEF'), mk(5, 'DEF'),
      mk(6, 'MID'), mk(7, 'MID'), mk(8, 'MID'), mk(9, 'MID'),
      mk(10, 'ATT'), mk(11, 'ATT'),
    ],
    showNames: true,
  }
}

describe('Pitch', () => {
  it('renders the pitch and eleven home tokens', () => {
    render(<Pitch home={xi()} />)
    expect(screen.getByTestId('pitch')).toBeInTheDocument()
    expect(screen.getAllByTestId('player-token')).toHaveLength(11)
  })

  it('renders 22 tokens when an away team is supplied', () => {
    const away: TeamOnPitch = { ...xi(), kit: { primary: '#003399', secondary: '#ffffff' }, showNames: false }
    render(<Pitch home={xi()} away={away} />)
    expect(screen.getAllByTestId('player-token')).toHaveLength(22)
  })

  it('shows home surnames', () => {
    render(<Pitch home={xi()} />)
    expect(screen.getByText('Jog1')).toBeInTheDocument()
    expect(screen.getByText('Jog11')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `Pitch.tsx`:**

```tsx
import { Box } from '@mui/material'
import { pitchSlots, type Slot, type Line } from './pitchSlots'
import { PlayerToken, type TokenBadges } from './PlayerToken'
import { tokenColors, type Kit } from './kit'

export type PlacedPlayer = {
  id: string
  surname: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  fitness: number
  badges?: TokenBadges
}
export type TeamOnPitch = { formation: string; kit: Kit; players: PlacedPlayer[]; showNames?: boolean }

const LINES: Line[] = ['GK', 'DEF', 'MID', 'ATT']

type Positioned = { slot: Slot; number: number; player: PlacedPlayer }

// Zip a team's players into their formation slots, line by line, assigning a
// stable shirt number from the slot's global order (GK=1, then DEF/MID/ATT).
function assign(team: TeamOnPitch): Positioned[] {
  const slots = pitchSlots(team.formation)
  const out: Positioned[] = []
  for (const line of LINES) {
    const lineSlots = slots.map((s, i) => ({ s, i })).filter(x => x.s.line === line)
    const linePlayers = team.players.filter(p => p.position === line)
    lineSlots.forEach(({ s, i }, k) => {
      const player = linePlayers[k]
      if (player) out.push({ slot: s, number: i + 1, player })
    })
  }
  return out
}

function TeamLayer({ team, mirror }: { team: TeamOnPitch; mirror: boolean }) {
  const colors = tokenColors(team.kit)
  return (
    <>
      {assign(team).map(({ slot, number, player }) => {
        const yPct = (mirror ? 1 - slot.y : slot.y) * 100
        const xPct = (mirror ? 1 - slot.x : slot.x) * 100
        return (
          <Box
            key={player.id}
            sx={{ position: 'absolute', left: `${xPct}%`, top: `${yPct}%`, transform: 'translate(-50%, -50%)' }}
          >
            <PlayerToken
              number={number}
              surname={team.showNames ? player.surname : undefined}
              fill={colors.fill}
              text={colors.text}
              fitness={player.fitness}
              badges={player.badges}
            />
          </Box>
        )
      })}
    </>
  )
}

export function Pitch({ home, away }: { home: TeamOnPitch; away?: TeamOnPitch }) {
  return (
    <Box
      data-testid="pitch"
      sx={{ position: 'relative', width: '100%', maxWidth: 440, mx: 'auto', aspectRatio: '2 / 3', borderRadius: 1, overflow: 'hidden', backgroundColor: '#2b8449' }}
    >
      <Box component="svg" viewBox="0 0 100 150" preserveAspectRatio="none" sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <g fill="none" stroke="#ffffff" strokeWidth={0.4} opacity={0.85}>
          <rect x={3} y={3} width={94} height={144} />
          <line x1={3} y1={75} x2={97} y2={75} />
          <circle cx={50} cy={75} r={12} />
          <rect x={28} y={3} width={44} height={18} />
          <rect x={28} y={129} width={44} height={18} />
        </g>
      </Box>
      <TeamLayer team={home} mirror={false} />
      {away && <TeamLayer team={away} mirror />}
    </Box>
  )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Type-check and commit**

Run: `npm run build`
Expected: no type errors.

```bash
git add frontend/src/features/pitch/Pitch.tsx frontend/src/features/pitch/Pitch.test.tsx
git commit -m "$(cat <<'EOF'
feat(pitch): Pitch component — SVG field + formation-positioned tokens

Places each team's XI into pitchSlots by line; away team mirrors into the top
half; shirt numbers derive from slot order. Home-only rendering used in Phase 1.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Render the read-only pitch on the Squad screen + reconcile the 16 formations

**Files:**
- Modify: `frontend/src/api/types.ts` (widen `SquadResponse`)
- Modify: `frontend/src/features/squad/SquadPage.tsx` (import `FORMATION_CODES`; extend `FORMATION_SHAPE`; replace `FORMATIONS`; render `<Pitch>`)
- Test: `frontend/src/features/squad/SquadPage.test.tsx` (add a pitch-render test; file exists)

**Interfaces:**
- Consumes: `Pitch`, `TeamOnPitch`, `PlacedPlayer` from `../pitch/Pitch`; `FORMATION_CODES` from `../pitch/pitchSlots`; the widened `SquadResponse` (`primaryColor`, `secondaryColor`).
- Produces: nothing downstream (leaf integration).

- [ ] **Step 1: Widen the frontend `SquadResponse` type** — in `frontend/src/api/types.ts`, replace:

```ts
export type SquadResponse = {
  players: PlayerResponse[]
}
```

with:

```ts
export type SquadResponse = {
  players: PlayerResponse[]
  primaryColor: string
  secondaryColor: string
}
```

- [ ] **Step 2a: Patch the shared `squad` fixture** — widening `SquadResponse` (Step 1) makes the existing `const squad: SquadResponse = { players: [...] }` fail `tsc -b`. Add the two colour fields to it:

```tsx
const squad: SquadResponse = {
  players: [GK, GK2, ...ALL_OUTFIELD, MID4, ATT4, SUSPENDED, INJURED, LOW_FITNESS],
  primaryColor: '#f7d417',
  secondaryColor: '#111111',
}
```

- [ ] **Step 2b: Write the failing test** — add this to the `describe('SquadPage', …)` block. It uses the file's existing `renderWithProviders()` helper and the existing player fixtures (`GK`, `DEF1..4`, `MID1..4`, `ATT1..2`), driving a saved 4-4-2 XI so the pitch has eleven tokens (mirrors the existing "rehydrates the saved formation" test):

```tsx
  it('renders the formation pitch with the selected XI', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER' },
        { playerId: DEF1.id, role: 'FULL_BACK' }, { playerId: DEF2.id, role: 'FULL_BACK' },
        { playerId: DEF3.id, role: 'FULL_BACK' }, { playerId: DEF4.id, role: 'FULL_BACK' },
        { playerId: MID1.id, role: 'BOX_TO_BOX' }, { playerId: MID2.id, role: 'BOX_TO_BOX' },
        { playerId: MID3.id, role: 'BOX_TO_BOX' }, { playerId: MID4.id, role: 'BOX_TO_BOX' },
        { playerId: ATT1.id, role: 'POACHER' }, { playerId: ATT2.id, role: 'POACHER' },
      ],
      bench: [],
    })
    renderWithProviders()
    expect(await screen.findByTestId('pitch')).toBeInTheDocument()
    expect(screen.getAllByTestId('player-token')).toHaveLength(11)
  })
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: FAIL — no element with `data-testid="pitch"` (pitch not rendered yet).

- [ ] **Step 4: Reconcile formations in `SquadPage.tsx`** — replace the `FORMATIONS` constant (line 20) and the `FORMATION_SHAPE` map (lines 27-33) with the full 16. Import `FORMATION_CODES` at the top (`import { FORMATION_CODES } from '../pitch/pitchSlots'`) and use it for the dropdown; extend `FORMATION_SHAPE`:

```ts
const FORMATIONS = FORMATION_CODES

const FORMATION_SHAPE: Record<string, Record<OutfieldPosition, number>> = {
  '4-4-2': { DEF: 4, MID: 4, ATT: 2 },
  '4-3-3': { DEF: 4, MID: 3, ATT: 3 },
  '3-5-2': { DEF: 3, MID: 5, ATT: 2 },
  '4-2-3-1': { DEF: 4, MID: 5, ATT: 1 },
  '5-3-2': { DEF: 5, MID: 3, ATT: 2 },
  '4-5-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-1-4-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-3-2-1': { DEF: 4, MID: 5, ATT: 1 },
  '3-4-3': { DEF: 3, MID: 4, ATT: 3 },
  '4-4-1-1': { DEF: 4, MID: 4, ATT: 2 },
  '5-4-1': { DEF: 5, MID: 4, ATT: 1 },
  '4-2-4': { DEF: 4, MID: 2, ATT: 4 },
  '3-4-2-1': { DEF: 3, MID: 6, ATT: 1 },
  '4-1-2-1-2': { DEF: 4, MID: 4, ATT: 2 },
  '5-2-3': { DEF: 5, MID: 2, ATT: 3 },
  '4-3-1-2': { DEF: 4, MID: 4, ATT: 2 },
}
```

(Keep the `FORMATIONS.map(...)` dropdown at line 348 as-is — it now iterates all 16.)

- [ ] **Step 5: Build the pitch view-model and render it** — add this import near the other imports:

```tsx
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
```

Inside `SquadPage`, after `positionCounts` is computed (around line 189), derive the pitch team from the currently-selected XI:

```tsx
  const pitchTeam: TeamOnPitch | null = data && selected.length > 0 ? {
    formation,
    kit: { primary: data.primaryColor, secondary: data.secondaryColor },
    showNames: true,
    players: selected
      .map(id => players.find(p => p.id === id))
      .filter((p): p is PlayerResponse => Boolean(p))
      .map((p): PlacedPlayer => ({ id: p.id, surname: p.name, position: p.position, fitness: p.fitness })),
  } : null
```

Then render the pitch inside the `Escalação` `Collapsible`, right after `{helperAndAlerts}` (line 383):

```tsx
        {pitchTeam && (
          <Box sx={{ mb: 2 }}>
            <Pitch home={pitchTeam} />
          </Box>
        )}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: PASS (new pitch test + all existing SquadPage tests — the formation dropdown still holds the original codes plus the new ones; existing tests that switch to `3-5-2` etc. still work).

- [ ] **Step 7: Full frontend gate**

Run: `npm run build && npx vitest run`
Expected: `tsc -b` clean, vite build succeeds, all Vitest suites green.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(pitch): read-only formation pitch on the Squad screen + all 16 formations

SquadResponse carries kit colours; the Escalação panel renders the selected XI
on the Pitch, and the formation menu now lists all 16 shapes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification (after all tasks)

- [ ] Backend: `cd backend && ./gradlew test` → no NEW failures (ignore known-flaky `RolloverContractsIT`, `SeedLoaderTest`).
- [ ] Frontend: `cd frontend && npm run build && npx vitest run` → `tsc -b` clean + all suites green.
- [ ] Manual smoke (optional): open the Squad screen for a loaded career → the selected XI appears on a green pitch in the chosen formation, tokens in the club's kit; switching formation re-lays the shape; the menu offers 16 formations.

## Notes for the executor

- This is **Phase 1 of 3** (see `docs/superpowers/specs/2026-07-06-carvoeiro-manager-pitch-view-design.md`). Live-match reactions and drag-and-drop are **out of scope** here — do not build them.
- The `Pitch` component's `away` prop and badges exist now but are unused in Phase 1; they are the seams for Phase 2. Do not delete them.
- Per-slot role refinement (outer DEF → `FULL_BACK` vs inner → `STOPPER`) is a Phase 3 concern; Phase 1 uses one default role per line.
