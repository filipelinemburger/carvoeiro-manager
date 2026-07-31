# Weather Simulation + Stadiums with Visible Attendance — Design Spec

**Date:** 2026-07-30
**Roadmap:** Group 3 (Match realism), sub-features 2 and 3 of 3.
**Status:** Approved (brainstorm decisions locked with the user)

## Summary

Every fixture is played in weather derived from the home club's real geography and the
month of the match. Weather is *interactive*: a wet pitch punishes a passing style and
rewards a direct one, heat amplifies fatigue (worst for a high-pressing side), and wind
blunts set pieces. The forecast is revealed on the eve of the match — through the
Auxiliar Técnico's existing `MATCH_PREVIEW` news item and on the fixture screen — so it
is a tactical input, not a post-hoc surprise.

At the same time, stadiums stop being generic. All 28 seeded clubs gain their real city,
state, and stadium capacity, replacing the derived `8000 + reputation × 500`. Attendance
— today an invisible local variable inside `FinanceCalculator.gateIncome` — becomes a
first-class, persisted, displayed number that responds to form, league position, and the
weather.

The two halves are one spec because they share a home (the fixture row and the match
screens) and because weather feeds attendance.

## User decisions (locked)

1. **Weather is interactive, not flavour.** It reads match context (`GameStyle`,
   `Pressing`, set-piece threats) and can favour one side over the other. Rejected: a
   display-only weather, and a single symmetric global scoring knob.
2. **Revealed on the eve.** The forecast reaches the manager the day before the match,
   via the existing `MATCH_PREVIEW` news item and the fixture screen, so the XI and
   tactics can be set with it in hand. Rejected: rolling at kickoff (reactive only), and
   a probabilistic forecast that may differ from the actual condition (frustrating, and
   doubles the state).
3. **Climate zones, not per-city normals.** ~5 zones with 12-month profiles, one assigned
   per club. Rejected: a single national model (a Fortaleza winter would feel wrong), and
   researching 28 × 12 real normals (indistinguishable in play, 5× the research).
4. **Real city, state, and stadium capacity for all 28 clubs**, seeded and backfilled.
5. **Attendance is context-driven**, modulated by form, league position, and weather on
   top of the existing reputation base. Rejected: surfacing the static number unchanged,
   and adding manager-set ticket pricing (a separate management surface, its own spec).
6. **Real capacities ship with a targeted finance recalibration**, driven by a solvency
   test rather than by guesswork. Rejected: keeping derived capacities, and shipping real
   capacities with no rebalance (an unannounced difficulty spike).
7. **Weather is a pure function, not a column.** Attendance *is* a column.
8. **Weather plugs in via a new `WeatherEffects` utility**, not by extending
   `MatchupModifiers` and not by pre-baking into `TeamMatchInput`.

## Current state (verified 2026-07-30)

- `MatchMath.perMinuteScoreChance(attack, defense, attackerIsHome)` is the single shared
  scoring curve (landed in Group 3 sub-feature 1). Both engines call it.
- `MatchupModifiers.chanceMults(home, away)` is applied identically at
  `LiveMatchEngine.java:132` and `MatchSimulationService.java:49` — the established seam
  for a per-side multiplier.
- `TeamStrengthCalculator` is the sole builder of `TeamMatchInput`, and already computes
  `fitnessDecay`, `cornerThreat`, and `freeKickThreat`.
- `FinanceCalculator.gateIncome(homeRep, awayRep, capacity)` computes
  `fill = 0.40 + homeRep/200 + awayRep/400` clamped `[0.25, 1.0]`, derives an
  `attendance` **local variable**, and returns only `TICKET_PRICE × attendance`. The
  attendance number is discarded.
- `Club` has `stadiumName` (real, seeded in `V33`) and `stadiumCapacity` (derived
  `8000 + reputation × 500` → every club sits between 28 000 and 53 000 seats). There is
  **no** city, state, or location data of any kind.
