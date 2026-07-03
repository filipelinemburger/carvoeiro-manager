# Player Morale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each player a morale ([−5..+5]) that shifts with results, playing time and key match events, feeds the match engine as a per-player multiplier, and surfaces as squad mood chips plus a team-average "dressing room" indicator.

**Architecture:** Morale mirrors the existing `form` system end to end. A new `Player.morale` int feeds the single shared `TeamStrengthCalculator` (used by both the live and instant sims) as `(1.0 + 0.02*morale)`. Post-match updates are added as sibling logic inside the existing `MatchOutcomeApplier.applyTeam` (played) and `applyNonPlayers` (unused). Morale is exposed on the squad DTO and rendered on the Squad screen; team morale is a display-only average computed in the frontend.

**Tech Stack:** Java 25 / Spring Boot / JPA / Flyway / Testcontainers-Postgres (backend); React + TypeScript (strict) / MUI / Vitest + RTL (frontend).

## Global Constraints

- Backend gradle needs `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`.
- `morale` is an int **clamped to [−5, +5]**, default **0**; engine coefficient `MORALE_COEFF = 0.02` (morale ±5 → ±10% per player).
- Jackson is `tools.jackson` (not `com.fasterxml`). Integration tests use Testcontainers Postgres.
- Frontend: run **`npx tsc -b`** (not just `vitest`) before a frontend task is "done" — vitest does not type-check, and a missing required field silently passes vitest but breaks the Vercel build.
- YAGNI — **not** in scope: winning/losing streaks, off-pitch (contract/transfer) drivers, any separate team-morale engine effect. Team morale is display-only.
- Never `git push`. Every commit message ends with a trailer line: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## File Structure

- `backend/.../player/Player.java` — `morale` field + `getMorale()` + `applyMorale(int)` + reset (Task 1).
- `backend/src/main/resources/db/migration/V18__morale.sql` — new column (Task 1).
- `backend/.../match/TeamStrengthCalculator.java` — morale multiplier (Task 2).
- `backend/.../match/MatchOutcomeApplier.java` — post-match morale update (Task 3).
- `backend/.../match/PlayerResponse.java` + `backend/.../match/LineupService.java` — expose `morale` (Task 4).
- `frontend/src/features/squad/morale.ts` — `moraleMood` + `teamMorale` helpers (Task 5).
- `frontend/src/features/squad/MoodChip.tsx` — presentational chip (Task 6).
- `frontend/src/api/types.ts` + `frontend/src/features/squad/SquadPage.tsx` — type field + UI (Task 6).

---

### Task 1: `Player.morale` field, `applyMorale`, season reset, V18 migration

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java` (field near line 68; getter near line 145; `applyMorale` near line 151; reset in `resetSeasonState` near line 208)
- Create: `backend/src/main/resources/db/migration/V18__morale.sql`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerMoraleTest.java`

