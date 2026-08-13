# Squad, News and Training UX Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship eight UX fixes to the news inbox, squad screen and training screen, plus a 25% cut to the match engine's card rate.

**Architecture:** Seven frontend changes across `NewsPage.tsx`, `SquadPage.tsx`, `Pitch.tsx` and `SlotPickerDialog.tsx`, with the Treino tab extracted into a new `features/squad/training/` module that carries the new bulk-assignment feature. One backend change: a shared constant lowered in the two match-simulation paths.

**Tech Stack:** React 19 + TypeScript + MUI v9 + TanStack Query v5, tested with Vitest + React Testing Library. Backend is Java 25 / Spring Boot with JUnit 5 + AssertJ.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-13-squad-news-training-ux-design.md`. Read it before starting.
- **Language:** all user-facing copy is Brazilian Portuguese. Comments and identifiers are English, matching the surrounding code.
- **Git:** work directly on `main`. Do not create branches or worktrees. Commit after each task.
- **Frontend commands** (run from `frontend/`):
  - Single test file: `npx vitest run src/path/to/File.test.tsx`
  - Type check: `npx tsc -b` (`tsc --noEmit` is a no-op in this repo and proves nothing)
  - Full suite: `npx vitest run`
- **Backend commands** (run from repo root) — `JAVA_HOME` **must** be pinned or Gradle dies before compiling:
  ```bash
  cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
  ```
- **Style:** `const` by default, functional components, no `any`. Follow the heavy explanatory-comment convention already in `SquadPage.tsx` and `Pitch.tsx` for anything non-obvious.
- **TDD:** every task writes the failing test first, watches it fail, then implements.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `frontend/src/features/news/newsSelection.ts` | Pure selection rules for the inbox: which item to land on, which is next unread. |
| `frontend/src/features/news/newsSelection.test.ts` | Unit tests for the above. |
| `frontend/src/features/squad/RoleContextMenu.tsx` | Pointer-anchored menu holding the Função/Foco selects. |
| `frontend/src/features/squad/RoleContextMenu.test.tsx` | Unit tests for the above. |
| `frontend/src/features/squad/training/trainingLabels.ts` | `TRAINABLE_ATTRIBUTE_LABEL` and `INTENSITY_LABEL`, shared by the tab and the bulk card. |
| `frontend/src/features/squad/training/bulkTraining.ts` | Pure `applyBulkRules` + the shared training state/rule types. |
| `frontend/src/features/squad/training/bulkTraining.test.ts` | Unit tests for the above. |
| `frontend/src/features/squad/training/TrainingBulkCard.tsx` | Collapsible per-position rule editor. |
| `frontend/src/features/squad/training/TrainingBulkCard.test.tsx` | Unit tests for the above. |
| `frontend/src/features/squad/training/TrainingTab.tsx` | The whole Treino panel: bulk card, per-player table, save button, snackbar, mutation. |
| `frontend/src/features/squad/training/TrainingTab.test.tsx` | Unit tests for the above. |

**Modified:**

| Path | Change |
|---|---|
| `frontend/src/features/news/NewsPage.tsx` | Oldest-unread landing; `Ler próxima` button. |
| `frontend/src/features/news/NewsPage.test.tsx` | New cases; existing newest-first cases updated. |
| `frontend/src/features/squad/SquadPage.tsx` | Button row order; morale chip moved; Função/Foco columns removed; context menu wired; position guards relaxed; Treino panel delegated to `TrainingTab`. |
| `frontend/src/features/squad/SquadPage.test.tsx` | New cases; training cases moved to `TrainingTab.test.tsx`. |
| `frontend/src/features/squad/SlotPickerDialog.tsx` | Goalkeeper filter removed. |
| `frontend/src/features/squad/SlotPickerDialog.test.tsx` | New case. |
| `frontend/src/features/pitch/Pitch.tsx` | `onSlotContextMenu` prop (right-click + long-press); GK slot draggable and droppable. |
| `frontend/src/features/pitch/Pitch.test.tsx` | New cases; two GK cases inverted. |
| `frontend/src/features/players/positionLabels.ts` | `COARSE_POS_SHORT` and `squadPositionLabel` moved here for reuse. |
| `backend/.../match/LiveMatchEngine.java` | `BASE_CARD_RATE` 0.02 → 0.015. |
| `backend/.../match/MatchSimulationService.java` | `BASE_CARD_RATE` 0.02 → 0.015. |
| `backend/.../match/MatchSimulationServiceTest.java` | New calibration assertion. |

---

## Task 1: Notícias — oldest-unread landing and "Ler próxima"

Implements spec §4.

**Files:**
- Create: `frontend/src/features/news/newsSelection.ts`
- Create: `frontend/src/features/news/newsSelection.test.ts`
- Modify: `frontend/src/features/news/NewsPage.tsx:99-134` (selection effect and `handleSelect`), `:200-206` (insertion point for the button)
- Test: `frontend/src/features/news/NewsPage.test.tsx`

**Interfaces:**
- Consumes: `NewsFeedItem` from `../../api/types` (fields used: `id`, `read`).
- Produces: `initialSelection(items)` and `nextUnread(items, currentId)` from `./newsSelection`; both return `NewsFeedItem | null`. No later task consumes them.

**Background:** `items` is ordered **newest first** (index 0 is the newest). "Oldest unread" is therefore the *last* unread element, and "newer than the current selection" means a *lower* index.

- [ ] **Step 1: Write the failing unit tests**

Create `frontend/src/features/news/newsSelection.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { initialSelection, nextUnread } from './newsSelection'
import type { NewsFeedItem } from '../../api/types'

// Only `id` and `read` matter to these rules; the payload is irrelevant, so it is cast in.
const item = (id: string, read: boolean): NewsFeedItem =>
  ({ id, read, payload: {} as NewsFeedItem['payload'] })

// Newest first, matching the API's ordering.
const FEED = [item('n3', false), item('n2', false), item('n1', true)]

describe('initialSelection', () => {
  it('returns the oldest unread item', () => {
    expect(initialSelection(FEED)?.id).toBe('n2')
  })

  it('returns the newest item when everything is read', () => {
    expect(initialSelection([item('n3', true), item('n2', true)])?.id).toBe('n3')
  })

  it('returns null for an empty feed', () => {
    expect(initialSelection([])).toBeNull()
  })
})

describe('nextUnread', () => {
  it('returns the nearest unread item newer than the current selection', () => {
    const feed = [item('n4', false), item('n3', true), item('n2', false), item('n1', true)]
    expect(nextUnread(feed, 'n2')?.id).toBe('n4')
  })

  it('falls back to the oldest unread when nothing newer is unread', () => {
    const feed = [item('n4', true), item('n3', true), item('n2', false), item('n1', false)]
    expect(nextUnread(feed, 'n4')?.id).toBe('n1')
  })

  it('returns the oldest unread when there is no current selection', () => {
    expect(nextUnread(FEED, null)?.id).toBe('n2')
  })

  it('returns null when nothing is unread', () => {
    expect(nextUnread([item('n2', true), item('n1', true)], 'n2')).toBeNull()
  })

  it('returns null for an empty feed', () => {
    expect(nextUnread([], null)).toBeNull()
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/news/newsSelection.test.ts`
Expected: FAIL — `Failed to resolve import "./newsSelection"`.

- [ ] **Step 3: Write the module**

Create `frontend/src/features/news/newsSelection.ts`:

```ts
import type { NewsFeedItem } from '../../api/types'

/**
 * The item the inbox should land on: the OLDEST unread item, so the reader works forward in
 * time with "Ler próxima" instead of backwards. With nothing unread there is nothing to work
 * through, so the newest item is shown — which is also what lands after "Avançar" once its
 * fresh result has been read.
 *
 * `items` is newest-first, so the oldest unread is the LAST unread element.
 */
export function initialSelection(items: NewsFeedItem[]): NewsFeedItem | null {
  if (items.length === 0) return null
  const unread = items.filter(it => !it.read)
  return unread.length > 0 ? unread[unread.length - 1] : items[0]
}

/**
 * The item "Ler próxima" should move to: the nearest unread item NEWER than `currentId`
 * (a lower index, since `items` is newest-first). When nothing newer is unread the walk wraps
 * to the oldest unread anywhere in the list, so the button never dead-ends while unread items
 * remain. Null when everything loaded is read.
 */
export function nextUnread(items: NewsFeedItem[], currentId: string | null): NewsFeedItem | null {
  const unread = items.filter(it => !it.read)
  if (unread.length === 0) return null
  const currentIndex = currentId === null ? -1 : items.findIndex(it => it.id === currentId)
  for (let i = currentIndex - 1; i >= 0; i--) {
    if (!items[i].read) return items[i]
  }
  return unread[unread.length - 1]
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/news/newsSelection.test.ts`
Expected: PASS, 9 tests.

- [ ] **Step 5: Write the failing page tests**

Append to the outermost `describe` in `frontend/src/features/news/NewsPage.test.tsx`:

```tsx
it('lands on the oldest unread item, not the newest', async () => {
  vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
  vi.mocked(getNews).mockResolvedValue({
    content: [
      { id: 'n3', read: false, payload: match(13) },
      { id: 'n2', read: false, payload: match(12) },
      { id: 'n1', read: true, payload: match(11) },
    ],
    page: 1, size: 10, totalElements: 3, totalPages: 1,
  })
  vi.mocked(markNewsRead).mockResolvedValue(undefined)
  renderPage()
  await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n2', expect.anything()))
})

it('lands on the newest item when everything is already read', async () => {
  vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
  vi.mocked(getNews).mockResolvedValue({
    content: [
      { id: 'n3', read: true, payload: match(13) },
      { id: 'n2', read: true, payload: match(12) },
    ],
    page: 1, size: 10, totalElements: 2, totalPages: 1,
  })
  renderPage()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (0)' })).toBeDisabled())
  expect(markNewsRead).not.toHaveBeenCalled()
})

it('"Ler próxima" walks forward to the next unread item and marks it read', async () => {
  vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
  vi.mocked(getNews).mockResolvedValue({
    content: [
      { id: 'n3', read: false, payload: match(13) },
      { id: 'n2', read: false, payload: match(12) },
    ],
    page: 1, size: 10, totalElements: 2, totalPages: 1,
  })
  vi.mocked(markNewsRead).mockResolvedValue(undefined)
  renderPage()
  // Landed on n2 (the oldest unread), leaving exactly one unread.
  await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (1)' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Ler próxima (1)' }))
  await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n3', expect.anything()))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (0)' })).toBeDisabled())
})
```

Then check the existing cases: any that assert the page lands on the **newest** item must be updated to the oldest-unread rule. Run the file and fix whatever the new behaviour breaks — do not delete a case, restate its expectation.

- [ ] **Step 6: Run the page tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/news/NewsPage.test.tsx`
Expected: FAIL — `Unable to find an accessible element with the role "button" and name "Ler próxima (0)"`, plus the oldest-unread case marking `n3` instead of `n2`.

