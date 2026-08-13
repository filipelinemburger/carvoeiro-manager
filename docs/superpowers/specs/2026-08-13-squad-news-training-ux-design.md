# Squad, News and Training UX Pass — Design Spec

**Date:** 2026-08-13
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the news inbox (`2026-07-21-news-inbox-design.md`), the pitch drag-and-drop slice
(`2026-07-24-pitch-drag-drop-design.md`), multi-position players
(`2026-07-24-multi-position-players-design.md`) and the training system
(`2026-07-14-training-system-design.md`).

---

## 1. Why this exists

Eight independent papercuts, all found by actually playing the game. Seven are frontend
ergonomics — the news inbox makes you read backwards, the squad screen buries its save button,
per-player tactical controls are stranded in a table that scrolls sideways, the training screen
makes you set 26 dropdowns one at a time, and the lineup screen refuses perfectly legal
selections. The eighth is a match-engine calibration nit: cards are too frequent.

They are grouped into one slice because six of the seven frontend items land in the same two
files (`SquadPage.tsx`, `NewsPage.tsx`), and doing them separately would mean touching those
files six times.

## 2. Current state

- **`SquadPage.tsx` is 1189 lines** and holds all three tabs (Plantel, Táticas de jogo, Treino),
  every piece of lineup state, and ~15 per-player render helpers that close over it.
- **`NewsPage.tsx`** auto-selects the *newest* item on landing and whenever a strictly-newer item
  arrives (`NewsPage.tsx:106-121`), marking it read. There is no way to walk the feed forward.
- **The Plantel tab** renders `Salvar escalação` at the bottom, below a 26-row table, and carries
  `Função` and `Foco` columns that only populate for starters.
- **The `Elenco: 😐 Neutro` chip** sits in the Plantel helper block (`SquadPage.tsx:864-868`),
  next to formation counters it has nothing to do with.
- **The Treino tab** is a flat table: one `Foco` and one `Intensidade` dropdown per player, no
  bulk operation.
- **Four frontend guards block out-of-position selection** even though the backend accepts it —
  enumerated in §6.
- **Card rate is `BASE_CARD_RATE = 0.02`** per minute per side, duplicated in
  `LiveMatchEngine.java:44` and `MatchSimulationService.java:28`. At aggression 1.0 that is
  ~1.8 cards per team per 90, ~3.6 per match, before the aggression multiplier
  (`TeamStrengthCalculator.java:102`) and before second-yellow reds.

## 3. Structural decision

The Treino tab is extracted into `frontend/src/features/squad/training/`, taking the new bulk
feature with it. Everything else stays in `SquadPage.tsx`.

Rejected alternatives:

- *Extract only the new components.* Leaves the whole bulk feature wired into an already-1189-line
  file.
- *Full three-way tab split behind a `useLineup` hook.* Lineup state is threaded through ~30
  closures; lifting it is a large refactor unrelated to this ask.

The Treino tab is the one clean seam: it reads `displayPlayers` and owns `trainingAssignments`,
with no coupling to lineup state. Net effect on `SquadPage.tsx`: about −110 lines from the
extraction, about −40 from the removed Função/Foco columns, plus ~30 for the new context menu.

## 4. Notícias — oldest-unread-first and "Ler próxima"

**File:** `frontend/src/features/news/NewsPage.tsx`.

### 4.1 Selection on landing

The auto-select effect selects the **oldest unread item among loaded pages**, falling back to the
newest item when nothing loaded is unread. Selecting still marks read through the existing
optimistic `markReadMutation`.

The current effect re-selects whenever a strictly-newer item arrives (so *Avançar* jumps you to
the fresh result). That trigger is kept, but what it selects changes: after a round that produced
three news items, you land on the **first** of the batch rather than the last.

The existing `lastNewestId` ref keeps guarding against re-selection loops. Re-selection when the
current selection drops out of the list is unchanged.

### 4.2 The button

A button sits **between the news list (and its `Carregar mais`) and the `AdvanceEventCard`**,
labelled `Ler próxima (N)` where N is the number of loaded unread items.

