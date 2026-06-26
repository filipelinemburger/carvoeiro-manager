# Phase 2A — Tactics & Match Depth — Design Spec

**Status:** Approved (brainstorming) — ready for implementation plan.

**Where this fits:** Phase 0 + Phase 1 are complete and merged to `main` (single
competition, single season, pick XI → simulate → league table). Phase 2 ("Classic
Elifoot") was decomposed into five sub-projects: **2A Tactics & match depth**,
2B finances, 2C transfer market, 2D multiple competitions, 2E multi-season careers.
This spec covers **2A only**, the first slice.

## Goal

Deepen the existing match loop with a full tactics board, per-player in-season
state (form), in-match fitness (stamina decay), and card-driven suspensions —
all feeding the deterministic match engine without breaking its reproducibility
invariant.

## Architecture (decision: "Approach A")

A new `TeamStrengthCalculator` folds an XI plus its `TacticalProfile` into an
enriched `TeamMatchInput`. The extended `MatchSimulationService.simulate(...)`
consumes two `TeamMatchInput`s and a seed and produces a `SimulatedMatch` with
typed events (open-play goal, set-piece goal, yellow, red). The engine remains a
single pure function seeded from `saveGameId ^ fixtureId ^ round`; **all new
randomness is derived from that one `Random(seed)` in a fixed order.** Two clean,
independently testable units: tactics→input, and input→match.

## Tech stack

No new dependencies. Spring Boot 4.0.7 / Java 25 / Gradle 9.6.1 backend; Flyway
migration V3; JPA entities; React 19 + TS strict + Vite + TanStack Query frontend;
JUnit 5 + Testcontainers (Postgres) + AssertJ; Vitest + React Testing Library.

## Global constraints (bind every task)

- **Determinism:** no `Math.random`, no clock, no `UUID.randomUUID()` inside the
  simulation path's RNG. Every stochastic outcome (goals, scorer, set-piece vs
  open-play, cards, carded player) is drawn from the single `Random(seed)` created
  in `MatchSimulationService.simulate`, in a fixed, documented order. Re-running
  the same round with the same pre-match state yields identical results.
- **Layering:** Controller → Service → Repository. `TeamStrengthCalculator` and
  `MatchSimulationService` are pure domain services (no repository access).
- **Money/IDs/time:** UUID ids; `Instant`/`LocalDate` (no `java.util.Date`); not
  relevant to 2A but keep the conventions.
- **Enums with behavior:** `Mentality`, `Pressing`, `Marking`, `Formation`,
  `PlayerRole`, `EventType` carry their modifier values as fields/methods. This
  also resolves the Phase-1 deferral "String type columns → enums" for the columns
  introduced here. `match_events.type` and `competitions.type` remain `VARCHAR`
  at the DB level; the entity exposes an `EventType` enum mapping for the new
  values (existing `GOAL` rows stay valid).
- **Ownership:** every new/changed endpoint goes through
  `SaveGameService.requireOwned` (and `LineupService.requireOwnedClub` for club
  scoping), returning `NOT_FOUND` for foreign/missing resources.
- **Validation error shape:** reuse the existing `ApiException` /
  `INVALID_LINEUP` / `VALIDATION_ERROR` conventions and precise status codes
  (422 for semantic lineup/tactics errors, 404 for ownership).
- **Coverage:** ≥80%; Given/When/Then; descriptive test names.

---

## 1. Domain model & enums

All numeric modifiers below are the **chosen defaults** for the plan (tunable
constants, defined once, not magic numbers scattered in code).

### `Mentality` (enum)
| value | attackMult | defenseMult |
|------|-----------|------------|
| DEFENSIVE | 0.88 | 1.12 |
| BALANCED | 1.00 | 1.00 |
| ATTACKING | 1.12 | 0.90 |

### `Pressing` (enum)
| value | tempoMult | aggressionAdd | decayMult |
|------|-----------|--------------|-----------|
| LOW | 0.90 | 0.0 | 0.85 |
| MEDIUM | 1.00 | 0.0 | 1.00 |
| HIGH | 1.12 | +0.50 | 1.25 |

### `Marking` (enum)
| value | defenseMult | aggressionAdd |
|------|------------|--------------|
| ZONAL | 1.00 | 0.0 |
| MAN_TIGHT | 1.05 | +0.50 |

