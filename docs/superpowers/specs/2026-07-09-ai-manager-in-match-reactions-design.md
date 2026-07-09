# AI Manager In-Match Reactions — Design

**Date:** 2026-07-09
**Status:** Approved (brainstormed with user)

## Context

AI clubs now carry a researched tactical identity (formation/mentality/pressing/marking on
`Club`, read by `TacticsService.defaultProfile`), but that identity is frozen for the whole
match: the AI opponent in the user's live match never reacts to the score. The user can already
change tactics mid-match (`TimedTactics` → `LiveMatchEngine.applyTacticChanges` → dirty-flag
strength recompute), and the engine machinery is side-agnostic — nothing produces changes for
the AI side.

This feature gives the AI opponent in the **user's live match** a deterministic "manager brain"
that reacts to score, minute, and red cards: chasing sides open up (up to an all-out 4-2-4),
leading sides shut the game down (down to a 5-4-1 bus), with thresholds shaped by each club's
tactical identity.

**Scope decision (user):** live-match opponent only. Background AI-vs-AI fixtures
(`MatchSimulationService` / `FixtureSimulator`) are untouched — that engine folds tactics in
once, pre-loop, and restructuring it is out of scope.

## Component 1 — `AiManagerBrain` (new, `match/AiManagerBrain.java`)

A pure, stateless, RNG-free decision function (utility class with static methods, like
`LiveCommentary`). No dependencies beyond the tactical enums.

```java
public enum Posture { PARK_THE_BUS, PROTECT, NORMAL, PUSH, ALL_OUT }  // nested in AiManagerBrain

public static Posture decide(Mentality identityMentality, int scoreDiff, int minute, boolean downAMan)
public static TacticalProfile profileFor(Posture posture, TacticalProfile identity)
```

`scoreDiff` is from the AI club's perspective (negative = losing). `downAMan` = the AI side has
received at least one red card.

### Identity offsets

Two minute-offsets derived from the club's identity `Mentality`:

| Identity | `chaseOffset` (applied to chasing thresholds) | `protectOffset` (applied to protecting thresholds) |
|---|---|---|
| ATTACKING | −10 | +5 |
| BALANCED | 0 | 0 |
| DEFENSIVE | +10 | −10 |

Every effective threshold is clamped to a minimum of **46** (no reactions in the first half;
minute 1–45 always returns `NORMAL`, regardless of score or red cards).

### Decision table (evaluated in this precedence order)

1. **`minute < 46`** → `NORMAL`.
2. **Losing by 2+ (`scoreDiff <= -2`):** `minute >= clamp(70 + chaseOffset)` → `ALL_OUT`;
   otherwise `minute >= clamp(46 + chaseOffset)` → `PUSH`; otherwise `NORMAL`.
   (A red card does not stop the chase — `downAMan` is ignored while losing.)
3. **Losing by 1 (`scoreDiff == -1`):** `minute >= clamp(80 + chaseOffset)` → `ALL_OUT`;
   otherwise `minute >= clamp(60 + chaseOffset)` → `PUSH`; otherwise `NORMAL`.
4. **Drawing (`scoreDiff == 0`):**
   - `downAMan` → `PROTECT` (immediately, i.e. from minute 46).
   - else identity ATTACKING → `PUSH` from minute 75 (fixed, no offset — the offset is already
     expressed by this identity-specific rule);
   - else identity DEFENSIVE → `PROTECT` from minute 85 (fixed);
   - else (BALANCED) → `NORMAL`.
5. **Winning (`scoreDiff >= 1`):**
   - `downAMan` → `minute >= clamp(75 + protectOffset)` → `PARK_THE_BUS`, else `PROTECT`
     (immediately from 46).
   - else winning by exactly 1: `minute >= clamp(85 + protectOffset)` → `PARK_THE_BUS`;
     otherwise `minute >= clamp(75 + protectOffset)` → `PROTECT`; otherwise `NORMAL`.
   - else winning by 2+: `minute >= clamp(80 + protectOffset)` → `PROTECT`; otherwise `NORMAL`
     (a two-goal cushion never parks the bus).

The function is memoryless: if the score swings back (the AI equalises while `ALL_OUT`), the
returned posture de-escalates naturally on the next evaluation. That is intended behavior.

### Posture → profile mapping (`profileFor`)

Overrides apply on top of the club's identity profile. Only the four tactical enums are
touched; everything else the profile carries (roles, set-piece takers) is preserved — exactly
the same swap discipline `applyTacticChanges` uses today.

| Posture | Mentality | Pressing | Formation | Marking |
|---|---|---|---|---|
| `ALL_OUT` | ATTACKING | HIGH | `F_4_2_4` | identity |
| `PUSH` | ATTACKING | identity | identity | identity |
| `NORMAL` | identity | identity | identity | identity |
| `PROTECT` | DEFENSIVE | identity | identity | identity |
| `PARK_THE_BUS` | DEFENSIVE | LOW | `F_5_4_1` | identity |

