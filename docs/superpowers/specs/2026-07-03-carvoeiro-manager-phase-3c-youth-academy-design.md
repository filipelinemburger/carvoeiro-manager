# Phase 3c — Youth Academy (Design)

**Status:** approved (2026-07-03), pending spec review

**Goal:** Give the managed club a youth pipeline — a pool of prospects that are generated each season,
develop toward a hidden potential ceiling, and can be promoted into the senior squad (or released).
Adds a "find and grow a gem" loop on top of the existing player-state systems (form/fitness/morale).

## Scope

**In scope**
- A **separate youth prospect pool** for the managed club: prospects are `Player` rows flagged
  `youth_prospect = true` and are **not match-eligible** until promoted.
- A hidden **potential** ceiling per prospect, surfaced to the UI only as a **1–5 star** band.
- **Intake** at each season rollover (top up the academy toward a target), **development** toward
  potential each season, and **departure** of prospects who reach age 20 unpromoted.
- **Promote** (any time → joins the senior squad on an auto youth contract) and **release**.
- A **Youth (Base)** screen with per-prospect info and Promote/Release actions.
- Managed-club retirees are **no longer auto-replaced** by a newgen (the academy is now the pipeline);
  **AI clubs keep** newgen-on-retirement exactly as today.

**Out of scope (YAGNI)**
- AI clubs having their own academy pool/UI (they keep newgen-on-retirement).
- Scouting inaccuracy (stars are a deterministic function of potential, always shown).
- Youth facilities / investment / youth wages beyond a simple auto contract; youth matches.
- A senior-squad cap (promotion just grows the squad; the user manages depth via transfers/releases).

## Model

Prospects reuse the `Player` entity so they inherit attributes, aging, overall and contracts.

### Data model (V19 migration)

Add two columns to `players`:
- `youth_prospect BOOLEAN NOT NULL DEFAULT false` — `true` while in the academy.
- `potential INT NOT NULL DEFAULT 0` — hidden ceiling overall (1–99); 0 means "not a tracked prospect"
  (all existing/senior players).

`Player` gains `isYouthProspect()`, `getPotential()`, `promoteFromYouth()` (flips the flag to false),
and the create path for a prospect sets both. A senior player's `potential` stays 0 and is never shown.

### Eligibility gating (prospects are invisible to match selection)

Everywhere the club's **selectable** players are loaded, filter `youth_prospect = false`:
- `LineupService.getSquad` (the squad screen shows seniors only; the academy has its own screen),
- `LineupService.bestXi` / `startingXi` (AI + live/instant selection),
- lineup validation (a prospect can never be named in an XI or on the bench).

This is the single invariant that keeps prospects out of matches until promoted. A dedicated repository
method (e.g. `findByClubIdAndYouthProspectFalse`) backs the senior-squad queries; the academy uses
`findByClubIdAndYouthProspectTrue`.

### Potential and stars

- On generation, `potential = min(POTENTIAL_CAP, currentOverall + headroom)` where `headroom` is drawn
  from a low-skewed distribution (most prospects modest, a few gems). `POTENTIAL_CAP ≈ 92`.
- `potentialStars(potential)` → 1–5, deterministic band: `≥85 → 5 · 78–84 → 4 · 70–77 → 3 · 62–69 → 2
  · <62 → 1`. Only the star count reaches the frontend — never the raw `potential`.

### Generation (intake) — `YouthIntakeService`

At each season rollover, after the existing aging/retirement pass, top up the managed club's academy to
`ACADEMY_TARGET` (≈10) by generating up to `MAX_INTAKE_PER_SEASON` (≈3) new prospects. Each prospect:
reuses `NewgenGenerator`'s position-appropriate attribute draw but age **15–17**, `youth_prospect = true`,
`contractYears = 0`, and an assigned hidden `potential`. Deterministic via the seeded rollover RNG (drawn
in a fixed order after the existing rollover draws, so pre-existing rollover results stay reproducible).

### Development — `YouthDevelopmentService`

At rollover, each prospect (identified by `youth_prospect = true`) is developed instead of going through
the senior retirement/contract path:
- age +1;
- **grow toward potential**: overall rises by a fraction of `(potential − overall)` each season (larger
  gains when far from the ceiling, tapering near it), applied by bumping the position's key attributes and
  recomputing overall; growth **never pushes overall past `potential`** and stops once reached;
- **departure**: a prospect that reaches **age 20** still unpromoted leaves the academy (deleted; surfaced
  as a youth departure). This keeps the pool fresh and bounds its size.

Prospects do **not** retire (ages 15–19 have retirement probability 0) and are **not** subject to the
senior contract-expiry logic while `youth_prospect = true`.

### Promotion & release — `YouthAcademyService`

