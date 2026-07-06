# Pitch View (Championship Manager–style) — Design

**Status:** Approved (brainstorm complete, 2026-07-06)
**Author:** Filipe Linemburger (with Claude)
**Scope:** New feature for carvoeiro-manager — a visual football pitch that shows players in their formation, both during the live match and in the lineup/tactics editor. Also expands the tactical formation catalogue from 6 to 16.

---

## 1. Goal

Add a reusable **pitch view** that renders an eleven in its formation on a green pitch, used in two places:

1. **Live match** — both teams facing off, reacting in real time as the match advances (goals, cards, injuries, substitutions), in the classic Championship Manager 01/02 style.
2. **Lineup/tactics editor** — an interactive pitch on the Squad screen where the manager builds the XI by tap-to-select / drag-and-drop.

Player tokens are always painted in **their own club's kit colours**.

Also: grow the formation catalogue from **6 → 16** so managers have real tactical choice.

## 2. Context & constraints (from the codebase)

- **Live matches are pre-computed then revealed minute-by-minute.** `LiveMatchEngine` simulates the whole game deterministically from a seed at kickoff; the client advances (~every 1.5 s) and `LiveMatchState` returns the events revealed *so far* plus the managed XI/bench, score, minute, and status. There is **no per-minute player position or trajectory data** — only a timeline of typed events (`GOAL`, `YELLOW_CARD`, `RED_CARD`, `INJURY`, `SUBSTITUTION`, `HALF_TIME`, …) each carrying a minute + player id.
  - **Consequence:** we cannot animate players running or the ball moving. We *can* place players by formation and react to events. This is exactly the CM 01/02 experience and is a deliberate non-goal to go further (see §12).
- **No positional coordinates exist anywhere.** A formation is a string (`"4-3-3"`); players carry an intrinsic `position` (GK/DEF/MID/ATT) and a per-lineup `role` enum (POACHER, FULL_BACK, …). Placing players on a pitch requires a **new** formation→coordinate map, authored on the frontend.
- **`LiveMatchState` today only sends the *managed* club's XI/bench.** Showing both teams needs an additive backend change to also send the opponent XI + both clubs' colours + both formations.
- **Team colours already exist.** `Club.primaryColor` / `Club.secondaryColor` (hex, `length 9` for `#RRGGBBAA`) are populated for every club in the seed (e.g. Criciúma `#f7d417`/`#111111`, Avaí `#003399`/`#ffffff`). No schema change needed.
- **Backend does not validate formation shape.** `LineupService.setLineup` validates player count, ownership, GK count, eligibility, and roles — but stores `formation` as a free string. `Formation.fromCode` maps a known code to its attack/defence multipliers and falls back to `4-4-2` for unknown codes. So a new formation is, backend-side, **one enum entry**.
- **Frontend stack:** React 19, MUI 9, TanStack Query 5, React Router 7, `useIsMobile()`; feature-folder layout under `frontend/src/features/*`; existing small visual components are DOM/MUI only (`FitnessBar`, `PotentialStars`) — **no SVG/canvas precedent**, so the pitch is a greenfield component.
- **Frontend/backend formation lists are out of sync:** backend enum has 6 (`4-4-2, 4-3-3, 3-5-2, 5-3-2, 4-5-1, 4-2-3-1`); frontend `FORMATIONS` lists only 5 (missing `4-5-1`). This will be reconciled to the full 16 in Phase 1.

## 3. Locked design decisions

| # | Decision |
|---|----------|
| D1 | The pitch lives in **both** the live-match screen and the lineup editor, backed by **one shared `Pitch` component**. |
| D2 | The live-match pitch shows **both teams** facing off (needs the additive backend change in §6). |
| D3 | The editor pitch is **interactive**: tap-to-select→tap-target as the primary (mobile-first) mechanism, pointer-drag as a desktop enhancement. |
| D4 | Rendering is **hybrid**: an SVG draws the pitch (grass, lines, boxes, centre circle); each player is a **positioned DOM/MUI token** overlaid on top (easy to style, badge, animate, and drag). |
| D5 | Tokens are painted in the player's **own club kit** (`primaryColor` fill, `secondaryColor` number/trim), with a **contrast** rule and a **kit-clash fallback** (§7). |
| D6 | Ship in **three phases**, each independently shippable and each its own implementation plan: Foundation → Live-match → Interactive editor. |
| D7 | Expand the formation catalogue **6 → 16** (§5), authored in Phase 1. |
| D8 | Persistence in the editor is an **explicit "Salvar escalação"** button via the existing `setLineup` endpoint (not autosave-on-drag). |