- [ ] **Step 7: Change the selection effect**

In `frontend/src/features/news/NewsPage.tsx`, add to the imports:

```tsx
import { initialSelection, nextUnread } from './newsSelection'
```

Replace the body of the auto-select `useEffect` (currently `NewsPage.tsx:106-121`) with:

```tsx
  // Land on the oldest unread item (see `initialSelection`) rather than the newest, so the
  // reader works forward in time with "Ler próxima". Re-runs on the same triggers as before:
  // a strictly-newer item arriving (Avançar prepending a fresh result), or the current
  // selection dropping out of the list.
  useEffect(() => {
    if (items.length === 0) {
      lastNewestId.current = null
      if (selectedId !== null) setSelectedId(null)
      return
    }
    const newest = items[0]
    const newestArrived = newest.id !== lastNewestId.current
    const selectionPresent = selectedId !== null && items.some(it => it.id === selectedId)
    if (newestArrived || !selectionPresent) {
      lastNewestId.current = newest.id
      const target = initialSelection(items)
      if (target) {
        setSelectedId(target.id)
        if (!target.read) markReadMutation.mutate(target.id)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, items[0]?.id, selectedId])
```

- [ ] **Step 8: Add the button**

Still in `NewsPage.tsx`, just after `const handleSelect = ...` add:

```tsx
  const nextItem = nextUnread(items, selectedId)
```

Then insert this block between the `hasNextPage` "Carregar mais" block and the `{selected && <AdvanceEventCard ... />}` block:

```tsx
          <Box sx={{ display: 'flex', justifyContent: 'center', mb: 2 }}>
            <Button
              variant="outlined"
              size="small"
              disabled={nextItem === null}
              onClick={() => { if (nextItem) handleSelect(nextItem) }}
            >
              {`Ler próxima (${loadedUnread})`}
            </Button>
          </Box>
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/news/`
Expected: PASS, all files.

- [ ] **Step 10: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/news/
git commit -m "feat(news): land on the oldest unread item and add Ler próxima"
```

---

## Task 2: Plantel and Táticas layout

Implements spec §5.

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx:859-873` (`helperAndAlerts`), `:886-894` (button row), `:963-969` (bottom save button), `:987-1000` (Táticas panel)
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**

Add to `frontend/src/features/squad/SquadPage.test.tsx`, inside the `describe` whose `beforeEach` (at roughly line 149) already primes `mockGetSquad`/`mockGetLineup` — so these cases need no mock setup of their own:

```tsx
it('renders Salvar escalação before Selecionar melhores, and not at the bottom of Plantel', async () => {
  renderWithProviders()
  await screen.findByText('Goleiro Teste')
  // Only one save button is reachable: the Táticas panel is mounted but `hidden`, so its copy
  // is out of the accessibility tree.
  expect(screen.getAllByRole('button', { name: 'Salvar escalação' })).toHaveLength(1)
  const labels = screen.getAllByRole('button').map(b => b.textContent)
  expect(labels.indexOf('Salvar escalação')).toBeLessThan(labels.indexOf('Selecionar melhores'))
})

it('shows the squad morale chip in the Táticas tab, not in Plantel', async () => {
  renderWithProviders()
  await screen.findByText('Goleiro Teste')
  expect(screen.queryByText(/Elenco:/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
  expect(screen.getByText(/Elenco:/)).toBeInTheDocument()
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx -t 'Salvar escalação before'`
Expected: FAIL — `expected 1 to be less than 0` or similar, because the save button currently comes last.

- [ ] **Step 3: Move the save button to the top of Plantel**

In `SquadPage.tsx`, replace the Plantel button `Stack` with:

```tsx
            <Stack direction="row" spacing={1} useFlexGap sx={{ mb: 2, flexWrap: 'wrap' }}>
              <Button
                color="secondary"
                size="small"
                variant="contained"
                disabled={!canSave || mutation.isPending}
                onClick={() => mutation.mutate()}
              >
                {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
              </Button>
              <Button size="small" variant="outlined" color="secondary" onClick={autoPickBestXI}>
                Selecionar melhores
              </Button>
              <Button size="small" variant="outlined" color="inherit" onClick={clearSelection} disabled={selected.length === 0}>
                Limpar
              </Button>
            </Stack>
```

Then delete the standalone save `<Button>` that currently sits after the table, at the end of the Plantel panel (`SquadPage.tsx:963-969`).

- [ ] **Step 4: Move the morale chip to Táticas**

Delete this block from `helperAndAlerts`:

```tsx
      {players.length > 0 && (
        <Box sx={{ mb: 2 }}>
          <MoodChip morale={teamMorale(players.map(p => p.morale))} prefix="Elenco: " />
        </Box>
      )}
```

In the Táticas panel, insert it directly above the `<Pitch .../>`, inside the pitch column `<Box>`:

```tsx
            <Box sx={{ flex: 1, minWidth: 0, mb: 2 }}>
              {players.length > 0 && (
                <Box sx={{ mb: 1.5, display: 'flex', justifyContent: 'center' }}>
                  <MoodChip morale={teamMorale(players.map(p => p.morale))} prefix="Elenco: " />
                </Box>
              )}
              <Pitch home={pitchTeam} onSwap={swapSlots} onSlotClick={slotIndex => setPickerSlot(slotIndex)} />
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: PASS. If an older test asserted the save button's position implicitly (e.g. `getAllByRole('button')` index), update it.

- [ ] **Step 6: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(squad): save button to the top of Plantel, morale chip above the pitch"
```

---

## Task 3: Pitch — `onSlotContextMenu` via right-click and long-press

Implements spec §6.1.

**Files:**
- Modify: `frontend/src/features/pitch/Pitch.tsx`
- Test: `frontend/src/features/pitch/Pitch.test.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Pitch`'s new optional prop
  `onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void`.
  Fires only for home-team slots that hold a player. Task 4 consumes it.

- [ ] **Step 1: Write the failing tests**

Append a new `describe` at the end of `frontend/src/features/pitch/Pitch.test.tsx`. Note it needs `act` from `@testing-library/react` and `beforeEach`/`afterEach` from `vitest` — add them to the existing imports at the top of the file if absent.

```tsx
describe('context menu (right-click and long-press)', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  function fullXi(): PlacedPlayer[] {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT', slotIndex: number): PlacedPlayer =>
      ({ id, surname: id, position, detailedPosition: null, fitness: 100, slotIndex })
    return [
      mk('gk', 'GK', 0),
      mk('d1', 'DEF', 1), mk('d2', 'DEF', 2), mk('d3', 'DEF', 3), mk('d4', 'DEF', 4),
      mk('m1', 'MID', 5), mk('m2', 'MID', 6), mk('m3', 'MID', 7),
      mk('a1', 'ATT', 8), mk('a2', 'ATT', 9), mk('a3', 'ATT', 10),
    ]
  }

  function renderMenuable(players = fullXi()) {
    const onSlotClick = vi.fn()
    const onSlotContextMenu = vi.fn()
    const onSwap = vi.fn()
    const { container } = render(
      <Pitch
        home={{ formation: '4-3-3', kit, showNames: true, players }}
        onSwap={onSwap}
        onSlotClick={onSlotClick}
        onSlotContextMenu={onSlotContextMenu}
      />,
    )
    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const token = (slot: number) => container.querySelector(`[data-slot-index="${slot}"]`)!
    return { onSwap, onSlotClick, onSlotContextMenu, token }
  }

  it('right-click on an occupied slot reports slotIndex and coordinates', () => {
    const { onSlotContextMenu, token } = renderMenuable()
    fireEvent.contextMenu(token(1), { clientX: 64, clientY: 474 })
    expect(onSlotContextMenu).toHaveBeenCalledWith(1, 64, 474)
  })

  it('right-click on an empty slot does nothing', () => {
    const players = fullXi().filter(p => p.slotIndex !== 10)
    const { onSlotContextMenu, token } = renderMenuable(players)
    fireEvent.contextMenu(token(10), { clientX: 200, clientY: 192 })
    expect(onSlotContextMenu).not.toHaveBeenCalled()
  })

  describe('long-press', () => {
    beforeEach(() => { vi.useFakeTimers() })
    afterEach(() => { vi.useRealTimers() })

    it('a 500ms hold fires the context menu and suppresses the tap', () => {
      const { onSlotContextMenu, onSlotClick, onSwap, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).toHaveBeenCalledWith(1, 64, 474)
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      expect(onSlotClick).not.toHaveBeenCalled()
      expect(onSwap).not.toHaveBeenCalled()
    })

    it('a short tap still fires onSlotClick and no context menu', () => {
      const { onSlotContextMenu, onSlotClick, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      act(() => { vi.advanceTimersByTime(200) })
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      expect(onSlotClick).toHaveBeenCalledWith(1)
      expect(onSlotContextMenu).not.toHaveBeenCalled()
    })

    it('a hold that moves past the threshold is a drag, not a context menu', () => {
      const { onSlotContextMenu, onSwap, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).not.toHaveBeenCalled()
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
      expect(onSwap).toHaveBeenCalledWith(1, 9)
    })
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx -t 'context menu'`
Expected: FAIL — `onSlotContextMenu` is never called (the prop does not exist).

