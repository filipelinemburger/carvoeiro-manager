# Live-Match Manual Positioning — Design

**Date:** 2026-09-10
**Status:** Approved

## Goal

Let the manager rearrange their team on the pitch **during** a live match — drag any player to any
slot, change formation and have the players actually relocate, and adjust a player's role — using
the same pitch editor the squad screen already has. The rearrangement is free and instant: it
applies from the next minute, as many times as the manager likes, with no adaptation period, no
budget, and no restriction to pauses.

A rearrangement lives for that match only. The saved squad lineup is never written back, so the
next match starts from whatever the manager left on the squad screen.

## Background

- **The live match has no slot state at all.** `LiveMatch` stores each XI as a flat, ordered list
  of player ids (`homeXi`/`awayXi`, written by `LiveMatchService.ids()`). Nothing records who
  plays where.
- **The engine does grade slot fit.** `TeamStrengthCalculator.positionFitMult()`
  (`backend/.../match/TeamStrengthCalculator.java:140-158`) scores the tactical distance between a
  player's playable positions and their assigned slot: natural ×1.00, adjacent ×0.92, far ×0.80,
  GK-cross ×0.50. Its input is `TacticalProfile.slotAssignments()`, a `Map<UUID,
  FormationSlots.Slot>` built once by `TacticsService` at kickoff via
  `SlotAssigner.assignToSlots(xi, formation)`.
- **A mid-match formation change relocates nobody.** `LiveMatchEngine.applyTacticChanges()`
  (`backend/.../match/LiveMatchEngine.java:340-355`) rebuilds the profile for the new formation
  but carries `old.slotAssignments()` forward untouched. `TacticalProfile`'s javadoc states this
  explicitly. The formation code changes; the grading does not.
- **A player with no slot assignment is graded ×1.00** (`TeamStrengthCalculator.java:143`). Since
  `slotAssignments` only ever covers the kickoff XI, **every substitute currently plays with no
  positional penalty whatsoever** — a goalkeeper brought on as a striker costs nothing today.
- **Roles are derived from slots.** `TacticsService` (`:77`, `:153`) computes each starter's
  `RoleAssignment` as `bestAssignmentFor(p, slotAssignments.get(p.getId()))`. Moving a player to a
  different slot therefore changes their role archetype, not just their position on the pitch.
- **The timeline is recomputed in full on every advance.** `LiveMatchConfig`'s javadoc states the
  timeline is a pure function of its inputs, and `LiveMatchService.changeTactics()` re-runs
  `liveMatchEngine.simulate(cfg)` on each call. Any mid-match state therefore **must** be a
  minute-stamped schedule — a mutable "current arrangement" field would retroactively rewrite
  minutes the manager has already watched.
- **The assignment algorithm this needs already exists.**
  `SlotAssigner.assignToSlots(xi, formation, pinnedSlotIndexes)` (the three-argument overload)
  honours explicit pinned indexes verbatim and sends only the leftovers through the greedy
  natural-first fit. It is already used by the pre-match lineup path.
- **`FormationSlots.Slot` carries no index** — it is `record Slot(Set<DetailedPosition> accepted)`.
  Slot identity is positional: the index into `FormationSlots.of(code)`'s list. The frontend's
  `pitchSlots.ts` is documented as matching that order slot-for-slot, an invariant the existing
  lineup feature already relies on.
- **The frontend pitch editor is built and tested.** `frontend/src/features/pitch/Pitch.tsx`
  supports drag-to-swap, tap-to-select, and a long-press role context menu, and already places
  players by an explicit `slotIndex` when every player carries one (`placeExplicit`), falling back
  to a line-zip otherwise.
- **The in-match pitch is currently a lie.** `LineupsTab.tsx:50` renders `Pitch` from
  `LivePlayer[]`, which carries no `slotIndex`, so it falls through to the line-zip. The shape the
  manager sees mid-match is not the arrangement the engine is grading.

## Decisions

These were settled during design and are binding on the plan:

1. **Free and instant.** No adaptation penalty, no per-match budget, no restriction to half-time or
   forced pauses. A rearrangement applies from the next minute.
2. **The arrangement dies at full time.** Nothing is written back to the saved lineup.
3. **The AI opponent does not get rearrangement.** `SlotAssigner` already produces a near-optimal
   natural-first arrangement for AI sides at kickoff, so there is nothing for it to correct. This
   is a deliberate asymmetry with a stated reason, not an oversight.
4. **A formation change auto-relocates** via the existing greedy assignment, and the manager
   adjusts from there. Emptying eleven slots for the manager to re-fill mid-match is hostile.
5. **A substitute inherits the outgoing player's slot, and is therefore graded** like any starter.
   This deliberately changes simulation results for existing saves: bringing a player on out of
   position now costs what it should. Accepted knowingly — a rearrangement feature is dishonest if
   the players entering it float unslotted.
6. **A moved player adopts the new slot's natural role**, overridable through the role context menu
   `Pitch` already provides.

## Backend changes

### Schedule

`V56__live_match_slot_schedule.sql` adds `slot_schedule TEXT NOT NULL DEFAULT '[]'` to
`live_match`, a sibling of `sub_schedule` and `tactics_schedule`.