**Interfaces:**
- Produces: `int Player.getMorale()`, `void Player.applyMorale(int delta)` (clamps to [−5, +5]); `resetSeasonState()` zeroes morale.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/player/PlayerMoraleTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerMoraleTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 25, "BRA",
                70, 70, 70, 70, 10, 70, 70);
    }

    @Test
    void moraleStartsNeutralAndClampsToPlusMinusFive() {
        Player p = player();
        assertThat(p.getMorale()).isZero();
        p.applyMorale(10);
        assertThat(p.getMorale()).isEqualTo(5);
        p.applyMorale(-20);
        assertThat(p.getMorale()).isEqualTo(-5);
    }

    @Test
    void resetSeasonStateZeroesMorale() {
        Player p = player();
        p.applyMorale(4);
        p.resetSeasonState();
        assertThat(p.getMorale()).isZero();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerMoraleTest"` (from `backend/`)
Expected: FAIL — compilation error, `getMorale()`/`applyMorale(int)` do not exist.

- [ ] **Step 3: Add the field, getter and mutator**

In `Player.java`, add the field after `form` (near line 69):

```java
    @Column(name = "morale", nullable = false)
    private int morale;
```

Add the getter next to `getForm()` (near line 145) and the mutator next to `applyForm` (near line 151):

```java
    public int getMorale() { return morale; }

    public void applyMorale(int delta) {
        this.morale = Math.max(-5, Math.min(5, this.morale + delta));
    }
```

In `resetSeasonState()` (near line 208), add alongside `this.form = 0;`:

```java
        this.morale = 0;
```

- [ ] **Step 4: Create the migration**

Create `backend/src/main/resources/db/migration/V18__morale.sql`:

```sql
ALTER TABLE players ADD COLUMN morale INT NOT NULL DEFAULT 0;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.PlayerMoraleTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/main/resources/db/migration/V18__morale.sql \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerMoraleTest.java
git commit -m "feat(morale): add Player.morale field, applyMorale, reset + V18 migration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Morale multiplier in `TeamStrengthCalculator`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TeamStrengthCalculator.java` (multiplier at line 37; add a constant near the top of the class)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java` (append two tests)

**Interfaces:**
- Consumes: `Player.getMorale()`, `Player.applyMorale(int)` (Task 1).
- Produces: per-player strength multiplier now includes `(1.0 + MORALE_COEFF * morale)` where `MORALE_COEFF = 0.02`.

- [ ] **Step 1: Write the failing tests**

Append to `TeamStrengthCalculatorTest` (the class already imports `IntStream` and has `standardXi()` / `profile(...)`):

```java
    @Test
    void moraleMultiplierRaisesContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyMorale(1))); // +5 morale -> +10%
        TeamMatchInput happy = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(happy.attack()).isGreaterThan(baseline.attack());
    }

    @Test
    void lowMoraleLowersContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyMorale(-1))); // -5 morale -> -10%
        TeamMatchInput sad = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(sad.attack()).isLessThan(baseline.attack());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.TeamStrengthCalculatorTest"`
Expected: FAIL — morale has no effect yet, so `happy.attack()` equals `baseline.attack()` (assertions fail).

- [ ] **Step 3: Add the morale multiplier**

Near the top of `TeamStrengthCalculator` (with the other class fields/constants), add:

```java
    private static final double MORALE_COEFF = 0.02;   // morale +/-5 -> +/-10% per player, same weight as form
```

Change line 37 from:

```java
            double mult = (1.0 + 0.02 * p.getForm()) * fitnessMult;
```

to:

```java
            double mult = (1.0 + 0.02 * p.getForm()) * (1.0 + MORALE_COEFF * p.getMorale()) * fitnessMult;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.TeamStrengthCalculatorTest"`
Expected: PASS (all tests, including the two new ones). Morale 0 leaves the multiplier at ×1.0, so every existing test is unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/TeamStrengthCalculator.java \
        backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java
git commit -m "feat(morale): morale feeds per-player strength (1 + 0.02*morale)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Post-match morale update in `MatchOutcomeApplier`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java` (`applyTeam` near line 95, after `p.applyForm(delta);`; `applyNonPlayers` near line 99, inside the loop)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java` (append tests; the class already has the `player(Position)` helper)

**Interfaces:**
- Consumes: `Player.applyMorale(int)`, `Player.getMorale()` (Task 1); existing `TeamOutcome`, `AttributedEvent`, `Summary`.
- Produces: morale mutated on played players (via `applyFixture`) and on unused players (via `applyNonPlayers`).

- [ ] **Step 1: Write the failing tests**

Append to `MatchOutcomeApplierTest`:

