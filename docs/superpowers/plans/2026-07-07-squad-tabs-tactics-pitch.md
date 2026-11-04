# Squad Tabs + Táticas de Jogo Pitch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the squad screen into "Plantel" / "Táticas de jogo" tabs (pitch + expandable tactics in tab 2), introduce shared friendly Portuguese tactic labels, and offer all 16 formations in the in-match tactics dialog.

**Architecture:** Frontend-only. A new `tacticsLabels.ts` module is the single label source consumed by `TacticsPanel` (squad) and `MatchActionsDialog` (live match). `SquadPage` swaps its two `Collapsible` sections for MUI `Tabs` whose panels stay mounted (hidden via the `hidden` attribute) so unsaved lineup AND unsaved tactics edits survive tab switches. The read-only `Pitch` moves from the Plantel content into tab 2, below an expandable "Instruções da equipe" section containing `TacticsPanel`.

**Tech Stack:** React 19, MUI 9, TanStack Query 5, Vitest 4 + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-07-07-squad-tabs-tactics-pitch-design.md`

## Global Constraints

- **NEVER run `git commit` or `git push`.** Prepare commit messages only; the user commits. (Overrides the executing skills' commit steps.)
- Work directly on `main` — no branches, no worktrees (user preference).
- Labels (exact strings): Mentalidade `DEFENSIVE→"Defensiva"`, `BALANCED→"Equilibrada"`, `ATTACKING→"Ofensiva"`; Pressão `LOW→"Baixa"`, `MEDIUM→"Média"`, `HIGH→"Alta"`; Marcação `ZONAL→"Por zona"`, `MAN_TIGHT→"Individual"`.
- Tab labels (exact strings): `"Plantel"` and `"Táticas de jogo"`. Expandable section title: `"Instruções da equipe"`.
- Wire format unchanged: selects submit enum values (`Mentality`/`Pressing`/`Marking` from `frontend/src/api/types.ts`); no API or backend change of any kind.
- Formation catalogue source of truth: `FORMATION_CODES` from `frontend/src/features/pitch/pitchSlots.ts` (16 codes). No hard-coded formation lists may remain in `MatchActionsDialog`.
- Test commands run from `frontend/`: `npx vitest run <file>` (single file) and `npx vitest run` (full suite).
- Working dir: repo root `<repo-root>` unless stated.

---

### Task 1: Shared tactic labels module

**Files:**
- Create: `frontend/src/features/squad/tacticsLabels.ts`
- Test: `frontend/src/features/squad/tacticsLabels.test.ts`

**Interfaces:**
- Consumes: `Mentality`, `Pressing`, `Marking` types from `frontend/src/api/types.ts` (string unions: `'DEFENSIVE' | 'BALANCED' | 'ATTACKING'`, `'LOW' | 'MEDIUM' | 'HIGH'`, `'ZONAL' | 'MAN_TIGHT'`).
- Produces: `MENTALITY_LABEL: Record<Mentality, string>`, `PRESSING_LABEL: Record<Pressing, string>`, `MARKING_LABEL: Record<Marking, string>` — consumed by Tasks 2 and 4.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/squad/tacticsLabels.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from './tacticsLabels'

describe('tacticsLabels', () => {
  it('maps every mentality to its friendly Portuguese label', () => {
    expect(MENTALITY_LABEL).toEqual({
      DEFENSIVE: 'Defensiva',
      BALANCED: 'Equilibrada',
      ATTACKING: 'Ofensiva',
    })
  })

  it('maps every pressing level to its friendly Portuguese label', () => {
    expect(PRESSING_LABEL).toEqual({ LOW: 'Baixa', MEDIUM: 'Média', HIGH: 'Alta' })
  })

  it('maps every marking scheme to its friendly Portuguese label', () => {
    expect(MARKING_LABEL).toEqual({ ZONAL: 'Por zona', MAN_TIGHT: 'Individual' })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/tacticsLabels.test.ts`
Expected: FAIL — cannot resolve `./tacticsLabels`.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/features/squad/tacticsLabels.ts`:

```ts
import type { Mentality, Pressing, Marking } from '../../api/types'

/**
 * Friendly Portuguese display names for the tactic enum values. Single source
 * for the squad TacticsPanel and the in-match MatchActionsDialog — the wire
 * format stays the enum; these are display-only.
 */
