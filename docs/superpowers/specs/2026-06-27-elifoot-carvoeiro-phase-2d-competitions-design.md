# Elifoot Carvoeiro — Phase 2D: Multiple Competitions & Dated Event Calendar — Design Spec

**Date:** 2026-06-27
**Status:** Approved (design); ready for implementation plan.
**Builds on:** Phase 2A (tactics), 2B (finances), 2E (multi-season rollover), 2C-1 (transfers), 2C-2 (contracts & free agents). This is the last Phase 2 slice.

## 1. Goal

Run **two competitions in one season** — the Campeonato Catarinense (existing 10-club state league) and a **Série B-style national league (20 clubs)** that Criciúma also plays — driven by a **CM-style dated event calendar**: the season starts in January and each "Advance" jumps to the next dated event (a match, or a transfer event), progressing an in-season date through to ~November. Remove every single-competition assumption baked in since Phase 1.

## 2. Locked product decisions

1. **Second competition = a national Série B-style league**, round-robin, 20 clubs (Criciúma + 19 others), with per-competition table / top scorers / prize.
2. **Calendar is interleaved**: the two leagues' rounds alternate across the season (Catarinense R1, Série B R1, Catarinense R2, …); when the shorter league (Catarinense, 18 rounds) ends, the longer (Série B, 38 rounds) continues alone.
3. **Hand-curated squads** for the new clubs (realistic Brazilian rosters authored as seed data — *not* claimed to mirror a specific real-world roster snapshot).
4. **Real overlap**: clubs can belong to BOTH competitions (Criciúma + the SC clubs that really play Série B). Club↔competition is many-to-many.
5. **Dated event calendar, folded into 2D**: Advance jumps to the next dated event — a MATCH or a TRANSFER (AI bids arriving during a window). The in-season date progresses and is shown CM-style.
6. **Transfer windows become date-based** (two date ranges), replacing the round-based window from 2C-1/2C-2.

## 3. Scope

**In scope**
- Club↔competition membership (`competition_club`); per-competition fixture generation over members.
- Dated event calendar: `season_event` queue (MATCH/TRANSFER), `SeasonCalendarGenerator`, date-driven advance (`SeasonAdvanceService`), `SaveGame.current_date`.
- Date-based `TransferWindow`; buy/sign gate on `current_date`; FE window mirror date-based.
- Finance per matchday over the playing competition's clubs; prize per competition at its completion.
- Seed: club-pool + competitions-with-members format; ~14 new national clubs + realistic squads + memberships; second competition.
- Rollover: regenerate the dated calendar per competition over members; archive both; reset date; purge events.
- Frontend: current-date display, event-aware advance result, competition switcher, both-competition dashboard, date-based window indicator.

**Out of scope (deferred / excluded)**
- Cup/knockout competitions (this slice is two leagues).
- Day-by-day ticking with empty days (advance jumps event-to-event).
- Per-competition lineups (the managed club fields ONE lineup used in whichever competition it plays) and per-competition suspensions (suspensions are global — carry to the next match in either league).
- Transfer-window changes beyond making it date-based (negotiation mechanics from 2C-1/2C-2 are unchanged).
- News/injuries/board events (only MATCH + TRANSFER events exist).
- Finance constant re-tuning (per-matchday economics scale proportionally; negative balances remain allowed-with-warning per 2B).

## 4. Architecture

### 4.1 Membership
`competition_club` join table makes club↔competition many-to-many. Each competition's fixtures and calendar are generated over **its members** (no more "all clubs in the save play the one competition"). A club in both leagues (Criciúma + shared SC clubs) gets fixtures in both.

### 4.2 Dated event calendar
A single `season_event` queue orders everything by date. Advance pops the next unprocessed event by `(event_date, sequence)`, processes it, marks it processed, and sets `save.current_date` to its date.
- **MATCH** event → simulate that `(competition, round)`'s fixtures (existing per-match lifecycle), run finance for that competition's clubs, and pay prize if it is that competition's final round.
- **TRANSFER** event → generate AI incoming bids (`generateIncomingOffers`) so offers land in the inbox; the advance stops here.

The interleave and dates are **materialized** at generation (creation + rollover) — explicit, testable, and stable across rollover.

### 4.3 Date-based transfer windows
`TransferWindow.isOpen(LocalDate currentDate)` over two date ranges (offsets from the season start): a pre-season window and a mid-season window. Because a season runs within a single calendar year (Jan 15 → ~Oct), the method derives `seasonStart = LocalDate.of(currentDate.getYear(), 1, 15)` internally and tests the two offset ranges — no extra arg needed. TRANSFER events are scheduled only inside these ranges; buying (`TransferService`) and signing (`ContractService`) gate on `current_date`. This replaces the round-based `isOpen(currentRound, totalRounds)` and its FE mirror.