### `Formation` (enum) — slot counts (DEF/MID/ATT, excluding GK) + bias
| value | DEF | MID | ATT | attackMult | defenseMult |
|------|----|----|----|-----------|------------|
| F_4_4_2 | 4 | 4 | 2 | 1.00 | 1.00 |
| F_4_3_3 | 4 | 3 | 3 | 1.10 | 0.94 |
| F_3_5_2 | 3 | 5 | 2 | 1.06 | 0.96 |
| F_5_3_2 | 5 | 3 | 2 | 0.92 | 1.10 |
| F_4_5_1 | 4 | 5 | 1 | 0.96 | 1.06 |
| F_4_2_3_1 | 4 | 5 | 1 | 1.04 | 1.00 |

Every starter has exactly 1 GK; the remaining 10 fill DEF+MID+ATT. The slot
counts are advisory for the UI and for AI role assignment; the engine does not
hard-reject an XI whose positions don't match the formation (positions in the
seed are coarse GK/DEF/MID/ATT). Formation contributes only via its
attack/defense multipliers in 2A.

### `PlayerRole` (enum) — attribute weighting per position band
Each role defines how a player's attributes roll up into their **attack
contribution** and **defense contribution** (weights applied to the 0–99
attributes, then the per-player form multiplier). Roles are restricted to the
player's `Position` band; an invalid role for a position is a 422.

| role | band | attack weights | defense weights |
|------|------|----------------|-----------------|
| GOALKEEPER | GK | finishing·0 | goalkeeping·1.0 |
| STOPPER | DEF | finishing·0.1 + pace·0.1 | tackling·1.0 |
| FULL_BACK | DEF | pace·0.3 + passing·0.2 | tackling·0.8 |
| BALL_PLAYING_DEF | DEF | passing·0.4 + pace·0.1 | tackling·0.85 |
| HOLDING_MID | MID | passing·0.3 | tackling·0.8 |
| BOX_TO_BOX | MID | finishing·0.3 + passing·0.3 + pace·0.2 | tackling·0.5 |
| PLAYMAKER | MID | passing·0.6 + finishing·0.2 | tackling·0.2 |
| POACHER | ATT | finishing·0.8 + pace·0.2 | tackling·0.0 |
| TARGET_MAN | ATT | finishing·0.6 + passing·0.2 | tackling·0.1 |
| WINGER | ATT | pace·0.6 + finishing·0.2 + passing·0.2 | tackling·0.05 |