- [ ] **Step 3: Implement the prop in `Pitch.tsx`**

Add the constant beside the existing `CLICK_MOVE_THRESHOLD`:

```tsx
// A pointer held this long without moving past CLICK_MOVE_THRESHOLD opens the context menu —
// the touch equivalent of a right-click.
const LONG_PRESS_MS = 500
```

Add `onSlotContextMenu` to `TeamLayer`'s props and destructuring:

```tsx
function TeamLayer({ team, mirror, onSwap, onSlotClick, onSlotContextMenu, containerRef }: {
  team: TeamOnPitch
  mirror: boolean
  onSwap?: (fromSlot: number, toSlot: number) => void
  onSlotClick?: (slotIndex: number) => void
  onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void
  containerRef?: React.RefObject<HTMLDivElement | null>
}) {
```

Inside `TeamLayer`, after `const [drag, setDrag] = useState<Drag | null>(null)`, add:

```tsx
  // Long-press bookkeeping. One set of refs for the whole layer, like `drag` above: only one
  // pointer interaction is ever in flight, so per-slot refs would buy nothing.
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const longPressFired = useRef(false)
  const pressStart = useRef<{ x: number; y: number } | null>(null)

  function cancelLongPress() {
    if (longPressTimer.current !== null) {
      clearTimeout(longPressTimer.current)
      longPressTimer.current = null
    }
    pressStart.current = null
  }
```

Inside the `positioned.map` callback, after `const interactive = draggable || tappable`, add:

```tsx
        // The context menu is offered only for slots that actually hold a player — there is no
        // Função/Foco to edit on an empty placeholder.
        const contextual = Boolean(onSlotContextMenu) && !mirror && player !== null
        const pointerActive = interactive || contextual
```

Replace the four pointer handlers and the `touchAction` line. The handlers now key off `pointerActive`, and each piece of logic re-checks the narrower flag it needs:

```tsx
            onContextMenu={contextual ? (e: React.MouseEvent) => {
              e.preventDefault()
              onSlotContextMenu?.(i, e.clientX, e.clientY)
            } : undefined}
            onPointerDown={pointerActive ? e => {
              e.currentTarget.setPointerCapture?.(e.pointerId)
              longPressFired.current = false
              if (contextual) {
                const { clientX, clientY } = e
                pressStart.current = { x: clientX, y: clientY }
                longPressTimer.current = setTimeout(() => {
                  longPressTimer.current = null
                  longPressFired.current = true
                  onSlotContextMenu?.(i, clientX, clientY)
                }, LONG_PRESS_MS)
              }
              if (interactive) {
                setDrag({ fromSlot: i, startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY, maxMoved: 0 })
              }
            } : undefined}
            onPointerMove={pointerActive ? e => {
              // Moving past the tap threshold turns the interaction into a drag, so the pending
              // long-press is abandoned rather than firing mid-drag.
              if (pressStart.current) {
                const moved = Math.hypot(e.clientX - pressStart.current.x, e.clientY - pressStart.current.y)
                if (moved > CLICK_MOVE_THRESHOLD) cancelLongPress()
              }
              setDrag(d => {
                if (!d || d.fromSlot !== i) return d
                const moved = Math.hypot(e.clientX - d.startX, e.clientY - d.startY)
                return { ...d, x: e.clientX, y: e.clientY, maxMoved: Math.max(d.maxMoved, moved) }
              })
            } : undefined}
            onPointerUp={pointerActive ? e => {
              cancelLongPress()
              // A long-press already opened the menu; the release must not ALSO pop the slot
              // picker on top of it.
              if (longPressFired.current) {
                longPressFired.current = false
                setDrag(null)
                return
              }
              if (!drag || drag.fromSlot !== i) return
              // Resolve tap-vs-drag by the farthest the pointer travelled during the WHOLE
              // interaction, not just the net start→release displacement — a user who drags a
              // token away and deliberately brings it back near its origin (changing their mind)
              // must still be treated as a (cancelled) drag, not a tap that pops the picker open.
              const upMoved = Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY)
              const moved = Math.max(drag.maxMoved, upMoved)
              setDrag(null)
              if (moved <= CLICK_MOVE_THRESHOLD) {
                onSlotClick?.(i)
                return
              }
              if (!draggable) return
              const target = dropTarget(e.clientX, e.clientY, i)
              if (target !== null) onSwap?.(i, target)
            } : undefined}
            onPointerCancel={pointerActive ? () => {
              cancelLongPress()
              longPressFired.current = false
              setDrag(null)
            } : undefined}
```

and in the `sx` block change `touchAction: interactive ? 'none' : undefined` to:

```tsx
              touchAction: pointerActive ? 'none' : undefined,
```

Finally thread the prop through `Pitch` itself — add it to the props type with this doc comment, and pass it to the home `TeamLayer`:

```tsx
  /**
   * Fired with a home-team slot index and the pointer's client coordinates on a right-click or
   * a ~500ms long-press, for slots that HOLD A PLAYER. A long-press suppresses the `onSlotClick`
   * that its release would otherwise produce; dragging is unaffected.
   */
  onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void
```

```tsx
      <TeamLayer team={home} mirror={false} onSwap={onSwap} onSlotClick={onSlotClick} onSlotContextMenu={onSlotContextMenu} containerRef={containerRef} />
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: PASS, whole file (the pre-existing drag and tap cases must still pass unchanged).

- [ ] **Step 5: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/pitch/Pitch.tsx frontend/src/features/pitch/Pitch.test.tsx
git commit -m "feat(pitch): report a context-menu gesture on occupied slots"
```

---

## Task 4: `RoleContextMenu` and the removed table columns

Implements spec §6.2 and §6.3.

**Files:**
- Create: `frontend/src/features/squad/RoleContextMenu.tsx`
- Create: `frontend/src/features/squad/RoleContextMenu.test.tsx`
- Modify: `frontend/src/features/squad/SquadPage.tsx` (imports, state, table columns, Táticas `Pitch` call, render tree)
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: `Pitch`'s `onSlotContextMenu` from Task 3.
- Produces: `RoleContextMenu` and `type RoleMenuAnchor = { playerId: string; x: number; y: number }` from `./RoleContextMenu`.

- [ ] **Step 1: Write the failing component test**

Create `frontend/src/features/squad/RoleContextMenu.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { RoleContextMenu } from './RoleContextMenu'

describe('RoleContextMenu', () => {
  it('renders nothing while closed', () => {
    render(
      <RoleContextMenu
        anchor={null}
        playerName="Fellipe Mateus"
        funcaoControl={<select aria-label="Função" />}
        focoControl={<select aria-label="Foco" />}
        onClose={() => {}}
      />,
    )
    expect(screen.queryByText('Fellipe Mateus')).not.toBeInTheDocument()
  })

  it('shows the player name and both controls when anchored', () => {
    render(
      <RoleContextMenu
        anchor={{ playerId: 'p1', x: 120, y: 240 }}
        playerName="Fellipe Mateus"
        funcaoControl={<select aria-label="Função" />}
        focoControl={<select aria-label="Foco" />}
        onClose={() => {}}
      />,
    )
    expect(screen.getByText('Fellipe Mateus')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Função' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Foco' })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/RoleContextMenu.test.tsx`
Expected: FAIL — `Failed to resolve import "./RoleContextMenu"`.

- [ ] **Step 3: Write the component**

Create `frontend/src/features/squad/RoleContextMenu.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Menu, Box, Typography } from '@mui/material'

/** Which player the menu is open for, and where the pointer was when it opened. */
export type RoleMenuAnchor = { playerId: string; x: number; y: number }

/**
 * The Função/Foco editor for a starter, opened by right-clicking (or long-pressing) their token
 * on the pitch. Replaces the two columns the Plantel table used to carry: those only ever
 * populated for starters, which is exactly the set of players visible on the pitch.
 *
 * The controls are passed in rather than built here — they close over SquadPage's role/focus
 * state and its slot-derived position rules, which have no business being duplicated.
 * The menu deliberately does NOT close on change, so both fields can be set in one open.
 */
export function RoleContextMenu({ anchor, playerName, funcaoControl, focoControl, onClose }: {
  anchor: RoleMenuAnchor | null
  playerName: string
  funcaoControl: ReactNode
  focoControl: ReactNode
  onClose: () => void
}) {
  return (
    <Menu
      open={anchor !== null}
      onClose={onClose}
      anchorReference="anchorPosition"
      anchorPosition={anchor ? { top: anchor.y, left: anchor.x } : undefined}
    >
      <Box sx={{ px: 2, py: 1, minWidth: 220 }}>
        <Typography sx={{ fontWeight: 700, mb: 1 }}>{playerName}</Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Função</Typography>
        <Box sx={{ mb: 1.5 }}>{funcaoControl}</Box>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Foco</Typography>
        <Box>{focoControl}</Box>
      </Box>
    </Menu>
  )
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/RoleContextMenu.test.tsx`
Expected: PASS, 2 tests.

