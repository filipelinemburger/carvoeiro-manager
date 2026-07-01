# Carvoeiro Manager — Phase 3a-1: Fitness & Injuries

## Context

Phase 3 ("Deep Manager") of the master design adds long-term depth: training, youth,
injuries & fatigue, morale, staff, expanded finances, and promotion/relegation. It is too
large for a single spec, so — following the project's established sub-phase discipline
(2a–2e) — Phase 3 is decomposed into shippable slices:

- **3a — Fitness & Injuries** (this area), split further into:
  - **3a-1 — Fitness & Injuries system** *(this spec)*
  - **3a-2 — Bench, in-match substitutions, and a live text-commentary match view** *(next spec, builds on 3a-1)*
- 3b — Training & Morale
- 3c — Youth Academy
- 3d — Staff (multipliers over training/recovery/youth/scouting)
- 3e — Sponsorships & expanded finances
- 3f — Promotion/Relegation pyramid (Série A/B/C)

### Baseline already in place (important)

The injury *scaffolding* already exists and works:

- `players.injury_matches` (countdown) with `addInjury`/`tickInjury`/`isEligible` on `Player`.
- Injuries roll per fixture in `MatchOutcomeApplier.applyInjuries` (flat ~3% chance, 1–3 matches)
  using a **dedicated deterministic `injuryRng`** seeded off the match seed.
- **Eligibility is enforced**: `LineupService.startingXi` filters injured/suspended players and
  falls back to `bestXi`; `setLineup` rejects injured/suspended selections; the advance preview
  warns about ineligible starters.
- Suspensions (from cards) and per-match `form` are applied in `MatchOutcomeApplier`.
- The match is an **instant simulation**: `MatchSimulationService.simulate` generates all 90'
  of events from a seed; the advance is **date-driven** (`SeasonAdvanceService`), fixtures carry
  a `matchDate` (`LocalDate`), and `SaveGame` carries `currentDate`.

What is **missing** — and what this spec adds — is a **persistent, match-to-match fitness/fatigue**
model. Today only *in-match* stamina decay exists (`TeamStrengthCalculator.fitnessDecay`), so a
manager can field the same XI every 3 days with zero penalty. **Rotation does not yet matter.**
3a-1 makes squad depth and rotation meaningful, especially given the congested multi-competition
calendar from Phase 2d.

## Goal

Introduce persistent player fitness and a richer, fitness- and age-linked injury model so that
squad rotation across a congested calendar becomes a real managerial decision — for the human and
for AI clubs alike — while preserving the engine's deterministic-replay guarantee.

## Scope

**In scope (3a-1):**

1. Persistent per-player fitness (0–100%) that decays when a player features and recovers with
   calendar rest, using a **lazy day-based** model.
2. Fitness lowers match performance and raises injury risk.
3. Age effects (via an `AgeBand` enum) on recovery, injury risk, and — modestly — decay.
4. Injury severity tiers (`KNOCK` / `MINOR` / `MAJOR`) with distinct layoffs, replacing the flat
   1–3 match model; probability scales with low fitness and late-career age. (Accumulated match
   load is captured indirectly through fitness; per-minute/late-match injury timing is a 3a-2
   concern — see the boundary section.)
5. AI clubs rotate on fitness (fatigue applies league-wide).
6. UI: fitness bars and injury-severity chips on the squad and lineup screens; injury severity in
   the news feed; a soft (non-blocking) low-fitness starter warning in the advance preview.

**Out of scope (deferred to 3a-2 and later):**

- Bench, in-match substitutions, and the live text-commentary match view.
- Partial-minutes fitness decay (a player subbed at 60' draining less than a full 90').
- Minute-timed in-match injury *events* (injuries in 3a-1 are still resolved once per fixture).
- Any medical/physio staff effects (3d), training load (3b), morale (3b).

3a-1 keeps the instant simulation: for fitness purposes a starter always plays a full 90'. 3a-2
will layer partial minutes onto this same fitness model.

## Design decisions (locked with the user)

- **Fitness model:** persistent 0–100% per player.
- **Recovery model:** lazy day-based (Approach 1) — store a base value + last match date; compute
  effective fitness on read from calendar days rested.
- **Injuries:** severity tiers, fitness-linked and age-linked.
- **Age:** affects recovery, injury risk, **and** decay (decay kept modest to avoid over-counting
  against stamina, which already declines with age via the season aging curve).
- **AI:** all clubs track fitness; AI rotates via fitness-adjusted `bestXi`.
- **Rotation mechanism in 3a-1:** between-match XI selection only (bench/subs are 3a-2).

---

## Section 1 — Data model & fitness lifecycle

### New `Player` state (`V15__fitness.sql`)