Default role per band (used by AI and when a user leaves a starter's role unset):
GK→GOALKEEPER, DEF→FULL_BACK, MID→BOX_TO_BOX, ATT→POACHER.

**Role resolution rule (safety):** wherever a player's effective role is needed,
resolve it as *the stored/selected role if it is valid for that player's position
band, otherwise the band default*. This protects against migrated
`lineup_players` rows (which carry the column default `BOX_TO_BOX` regardless of
position — e.g. a GK) and any mismatch.

### `EventType` (enum)
`GOAL` (open-play), `SET_PIECE_GOAL`, `YELLOW_CARD`, `RED_CARD`. Maps to the
`match_events.type` VARCHAR.

### `TacticalProfile` (value object, not persisted directly)
`mentality`, `formation`, `pressing`, `marking`, `Map<UUID,PlayerRole> roles`
(per starter), `penaltyTakerId`, `freekickTakerId`, `cornerTakerId` (each
nullable; null → auto-pick best available in the XI).

### `TeamMatchInput` (record)
`attack`, `defense`, `tempo`, `aggression`, `setPieceThreat`, `staminaAvg`
(all `double`).

---

## 2. Data model — Flyway `V3__tactics.sql`

Additive only, with defaults so existing saves migrate cleanly.

```sql
ALTER TABLE players
  ADD COLUMN form                 INT NOT NULL DEFAULT 0,    -- clamped [-5,5]
  ADD COLUMN yellow_cards         INT NOT NULL DEFAULT 0,    -- season running total
  ADD COLUMN suspension_remaining INT NOT NULL DEFAULT 0;    -- matches still to sit out

ALTER TABLE lineups
  ADD COLUMN mentality        VARCHAR(12) NOT NULL DEFAULT 'BALANCED',
  ADD COLUMN pressing         VARCHAR(8)  NOT NULL DEFAULT 'MEDIUM',
  ADD COLUMN marking          VARCHAR(10) NOT NULL DEFAULT 'ZONAL',
  ADD COLUMN penalty_taker_id  UUID NULL REFERENCES players(id),
  ADD COLUMN freekick_taker_id UUID NULL REFERENCES players(id),
  ADD COLUMN corner_taker_id   UUID NULL REFERENCES players(id);

ALTER TABLE lineup_players
  ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'BOX_TO_BOX';
```

**No persistent `fitness` column.** Fitness is an in-match effect only (see §4),
recomputed from stamina + pressing each match and reset every match — a
persistent fitness/recovery column would be near-useless in a weekly single
competition. Persistent between-match fitness arrives in slice 2D.

JPA entities `Player`, `Lineup`, `LineupPlayer` gain the new fields. `Player`
exposes `form`/`yellowCards`/`suspensionRemaining` getters and mutation methods
(`applyForm(int delta)` clamping to [-5,5], `addYellow()`, `serveSuspension()`,
`isEligible()`); `Lineup` exposes the tactics fields via the `Mentality`/
`Pressing`/`Marking` enums; `LineupPlayer` exposes `PlayerRole`.

---

## 3. Tactics → input: `TeamStrengthCalculator`

Pure domain service: `TeamMatchInput compute(List<Player> xi, TacticalProfile
profile)`. Pipeline:

1. **Per-player contribution.** For each starter, look up its `PlayerRole`
   (from the profile, or default for its band). Compute
   `attackContribution = Σ(weight·attribute)` and
   `defenseContribution = Σ(weight·attribute)` using the role's weight tables,
   each multiplied by the player's **form multiplier** `(1 + 0.02·form)`.
2. **Roll-up.**
   `attack = mean(attackContribution over the 11)`.
   `defense = 0.6·mean(defenseContribution over the 10 outfield) + 0.4·(GK's
   goalkeeping · GK form multiplier)` — preserves the Phase-1 defensive shape,
   now role-weighted.
3. **Formation bias:** `attack *= formation.attackMult`, `defense *=
   formation.defenseMult`.
4. **Mentality:** `attack *= mentality.attackMult`, `defense *=
   mentality.defenseMult`.
5. **Marking:** `defense *= marking.defenseMult`.
6. **tempo** `= pressing.tempoMult`.
7. **aggression** `= 1.0 + pressing.aggressionAdd + marking.aggressionAdd`
   (baseline 1.0; HIGH press + MAN_TIGHT → 2.0).
8. **setPieceThreat** = normalized quality of the penalty taker (its
   `finishing`), or best finishing in the XI if unset, scaled to ~[0,1]
   (`finishing/99`).
9. **staminaAvg** = mean `stamina` of the 11.

---

## 4. Input → match: `MatchSimulationService` (extended)

Signature changes from `simulate(LineupStrength, LineupStrength, long)` to
`simulate(TeamMatchInput home, TeamMatchInput away, long seed)`. One
`Random(seed)`; draw order fixed and documented as:
per minute m=1..90 → [home goal check, away goal check]; then for each goal in
order → [set-piece vs open-play, scorer pick]; then per minute → [home card
check, away card check, carded-player pick]. (Concretely the code performs goal
checks in the minute loop and resolves scorer/cards within the same pass, in a
single deterministic sequence; the plan pins the exact ordering with a test that
asserts identical output for a fixed seed.)

- **Home advantage:** keep `+5.0` applied to attack (as Phase 1).
- **In-match fitness:** `fitnessFactor(input, m) = max(0.75, 1 − decay·(m/90)²)`
  where `decay = 0.15 · pressing.decayMult · clamp(70/staminaAvg, 0.7, 1.4)`.
  Each minute, `effAttack = attack · fitnessFactor`, `effDefense = defense ·
  fitnessFactor`. High press + low stamina → stronger early, weaker late.
- **Scoring chance** per minute (per side): same `perMinuteScoreChance(effAtt +
  homeAdv, oppEffDef)` mapping as Phase 1, multiplied by the combined tempo
  factor `(home.tempo + away.tempo)/2`.
- **Goal type:** for each goal, with probability `SET_PIECE_SHARE=0.18` adjusted
  by the scoring team's `setPieceThreat` it is a `SET_PIECE_GOAL` attributed to
  the designated penalty/freekick taker (or auto best); otherwise open-play
  `GOAL` with the existing finishing-weighted scorer pick.
- **Cards:** per minute per side, foul→card probability
  `= BASE_CARD_RATE · aggression` with `BASE_CARD_RATE` tuned so a BALANCED
  match averages ≈ 3–4 yellows total (per-minute ≈ 0.02 × aggression split
  across both sides; pinned by a statistical test over many seeds). A carded
  event is a `RED_CARD` with probability `RED_SHARE=0.06`, else `YELLOW_CARD`.
  The carded player is drawn weighted toward higher `tackling` (defenders/
  holders foul more).

`SimulatedMatch` carries `homeGoals`, `awayGoals`, and a `List<SimulatedEvent>`
(minute, EventType, home/away side) so the round service can persist typed
`MatchEvent`s and update player state.

---

## 5. Form, fitness, cards/suspensions — round lifecycle

In `RoundSimulationService.simulateNextRound` (extended), ordered:

1. **Eligibility.** A player with `suspension_remaining > 0` cannot appear in any
   XI. The managed club's XI is validated at set-lineup time *and* re-checked at
   sim time; AI `bestXi` filters suspended players out before picking.
2. **Build tactics.** Managed club: load its persisted `TacticalProfile` from the
   `lineups` row (+ `lineup_players.role`, + taker ids). If the managed club has
   **no lineup row yet** (fresh save), use the default profile (§6) over its
   `bestXi`, exactly like an AI club. AI clubs: compute the default profile (§6).
   Build `TeamMatchInput` for both via `TeamStrengthCalculator`.
3. **Simulate** each fixture → `SimulatedMatch`; persist score (`recordResult`)
   and typed `MatchEvent`s.
4. **Apply cards** to players who played: `YELLOW_CARD` → `yellow_cards++`, and
   if `yellow_cards % 3 == 0` → `suspension_remaining += 1`; `RED_CARD` →
   `suspension_remaining += 1`. (A red causes the next-match ban but does **not**
   reduce the team to 10 men for the rest of the current match — deferred.)
5. **Apply form** (§ rule below) to every player, based on whether they played
   and the match facts.
6. **Decrement suspensions:** for every player who entered this round with
   `suspension_remaining > 0` (i.e. sat out), `suspension_remaining -= 1`.
   Players newly banned in step 4 played this round, so they begin serving next
   round (no decrement now). With 10 clubs everyone plays every round, so a
   1-match ban = exactly the next round.
7. Persist player state (`saveAll`).

**Form update rule** (deterministic; reads match facts, no RNG):
- *Played:* `delta = min(2, goalsScored) + (won ? +1 : lost ? −1 : 0) +
  (GK/DEF: conceded==0 ? +1 : conceded>=3 ? −1 : 0)`; `applyForm(delta)` (clamp
  [-5,5]).
- *Did not play* (not in XI this round): regress toward 0 by 1.

**Selection guard** (`LineupService.setLineup`, extended): reject if any starter
is suspended (`SUSPENDED_PLAYER`, 422), if a role is invalid for a player's
position (`INVALID_ROLE`, 422), keeping the existing 11-players / exactly-1-GK /
all-belong-to-club rules.

---

## 6. AI tactics

`AiTacticsService` (or a method on the round service) builds a default
`TacticalProfile` for an AI club at sim time — pure function of the squad, no
RNG, not persisted:
- mentality BALANCED, pressing MEDIUM, marking ZONAL.
- formation `F_4_4_2`.
- roles: default per position band for each starter.
- penalty/corner takers = highest `finishing` in XI; freekick taker = highest
  `passing` in XI (among eligible).
- XI = suspension-aware `bestXi` (best GK + best 10 outfield by overall,
  excluding suspended).

Opponent-reactive AI is deferred.

---

## 7. API contracts

All under `/api/v1/save-games/{saveGameId}/clubs/{clubId}`, owner-guarded.

### `PUT .../lineup` (extended)
Request adds per-starter roles:
```
SetLineupRequest {
  formation: String,                 // a Formation enum value
  starters: [ { playerId: UUID, role: String } ]   // exactly 11
}
```
(Replaces the old `startingPlayerIds: [UUID]`. Frontend updated accordingly.)
Validation: 11 starters, exactly 1 GK, all belong to club, none suspended, each
role valid for its position. Response `LineupResponse` echoes formation + the
starters with roles.

### `PUT .../tactics` (new)
```
SetTacticsRequest {
  mentality: String, pressing: String, marking: String,
  penaltyTakerId: UUID|null, freekickTakerId: UUID|null, cornerTakerId: UUID|null
}
```
Taker ids (when non-null) must be in the current XI → else 422 `INVALID_TACTICS`.
Persists onto the `lineups` row. Response `TacticsResponse` echoes the saved
values. Requires a lineup to exist (else 422 `LINEUP_REQUIRED`).

### `GET .../tactics` (new)
Returns the current `TacticsResponse` (defaults if unset).

### `GET .../squad` (extended)
`PlayerResponse` gains `form`, `yellowCards`, `suspensionRemaining`,
`eligible` (bool), and `role` (the player's role in the current lineup, or null
if not a starter). The existing `stamina` field doubles as the will-fade /
endurance indicator — no separate field.

### `POST .../advance` (round simulation, extended)
`SimulateRoundResponse` gains, for the managed club:
`bookings: [{ playerId, playerName, type }]` and
`newSuspensions: [{ playerId, playerName, matches }]`.

---

## 8. Frontend

- **Squad page** (`features/squad`): add per-starter **role** dropdown (options
  limited to the player's position band); show **form** (↑/↓ + value),
  **suspension** badge (suspended → not selectable), **endurance** indicator.
- **Tactics panel** (on the squad page or a sibling tab): selectors for
  mentality, formation, pressing, marking; set-piece taker dropdowns populated
  from the current XI. Persists via `PUT .../tactics`; prefilled via
  `GET .../tactics`.
- **Advance results** (`features/match`): render bookings and new suspensions
  alongside the round's scores.
- Typed API client + types updated for the new request/response shapes.

---

## 9. Testing strategy (≥80%, Given/When/Then)

**Backend unit:**
- `TeamStrengthCalculator`: each lever isolated — mentality shifts attack/
  defense in the right direction; formation bias ordering (4-3-3 > 4-4-2 > 5-3-2
  on attack); role weights (POACHER XI out-attacks HOLDING XI of same players);
  pressing raises tempo + aggression; MAN_TIGHT raises defense + aggression;
  set-piece threat tracks the taker's finishing; form multiplier raises a
  high-form player's contribution.
- `MatchSimulationService`: same seed → byte-identical `SimulatedMatch`
  (goals + ordered events); high-press/low-stamina side has a lower 2nd-half vs
  1st-half effective output / goal share (statistical over many seeds);
  set-piece goals are attributed to the designated taker; card rate scales with
  aggression (BALANCED ≈ 3–4 yellows avg; HIGH+MAN_TIGHT measurably more);
  reds occur and are typed `RED_CARD`.

**Backend integration (Testcontainers):**
- Suspension lifecycle: simulate until a player reaches the 3rd yellow → becomes
  ineligible the next round → set-lineup rejects them (422) → AI/`bestXi`
  excludes them → after serving, `suspension_remaining==0` and eligible again.
- Red-card path: red → `suspension_remaining==1` next round.
- Form lifecycle: scorer/winner form rises, loser falls, non-playing regresses,
  clamps at ±5.
- Endpoints: `PUT .../tactics` (valid; invalid taker not in XI → 422; no lineup →
  422); `PUT .../lineup` with roles (valid; invalid role for position → 422;
  suspended starter → 422); `GET .../tactics` and `GET .../squad` shapes;
  `POST .../advance` surfaces bookings + new suspensions; ownership guards (foreign
  user → 404) on all new endpoints.
- V3 migration applies; a pre-V3-style save (defaults) loads and simulates.

**Frontend (Vitest/RTL):**
- Role dropdown shows only valid roles for a player's position; tactics
  selectors persist (mocked API) and prefill from GET; suspended player cannot be
  selected into the XI; form / suspension / endurance badges render; advance
  results show bookings + suspensions.

---

## 10. Determinism, migration & deferred items

- **Determinism preserved**; the Phase-1 engine's exact scorelines change because
  the model changed — the engine's deterministic tests are **rebaselined** in this
  slice (same-seed-stability is the invariant, not the specific old scores).
- **Migration:** V3 is additive with defaults; existing saves get neutral form,
  zero cards, BALANCED/MEDIUM/ZONAL tactics, default roles, null takers.
- **Deferred (out of scope for 2A):** persistent between-match fitness/recovery
  (2D); in-match reduction to 10 men after a red card; opponent-reactive AI
  tactics; per-player bench persistence and substitutions; set-piece *types*
  beyond a single threat channel.

## Acceptance

Playing a Criciúma season, the user can: set a full tactical profile (mentality,
formation, pressing, marking, set-piece takers) and per-starter roles; see those
choices change match outcomes; watch players gain/lose form; see bookings pile up
and players miss matches through suspension; and feel pressing as a genuine
risk/reward (more chances, harder late fade, more cards). All deterministic, all
tested ≥80%.