- `Fixture` has `matchDate`, home/away club ids, and goals.
- `LiveMatchState` already carries `stadiumName`; `FixtureResultResponse` carries
  `stadium`.
- `MatchOutcomeApplier` is called from exactly two places — `LiveMatchService` (managed
  club) and `FixtureSimulator` (everything else). That pair is the template for any
  per-match persistence.
- Season starts 15 January (`SeasonCalendarGenerator`); the finance model bills monthly
  across 10 months, Jan–Oct.

## Part A — Club geography and real stadiums

### Data layer (`V45__weather_and_stadiums.sql`)

```sql
ALTER TABLE clubs ADD COLUMN city VARCHAR(60);
ALTER TABLE clubs ADD COLUMN state CHAR(2);
ALTER TABLE clubs ADD COLUMN climate_zone VARCHAR(20);
```

Backfilled by club name for all 28 seeded clubs, following the `V33__stadium_name.sql`
and `V43__research_backfill.sql` precedent. `stadium_capacity` is rewritten in the same
migration from researched real values, matched by club name.

Existing saves therefore keep working: clubs created before V45 get the same geography
and capacity as new ones. Any club row not matched by name (a defensive case — the seed
is closed) keeps its derived capacity and gets `climate_zone = 'SUDESTE'` as the neutral
default.

`clubs.json` gains `city`, `state`, `climateZone`, and `stadiumCapacity` per club so new
saves are seeded directly rather than relying on the backfill.

### `ClimateZone`

A Java enum with behaviour, per the project's convention:

```java
public enum ClimateZone {
    SUL, SUDESTE, CENTRO_OESTE, NORDESTE_LITORAL, NORDESTE_INTERIOR;
    MonthProfile profileFor(Month month);
}
```

The 12-month profiles live in `backend/src/main/resources/seed/climate.json` — tunable
without a recompile, same as the other seeds:

```json
{
  "zones": {
    "SUL": [
      { "month": 1, "minC": 19, "maxC": 30, "rainChance": 0.35, "windChance": 0.10 },
      { "month": 2, "minC": 19, "maxC": 29, "rainChance": 0.35, "windChance": 0.10 }
    ]
  }
}
```

(Abbreviated — each zone lists all 12 months.)

Five zones × 12 months ≈ 60 tunable rows. Every zone must define all 12 months
(enforced by a validation test).

### `Club` entity

Gains `city`, `state`, `climateZone` with getters/setters matching the existing style,
plus a `resolvedLocation()` helper returning `"Criciúma/SC"` (or `null` when unseeded),
mirroring the existing `resolvedStadiumName()`.

## Part B — Weather generation

### `Weather` — the value

```java
public record Weather(WeatherCondition condition, int temperatureC) {}

public enum WeatherCondition {
    ENSOLARADO("Ensolarado"),
    NUBLADO("Nublado"),
    VENTANIA("Ventania"),
    CHUVA("Chuva"),
    CHUVA_FORTE("Chuva forte");

    /** 0.0 dry, 0.5 CHUVA, 1.0 CHUVA_FORTE — the wetness the engine reads. */
    public double wetness();
}
```

### `WeatherGenerator` — pure and deterministic

```java
public static Weather forFixture(UUID fixtureId, ClimateZone zone, LocalDate matchDate)
```

Seeded from `fixtureId.getMostSignificantBits() ^ fixtureId.getLeastSignificantBits()`
via a fresh `Random`, following the id-seeded familiar-role generation in `eaa6553`. The
same fixture yields the same weather on every reload and in every process.

Algorithm: look up the zone's `MonthProfile` for `matchDate.getMonth()`; roll
`rainChance` → `CHUVA` (and a further 1-in-3 escalates to `CHUVA_FORTE`); otherwise roll
`windChance` → `VENTANIA`; otherwise `ENSOLARADO` or `NUBLADO` on a 60/40 split.
Temperature is drawn uniformly from `[minC, maxC]`, then adjusted −3°C when raining.

