# Second Yellow = Red Card — Design

## Context

Both simulation paths can currently give the same player two (or more) yellow cards in one
match with no consequence:

- **Live engine** (`LiveMatchEngine.maybeCard`, `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:321`):
  each card roll picks a player by tackling weight with no memory of prior bookings. Only
  straight reds (`RED_SHARE` of card rolls) remove a player from the pitch.
- **Instant sim** (`FixtureSimulator.saveEvents`, `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java:197`):
  abstract card events from `MatchSimulationService` are attributed to the full XI after the
  fact, so a player can collect two yellows there too — and a sent-off player can even be
  attributed later events (goals, more cards), since the XI list is never mutated.

Real football rule (and the user's request): a second yellow in the same match comes **with**
a red card — the player is sent off. The timeline must show the second yellow followed by the
red, as on TV.

## Decisions

- **No new `EventType`.** A second-yellow send-off is represented as a YELLOW_CARD event plus a
  RED_CARD event in the same minute for the same player. Nothing ripples into frontend icon
  mapping, stats counting (which counts `match_events` rows by type), or persistence
  (`match_events` has no detail column — two rows, `YELLOW_CARD` then `RED_CARD`).
- **Suspension accounting (user-chosen, CBF-realistic):** for any player red-carded in a match
  (second yellow *or* straight red), that match's yellows do NOT count toward the
  every-3-yellows accumulation tally (`Player.addYellowCard` is skipped). Only the red's
  standard 1-match ban applies. Season stats still record every card shown, because
  `PlayerStatsService` counts `match_events` rows, not the tally.
- **Dedicated commentary** for a second-yellow red in the live match, driven by
  `LiveTimelineEvent.detail = "SECOND_YELLOW"` on the RED_CARD event (the `detail` field is
  in-memory/timeline-only; it is not persisted to `match_events`).

## Backend changes

**`LiveMatchEngine`** (`backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`):

- `Side` gains `final Set<UUID> booked = new HashSet<>()`.
- `maybeCard`:
  - YELLOW_CARD pick on an **unbooked** player → emit yellow, add to `booked` (unchanged
    otherwise).
  - YELLOW_CARD pick on a **booked** player → emit the YELLOW_CARD event, then immediately a
    RED_CARD event (same minute, same player, `detail = "SECOND_YELLOW"`), remove the player
    from `onPitch`, record `exitMinute`, set `redCarded = true`, `dirty = true`.
  - Straight RED_CARD path unchanged (a booked player can still receive a straight red — the
    yellow simply preceded it).
- Because the send-off is a real RED_CARD event, everything downstream already works:
  managed-team pause (`PAUSED_RED`, `LiveMatchService.java:744`), minutes-played bookkeeping
  (`exit` map, `LiveMatchService.java:659`), on-pitch tracking (`LiveMatchService.java:726`),
  and the 10-men strength recomputation.

**`FixtureSimulator`** (`backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`):

- `saveEvents` walks events in minute order tracking two sets per match: `booked` and
  `sentOff` (both `Set<UUID>`).
- All attribution picks draw from the XI **excluding `sentOff` players** (fallback to the full
  XI if the filtered pool is ever empty — practically unreachable, but avoids a crash).
- A YELLOW_CARD attributed to a booked player persists the YELLOW_CARD row **and** a RED_CARD
  row (same minute), adds the player to `sentOff`, and adds a RED_CARD `AttributedEvent` so
  `MatchOutcomeApplier` sees the send-off.
- A straight RED_CARD adds the player to `sentOff` as today (plus new exclusion from later
  picks).

**`MatchOutcomeApplier`** (`backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java`):

- In `applyTeam`: keep iterating the player's yellows so managed-side booking responses still
  surface every card shown (yellow bookings + red booking — the post-match summary reads like
  the real sequence), but when `reds.contains(p.getId())`, skip the accumulation part inside
  the loop (no `addYellowCard`, no 3-tally suspension check from this match's yellows). The
  red's `addSuspension(1)` is unchanged.

**`LiveCommentary`** (`backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`):

- New small pool `SECOND_YELLOW` (PT-BR lines in the existing voice, e.g. "Segundo amarelo!
  Está expulso!"). `of(...)`: RED_CARD with `detail == "SECOND_YELLOW"` picks from the new
  pool; RED_CARD otherwise keeps the generic `RED` pool. The `detail` param already reaches
  `of(...)`.
- Update the class Javadoc for the new `detail` meaning on RED_CARD (mirrors the existing
  TACTICS_CHANGE posture documentation in `LiveTimelineEvent`).

**`LiveTimelineEvent`** (`backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java`):

- Javadoc only: document `detail = "SECOND_YELLOW"` on RED_CARD.

No frontend changes: the live feed renders commentary text verbatim, and fixture details render
`match_events` rows by type exactly as today.

## Testing

- **`LiveMatchEngine` tests**: with a seed/config forced to produce repeat yellows on one
  player — second yellow emits the yellow+red pair in the same minute, player leaves the pitch,
  and never receives a third card; a straight red on an already-booked player stays a single
  RED_CARD event (no `SECOND_YELLOW` detail).
- **`FixtureSimulator` tests**: attribution never yields two yellows for one player without an
  accompanying red row; a sent-off player is never attributed any later event; the red row for
  a second yellow lands in the same minute as the second yellow row.
- **`MatchOutcomeApplier` tests**: second-yellow send-off → `yellowCards` tally unchanged,
  exactly one 1-match suspension; straight red after one yellow → same (tally unchanged);
  3 yellows accumulated across separate matches still suspends (regression); bookings summary
  for a managed second-yellow send-off lists yellow, yellow, red.
- **`LiveCommentary` tests**: RED_CARD + `SECOND_YELLOW` detail uses the new pool; RED_CARD
  with null detail keeps the generic pool.

## Out of scope

- `MatchSimulationService`'s abstract score/event generation (a red does not retroactively
  change the instant-sim scoreline — attribution-level realism only, as today).
- Player match ratings: a second-yellow send-off feeds 2 yellows + 1 red into the rating
  calculator; the slightly harsher penalty vs a straight red is accepted (arguably realistic).
- In-progress live matches at deploy time re-simulate under the new rules mid-match — accepted
  (solo-dev game).