## 5. Data model — V8 migration (additive)

```sql
-- Club <-> competition membership (many-to-many)
CREATE TABLE competition_club (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    club_id        UUID NOT NULL
);
CREATE INDEX idx_competition_club_comp ON competition_club(competition_id);
CREATE INDEX idx_competition_club_save ON competition_club(save_game_id);

-- Dated event queue
CREATE TABLE season_event (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date     DATE NOT NULL,
    type           VARCHAR(12) NOT NULL,   -- MATCH | TRANSFER
    competition_id UUID,                   -- set for MATCH
    round          INT,                    -- set for MATCH
    sequence       INT NOT NULL,           -- tiebreak within a date
    processed      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_season_event_save_proc ON season_event(save_game_id, processed, event_date, sequence);

-- Fixtures gain their match date (all fixtures of a round share their MATCH event's date)
ALTER TABLE fixtures ADD COLUMN match_date DATE;

-- Save advances by date now
ALTER TABLE save_games ADD COLUMN current_date DATE;
```

Notes:
- `save_games.current_round` becomes vestigial (no longer the advance pointer); left in place, set to 0 — no destructive change. `current_date` is the pointer.
- `fixtures.match_date` is nullable for migration safety; the calendar generator sets it. Existing single-competition saves are not a concern (greenfield; no production saves).
- `competition_club`/`season_event` reference only `save_game_id` (cascade); no player FK, so rollover purge ordering is independent of player deletes.

### 5.1 Entities & repositories
- `CompetitionClub` entity + `CompetitionClubRepository` (`findByCompetitionId`, `findBySaveGameIdAndClubId`, `@Transactional deleteBySaveGameId`).
- `SeasonEvent` entity + `SeasonEventRepository` (`findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc`, `findBySaveGameId`, `@Transactional deleteBySaveGameId`). `SeasonEventType` enum `{ MATCH, TRANSFER }`.
- `Fixture` gains `matchDate` (LocalDate) + `create(...)` param + getter + setter; `FixtureRepository.findBySaveGameIdAndCompetitionIdAndRound` already exists for playing a round.
- `SaveGame` gains `currentDate` (LocalDate) + getter/setter; `advanceToNextSeason()` resets it to the new season's start (§9).

## 6. `SeasonCalendarGenerator` (pure-ish, deterministic; no wall-clock)

Run at career creation and at rollover. Inputs: `saveGameId`, `seasonYear`, the competitions + their member club lists. Output: persisted `season_event` rows + each fixture's `match_date`.

**Constants (locked):**
- `SEASON_START = LocalDate.of(seasonYear, 1, 15)`.
- `MATCH_INTERVAL_DAYS = 5` (date of match-unit *i* = `SEASON_START.plusDays(i * 5)`).
- Windows (offsets from `SEASON_START`): pre-season `[+0, +42)` days; mid-season `[+140, +168)` days.
- `TRANSFER_INTERVAL_DAYS = 10` (within a window, a TRANSFER event every 10 days).
- Same-date ordering: MATCH `sequence = 0`, TRANSFER `sequence = 1`.

**Algorithm:**
1. For each competition, generate its rounds via `FixtureScheduler.doubleRoundRobin(memberCount)` over its **id-sorted** member clubs → fixtures per `(competition, round)`.
2. **Interleave** the competitions' rounds into a global ordered list of match-units: alternate competitions within each round index; when a competition runs out of rounds, the others continue. (Deterministic ordering: competitions sorted by id; round index ascending; competition order within a round-index by competition id.)
3. Assign match-unit *i* the date `SEASON_START.plusDays(i * MATCH_INTERVAL_DAYS)`; create a MATCH `season_event` (competition, round, date, sequence 0); set every fixture of that `(competition, round)` to `match_date = date`.
4. Inject TRANSFER events: for each window range, step from its start by `TRANSFER_INTERVAL_DAYS`, adding a TRANSFER `season_event` (date, sequence 1) at each step inside the range.

All randomness elsewhere is unaffected; this generator uses only `LocalDate` arithmetic + id-sorted iteration (deterministic, no RNG, no wall-clock).

## 7. `SeasonAdvanceService` (date-driven advance)

Replaces the single-competition `simulateNextRound` entry point (the per-fixture simulation in `RoundSimulationService` is reused).

