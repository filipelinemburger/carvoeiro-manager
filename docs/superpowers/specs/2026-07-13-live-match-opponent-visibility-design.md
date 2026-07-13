# Live-Match Opponent Visibility — Design

**Date:** 2026-07-13
**Status:** Approved

## Goal

Let the user see the opponent's side during a live match: their player ratings (a new
"Adversário" sub-tab next to today's own-team grades under "Atuações") and a dual-formation
pitch view showing both teams' current on-pitch shape (a new "Campo" tab).

This is Group 4 of the 10-item enhancement roadmap (`~/.claude/plans/imperative-strolling-parasol.md`).

## Background

- `LiveMatchService.toState()` (`backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`)
  currently computes four things only for the **managed** side, each hardcoded via
  `lm.isManagedIsHome()`: player ratings (`managedRatings`), the on-pitch/bench roster
  (`onPitchManaged`), and the active tactics (`computeCurrentTactics`). `PlayerRatingCalculator.rate()`
  itself is already fully side-agnostic — it takes a plain player list, event list, minutes map,
  and scored/conceded counts, with no notion of "managed."
- `LiveMatchConfig` already carries both sides' full data (`homeXi`, `awayXi`, `homeBench`,
  `awayBench`, `homeProfile`, `awayProfile`) — nothing about the opponent's data is actually
  missing, it's just never read.
- `frontend/src/features/pitch/Pitch.tsx` already supports two teams (`Pitch({home, away})`,
  mirroring the away side onto the opposite half) and is unit-tested, but only `home` is ever
  passed in production (`SquadPage.tsx`, for the managed team's static saved lineup).
  `Pitch.tsx` only needs each player's coarse `position` (GK/DEF/MID/ATT) and the team's
  `formation` code to lay players out — confirmed by reading `assign()`/`pitchSlots.ts`
  directly; `PlayerRole` is not read anywhere in the actual rendering path, even though
  `pitchSlots.ts`'s `Slot` type carries an unused `defaultRole` field.
- `PlayerRatingsList.tsx` is already a pure, reusable presentational component taking
  `ratings: PlayerRating[]` — usable as-is a second time for the opponent.
- `LivePlayerResponse` has no `role` field today (only the coarse `position`), even though
  `TacticalProfile.roles()` (`Map<UUID, PlayerRole>`, keyed by player id) is already resolved
  and available on `LiveMatchConfig`'s `homeProfile()`/`awayProfile()`.
- `LiveMatchState` has no club-colors field; `Club` entities (already loaded in `toState()` for
  the club names) expose `getPrimaryColor()`/`getSecondaryColor()`.

## Backend changes

Four existing managed-side-only computations are generalized using the same pattern: extract
the core logic into a version that takes an explicit `isHome` boolean, keep the existing method
as a one-line delegate to it (so its two current call sites don't change), and add an
`opponent*` sibling that calls the same core logic with `!lm.isManagedIsHome()`.

- `managedRatings(...)` → generalized; new `opponentRatings(...)`.
- `onPitchManaged(...)` → generalized; new `onPitchOpponent(...)` (needs the opponent's XI from
  `cfg`, selected by `!managedIsHome`). Only the on-pitch 11 and their hobbled status are needed
  for this pass's UI (the pitch view draws only starters-currently-on-the-pitch, never a bench) —
  the `usedIn` tracking `onPitchManaged` computes today is specifically for the managed side's
  substitution dialog (marking which bench players are already used) and has no opponent
  equivalent in this scope, so the generalized core logic drops that piece for the opponent path.
- `computeCurrentTactics(...)` → generalized; new `opponentTactics(...)`.

`LivePlayerResponse` gains a `role: String` field — computed via
`profile.roles().getOrDefault(player.getId(), PlayerRole.defaultFor(player.getPosition())).name()`,
where `profile` is whichever side's `TacticalProfile` that player belongs to. Not consumed by
the frontend in this pass (Pitch.tsx doesn't need it), but wired through end-to-end per the
user's call, since the data is already resolved internally and cheap to expose.

`LiveMatchState` gains four new fields:
- `opponentOnPitch: List<LivePlayerResponse>`
- `opponentTactics: LiveTactics`
- `opponentRatings: List<PlayerRating>`
- `homeClubColors` / `awayClubColors`, each a small `{primary: String, secondary: String}`
  record — read from the same `Club` rows `toState()` already loads for the club names, no new
  query.

All four are computed unconditionally on every `toState()` call, matching how the managed
side's equivalents already work today (no lazy/on-demand fetching) — consistent with the
existing pattern and simple, given the payload size increase is a handful of players.

## Frontend changes

**Atuações tab:** a small "Meu Time" / "Adversário" toggle at the top of the existing content
area. `PlayerRatingsList` renders `state.playerRatings` or `state.opponentRatings` depending on
the toggle — no changes to that component.

**New "Campo" tab** (3rd top-level tab, alongside "Lances" and "Atuações"): builds two
`TeamOnPitch` objects, one per side — `players` from that side's `onPitch` roster (mapped to
`PlacedPlayer`), `formation` from that side's current tactics, `kit` from the new club-colors
fields — and renders `<Pitch home={...} away={...} />` unchanged. `showNames: true` for both
sides (a spectator view of the whole match, unlike `SquadPage`'s single-team editor). Each
token's `badges.injured` is set from that player's `hobbled` flag — a small addition since the
data is already present and `PlayerToken` already supports the badge, just unused everywhere
today.

Home/away mapping for both new UI pieces: whichever of `state.onPitch`/`state.opponentOnPitch`
(and the ratings/tactics equivalents) corresponds to the actual home club is decided by
`state.managedIsHome`, exactly like the existing `homeClubName`/`awayClubName` display already
does today.

## Testing

- Backend: new tests confirming `opponentRatings`/`opponentOnPitch`/`opponentTactics` compute
  correctly for the non-managed side, mirroring the coverage the managed-side versions already
  have (same scenarios, opposite side). A test confirming
  `LivePlayerResponse.role` resolves from the correct side's `TacticalProfile` and falls back to
  `PlayerRole.defaultFor(position)` when a player has no explicit role entry.
- Frontend: a test for the Atuações toggle (switching shows the other side's ratings via the
  same `PlayerRatingsList`); a test for the Campo tab rendering both teams' current formations
  and on-pitch players.
- Full backend suite green, full frontend suite green, per project convention.

## Out of scope

- Any change to `Pitch.tsx`, `PlayerRatingsList.tsx`, or `pitchSlots.ts` — both are reused
  exactly as they are.
- Consuming the new `role` field in any UI — it's wired through the backend/API only, per this
  pass's scope; a future feature can read it later.
- Historical/post-match opponent data — this is a live-in-progress-match feature only, matching
  the existing managed-side ratings' "populated live, every poll" behavior.
