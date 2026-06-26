# Phase 2E — Multi-Season Careers — Design Spec

**Status:** Approved (brainstorm complete) — ready for implementation plan.
**Date:** 2026-06-26
**Slice:** Phase 2E of the Elifoot Carvoeiro roadmap. Phase 2A (Tactics & Match Depth) is complete and on `main`. Phases 2B (Finances), 2C (Transfer market), 2D (Multiple competitions) are not yet built; this slice is designed to be forward-compatible with them.

---

## 1. Goal

Turn the single-season game into an ongoing multi-year career. When a season ends, the manager reviews an end-of-season summary, then starts the next season: the finished season is archived (full final table + top scorers + champion), every player ages one year along an attribute curve, very old players retire and are replaced by generated youth ("newgens"), per-season player state resets, the fixture calendar regenerates, and the save rolls forward — persisting a browsable history of past seasons.

**Playable result:** play the Catarinense year after year as Criciúma, watch your squad age and renew, and build up a career history of titles and final placings.

## 2. Context & current state (what we build on)

- **`SaveGame` is the career.** Fields: `id`, `userId`, `managedClubId`, `seasonYear` (int, currently read-only after creation), `currentRound` (mutable), `status` (`IN_PROGRESS` / `FINISHED`), `createdAt`. No calendar date.
- **Season is implicit.** `Competition(id, saveGameId, name, type, seasonYear, totalRounds)`; there is no `Season` entity and no champion field. "Season finished" is detected at advance time when `currentRound >= competition.totalRounds`, which sets `SaveGame.status = FINISHED` (`RoundSimulationService`).
- **Standings are computed on the fly** by `StandingsCalculator.compute(clubs, playedFixtures)` → `StandingRow(clubId, clubName, played, won, drawn, lost, goalsFor, goalsAgainst, goalDifference, points)`, sorted points→GD→GF→name. Nothing is persisted.
- **Top scorers do not exist yet.** Goals are recorded as `MatchEvent(id, fixtureId, minute, type, playerId, clubId)` rows with `type = "GOAL"` (no `saveGameId`/`competitionId` column — they are reached via `fixtureId`). Aggregation must be built in this slice.
- **`Player`**: `id`, `saveGameId`, `clubId` (mutable), `name`, `position` (GK/DEF/MID/ATT), `age` (mutable via `setAge`), `nationality`, six attributes `pace/finishing/passing/tackling/goalkeeping/stamina` (**currently set-once, no setters**), `overall` (mutable), and Phase-2A state `form`/`yellowCards`/`suspensionRemaining` with `applyForm/addYellowCard/addSuspension/decrementSuspension/isEligible`.
- **Match RNG seed** = `saveGameId.msb ^ fixtureId.msb ^ (long) round`. Because the seed includes `fixtureId`, **regenerating fixtures with fresh UUIDs automatically yields new results next season.**
- **`FixtureScheduler.doubleRoundRobin(int teamCount) : List<ScheduledFixture(round, homeIndex, awayIndex)>`** generates the calendar; `2*(teamCount-1)` rounds.
- **Overall computation** lives as a private `computeOverall(SeedPlayer, Position)` in `SaveGameService`; it must be extracted to be reused by aging and newgens.
- **Seed data**: `resources/seed/catarinense.json` (10 clubs, real rosters). Loader `SeedLoader.loadCatarinense()`; create-player path in `SaveGameService.createCareer`.
- **Latest migration is `V3`** → this slice adds **`V4`**.
- Established cross-cutting conventions: Controller→Service→Repository; records for DTOs; UUID ids; `Instant`/`LocalDate` (never `java.util.Date`); constructor injection; enums with behavior; REST with `/api/v1`, plural nouns, precise status codes, standard error JSON `{error, message, status}`; owner (IDOR) checks return 404; deterministic seeded engine; ≥80% coverage, Given/When/Then tests, Testcontainers (backend) + RTL (frontend).

## 3. Locked decisions (from brainstorming)

1. **Season transition** = an explicit **end-of-season summary screen** with a "Start next season" action that performs the rollover.
2. **Aging** = an **age curve** (young improve, peak stable, old decline) **plus retirement & newgen replacements** so squad sizes stay constant and the career stays fresh over many years.
3. **History** = **full archived tables**: per season, the complete final standings plus the full top-scorers list, browsable later.
4. **Architecture** = **Approach A: in-place rollover + dedicated archive tables.** The `SaveGame` rolls forward in place; finished seasons are snapshotted into archive tables, keeping every existing live read/advance path unchanged and the live DB small.

## 4. Architecture overview

The `SaveGame` remains the single career and is mutated forward each year. A new `SeasonRolloverService` orchestrates the transition; focused collaborators handle the independently-testable pieces:

