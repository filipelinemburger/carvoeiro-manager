# Match Strength-Spread Recalibration — Design Spec

**Date:** 2026-07-21
**Roadmap:** Group 3 (Match realism), sub-feature 1 of 3 — item 4.
**Status:** Approved for planning.

## Goal

Make squad quality matter more in match outcomes. Today team strength is averaged
across all 11 players, so a squad-wide quality gap barely moves the average and
favorites rarely dominate — matches feel like coin-flips-with-a-home-edge. Widen the
talent gap's effect on scoring to a "realistic league" feel, recast home advantage as
an orthogonal knob, and extract the duplicated match-scoring math into one shared
utility so both engines stay in lockstep.

Backend-only. No migration, no API change, no UI, no new dependency.

## Target outcome feel ("realistic league")

Calibration targets, measured over many simulated matches drawn from the real seeded
rosters:

- **Strong vs weak** (best squad-overall vs worst): favorite win-rate **≈ 60–70%**.
- **Equal teams, one at home**: home win **≈ 42–48%**, draws **≈ 24–28%**.
- **Goals per match** in a sane band **≈ 2.4–3.0**.

Upsets stay common; the league table ends up reflecting squad quality without being
deterministic.

## Current state (verified 2026-07-21)

The scoring math is duplicated **verbatim** in two engines:

- `match/LiveMatchEngine.java` — the live, substitution-aware, per-minute engine used
  for the managed club's own matches.
- `match/MatchSimulationService.java` — the instant engine used for all AI/other-club
  fixtures (orchestrated by `match/FixtureSimulator.java`).

Both contain identical private copies of:

```java
private static final int MINUTES = 90;
private static final double HOME_ADVANTAGE = 5.0;
private static final double BASE = 50.0;
private static final double FITNESS_FLOOR = 0.75;

private double fitnessFactor(double decay, int minute) {
    double frac = minute / (double) MINUTES;
    return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
}

private double perMinuteScoreChance(double attack, double defense) {
    double diff = (attack - defense) / BASE;
    double expectedGoals = Math.max(0.2, 1.4 + diff);
    return expectedGoals / MINUTES;
}
```

Both call sites bake `HOME_ADVANTAGE` into strength **on both ends** — the home side's
attack is boosted, and the home side's defense is boosted when computing the away
side's chance:

```java
// LiveMatchEngine.java:127-130 and MatchSimulationService.java:48-51
double homeChance = perMinuteScoreChance(homeAttack * homeFf + HOME_ADVANTAGE, awayDefense * awayFf) * tempo;
double awayChance = perMinuteScoreChance(awayAttack * awayFf, homeDefense * homeFf + HOME_ADVANTAGE) * tempo;
```

Team `attack`/`defense` come from `match/TeamStrengthCalculator.java`, which **averages
per-player contributions over the XI** (`attack = Σ attackContribution / 11`; defense is
`0.6·outfieldAvg + 0.4·gkDefense`). A typical squad sits around `attack ≈ 33`,
`defense ≈ 57`; `HOME_ADVANTAGE = 5.0` is worth `≈ +0.10 xG` — about half a 10-point
talent gap. That averaging is the source of the compression, and it is **left
untouched** (Approach A): the curve compensates for it.

`SetPieces.java` is the established precedent for a pure static match-math utility.

## Architecture

### New unit: `MatchMath` (pure static utility)

`match/MatchMath.java` — the single home for the shared scoring math, mirroring the
`SetPieces` pattern (final class, private constructor, static methods, no Spring).

Owns:
- Constants: `MINUTES`, `BASE`, `FITNESS_FLOOR`, and the recalibrated curve constants
  (`GAIN`, `ANCHOR`, `REF`, `HOME_XG`, `XG_FLOOR`, `XG_CEILING`).
- `static double fitnessFactor(double decay, int minute)`.
- `static double perMinuteScoreChance(double attackerStrength, double defenderStrength, boolean attackerIsHome)`.