- [ ] **Step 5: Write the failing SquadPage tests**

Add to `frontend/src/features/squad/SquadPage.test.tsx`, in the same `describe` used in Task 2:

```tsx
it('no longer shows Função and Foco columns in the Plantel table', async () => {
  renderWithProviders()
  await screen.findByText('Goleiro Teste')
  expect(screen.queryByRole('columnheader', { name: 'Função' })).not.toBeInTheDocument()
  expect(screen.queryByRole('columnheader', { name: 'Foco' })).not.toBeInTheDocument()
})

it('right-clicking a pitch token opens the Função/Foco menu for that player', async () => {
  renderWithProviders()
  await screen.findByText('Goleiro Teste')
  fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
  fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
  // Auto-pick puts the higher-rated keeper (Goleiro Dois, OVR 75) in slot 0.
  const gkToken = screen.getByTestId('pitch').querySelector('[data-slot-index="0"]')!
  fireEvent.contextMenu(gkToken, { clientX: 100, clientY: 200 })
  expect(await screen.findByRole('combobox', { name: 'Função de Goleiro Dois' })).toBeInTheDocument()
  expect(screen.getByRole('combobox', { name: 'Foco de Goleiro Dois' })).toBeInTheDocument()
})
```

`fireEvent.contextMenu` needs no `getBoundingClientRect` stub — it carries its own coordinates.

- [ ] **Step 6: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx -t 'Função'`
Expected: FAIL — the column headers still exist, and no menu opens.

- [ ] **Step 7: Remove the table columns**

In `SquadPage.tsx`, delete these two header cells from the Plantel `TableHead`:

```tsx
                      <TableCell align="center">Função</TableCell>
                      <TableCell align="center">Foco</TableCell>
```

and these two body cells from the row:

```tsx
                          <TableCell align="center">{inv.kind === 'TITULAR' && funcaoSelect(player)}</TableCell>
                          <TableCell align="center">{inv.kind === 'TITULAR' && focoSelect(player)}</TableCell>