## 4. Architecture

```
frontend/src/features/pitch/            (new shared feature folder)
  pitchSlots.ts        pure: formation code -> ordered Slot[] (11 normalized 0..1 coords + line + defaultRole)
  kit.ts               pure: colour helpers — readableTextColor(bg), kitsClash(a,b), resolveKits(home,away)
  Pitch.tsx            presentational: SVG pitch + positioned PlayerToken children; props-driven, no data fetching
  PlayerToken.tsx      one token: kit colours, number, surname, fitness ring, status badges
  pitchSlots.test.ts, kit.test.ts, Pitch.test.tsx

Consumers:
  features/match/LiveMatchPage.tsx   -> <Pitch> both teams, read-only, event-driven badges   (Phase 2)
  features/squad/SquadPage.tsx       -> <Pitch> single team, interactive editor              (Phase 3)

Backend:
  player/Formation.java              -> +10 enum entries (Phase 1)
  match/LiveMatchState.java + service -> expose opponent XI + both kits + both formations     (Phase 2)
```

**Unit responsibilities & interfaces**

- **`pitchSlots(formation: string): Slot[]`** — pure. `Slot = { line: 'GK'|'DEF'|'MID'|'ATT'; x: number; y: number; defaultRole: PlayerRole }`. `x,y` normalized to `[0,1]` in a "team attacks upward from the bottom" frame (GK near `y≈0.94`, forwards near `y≈0.40`). Exactly 11 slots; line counts match the formation's position-shape (§5). Unknown code → 4-4-2 slots (mirrors backend fallback).
- **`kit.ts`** — pure colour helpers: `readableTextColor(hexBg)` picks black/white by luminance; `kitsClash(aPrimary, bPrimary)` returns true when the two primaries are perceptually too close (simple RGB/luminance distance under a threshold); `resolveKits(home, away)` returns the fill/text colours for each side, swapping the **away** side to its `secondaryColor` when the primaries clash.
- **`Pitch`** — presentational only. Props: `homeTeam` and optional `awayTeam` (each `{ formation, players: PlacedPlayer[], kit }`), plus `interactive?` config and an `onSwap` callback for the editor. Renders the SVG field once and maps players to slots. Knows nothing about fetching or match state.
- **`PlayerToken`** — one player: kit fill/number, surname (home only by default), a thin fitness ring (green/amber/red bands mirroring `FitnessBar`), and status badges (goal ⚽, yellow/red card, injury cross). In interactive mode it is the select/drag handle.

The **live-match** and **editor** screens own all data-fetching and state; they translate their data into `Pitch` props. The pitch itself is dumb and fully unit-testable.

## 5. Formation catalogue (16)

A formation contributes: a **code**, **attack/defence multipliers** (backend `Formation` enum, feeds `TeamStrengthCalculator`), a **position-shape** (DEF/MID/ATT counts, frontend `FORMATION_SHAPE`), and **slot coordinates** (frontend `pitchSlots`).

