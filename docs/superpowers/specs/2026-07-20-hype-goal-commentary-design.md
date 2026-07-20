# Hype Goal Commentary — Design

## Context

`LiveCommentary`/`LiveMatchService` already has a precedent for appending a signature line
after a goal's normal commentary: `comebackGoal` (a goal that erases a deficit for the managed
club) appends `COMEBACK_GOAL_LINE` after the normal goal text
(`LiveCommentary.of`, `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java:142`).
There's also a precedent for computing a form-based signal from completed fixture history:
`isLosingStreak`/`losingStreakHalfTime` (`LiveMatchService.java:510-535`) checks whether the
managed club's last 2 completed fixtures were both losses, and overrides the half-time
commentary pool when true.

This cycle adds a third signature line, reusing both patterns: a hype line that appends after a
goal when the managed club (Criciúma, in this save) is either riding a hot streak coming into
the match, or has just taken a big lead within the match itself.

## Trigger conditions

Either condition independently qualifies a goal for the hype line:

1. **Streak condition** — evaluated once per match, from completed fixture history *before* this
   match (same fixtures the existing `losingStreakHalfTime` already queries):
   - the managed club's most recent 3 completed fixtures were all wins, OR
   - the managed club's most recent 5 completed fixtures included no loss (draws are fine).
2. **Margin condition** — evaluated per goal, within this match: the goal being scored brings
   the managed club's in-match lead to 3 or more goals (i.e. `managedGoals - opponentGoals >= 3`
   immediately after this goal).

The line appears **once per match**, on whichever managed goal is the first to satisfy either
condition. Later goals in the same match — even ones that would also qualify (e.g. extending a
3-goal lead to 4, or a second goal while already on a streak) — get normal commentary only.

If a single goal happens to satisfy both the hype condition and the existing comeback condition
(e.g. erasing a deficit while already on a win streak — rare but possible), both signature lines
append, back to back, after the normal goal text. They are independent, additive lines; neither
takes priority over the other.

## Backend changes

**`LiveMatchService`** (`backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`):

- Add `static boolean isHypeStreak(List<Fixture> mostRecentFirst, UUID managedClubId)` — sibling
  to the existing `isLosingStreak` (line 510), same static/testable shape. Uses a new
  `wonBy(Fixture, UUID)` helper (symmetric to the existing `lostBy`, line 517) for the 3-win
  check, and `!lostBy(...)` for the 5-unbeaten check.
- Add `static boolean isHypeGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome, boolean enteringMatchOnStreak)`
  — sibling to `isComebackGoal` (line 488), same scanning style: walk `events` up to (not
  including) `event`, tracking managed/opponent goal counts, and return `false` immediately if an
  *earlier* managed goal already qualified (streak condition is goal-independent, so on a streak
  only the first managed goal ever qualifies; otherwise track whether an earlier goal already hit
  the 3-goal margin). Otherwise, `event` qualifies if `enteringMatchOnStreak` is true, or if this
  goal's own resulting margin is `>= 3`.
- Extract the fixture fetch/filter/sort block currently inlined in `losingStreakHalfTime`
  (lines 527–533: `fixtureRepository.findBySaveGameId(...)`, filter played + club involvement,
  sort by match date/round descending) into a private helper, e.g.
  `recentCompletedFixtures(UUID saveGameId, UUID managedClubId): List<Fixture>`. Both
  `losingStreakHalfTime` and the new `hypeStreak(...)` method call it — avoids duplicating the
  `nullsFirst` comparator dance.
- In `toState()` (line 413 onward): compute `boolean enteringMatchOnStreak = hypeStreak(lm.getSaveGameId(), save.getManagedClubId())`
  once, alongside the existing `losingStreak` computation (line 422). Inside the events-mapping
  stream (lines 423–431), compute `isHypeGoal(revealed, e, lm.isManagedIsHome(), enteringMatchOnStreak)`
  alongside the existing `isComebackGoal(...)` call, and pass it as a new argument to
  `LiveCommentary.of(...)`.

**`LiveCommentary`** (`backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`):

- Add a constant:
  `HYPE_GOAL_LINE = "Olha, eu não quero dizer nada, mas esse Criciúma já ta me cheirando a libertadores... Cruuuzisss!!"`
- Add a `boolean hypeGoal` parameter to `of(...)` (after `comebackGoal`, before
  `losingStreakHalfTime`). Update the existing append line
  (`return comebackGoal && type.isGoal() ? base + " " + COMEBACK_GOAL_LINE : base;`) to also
  append `HYPE_GOAL_LINE` when `hypeGoal && type.isGoal()`, independently of `comebackGoal` — both
  can append in the same call.
- Update the class-level Javadoc (lines 108–117) to document the new parameter the same way
  `comebackGoal` and `losingStreakHalfTime` are documented today.

No other files change. The frontend already renders `LiveEvent.text` verbatim
(`frontend/src/features/match/LiveMatchPage.tsx:249`) — the longer combined string needs no
frontend changes.

## Testing

- **`LiveMatchServiceCommentaryTest`**: new tests for `isHypeStreak` (3 wins in a row → true;
  2 wins + a draw → false for the 3-win leg but check the 5-unbeaten leg separately; unbeaten
  over exactly 5 with a draw inside → true; a loss anywhere in the last 5 → false; fewer than 3
  or 5 completed fixtures → false for the respective leg) and `isHypeGoal` (margin reaches 3 on
  this goal → true; margin already reached 3 on an earlier goal, this is the 4th → false;
  entering on a streak, first managed goal → true regardless of margin; entering on a streak,
  second managed goal → false; opponent goal never qualifies).
- **`LiveCommentaryTest`**: a test mirroring
  `comebackGoalAppendsTheSignatureLineAfterTheNormalGoalDescriptionForEveryGoalFamilyType` — the
  hype line appends after the normal goal text for each goal-family `EventType`. A test asserting
  that when `comebackGoal` and `hypeGoal` are both true, both lines are appended in order.
- **New integration test** `LiveMatchHypeStreakIT`, mirroring `LiveMatchLosingStreakIT`: seed 3
  consecutive won fixtures for the managed club, play a live match via
  `liveMatchService.kickoff`/`advance`, and assert the first managed goal's event text ends with
  `HYPE_GOAL_LINE`. A second test seeds no streak and asserts a goal that takes the score to a
  3-0 managed lead gets the line on that goal specifically, not the 1st or 2nd goal.

## Out of scope

- No frontend changes (existing text rendering already handles arbitrary-length strings).
- No change to `comebackGoal` or `losingStreakHalfTime` behavior — additive only.
- No generalization beyond the managed club — this mirrors every existing signature-line
  feature (comeback, losing streak), which are managed-club-relative, not a general "any club"
  system.