Clicking it selects the next unread item **newer than the current selection**; if there is none,
it falls back to the oldest unread anywhere in the loaded list, so the button never dead-ends.
Selection marks the item read via the same optimistic mutation, so N decrements immediately.

The button is **disabled** when no loaded item is unread. It is not hidden — a stable layout
beats a jumping one.

### 4.3 Scope boundary

"Unread" means unread **among loaded pages**. The button does not auto-fetch older pages hunting
for a stale unread item deeper in the feed; `Carregar mais` remains the way to reach those. The
sidebar's `newsUnreadCount` badge may therefore exceed N; that is accepted and not surfaced.

## 5. Plantel and Táticas layout

**File:** `frontend/src/features/squad/SquadPage.tsx`.

- **Save button moves to the top.** The Plantel button row becomes
  `[Salvar escalação] [Selecionar melhores] [Limpar]`, in that order. The duplicate at the bottom
  of the Plantel panel is deleted. Disabled state and pending label (`Salvando…`) are unchanged.
- **The Táticas save button stays** where it is, centred under the pitch — it is the natural
  place to save after arranging the field, and the two buttons drive the same mutation.
- **The morale chip moves.** `<MoodChip morale={teamMorale(...)} prefix="Elenco: " />` is removed
  from `helperAndAlerts` and rendered **above the pitch** in the Táticas panel. The formation
  counters line and the validation `Alert` stay in Plantel.

## 6. Função and Foco from the pitch

### 6.1 `Pitch.tsx`

One new optional prop:

```ts
onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void
```

It fires from two gestures, only for slots that hold a player:

- **Right-click** — an `onContextMenu` handler that calls `preventDefault()` before firing.
- **Long-press** — a timer started on `pointerdown`, firing at **500ms** if the pointer has moved
  no more than the existing `CLICK_MOVE_THRESHOLD` (6px). The timer is cleared on `pointerup`,
  `pointercancel`, and on any move past the threshold.

When a long-press fires, the interaction is flagged as consumed so the subsequent `pointerup`
does **not** also call `onSlotClick` (which would pop the slot picker on top of the menu). Drag
resolution is otherwise untouched: a long-press that then moves past the threshold is still a
drag, because the timer has already been cleared.

### 6.2 `RoleContextMenu.tsx`

A new component in `features/squad/`. A MUI `Menu` with `anchorReference="anchorPosition"` at the
pointer coordinates, containing the player's name and the two existing selects — `funcaoSelect`
and `focoSelect`, passed in as render props since they close over `SquadPage` state. Their logic
is unchanged, including `positionForRole` (slot-derived) and the ★/★★ familiarity markers.

Closing on select is not required; the menu closes on outside click or Escape, so several changes
can be made in one open.

### 6.3 Table columns removed

The `Função` and `Foco` `TableCell`s are removed from the Plantel desktop table — both the header
and the body cells (`SquadPage.tsx:927-928`, `953-954`). They only ever populated for starters,
and the pitch menu now covers exactly that case.

The mobile path is unchanged: `PlayerDetails` keeps its `lineupControl` selects, which remain the
touch-friendly way to reach Função/Foco alongside the new long-press.

## 7. Stop blocking out-of-position picks

The backend already accepts an improvised XI — `validateGkCount` permits **0 or 1** goalkeeper
(`LineupService.java:109`), and `LineupService` can improvise a keeper from outfielders
(`LineupService.java:416-427`). Four frontend guards are stricter than that and come off:

| File | Today | After |
|---|---|---|
| `SquadPage.validate` | `counts.GK !== 1` rejects a keeper-less XI | `counts.GK > 1` → *"No máximo 1 goleiro na escalação"* |
| `SquadPage.firstFreeSlotFor` | falls back only to a free slot on the player's own coarse line, else the bench | falls back to **any** free slot before considering the bench |
| `SlotPickerDialog` | `players.filter(p => isGkSlot \|\| p.position !== 'GK')` hides keepers from outfield slots | no filter; keepers appear, sorted into `improvised` like anyone else |
| `Pitch.dropTarget` / `draggable` | slot 0 (GK) is excluded as a drop target and is never draggable | the GK slot behaves like every other slot |