`LiveMatchEngine` and `MatchSimulationService` delete their private copies of the
constants and both formulas and delegate to `MatchMath`.

**Scope boundary:** the card constants `BASE_CARD_RATE` and `RED_SHARE` are also
duplicated in both engines, but cards are not being recalibrated — they stay local to
each engine. Only the scoring/fitness math moves.

### The recalibrated curve

`MatchMath.perMinuteScoreChance(attackerStrength, defenderStrength, attackerIsHome)`:

```
d  = attackerStrength - defenderStrength
xg = ANCHOR + GAIN * (d - REF) / BASE
xg += attackerIsHome ? HOME_XG : -HOME_XG      // symmetric home edge
xg = clamp(xg, XG_FLOOR, XG_CEILING)
return xg / MINUTES
```

Two changes from today:

1. **Gain + re-centre.** `GAIN` is the single spread knob. `REF` is the average-matchup
   differential (so the *typical* game keeps realistic goal output) and `ANCHOR` its xG.
   A new `XG_CEILING` caps absurd scorelines in extreme mismatches (today only a floor
   exists). `GAIN = 1`, `ANCHOR = 0.92`, `REF = -24`, `HOME_XG = 0.10`,
   `XG_CEILING = ∞` reproduces today's formula exactly — the new form is a strict
   generalization.

2. **Home advantage as an orthogonal xG term.** Instead of `+HOME_ADVANTAGE` funneled
   into strength (which would silently scale up as `GAIN` rises), the home side gets
   `+HOME_XG` on its expected goals and the away side `−HOME_XG` (matching today's
   symmetric both-ends application). `GAIN` now controls **talent spread** and `HOME_XG`
   controls the **home edge**, independently.

Call sites become (both engines, identically):

```java
double homeChance = MatchMath.perMinuteScoreChance(homeAttack * homeFf, awayDefense * awayFf, true) * tempo;
double awayChance = MatchMath.perMinuteScoreChance(awayAttack * awayFf, homeDefense * homeFf, false) * tempo;
```

`fitnessFactor` moves unchanged.

### Constant values

Initial estimates (validated by hand to land near the targets), **finalised by the
calibration harness** during implementation:

| Constant     | Initial | Meaning |
|--------------|---------|---------|
| `BASE`       | 50.0    | Differential denominator (unchanged). |
| `GAIN`       | ~2.2    | Talent-spread multiplier. Tuned to hit strong-vs-weak 60–70%. |
| `REF`        | ~-24.0  | Average-matchup `d` across seeded squads. Sets where the pivot sits. |
| `ANCHOR`     | ~1.15   | xG of an average (d = REF) matchup, home bonus excluded. |
| `HOME_XG`    | ~0.15   | Home-edge xG bonus (home +, away −). Tuned to equal-team home win ~45%. |
| `XG_FLOOR`   | 0.2     | Lower clamp (unchanged). |
| `XG_CEILING` | 3.5     | Upper clamp (new). Rarely binds; guards extreme mismatches. |
| `MINUTES`    | 90      | Match length (unchanged). |
| `FITNESS_FLOOR` | 0.75 | Fitness-decay floor (unchanged). |

`REF` and `ANCHOR` are set from the measured average-matchup differential across the
seeded clubs so the average game keeps ~current goal output; `GAIN` and `HOME_XG` are
tuned against the two win-rate bands.

## Calibration harness

`match/MatchCalibrationTest.java` — a permanent JUnit test that is both the tuning tool
and the regression guard.

- **Teams from real rosters.** Loads seeded squads from `resources/seed/clubs.json`
  (real Catarinense/Série B rosters already in the repo) and selects a **strong**, a
  **mid**, and a **weak** club by squad-average overall. Targets therefore reflect the
  actual league, not synthetic teams. (Reuses the existing seed-loading / test player
  fixtures rather than hand-building XIs.)