```

`const inv = involvementOf(player.id)` inside the `displayPlayers.map` is now unused — delete it and change `displayPlayers.map(player => { ... return (<TableRow>…` back to a direct `displayPlayers.map(player => (<TableRow …>))` expression.

`funcaoSelect` and `focoSelect` stay: they are still used by the mobile `PlayerDetails` controls and now by the menu.

- [ ] **Step 8: Wire the menu**

Add the import:

```tsx
import { RoleContextMenu, type RoleMenuAnchor } from './RoleContextMenu'
```

Add state beside `pickerSlot`:

```tsx
  // The player whose Função/Foco menu is open (right-click / long-press on their pitch token),
  // plus the pointer position to anchor it at; null when the menu is closed.
  const [roleMenu, setRoleMenu] = useState<RoleMenuAnchor | null>(null)
```

Add the derived player just below `detailsPlayer`:

```tsx
  const roleMenuPlayer = players.find(p => p.id === roleMenu?.playerId) ?? null
```

Pass the handler to the Táticas `Pitch`:

```tsx
              <Pitch
                home={pitchTeam}
                onSwap={swapSlots}
                onSlotClick={slotIndex => setPickerSlot(slotIndex)}
                onSlotContextMenu={(slotIndex, x, y) => {
                  const playerId = Object.keys(slotByPlayerId).find(id => slotByPlayerId[id] === slotIndex)
                  if (playerId) setRoleMenu({ playerId, x, y })
                }}
              />
```

Render the menu next to `SlotPickerDialog`:

```tsx
      {/* Opened by right-clicking / long-pressing a pitch token in the Táticas tab (spec §6.2). */}
      <RoleContextMenu
        anchor={roleMenu}
        playerName={roleMenuPlayer?.name ?? ''}
        funcaoControl={roleMenuPlayer ? funcaoSelect(roleMenuPlayer) : null}
        focoControl={roleMenuPlayer ? focoSelect(roleMenuPlayer) : null}
        onClose={() => setRoleMenu(null)}
      />
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/`
Expected: PASS. Any existing test that reached a Função/Foco select through the Plantel table must be rewritten to go through the pitch menu (desktop) or `PlayerDetails` (mobile) — do not delete the coverage.

- [ ] **Step 10: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/squad/
git commit -m "feat(squad): edit Função/Foco from the pitch instead of the table"
```

---

## Task 5: Stop blocking out-of-position picks

Implements spec §7.

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx:133-163` (`validate`), `:415-422` (`firstFreeSlotFor`)
- Modify: `frontend/src/features/squad/SlotPickerDialog.tsx:85-86`
- Modify: `frontend/src/features/pitch/Pitch.tsx` (`dropTarget`, `draggable`)
- Test: `frontend/src/features/squad/SquadPage.test.tsx`, `frontend/src/features/squad/SlotPickerDialog.test.tsx`, `frontend/src/features/pitch/Pitch.test.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new.

**Background:** the backend permits **0 or 1** goalkeepers in the XI (`LineupService.java:109`) and can improvise a keeper from outfielders. Everything else here is frontend-only strictness.

- [ ] **Step 1: Invert the two Pitch GK tests**

In `frontend/src/features/pitch/Pitch.test.tsx`, replace these two existing cases:

```tsx
  it('the GK token is not draggable', () => { ... })
  it('the GK slot is never a drop target', () => { ... })
```

with:

```tsx
  it('the GK token is draggable — an outfielder may be improvised in goal', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(0), { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(token(0), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(0, 9)
  })

  it('the GK slot is a valid drop target', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 564 })
    expect(onSwap).toHaveBeenCalledWith(1, 0)
  })
```

Also retitle the tap case `'a tap on the GK slot fires onSlotClick even though the GK token is never draggable'` to `'a tap on the GK slot fires onSlotClick'` — its assertions are unchanged and still correct.

- [ ] **Step 2: Write the failing squad and picker tests**

In `frontend/src/features/squad/SlotPickerDialog.test.tsx`, **invert** the existing case
`'excludes GK-position players from an outfield slot (the XI allows at most 1 goalkeeper)'` — it asserts exactly the behaviour being removed. Replace it with:

```tsx
  it('offers GK-position players for an outfield slot, flagged as improvised', () => {
    const keeper = mkPlayer({ id: 'gk1', name: 'Goleiro Um', position: 'GK', detailedPosition: 'GOLEIRO' })
    const defender = mkPlayer({ id: 'd1', name: 'Zagueiro Um', detailedPosition: 'LATERAL_ESQUERDO' })
    renderDialog({ slot: LE_SLOT, players: [keeper, defender] })

    // Improvising a keeper outfield is the manager's call; the dialog informs, it does not filter.
    expect(screen.getByText('Goleiro Um')).toBeInTheDocument()
    expect(screen.getByTestId('slot-picker-row-gk1')).toHaveAttribute('data-natural', 'false')
    expect(within(screen.getByTestId('slot-picker-row-gk1')).getByText('Improvisado')).toBeInTheDocument()
    expect(screen.getByText('Zagueiro Um')).toBeInTheDocument()
  })
```

Then add to `frontend/src/features/squad/SquadPage.test.tsx`, inside the **nested** `describe` that defines `mockRect()` (around line 1127) — these cases need its pitch-rect stub to drive the picker:

```tsx
  it('allows an XI with no recognised goalkeeper', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    // Slot 0 (GK) center in the default 4-3-3 layout.
    const slot0 = pitch.querySelector('[data-slot-index="0"]')!
    fireEvent.pointerDown(slot0, { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(slot0, { pointerId: 1, clientX: 200, clientY: 564 })

    // Put an unpicked outfielder in goal; the displaced keeper drops to the bench, leaving a
    // full XI with zero goalkeepers.
    fireEvent.click(await screen.findByTestId('slot-picker-row-p-m4'))
    await waitFor(() => expect(pitch.querySelector('[data-slot-index="0"]')).toHaveTextContent('Meio Quatro'))
    expect(screen.queryByText(/goleiro/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar escalação' })).toBeEnabled()
  })

  it('blocks an XI containing two goalkeepers', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    // Slot 4 ("le", LATERAL_ESQUERDO) center — an outfield slot.
    const slot4 = pitch.querySelector('[data-slot-index="4"]')!
    fireEvent.pointerDown(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerUp(slot4, { pointerId: 1, clientX: 336, clientY: 474 })

    // Auto-pick fields Goleiro Dois (OVR 75) in goal; adding Goleiro Teste outfield makes two.
    fireEvent.click(await screen.findByTestId('slot-picker-row-p-gk'))
    expect(await screen.findByText('No máximo 1 goleiro na escalação')).toBeInTheDocument()
  })
```

The squad fixture already contains two keepers (`p-gk` "Goleiro Teste" OVR 72 and `p-gk2` "Goleiro Dois" OVR 75) and two unpicked outfielders (`p-m4` "Meio Quatro", `p-a4` "Atacante Quatro"), so no fixture changes are needed.

- [ ] **Step 3: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/ src/features/pitch/`
Expected: FAIL — the GK is filtered out of the picker, the GK slot is neither draggable nor droppable, and a keeper-less XI still shows the old error.

- [ ] **Step 4: Relax `validate`**

In `SquadPage.tsx`, replace:

```tsx
  const counts = countByPosition(selected, players)
  if (counts.GK !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
  }
```

with:

```tsx
  // The ONLY position rule left. Playing a player out of position — including an outfielder
  // improvised in goal, or no recognised goalkeeper at all — is the manager's call, and the
  // backend accepts it (LineupService validateGkCount permits 0 or 1). A SECOND keeper is not
  // accepted there, so it is still blocked here rather than surfaced as a 422.
  const counts = countByPosition(selected, players)
  if (counts.GK > 1) {
    return 'No máximo 1 goleiro na escalação'
  }
```

- [ ] **Step 5: Relax `firstFreeSlotFor`**

Replace its `return` with:

```tsx
    // Preference order: a slot that accepts the player's exact detailed position, then any free
    // slot on their own coarse line, then ANY free slot at all. The last fallback is what lets a
    // defender be pushed into a full-elsewhere XI rather than being bounced to the bench.
    return (exact ?? free.find(x => x.slot.line === player.position) ?? free[0])?.index ?? null
```

- [ ] **Step 6: Relax `SlotPickerDialog`**

Replace:

```tsx
  const isGkSlot = Boolean(slot?.accepted.includes('GOLEIRO'))
  const candidates = slot ? players.filter(p => isGkSlot || p.position !== 'GK') : []
```

with:

```tsx
  // No position filter: a goalkeeper may be listed for an outfield slot and vice versa. Both
  // land in `improvised` below and carry the warning chip, which informs without deciding.
  const candidates = slot ? players : []
```

and update the component's doc comment, replacing the sentence "GK-position players are excluded from an outfield slot's list (the backend allows at most 1 GK in the XI, so an improvised keeper outfield would always 422)" with "Every eligible player is offered for every slot".

- [ ] **Step 7: Relax `Pitch`**

In `dropTarget`, change:

```tsx
      if (i === fromSlot || i === 0) return acc
```

to:

```tsx
      if (i === fromSlot) return acc
```

and change:

```tsx
        const draggable = Boolean(onSwap) && !mirror && i !== 0 && player !== null
```

to:

```tsx
        // The GK slot is draggable and droppable like any other: improvising an outfielder in
        // goal is the manager's decision, not the UI's.
        const draggable = Boolean(onSwap) && !mirror && player !== null
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/ src/features/pitch/`
Expected: PASS.

- [ ] **Step 9: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/squad/ frontend/src/features/pitch/
git commit -m "feat(squad): stop blocking out-of-position selections"
```

---

## Task 6: `bulkTraining.ts` — the pure rule engine

Implements spec §8.1.

**Files:**
- Create: `frontend/src/features/squad/training/bulkTraining.ts`
- Create: `frontend/src/features/squad/training/bulkTraining.test.ts`

**Interfaces:**
- Consumes: `DetailedPosition`, `PlayerResponse`, `TrainableAttribute`, `TrainingIntensity` from `../../../api/types`.
- Produces, all consumed by Tasks 7 and 8:
  - `type TrainingAssignmentsState = Record<string, { focus: TrainableAttribute | null; intensity: TrainingIntensity }>`
  - `type BulkTrainingRule = { position: DetailedPosition; focus: TrainableAttribute | null; intensity: TrainingIntensity }`
  - `applyBulkRules(assignments: TrainingAssignmentsState, players: PlayerResponse[], rules: BulkTrainingRule[]): TrainingAssignmentsState`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/features/squad/training/bulkTraining.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { applyBulkRules, type BulkTrainingRule, type TrainingAssignmentsState } from './bulkTraining'
import type { PlayerResponse } from '../../../api/types'

// Only id, detailedPosition and altPositions matter here; the rest is cast in.
const player = (
  id: string,
  detailedPosition: PlayerResponse['detailedPosition'],
  altPositions: PlayerResponse['altPositions'] = null,
): PlayerResponse => ({ id, detailedPosition, altPositions } as PlayerResponse)

const PLAYERS = [
  player('gk1', 'GOLEIRO'),
  player('gk2', 'GOLEIRO'),
  player('cb1', 'ZAGUEIRO'),
  player('lb1', 'LATERAL_ESQUERDO', ['ZAGUEIRO']),
  player('legacy', null),
]

const BASE: TrainingAssignmentsState = {
  gk1: { focus: 'PASSE', intensity: 'LEVE' },
  gk2: { focus: null, intensity: 'NORMAL' },
  cb1: { focus: 'DRIBLE', intensity: 'INTENSA' },
  lb1: { focus: 'CRUZAMENTO', intensity: 'NORMAL' },
  legacy: { focus: 'FORCA', intensity: 'LEVE' },
}

const gkRule: BulkTrainingRule = { position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'INTENSA' }

describe('applyBulkRules', () => {
  it('applies a rule to every player whose main position matches', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.gk1).toEqual({ focus: 'REFLEXOS', intensity: 'INTENSA' })
    expect(next.gk2).toEqual({ focus: 'REFLEXOS', intensity: 'INTENSA' })
  })

  it('leaves players matched by no rule untouched', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.cb1).toEqual({ focus: 'DRIBLE', intensity: 'INTENSA' })
  })

  it('ignores alternate positions — main position only', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      { position: 'ZAGUEIRO', focus: 'MARCACAO', intensity: 'NORMAL' },
    ])
    expect(next.cb1).toEqual({ focus: 'MARCACAO', intensity: 'NORMAL' })
    // lb1 lists ZAGUEIRO as an ALTERNATE, so the rule must not reach them.
    expect(next.lb1).toEqual({ focus: 'CRUZAMENTO', intensity: 'NORMAL' })
  })

  it('never touches a player without a detailed position', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.legacy).toEqual({ focus: 'FORCA', intensity: 'LEVE' })
  })

  it('treats a null focus as a deliberate clear, not a skip', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      { position: 'GOLEIRO', focus: null, intensity: 'LEVE' },
    ])
    expect(next.gk1).toEqual({ focus: null, intensity: 'LEVE' })
  })

  it('applies several rules in one pass', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      gkRule,
      { position: 'ZAGUEIRO', focus: 'DESARME', intensity: 'LEVE' },
    ])
    expect(next.gk1.focus).toBe('REFLEXOS')
    expect(next.cb1).toEqual({ focus: 'DESARME', intensity: 'LEVE' })
  })

  it('does not mutate the input record', () => {
    applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(BASE.gk1).toEqual({ focus: 'PASSE', intensity: 'LEVE' })
  })

  it('returns the assignments unchanged when there are no rules', () => {
    expect(applyBulkRules(BASE, PLAYERS, [])).toEqual(BASE)
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/training/bulkTraining.test.ts`
Expected: FAIL — `Failed to resolve import "./bulkTraining"`.

- [ ] **Step 3: Write the module**

Create `frontend/src/features/squad/training/bulkTraining.ts`:

```ts
import type { DetailedPosition, PlayerResponse, TrainableAttribute, TrainingIntensity } from '../../../api/types'

/** The Treino tab's working state: what each player is currently set to train, keyed by playerId. */
export type TrainingAssignmentsState = Record<
  string,
  { focus: TrainableAttribute | null; intensity: TrainingIntensity }
>

/** One "everyone in this position trains X at intensity Y" rule from the bulk card. */
export type BulkTrainingRule = {
  position: DetailedPosition
  focus: TrainableAttribute | null
  intensity: TrainingIntensity
}

/**
 * Applies the bulk card's rules to the working assignments, returning a NEW record.
 *
 * Matching is on the player's MAIN position (`detailedPosition`) only — `altPositions` are
 * deliberately ignored, because "all my centre-backs" should not sweep up a full-back who
 * merely can cover there. A player with no detailed position (legacy payload) matches nothing
 * and keeps their current assignment: rules are keyed on detailed positions, and the coarse
 * fallback the table shows in its Pos column is not one of them.
 *
 * A rule with `focus: null` CLEARS that position's training focus. That is an assignment, not
 * a no-op — "these players train nothing in particular" is a real instruction.
 */
export function applyBulkRules(
  assignments: TrainingAssignmentsState,
  players: PlayerResponse[],
  rules: BulkTrainingRule[],
): TrainingAssignmentsState {
  const byPosition = new Map(rules.map(rule => [rule.position, rule]))
  const next: TrainingAssignmentsState = { ...assignments }
  for (const player of players) {
    if (!player.detailedPosition) continue
    const rule = byPosition.get(player.detailedPosition)
    if (!rule) continue
    next[player.id] = { focus: rule.focus, intensity: rule.intensity }
  }
  return next
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/training/bulkTraining.test.ts`
Expected: PASS, 8 tests.

- [ ] **Step 5: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/squad/training/
git commit -m "feat(training): pure per-position bulk assignment rules"
```

---

## Task 7: Extract the Treino tab into `TrainingTab`

Implements spec §8.3, and moves `positionLabel` to a shared home.

**Files:**
- Create: `frontend/src/features/squad/training/trainingLabels.ts`
- Create: `frontend/src/features/squad/training/TrainingTab.tsx`
- Create: `frontend/src/features/squad/training/TrainingTab.test.tsx`
- Modify: `frontend/src/features/players/positionLabels.ts` (add `COARSE_POS_SHORT` + `squadPositionLabel`)
- Modify: `frontend/src/features/squad/SquadPage.tsx` (delete the moved code, render `<TrainingTab>`)
- Modify: `frontend/src/features/squad/SquadPage.test.tsx` (move the training cases out)

**Interfaces:**
- Consumes: `TrainingAssignmentsState` from `./bulkTraining` (Task 6).
- Produces, consumed by Task 8:
  - `TRAINABLE_ATTRIBUTE_LABEL: Record<TrainableAttribute, string>` and `INTENSITY_LABEL: Record<TrainingIntensity, string>` from `./trainingLabels`.
  - `TrainingTab` with props
    `{ players: PlayerResponse[]; saveGameId: string; clubId: string; assignments: TrainingAssignmentsState; onChange: (next: TrainingAssignmentsState) => void; playerNameButton: (player: PlayerResponse) => ReactNode }`.
  - `squadPositionLabel(player: PlayerResponse): string` from `../../players/positionLabels`.

**Important:** `trainingAssignments` state and its hydration effect **stay in `SquadPage`**. The Treino panel unmounts on tab-away (deliberately — see the comment at `SquadPage.tsx:1005-1011`), so state owned by `TrainingTab` would lose unsaved edits on every tab switch.

- [ ] **Step 1: Move the position label helper**

Cut `COARSE_POS_SHORT` and `positionLabel` out of `SquadPage.tsx` and add them to `frontend/src/features/players/positionLabels.ts`:

```ts
/** Coarse fallbacks for a player with no detailed position (legacy payloads). */
export const COARSE_POS_SHORT: Record<PlayerResponse['position'], string> = {
  GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA',
}

/** Short position abbr for a squad-table cell: detailed when present, else coarse. */
export function squadPositionLabel(player: PlayerResponse): string {
  return positionShort(player, COARSE_POS_SHORT)
}
```

Add `PlayerResponse` to that file's type imports if it is not already there. In `SquadPage.tsx`, import `squadPositionLabel` and replace every `positionLabel(` call with `squadPositionLabel(` — including the one inside `MobileSquadRow`.

Run: `cd frontend && npx tsc -b && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: no type errors, tests still pass. Commit this move on its own:

```bash
git add frontend/src/features/players/positionLabels.ts frontend/src/features/squad/SquadPage.tsx
git commit -m "refactor(squad): move squadPositionLabel into positionLabels"
```

- [ ] **Step 2: Create the labels module**

Create `frontend/src/features/squad/training/trainingLabels.ts` by cutting the two maps verbatim out of `SquadPage.tsx`:

```ts
import type { TrainableAttribute, TrainingIntensity } from '../../../api/types'

export const TRAINABLE_ATTRIBUTE_LABEL: Record<TrainableAttribute, string> = {
  VELOCIDADE: 'Velocidade',
  FINALIZACAO: 'Finalização',
  PASSE: 'Passe',
  DESARME: 'Desarme',
  REFLEXOS: 'Reflexos',
  FOLEGO: 'Fôlego',
  CABECEIO: 'Cabeceio',
  CRUZAMENTO: 'Cruzamento',
  MARCACAO: 'Marcação',
  DRIBLE: 'Drible',
  VISAO: 'Visão de jogo',
  FORCA: 'Força',
}

export const INTENSITY_LABEL: Record<TrainingIntensity, string> = {
  LEVE: 'Leve',
  NORMAL: 'Normal',
  INTENSA: 'Intensa',
}
```

- [ ] **Step 3: Write the failing `TrainingTab` test**

Create `frontend/src/features/squad/training/TrainingTab.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TrainingTab } from './TrainingTab'
import { AuthProvider } from '../../../auth/AuthContext'
import type { PlayerResponse, SquadResponse } from '../../../api/types'
import type { TrainingAssignmentsState } from './bulkTraining'

vi.mock('../../../api/career', () => ({ setTraining: vi.fn() }))
import { setTraining } from '../../../api/career'
const mockSetTraining = vi.mocked(setTraining)

const player = (id: string, name: string, detailedPosition: PlayerResponse['detailedPosition'],
                position: PlayerResponse['position']): PlayerResponse =>
  ({ id, name, position, detailedPosition, altPositions: null, overall: 70 } as PlayerResponse)

const PLAYERS = [
  player('gk1', 'Alisson', 'GOLEIRO', 'GK'),
  player('gk2', 'Airton', 'GOLEIRO', 'GK'),
  player('cb1', 'Rodrigo', 'ZAGUEIRO', 'DEF'),
]

const ASSIGNMENTS: TrainingAssignmentsState = {
  gk1: { focus: null, intensity: 'NORMAL' },
  gk2: { focus: null, intensity: 'NORMAL' },
  cb1: { focus: null, intensity: 'NORMAL' },
}

function renderTab(onChange = vi.fn(), assignments = ASSIGNMENTS) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const utils = render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TrainingTab
            players={PLAYERS}
            saveGameId="sg-1"
            clubId="club-1"
            assignments={assignments}
            onChange={onChange}
            playerNameButton={p => <span>{p.name}</span>}
          />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
  return { ...utils, onChange }
}

beforeEach(() => { vi.clearAllMocks() })

describe('TrainingTab', () => {
  it('renders one row per player with a focus and an intensity control', () => {
    renderTab()
    expect(screen.getByRole('combobox', { name: 'Foco de treino de Alisson' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Intensidade de treino de Rodrigo' })).toBeInTheDocument()
  })

  it('reports a per-player focus change', () => {
    const { onChange } = renderTab()
    fireEvent.change(screen.getByRole('combobox', { name: 'Foco de treino de Alisson' }), {
      target: { value: 'REFLEXOS' },
    })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      gk1: { focus: 'REFLEXOS', intensity: 'NORMAL' },
    }))
  })

  it('saves every player on Salvar treino', async () => {
    mockSetTraining.mockResolvedValue({ players: [] } as unknown as SquadResponse)
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: 'Salvar treino' }))
    await waitFor(() => expect(mockSetTraining).toHaveBeenCalledWith(
      'sg-1', 'club-1',
      { assignments: [
        { playerId: 'gk1', focus: null, intensity: 'NORMAL' },
        { playerId: 'gk2', focus: null, intensity: 'NORMAL' },
        { playerId: 'cb1', focus: null, intensity: 'NORMAL' },
      ] },
      expect.anything(),
    ))
  })
})
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/training/TrainingTab.test.tsx`
Expected: FAIL — `Failed to resolve import "./TrainingTab"`.

- [ ] **Step 5: Write `TrainingTab`**

Create `frontend/src/features/squad/training/TrainingTab.tsx`:

```tsx
import { useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, NativeSelect, Paper, Snackbar,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
} from '@mui/material'
import { setTraining } from '../../../api/career'
import { useAuth } from '../../../auth/AuthContext'
import { squadPositionLabel } from '../../players/positionLabels'
import type { PlayerResponse, TrainableAttribute, TrainingAssignment, TrainingIntensity } from '../../../api/types'
import { TRAINABLE_ATTRIBUTE_LABEL, INTENSITY_LABEL } from './trainingLabels'
import type { TrainingAssignmentsState } from './bulkTraining'