The **max-one-goalkeeper rule is the only position rule that survives**, because the backend
would return 422 for a second keeper. It is enforced in `validate` with the message above.

Everything informational stays: the `Improvisado` chip in the picker, its natural-position
subtitle, and the amber out-of-position tint on the pitch token. They tell you the player is out
of position; they no longer decide for you.

No behaviour depends on the removed guards elsewhere: `positionForRole` already derives from the
assigned slot, so a keeper dragged to a midfield slot gets that slot's default archetype, and an
outfielder in goal gets the goalkeeper archetype. `autoPickBestXI` is unchanged — it still picks
natural-first, which is what "Selecionar melhores" should do.

## 8. Treino — bulk assignment by main position

New directory `frontend/src/features/squad/training/` with three files.

### 8.1 `bulkTraining.ts` — pure

```ts
export type BulkTrainingRule = {
  position: DetailedPosition
  focus: TrainableAttribute | null
  intensity: TrainingIntensity
}

export function applyBulkRules(
  assignments: Record<string, { focus: TrainableAttribute | null; intensity: TrainingIntensity }>,
  players: PlayerResponse[],
  rules: BulkTrainingRule[],
): Record<string, { focus: TrainableAttribute | null; intensity: TrainingIntensity }>
```

Matching rules:

- A rule matches a player when `player.detailedPosition === rule.position`. **Main position only** —
  `altPositions` are never consulted.
- A player with **no** `detailedPosition` (legacy payload) is never matched and keeps their
  current assignment. Rules are keyed on detailed positions, and the coarse fallback the table
  shows in its `Pos` column (`DEF`, `MEI`, …) is not one.
- Both `focus` and `intensity` are written for every matched player. A rule with
  `focus: null` clears that position's training focus — that is a deliberate assignment, not a
  no-op.
- Players matched by no rule are returned untouched.
- The input record is not mutated; a new record is returned.

### 8.2 `TrainingBulkCard.tsx`

A collapsed MUI `Card` above the training table, header **"Treino por posição"** with an
expand/collapse `IconButton`.

Expanded it shows, in order:

1. One row per configured rule: `Posição` · `Foco` · `Intensidade` (three `NativeSelect`s) and a
   remove `×` `IconButton`.
2. A `+` `IconButton` that appends a new row.
3. A `Salvar` button.

Details:

- **One rule per position.** Each row's `Posição` dropdown offers the 12 detailed positions minus
  those claimed by *other* rows (its own stays selectable). The `+` button is disabled once all
  12 positions are in use.
- A new row defaults to the first unused position, `Foco: Nenhum` (`null`), `Intensidade: Normal`.
- `Foco` offers `Nenhum` plus the 12 trainable attributes, reusing `TRAINABLE_ATTRIBUTE_LABEL`.
- `Salvar` is disabled when there are no rules.
- Labels for positions come from `DETAILED_POSITION_SHORT`.

### 8.3 `TrainingTab.tsx`

The extracted Treino panel: the bulk card, the existing per-player table, and the existing
`Salvar treino` button. It owns the two change handlers (`changeFocus`/`changeIntensity`) and
`trainingMutation` — moved verbatim from `SquadPage`. It receives `displayPlayers`, `saveGameId`,
`clubId` and the `playerNameButton` render prop, plus `assignments` and `onChange`.

`trainingAssignments` itself, and the effect that hydrates it from the squad query, **stay in
`SquadPage`** rather than moving into `TrainingTab` — deliberately, not as leftover scope. The tab
stays mounted-only-while-active (`tab === 2`, see below), so any state owned by the tab component
itself would be discarded on every tab switch, silently losing an unsaved focus/intensity edit the
moment the manager glances at Plantel or Táticas. `SquadPage` outlives the mount/unmount cycle, so
state kept there survives it; `TrainingTab` only reads `assignments` and reports edits upward via
`onChange`, the same shape `changeFocus`/`changeIntensity` already use internally.