- `OverallCalculator` — single source of truth for `overall` from position + attributes (extracted from `SaveGameService`).
- `TopScorersService` (+ aggregation query) — live and archival top scorers.
- `PlayerAgingService` — applies the deterministic age curve and decides retirements.
- `NewgenGenerator` — produces replacement youth players.
- `SeasonArchiveService` (or inline in rollover) — writes the `season` / `season_standing` / `season_top_scorer` snapshot.

Each unit has one clear responsibility, a small interface, and is unit-testable; `SeasonRolloverService` composes them inside one `@Transactional` method. Rollover iterates over the save's competitions (one today) so Phase 2D drops in without reshaping the flow.

## 5. Data model — migration `V4__multi_season.sql` (additive only)

No existing table changes; only new tables. (Java-side mutability additions in §6 require no schema change — the columns already exist.)

**`season`** — one archived finished season, per competition:
- `id` uuid PK
- `save_game_id` uuid NOT NULL
- `competition_id` uuid NOT NULL
- `season_year` int NOT NULL
- `competition_name` varchar(80) NOT NULL
- `champion_club_id` uuid NOT NULL
- `champion_club_name` varchar(80) NOT NULL
- `managed_club_id` uuid NOT NULL
- `managed_club_name` varchar(80) NOT NULL
- `managed_club_position` int NOT NULL
- `created_at` timestamptz NOT NULL
- `UNIQUE (save_game_id, season_year, competition_id)`
- index on `save_game_id`

**`season_standing`** — archived final-table rows:
- `id` uuid PK
- `season_id` uuid NOT NULL REFERENCES `season(id)`
- `position` int NOT NULL
- `club_id` uuid NOT NULL
- `club_name` varchar(80) NOT NULL
- `played`, `won`, `drawn`, `lost`, `goals_for`, `goals_against`, `goal_difference`, `points` int NOT NULL
- index on `season_id`

**`season_top_scorer`** — archived scorers:
- `id` uuid PK
- `season_id` uuid NOT NULL REFERENCES `season(id)`
- `rank` int NOT NULL
- `player_id` uuid NOT NULL
- `player_name` varchar(80) NOT NULL
- `club_id` uuid NOT NULL
- `club_name` varchar(80) NOT NULL
- `goals` int NOT NULL
- index on `season_id`

New JPA entities map each table; new repositories:
- `SeasonRepository extends JpaRepository<Season, UUID>`: `findBySaveGameIdOrderBySeasonYearDesc(UUID)`, `findBySaveGameIdAndSeasonYear(UUID, int)`.
- `SeasonStandingRepository`: `findBySeasonIdOrderByPosition(UUID)`.
- `SeasonTopScorerRepository`: `findBySeasonIdOrderByRank(UUID)`.

## 6. Java-only additions (no schema change)

- `SaveGame.advanceToNextSeason()` → `seasonYear++`, `currentRound = 0`, `status = IN_PROGRESS`.
- `Competition` setters: `setSeasonYear(int)` (and `setTotalRounds(int)` for forward use; club count is constant today so `totalRounds` is unchanged).
- `Player.evolveAttributes(int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina, int overall)` — sets the six attributes plus overall after aging (attributes are otherwise set-once).
- `Player.resetSeasonState()` → `form = 0`, `yellowCards = 0`, `suspensionRemaining = 0`.
- `OverallCalculator.compute(Position, pace, finishing, passing, tackling, goalkeeping, stamina) : int` — extracted verbatim from `SaveGameService.computeOverall`; `SaveGameService` refactored to call it (behaviour preserved, covered by a parity test).

## 7. Top scorers (new capability)

`TopScorersService.getTopScorers(UUID saveGameId, UUID competitionId, UUID userId) : List<TopScorerRow>`:
- Owner check (404 on foreign user).
- Aggregates `MatchEvent` rows with `type = "GOAL"` whose `fixtureId` belongs to the competition's fixtures, grouped by `playerId`, counting goals.
- Joins player name + club name (clubs/players are save-scoped); `clubId` is taken from the event.
- Returns `TopScorerRow(playerId, playerName, clubId, clubName, goals)` sorted by `goals` desc, then `playerName` asc. Includes all players with ≥1 goal.
- Implemented via a repository aggregation (JPQL/native join `match_events`→`fixtures`), reused live and at archive time.

Because rollover purges a competition's events when it archives, live aggregation always reflects the current season only.

## 8. `SeasonRolloverService.rollover(UUID saveGameId, UUID userId)` — `@Transactional`

Ordered so the archive is written from live data **before** anything is mutated or deleted. Players are iterated in **stable `id` order** so the seeded sequence is reproducible.