New record `TimedSlots(int minute, boolean home, Map<UUID, Integer> slotIndexes,
Map<UUID, RoleAssignment> roles)` — a **complete snapshot of all eleven**, not a delta. A snapshot
is self-describing: it replays without knowing prior state and survives substitutions without an
extra reconciliation rule.

`LiveMatchConfig` gains `List<TimedSlots> slotSchedule`. An empty list is the neutral default and
must reproduce today's behaviour exactly.

### Replay

`LiveMatchEngine` gains `applySlotChanges(cfg, minute, side, events)`. The existing pair of calls at
`LiveMatchEngine.java:176-177` is kept as is, and two new calls are appended directly below them,
giving this exact order for every minute:

```
applyTacticChanges(cfg, minute, home, events);
applyTacticChanges(cfg, minute, away, events);
applySlotChanges(cfg, minute, home, events);
applySlotChanges(cfg, minute, away, events);
```

The order is load-bearing and must be documented at the call site: a formation change must install
the new slot list before an arrangement is applied against it. Both sides' tactics settle before
either side's arrangement, which keeps the two sides symmetric.

It rebuilds the side's `TacticalProfile` with
`SlotAssigner.assignToSlots(xi, formation, entry.slotIndexes())`, then resolves roles from the
resulting slots via `bestAssignmentFor`, with `entry.roles()` overriding where the manager set one
by hand.

Like `TimedTactics`, it draws no RNG, so the match/attribute/injury streams stay byte-identical for
a given `(seed, subSchedule)`.

### Substitute slot inheritance

When the engine applies a `LiveSub`, the incoming player takes the outgoing player's entry in
`slotAssignments`. This is what makes Decision 5 real, and it applies whether or not the manager
has ever opened the positioning tab.

**It applies to both sides**, including AI-controlled ones. Decision 3 withholds the *rearrangement
tool* from the AI; it does not exempt AI substitutes from positional grading. An AI side whose
`AiSubstitutionBrain` brings on an out-of-position player pays the same penalty the manager's would.
Leaving AI substitutes ungraded while grading the manager's would be an asymmetry in the manager's
disfavour, and nobody asked for that.

### Wire

`LivePlayerResponse` gains `slotIndex` (`Integer`, null for bench players), `detailedPosition`, and
`altPositions`. Without these the pitch can neither draw the true arrangement nor tint a player who
is out of position.

`LiveTacticsRequest` gains an optional `arrangement` field, shaped as:

```java
record ArrangementRequest(
        Map<String, Integer> slotIndexes,   // playerId -> slot index, all 11
        Map<String, String> roles) {}       // playerId -> RoleArchetype name; may be empty
```

String-keyed because it crosses JSON; the service parses to `UUID`. Validation mirrors
`LineupService`'s existing rule for explicit slots: `slotIndexes` must be a permutation of
`0..10` over exactly the eleven players currently on the pitch for that side, or the request is
rejected with `INVALID_LINEUP`. A `roles` entry for a player absent from `slotIndexes` is rejected
the same way; an empty `roles` map means "every player takes their slot's natural role".

A single `POST /live-match/tactics` writes the `TimedTactics` entry and, when `arrangement` is
present, the `TimedSlots` entry **at the same minute** — so formation-plus-relocation is atomic in
the service and the client never has to sequence two calls.

## Frontend changes

- A third tab, **Posições**, in `MatchActionsDialog`, rendering `Pitch` for the managed side with
  its existing `onSwap`, tap-to-select and long-press role menu. It is seeded from `state.onPitch`,
  which now carries a real `slotIndex`.
- The dialog's apply path sends the arrangement alongside any tactics change in the one request.
  The footer's change count (built in the previous usability pass) must include a changed
  arrangement.
- `LineupsTab` becomes truthful for free: once `LivePlayer` carries `slotIndex`, `Pitch`'s
  `placeExplicit` path takes over from the line-zip fallback. No change to `LineupsTab` itself.

## Testing

**Backend**
- An empty `slotSchedule` produces a byte-identical timeline to the pre-change engine — the
  regression that protects every existing save.
- Pinned indexes are honoured verbatim; unpinned players fill the leftovers greedily.
- A substitute inherits the outgoing player's slot and is graded by `positionFitMult` accordingly
  (a GK brought on into a striker slot must be penalised, not ×1.00) — asserted for an
  **AI-controlled side** as well as the managed one, since Decision 3 withholds only the tool.
- An `arrangement` whose `slotIndexes` are not a permutation of `0..10` over the side's eleven
  on-pitch players is rejected with `INVALID_LINEUP`.
- A mid-match formation change relocates players instead of preserving the kickoff assignment —
  the behaviour `TacticalProfile`'s javadoc currently documents as *not* happening. That javadoc
  must be updated in the same change.
- Tactics and arrangement submitted together land at the same minute, tactics applied first.

**Frontend**
- `Pitch` inside the dialog renders the server's arrangement, not the line-zip fallback.
- A drag produces the expected `arrangement` payload.
- `LineupsTab` renders the true arrangement once `slotIndex` is present.

## Out of scope

- Live per-player ratings during the match (the backend populates `playerRatings` only at full
  time).
- Rearrangement for AI-controlled sides (Decision 3).
- Persisting the in-match arrangement back to the saved lineup (Decision 2).
- Any adaptation penalty, cooldown, or budget mechanic (Decision 1).
