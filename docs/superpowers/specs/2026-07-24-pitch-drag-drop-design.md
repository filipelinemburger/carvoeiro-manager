# Pitch drag-and-drop slot assignment — design

**Date:** 2026-07-24
**Status:** approved (brainstorming session)

## Problem

The tactics pitch (SquadPage → "Táticas de jogo" tab) is read-only. Players are placed
into formation slots by a naive zip — the order they appear in the selected XI, filtered per
coarse line (GK/DEF/MID/ATT) in `Pitch.tsx#assign`. The user cannot choose who plays in which
slot (e.g. which lateral plays on the right vs. left, who is the volante vs. the meia), and the
match engine ignores the display entirely: `SlotAssigner.assignToSlots` re-derives assignments
greedily and grades the out-of-position penalty on its own assignment.

## Goal

Drag-and-drop a player token to another spot on the pitch to **really** assign him there:

- The arrangement persists with the lineup and survives reload.
- The match engine grades the out-of-position penalty against the user's arrangement.
- Any outfield player can be dropped on any outfield slot (cross-line allowed — the warn tint
  and match penalty price it). The GK token is locked and the GK slot accepts no drops.
- Dropping on an occupied slot swaps the two players; on an empty slot, moves.
- Saving is explicit (a button), not per-drop.

## Non-goals