1. Load `SaveGame`; **owner check → 404** if `userId` mismatch. **Guard:** `status == FINISHED` else **`422 SEASON_NOT_FINISHED`**.
2. **Archive (per competition):** compute final standings (`StandingsCalculator`) and top scorers (`TopScorersService`); champion = standings[0]; managed-club position = index of `managedClubId` in standings (1-based; defaults to last if absent). Persist one `season` row + ordered `season_standing` rows + ranked `season_top_scorer` rows.
3. **Purge:** delete the competition's `match_events` (via its fixtures), then its `fixtures`.
4. **Age & evolve every player** of the save (managed + AI), in id order: `age++`; apply age-curve deltas; clamp each attribute to `[1, 99]`; recompute overall via `OverallCalculator`; persist via `evolveAttributes`.
5. **Retire & replace:** players meeting the retirement rule are deleted; for each, `NewgenGenerator` creates a replacement in the **same club + position** so each club's squad size is unchanged.
6. **Reset carried players' season state** (`resetSeasonState`). Newgens already start at zero.
7. **Clear the managed club's saved lineup** (delete `lineup` + `lineup_players`) so retired players cannot dangle and the user re-picks the XI.
8. **Regenerate fixtures** for each competition via `FixtureScheduler.doubleRoundRobin(clubCount)` → new `Fixture` rows (fresh UUIDs ⇒ new results); `totalRounds` unchanged.
9. `SaveGame.advanceToNextSeason()` and `Competition.setSeasonYear(seasonYear + 1)`.
10. Return `RolloverResult(seasonYear, currentRound = 0, retirements, newcomers)` where `retirements`/`newcomers` describe the **managed club's** departures and arrivals.

### Determinism
A single `Random` seeded with `saveGameId.getMostSignificantBits() ^ (long) finishingSeasonYear` drives all rollover randomness (aging deltas, retirement draws, newgen ages/attributes/names), consumed in a fixed order over id-sorted players. This matches the project's seeded-engine discipline. (It relies on stable id ordering — the same caveat tracked by the existing fast-follow `task_b9b7d4c7`; rollover sorts by id explicitly.)

## 9. Player-evolution rules (deterministic)

**Age curve** (applied after `age++`), per attribute then clamp `[1,99]`, then recompute overall:
- **≤ 21 (youth growth):** each attribute `+0..+3` (positive-skewed).
- **22–29 (peak):** each attribute `−1..+1`.
- **30–32 (decline):** each attribute `−2..0`.
- **≥ 33 (steep decline):** each attribute `−4..−1`, with `pace` and `stamina` weighted to decline most.

**Retirement** (seeded):
- `age ≥ 38` → always retire.
- `age == 37` → retire with probability 70%.
- `age == 36` → retire with probability 45%.
- `age == 35` → retire with probability 25%.
- `age < 35` → never retire.

**Newgen generation:**
- Same `clubId` and `position` as the retiree.
- `age` = `16..19`.
- Attributes drawn from position-appropriate youth bands with seeded jitter (e.g. GK emphasises goalkeeping, ATT finishing/pace, DEF tackling, MID passing/stamina), clamped `[1,99]`; `overall` via `OverallCalculator`. The youth-growth curve develops them over subsequent seasons.
- `name` = random first + last from a new curated `resources/seed/newgen-names.json` (`firstNames[]`, `lastNames[]`, Brazilian); `nationality = "Brasil"`.

## 10. REST API (`/api/v1`, all owner-guarded → 404 on foreign user)

- **`POST /save-games/{saveGameId}/seasons/rollover`** → `200 OK`, body `RolloverResponse(int seasonYear, int currentRound, List<SquadChangeResponse> retirements, List<SquadChangeResponse> newcomers)`; `SquadChangeResponse(String playerName, String position, int age)`. Guard violation → `422 SEASON_NOT_FINISHED`. (Action-style POST, consistent with the existing `/advance`.)
- **`GET /save-games/{saveGameId}/seasons`** → `200`, `List<SeasonSummaryResponse(int seasonYear, String competitionName, String championClubName, int managedClubPosition, String topScorerName, int topScorerGoals)>`, newest first. (`topScorer*` come from the archived rank-1 scorer; null/0 only if no goals were scored.)
- **`GET /save-games/{saveGameId}/seasons/{seasonYear}`** → `200`, `SeasonDetailResponse(int seasonYear, String competitionName, String championClubName, List<StandingRowResponse> table, List<TopScorerResponse> topScorers)`; `404 SEASON_NOT_FOUND` if absent.
- **`GET /save-games/{saveGameId}/competitions/{competitionId}/top-scorers`** → `200`, `List<TopScorerResponse(String playerName, String clubName, int goals)>` — live current-season scorers for the end-of-season screen (the existing standings endpoint supplies the live final table).