Weather is **always** rolled from the **home** club's zone — the match is at their
stadium.

### No persistence

Weather is not stored. The live engine, the instant simulator, the eve-of-match news, and
the UI all recompute it from the same three inputs, which makes disagreement structurally
impossible rather than merely tested-for. It also means no null-weather state for
unplayed fixtures and no backfill for fixtures already sitting in existing saves.

Accepted cost: retuning `climate.json` later retroactively changes historical fixtures'
weather. For a display detail on a finished match, that is acceptable.

### The reveal rule

One method, one place:

```java
// WeatherService
Optional<Weather> visibleWeather(Fixture fixture, LocalDate today);  // empty if matchDate - today > 1
```

Every surface calls this. Deliberately **not** gated on having an Auxiliar hired: weather
is public knowledge, and gating it would leave a staff-less club with no forecast
anywhere, since no Auxiliar means no `MATCH_PREVIEW` news at all. The Auxiliar's
value-add is the interpretation, not the forecast.

The engines call `WeatherGenerator.forFixture` directly — the reveal rule is a
presentation concern and must never affect simulation.

## Part C — Weather effects on the match

### `WeatherEffects` — pure, static, no Spring

Mirrors the `SetPieces` / `MatchupModifiers` utility pattern.

```java
public record Impact(double homeChanceMult, double awayChanceMult,
                     double homeDecayMult, double awayDecayMult,
                     double setPieceMult) {}

public static Impact of(Weather weather, TeamMatchInput home, TeamMatchInput away)
```

### Determinism contract

`ENSOLARADO` with `temperatureC` in `[13, 28]` returns exactly `1.0` on all five fields.
This is the same promise `GameStyle.EQUILIBRADO` and `MatchupModifiers` already make in
their doc comments, and it keeps existing engine tests byte-identical instead of
requiring a mass re-baseline.

### The four effects

| Effect | Reads | Formula |
|---|---|---|
| Wet pitch (both sides) | `condition` | `CHUVA` ×0.97, `CHUVA_FORTE` ×0.93, `VENTANIA` ×0.98, else ×1.00 |
| Rain vs style (per side) | `gameStyle()` | `JOGO_DE_PASSES` ×(1 − 0.06·w), `JOGO_DIRETO` ×(1 + 0.04·w), else ×1.0, where `w = condition.wetness()` |
| Heat / cold (per side) | `temperatureC`, `gameStyle()` | `decayMult = 1 + 0.02 × max(0, T − 28)`, capped at 1.20; the bonus above 1.0 is multiplied by 1.5 for a `PRESSAO_ALTA` side. If `T ≤ 12`, `decayMult = 0.95` |
| Wind (both sides) | `condition` | `VENTANIA` → `setPieceMult = 0.85`, else 1.0 |

The heat effect carries the tactical weight: a 33°C afternoon costs a `PRESSAO_ALTA`
side roughly twice the fatigue of a balanced one, making the eve-of-match forecast a
genuine reason to drop the press.

### Application in the engines

In **both** `LiveMatchEngine` and `MatchSimulationService`, immediately after the
existing `chanceMults` application:

```java
MatchupModifiers.ChanceMults chanceMults = MatchupModifiers.chanceMults(homeInput, awayInput);
homeChance *= chanceMults.home();
awayChance *= chanceMults.away();

WeatherEffects.Impact weatherImpact = WeatherEffects.of(weather, homeInput, awayInput);
homeChance *= weatherImpact.homeChanceMult();
awayChance *= weatherImpact.awayChanceMult();
```

`homeDecayMult` / `awayDecayMult` scale the `fitnessDecay` each engine feeds to
`MatchMath.fitnessFactor`. `setPieceMult` scales `cornerThreat` and `freeKickThreat` at
the point they are consumed.

Both engines receive the `Weather` from their caller (`LiveMatchService`,
`FixtureSimulator`), which resolves it via `WeatherGenerator.forFixture`.