/**
 * The Treino panel: a per-player focus/intensity table plus its save button.
 *
 * The assignments state itself lives in SquadPage, not here: this panel is mounted only while
 * its tab is active, so state owned here would lose unsaved edits on every tab switch.
 */
export function TrainingTab({ players, saveGameId, clubId, assignments, onChange, playerNameButton }: {
  /** Already display-ordered (GK → DEF → MEI → ATA). */
  players: PlayerResponse[]
  saveGameId: string
  clubId: string
  assignments: TrainingAssignmentsState
  onChange: (next: TrainingAssignmentsState) => void
  playerNameButton: (player: PlayerResponse) => ReactNode
}) {
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [saveSuccess, setSaveSuccess] = useState(false)

  // The state to send is passed as the mutation variable rather than read from `assignments`,
  // so a save fired in the same tick as an optimistic change sends the NEW values.
  const mutation = useMutation({
    mutationFn: (state: TrainingAssignmentsState) => {
      const list: TrainingAssignment[] = players.map(p => ({
        playerId: p.id,
        focus: state[p.id]?.focus ?? null,
        intensity: state[p.id]?.intensity ?? 'NORMAL',
      }))
      return setTraining(saveGameId, clubId, { assignments: list }, token ?? '')
    },
    onSuccess: (data) => {
      setSaveSuccess(true)
      // Write-through: setTraining already returns a SquadResponse — the exact shape of the
      // squad query — so patch the cache directly instead of paying for a refetch.
      queryClient.setQueryData(['squad', saveGameId, clubId], data)
    },
  })

  function changeFocus(player: PlayerResponse, focus: TrainableAttribute | ''): void {
    onChange({
      ...assignments,
      [player.id]: {
        focus: focus === '' ? null : focus,
        intensity: assignments[player.id]?.intensity ?? 'NORMAL',
      },
    })
  }

  function changeIntensity(player: PlayerResponse, intensity: TrainingIntensity): void {
    onChange({
      ...assignments,
      [player.id]: { focus: assignments[player.id]?.focus ?? null, intensity },
    })
  }

  return (
    <>
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
            {players.map(player => (
              <TableRow key={player.id}>
                <TableCell>{playerNameButton(player)}</TableCell>
                <TableCell align="center">{squadPositionLabel(player)}</TableCell>
                <TableCell align="center">
                  <NativeSelect
                    value={assignments[player.id]?.focus ?? ''}
                    onChange={e => changeFocus(player, e.target.value as TrainableAttribute | '')}
                    inputProps={{ 'aria-label': `Foco de treino de ${player.name}` }}
                    sx={{ fontSize: '0.85rem' }}
                  >
                    <option value="">Nenhum</option>
                    {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
                      <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
                    ))}
                  </NativeSelect>
                </TableCell>
                <TableCell align="center">
                  <NativeSelect
                    value={assignments[player.id]?.intensity ?? 'NORMAL'}
                    onChange={e => changeIntensity(player, e.target.value as TrainingIntensity)}
                    inputProps={{ 'aria-label': `Intensidade de treino de ${player.name}` }}
                    sx={{ fontSize: '0.85rem' }}
                  >
                    {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
                      <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
                    ))}
                  </NativeSelect>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Button color="secondary" disabled={mutation.isPending} onClick={() => mutation.mutate(assignments)}>
        {mutation.isPending ? 'Salvando…' : 'Salvar treino'}
      </Button>

      <Snackbar
        open={saveSuccess || mutation.isError}
        autoHideDuration={4000}
        onClose={() => { setSaveSuccess(false); mutation.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={mutation.isError ? 'error' : 'success'}
          variant="filled"
          onClose={() => { setSaveSuccess(false); mutation.reset() }}
          sx={{ width: '100%' }}
        >
          {mutation.isError ? 'Erro ao salvar treino.' : 'Treino salvo com sucesso!'}
        </Alert>
      </Snackbar>
    </>
  )
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/training/TrainingTab.test.tsx`
Expected: PASS, 3 tests.

- [ ] **Step 7: Delete the moved code from `SquadPage`**

Remove from `SquadPage.tsx`:
- `TRAINABLE_ATTRIBUTE_LABEL` and `INTENSITY_LABEL` (now in `trainingLabels.ts`)
- `changeTrainingFocus`, `changeTrainingIntensity`, `trainingFocusSelect`, `trainingIntensitySelect`
- `trainingMutation`, `trainingSaveSuccess` state, and the training `Snackbar`
- the `setTraining` import, and `TrainableAttribute` / `TrainingIntensity` / `TrainingAssignment` from the type import if now unused

Keep `trainingAssignments`, `setTrainingAssignments` and the `trainingHydrated` effect, but retype the state with the shared type:

```tsx
import type { TrainingAssignmentsState } from './training/bulkTraining'
import { TrainingTab } from './training/TrainingTab'
...
  const [trainingAssignments, setTrainingAssignments] = useState<TrainingAssignmentsState>({})
```

Replace the whole Treino panel body with:

```tsx
      <Box role="tabpanel" hidden={tab !== 2} id="squad-tabpanel-2" aria-labelledby="squad-tab-2">
        {/* Unlike the Plantel/Táticas panels above, this content is only mounted while the tab
            is active rather than kept mounted-but-hidden: `trainingAssignments` lives in THIS
            component's state (TrainingTab only reads it), so nothing is lost by unmounting on
            tab-away. Mounting it unconditionally would render every player's name a second time
            into the DOM at all times, breaking every other test that waits on a player's name
            via `getByText`. */}
        {tab === 2 && (
          <TrainingTab
            players={displayPlayers}
            saveGameId={saveGameId}
            clubId={clubId}
            assignments={trainingAssignments}
            onChange={setTrainingAssignments}
            playerNameButton={playerNameButton}
          />
        )}
      </Box>
```

- [ ] **Step 8: Move the training tests out of `SquadPage.test.tsx`**

The cases at roughly `SquadPage.test.tsx:302`, `:331` and `:429` exercise the Treino tab. Any that only test the table/save now belong in `TrainingTab.test.tsx` — move them there, adapting to `TrainingTab`'s props. Keep in `SquadPage.test.tsx` only what proves the wiring: that clicking the Treino tab renders the training controls seeded from the squad payload.

- [ ] **Step 9: Run the full squad suite**

Run: `cd frontend && npx vitest run src/features/squad/`
Expected: PASS.

- [ ] **Step 10: Type check and commit**

Run: `cd frontend && npx tsc -b`
Expected: no output.

```bash
git add frontend/src/features/squad/
git commit -m "refactor(training): extract the Treino tab into its own module"
```

---

## Task 8: `TrainingBulkCard` — set focus and intensity by position

Implements spec §8.2 and §8.4.

**Files:**
- Create: `frontend/src/features/squad/training/TrainingBulkCard.tsx`
- Create: `frontend/src/features/squad/training/TrainingBulkCard.test.tsx`
- Modify: `frontend/src/features/squad/training/TrainingTab.tsx`
- Test: `frontend/src/features/squad/training/TrainingTab.test.tsx`

**Interfaces:**
- Consumes: `BulkTrainingRule`, `applyBulkRules` (Task 6); `TRAINABLE_ATTRIBUTE_LABEL`, `INTENSITY_LABEL` (Task 7); `DETAILED_POSITION_SHORT` from `../../players/positionLabels`.
- Produces: `TrainingBulkCard` with props
  `{ rules: BulkTrainingRule[]; onRulesChange: (next: BulkTrainingRule[]) => void; onApply: () => void }`.

**Accessible names** (tests depend on them exactly): `Posição da regra N`, `Foco da regra N`, `Intensidade da regra N` (N is 1-based), `Adicionar posição`, `Remover regra N`, the expand toggle `Treino por posição`, and the save button `Salvar`.

- [ ] **Step 1: Write the failing card test**

Create `frontend/src/features/squad/training/TrainingBulkCard.test.tsx`:

```tsx
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { TrainingBulkCard } from './TrainingBulkCard'
import type { BulkTrainingRule } from './bulkTraining'

function renderCard(rules: BulkTrainingRule[] = []) {
  const onRulesChange = vi.fn()
  const onApply = vi.fn()
  render(<TrainingBulkCard rules={rules} onRulesChange={onRulesChange} onApply={onApply} />)
  return { onRulesChange, onApply }
}

function expand() {
  fireEvent.click(screen.getByRole('button', { name: 'Treino por posição' }))
}

describe('TrainingBulkCard', () => {
  it('starts collapsed', () => {
    renderCard()
    // The Collapse uses unmountOnExit, so a collapsed card has no controls in the DOM at all.
    expect(screen.queryByRole('button', { name: 'Adicionar posição' })).toBeNull()
  })

  it('adds a rule defaulted to the first unused position', () => {
    const { onRulesChange } = renderCard()
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Adicionar posição' }))
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
    ])
  })

  it('offers only unused positions to a second rule', () => {
    renderCard([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
    expand()
    const second = screen.getByRole('combobox', { name: 'Posição da regra 2' })
    const options = Array.from(second.querySelectorAll('option')).map(o => o.getAttribute('value'))
    // Its own position stays selectable; the other rule's does not.
    expect(options).toContain('ZAGUEIRO')
    expect(options).not.toContain('GOLEIRO')
  })

  it('edits a rule in place', () => {
    const { onRulesChange } = renderCard([{ position: 'GOLEIRO', focus: null, intensity: 'NORMAL' }])
    expand()
    fireEvent.change(screen.getByRole('combobox', { name: 'Foco da regra 1' }), {
      target: { value: 'REFLEXOS' },
    })
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'NORMAL' },
    ])
  })

  it('removes a rule', () => {
    const { onRulesChange } = renderCard([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Remover regra 1' }))
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
  })

  it('disables Salvar with no rules and enables it with one', () => {
    const { unmount } = render(
      <TrainingBulkCard rules={[]} onRulesChange={vi.fn()} onApply={vi.fn()} />,
    )
    expand()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled()
    unmount()
    renderCard([{ position: 'GOLEIRO', focus: null, intensity: 'NORMAL' }])
    expand()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeEnabled()
  })

  it('calls onApply when Salvar is clicked', () => {
    const { onApply } = renderCard([{ position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'INTENSA' }])
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(onApply).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/training/TrainingBulkCard.test.tsx`
Expected: FAIL — `Failed to resolve import "./TrainingBulkCard"`.

- [ ] **Step 3: Write the card**

Create `frontend/src/features/squad/training/TrainingBulkCard.tsx`:

```tsx
import { useState } from 'react'
import {
  Box, Button, Card, CardContent, Collapse, IconButton, NativeSelect, Stack, Typography,
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import CloseIcon from '@mui/icons-material/Close'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import { DETAILED_POSITION_SHORT } from '../../players/positionLabels'
import type { DetailedPosition, TrainableAttribute, TrainingIntensity } from '../../../api/types'
import { TRAINABLE_ATTRIBUTE_LABEL, INTENSITY_LABEL } from './trainingLabels'
import type { BulkTrainingRule } from './bulkTraining'

const ALL_POSITIONS = Object.keys(DETAILED_POSITION_SHORT) as DetailedPosition[]

/**
 * Sets training focus and intensity for a whole position at once — 26 dropdowns of busywork
 * collapsed into one rule per position.
 *
 * Rules are the caller's state and are deliberately NOT persisted: the card is a one-shot tool
 * whose OUTPUT (each player's own assignment) is what gets saved.
 */
export function TrainingBulkCard({ rules, onRulesChange, onApply }: {
  rules: BulkTrainingRule[]
  onRulesChange: (next: BulkTrainingRule[]) => void
  /** Apply the rules to the squad and save. The caller owns the optimistic update. */
  onApply: () => void
}) {
  const [open, setOpen] = useState(false)

  const used = new Set(rules.map(r => r.position))
  const firstUnused = ALL_POSITIONS.find(p => !used.has(p))

  function addRule() {
    if (!firstUnused) return
    onRulesChange([...rules, { position: firstUnused, focus: null, intensity: 'NORMAL' }])
  }

  function updateRule(index: number, patch: Partial<BulkTrainingRule>) {
    onRulesChange(rules.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  }

  function removeRule(index: number) {
    onRulesChange(rules.filter((_, i) => i !== index))
  }

  return (
    <Card variant="outlined" sx={{ mb: 2, borderRadius: 2 }}>
      <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography sx={{ fontWeight: 700 }}>Treino por posição</Typography>
          <IconButton size="small" onClick={() => setOpen(o => !o)} aria-label="Treino por posição">
            {open ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
          </IconButton>
        </Stack>

        {/* unmountOnExit keeps a collapsed card out of the DOM entirely — the rules themselves
            are the caller's state, so nothing is lost by unmounting the controls. */}
        <Collapse in={open} timeout="auto" unmountOnExit>
          <Stack spacing={1.5} sx={{ mt: 1.5 }}>
            {rules.map((rule, index) => (
              <Stack key={rule.position} direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <NativeSelect
                  value={rule.position}
                  onChange={e => updateRule(index, { position: e.target.value as DetailedPosition })}
                  inputProps={{ 'aria-label': `Posição da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 90 }}
                >
                  {/* Its own position stays selectable; positions claimed by OTHER rules do not,
                      which is what keeps the "one rule per position" invariant. */}
                  {ALL_POSITIONS.filter(p => p === rule.position || !used.has(p)).map(p => (
                    <option key={p} value={p}>{DETAILED_POSITION_SHORT[p]}</option>
                  ))}
                </NativeSelect>
                <NativeSelect
                  value={rule.focus ?? ''}
                  onChange={e => updateRule(index, {
                    focus: e.target.value === '' ? null : (e.target.value as TrainableAttribute),
                  })}
                  inputProps={{ 'aria-label': `Foco da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 140 }}
                >
                  <option value="">Nenhum</option>
                  {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
                    <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
                  ))}
                </NativeSelect>
                <NativeSelect
                  value={rule.intensity}
                  onChange={e => updateRule(index, { intensity: e.target.value as TrainingIntensity })}
                  inputProps={{ 'aria-label': `Intensidade da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 110 }}
                >
                  {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
                    <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
                  ))}
                </NativeSelect>
                <IconButton size="small" onClick={() => removeRule(index)} aria-label={`Remover regra ${index + 1}`}>
                  <CloseIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}

            <Box>
              <IconButton size="small" onClick={addRule} disabled={!firstUnused} aria-label="Adicionar posição">
                <AddIcon fontSize="small" />
              </IconButton>
            </Box>

            <Box>
              <Button size="small" color="secondary" variant="contained" disabled={rules.length === 0} onClick={onApply}>
                Salvar
              </Button>
            </Box>
          </Stack>
        </Collapse>
      </CardContent>
    </Card>
  )
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/training/TrainingBulkCard.test.tsx`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the failing wiring tests**

Append to `describe('TrainingTab', ...)` in `frontend/src/features/squad/training/TrainingTab.test.tsx`:

```tsx
it('applies a bulk rule to the table and saves, optimistically', async () => {
  mockSetTraining.mockResolvedValue({ players: [] } as unknown as SquadResponse)
  const { onChange } = renderTab()
  fireEvent.click(screen.getByRole('button', { name: 'Treino por posição' }))
  fireEvent.click(screen.getByRole('button', { name: 'Adicionar posição' }))
  // The card's rules are TrainingTab's own state, so the new row is on screen already.
  fireEvent.change(screen.getByRole('combobox', { name: 'Foco da regra 1' }), {
    target: { value: 'REFLEXOS' },
  })
  fireEvent.change(screen.getByRole('combobox', { name: 'Intensidade da regra 1' }), {
    target: { value: 'INTENSA' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
  // Both goalkeepers get the rule; the centre-back does not.
  expect(onChange).toHaveBeenCalledWith({
    gk1: { focus: 'REFLEXOS', intensity: 'INTENSA' },
    gk2: { focus: 'REFLEXOS', intensity: 'INTENSA' },
    cb1: { focus: null, intensity: 'NORMAL' },
  })
  await waitFor(() => expect(mockSetTraining).toHaveBeenCalledWith(
    'sg-1', 'club-1',
    { assignments: [
      { playerId: 'gk1', focus: 'REFLEXOS', intensity: 'INTENSA' },
      { playerId: 'gk2', focus: 'REFLEXOS', intensity: 'INTENSA' },
      { playerId: 'cb1', focus: null, intensity: 'NORMAL' },
    ] },
    expect.anything(),
  ))
})

it('rolls the table back when the bulk save fails', async () => {
  mockSetTraining.mockRejectedValue(new Error('boom'))
  const { onChange } = renderTab()
  fireEvent.click(screen.getByRole('button', { name: 'Treino por posição' }))
  fireEvent.click(screen.getByRole('button', { name: 'Adicionar posição' }))
  fireEvent.change(screen.getByRole('combobox', { name: 'Foco da regra 1' }), {
    target: { value: 'REFLEXOS' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
  await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(ASSIGNMENTS))
  expect(await screen.findByText('Erro ao salvar treino.')).toBeInTheDocument()
})
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/squad/training/TrainingTab.test.tsx`
Expected: FAIL — no `Treino por posição` button in `TrainingTab`.

- [ ] **Step 7: Wire the card into `TrainingTab`**

Add to `TrainingTab.tsx`'s imports:

```tsx
import { useRef } from 'react'
import { TrainingBulkCard } from './TrainingBulkCard'
import { applyBulkRules, type BulkTrainingRule, type TrainingAssignmentsState } from './bulkTraining'
```

(merge `useRef` into the existing `react` import, and `BulkTrainingRule` into the existing `bulkTraining` type import).

Add state and the rollback ref beside `saveSuccess`:

```tsx
  // Bulk rules are one-shot page state: configure, apply, done. Nothing persists them.
  const [rules, setRules] = useState<BulkTrainingRule[]>([])
  // The assignments to restore if an OPTIMISTIC (bulk) save fails. Null for a plain save, which
  // changes nothing up front and therefore has nothing to undo.
  const rollback = useRef<TrainingAssignmentsState | null>(null)
```

Add `onError` to the mutation:

```tsx
    onError: () => {
      if (rollback.current) {
        onChange(rollback.current)
        rollback.current = null
      }
    },
```

Add the apply handler:

```tsx
  // Optimistic: the table updates before the round-trip, and `onError` puts it back.
  function applyRules() {
    const next = applyBulkRules(assignments, players, rules)
    rollback.current = assignments
    onChange(next)
    mutation.mutate(next)
  }
```

Change the plain save button's handler so it never triggers a rollback:

```tsx
      <Button
        color="secondary"
        disabled={mutation.isPending}
        onClick={() => { rollback.current = null; mutation.mutate(assignments) }}
      >
```

Render the card as the panel's first child, above the `TableContainer`:

```tsx
      <TrainingBulkCard rules={rules} onRulesChange={setRules} onApply={applyRules} />
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/squad/`
Expected: PASS.

- [ ] **Step 9: Type check, full frontend suite, commit**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: no type errors; the whole suite green.

```bash
git add frontend/src/features/squad/training/
git commit -m "feat(training): set focus and intensity for a whole position at once"
```

---

## Task 9: Lower the card rate

Implements spec §9.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:44`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java:28`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/MatchSimulationServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

**Both constants must move together** — the instant path and the live path are calibrated against each other, so a divergence would make a watched match statistically different from a simulated one.

- [ ] **Step 1: Write the failing calibration test**

Add to `backend/src/test/java/br/com/carvoeiromanager/match/MatchSimulationServiceTest.java`, inside the existing test class (it already has a `countCards` helper and a neutral `TeamMatchInput` shape to copy):

```java
    @Test
    void cardsPerMatchSitAroundTwoAndAHalf() {
        // Calibration guard (spec 2026-08-13 §9): ~1.35 cards per side per 90 at aggression 1.0,
        // so ~2.7 per match. Asserted as a band over 200 seeds, wide enough to absorb RNG noise
        // but tight enough that a changed BASE_CARD_RATE trips it.
        TeamMatchInput neutral = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15,
                Mentality.BALANCED, GameStyle.EQUILIBRADO);
        long homeCards = countCards(neutral);
        double perSidePerMatch = homeCards / 200.0;
        assertThat(perSidePerMatch).isBetween(1.15, 1.55);
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*MatchSimulationServiceTest*' > /tmp/cards.log 2>&1; echo "EXIT=$?"
```

Expected: `EXIT=1`, and the log reports the actual around **1.8** per side — above the 1.55 ceiling, because the rate is still 0.02.

Capture the exit code directly like this, never after a pipe: `... | tail > file; echo $?` records `tail`'s status and reports a failed build as success.

- [ ] **Step 3: Lower both constants**

In `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java`:

```java
    // ~1.35 cards per side per 90 at aggression 1.0. Lowered from 0.02 (spec 2026-08-13 §9):
    // the old rate produced a card storm. MUST stay in lockstep with LiveMatchEngine's copy —
    // the instant and live paths are calibrated against each other.
    private static final double BASE_CARD_RATE = 0.015;
```

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`:

```java
    // ~1.35 cards per side per 90 at aggression 1.0. Lowered from 0.02 (spec 2026-08-13 §9).
    // MUST stay in lockstep with MatchSimulationService's copy.
    private static final double BASE_CARD_RATE = 0.015;
```

`RED_SHARE` stays at `0.06` in both files. Do not touch `BASE_FOUL_RATE`.

- [ ] **Step 4: Run the match tests to verify they pass**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*Match*' --tests '*LiveMatch*' --tests '*FixtureSimulator*' > /tmp/cards.log 2>&1; echo "EXIT=$?"
```

Expected: `EXIT=0`. Fixed-seed tests elsewhere may now see different events — if one fails, it is asserting a specific simulated outcome and its expected values need updating to the new draw sequence. Do **not** loosen an assertion to make it pass; recompute the expected value.

- [ ] **Step 5: Run the whole backend suite**

Integration tests need Docker: if every IT fails at once with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate`, run `open -a OrbStack` and retry rather than debugging the code.

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test > /tmp/backend.log 2>&1; echo "EXIT=$?"
```

Expected: `EXIT=0`. `TransferServiceSellingIT` is a known flake (~26% from a correlated `Random` seed) — if it is the only failure, re-run it in isolation to confirm, then proceed.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/test/java/br/com/carvoeiromanager/match/
git commit -m "fix(match): lower the card rate by 25%"
```

---

## Final verification

- [ ] `cd frontend && npx tsc -b` — no output
- [ ] `cd frontend && npx vitest run` — whole suite green
- [ ] `cd frontend && npx oxlint` — clean
- [ ] `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test > /tmp/backend.log 2>&1; echo "EXIT=$?"` — `EXIT=0`
- [ ] Every spec section §4–§9 has a landed task