```
advance(saveGameId, userId):
  save = requireOwned(...)
  if save.status == FINISHED -> 409 SEASON_FINISHED
  event = seasonEventRepository.findFirst...ProcessedFalse...(saveGameId)   // next by (date, seq)
  if event == null -> mark FINISHED, return finished response
  switch event.type:
    MATCH     -> play that competition's round (existing fixture simulation + outcome lifecycle);
                 finance for that competition's clubs; if round == competition.totalRounds-1 (final), prize;
    TRANSFER  -> transferService.generateIncomingOffers(saveGameId, event.date)   // signature changes from (saveGameId, round) to (saveGameId, LocalDate); window open by construction
  event.markProcessed(); save.currentDate = event.date
  if no more unprocessed MATCH events -> save.status = FINISHED
  save; return AdvanceResponse(event-shaped: type, date, competitionName, round, results/bookings/finance | bidsReceived count)
```

- The match seed stays `saveGameId.msb ^ fixtureId.msb ^ round` (per-competition round; distinct fixtureId keeps seeds unique across competitions). The engine is untouched.
- The managed club uses its single set lineup in whichever competition it plays; `bestXi` for AI (unchanged). Suspensions are global.

## 8. Finance & prize (per §4.2)

- `FinanceService.processMatchday(saveGameId, competitionId, fixtures)`: gate income to home clubs in those fixtures; sponsorship + wages + upkeep to that **competition's member clubs**. For a single-competition save this equals today's "all clubs" behavior. Managed-club ledger rows recorded only when the managed club is among the processed (it is, whenever it is the playing competition). Per-matchday economics scale proportionally with the longer season; constants unchanged.
- Prize: when a MATCH event is a competition's **final round**, `FinanceService.processSeasonEnd(saveGameId, competitionId)` pays prize by final position in **that** competition (`FinanceCalculator.prizeMoney(position, memberCount)`).

## 9. Rollover (extends `SeasonRolloverService`)

The rollover already loops competitions for archiving (2E). Changes:
- Season is **FINISHED** when no unprocessed MATCH events remain.
- Archive **both** competitions (unchanged loop) using each competition's standings/top-scorers (already per-competition).
- Age + tick contracts once (2E/2C-2 logic unchanged: aging curve, retirement+newgens, contract tick, FA pool).
- **Regenerate the dated calendar**: purge the finished season's `season_event` rows; regenerate fixtures **per competition over its members** (via `SeasonCalendarGenerator`) for `seasonYear+1`; set every new fixture's `match_date`; reset `save.currentDate` to the new season's `SEASON_START`.
- Existing purges retained (finance ledger, transfer offers). FK-safe ordering unchanged: events/fixtures purge + lineup clear + flush BEFORE any player delete; `season_event`/`competition_club` have no player FK.
- `competition_club` memberships persist across seasons (clubs stay in their competitions); only fixtures + calendar regenerate.

## 10. Seed data

