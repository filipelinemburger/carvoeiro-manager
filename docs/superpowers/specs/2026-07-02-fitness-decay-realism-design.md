# Fitness Decay Realism Tuning

## Context

Phase 3a-1 introduced per-match fitness decay:
`decay = DECAY_BASE(30) × clamp(70/stamina, 0.7, 1.4) × AgeBand.decayFactor()`, recovered at
`RECOVERY_BASE(5)/day`. Two realism problems surfaced in play:

1. **Goalkeepers tire like outfielders** — decay is position-independent, so a keeper (who covers
   roughly half an outfielder's match distance) loses ~29 condition per match, the same as a
   winger. After 3 matches a GK sat at ~63%.
2. **The overall curve is too steep** — outfield starters dropped to ~53–75% after only 3 matches,
   which feels punishing rather than realistic.

## Decision (locked with the user)

- Add a **position-aware decay factor**.
- **Moderately soften** the global curve (keep rotation meaningful in congestion, but not brutal).

### New parameters

| Parameter | Old | New |
|-----------|:---:|:---:|
| `DECAY_BASE` | 30 | **24** |
| `RECOVERY_BASE` | 5 /day | **6 /day** |

| Position | decay factor |
|----------|:---:|
| `GK` | **0.45** |
| `DEF` | **0.9** |
| `MID` | **1.0** |
| `ATT` | **1.0** |

`decay = DECAY_BASE × clamp(70/stamina, 0.7, 1.4) × AgeBand.decayFactor() × positionFactor`.

**Expected effect** (outfielder, stamina 70, prime): ~24 lost/match; a ~weekly schedule stays near
100%; congested midweek runs still dip but land ~80% after 3 matches (was ~55%). A GK loses ~11/match
→ stays ~95–100% and rarely needs resting. Injury risk (fitness-linked) eases correspondingly.

## Changes

- `FitnessCalculator`:
  - `RECOVERY_BASE = 6.0`, `DECAY_BASE = 24.0`.
  - `decayedBaseFitness(int effectiveAtKickoff, int stamina, int age, Position position)` — new
    `Position` parameter; multiply decay by a private `positionDecayFactor(position)`
    (GK 0.45 / DEF 0.9 / MID 1.0 / ATT 1.0). The factor lives in `FitnessCalculator` so the
    `Position` enum stays unaware of fitness concerns.
- `FixtureSimulator.applyFitnessDecay` passes `p.getPosition()` to `decayedBaseFitness`.

No API/DTO/frontend change — the squad screen already displays the resulting fitness %.

## Testing

- `FitnessCalculatorTest`:
  - Update the recovery pin: base 60, prime, 3 days rest → `60 + 3×6 = 78` (was 75).
  - Update the decay pin to the new constant + a `MID`: `decayedBaseFitness(100, 70, 27, MID) == 76`
    (decay 24).
  - Add a position case: a `GK` loses far less than an outfielder from the same kickoff —
    `decayedBaseFitness(100, 70, 27, GK)` (≈89) `>` `decayedBaseFitness(100, 70, 27, MID)` (76).
  - Keep the stamina-ordering assertions (pass position `MID`).
- Other suites unaffected: `FitnessLifecycleIT` asserts only that players who feature drop below 100
  and rested players stay 100 (true for any positive decay); `TeamStrengthCalculatorTest` /
  `MatchOutcomeApplierTest` use `fitnessMult` / `injuryChance`, which are unchanged.

## Verification

- `./gradlew test --tests "br.com.carvoeiromanager.match.FitnessCalculatorTest"` +
  `--tests "br.com.carvoeiromanager.match.*"` green.
- Manual: play a few congested rounds — GK condition stays high, outfield condition eases gently.
