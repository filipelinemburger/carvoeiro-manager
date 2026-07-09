# AI Substitutions in Live Matches — Design

**Date:** 2026-07-09
**Status:** Approved (brainstormed with user)

## Context

The AI opponent in the user's live match now reacts tactically to the score
(`AiManagerBrain`), but it never makes a substitution: the non-managed side's bench is
hard-coded to `List.of()` at kickoff (`LineupService.benchPlayers` returns empty for clubs
without a lineup row), so an injured AI player hobbles at ×0.5 effectiveness for the rest of
the match, tired legs are never freshened, and posture changes never bring matching personnel.

This feature gives the AI side a real bench and a deterministic, RNG-free in-engine
substitution brain — the same pattern as the tactics reactions: decisions are a pure function
of the match config, replayed identically on every `/advance` re-simulation, with no new
persisted state.

**User decision:** full trigger set — injury + posture + fatigue substitutions.

## Component 1 — AI bench (`LineupService.bestBench`)

```java
public List<Player> bestBench(UUID clubId, List<Player> xi)
```

Mirrors `bestXi`'s population and filters: candidates are the club's non-youth players
(`findByClubIdAndYouthProspectFalse`) that are `isEligible()` and NOT in the given XI.
Composition:

1. The best remaining eligible GK, if one exists (injury cover for the keeper).
2. Then the best other candidates by raw `overall` (descending; ties by squad-query order),
   up to a total bench of **7**.

Fewer candidates → smaller bench (possibly empty). No ineligible fallback — unlike the XI,
a short bench is fine.

**Kickoff wiring (`LiveMatchService.kickoff`):** the non-managed side's bench becomes
`lineupService.bestBench(aiClubId, aiXi)` instead of `List.of()`. The ids persist in the
existing `LiveMatch` bench TEXT columns — no migration. `configFor` already resolves both
benches and computes kickoff fitness for every XI + bench player, so nothing else changes
there. **Old-save compatibility:** an in-progress `LiveMatch` row persisted before this
feature has an empty AI bench → the brain finds no candidates → zero AI subs; nothing breaks.

## Component 2 — `AiSubstitutionBrain` (new, `match/AiSubstitutionBrain.java`)

Pure, stateless, RNG-free utility (same style as `AiManagerBrain`). One decision per call:

```java
public record SubDecision(UUID outId, UUID inId) { }

public static Optional<SubDecision> decide(
        int minute,
        AiManagerBrain.Posture posture,
        boolean postureSubMade,          // a posture sub already happened this match
        int fatigueSubsMade,             // fatigue subs so far this match
        int totalSubsMade,               // all AI subs so far this match
        List<Player> onPitch,            // current on-pitch players, in on-pitch order
        Set<UUID> hobbled,               // hobbled on-pitch ids
        List<Player> benchAvailable,     // bench players not yet brought on, bench order
        Map<UUID, Integer> kickoffFitness)
```

At most ONE substitution per evaluated minute. Rules in strict priority order (first match
wins); all selection is deterministic — "best" = highest `overall`, ties broken by list order
(first wins):

1. **Injury** — allowed while `totalSubsMade < 5`: scan hobbled on-pitch players in on-pitch
   order and substitute the FIRST one that has positional cover — the best same-`Position`
   bench player (a hobbled GK only by a bench GK). A hobbled player without cover is skipped
   (he keeps hobbling, as today) and the scan continues to the next hobbled player.
2. **Posture** — allowed while `totalSubsMade < 4` (one sub is always held back for injuries)
   and `!postureSubMade` (max one posture sub per match):
   - `posture == ALL_OUT`: best bench ATT comes on for the lowest-overall on-pitch DEF —
     only if the pitch currently has **more than 3 DEF** and a bench ATT exists.
   - `posture == PARK_THE_BUS`: best bench DEF comes on for the lowest-overall on-pitch ATT —
     only if the pitch currently has **more than 1 ATT** and a bench DEF exists.
   - Other postures: no posture sub.