## Part D — Attendance

### `AttendanceCalculator` — pure, in the `finance` package

```java
public int attendance(int stadiumCapacity, int homeReputation, int awayReputation,
                      int recentFormPoints, int leaguePosition, int clubCount,
                      Weather weather)
```

```
fill = base × form × position × weather      clamped [0.15, 1.0]
attendance = min(capacity, round(capacity × fill))
```

| Factor | Formula | Range |
|---|---|---|
| `base` | `0.40 + homeRep/200 + awayRep/400` (unchanged from today) | — |
| `form` | `0.90 + 0.20 × (recentFormPoints / 15)`, last 5 league results | 0.90 – 1.10 |
| `position` | `1.10 − 0.20 × (pos − 1)/(clubCount − 1)` | 0.90 – 1.10 |
| `weather` | `ENSOLARADO` 1.00, `NUBLADO` 0.98, `VENTANIA` 0.95, `CHUVA` 0.90, `CHUVA_FORTE` 0.78; ×0.95 more when `T ≥ 35` or `T ≤ 8` | 0.74 – 1.00 |

Worst case (bottom of the table, no form, heavy rain, temperature extreme) is
`0.90 × 0.90 × 0.74 ≈ 0.60×` the reputation baseline; best case is
`1.10 × 1.10 × 1.00 = 1.21×`. Wide enough to feel, narrow enough not to wreck the
finances.

`clubCount ≤ 1` yields a `position` factor of 1.0 (no division by zero).

**Input sources.** `recentFormPoints` is the home club's points from its last 5 *played*
fixtures in the same competition (3/1/0), capped at 15 — fewer than 5 played (early
season) uses what exists, scaled to the same 0–15 range. `leaguePosition` and `clubCount`
come from `StandingsService` for the fixture's competition as of the match date. Both are
resolved by the caller (`LiveMatchService` / `FixtureSimulator`), keeping
`AttendanceCalculator` pure.

### Persistence

```sql
ALTER TABLE fixtures ADD COLUMN attendance INT;
```

Nullable — unplayed fixtures and every fixture in existing saves have none, and callers
null-guard, consistent with how nullable `AdvanceResponse` fields were handled in the
opponent-analysis work.

Computed and persisted at match resolution from both call sites — `LiveMatchService` for
the managed club's match, `FixtureSimulator` for all others — mirroring exactly how both
already call `MatchOutcomeApplier`.

### Finance refactor

`FinanceCalculator.gateIncome` currently hides the crowd model inside a money method. It
becomes:

```java
public BigDecimal gateIncome(int attendance)   // TICKET_PRICE × attendance
```

The fill model moves wholesale to `AttendanceCalculator`. Finance stops owning crowd
behaviour; each class answers one question. `FinanceService.java:145` reads
`fixture.getAttendance()` instead of recomputing, null-guarding to zero gate for legacy
rows.

### Recalibration

Order matters: land real capacities, then measure, then tune.

A `FinanceSolvencyTest` simulates a full season across the capacity range — roughly
3 000 seats (Arena Barra) to 64 000 (Arena Castelão) — and asserts a mid-table club
finishes solvent at each end. Only constants that test proves broken get changed.
`MONTHLY_SPONSORSHIP_PER_REP` is the likeliest lever, being the existing counterweight to
payroll, but the spec deliberately does not pre-commit: guessing before measuring is how
two changes end up cancelling out.

`FinanceCalculator`'s class-level calibration comment is rewritten — it currently
documents a `cap = 38 000` world that will no longer exist.

## Part E — Surfacing

| Surface | Added |
|---|---|
| `MatchPreviewData` (eve news) | `weather`, `temperatureC`, and a pt-BR forecast line from the Auxiliar |
| Fixture / Calendário screen | Weather chip on upcoming fixtures (subject to the reveal rule); attendance on played ones |
| `LiveMatchState` | `weather`, `temperatureC`, `attendance` — beside the existing `stadiumName` |
| `FixtureResultResponse` | `attendance`, `weather`, `temperatureC` |
| `ClubSummaryResponse` | `city`, `state`, real `stadiumCapacity` |

