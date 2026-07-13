# Mateus Mastella-Style Commentary — Design

**Date:** 2026-07-13
**Status:** Approved

## Goal

Rewrite live-match commentary text to sound like real Brazilian narrator Mateus Mastella
(Criciúma's actual radio/stream narrator, known for raw, spontaneous, fan-first "raiz"
delivery), expand variant coverage for freshness across a season, and give two of his real,
verified catchphrases signature placement tied to real match-state triggers rather than random
rotation.

This is Group 3 of the 10-item enhancement roadmap (`~/.claude/plans/imperative-strolling-parasol.md`).

## Background

- `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java` is the **sole**
  source of live-match commentary text — one call site, `LiveMatchService.toState()`
  (`LiveMatchService.java:399`), inside the `.stream().map(...)` that builds each match's
  `LiveEventResponse` list from its revealed timeline events.
- Today: 13 `EventType` categories (`GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE,
  INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_GOAL, FREE_KICK_GOAL, CORNER_GOAL,
  PENALTY_MISSED`), ~30 lines total (~2.3 variants/category), selected deterministically via
  `Math.floorMod(minute + type.ordinal(), lines.length)` — no randomness, replay-stable.
- Every `LiveMatch` row is inherently the managed club's own fixture (`isManagedIsHome()` is
  meaningless otherwise), and the managed club is hardcoded to Criciúma. **Every live-commentated
  match is already a Criciúma match** — no club-conditional branching is needed anywhere in this
  work.
- Research (web search, cited below) confirms two real, verifiable elements of Mastella's style:
  a signature catchphrase born from a real moment with CRI goalkeeper Gustavo, and a documented
  reputation for raw, unfiltered, passionate "raiz" delivery — including real (mild) profanity in
  genuine moments of frustration.

## Content

### Regular category rewrite

All 13 categories rewritten in Mastella's voice — dramatic, exclamation-heavy, spontaneous,
fan-first — expanding from ~2.3 to **5-6 variants per category** (~70-80 lines total). Deterministic
selection (`Math.floorMod`) is unchanged; only the line pools grow. No manufactured profanity in
this general pool — see the two signature lines below for the one deliberate exception.

### Two signature lines (real quotes, used verbatim, condition-gated — never mixed into random rotation)

1. **"Homens de pouca fé... homens de muita fé!"** — his most iconic line, born from a real
   redemption moment. Fires when Criciúma scores a goal that **erases a deficit**: an equalizer
   or go-ahead goal scored while CRI was previously behind. Replaces the normal GOAL-family
   variant selection entirely when the condition holds — this is a signature moment, not one more
   option in the pool.
2. **"E não adianta me ligar que eu vou mandar a merda."** — his real words, the one place actual
   (mild) profanity appears in this codebase's commentary, deliberately, for authenticity. Fires
   at half-time when Criciúma has **lost each of its last 2 completed fixtures** (across both
   competitions, most recent 2 by match date). Replaces the generic "Intervalo — fim do primeiro
   tempo." line for that one match.