- **Deterministic volume.** Runs the cheap **instant** path (`MatchSimulationService` —
  pure arithmetic, no DB) over many matchups with a fixed seed sequence
  (`seed = base + i`), so the statistical test is itself fully deterministic and fast
  (thousands of sims in milliseconds).
- **Asserts tolerance bands** (not exact values, for RNG robustness):
  - Strong (home) vs weak (away) and vice-versa, aggregated: favorite win-rate **60–70%**.
  - Equal teams, one at home: home win **42–48%**, draw **24–28%**.
  - Mean goals per match **2.4–3.0**.

During implementation, `GAIN`/`HOME_XG`/`ANCHOR`/`REF` are tuned until the bands pass;
thereafter the test fails loudly if anyone perturbs the math.

## Testing & determinism impact

- **Determinism is preserved; results change.** `MatchMath` is pure and we change only
  *threshold values*, not the number or order of RNG draws. Same seed still yields one
  deterministic match — a *different* one than before. Every test asserting a specific
  seeded scoreline or match event must have its **golden values regenerated** (the
  established "update seeded expectations first (red)" step from prior features).
- **Likely affected:** the two engines' unit/integration tests, live-match ITs, and
  standings/season-rollover tests that depend on match results.
- **Not affected:** finance tests — `FinanceCalculator.gateIncome` keys off reputation,
  not scores.
- **Step 1 (extraction) breaks nothing** by construction — the suite staying green is
  the proof the refactor introduced no behavior change.

## Sequencing (two isolated phases)

1. **Extract `MatchMath` with zero behavior change.** Move the **current** code verbatim:
   the 2-arg `perMinuteScoreChance(attack, defense)` (the `max(0.2, 1.4 + diff)` form),
   `fitnessFactor`, and the `MINUTES`/`BASE`/`FITNESS_FLOOR`/`HOME_ADVANTAGE` constants.
   Both engines delegate; call sites keep `+ HOME_ADVANTAGE` exactly as today.
   **Full suite stays green** — no seeded expectation changes. This is a pure refactor.
2. **Recalibrate.** Replace the 2-arg formula with the 3-arg gain/re-centre/home-xg form,
   swap `HOME_ADVANTAGE` for the `HOME_XG` term (dropping `+ HOME_ADVANTAGE` at both call
   sites), introduce the new constants, and add `MatchCalibrationTest` (red → tune to
   green). Regenerate the affected seeded expectations across the suite.

## File structure

- **Create:** `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java`
- **Create:** `backend/src/test/java/br/com/carvoeiromanager/match/MatchCalibrationTest.java`
- **Modify:** `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
  (delete private math, delegate to `MatchMath`, drop `+ HOME_ADVANTAGE` at call sites)
- **Modify:** `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java`
  (same)
- **Modify:** the seeded-expectation tests that assert specific scorelines/events
  (enumerated during planning by running the suite after phase 2).

## Global constraints

- Backend only. No migration, no API/DTO change, no frontend change, no new dependency.
- `MatchMath` is a pure static utility (the `SetPieces` pattern): final class, private
  constructor, no Spring, no RNG, no I/O.
- The recalibrated curve reduces to today's formula exactly at
  `GAIN=1, ANCHOR=0.92, REF=-24, HOME_XG=0.10, XG_CEILING=∞`.
- `TeamStrengthCalculator` aggregation is **not** changed (Approach A).
- Card constants (`BASE_CARD_RATE`, `RED_SHARE`) stay local to each engine — out of scope.
- Both engines must apply **identical** scoring math via `MatchMath` (no per-engine
  divergence in the formula).
- Phase 1 must leave the full test suite green (proof of no behavior change) before
  phase 2 changes any number.
- Calibration targets: strong-vs-weak favorite win 60–70%; equal-team home win 42–48%,
  draw 24–28%; mean goals/match 2.4–3.0 — all as tolerance bands.

## Out of scope (later Group 3 sub-features)

- Weather simulation (sub-feature 2).
- Stadiums & visible attendance (sub-feature 3).
- Any change to card/injury/foul/set-piece rates or to team-strength aggregation.
