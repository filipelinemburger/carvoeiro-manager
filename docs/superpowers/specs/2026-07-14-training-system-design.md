# Training System — Design

**Date:** 2026-07-14
**Status:** Approved

## Goal

Let the manager influence player development instead of it being purely automatic: assign each
graduated (non-prospect) player a training focus attribute and an intensity, which biases their
existing season-end attribute change in that attribute's favor — at the cost of a small,
intensity-scaled injury risk.

This is the first of three gaps identified against the original Phase 3 vision in
`docs/superpowers/specs/2026-06-26-carvoeiro-manager-design.md` ("training schedules improving
attributes over time"). It ships before Staff and before Promotion/Relegation.

## Background

- The game already has a **passive** progression system with no manager input today:
  `PlayerAgingService.applyAgeCurve` (`backend/src/main/java/br/com/carvoeiromanager/player/PlayerAgingService.java:22-39`)
  applies a random per-attribute delta to the 6 "anchor" attributes of every **non-prospect**
  player, once per season, from `SeasonRolloverService.rollover`
  (`backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java:220-221`).
  The delta is age-banded (`delta(age, rng)`, lines 52-57): ≤21 trends up, 22-29 is roughly flat,
  30-32 trends down, 33+ declines, plus an extra pace/stamina penalty at 33+.
- **Youth prospects** (`youthProspect == true`) do not go through `applyAgeCurve` at all — they
  instead grow toward a hidden `potential` ceiling via `YouthDevelopmentService.develop`
  (`backend/src/main/java/br/com/carvoeiromanager/player/YouthDevelopmentService.java:34-55`),
  called from `SeasonRolloverService.rollover:214`. This pass does not touch that path — training
  controls apply to graduated players only.
- The 6 anchor attributes (`PACE`→`VELOCIDADE`, `FINALIZACAO`, `PASSE`, `DESARME`, `REFLEXOS`,
  `FOLEGO`) are the only ones any post-creation mutation ever touches, via
  `Player.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall)`
  (`backend/src/main/java/br/com/carvoeiromanager/player/Player.java:232-242`). The other 22
  attributes are set once at creation by `AttributeDeriver` and never move again — out of scope
  for training in this pass.
- `Player` already has an injury mechanism usable for the intensity trade-off:
  `addInjury(int matches)` (`Player.java:204`) adds to `injuryMatches`, which
  `MatchOutcomeApplier`/`tickInjury` already drain match-by-match during the season.
- `SeasonRolloverService.rollover` consumes a single shared, seeded `Random` in a fixed order
  per player so a rollover is reproducible (per `PlayerAgingService`'s class javadoc) — any new
  per-player randomness must consume from that same `rng`, in the same fixed position each time,
  to preserve that guarantee.
- `PlayerResponse` (`backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`)
  already carries the full attribute board consumed as-is by `SquadPage.tsx` and
  `PlayerDetails.tsx` — no separate fetch is needed to display current attribute values.
- `SquadPage.tsx` already has an established "map of `playerId -> chosen value`, hydrated once
  from the server, edited via per-row selects, committed via one bulk-save mutation" pattern
  (`roles` state, `changeRole`, the `setLineup` mutation) — this is the template the new Treino
  tab reuses.

## Backend changes

**Migration `V26__player_training.sql`** — adds two nullable/defaulted columns to `players`:
- `training_focus VARCHAR` — nullable. Stores an `Attribute` enum name. The service layer
  restricts accepted values to the 6 anchors (`VELOCIDADE`, `FINALIZACAO`, `PASSE`, `DESARME`,
  `REFLEXOS`, `FOLEGO`); anything else is rejected at the API boundary. `null` means "not
  training" — the player's rollover is unaffected, identical to today's behavior.
- `training_intensity VARCHAR NOT NULL DEFAULT 'NORMAL'` — a new `TrainingIntensity` enum with
  three values: `LEVE`, `NORMAL`, `INTENSA`.

Every existing row backfills to `training_focus = null`, `training_intensity = 'NORMAL'`, so
this ships as a strict opt-in: no player's rollover outcome changes until the manager explicitly
sets a focus for them. The assignment persists across seasons until the manager changes it again
— a standing instruction, not a one-shot pick made at each rollover.

`Player` gains `trainingFocus` (nullable `Attribute`), `trainingIntensity` (`TrainingIntensity`,
default `NORMAL`), their getters, and `setTrainingAssignment(Attribute focus, TrainingIntensity
intensity)`.

**New `TrainingService`** (`@Component`, same shape as `PlayerAgingService`), method
`applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng)`:

- No-op if `focus == null`.
- Otherwise: read the player's current value for that one anchor attribute (post-aging, since
  this runs after `applyAgeCurve`), add a flat bonus by intensity (`LEVE +1, NORMAL +2, INTENSA
  +3`), clamp to `[1, 99]`, recompute `overall` via `OverallCalculator`, and commit all 6 anchor
  values (5 unchanged + 1 bumped) through `player.evolveAttributes(...)` — mirroring exactly how
  `PlayerAgingService.applyAgeCurve` already commits its own result.
- Injury roll, scaled by intensity (`LEVE 0%, NORMAL 3%, INTENSA 8%`): if
  `rng.nextDouble() < risk`, call `player.addInjury(1 + rng.nextInt(2))` (1–2 matches out).

`SeasonRolloverService.rollover`'s existing non-prospect per-player loop
(`SeasonRolloverService.java:220-221`) calls `trainingService.applyTraining(...)` immediately
after `playerAgingService.applyAgeCurve(...)`, consuming the same shared `rng`, with the focus
and intensity resolved as follows:

- **Managed club's players**: `focus = player.getTrainingFocus()`, `intensity =
  player.getTrainingIntensity()` — whatever the manager last set (or `null`/`NORMAL` defaults if
  never touched).
- **Every other (AI-controlled) club's players**: focus and intensity are never read from the
  database — they're computed fresh each rollover by a fixed heuristic,
  `TrainingService.heuristicFocusFor(Position)`: `GK→REFLEXOS, DEF→DESARME, MID→PASSE,
  ATT→FINALIZACAO`, always at `NORMAL` intensity. This gives every AI squad the same steady,
  deterministic improvement a human who trains moderately would get, without adding any new
  manager-facing surface for clubs the user doesn't control.

**Known test-fixture consequence:** AI clubs' players will now always receive the heuristic's
training bump each rollover (they have no way to opt out), so any existing rollover test that
asserts exact post-season attribute values for AI-club players will need its expected values
updated. This must be audited during implementation, not deferred.

**`PlayerResponse`** gains `trainingFocus: String?` and `trainingIntensity: String` fields, so
the existing squad fetch (`getSquad`) already hydrates the Treino tab — no new GET endpoint.

**New endpoint** — `PUT /api/v1/save-games/{saveGameId}/clubs/{clubId}/training`, request body a
list of `{playerId: UUID, focus: String?, intensity: String}`, applied in one transaction to the
managed club's own players only (same ownership check shape as `setLineup`). Response is the
updated `SquadResponse` (so the frontend can just replace its cached squad query result, matching
`setLineup`'s existing response shape).

## Frontend changes

**New "Treino" tab** on `SquadPage.tsx`, alongside the existing "Plantel" / "Táticas de jogo"
tabs. One row per (non-prospect) squad player: name, position, two `NativeSelect`s — Foco (the 6
anchor attributes, pt-BR labels already defined on `Attribute`, plus a "Nenhum" option mapping to
`null`) and Intensidade (Leve/Normal/Intensa). State is a local `Record<string, {focus: string |
null, intensity: string}>` map keyed by player id, hydrated once from the squad query result (via
the new `trainingFocus`/`trainingIntensity` fields on each player), edited per-row, and committed
via a single bulk-save mutation calling the new `PUT .../training` endpoint — same
hydrate-once/local-edit/bulk-save/snackbar-feedback shape as the existing lineup-assignment flow.
Youth prospects are excluded from this tab's rows entirely (they have no training controls in
this pass).

No changes to `PlayerDetails.tsx` — training assignment is a squad-wide bulk action, not a
per-player detail view, so it doesn't follow the Atributos/Contrato/Estatísticas per-tab-fetch
pattern those use.

## Testing

- **Backend unit:** `TrainingServiceTest` mirroring `PlayerAgingServiceTest`'s style — bonus math
  per intensity tier, clamping at 99, no-op when focus is `null`, injury-roll boundaries
  (deterministic with a fixed-seed `Random`), and the AI heuristic's position→attribute mapping.
- **Backend integration:** a `SeasonRolloverService` test covering three players in one rollover
  — a managed-club player with an explicit focus (attribute increases beyond the plain age-curve
  delta), a managed-club player with no focus set (byte-for-byte identical to pre-training
  behavior), and an AI-club player (heuristic focus applied automatically). A
  `PlayerControllerIT`/new controller IT for the `PUT .../training` endpoint covering a
  successful bulk update and a rejected out-of-range `focus` value.
- **Frontend:** RTL coverage for the Treino tab's hydrate/edit/save cycle (select a focus and
  intensity for a player, save, confirm the mutation payload and the success snackbar).
- Existing `SeasonRolloverService`/rollover-related tests audited and updated per the AI-heuristic
  consequence noted above.
- Full backend suite (`./gradlew test`) and full frontend suite
  (`npx tsc --noEmit && npx vitest run`) green, per project convention.

## Out of scope

- Youth prospects — no training controls for them in this pass; they keep
  `YouthDevelopmentService`'s existing potential-closing behavior untouched.
- The other 22 (non-anchor) attributes — training only ever moves the same 6 anchors the
  existing aging system already touches.
- Any UI surfacing of *why* an attribute changed (e.g. a rollover results/highlights feed
  distinguishing "aging" from "training" deltas) — the manager sees the net result reflected in
  the squad/player details after rollover, same as aging already works today.
- Position-based restriction of which focus options the UI offers per player (e.g. hiding
  `REFLEXOS` for outfield players) — all 6 anchors are selectable for every player.