## Component 2 — Engine integration (`LiveMatchEngine`)

- **`LiveMatchConfig`** gains an 11th component: `Boolean aiHome` — which side (if any) is under
  AI-manager control. `null` disables AI reactions entirely (existing pure-simulation tests keep
  their current behavior by passing `null`). `LiveMatchService.configFor` passes
  `!lm.isManagedIsHome()`.
- **Running score:** the engine's minute loop gains `homeGoals`/`awayGoals` counters,
  incremented at the points where `GOAL`/`SET_PIECE_GOAL` events are appended (the instant
  engine already keeps a running score this way; the live engine currently tallies only after
  the loop).
- **Evaluation hook:** each minute, after `applyTacticChanges`/`applySubs` and before the
  dirty-flag strength recompute, when `aiHome != null && minute >= 46`:
  1. `scoreDiff` = AI side goals − opponent goals (score as of the end of the previous minute —
     the natural value at that loop position).
  2. `downAMan` = the AI side has had a red card applied (track a per-side boolean when the
     engine processes a red card).
  3. `posture = AiManagerBrain.decide(identityMentality, scoreDiff, minute, downAMan)` where
     the identity is the AI side's starting profile from the config (for AI clubs that IS the
     club identity, resolved by `TacticsService.defaultProfile` in `configFor`).
  4. If the posture differs from the AI side's current posture (starts at `NORMAL`): set
     `side.currentProfile = AiManagerBrain.profileFor(posture, identity)`, set
     `side.dirty = true`, and append a `TACTICS_CHANGE` timeline event for the AI side carrying
     the posture (see Component 3). Evaluating every minute is cheap and only fires an event
     when the decision output changes — i.e. at threshold minutes and right after goals.

Determinism is preserved by construction: the timeline is already a pure function of the
config, the running score at minute N is deterministic, and the brain is RNG-free — so every
`/advance` re-simulation replays the identical sequence of AI reactions. **No new persisted
state, no migration.**

## Component 3 — Commentary (visibility)

- **`LiveTimelineEvent`** gains a 7th component: `String detail` — `null` for every event except
  AI-produced `TACTICS_CHANGE`, where it holds the `Posture` name. (The record is recomputed on
  every simulate, never persisted — compile-safe change; all construction sites updated.)
- **`LiveCommentary.of`** gains the `detail` parameter. `TACTICS_CHANGE` branch:
  - `detail == null` (user's own change) → current text, unchanged: `"Mudança tática do treinador."`
  - `PUSH` → `"O adversário se lança ao ataque."`
  - `ALL_OUT` → `"Tudo ou nada do adversário: 4-2-4 ofensivo!"`
  - `PROTECT` → `"O adversário recua para segurar o resultado."`
  - `PARK_THE_BUS` → `"Adversário fecha-se na defesa: 5-4-1."`
  - `NORMAL` (de-escalation back to identity) → `"O adversário reorganiza a equipe."`
- **No frontend changes:** the timeline already renders `TACTICS_CHANGE` (📋 icon) with the
  backend-provided `text`; the AI's reactions appear in the "Lances" feed automatically.

## Out of scope (explicit)

- AI substitutions (injured/hobbled AI players still play on at the hobble multiplier).
- Background AI-vs-AI fixtures — the instant engine remains tactics-static per match.
- Reactions to anything besides score/minute/own red card: no momentum, no fitness- or
  morale-based decisions, no reacting to the USER's tactical changes.
- The user's own side is never touched by the brain.

## Testing

- **`AiManagerBrainTest`** (unit, table-driven): full posture matrix — each score band
  (−2, −1, 0, +1, +2) × key minutes including exact threshold boundaries (e.g. 59' vs 60' for
  the balanced losing-by-1 PUSH) × all three identities; first-half lockout (minute 45 always
  NORMAL); red-card rules (drawing → PROTECT, winning late → PARK_THE_BUS, losing → chase
  unaffected); clamp-to-46 cases (attacking identity losing by 2 pushes at 46, not 36);
  `profileFor` mapping including preservation of identity marking/roles.
- **Engine integration (extend live-match engine/service ITs):**
  - Seeded config where the AI side trails in the second half → timeline contains an AI-side
    `TACTICS_CHANGE` with the expected posture detail, and the subsequent goal probabilities
    reflect the recomputed strength (assert via the changed profile on the side, or a
    deterministic outcome shift).
  - Determinism: simulating the same config twice yields identical timelines (including AI
    reaction events).
  - `aiHome = null` → no AI `TACTICS_CHANGE` events ever (backward-compat path).
  - The managed side never receives brain-produced changes; a user `TimedTactics` and an AI
    reaction coexist in one match.
  - Commentary mapping: `detail` → exact pt-BR strings; user change still gets the generic text.
- **Frontend:** no changes expected; existing live-match page tests remain green.