**Shape convention (matches the existing 4-2-3-1 → 4/5/1):** count by intrinsic `Position`. Attacking-midfield slots count as **MID**, wing-backs as **DEF**, withdrawn/second strikers as **ATT**. Several formations therefore share a position-shape but differ in **slot layout** and **multipliers** — that is intended (visual + balance identity without a positional model the engine doesn't have).

| Formation | Shape DEF/MID/ATT | attackMult | defenseMult | Identity | Status |
|-----------|-------------------|------------|-------------|----------|--------|
| 4-4-2         | 4/4/2 | 1.00 | 1.00 | balanced baseline | existing |
| 4-3-3         | 4/3/3 | 1.10 | 0.94 | attacking, wide | existing |
| 3-5-2         | 3/5/2 | 1.06 | 0.96 | midfield overload | existing |
| 5-3-2         | 5/3/2 | 0.92 | 1.10 | defensive | existing |
| 4-5-1         | 4/5/1 | 0.96 | 1.06 | packed midfield | existing |
| 4-2-3-1       | 4/5/1 | 1.04 | 1.00 | attacking-balanced | existing |
| 4-1-4-1       | 4/5/1 | 0.98 | 1.05 | single pivot, cautious | new |
| 4-3-2-1       | 4/5/1 | 1.00 | 1.04 | narrow "Christmas tree" | new |
| 3-4-3         | 3/4/3 | 1.12 | 0.90 | all-out wide attack | new |
| 4-4-1-1       | 4/4/2 | 0.99 | 1.02 | withdrawn second striker | new |
| 5-4-1         | 5/4/1 | 0.90 | 1.12 | ultra-defensive | new |
| 4-2-4         | 4/2/4 | 1.14 | 0.88 | ultra-attacking | new |
| 3-4-2-1       | 3/6/1 | 1.06 | 0.95 | wing-backs + two 10s | new |
| 4-1-2-1-2     | 4/4/2 | 1.02 | 1.00 | midfield diamond | new |
| 5-2-3         | 5/2/3 | 1.02 | 1.02 | back-five counter | new |
| 4-3-1-2       | 4/4/2 | 1.03 | 0.99 | narrow, one 10 | new |

Multipliers above are the **starting values**; the plan may fine-tune within the existing 0.88–1.14 band. All 16 get authored `pitchSlots` coordinates in Phase 1.

## 6. Backend change (Phase 2) — expose both teams

`LiveMatchState` gains (additive; existing fields unchanged so nothing breaks):

- `homeColors` / `awayColors`: `{ primary: string; secondary: string }` from each `Club`.
- `homeFormation` / `awayFormation`: the formation code each side is using (managed side from its saved lineup / tactics schedule; AI side from its chosen tactics, defaulting to `4-4-2`).
- The **opponent's on-pitch XI** as a list of `{ playerId, name, position, role, hobbled }` — enough to place and badge them. (Your XI is already sent via `onPitch`.)

No new endpoints, no new polling — the existing `advance`/`getLiveMatch` payloads simply carry more. Shirt numbers are **not** stored on players; tokens derive a stable 1–11 from slot order (GK=1, then by line) unless real squad numbers are added later (non-goal for now).

## 7. Token colours, contrast & kit clash (frontend, pure)

- **Fill** = club `primaryColor`; **number/trim** = club `secondaryColor`.
- **Contrast guard:** if `secondaryColor` is too low-contrast against `primaryColor`, the number falls back to black/white by luminance (`readableTextColor`).
- **Kit clash:** many clubs share a black primary (Figueirense, Ceará, Ponte Preta, Botafogo-SP…). If `kitsClash(home.primary, away.primary)`, the **away** side renders in its `secondaryColor` (a deterministic "change strip"). Computed purely on the frontend from the two clubs' colours; no data change.
- **Fitness ring:** a thin arc around each token, green/amber/red mirroring the existing `FitnessBar` bands, so tiredness reads at a glance during a match.

## 8. Live-match reactions (Phase 2, event-driven)

The pitch derives per-player state from `state.events` filtered to `minute ≤ state.minute` — no extra state machine, no new polling:

- **GOAL / SET_PIECE_GOAL** → brief pulse ring + ⚽ badge on the scorer's token.
- **YELLOW_CARD / RED_CARD** → persistent card badge; a red card also removes the token from the pitch after its minute.
- **INJURY / `hobbled`** → injury-cross badge.
- **SUBSTITUTION** → outgoing token fades out, incoming token fades in at the same slot.
- Score, minute, and status come straight from `state`; the pitch sits **above** the existing **Lances** (commentary) and **Atuações** (ratings) tabs, which are unchanged.

## 9. Interactive editor (Phase 3)

- **Layout:** single team (your XI) on a full-height pitch, kit-coloured, with a **bench strip** beneath.
- **Interaction:** primary is **tap-to-select → tap-target** (tap a player → highlights → tap a slot or another player to place/swap) — reliable on touch. **Pointer-drag** is layered on desktop; both routes call the same `onSwap(fromId, toSlotOrId)` logic.
- **Formation-driven slots:** the existing formation dropdown sets the slot template; dropping a player into a slot assigns that slot's **`defaultRole`** (reusing the current role model, e.g. a wide-mid slot → `WINGER`).
- **Validation mirrors the server:** the client uses `FORMATION_SHAPE` to block illegal moves (only a GK-position player in the GK slot; each line matches the formation counts); the authoritative guard remains `setLineup` (shape-agnostic today, but it enforces player count, GK count, ownership, eligibility, roles, and no youth prospects).
- **Persistence:** **"Salvar escalação"** commits via the existing `setLineup` mutation and invalidates `['squad', …]`. The existing mentality/pressing/marking and set-piece-taker controls stay; only the checkbox+role grid is replaced by the pitch.

## 10. Phasing (each phase = one implementation plan, executed subagent-driven)

- **Phase 1 — Foundation + formations.**
  Backend: add the 10 new `Formation` enum entries (codes + multipliers) + tests.
  Frontend: `pitchSlots` for all 16, `kit.ts` helpers, `Pitch` + `PlayerToken` components; reconcile `FORMATIONS`/`FORMATION_SHAPE` to all 16; render the pitch **read-only** on the Squad screen from the saved lineup.
  *Ships:* your formation drawn on a pitch + 16 formations selectable.
- **Phase 2 — Live-match pitch.**
  Backend: extend `LiveMatchState` (opponent XI + both kits + both formations) + IT.
  Frontend: both-teams `Pitch` atop `LiveMatchPage`, event-driven badges/animations; opponent shown as numbers only.
  *Ships:* the CM-style live pitch.
- **Phase 3 — Interactive editor.**
  Frontend: tap-select + desktop drag, formation-driven slots with role auto-assignment, bench strip, client validation, **Salvar** via `setLineup`.
  *Ships:* build your XI on the pitch.

## 11. Testing strategy

- **Foundation:** `pitchSlots` — every one of the 16 formations returns exactly 11 slots, line counts equal its shape, coords in `[0,1]` and non-overlapping. `kit.ts` — `readableTextColor` luminance boundaries, `kitsClash` true/false pairs, `resolveKits` swaps away on clash. `Pitch`/`PlayerToken` RTL — 11 tokens render, kit colours applied, badges from props. Backend — `Formation` enum has all 16 with valid multipliers; `TeamStrengthCalculator` cases for new multipliers; unknown code still falls back to 4-4-2.
- **Live-match:** IT — `LiveMatchState` carries opponent XI + both kits + both formations after kickoff→advance. RTL — goal badge on scorer, card badge, red card removes token, substitution swaps token, opponent mirrors into the top half.
- **Editor:** RTL — tap-select-then-tap-slot swaps two players; illegal drop (outfielder→GK) rejected; changing formation re-lays slots; **Salvar** calls `setLineup` with the correct payload; `['squad', …]` invalidated.
- **Determinism:** no `LiveMatchEngine`/RNG/`FixtureSimulator` draw-order changes anywhere; new multipliers only feed the existing (already-tested) strength path.
- **Deploy guard:** every frontend commit stays green under `tsc -b` (it type-checks `.test.tsx`), not just vitest.

## 12. Non-goals (YAGNI)

- **No animated player movement or ball physics.** The engine emits events, not trajectories; faking movement would be misleading and is out of scope.
- **No real squad numbers** — tokens use a stable slot-derived 1–11 for now.
- **No new tactical mechanics** from the extra formations beyond their attack/defence multipliers + layout; the engine's position model (GK/DEF/MID/ATT + role) is unchanged.
- **No drag-reordering of the bench**, no formation presets/saved tactics beyond what exists.
- **No opponent player names** on the pitch (numbers only) — keeps it readable and avoids leaking more than the commentary already does.

## 13. Open items to resolve during planning

- Exact `pitchSlots` coordinates for all 16 (authored in Phase 1; must look right on both portrait mobile and wider screens).
- Final `kitsClash` threshold + whether a home-vs-home dark clash ever needs the *home* side to change (default: only the away side changes).
- Where the opponent formation code comes from for the AI side (tactics schedule vs a sensible default) — confirm against `LiveMatchService` when Phase 2 is planned.
