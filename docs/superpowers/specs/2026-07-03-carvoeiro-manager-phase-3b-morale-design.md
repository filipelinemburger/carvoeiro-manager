# Phase 3b — Player Morale (Design)

**Status:** approved (2026-07-03)

**Goal:** Give each player a morale that rises and falls with results, playing time, and standout
match events, and let that morale nudge their on-pitch performance — adding a "dressing room"
dimension to squad management. Morale mirrors the existing `form` system so it plugs into the same
places with minimal new surface.

## Scope

**In scope**
- Per-player morale, stored as an int clamped **[−5, +5]**, neutral **0**.
- Morale updates after every match the player's club plays.
- Individual morale feeds the match engine as a per-player strength multiplier (both the live and the
  instant simulation, via the single shared `TeamStrengthCalculator`).
- A derived, display-only **team morale** = squad average, shown as a "dressing room" mood.
- UI: a mood chip per player on the squad screen and a team-morale chip in the squad header.

**Out of scope (explicitly declined during brainstorming — YAGNI)**
- Winning/losing **streak** amplification.
- **Off-pitch** drivers (contract/transfer unhappiness).
- A **separate team-morale mechanic** with its own engine effect (team morale is display-only).

## Model

Morale deliberately parallels `form`:

| Concept | form (existing) | morale (new) |
|---|---|---|
| Storage | `players.form` int [−5,+5], default 0 | `players.morale` int [−5,+5], default 0 |
| Mutator | `applyForm(delta)` (clamps) | `applyMorale(delta)` (clamps) |
| Season reset | zeroed in `resetSeasonState()` | zeroed in `resetSeasonState()` |
| Engine effect | `(1.0 + 0.02 * form)` | `(1.0 + 0.02 * morale)` |
| Post-match update | in `MatchOutcomeApplier` | in `MatchOutcomeApplier` (sibling logic) |

### Data model

- Add `Player.morale` (int, `@Column(name = "morale", nullable = false)`, default 0) with
  `getMorale()` and `applyMorale(int delta)` that clamps to [−5, +5].
- Zero morale in `Player.resetSeasonState()` alongside form/fitness.
- Migration **`V18__morale.sql`**: `ALTER TABLE players ADD COLUMN morale INT NOT NULL DEFAULT 0;`

### Engine effect (single point, covers live + instant sims)

`TeamStrengthCalculator.compute` currently builds a per-player multiplier:

```java
double mult = (1.0 + 0.02 * p.getForm()) * fitnessMult;
```

becomes:

```java
double mult = (1.0 + 0.02 * p.getForm()) * (1.0 + MORALE_COEFF * p.getMorale()) * fitnessMult;
```

with `MORALE_COEFF = 0.02` → morale ±5 gives ±10% per player, the same weight as form.

**Determinism / backward compatibility:** every player defaults to morale 0, at which the morale term
is exactly ×1.0. So any simulation over neutral-morale squads is byte-identical to today, and existing
seeded tests stay green until morale actually diverges across rounds. `TeamStrengthCalculator` is the
only strength path — used by `LiveMatchEngine`, `FixtureSimulator`, and `LineupService.bestXi` — so a
single change covers the user's live match, all AI fixtures, and AI rotation consistently.

### Post-match morale update (`MatchOutcomeApplier`, sibling to the form update)

Applied once per match to the club's players, using the same played/non-played split the form update
already uses.

**Players who featured** (starters + substitutes who came on — the `TeamOutcome.xi()` set):
- result: **win +1 / draw 0 / loss −1**
- **scored at least one goal → +1** (flat, not per-goal, to keep per-match swings modest)
- **received a red card → −1**

Net per-match swing is small (roughly −2..+2), then `applyMorale` clamps to [−5, +5].

**Unused squad members** (bench players who did not come on, and squad members not selected — the
`applyNonPlayers` set): morale drifts one step toward **−1** (if `morale > −1` then −1; if `morale < −1`
then +1). Reserves settle mildly unhappy but rebound when they get minutes on a winning side. This is
bounded — no runaway spiral to −5 from inactivity alone.