`StandingRowResponse` mirrors the existing standings response shape; `TopScorerResponse` is the response DTO for a scorer row.

## 11. Frontend (React + TS + Vite + TanStack Query)

- **End-of-season screen** — shown when `advance` returns `seasonFinished = true`: champion banner, final table (managed club highlighted), top scorers, and an **"Iniciar próxima temporada"** button calling rollover. Built by reusing the live standings query + the new live top-scorers query.
- **Squad-changes panel** — after rollover succeeds, list the managed club's **retirements** (name, age) and **newcomers** (name, position, age) from `RolloverResponse`, then continue into the new season (squad screen / dashboard).
- **Season history view** — on the dashboard: a list of past seasons (year, champion, your placing, top scorer) from `GET /seasons`; selecting one opens detail (full archived table + top scorers) from `GET /seasons/{year}`.
- **Dashboard** shows the current `seasonYear`. `AdvancePanel` is extended to route to the end-of-season screen instead of only displaying a "season finished" message.
- **API client (`career.ts`) + `types.ts`**: add `getTopScorers`, `rolloverSeason`, `getSeasonHistory`, `getSeason`, and the new response/request types.

## 12. Error handling

- Standard JSON `{error, message, status}` via the existing global handler.
- New codes: `SEASON_NOT_FINISHED` (422, rollover before the season is complete); `SEASON_NOT_FOUND` (404, unknown archived year).
- Foreign-user access → 404 on every new endpoint (consistent with existing IDOR policy).
- Defensive: managed club always present in standings (position defaults to last if not found); newgen name pool is validated non-empty at load.

## 13. Testing (≥80% coverage, Given/When/Then; Testcontainers backend, RTL frontend)

**Unit:**
- `OverallCalculatorTest` — parity with the prior `computeOverall` for each position.
- `PlayerAgingTest` — youth growth, peak stability, decline brackets, clamping, overall recompute; deterministic for a fixed seed.
- `RetirementRuleTest` — `≥38` always; `35–37` probability boundaries; `<35` never.
- `NewgenGeneratorTest` — same club + position, age `16–19`, attributes within band, name drawn from pool, overall computed.

**Integration (Testcontainers Postgres):**
- `TopScorersServiceIT` — goals aggregated across a competition's fixtures, correct sort, owner 404.
- `SeasonRolloverServiceIT` (primary) — finish a season then roll over and assert: archive rows written (table + scorers + champion + managed position) reflecting the finished season; fixtures regenerated (count correct, all unplayed); every player `age + 1`; some players retired and replaced with **per-club squad size constant**; carried players' form/cards/suspension reset to 0; managed lineup cleared; `seasonYear + 1`, `currentRound = 0`, `status = IN_PROGRESS`; **determinism** — rolling over the same finished save twice (fresh saves, same seed inputs) yields identical rosters and fixtures; **guard** — rollover before season finished → 422; **IDOR** — foreign user → 404.
- Endpoint ITs for all four routes: owner happy-path, foreign-user 404, and `422 SEASON_NOT_FINISHED` / `404 SEASON_NOT_FOUND` where applicable.

**Frontend (RTL):**
- `SeasonSummaryPage.test` — renders champion/table/scorers; button triggers `rolloverSeason`; squad-changes panel shows retirements/newcomers.
- `SeasonHistory.test` — history list and detail render correctly.
- Extended `AdvancePanel.test` — `seasonFinished` routes to the summary screen.

## 14. Forward-compatibility & out of scope

- **2B Finances:** rollover will later carry budget over and book end-of-season finances; budget is out of scope here.
- **2C Transfers:** rosters become mutable within a season; this slice already makes attributes mutable and clears lineups on rollover, easing that work. (Note from the Phase-2A review: when 2C lands, harden the `players.find(...)!` assertions in `SquadPage.tsx`.)
- **2D Multiple competitions:** rollover already iterates the save's competitions and archives per competition; adding a second competition needs no reshaping of the rollover flow.
- **Out of scope now:** training-driven development (Phase 3 youth/training), injuries/morale, per-player career statistics beyond the season archive, finances, transfers, mid-season top-scorers UI (top scorers are computed at season end and for the summary screen).

## 15. Acceptance criteria

A manager can: finish a Catarinense season, see an end-of-season summary (champion, full final table, top scorers), click "Start next season" and watch squad changes (retirements + newcomers), then play a fresh season with regenerated fixtures and aged players — and browse a growing history of past seasons with full archived tables and scorers. Backend and frontend test suites green; coverage ≥80%; determinism preserved.