3. **Fatigue** — allowed while `totalSubsMade < 4`, `fatigueSubsMade < 2`, and `minute >= 65`:
   the on-pitch non-GK player with the lowest kickoff fitness (ties by on-pitch order), if
   that fitness is **below 70**, is replaced by the best same-`Position` bench player. No
   same-position cover → no fatigue sub this minute.

`decide` never returns a hobbled/on-pitch-invalid pairing by construction: `benchAvailable`
is the caller's not-yet-used bench list, and each returned `inId` is consumed by the engine.

## Component 3 — Engine integration (`LiveMatchEngine`)

- The AI side's bench players are already in `Side.all` (buildSide adds bench). The engine
  tracks, for the AI side only: `aiSubsMade`, `aiFatigueSubsMade`, `aiPostureSubMade`, and the
  set of bench ids already brought on (derivable from `entryMinute`).
- **Hook placement:** in the minute loop, immediately AFTER the `reactAiManager` posture
  evaluation (so a posture escalation and its personnel sub land in the same minute) and
  before the dirty-flag strength recompute. Runs when `aiSide != null` — every minute (injury
  subs may happen in the first half; the posture/fatigue rules gate themselves by minute).
- **Applying a decision** mirrors `applySubs` exactly: swap in `onPitch` (same index),
  `exitMinute`/`entryMinute` bookkeeping, `side.dirty = true`, and append a normal
  `SUBSTITUTION` timeline event `(minute, SUBSTITUTION, side.home, outId, inId, null, null)`.
  The feed already renders it ("Substituição: entra X, sai Y.") with away-side styling — no
  commentary or frontend change.
- Determinism: the brain consumes zero RNG and reads only deterministic state (hobbled flags
  derive from the injury stream, which is unchanged). Timelines remain pure functions of the
  config. AI subs shift strength and therefore outcomes for existing seeds — accepted, as
  with every engine change.

## Untouched by design (verified seams)

- Managed side: `subsRemaining` counts only managed schedule entries; the brain never touches
  the managed side; the user's batch-subs endpoint is unaffected.
- `statusAt`/pauses: SUBSTITUTION is not a pause trigger; AI subs are just feed entries.
- Minutes/fitness/appearances: `finish()`'s `playedPlayers` already covers BOTH sides' XI +
  bench via `timeline.minutesPlayed()` — AI bench players who came on get real minutes and
  post-match fitness decay with no changes.
- Instant AI-vs-AI engine (`MatchSimulationService`/`FixtureSimulator`): untouched — no subs
  concept there.

## Out of scope (explicit)

- Red-card adjustments (no GK-for-outfielder swap after a keeper is sent off; the side plays
  on as today).
- AI reacting to the USER's substitutions or lineup.
- Per-player in-match fatigue modeling (kickoff fitness is the deterministic signal).
- Any UI change.

## Testing

- **`AiSubstitutionBrainTest`** (unit, table-driven): priority order (hobbled beats posture
  beats fatigue in the same minute); injury cover rules (same-position best, hobbled GK needs
  bench GK, no cover → skip to next hobbled/none); posture rules (ALL_OUT and PARK selections,
  DEF > 3 / ATT > 1 floors, one-per-match, blocked at `totalSubsMade == 4`); fatigue rules
  (minute 64 vs 65 boundary, fitness 69 vs 70 boundary, cap 2, non-GK only, held-back rule);
  determinism of tie-breaks; empty bench → always empty.
- **`LineupService` `bestBench` tests:** filters (youth/ineligible/XI excluded), GK-first
  composition, cap 7, empty when no candidates.
- **Engine tests (`LiveMatchEngineTest`):** with an AI bench configured — (a) seeded sweep: an
  AI hobble with same-position cover is followed by an AI-side SUBSTITUTION event, and the
  subbed-off player's minutes end at that minute; (b) AI subs never exceed 5 across seeds, and
  non-injury subs never push past 4 before an injury needs the 5th; (c) empty AI bench → zero
  AI-side SUBSTITUTION events (old-save compat); (d) determinism — same config twice →
  identical timelines; (e) the managed side's schedule-driven subs coexist with AI subs.
- **Kickoff IT:** the non-managed side persists a bench (≤ 7, GK included when available) and
  `configFor` resolves it.