```java
    @Test
    void winnerWhoPlayedGainsMoraleAndLoserLoses() {
        Player winner = player(Position.MID);
        Player loser = player(Position.MID);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(List.of(),
                new MatchOutcomeApplier.TeamOutcome(List.of(winner), 2, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(loser), 0, 2, false), acc);

        assertThat(winner.getMorale()).isEqualTo(1);   // win +1
        assertThat(loser.getMorale()).isEqualTo(-1);   // loss -1
    }

    @Test
    void scorerGainsExtraMoraleAndRedCardCostsMorale() {
        Player scorer = player(Position.ATT);
        Player sentOff = player(Position.DEF);
        List<AttributedEvent> events = List.of(
                new AttributedEvent(scorer.getId(), EventType.GOAL, true),
                new AttributedEvent(sentOff.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(scorer, sentOff), 1, 1, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 1, 1, false), acc);

        assertThat(scorer.getMorale()).isEqualTo(1);    // draw 0 + scored +1
        assertThat(sentOff.getMorale()).isEqualTo(-1);  // draw 0 + red -1
    }

    @Test
    void unusedReservesDriftTowardMildDiscontent() {
        Player fresh = player(Position.MID);        // morale 0 -> -1
        Player low = player(Position.MID);
        low.applyMorale(-3);                         // below -1 -> steps back up toward -1

        applier.applyNonPlayers(List.of(fresh, low));

        assertThat(fresh.getMorale()).isEqualTo(-1);
        assertThat(low.getMorale()).isEqualTo(-2);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest"`
Expected: FAIL — morale is never updated, so it stays 0 (assertions fail).

- [ ] **Step 3: Add the morale update to `applyTeam`**

In `applyTeam`, immediately after the existing `p.applyForm(delta);` line, add:

```java
            // Morale: match result, plus a lift for scoring and a hit for a red card.
            int moraleDelta = won ? 1 : (lost ? -1 : 0);
            if (goals.getOrDefault(p.getId(), 0) > 0) {
                moraleDelta += 1;
            }
            if (reds.contains(p.getId())) {
                moraleDelta -= 1;
            }
            p.applyMorale(moraleDelta);
```

- [ ] **Step 4: Add the morale drift to `applyNonPlayers`**

Inside the `applyNonPlayers` loop (near the existing form regression), add:

