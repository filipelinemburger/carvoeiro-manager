# Player Match Statistics — Design

**Date:** 2026-07-13
**Status:** Approved

## Goal

Persist per-player match statistics — matches played, minutes played, goals, assists, fouls,
yellow/red cards — league-wide (every club, every fixture), retained for the life of the save,
and surface them in a new "Estatísticas" tab on the player details drawer.

This is Group 5 of the 10-item enhancement roadmap (`~/.claude/plans/imperative-strolling-parasol.md`),
the last and most architectural item, brainstormed separately as the roadmap required.

## Decisions made during brainstorming

- **Scope: league-wide.** Stats accrue for every player in every fixture — both the managed
  club's live-simulated matches and AI-vs-AI instant-simulated matches.
- **Assists: corners + open-play goals.** Corner takers already are the assist; open-play
  `GOAL` events gain a probabilistic assist. Penalties and free kicks stay unassisted.
- **Fouls: a real simulated concept**, including uncarded fouls (`EventType.FOUL`), not just
  a cards-derived proxy — but **silent**: no commentary line, no Lances feed entry. Fouls
  surface only in statistics.
- **Assists: narrated.** When an open-play goal carries an assister, the live commentary names
  both players (new two-name pool), like corner goals already do.
- **Retention: career-spanning** via per-season summary rows archived at rollover — not an
  unbounded per-match log. The in-progress season is aggregated live from the season-scoped
  detail tables, which are purged at rollover exactly like `match_events` today.

## Background (verified against current code)

- `match_events` (entity `MatchEvent`: fixtureId, minute, type, playerId, clubId) already
  persists goals and cards from BOTH simulation paths, and is purged per-fixture at season
  rollover (`SeasonRolloverService.rollover()`, FK-safe ordering documented there).
- `SeasonRolloverService.archive(...)` already snapshots standings (`SeasonStanding`) and top
  scorers (`SeasonTopScorer`, with denormalized player/club names) per competition BEFORE the
  purge — the exact pattern the new archival follows.
- `Season` rows are already per-competition-per-year, so per-season stats naturally split by
  competition the way archived top scorers already do.
- `TopScorersService.getTopScorers` already aggregates `match_events` by player for the live
  season — the new live aggregation generalizes this shape to one player, all stat kinds.
- Minutes: `LiveTimeline.minutesPlayed()` exists but is discarded at `finish()`. The instant
  path (`FixtureSimulator`) has no substitutions — all 11 starters play 90.
- Both paths already call `Player.recordAppearance(...)` per played player at a single, clear
  point (`LiveMatchService.finish()` ~line 307, `FixtureSimulator.applyFitnessDecay`) — the
  new `fixture_appearance` write hooks in at those same points.
- `LiveTimelineEvent.playerInId` is the established "second player" slot (sub coming on;
  corner taker for `CORNER_GOAL`) — the assister for `GOAL` reuses it.
- `LiveMatchEngine` already uses separate seeded RNG streams (`rng`, `attrRng`, `injuryRng`)
  and has precedent (half-time marker) for adding features with zero disturbance to existing
  draw sequences — the determinism guard below follows that precedent.
- Weighting attributes exist: `PASSE`, `VISAO`, `CRUZAMENTO` (assist creativity);
  `AGRESSIVIDADE`, `DESARME` (foul propensity). `SetPieces.headerWeight`/crossing-style
  weighting exists for corner attribution.
- `PlayerDetails.tsx` has an established 2-tab `Tabs variant="fullWidth"` pattern
  ("Atributos"/"Contrato") and currently receives `player` by value with no fetch of its own.

## Data model

Two new tables (migration `V24__player_match_stats.sql`):

**`fixture_appearance`** — season-scoped detail, one row per player per fixture featured in:
`id, fixture_id, player_id, club_id, minutes_played, fouls`. Written by both simulation paths
at their existing `recordAppearance` call sites. Purged at rollover alongside `match_events`
(`deleteByFixtureIdIn`, before any player is deleted — same FK-safe ordering).

**`season_player_stats`** — permanent archive, one row per `(season_id, player_id)`:
`id, season_id, player_id, player_name, club_id, club_name, matches_played, minutes_played,
goals, assists, fouls, yellow_cards, red_cards`. Denormalized names snapshot the moment of
archival, exactly like `season_top_scorer`, so rows stay readable after transfers/retirements.
Never purged.

**`match_events` gains one new type value, `ASSIST`** (no schema change — `type` is a
varchar): a sibling row saved alongside a goal row (same fixture/minute/club, `player_id` =
assister) whenever an assister exists. `FOUL` never enters `match_events` (at ~20–25 fouls per
match it would triple the table for data nothing reads at event granularity) — fouls are
counted straight into `fixture_appearance.fouls`.

`EventType` gains `FOUL` appended after `PENALTY_MISSED` (ordinals feed LiveCommentary variant
selection and must not shift — same rule already documented in the enum). `ASSIST` is a
persistence-only type string, not an `EventType` member, since it is never simulated,
narrated, or replayed; it exists only as a `match_events.type` value written next to its goal.
(`EventType.goalTypeNames()` and all replay logic are therefore untouched by it.)

## Engine changes

**Determinism guard (both engines):** all new randomness comes from dedicated streams —
`assistRng` and `foulRng`, seeded from the existing config/fixture seed with distinct salts,
exactly like `injuryRng`. The existing `rng`/`attrRng`/`injuryRng` draw sequences are
untouched: every existing deterministic test and every in-progress save replays identically.

