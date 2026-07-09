# Distinct Set-Piece Events — Design

**Date:** 2026-07-09
**Status:** Approved (brainstormed with user)

## Context

Both match engines treat set pieces as one blended bucket: a scoring event has an
18%-share chance (`SET_PIECE_SHARE`) of being a `SET_PIECE_GOAL`, the team's "set-piece
threat" is the **penalty taker's finishing**, and the scorer is `penaltyTakerId` else
`freekickTakerId`. The saved `cornerTakerId` is dead code (stored, validated, UI-exposed,
never simulated), and the attributes Bola parada (`BOLA_PARADA`), Cruzamento
(`CRUZAMENTO`), Cabeceio (`CABECEIO`) exist on every player but are unused by simulation.

This feature splits the bucket into **corners, free kicks, and penalties** with separate
probabilities, type-correct takers/scorers, a penalty **conversion roll** (penalties can
be missed), and type-specific pt-BR commentary. Applies to BOTH engines (`LiveMatchEngine`
and `MatchSimulationService`/`FixtureSimulator`) with identical math, preserving their
determinism parity.

**User decisions:** approach A (typed subtypes with taker-skill-driven shares — not full
corner-by-corner event modeling); penalties get a conversion roll and can be missed.

## 1. Event model

`EventType` gains four values: `PENALTY_GOAL`, `FREE_KICK_GOAL`, `CORNER_GOAL`,
`PENALTY_MISSED`.

- `SET_PIECE_GOAL` **stays in the enum as a legacy value** — never emitted again, but old
  `match_events` rows and any old persisted payloads reference it by name, so every goal
  predicate and the top-scorers query must keep counting it.
- A missed penalty is a **single** `PENALTY_MISSED` event (no separate "awarded" marker);
  the commentary line carries the drama. It is NOT a goal: excluded from score tallies and
  from `match_events` persistence (`isCardOrGoal` stays goals+cards); it appears only in
  the live feed.
- No migration: `match_events.type` is `VARCHAR(20)` with no constraint; the longest new
  name (`FREE_KICK_GOAL`, `PENALTY_MISSED` — 14 chars) fits.

## 2. Probability model (identical in both engines)

The per-minute scoring chance is unchanged. The set-piece share formula keeps its shape
but its threat input changes:

- `cornerThreat` = corner taker's Cruzamento / 99 if the taker is set **and in the XI/on
  pitch**, else the best Cruzamento in the XI / 99.
- `freeKickThreat` = free-kick taker's Bola parada / 99 under the same rule, else the best
  Bola parada in the XI / 99.
- Overall set-piece share = `clamp(0.18 × (0.5 + (cornerThreat + freeKickThreat) / 2), 0.0, 0.6)`
  — same scale as today (`SET_PIECE_SHARE = 0.18` constant retained).