```java
            // Morale: players who did not feature drift toward mild discontent (-1).
            if (p.getMorale() > -1) {
                p.applyMorale(-1);
            } else if (p.getMorale() < -1) {
                p.applyMorale(1);
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.MatchOutcomeApplierTest"`
Expected: PASS (existing + 3 new tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java \
        backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java
git commit -m "feat(morale): post-match morale update (result/scored/red + unused drift)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Expose `morale` on the squad DTO

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java` (add `int morale` after `form`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (`toPlayerResponse`, line 458 — insert `p.getMorale()` after `p.getForm()`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java` (append one test; the class has `private Career createCareer()` and autowired `lineupService`)

**Interfaces:**
- Consumes: `Player.getMorale()` (Task 1); `lineupService.getSquad(saveGameId, clubId, userId)` → `SquadResponse` with `List<PlayerResponse>`.
- Produces: `PlayerResponse.morale()` (int), positioned immediately after `form()`.

- [ ] **Step 1: Write the failing test**

Append to `LineupServiceIT`:

```java
    @Test
    void squadResponseExposesMorale() {
        Career career = createCareer();
        SquadResponse squad = lineupService.getSquad(career.id(), career.managedClubId(), career.userId());
        assertThat(squad.players()).isNotEmpty();
        assertThat(squad.players()).allSatisfy(pr -> assertThat(pr.morale()).isBetween(-5, 5));
    }
```

(If `SquadResponse` is not already imported in the test, add `import br.com.carvoeiromanager.match.SquadResponse;` — same package, so likely unnecessary.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT.squadResponseExposesMorale"`
Expected: FAIL — compilation error, `PlayerResponse.morale()` does not exist.

- [ ] **Step 3: Add `morale` to the record and the builder**

In `PlayerResponse.java`, add `int morale,` immediately after the `int form,` line:

```java
        int form,
        int morale,
        int yellowCards,
```

In `LineupService.toPlayerResponse` (line 458), insert `p.getMorale()` immediately after `p.getForm(),`:

```java
                p.getForm(),
                p.getMorale(),
                p.getYellowCards(),
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT.squadResponseExposesMorale"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java
git commit -m "feat(morale): expose morale on the squad PlayerResponse

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Frontend `moraleMood` + `teamMorale` helpers

**Files:**
- Create: `frontend/src/features/squad/morale.ts`
- Test: `frontend/src/features/squad/morale.test.ts`

**Interfaces:**
- Produces: `type Mood = { emoji: string; label: string; color: string }`; `moraleMood(morale: number): Mood`; `teamMorale(morales: number[]): number`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/squad/morale.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { moraleMood, teamMorale } from './morale'

describe('moraleMood', () => {
  it('maps a value to its mood bucket', () => {
    expect(moraleMood(-5).label).toBe('Desanimado')
    expect(moraleMood(-3).label).toBe('Desanimado')
    expect(moraleMood(-1).label).toBe('Descontente')
    expect(moraleMood(0).label).toBe('Neutro')
    expect(moraleMood(2).label).toBe('Contente')
    expect(moraleMood(3).label).toBe('Radiante')
    expect(moraleMood(5).label).toBe('Radiante')
  })
})

describe('teamMorale', () => {
  it('averages and rounds, and is 0 for an empty squad', () => {
    expect(teamMorale([])).toBe(0)
    expect(teamMorale([1, 2, 3])).toBe(2)      // 2.0
    expect(teamMorale([-2, -2, -1])).toBe(-2)  // -1.67 -> -2
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npx vitest run src/features/squad/morale.test.ts`
Expected: FAIL — `./morale` module does not exist.

- [ ] **Step 3: Create the helper**

Create `frontend/src/features/squad/morale.ts`:

```ts
export type Mood = { emoji: string; label: string; color: string }

/** Maps a [-5..+5] morale value to a display mood (emoji + pt-BR label + colour). */
export function moraleMood(morale: number): Mood {
  if (morale <= -3) return { emoji: '😞', label: 'Desanimado', color: '#c0392b' }
  if (morale <= -1) return { emoji: '😕', label: 'Descontente', color: '#c9911f' }
  if (morale === 0) return { emoji: '😐', label: 'Neutro', color: '#7f8c8d' }
  if (morale <= 2) return { emoji: '🙂', label: 'Contente', color: '#3e8e41' }
  return { emoji: '🤩', label: 'Radiante', color: '#1b7e3c' }
}

/** Squad-average morale, rounded to the nearest whole for bucket lookup; 0 for an empty squad. */
export function teamMorale(morales: number[]): number {
  if (morales.length === 0) return 0
  return Math.round(morales.reduce((sum, m) => sum + m, 0) / morales.length)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx vitest run src/features/squad/morale.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/squad/morale.ts frontend/src/features/squad/morale.test.ts
git commit -m "feat(morale): moraleMood + teamMorale frontend helpers

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Squad UI — per-player mood chip, team-morale header, type field

**Files:**
- Create: `frontend/src/features/squad/MoodChip.tsx`
- Modify: `frontend/src/api/types.ts` (add `morale: number` to the `PlayerResponse` type, after `form`)
- Modify: `frontend/src/features/squad/SquadPage.tsx` (import helpers/chip; add a "Moral" column; add a team-morale chip to the header near line 354)
- Modify: `frontend/src/features/squad/SquadPage.test.tsx` (add `morale: 0` to the shared `STATE` at line 27; add render assertions)

**Interfaces:**
- Consumes: `moraleMood`, `teamMorale` (Task 5); `PlayerResponse.morale` (Task 4 backend, mirrored in the TS type here).
- Produces: `<MoodChip morale={n} />` React component; `PlayerResponse.morale: number` on the frontend type.

- [ ] **Step 1: Write the failing test**

In `SquadPage.test.tsx`, first add `morale: 0` to the shared player base `STATE` (line 27) so every existing mock (spread via `...STATE` in `GK`/`GK2`/`makeOutfield`) stays a valid `PlayerResponse`:

```ts
const STATE = { form: 0, morale: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0, injurySeverity: 'NONE' as const, eligible: true, role: null, contractYears: 2, wageDemand: 90000, fitness: 100 }
```

Then add this test inside the `describe('SquadPage', ...)` block. It overrides `mockGetSquad` for one render (the `beforeEach` already sets `getLineup` to null and provides `renderWithProviders`), and asserts both surfaces. Note both the row chip and the team chip contain "Contente", so we assert the team chip via its unique `Elenco:` prefix and use `getAllByText` for the row chip to avoid an ambiguous match:

```ts
it('shows a morale mood chip per player and a team dressing-room chip', async () => {
  mockGetSquad.mockResolvedValue({
    players: [{ ...MID1, id: 'p-happy', name: 'Feliz', morale: 2 }],
  })
  renderWithProviders()
  // Team "dressing room" chip (unique prefix)
  expect(await screen.findByText(/Elenco:/)).toBeInTheDocument()
  // At least the player row chip renders the Contente mood
  expect(screen.getAllByText(/Contente/).length).toBeGreaterThanOrEqual(1)
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: FAIL — no "Contente" text is rendered (the mood chip does not exist yet).

- [ ] **Step 3: Add `morale` to the frontend type**

In `frontend/src/api/types.ts`, in the `PlayerResponse` type, add after `form: number`:

```ts
  form: number
  morale: number
```

- [ ] **Step 4: Create the `MoodChip` component**

Create `frontend/src/features/squad/MoodChip.tsx`:

```tsx
import { Chip } from '@mui/material'
import { moraleMood } from './morale'

/** A small coloured chip showing a morale value's mood (emoji + label). */
export function MoodChip({ morale, prefix = '' }: { morale: number; prefix?: string }) {
  const mood = moraleMood(morale)
  return (
    <Chip
      size="small"
      label={`${prefix}${mood.emoji} ${mood.label}`}
      sx={{ bgcolor: mood.color, color: '#fff', fontWeight: 600 }}
    />
  )
}
```

- [ ] **Step 5: Wire the chip into `SquadPage.tsx`**

Add imports near the other feature imports (e.g. next to `import { FitnessBar } from './FitnessBar'`):

```tsx
import { MoodChip } from './MoodChip'
import { teamMorale } from './morale'
```

Add a "Moral" column header immediately after the existing `<TableCell align="center">Forma</TableCell>` (near line 409):

```tsx
                    <TableCell align="center">Forma</TableCell>
                    <TableCell align="center">Moral</TableCell>
```

Add the matching body cell immediately after the form cell `<TableCell align="center">{formLabel(player.form)}</TableCell>` (near line 427):

```tsx
                      <TableCell align="center">{formLabel(player.form)}</TableCell>
                      <TableCell align="center"><MoodChip morale={player.morale} /></TableCell>
```

Add a team-morale chip to the squad header summary line (near line 354, the "Formação … GOL/DEF/MEI/ATA" `Typography`). Immediately after that block, render:

```tsx
      {players.length > 0 && (
        <Box sx={{ mb: 2 }}>
          <MoodChip morale={teamMorale(players.map(p => p.morale))} prefix="Elenco: " />
        </Box>
      )}
```

(`players` is the squad array already defined at line 126: `const players = data?.players ?? []`.)

- [ ] **Step 6: Run tests AND the type-check to verify they pass**

Run (from `frontend/`):
```bash
npx vitest run src/features/squad/SquadPage.test.tsx src/features/squad/morale.test.ts
npx tsc -b
```
Expected: vitest PASS; `tsc -b` exits 0 with no output. (The `tsc -b` step is mandatory — it is what the Vercel build runs, and it is the only check that catches a missing required `morale` on any mock.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts \
        frontend/src/features/squad/MoodChip.tsx \
        frontend/src/features/squad/SquadPage.tsx \
        frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(morale): squad mood chips + team-morale dressing-room indicator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final verification (after all tasks)

- [ ] Backend: `JAVA_HOME=... ./gradlew test --tests "br.com.carvoeiromanager.match.*" --tests "br.com.carvoeiromanager.player.*" --tests "br.com.carvoeiromanager.season.*"` green (the known-flaky `RolloverContractsIT` may need an isolated re-run — it is unrelated to morale).
- [ ] Frontend: `npx vitest run` green and `npx tsc -b` clean.
- [ ] Manual: play a live match, win it, and confirm the squad screen mood chips rise for those who played and the "Elenco" team chip reflects the dressing room; confirm an unused reserve trends toward Descontente over a few rounds.