Sources on Mastella's real style and catchphrase, consulted during brainstorming:
[Rádio Araranguá profile](https://radioararangua.com.br/mastella-o-narrador-que-comecou-na-sala-dos-pais-e-viralizou-na-argentina-espanha-e-italia/).

## Architecture

`LiveCommentary.of()` stays a pure function (no DB access, no hidden state — matches its current
design), but gains two new caller-computed booleans:

```java
public static String of(EventType type, String playerName, String inName, int minute,
                         String detail, boolean comebackGoal, boolean losingStreakHalfTime)
```

- `comebackGoal`: true only when `type.isGoal()` (reusing the existing `EventType.isGoal()`
  helper) **and** the scoring side is the managed club **and** the managed club's score before
  this event was strictly less than the opponent's. When true, `of()` returns the signature
  comeback line instead of picking from the GOAL-family pool.
- `losingStreakHalfTime`: true only when `type == HALF_TIME` and the streak condition (below)
  holds for this match. When true, `of()` returns the signature streak line instead of the
  generic half-time text.
- For every other call, both flags are irrelevant and ignored — the existing deterministic
  variant-pool logic runs unchanged.

### Computing `comebackGoal` — running score in `LiveMatchService.toState()`

The current `revealed.stream().map(...)` is stateless and already processes `revealed` in
chronological order (events are revealed through `eventsThrough(minute)`, itself minute-ordered).
Replace the stream with an explicit loop that carries two running counters (`homeGoals`,
`awayGoals`) forward across iterations:

- For each event, before applying its effect: if `e.type().isGoal()` and `e.home() ==
  lm.isManagedIsHome()` (this goal belongs to the managed side), compute `comebackGoal =
  managedGoalsSoFar < opponentGoalsSoFar` using the counters' state as of *before* this event.
  Otherwise `comebackGoal = false`.
- After computing the flag (and building the `LiveEventResponse` for this event), if
  `e.type().isGoal()`, increment the appropriate counter (`homeGoals` or `awayGoals` per
  `e.home()`).

This only touches `toState()`'s event-building loop — no new fields, no persistence, computed
fresh from the timeline every time (same "replay is stable" guarantee the class already has,
since the timeline itself is deterministic).

### Computing `losingStreakHalfTime` — a small repository addition + a helper

`FixtureRepository` currently has no "all fixtures for a save game" query (only
competition-scoped ones). Add:

```java
List<Fixture> findBySaveGameId(UUID saveGameId);
```

Then, once per `toState()` call (not per-event — the result is the same for every event in one
match): fetch all fixtures for the save game, filter to `isPlayed()` and involving the managed
club (`homeClubId` or `awayClubId` equals managed club id), sort by `matchDate` descending (then
`round` descending as a tiebreaker), take the first 2. If there are at least 2, and the managed
club's goals were strictly less than the opponent's in **both**, `losingStreakHalfTime = true` for
every `HALF_TIME` event in this match (there is normally exactly one per match). Fewer than 2
prior played fixtures (e.g., round 1 or 2 of a season) — condition is false, never fires.

## Attribution

A small caption directly under the live commentary feed (the "Lances" tab in
`frontend/src/features/match/LiveMatchPage.tsx`, around where the feed renders today) — e.g.
"Narração no estilo de Mateus Mastella." Scoped tightly to the commentary feature, not a general
UI addition. This credits a real, living private individual who has no involvement in this
project; acceptable here because this is a personal, non-commercial project, but noted
explicitly as a real (low, but nonzero) publicity-rights consideration.

## Testing

- `LiveCommentary`: unit tests per category confirming the expanded pools still resolve
  deterministically (same minute+type always yields the same line), and that the two signature
  lines return exactly their verbatim text when their boolean is true, regardless of what the
  normal pool would have picked.
- `LiveMatchService`/`LiveMatchServiceIT` (or wherever the existing kickoff/advance integration
  tests live): a scenario where the managed club goes 0-1 down then equalizes — asserts the
  equalizing event's commentary is the exact comeback line, and that a non-comeback goal (already
  leading, or extending a lead) is not affected. A scenario with 2 prior recorded losses for the
  managed club — asserts the half-time line is the exact streak line; a scenario with only 1 prior
  loss (or a win/draw mixed in) — asserts the generic half-time line still appears.
- Full backend suite green before completion, per project convention.

## Out of scope

- Any UI/text outside live-match commentary (buttons, toasts, empty states, other screens) — per
  the earlier roadmap scoping decision.
- Extending profanity or this raw tone to any other line beyond the one verbatim streak quote.
- A new `FULL_TIME` (or `KICKOFF`) `EventType` — the streak line uses the existing `HALF_TIME`
  hook instead, since inventing a new event type (and wiring it through the simulation engine)
  is out of proportion for one flavor line.
- Applying this voice to non-Criciúma matches — moot, since no such live-commentated match exists
  in this game today (see Background).