| Column | Type | Default | Meaning |
|--------|------|---------|---------|
| `fitness` | `INT NOT NULL` | `100` | Stored **base** fitness (0–100). Not the displayed value. |
| `last_match_date` | `DATE NULL` | `NULL` | Date the player last featured; `NULL` = never played. |
| `injury_severity` | `VARCHAR(10) NOT NULL` | `'NONE'` | `NONE` / `KNOCK` / `MINOR` / `MAJOR`. |

`injury_matches` (already present) remains the layoff countdown.

### `InjurySeverity` enum (enum-with-behavior)

Holds each tier's layoff range and knows how to draw a concrete layoff from a seeded RNG:

- `NONE`
- `KNOCK` — 1 match
- `MINOR` — 2–4 matches
- `MAJOR` — 5–12 matches

### `AgeBand` enum (enum-with-behavior)

Derived from `Player.age`; exposes `recoveryFactor()`, `injuryFactor()`, `decayFactor()`:

| Age band | `recoveryFactor` | `injuryFactor` | `decayFactor` |
|----------|:---:|:---:|:---:|
| `YOUNG` (≤23) | 1.15 | 1.0 | 0.95 |
| `PRIME` (24–29) | 1.0 | 1.0 | 1.0 |
| `EXPERIENCED` (30–32) | 0.9 | 1.2 | 1.10 |
| `VETERAN` (≥33) | 0.8 | 1.4 | 1.20 |

### Effective fitness (lazy — never stored)

```
effectiveFitness(onDate) = min(100, base + daysBetween(lastMatchDate, onDate) × recoveryPerDay)
recoveryPerDay          = RECOVERY_BASE × ageBand.recoveryFactor()
```

A player who never played (`lastMatchDate = null`) reports `100`. A single domain method /
`FitnessCalculator` owns this formula so the squad screen, strength calc, and injury roll all read
the same number.

### Lifecycle when a player features in a fixture on `matchDate`

1. `eff = effectiveFitness(matchDate)` — recover up to kickoff.
2. `decay = DECAY_BASE × clamp(70 / stamina, 0.7, 1.4) × ageBand.decayFactor()`.
3. `newBase = max(0, eff − decay)`.
4. Persist `fitness = newBase`, `last_match_date = matchDate`.

Players who do **not** feature need no writes — their effective fitness simply climbs with the
calendar, computed on read. Only players who play get a fitness DB write, keeping the advance cheap.

### Proposed starting constants (tunable during implementation)

- `RECOVERY_BASE ≈ 5` points/day (≈full recovery within a week; ~15 pts back after 3 days, so
  congestion bites).
- `DECAY_BASE ≈ 30` (with the stamina clamp, ~22 for stamina 90, ~33 for stamina 60, before the
  age `decayFactor`).

### Season rollover

`Player.resetSeasonState()` also resets fitness state: `fitness = 100`, `last_match_date = null`,
`injury_severity = NONE` (it already clears `injury_matches`).

---

## Section 2 — Match-engine integration

### (a) Fitness → performance

`TeamStrengthCalculator.compute` needs each player's effective fitness at kickoff. `FixtureSimulator`
(which knows the fixture and its date) resolves a `Map<playerId, effectiveFitness>` and passes it in,
keeping `TeamStrengthCalculator` a **pure function** (no repository access). Alongside the existing
`formMult`, add:

```
fitnessMult = 0.80 + 0.20 × (fitness / 100)     // 1.0 @100%, 0.90 @50%, 0.80 @0%
```

applied to each player's attack and defense contribution. A tired XI is measurably weaker but not
crippled — the same ~±10–20% band as form.

### (b) Fitness → injury risk + severity

`MatchOutcomeApplier.applyInjuries` keeps its dedicated deterministic `injuryRng`. Changes:

```
chance = BASE × (1 + K × (1 − fitness/100)) × ageBand.injuryFactor()
```

(e.g. `BASE ≈ 2.5%`; ~2.5% at full fitness/prime age, ~5–6% at 40% fitness, up to ~1.4× more for
veterans). On a hit, draw **severity** from a fixed distribution (≈ `KNOCK` 60%, `MINOR` 30%,
`MAJOR` 10%), set `injury_matches` from the tier range, and stamp `injury_severity`. The severity
draw is appended **after** the existing draws so current match/event RNG stays byte-identical.

`InjuryResponse` (already surfaced in the advance summary) gains `severity` so news/UI can render
"Minor injury — 3 matches". Recovery is mechanically unchanged (`tickInjury` per rested fixture);
when `injury_matches` reaches 0, `injury_severity` resets to `NONE`.

### (c) AI rotation