All new DTO fields nullable; all callers null-guard. All copy pt-BR.

Frontend: a `WeatherChip` component (icon + condition label + temperature) and an
attendance line formatted `pt-BR` (`19.300`). Club screen shows
`Estádio Heriberto Hülse · Criciúma/SC · 19.300 lugares`.

## Testing

- `WeatherEffectsTest` — the neutrality contract first (`ENSOLARADO` at 13–28°C returns
  exactly 1.0 on all five fields), then each of the four effects in isolation, then the
  clamps.
- `WeatherGeneratorTest` — determinism (same fixture id → identical weather over 1000
  draws) and seasonal sanity per zone (no 30°C July in `SUL`, no 5°C anywhere in
  `NORDESTE_LITORAL`).
- `AttendanceCalculatorTest` — never exceeds capacity, monotonic in form and in position,
  heavy rain always reduces, `clubCount ≤ 1` is safe.
- `ClimateSeedValidationTest` — all 28 clubs have city/state/zone/capacity; every zone
  defines all 12 months. Same shape as the existing seed-fragment validator.
- `FinanceSolvencyTest` — full-season solvency across the capacity range.
- `MatchCalibrationTest` — must still hit its existing bands (favourite win rate 60–70%,
  home win 42–48%, goals 2.4–3.0) **with real generated weather**, not a neutral stub, so
  the bands hold across the weather distribution a real season produces. If the scoring
  multipliers shift goals-per-match out of band, `MatchMath.ANCHOR` gets the nudge. The
  existing equal-teams determinism assertions keep passing a neutral `ENSOLARADO` at 20°C,
  which the neutrality contract guarantees is a no-op.
- `WeatherServiceTest` — the reveal rule: empty more than one day out, present on the eve
  and on match day.
- Migration IT — `V45` backfills all 28 clubs and leaves an unmatched club row on safe
  defaults.
- Frontend `vitest` — `WeatherChip` rendering per condition, attendance formatting, and
  the reveal rule's absent-weather case.

## Out of scope

- AI managers adapting tactics or selection to the weather.
- Weather changing during a match (it is fixed at kickoff).
- Pitch condition persisting across fixtures (a rained-on pitch staying heavy next round).
- Manager-set ticket pricing and price elasticity — a separate finance spec.
- Travel distance, altitude, or time-of-day kickoffs (the `ClimateZone` is the hook if
  these are wanted later).
- Per-city climate normals.
- Weather effects on injury rates, card rates, or set-piece *frequency* (only set-piece
  *threat* is touched).

## File summary

**Backend (create):** `V45__weather_and_stadiums.sql`, `seed/climate.json`,
`club/ClimateZone.java`, `club/MonthProfile.java`, `match/Weather.java`,
`match/WeatherCondition.java`, `match/WeatherGenerator.java`, `match/WeatherEffects.java`,
`match/WeatherService.java`, `finance/AttendanceCalculator.java`.

**Backend (modify):** `club/Club.java`, `club/ClubSummaryResponse.java`,
`club/ClubService.java`, `competition/Fixture.java`, `match/LiveMatchEngine.java`,
`match/MatchSimulationService.java`, `match/LiveMatchService.java`,
`match/FixtureSimulator.java`, `match/LiveMatchState.java`,
`match/FixtureResultResponse.java`, `finance/FinanceCalculator.java`,
`finance/FinanceService.java`, `season/MatchPreviewService.java`,
`season/MatchPreviewData.java`, `seed/` loader for the new club fields.

**Frontend (create):** `WeatherChip.tsx`, weather label/icon map.

**Frontend (modify):** live match header, fixture/Calendário screen, club screen, the
news preview renderer, and the corresponding types.