- **Promote** `POST /youth-prospects/{id}/promote` (owner-checked): `promoteFromYouth()` flips
  `youth_prospect=false`, sets an auto youth contract (`contractYears = 3`, wage = standard demand for the
  player's overall/age via the existing `ContractValuation`). The player is immediately a normal squad
  member (now passes the `youth_prospect=false` filter). No squad cap. Returns the promoted player.
- **Release** `POST /youth-prospects/{id}/release` (owner-checked): deletes the prospect.
- **List** `GET /youth-prospects?clubId=` (owner-checked): the academy roster as `YouthProspectResponse`
  (id, name, position, age, overall, key attributes, `potentialStars` — never raw potential).

## API

- `GET /youth-prospects?clubId={id}` → `List<YouthProspectResponse>`
- `POST /youth-prospects/{id}/promote` → `YouthProspectResponse` (or the promoted `PlayerResponse`)
- `POST /youth-prospects/{id}/release` → `204 No Content`
- `RolloverResponse` gains `youthIntake: List<SquadChangeResponse>` (new prospects) and
  `youthDepartures: List<SquadChangeResponse>` (age-20 leavers), so the rollover/news summary can show them.

`YouthProspectResponse`: `{ id, name, position, age, overall, pace, finishing, passing, tackling,
goalkeeping, stamina, potentialStars }`.

## UI

- A new route/screen **"Base"** (Youth academy), reachable from the main nav.
- One card/row per prospect: name, age, position, current **OVR**, a **★★★☆☆** potential rating, and the
  key attributes; plus **Promover** and **Liberar** (release) actions with confirm.
- Empty state when the academy is empty ("Sua base ainda não tem jovens — eles chegam a cada temporada.").
- The rollover/news summary lists youth intake and departures (reuses the existing timeline card).

## Testing

**Backend (unit):** `potentialStars` band mapping; potential assignment stays ≤ cap and ≥ current;
youth development grows toward potential and **stops at the ceiling** (low-potential prospect plateaus,
high-potential keeps rising, never exceeds potential); age-20 departure; promotion flips the flag + sets
the youth contract + makes the player match-eligible; release removes the prospect; **seniors are
unaffected** (potential 0, never developed as youth).

**Backend (integration):** the youth endpoints (list/promote/release, owner-checked); a rollover IT
asserting the managed academy is topped up, prospects develop, an age-20 prospect departs, and a
**managed retiree is NOT auto-replaced** while an **AI retiree still is**; and that `getSquad`/`bestXi`
exclude prospects.

**Frontend (RTL):** the Base screen renders prospects with the correct star rating; promote/release call
the API and update the list; empty state renders.

## Rollover / determinism notes

- Youth RNG draws happen **after** the existing per-player aging/retirement draws, in a fixed order, so
  the current rollover outputs for existing players remain reproducible.
- Removing managed newgen-on-retirement changes managed rollover output (that retiree is no longer
  replaced) — this is the intended design (the academy replaces that pipeline). Existing rollover tests
  that assert managed squad-size constancy will need updating to the new behavior.
- Backward-compat: `youth_prospect` defaults false and `potential` defaults 0 for all existing players, so
  saves created before this feature simply have an empty academy until the next rollover populates it.

## Constants (tunable, all in one place per service)

`ACADEMY_TARGET = 10`, `MAX_INTAKE_PER_SEASON = 3`, prospect age `15–17`, `POTENTIAL_CAP = 92`,
departure age `20`, promotion contract `3` years, development rate ≈ `0.35` of the potential gap/season.

## File touch list

- `backend/.../player/Player.java` — `youthProspect` + `potential` fields, accessors, `promoteFromYouth`,
  prospect create path.
- `backend/src/main/resources/db/migration/V19__youth_academy.sql` — two columns.
- `backend/.../player/YouthDevelopmentService.java` (new) — grow toward potential; `potentialStars`.
- `backend/.../player/NewgenGenerator.java` — add a `generateProspect(saveGameId, clubId, position, rng)`
  method that reuses the existing position attribute bands but draws age 15–17, sets `youth_prospect=true`
  and assigns `potential` (no new generator class — avoids duplicating the bands).
- `backend/.../season/SeasonRolloverService.java` — youth branch (develop/depart), youth intake top-up,
  stop managed newgen-on-retirement; populate `RolloverResponse.youthIntake/youthDepartures`.
- `backend/.../season/RolloverResponse.java` — two new lists.
- `backend/.../youth/YouthAcademyService.java`, `YouthController.java`, `YouthProspectResponse.java` (new).
- `backend/.../match/LineupService.java` + `PlayerRepository.java` — `youth_prospect=false` filters for
  squad/bestXi/startingXi/validation; academy query.
- `frontend/src/api/` — youth client + `YouthProspectResponse` type; `RolloverResponse` new fields.
- `frontend/src/features/youth/` — Base screen, prospect card, star rating, promote/release; nav entry.
- Tests alongside each.