`bestXi` today ranks eligible players by `overall`. It changes to rank by a **fitness-adjusted
rating** (`overall × fitnessMult` at the fixture date), so AI clubs bench a drained star for a
fresher squad player during congestion — fatigue applies league-wide, not just to the human. The
eligibility fallback (fill from ineligible when short) is untouched. `bestXi` therefore also
receives the current date; `FixtureSimulator` passes `fixture.getMatchDate()`.

---

## Section 3 — API & frontend

### API (no new endpoints — enrich existing responses)

- `PlayerResponse` (used by `SquadResponse` and the lineup screen) gains `fitness` (effective %
  at the save's `currentDate`), `injurySeverity` (`NONE|KNOCK|MINOR|MAJOR`), and the existing
  `injuryMatches`. `LineupService.getSquad` already knows the save, so it computes effective
  fitness via the shared helper.
- `InjuryResponse` gains `severity`.
- The advance **preview** (`AdvancePreviewService`, which already warns about ineligible starters)
  surfaces **low-fitness starters** (below a threshold, e.g. 50%) as a **soft, non-blocking**
  warning that nudges rotation.

### Frontend

- **Squad screen** (`features/squad`) and **Escalação/lineup screen**: a compact reusable
  `FitnessBar` per player (green → amber → red) with the %, plus an **injury chip** showing
  severity + matches remaining. These are the rotation decision aids.
- **News feed** (`features/news`): injury items show severity and layoff.
- No new routes — enrichment of existing screens only.

---

## Section 4 — Testing, determinism & edge cases

### Determinism (project bedrock)

Effective fitness is a pure function of `(base, lastMatchDate, onDate)`. Decay/injury use the
existing dedicated `injuryRng`; the severity draw is appended after current draws. The same save +
same decisions replays byte-identically, apart from the new fitness fields.

### Backend tests (JUnit + Testcontainers, Given/When/Then, ≥80% coverage)

- `FitnessCalculator`: recovery caps at 100; 2–3 day gaps accumulate fatigue; a full week restores
  fitness; age bands scale recovery/decay correctly.
- `MatchOutcomeApplier`: injury probability rises as fitness falls and with age; severity
  distribution over many seeded rolls matches the tiers; `injury_matches` set from tier range;
  `injury_severity` clears on recovery.
- `TeamStrengthCalculator`: a low-fitness XI yields measurably lower attack/defense than a fresh
  one; `fitnessMult` stays within 0.80–1.0.
- `bestXi`: with equal-overall players of differing fitness, the fresher XI is chosen; congestion
  makes AI rotate.
- Rollover: `resetSeasonState` zeroes fitness state.
- Regression: a full seeded season replays identically pre/post-change except for the new fields.

### Frontend tests (Vitest/RTL)

- `FitnessBar` color/label thresholds.
- Squad & lineup screens render fitness + injury chips.
- News shows severity.

### Edge cases

- Free agents / never-played: fitness 100, `lastMatchDate = null`.
- Players injured mid-season carry `injury_severity` correctly through recovery.
- `bestXi` ineligible-fallback still guarantees a full XI when fatigue + injuries thin a squad.
- A player at 0 fitness still plays (no hard block — soft warning only).

---

## Explicit 3a-2 boundary (deferred, not in this spec)

Bench, in-match substitutions, the live text-commentary match view, partial-minutes fitness decay,
and minute-timed in-match injury events. 3a-2 layers partial minutes onto the same fitness model
defined here.

## Critical files

- `backend/.../db/migration/V15__fitness.sql` (new)
- `backend/.../player/Player.java` (fields + fitness/injury methods; `resetSeasonState`)
- `backend/.../player/InjurySeverity.java`, `backend/.../player/AgeBand.java` (new enums)
- `backend/.../match/FitnessCalculator.java` (new; lazy effective-fitness + decay)
- `backend/.../match/TeamStrengthCalculator.java` (accept fitness map; `fitnessMult`)
- `backend/.../match/MatchOutcomeApplier.java` (fitness/age-linked injury + severity)
- `backend/.../match/FixtureSimulator.java` (resolve fitness map; apply decay; pass match date)
- `backend/.../match/LineupService.java` (`bestXi` fitness-adjusted; `getSquad` effective fitness)
- `backend/.../match/InjuryResponse.java`, `.../PlayerResponse.java` (severity, fitness fields)
- `backend/.../season/AdvancePreviewService.java` (soft low-fitness warning)
- `frontend/src/features/squad/`, `frontend/src/features/*/` FitnessBar + injury chip; news severity

## Verification

- `./gradlew test` green (unit + Testcontainers).
- `docker-compose up`, create a Criciúma career, play a congested run of fixtures; confirm fitness
  drops for those who play, recovers with rest, AI rotates, injuries show severity, and the squad
  screen reflects live fitness.
- `npm run test` green; `npm run dev` and verify fitness bars / injury chips / news render.