The tab stays mounted-only-while-active (`tab === 2`), for the reason already documented at
`SquadPage.tsx:1005-1011`: mounting it always would render every player's name a second time and
break `getByText` in unrelated tests.

### 8.4 Save flow — optimistic

Clicking the card's `Salvar`:

1. Snapshots the current `trainingAssignments`.
2. Sets state to `applyBulkRules(current, players, rules)` — **the table's dropdowns update
   immediately**, before any network call.
3. Fires the existing `setTraining` mutation with the new assignments.
4. On success: the existing write-through of the returned `SquadResponse` into the squad cache,
   and the existing success snackbar.
5. On error: state is restored from the snapshot and the existing error snackbar shows.

The rules themselves are **page state only**. There is no persistence, no new endpoint, no new
table — reopening Treino starts with an empty card. The per-player values it produces are
persisted by the existing training endpoint, which is the part that matters.

## 9. Card frequency

`BASE_CARD_RATE` goes from `0.02` to `0.015` in **both**:

- `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:44`
- `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java:28`

They must move together — the instant and live paths are calibrated against each other, and a
divergence would make a watched match statistically different from a simulated one.

`RED_SHARE` stays at `0.06`. Reds fall by the same 25% in absolute terms while remaining the same
fraction of cards; second-yellow reds (`FixtureSimulator.java:551`, `LiveMatchEngine.java:465`)
fall with the yellow rate that feeds them.

Expected outcome at aggression 1.0: ~1.35 cards per team per 90, ~2.7 per match, down from ~3.6.

Nothing else changes: `BASE_FOUL_RATE`, the aggression multiplier, `cardWeight` attribution and
the suspension accumulation rules are all untouched.

## 10. Testing

**Frontend (RTL, Vitest):**

- `NewsPage`: landing selects the oldest unread when several are unread; falls back to the newest
  when all are read; `Ler próxima` walks forward and decrements its count; disabled when nothing
  unread; a newly-arrived batch selects the first of the batch.
- `SquadPage`: `Salvar escalação` precedes `Selecionar melhores` in the button row and no longer
  appears at the bottom of Plantel; the morale chip renders in the Táticas panel and not in
  Plantel; the `Função`/`Foco` columns are gone from the desktop table.
- `Pitch`: right-click on an occupied slot fires `onSlotContextMenu` with coordinates; a 500ms
  hold fires it and suppresses the following `onSlotClick`; a short tap still fires `onSlotClick`;
  a hold that moves past the threshold still resolves as a drag.
- `RoleContextMenu`: opens with the player's name and both selects; changing Função resets Foco.
- Position unblocking, one test each: a keeper-less XI saves; a twelfth-man second keeper is
  blocked with the new message; the slot picker lists a goalkeeper for an outfield slot; `+`
  places a defender into a free midfield slot when the defensive line is full; a token can be
  dragged onto the GK slot.
- `TrainingBulkCard`: add/remove rows; a position claimed by one row is absent from another row's
  options; `+` disabled at 12 rows; `Salvar` applies to the table immediately; an API error rolls
  the table back.

**Frontend (unit, Vitest):** `bulkTraining.ts` — matches on main position only; ignores
`altPositions`; leaves players without a `detailedPosition` untouched; `focus: null` clears;
unmatched players unchanged; input not mutated.

**Backend (JUnit):** the existing relative card assertions
(`MatchSimulationServiceTest.countCards`, `FixtureSimulatorCardsIT`) must still pass. A new
calibration assertion pins the per-match card average to a band around the new rate, so a future
edit cannot drift it silently.

## 11. Out of scope

- Persisting bulk training rules per club (§8.4).
- Allowing two or more goalkeepers in the XI — would need a backend rule change and a change to
  the engine's keeper pick.
- Fetching older news pages to find unread items (§4.3).
- Any change to foul rate, aggression, card attribution weights, or suspension thresholds.
- The `useLineup` hook / full tab split rejected in §3.