- `PreMatchLineups` and `LiveMatchPage` pitches stay read-only and keep their current
  zip-based rendering (their data sources don't carry slot info).
- AI clubs keep the fully greedy assignment (`TacticsService.defaultProfile` unchanged).
- No keyboard-accessible drag (future work).
- No changes to bench ordering, roles, or instructions.

## Shared contract: the slot index

Backend `FormationSlots.of(formation)` and frontend `pitchSlots(formation)` already emit the
same 11 slots in the same stable order (index 0 = GK, then DEF left→right, MID deepest row
first, ATT) — both files document that they mirror each other. **The slot index into this
shared order is the persistence contract.** Slot 0 is the GK slot in every formation.

## 1. Data model & API

### Migration

`V40__lineup_slot_index.sql`:

```sql
ALTER TABLE lineup_players ADD COLUMN slot_index INT;
```

Nullable; no backfill. Legacy starter rows and all bench rows stay `NULL`.

### DTOs

- Java `LineupStarter` record gains a 4th component: `Integer slotIndex` (nullable).
  Convenience constructors keep existing `(playerId, role)` and `(playerId, role, instruction)`
  call sites compiling (delegating with `slotIndex = null`).
- TS `LineupStarter` type gains `slotIndex: number | null`.
- `LineupPlayer` entity gains the `slot_index` column mapping (`Integer`, nullable) with a
  setter; `LineupService.getLineup`/`toLineupStarter` and `setLineup` echo it through, so
  `GET` and `PUT` responses both carry it (null on legacy lineups).

### `setLineup` validation (before any delete/insert, like the existing checks)

All-or-nothing on the 11 starters, error code `INVALID_LINEUP` (422):

1. Either **every** starter has a non-null `slotIndex` or **none** does. Mixed → reject.
2. When present: the 11 values must be exactly the set {0..10} (a permutation — this also
   bounds the range and forbids duplicates).
3. The starter assigned `slotIndex` 0 must have position GK. (Together with the existing
   "exactly 1 GK" check this pins the goalkeeper to the GK slot and keeps outfielders off it.)
4. A bench entry with a non-null `slotIndex` → reject.

When no starter carries a `slotIndex` (legacy client / old tests), behavior is exactly today's:
rows persist with `slot_index = NULL` and the engine falls back to greedy.

## 2. Match engine

`TacticsService.fromLineup(lineup, xi)` builds `slotAssignments` in two steps instead of one
greedy pass:

1. **Pinned:** every XI member whose persisted `LineupPlayer.slot_index` is non-null (and
   `< slots.size()`, defensive) gets exactly that slot.
2. **Leftovers:** remaining XI members are greedily assigned to the remaining slots via the
   existing `SlotAssigner.assign` (which already takes an arbitrary slot list). This covers
   substitutes who came on mid-match (`LiveMatchService` re-runs `profileFor` with the current
   XI — the sub inherits the single vacated slot), legacy lineups (everything is a leftover →
   byte-identical to today), and the ineligible-starter fallback XI.

`SlotAssigner` gains a small overload/helper for "assign these players to this subset of slots
and merge with the pinned map"; the greedy core is unchanged. `defaultProfile` (AI clubs) is
untouched.

Effect: `TacticalProfile.slotAssignments` — and therefore the out-of-position multiplier in
`TeamStrengthCalculator` — now reflects the user's arrangement.

## 3. Frontend state (SquadPage)

New state: `slotByPlayerId: Record<string, number>` (playerId → slot index). Invariants: keys
⊆ `selected`, values unique. All sync points:

- **Hydration** from `getLineup`: seed from the returned `slotIndex`s. If any starter lacks one
  (legacy lineup), run the client-side greedy assigner (below) over the full XI instead.
- **Plantel "+" (cyclePlayer add):** first free slot whose `accepted` contains the player's
  `detailedPosition` → else first free slot of his coarse line → else bench (today's feel;
  cross-line placement happens only by dragging). Structurally caps starters at 11 (11 slots).
- **Remove:** delete the player's entry (his slot becomes an empty placeholder).
- **autoPickBestXI:** after picking, run the greedy assigner for a full arrangement.
- **clearSelection:** reset to `{}`.
- **Formation change:** re-run the greedy assigner from scratch against the new formation's
  slots — custom placement intentionally resets (the slot geometry changed).
- **Drag swap/move** (from the Pitch callback): update the two entries; mark unsaved
  (`setSaveSuccess(false)`).
- **Save payload:** each starter gets `slotIndex: slotByPlayerId[id]`.

### Client-side greedy assigner

`frontend/src/features/pitch/slotAssign.ts` — a mirror of `SlotAssigner`'s tiering so defaults
never drift from the engine: slots processed scarcest-first (fewest tier-1 exact-detailed
candidates in the initial pool, ties by slot index); each slot takes the best remaining
candidate by tier (1 = `accepted` contains `detailedPosition`, 2 = same coarse line,
3 = anyone), ties by overall desc then id. Input: `{ id, position, detailedPosition, overall }`
(all on `PlayerResponse`). Output: `Record<playerId, slotIndex>`. Unit-tested against the same
scenarios as the backend's.

### Validation change

`validate()` relaxes from strict per-line player counts to slot-based rules:

- exactly 11 selected, each with a slot assignment (⇔ all 11 slots filled);
- exactly 1 GK (unchanged message);
- no ineligible player (unchanged).

The strict-shape error ("A formação 4-3-3 exige 3 atacante(s)…") disappears — a MID occupying
an ATT slot is legal and simply warns/penalises. The helper line becomes slot occupancy per
line (e.g. `DEF 4/4` = DEF-line slots filled, whatever the occupants' natural positions).
A second GK can never enter the XI: the only slot accepting GOLEIRO is slot 0, so the "+"
flow sends him to the bench, and the "exactly 1 GK" validation still backs this up.
`FORMATION_SHAPE` survives only where it still fits (e.g. `autoPickBestXI`'s balanced pick).

### Save button

The Táticas tab gets a "Salvar escalação" button under the pitch firing the **same** lineup
mutation (and sharing `canSave`), so a drag session can be saved without switching tabs.
Unsaved arrangements survive tab switches (panels stay mounted, as today).

## 4. Pitch drag interaction

`Pitch` stays presentational and read-only by default; interactivity is opt-in:

- `PlacedPlayer` gains optional `slotIndex`. When **every** player of a team carries one,
  `TeamLayer` places by it (empty slots = the unassigned indexes, shown as today's numbered
  placeholders); otherwise the legacy zip runs (PreMatch/Live unchanged). Shirt number stays
  `slotIndex + 1`, so numbers follow the slot, as today.
- New prop `onSwap?: (fromSlot: number, toSlot: number) => void` — its presence makes home-team
  outfield tokens draggable (away layer never is).
- Hand-rolled pointer events (works for mouse + touch): `pointerdown` on a token +
  `setPointerCapture`, `touch-action: none`; while dragging, the token follows the pointer
  (transform, raised z-index) and the nearest droppable slot within the drop radius gets a
  highlight ring; `pointerup` → if a candidate slot exists, call `onSwap(from, to)` (occupied
  target ⇒ the parent swaps the two entries; empty ⇒ move), else snap back. Default drop
  radius: nearest slot center within ~12% of the pitch width (implementation may tune).
- GK rules: the slot-0 token has no drag handlers, and slot 0 is never a drop candidate.
- The out-of-position warn tint needs no new code — it already derives from
  `slot.accepted` vs. `detailedPosition`, so it updates live after each swap.

## 5. Error handling

- Backend: all new rejections are `ApiException(INVALID_LINEUP, …)` with a message naming the
  offending rule, thrown before any mutation (whole request rejected, nothing partial).
- Frontend: save failures reuse the existing error snackbar; an invalid drop simply snaps back
  (no toast); the drag never produces an invalid local state (GK locked, swaps preserve the
  permutation invariant by construction).

## 6. Testing

Backend (JUnit, existing patterns):
- `LineupService.setLineup`: accepts a valid permutation; rejects mixed null/non-null,
  duplicate/out-of-range indexes, non-GK at slot 0, bench with `slotIndex`; legacy request
  (all null) still saves; save→get roundtrip returns the indexes.
- `TacticsService.fromLineup`: persisted indexes are honored verbatim (including a deliberate
  out-of-position arrangement); a player not in the persisted map (sub) greedily inherits the
  vacated slot; all-null lineups behave byte-identically to today.

Frontend (Vitest + RTL):
- `slotAssign.ts` unit tests mirroring the backend tiering scenarios.
- Pitch drag: pointer-event sequence swaps two tokens, moves to an empty slot, snaps back
  outside the radius, GK not draggable, GK slot not droppable, warn tint appears after a
  cross-line swap.
- SquadPage: hydration with and without `slotIndex`s; "+" fills detailed-position slot first;
  save payload carries the permutation; formation change re-derives; relaxed validation
  (cross-line arrangement saves; 2 GKs still blocked); existing tests updated for the removed
  strict-shape error.

Live verification: run backend + frontend, drag on the Táticas tab, save, reload, simulate.