**Assists — `LiveMatchEngine`:** when an open-play `GOAL` resolves, draw from `assistRng`:
~65% chance the goal is assisted. If assisted, pick a teammate on pitch (excluding the
scorer) weighted by `PASSE + VISAO + CRUZAMENTO`, and carry them in the event's `playerInId`
(documented today for subs/corner takers; the doc comment extends to cover `GOAL` assisters).
`CORNER_GOAL` is unchanged — its taker already rides in `playerInId` and now also counts as
the assist. `PENALTY_GOAL` / `FREE_KICK_GOAL` stay unassisted.

**Assists — `FixtureSimulator.saveEvents`:** same ~65% probability and creativity weighting
picks an assister for `GOAL` (excluding the scorer); for `CORNER_GOAL` a crossing-weighted
taker-style assister is picked (the instant path currently attributes only the scorer).
Draws come from a dedicated `Random` salted from the fixture seed, leaving the existing
attribution `rng` sequence untouched.

**Assist persistence (both paths):** wherever a goal `MatchEvent` row is saved and an assister
exists, save a sibling `ASSIST` row crediting the assister.

**Fouls — `LiveMatchEngine`:** per-minute per-side foul chance from `foulRng`, calibrated to
~10–13 fouls per team per match, attributed by `AGRESSIVIDADE + DESARME` weighting. Emitted as
`FOUL` timeline events so replay stays deterministic, but filtered out of the visible feed in
`LiveMatchService.toState()` (no commentary, no Lances entry). At `finish()`, counted per
player into `fixture_appearance.fouls`.

**Fouls — `FixtureSimulator`:** per-player foul counts drawn directly (same weighting,
seeded) and written straight into `fixture_appearance` — no minute-by-minute simulation,
since nothing displays instant-match fouls live.

**Minutes:** live path uses `timeline.minutesPlayed()`; instant path writes 90 for all 11
starters.

**Commentary:** `LiveCommentary` gains a two-name `GOAL_ASSISTED` pool (Mastella broadcast
style, e.g. "X cruza na medida, Y aparece... É GOOOOOL!") selected when a `GOAL` event
carries an assister in `playerInId`; the existing one-name `GOAL` pool remains for unassisted
goals. New pool follows the established `pick2` mechanism.

## Aggregation, archival, API

**Live aggregation (current season):** new `PlayerStatsService` computes the in-progress
season per competition for one player: matches/minutes/fouls summed from
`fixture_appearance`, goals/assists/cards counted from `match_events` (the `TopScorersService`
query shape, filtered to one player). No caching.

**Archival:** `SeasonRolloverService.archive(...)` additionally aggregates every player's
season line and saves `season_player_stats` rows before the purge. The purge step gains
`fixtureAppearanceRepository.deleteByFixtureIdIn(...)` alongside the `match_events` cleanup,
inside the existing FK-safe ordering (appearance rows deleted and flushed before any player
deletion).

**API:** `GET /v1/save-games/{saveGameId}/players/{playerId}/stats` → `PlayerStatsResponse`:
a list of season lines (`seasonYear`, `competitionName`, `matchesPlayed`, `minutesPlayed`,
`goals`, `assists`, `fouls`, `yellowCards`, `redCards`) — current season computed live, past
seasons read from `season_player_stats` — plus a server-computed career-totals line. Owned-save
guard (`saveGameService.requireOwned`) like every other endpoint.

## UI

Third tab **"Estatísticas"** in `PlayerDetails.tsx`, following the existing
`Tabs variant="fullWidth"` pattern ("Atributos" / "Contrato" / "Estatísticas"). The component
gains its first `useQuery` (keyed by player id, enabled when the drawer is open), rendering a
compact season-by-season table with a career-totals footer row. Column headers abbreviated
pt-BR: `Temporada, Competição, J, Min, G, A, F, CA, CV`. Works for any player in the save
(managed squad, transfer targets, rivals) since stats are league-wide.

## Testing

- Unit: assist picking (probability honored per seed, scorer excluded, creativity weighting),
  foul attribution weighting, and determinism — identical seeds produce identical output, and
  the existing streams are undisturbed (a pre-change timeline's goals/cards/subs replay
  byte-identical under the new engine).
- IT, live path: `LiveMatchService.finish()` writes `fixture_appearance` rows with real
  minutes and foul counts, and `ASSIST` rows for assisted goals.
- IT, instant path: advancing a round writes `fixture_appearance` (90 minutes each) and
  `ASSIST` rows for AI-vs-AI fixtures.
- IT, rollover: `season_player_stats` rows exist per competition after rollover with correct
  aggregates; `fixture_appearance` rows are purged; no FK violations.
- IT, endpoint: current-season live aggregation and archived seasons both appear in the
  response with a correct totals line.
- Frontend: new tab renders season lines + career totals; fetch is lazy (fires when opened);
  loading/empty states.
- Full backend suite and full frontend suite (`tsc --noEmit` + vitest) green, per convention.

## Out of scope

- Narrating fouls in the feed (silent by decision), foul-based bookings logic changes, or any
  rebalancing of card frequency.
- League-wide stat leaderboards beyond the existing top-scorers table (assists/fouls
  leaderboards can reuse `season_player_stats` later).
- Retroactive stats for seasons already played before this feature ships.
- Any change to `PlayerRatingCalculator` (live match grades are a separate concept).