The existing form update is untouched; morale is added as parallel logic in the same methods
(`applyTeam` for players, `applyNonPlayers` for the rest).

**Both sim paths are already covered.** `applyTeam` runs via `applyFixture` (called from the live
`finish()` for the managed XI and from `FixtureSimulator` for AI fixtures). `applyNonPlayers` runs
once per round inside `FixtureSimulator.simulateRound`; in the live path `completeUserMatch` invokes
that with `alreadyPlayedElsewhere = userPlayedIds` (only the user's *played* players), so the managed
club's unused reserves fall through into the non-players set and get the drift. No new wiring needed —
only the morale delta logic is added inside these two existing methods.

### Team morale (aggregate, display-only)

Team morale is **not** stored. `morale` is exposed on `PlayerResponse` (already returned by the squad
API), and the frontend computes the squad average for the "dressing room" chip. There is no separate
team-morale engine effect — only individual morale influences matches.

## API

- `PlayerResponse` gains `morale: int`. No new endpoints. (Squad screen already fetches the squad via
  the existing squad endpoint.)

## UI

Mood buckets (label + emoji), from the [−5, +5] value:

| Range | Emoji | Label (pt-BR) |
|---|---|---|
| ≤ −3 | 😞 | Desanimado |
| −2, −1 | 😕 | Descontente |
| 0 | 😐 | Neutro |
| +1, +2 | 🙂 | Contente |
| ≥ +3 | 🤩 | Radiante |

- **Squad rows:** a small morale mood chip (emoji + label) beside the existing form indicator.
- **Squad header:** a team-morale chip = squad average mapped to the same bucket scale ("dressing
  room" mood).
- A shared helper (e.g. `moraleMood(value)`) maps a value to `{ emoji, label, color }` so player rows
  and the team chip render identically.

## Testing

**Backend (unit, Given/When/Then):**
- `applyMorale` clamps to [−5, +5]; `resetSeasonState` zeroes morale.
- `MatchOutcomeApplier`: winner who played gains morale; loser loses; a scorer gets the extra +1; a
  red card costs −1; an unused reserve drifts toward −1; a very-low reserve does not drop below the
  clamp; team-result deltas combine as specified.
- `TeamStrengthCalculator`: morale +5 raises a player's contribution ~10%, −5 lowers it ~10%, morale 0
  leaves the multiplier unchanged (regression guard for determinism).

**Backend (integration):** existing live/season ITs remain green (neutral morale = no change); one
added assertion that a full match round moves the managed club's morale.

**Frontend (RTL):** `moraleMood` bucket mapping; a squad row renders the correct mood chip; the squad
header renders the team-average mood.

## Rollout / risk notes

- The engine change is behind the morale value; because default morale is 0 and the multiplier is 1.0
  there, no existing behavior changes until players accumulate morale over played rounds.
- form and morale each swing a player ±10% at the extremes, so a player who is both in-form and
  high-morale is ~+21% and one who is out-of-form and demoralized ~−19% — meaningful but rare (needs
  sustained results). `MORALE_COEFF` is a single tunable constant.

## File touch list

- `backend/.../player/Player.java` — `morale` field, `getMorale`, `applyMorale`, reset.
- `backend/src/main/resources/db/migration/V18__morale.sql` — new column.
- `backend/.../match/TeamStrengthCalculator.java` — morale multiplier.
- `backend/.../match/MatchOutcomeApplier.java` — morale update (played in `applyTeam` + unused in `applyNonPlayers`).
- `backend/.../match/PlayerResponse.java` — add `morale` (this record already carries `form`; morale sits beside it). Populated wherever `PlayerResponse` is built from a `Player`.
- `frontend/src/api/types.ts` — `morale` on the `PlayerResponse` type (already has `form`).
- `frontend/src/features/squad/` — `moraleMood` helper, player mood chip, team-morale header chip.
- Tests alongside each of the above.