export const MENTALITY_LABEL: Record<Mentality, string> = {
  DEFENSIVE: 'Defensiva',
  BALANCED: 'Equilibrada',
  ATTACKING: 'Ofensiva',
}

export const PRESSING_LABEL: Record<Pressing, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
}

export const MARKING_LABEL: Record<Marking, string> = {
  ZONAL: 'Por zona',
  MAN_TIGHT: 'Individual',
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/tacticsLabels.test.ts`
Expected: 3 passed.

- [ ] **Step 5: Prepare (do not run) the commit message**

`feat(squad): shared friendly labels for tactic enum values`

---

### Task 2: TacticsPanel shows friendly labels

**Files:**
- Modify: `frontend/src/features/squad/TacticsPanel.tsx:19-21` (option lists) and `:113-121` (option rendering)
- Test: `frontend/src/features/squad/TacticsPanel.test.tsx`

**Interfaces:**
- Consumes: `MENTALITY_LABEL`, `PRESSING_LABEL`, `MARKING_LABEL` from `./tacticsLabels` (Task 1).
- Produces: no new exports; the Mentalidade/Pressão/Marcação selects display friendly labels while option values stay enums. Task 3 renders this panel unchanged inside tab 2.

- [ ] **Step 1: Extend the test with failing label assertions**

In `frontend/src/features/squad/TacticsPanel.test.tsx`, read the existing setup (it renders `<TacticsPanel>` with a query-client wrapper and mocked `getTactics`/`setTactics` — reuse the file's existing helpers verbatim) and ADD a test in the same `describe`:

```tsx
it('displays friendly labels while keeping enum option values', async () => {
  renderPanel() // ← use the file's existing render helper (adjust name to match)

  const mentality = await screen.findByLabelText('Mentalidade')
  const mentalityOptions = within(mentality.closest('div')!.parentElement!).getAllByRole('option')
  // Simpler and robust: query the select's own options
  const opts = Array.from((mentality as HTMLSelectElement).options).map(o => ({ value: o.value, label: o.text }))
  expect(opts).toEqual([
    { value: 'DEFENSIVE', label: 'Defensiva' },
    { value: 'BALANCED', label: 'Equilibrada' },
    { value: 'ATTACKING', label: 'Ofensiva' },
  ])

  const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
  expect(Array.from(pressing.options).map(o => o.text)).toEqual(['Baixa', 'Média', 'Alta'])

  const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
  expect(Array.from(marking.options).map(o => o.text)).toEqual(['Por zona', 'Individual'])
})
```

(Drop the `within(...)` line — the `HTMLSelectElement.options` approach is the assertion; shown here to make the intent unambiguous. If the file imports `within` already, leave imports as they are.)

Also VERIFY (don't rewrite) that the existing save test asserts the payload carries enum values (`mentality: 'DEFENSIVE'`-style). If no existing test pins the payload, extend the existing save test's expectation to assert `setTactics` was called with enum values, e.g. `expect.objectContaining({ mentality: 'ATTACKING' })`, matching however that test currently drives the selects.

- [ ] **Step 2: Run to verify the new test fails**

Run: `cd frontend && npx vitest run src/features/squad/TacticsPanel.test.tsx`
Expected: the new test FAILS (labels are currently the raw enums like `DEFENSIVE`); all pre-existing tests still pass.

- [ ] **Step 3: Implement friendly labels**

In `frontend/src/features/squad/TacticsPanel.tsx`:

Add the import:

```tsx
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from './tacticsLabels'
```

Replace the three option-rendering lines inside the selects (lines ~113-121):

```tsx
<SelectField label="Mentalidade" value={mentality} onChange={v => { setSaved(false); setMentality(v as Mentality) }}>
  {MENTALITIES.map(m => <option key={m} value={m}>{MENTALITY_LABEL[m]}</option>)}
</SelectField>
<SelectField label="Pressão" value={pressing} onChange={v => { setSaved(false); setPressing(v as Pressing) }}>
  {PRESSINGS.map(p => <option key={p} value={p}>{PRESSING_LABEL[p]}</option>)}
</SelectField>
<SelectField label="Marcação" value={marking} onChange={v => { setSaved(false); setMarking(v as Marking) }}>
  {MARKINGS.map(m => <option key={m} value={m}>{MARKING_LABEL[m]}</option>)}
</SelectField>
```

The `MENTALITIES`/`PRESSINGS`/`MARKINGS` arrays (lines 19-21) and everything else stay as-is.

CHECK: if any pre-existing test in `TacticsPanel.test.tsx` or `SquadPage.test.tsx` selects a tactic by its raw enum text (e.g. `selectOptions(..., 'DEFENSIVE')` is fine — that's by value — but `getByText('DEFENSIVE')` is not), update it to the friendly label. `user.selectOptions` by value continues to work unchanged.

- [ ] **Step 4: Run the panel tests**

Run: `cd frontend && npx vitest run src/features/squad/TacticsPanel.test.tsx`
Expected: all pass, including the new label test.

- [ ] **Step 5: Prepare (do not run) the commit message**

`feat(squad): TacticsPanel displays friendly tactic names`

---

### Task 3: SquadPage tabs — Plantel / Táticas de jogo

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx` (the return JSX, lines ~463-590, plus imports)
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: existing `TacticsPanel`, `Pitch`/`pitchTeam`, `Collapsible` (`defaultOpen` prop exists, defaults `true`).
- Produces: the tabbed layout the spec defines. No export changes.

- [ ] **Step 1: Write failing tests for the tab layout**

In `frontend/src/features/squad/SquadPage.test.tsx`, add tests (reuse the file's existing render helper / mock setup exactly as the other ~25 tests do). The existing test `renders the formation pitch with the selected XI` must ALSO be updated: it currently finds the pitch directly; it now needs to switch to the second tab first. Look at how that test locates the pitch (its queries stay valid once the tab is active — panels are mounted but hidden, so prefer visibility assertions).

```tsx
it('starts on the Plantel tab with the squad table and no visible pitch', async () => {
  renderSquadPage() // ← use the file's existing helper name
  expect(await screen.findByRole('tab', { name: 'Plantel', selected: true })).toBeInTheDocument()
  expect(screen.getByRole('tab', { name: 'Táticas de jogo' })).toBeInTheDocument()
  // The pitch container lives in the hidden tab-2 panel.
  const tacticsPanel = screen.getByRole('tabpanel', { hidden: true, name: 'Táticas de jogo' })
  expect(tacticsPanel).not.toBeVisible()
})

it('shows tactics controls above the pitch in the Táticas de jogo tab', async () => {
  renderSquadPage()
  const user = userEvent.setup()
  await user.click(await screen.findByRole('tab', { name: 'Táticas de jogo' }))
  const panel = screen.getByRole('tabpanel', { name: 'Táticas de jogo' })
  expect(panel).toBeVisible()
  const instrucoes = within(panel).getByRole('button', { name: /Instruções da equipe/ })
  expect(instrucoes).toHaveAttribute('aria-expanded', 'true')
  expect(within(panel).getByLabelText('Mentalidade')).toBeInTheDocument()
  // Tactics section renders before (above) the pitch in the DOM.
  const salvarTatica = within(panel).getByRole('button', { name: /Salvar tática/ })
  expect(salvarTatica.compareDocumentPosition(within(panel).getByTestId('pitch'))
    & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
})

it('preserves an unsaved lineup selection across tab switches', async () => {
  renderSquadPage()
  const user = userEvent.setup()
  // Select one player in Plantel (mirror how existing selection tests click the "+" button).
  const addButtons = await screen.findAllByRole('button', { name: /^Escalar / })
  await user.click(addButtons[0])
  await user.click(screen.getByRole('tab', { name: 'Táticas de jogo' }))
  await user.click(screen.getByRole('tab', { name: 'Plantel' }))
  // Still exactly one starter selected (helper line reflects it).
  expect(screen.getByText(/Titulares 1\/11/)).toBeInTheDocument()
})
```

Adjust query details (`getByTestId('pitch')`, helper names, how a player gets selected) to match the file's existing patterns — read the neighbouring tests first; the assertions above are the contract. If `Pitch`'s root has no test id, locate it the same way the existing pitch test does. `within` and `userEvent` are likely already imported.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: the three new tests FAIL (no tabs exist yet); pre-existing tests pass.

- [ ] **Step 3: Implement the tab layout**

In `frontend/src/features/squad/SquadPage.tsx`:

1. Add `Tabs, Tab` to the MUI import list. Add tab state next to the other `useState`s: `const [tab, setTab] = useState(0)`.
2. Replace the returned JSX's two `Collapsible` sections with the structure below. Everything currently inside `<Collapsible title="Escalação">` EXCEPT the pitch block moves into panel 0 verbatim; the pitch block and the `TacticsPanel` (previously in `<Collapsible title="Tática">`) move into panel 1. `PlayerDetails` and the `Snackbar` stay after the panels, unchanged.

```tsx
return (
  <Box>
    <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ mb: 2 }}
          aria-label="Seções do elenco">
      <Tab label="Plantel" id="squad-tab-0" aria-controls="squad-tabpanel-0" />
      <Tab label="Táticas de jogo" id="squad-tab-1" aria-controls="squad-tabpanel-1" />
    </Tabs>

    {/* Panels stay mounted (hidden) so unsaved lineup AND tactics edits survive tab switches. */}
    <Box role="tabpanel" hidden={tab !== 0} id="squad-tabpanel-0" aria-labelledby="squad-tab-0">
      {formationSelect}
      {/* …auto-pick/clear Stack, helperAndAlerts, mobile cards / desktop table,
          "Salvar escalação" Button — all EXACTLY as they are today, minus the
          `{pitchTeam && (<Box…><Pitch…/></Box>)}` block… */}
    </Box>

    <Box role="tabpanel" hidden={tab !== 1} id="squad-tabpanel-1" aria-labelledby="squad-tab-1">
      <Collapsible title="Instruções da equipe">
        <TacticsPanel
          saveGameId={saveGameId}
          clubId={clubId}
          starters={selected.map(id => {
            const p = players.find(pp => pp.id === id)!
            return { id, name: p.name }
          })}
        />
      </Collapsible>
      {pitchTeam && (
        <Box sx={{ mb: 2 }}>
          <Pitch home={pitchTeam} />
        </Box>
      )}
    </Box>

    <PlayerDetails … />   {/* unchanged */}
    <Snackbar … />        {/* unchanged */}
  </Box>
)
```

The `…` markers mean "move the existing JSX verbatim" — do not retype it; cut and paste the existing blocks. Do not modify `pitchTeam`, `helperAndAlerts`, `formationSelect`, or any handler. Remove the now-unused `Collapsible title="Escalação"` / `title="Tática"` wrappers (the `Collapsible` import stays — tab 2 uses it for "Instruções da equipe").

3. For the DOM-order test, ensure the tab-1 panel renders `Collapsible` BEFORE the pitch (as shown). If the pitch needs a stable hook for tests and lacks one, add `data-testid="pitch"` to the pitch wrapper `Box` in panel 1 (only if the existing pitch test doesn't already have a better locator).

- [ ] **Step 4: Run the page tests, fix fallout**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: all pass. Likely fallout to fix in tests (not in the component): tests that clicked "Salvar tática" or queried tactics selects without switching tabs still WORK because panels stay mounted — but visibility-based assertions (`toBeVisible`) need the right tab active; the old pitch test needs the tab switch added (Step 1).

- [ ] **Step 5: Run the full squad-feature tests**

Run: `cd frontend && npx vitest run src/features/squad`
Expected: all pass (TacticsPanel, FitnessBar, morale, etc. untouched).

- [ ] **Step 6: Prepare (do not run) the commit message**

`feat(squad): Plantel / Táticas de jogo tabs with CM-style pitch view`

---

### Task 4: MatchActionsDialog — 16 formations + shared labels

**Files:**
- Modify: `frontend/src/features/match/MatchActionsDialog.tsx:24-40` (constants) — the rest of the dialog is untouched
- Test: create `frontend/src/features/match/MatchActionsDialog.test.tsx`

**Interfaces:**
- Consumes: `MENTALITY_LABEL`, `PRESSING_LABEL`, `MARKING_LABEL` from `../squad/tacticsLabels` (Task 1); `FORMATION_CODES` from `../pitch/pitchSlots` (existing, 16 codes).
- Produces: the in-match tactics tab offers all 16 formations and the same labels as the squad screen.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/match/MatchActionsDialog.test.tsx`. Read `MatchActionsDialog.tsx` first for the exact `LivePlayer`/`LiveTactics` import sources and shapes, and mirror how `LiveMatchPage.test.tsx` builds fixtures if it has any. Minimal shape:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MatchActionsDialog } from './MatchActionsDialog'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'

function renderTacticsTab() {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={1}
      onPitch={[]}
      bench={[]}
      subsRemaining={3}
      pending={false}
      onApply={() => {}}
      currentTactics={{ mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }}
    />,
  )
}

describe('MatchActionsDialog tactics tab', () => {
  it('offers every formation from the shared catalogue', () => {
    renderTacticsTab()
    const formation = screen.getByLabelText('Formação') as HTMLSelectElement
    expect(Array.from(formation.options).map(o => o.value)).toEqual(FORMATION_CODES)
    expect(FORMATION_CODES).toHaveLength(16)
  })

  it('labels tactic options with the shared friendly names', () => {
    renderTacticsTab()
    const mentality = screen.getByLabelText('Mentalidade') as HTMLSelectElement
    expect(Array.from(mentality.options).map(o => o.text)).toEqual(Object.values(MENTALITY_LABEL))
    const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
    expect(Array.from(pressing.options).map(o => o.text)).toEqual(Object.values(PRESSING_LABEL))
    const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
    expect(Array.from(marking.options).map(o => o.text)).toEqual(Object.values(MARKING_LABEL))
  })
})
```

If the dialog's `SelectField` (line 125) doesn't wire `aria-label` the way `getByLabelText` needs, adapt the queries to the actual markup (check the component; it may use MUI `TextField select` or `NativeSelect`). If `currentTactics`'s type needs more fields, satisfy the type minimally. Keep fixtures inline — no fixture files.

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/match/MatchActionsDialog.test.tsx`
Expected: FAIL — formation list has 5 entries (`4-4-2, 4-3-3, 3-5-2, 4-2-3-1, 5-3-2`), and current labels differ (`Defensivo` vs `Defensiva`, `Zonal` vs `Por zona`, `Por homem` vs `Individual`).

- [ ] **Step 3: Implement**

In `frontend/src/features/match/MatchActionsDialog.tsx`, add imports:

```tsx
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'
import type { Mentality, Pressing, Marking } from '../../api/types'
```

Replace the four local constants (lines ~24-40) with:

```tsx
const MENTALITIES = (Object.keys(MENTALITY_LABEL) as Mentality[])
  .map(v => ({ value: v, label: MENTALITY_LABEL[v] }))
const PRESSINGS = (Object.keys(PRESSING_LABEL) as Pressing[])
  .map(v => ({ value: v, label: PRESSING_LABEL[v] }))
const MARKINGS = (Object.keys(MARKING_LABEL) as Marking[])
  .map(v => ({ value: v, label: MARKING_LABEL[v] }))
const FORMATIONS = FORMATION_CODES
```

Nothing else in the dialog changes (`FORMATIONS.map(f => ({ value: f, label: f }))` at line ~362 keeps working).

- [ ] **Step 4: Run the match-feature tests**

Run: `cd frontend && npx vitest run src/features/match`
Expected: all pass — including `LiveMatchPage.test.tsx`; if any of its assertions referenced the old labels (`Defensivo`, `Zonal`, `Por homem`), update them to the shared labels.

- [ ] **Step 5: Full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: all green.

- [ ] **Step 6: Prepare (do not run) the commit message**

`feat(match): all 16 formations and shared friendly tactic labels in match dialog`

---

## Self-Review Notes

- **Spec coverage:** tabs + panel structure (Task 3), pitch moved out of Plantel (Task 3), expandable "Instruções da equipe" above pitch (Task 3), friendly labels (Tasks 1-2), match dialog 16 formations + shared labels (Task 4), tests incl. tab-switch state preservation (Task 3 Step 1) — all spec sections mapped.
- **Known judgment call:** panels stay mounted with `hidden` (spec: unsaved edits must survive switches; also keeps TacticsPanel's local edits alive). Visibility assertions use `toBeVisible()` accordingly.
- **Type consistency:** label maps' names/types identical across Tasks 1, 2, 4; `FORMATION_CODES` used verbatim in Tasks 3 (already in SquadPage) and 4.
- **Order dependency:** Task 2 and Task 4 both depend only on Task 1; Task 3 depends on Task 2 only in that both touch squad tests — execute sequentially (1 → 2 → 3 → 4) to keep review diffs clean.