`TeamMatchInput.setPieceThreat` is **replaced** by two fields: `cornerThreat` and
`freeKickThreat` (computed in `TeamStrengthCalculator.compute`, which currently derives
`setPieceThreat` from the penalty taker's finishing — that logic is removed).

**Subtype roll** — when a set-piece goal fires, roll the subtype by effective weights:

| Subtype | Effective weight |
|---|---|
| CORNER | `50 × (0.5 + cornerThreat)` |
| FREE_KICK | `30 × (0.5 + freeKickThreat)` |
| PENALTY | `20` (fixed — penalties come from fouls, not taker skill) |

A great corner taker therefore both raises total set-piece output and shifts the mix
toward corners.

## 3. Per-type resolution

- **PENALTY:** taker = `penaltyTakerId` if on pitch, else the best finisher in the
  XI (highest `getFinishing()`, existing tiebreak conventions). Conversion chance =
  `0.70 + 0.25 × (taker's Bola parada / 99)` → range [0.70, 0.95], ~0.82 average.
  Converted → `PENALTY_GOAL` (scorer = taker); missed → `PENALTY_MISSED` (event's
  `playerId` = taker, no goal). The miss shaves total goals by well under 1%.
- **FREE_KICK (direct):** scorer = `freekickTakerId` if on pitch, else the player with
  the best Bola parada in the XI.
- **CORNER:** the taker assists, a header scores. Scorer picked by weighted random with
  `headerWeight(p) = positionFactor × (Cabeceio + 10)` where positionFactor is ATT 10 /
  DEF 8 / MID 4 / GK 0 (center-backs score corners; keepers never). The corner taker's id
  (same on-pitch-else-fallback rule, fallback = best Cruzamento) rides in the event's
  `playerInId` slot — currently used only by SUBSTITUTION; javadoc updated — so commentary
  can name both taker and scorer. If the scorer would equal the taker (taker is the best
  header option picked), that is acceptable and renders with the single-name fallback line.

**RNG discipline:** the subtype roll and the penalty conversion roll draw from the match
stream (`rng`); scorer/attribution picks draw from the attribution stream (`attrRng`),
exactly like today's goal attribution. Timelines remain pure functions of the config.
Existing saves' in-progress live matches will re-simulate under the new model after the
update — accepted (single-player game, deterministic per config either way).

## 4. Commentary (LiveCommentary) and frontend

New pt-BR variant arrays (deterministic pick, same `pick(...)` mechanism):

- `PENALTY_GOAL`: `"Pênalti! %s converte com categoria!"`, `"GOL DE PÊNALTI! %s não desperdiça!"`
- `PENALTY_MISSED`: `"Pênalti perdido! %s desperdiça — defesa do goleiro!"`, `"%s cobra o pênalti... e o goleiro defende!"`
- `FREE_KICK_GOAL`: `"GOL DE FALTA! %s acerta o ângulo!"`, `"Falta cobrada por %s... GOL!"`
- `CORNER_GOAL` (two names — playerName = scorer, inName = taker):
  `"Escanteio de %s, %s sobe e cabeceia: GOL!"` (formatted inName, playerName); when the
  taker is null or equals the scorer, single-name fallback `"GOL de escanteio! %s aparece na área e marca!"`

Frontend (only two files): extend the `LiveEvent['type']` union in `api/types.ts` and the
`EVENT_ICON` map in `LiveMatchPage.tsx` (`PENALTY_GOAL`/`FREE_KICK_GOAL`/`CORNER_GOAL` →
⚽, `PENALTY_MISSED` → 🧤). News/report/advance views do not branch on goal subtypes — no
other frontend change.

## 5. Consumer surface (every switch/predicate, extended explicitly)

| Consumer | Change |
|---|---|
| `LiveMatchEngine.isGoal`, `LiveTimeline.isGoal`, `LiveMatchService.isCardOrGoal` | count `PENALTY_GOAL`/`FREE_KICK_GOAL`/`CORNER_GOAL` (+ legacy `SET_PIECE_GOAL`); `PENALTY_MISSED` excluded |
| `TopScorersService` string list | `List.of("GOAL", "SET_PIECE_GOAL", "PENALTY_GOAL", "FREE_KICK_GOAL", "CORNER_GOAL")` |
| `PlayerRatingCalculator` | new goal types credit goals like today; `PENALTY_MISSED` applies a −0.5 rating malus to the taker. NOTE: the live rating path currently feeds only `isCardOrGoal` events to the calculator — it must additionally pass `PENALTY_MISSED` through to ratings (while still NOT persisting it to `match_events`) |
| `MatchOutcomeApplier` | new goal types grant the same form/morale as GOAL; `PENALTY_MISSED` grants nothing |
| `FixtureSimulator.saveEvents` exhaustive switch | cases for the three new goal types (type-correct attribution mirroring §3); `PENALTY_MISSED` never reaches it (not a card/goal — see below) |
| `LiveCommentary.of` exhaustive switch | new cases per §4 |
| Frontend `types.ts` union + `EVENT_ICON` | per §4 |

**Instant engine specifics:** `MatchSimulationService` rolls subtype and penalty
conversion inside its loop; a missed penalty simply produces no event there (the instant
path only carries goals/cards — the miss still realistically suppresses that goal from
league results). The engines' duplicated set-piece helpers change in lockstep.

The exhaustive Java switch expressions self-enforce at compile time. The three boolean
predicates and the `TopScorersService` string list fail SILENTLY if missed — each gets a
dedicated regression test.

**Interplay with AI manager reactions:** posture profile swaps preserve takers
(`AiManagerBrain.profileFor` copies identity takers) — no change needed.

## 6. Out of scope (explicit)

- No corner/free-kick "awarded" events or outcome chains — only goals (and penalty
  misses) reach the timeline; no per-set-piece chance events.
- No foul→penalty causal link (cards remain independent draws).
- No new attributes, no seed-data changes, no tactics-UI changes (the three taker
  dropdowns already exist in `TacticsPanel`).
- No assist tracking/stats beyond the corner taker riding in `playerInId` for commentary.

## 7. Testing

- **`TeamStrengthCalculatorTest`:** cornerThreat/freeKickThreat from set takers; fallback
  to best-in-XI when taker unset or not in the XI; setPieceThreat removal compiles away.
- **Both engines, subtype resolution:** seeded sweeps assert (a) all four new types occur
  across seeds and `SET_PIECE_GOAL` never does; (b) penalty taker on pitch scores every
  `PENALTY_GOAL`/is the `PENALTY_MISSED` actor; (c) FK taker scores `FREE_KICK_GOAL` when
  on pitch; (d) `CORNER_GOAL` scorer is never a GK and the event carries the taker in
  `playerInId`; (e) determinism — same config twice → identical timelines; (f) a
  substituted-off taker falls back correctly.
- **Conversion bounds:** with Bola parada 0 vs 99 the conversion chance is 0.70 / 0.95
  (unit-test the formula seam directly).
- **Silent-failure regressions:** persisted legacy `SET_PIECE_GOAL` row still counted by
  `TopScorersService`; new goal types persist via `isCardOrGoal` and count in
  `LiveTimeline` score; `PENALTY_MISSED` does not.
- **Ratings/outcome:** goal credit for new types; −0.5 malus on a miss; form/morale
  granted for new goal types.
- **Commentary:** exact strings per type, including the corner two-name and fallback
  lines.
- **Frontend:** type-check (`tsc --noEmit`) covers the union/icon exhaustiveness; existing
  live-match page tests stay green.