Seed format evolves from "one competition + its clubs" to **a club pool + competitions that reference members**:
- `clubs[]` — all distinct clubs, each with a hand-curated squad. The 10 Catarinense clubs already exist; ~14 new national clubs are added (Série B's 20 minus Criciúma minus the shared SC clubs).
- `competitions[]` — each `{ name, type, memberShortNames[] }`. Catarinense lists its 10; Série B lists its 20. **Overlap** = a short-name in both lists (Criciúma + e.g. Avaí, Chapecoense, Figueirense, Brusque, Joinville).
- Loader: create each club once (with its squad + 2B financial profile from squad strength + 2C-2 staggered contracts + wages), then write `competition_club` rows per competition's member list, create each `Competition` row, and run `SeasonCalendarGenerator` over all competitions to produce the interleaved dated calendar + fixtures.
- New squads are **realistic curated Brazilian rosters** (name/position/age/nationality/attributes), authored like the Catarinense seed; club identities (name, short name, colors, strength tier guiding squad quality) are real Série B clubs. Not claimed to be exact real-world rosters.

## 11. DTOs (high level; plan details fields)
- `SaveGameResponse` gains `currentDate` (ISO date string) + a `competitions[]` list (`id, name, type, totalRounds` + the managed club's current position) and drops the single `competitionId/currentRound/totalRounds` reliance. (Backward-compatible additive fields where practical; FE updated.)
- Advance response is **event-shaped**: `{ type: 'MATCH'|'TRANSFER', date, competitionName?, round?, results?, bookings?, newSuspensions?, finance?, bidsReceived? }`.
- Standings/top-scorers endpoints already take `competitionId` (unchanged).

## 12. Frontend
- **Current date** shown prominently (header/dashboard), progressing each advance.
- **Advance → event-aware result:** MATCH renders the result block (competition + round + score + bookings + finance, as today); TRANSFER renders a "Propostas recebidas" notice (new inbox count).
- **Competition switcher** on the table page (Catarinense / Série B); dashboard shows Criciúma's position in **both** + current date + next-event hint.
- **Date-based window indicator** on the market / free-agents pages (keys off `current_date`); FE `isTransferWindowOpen` becomes date-based (mirrors the backend date ranges).

## 13. Constants (locked)

| Constant | Value | Where |
|---|---|---|
| `SEASON_START` | `LocalDate.of(seasonYear, 1, 15)` | calendar generator |
| `MATCH_INTERVAL_DAYS` | 5 | match-unit dating |
| pre-season window | `[+0, +42)` days from start | `TransferWindow` |
| mid-season window | `[+140, +168)` days from start | `TransferWindow` |
| `TRANSFER_INTERVAL_DAYS` | 10 | transfer-event spacing within a window |
| same-date order | MATCH seq 0, TRANSFER seq 1 | calendar generator |
| Série B size | 20 clubs (Criciúma + 19) | seed |
| Catarinense size | 10 clubs (unchanged) | seed |

## 14. Error handling
Standard `{error, message, status}`. `SEASON_FINISHED` (409) when advancing a finished save (unchanged). `TRANSFER_WINDOW_CLOSED` (422) now keyed on `current_date`. Existing transfer/contract codes unchanged. `requireOwned` (404) on every save-scoped op.

## 15. Testing
- **`SeasonCalendarGeneratorTest`** (unit/IT): deterministic dates from `seasonYear` (match-unit *i* at start + i*5); correct interleave (alternate until Catarinense's 18 rounds end, then Série B continues to 38); TRANSFER events only inside the two window ranges, spaced 10 days; same-date MATCH-before-TRANSFER ordering; each fixture's `match_date` equals its MATCH event's date.
- **Membership IT**: a competition's fixtures only involve its members; a shared club (Criciúma) has fixtures in both; member counts (10 / 20) correct.
- **`SeasonAdvanceServiceIT`**: events processed in `(date, sequence)` order; MATCH simulates the right competition's round + finance to that competition's clubs + prize at the final round; TRANSFER lands inbox bids (during a window); `current_date` progresses; status FINISHED after the last MATCH; advancing a finished save → 409.
- **Date-based `TransferWindowTest`** (in/out of pre-season + mid-season ranges).
- **Finance IT**: gate to home clubs of the matchday; wages/upkeep/sponsorship to the playing competition's members only; a both-competition club processed on both leagues' matchdays.
- **Per-competition standings + prize IT**: each competition's table independent; prize paid by position at that competition's completion.
- **Rollover IT**: regenerates the dated calendar for `seasonYear+1` (new dates, fixtures unplayed), archives **both** competitions, `current_date` resets to new January, `season_event` rows purged, memberships persist.
- **Seed IT**: both competitions loaded; `competition_club` correct incl. overlap (Criciúma in both); new clubs have squads + financial profiles.
- **Frontend (Vitest/RTL)**: current-date display; advance result renders MATCH vs TRANSFER; competition switcher shows each table; date-based window indicator.
- All suites green; determinism preserved.

## 16. Determinism
The match engine is untouched (seed unchanged: `saveGameId.msb ^ fixtureId.msb ^ round`). The calendar generator is pure (`LocalDate` math + id-sorted iteration, no RNG, no wall-clock). Bid generation stays seeded but moves from a round to a date key: `generateIncomingOffers(saveGameId, eventDate)` seeds `new Random(saveGameId.getMostSignificantBits() ^ eventDate.toEpochDay())` — deterministic and unique per transfer-event date. No wall-clock anywhere — dates derive from `seasonYear`. Finance is pure (2B). Contract/aging RNG seeds unchanged (2C-2/2E).

## 17. Forward compatibility
- The `season_event` queue generalizes to future event types (injuries, news, board) — new `SeasonEventType` values + handlers, no schema change.
- `competition_club` + per-competition calendar generalize to N competitions and to cups (a cup is a different generator + a knockout standings model) without touching the advance loop.
- Date-based windows + `current_date` are the foundation for any future date-driven feature.

## 18. Acceptance
A user can: create a career and see an in-season date starting in January; click Advance to jump to the next event — a Catarinense or Série B match (with its result) or, during a window, an inbox of new AI bids; watch **two** league tables progress in parallel via a competition switcher; play Criciúma through both leagues to season's end with each competition crowning a champion and paying prize; and roll over into a fresh dated season. All suites stay green; the match engine and determinism are intact; no single-competition assumption remains.
