# Phase 2B — Finances — Design Spec

**Status:** Approved (brainstorm complete) — ready for implementation plan.
**Date:** 2026-06-26
**Slice:** Phase 2B of the Carvoeiro Manager roadmap. Phase 2A (Tactics), 2E (Multi-season careers) complete and on `main`. 2C (Transfer market) and 2D (Multiple competitions) not yet built; this slice is the money foundation that 2C will spend against.

---

## 1. Goal

Give every club a budget that moves each round. Home games earn gate income; sponsorship pays a fixed amount each round; player wages and stadium upkeep are deducted each round; a strong final-table finish earns prize money at season end. The managed club gets a finances screen with its running balance and an itemized transaction ledger; advancing a round shows the money moving.

**Playable result:** manage Criciúma's money — earn at home, pay wages, watch the balance over a season, and collect prize money for a good finish.

## 2. Context & current state (what we build on)

- **`Club`** (`club/Club.java`): `id`, `saveGameId`, `name`, `shortName`, `nickname`, `primaryColor`, `secondaryColor`. Created in `SaveGameService.createCareer` via `Club.create(...)` + setters. **No financial fields.**
- **`Player`** (`player/Player.java`): attributes + `overall` + Phase-2A/2E state. Created via the 13-arg `Player.create(...)` (called by `createCareer` and `NewgenGenerator`). **No wage field.**
- **`SaveGameService.createCareer`** seeds clubs + players + one competition + fixtures. `NewgenGenerator.generate(...)` creates replacement youth at rollover.
- **Advance flow** — `RoundSimulationService.simulateNextRound(saveGameId, userId)` simulates a round's fixtures, applies the player-state lifecycle, persists players, bumps `currentRound`, and sets `status = FINISHED` when `currentRound >= totalRounds`. Returns `SimulateRoundResponse(round, results, seasonFinished, bookings, newSuspensions)`.
- **Season rollover** — `SeasonRolloverService.rollover(...)` (Phase 2E) archives the finished season, ages/retires/regenerates players, regenerates fixtures, advances the year. It purges the season's `match_events`/`fixtures` and clears the managed lineup, with FK-safe flush ordering.
- **Standings** — `StandingsCalculator.compute(clubs, playedFixtures) : List<StandingRow>` (pure, sorted points→GD→GF→name). `StandingsService.getStandings(saveGameId, competitionId, userId)` wraps it with ownership.
- **Latest migration is `V4`** → this slice adds **`V5`**.
- Cross-cutting conventions: Controller→Service→Repository; records for DTOs; UUID ids; **`BigDecimal` for money (never float/double)**; `Instant`/`LocalDate`; constructor injection; enums with behavior; REST `/api/v1`, plural nouns, precise status codes, standard error JSON `{error, message, status}`; owner (IDOR) checks return 404; deterministic seeded engine; ≥80% coverage, Given/When/Then, Testcontainers (backend) + RTL (frontend).

## 3. Locked decisions (from brainstorming)

1. **Money flows (5):** matchday **gate income** (home games), per-round player **wages**, per-round **sponsorship** income, per-round **stadium upkeep** cost, and end-of-season **prize money** by final position.
2. **Wages:** **per-player** wage (`BigDecimal`, derived from overall + age), summed into a per-round wage bill; newgens get a wage. Forward-compatible with 2C transfers.
3. **Scope:** **all clubs' balances evolve** each round (full income/wages engine for every club), but only the **managed club** records an itemized transaction ledger and has finance UI.
4. **Bankruptcy:** negative balances are **allowed** with a visible UI warning; no hard consequence in 2B (real consequences arrive with 2C/Phase 3).
5. **Architecture:** **Approach A — stored balance per club + managed-club ledger.** A pure `FinanceCalculator` holds all formulas (no RNG); a `FinanceService` orchestrates per-round and season-end processing hooked into the existing advance/rollover flow.

## 4. Architecture overview

A pure **`FinanceCalculator`** owns every formula (wages, reputation, capacity, starting balance, sponsorship, upkeep, gate income, prize money) and is fully deterministic — **no RNG**, so finances never threaten the seeded-engine determinism. A **`FinanceService`** (`@Service`) orchestrates persistence: it runs each round inside `RoundSimulationService.simulateNextRound`, runs season-end inside the same flow when the season finishes, and serves the managed club's finances to the API. Career creation populates each club's financial profile and each player's wage; rollover purges the season's ledger while balances persist on the club. Each unit has one responsibility and is independently testable.

## 5. Data model — migration `V5__finances.sql` (additive only)

**`clubs`** — add financial columns (populated at career creation; `DEFAULT 0` satisfies the migration on any pre-existing rows):
- `balance NUMERIC(15,2) NOT NULL DEFAULT 0`
- `reputation INT NOT NULL DEFAULT 0`
- `stadium_capacity INT NOT NULL DEFAULT 0`
- `sponsorship_per_round NUMERIC(12,2) NOT NULL DEFAULT 0`
- `stadium_upkeep_per_round NUMERIC(12,2) NOT NULL DEFAULT 0`

**`players`** — add:
- `wage NUMERIC(12,2) NOT NULL DEFAULT 0`

**`finance_transaction`** — managed-club itemized ledger:
- `id UUID PRIMARY KEY`
- `save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE`
- `club_id UUID NOT NULL` (plain UUID — denormalized, no FK)
- `season_year INT NOT NULL`
- `round INT NOT NULL`
- `type VARCHAR(16) NOT NULL` (`GATE`/`WAGES`/`SPONSOR`/`UPKEEP`/`PRIZE`)
- `amount NUMERIC(15,2) NOT NULL` (signed: income positive, costs negative)
- `description VARCHAR(120) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- index on `(save_game_id, club_id)`

New entity `FinanceTransaction` + `FinanceTransactionRepository`:
- `findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(UUID saveGameId, UUID clubId)` (newest-first ledger for the UI).
- `deleteBySaveGameId(UUID saveGameId)` (rollover purge).

`FinanceTransactionType` — enum (`GATE`, `WAGES`, `SPONSOR`, `UPKEEP`, `PRIZE`).

## 6. Java entity changes (no schema change beyond V5)

- **`Club`** += `BigDecimal balance`, `int reputation`, `int stadiumCapacity`, `BigDecimal sponsorshipPerRound`, `BigDecimal stadiumUpkeepPerRound`. Add `adjustBalance(BigDecimal delta)` (mutates balance) and setters for the profile fields (set once at creation). The static `Club.create(...)` stays minimal; the profile is applied via setters in `createCareer` (mirrors the existing `setNickname`/`setPrimaryColor` pattern).
- **`Player`** += `BigDecimal wage` with `getWage()`/`setWage(BigDecimal)`. The 13-arg `Player.create(...)` is unchanged; `createCareer` and `NewgenGenerator` call `setWage(...)` right after creating each player (so existing callers/tests are untouched; un-set wage defaults to the migration's 0).

## 7. FinanceCalculator (pure, deterministic, unit-tested)

All money is `BigDecimal`. Constants live here verbatim (the plan pins exact values); the calibration target is mild tension — home rounds run a small surplus, away rounds a deficit, a season roughly break-even, over-spending pushes a club negative.

- `weeklyWage(int overall, int age) : BigDecimal` — rises steeply with overall (e.g. proportional to `overall²`), with a mild age adjustment.
- `reputation(List<Player> squad) : int` — average squad overall, clamped to a sane band.
- `stadiumCapacity(int reputation) : int` — base + reputation-scaled.
- `startingBalance(int reputation) : BigDecimal` — reputation-scaled.
- `sponsorshipPerRound(int reputation) : BigDecimal` — reputation-scaled.
- `upkeepPerRound(int stadiumCapacity) : BigDecimal` — capacity-scaled.
- `gateIncome(int homeReputation, int awayReputation, int stadiumCapacity) : BigDecimal` — `attendance = min(capacity, round(capacity × fill))` with `fill` a clamped function of home/away reputation; `gate = attendance × ticketPrice`. **Attendance never exceeds capacity.** No RNG.
- `prizeMoney(int position, int clubCount) : BigDecimal` — monotonically decreasing with position (champion highest, last lowest), `> 0` for every position.

## 8. FinanceService (`@Service`, orchestration)

- `processRound(UUID saveGameId, int round, List<Fixture> roundFixtures)`:
  - Load all clubs and players (group player wages by club). Build a set of home club ids for `roundFixtures` and a map of opponent reputation.
  - For each club: `wageBill = Σ player wages`; `sponsorship`, `upkeep` from its profile; `gate = home ? gateIncome(homeRep, awayRep, capacity) : 0`. `net = gate + sponsorship − wageBill − upkeep`; `club.adjustBalance(net)`.
  - For the **managed club only**, write `finance_transaction` rows for the round: `GATE` (if home), `SPONSOR`, `WAGES` (negative), `UPKEEP` (negative).
  - `saveAll(clubs)`.
- `processSeasonEnd(UUID saveGameId)`:
  - Compute final standings with the pure `StandingsCalculator` (load clubs + played fixtures of the save's competition — no ownership coupling). Award `prizeMoney(position, clubCount)` to each club by its final position; `adjustBalance`. Managed club gets a `PRIZE` row. `saveAll(clubs)`.
- `getFinances(UUID saveGameId, UUID clubId, UUID userId) : FinancesResponse`:
  - `saveGameService.requireOwned(...)`; the club must belong to the save else `ApiException("CLUB_NOT_FOUND", ..., HttpStatus.NOT_FOUND)`.
  - Return balance + newest-first current-season transactions.
- `roundFinanceFor(UUID saveGameId, UUID managedClubId, ...) : FinanceRoundResponse` — the managed club's per-round breakdown (gate, sponsorship, wages, upkeep, prize, net, balance) used to enrich the advance response.

## 9. Hooks into the existing flow

- **`RoundSimulationService.simulateNextRound`**: after persisting player outcomes and bumping `currentRound`, call `financeService.processRound(saveGameId, roundJustPlayed, fixtures)`; if the season just finished (`status == FINISHED`), call `financeService.processSeasonEnd(saveGameId)`. Build the managed club's `FinanceRoundResponse` and include it on `SimulateRoundResponse`.
- **`SimulateRoundResponse`** gains `FinanceRoundResponse finance` (managed club: gate, sponsorship, wages, upkeep, prize, net, balance). `prize` is non-zero only on the season-finishing advance.
- **`SeasonRolloverService`**: in the purge step, `financeTransactionRepository.deleteBySaveGameId(saveGameId)` (current season's ledger). Balances persist on clubs; club financial profiles are fixed at creation. Newgens already carry a wage, so the new season's wage bill reflects the changed roster.
- **`SaveGameService.createCareer`**: per club, compute `reputation` from its squad, then set `stadiumCapacity`, `balance` (starting), `sponsorshipPerRound`, `stadiumUpkeepPerRound` via `FinanceCalculator`; per player, `setWage(FinanceCalculator.weeklyWage(overall, age))`.
- **`NewgenGenerator`**: set the newgen's wage via `FinanceCalculator.weeklyWage(...)`.

## 10. REST API (`/api/v1`, owner-guarded → 404 on foreign user)

- **`GET /save-games/{saveGameId}/clubs/{clubId}/finances`** → `200`, `FinancesResponse(BigDecimal balance, List<FinanceTransactionResponse> transactions)`; `FinanceTransactionResponse(int seasonYear, int round, String type, BigDecimal amount, String description)`. `requireOwned` + club-belongs-to-save (`CLUB_NOT_FOUND` 404). Transactions newest-first, current season.
- **`SimulateRoundResponse`** (existing `POST /save-games/{saveGameId}/advance`) gains `FinanceRoundResponse finance(BigDecimal gate, BigDecimal sponsorship, BigDecimal wages, BigDecimal upkeep, BigDecimal prize, BigDecimal net, BigDecimal balance)`.
- Money fields serialize as JSON numbers (BigDecimal server-side).

## 11. Frontend (React + TS + Vite + TanStack Query)

- **`FinancesPage`** (route `/finances`, reads `saveGameId`/`clubId` from query): current balance (rendered red with a warning when `< 0`), a latest-round breakdown (gate/sponsor/wages/upkeep/net grouped from the newest round's transactions), and a transactions table (round · type · signed amount · description). Format money with `Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })`.
- **`AdvancePanel`**: a "Finanças da rodada" summary (gate, sponsorship, wages, upkeep, net, new balance; plus prize on season finish) from the enriched advance response.
- **`DashboardPage`**: show the balance and a "Finanças" link.
- **API client + types**: `getFinances(saveGameId, clubId, token)`; types `FinancesResponse`, `FinanceTransaction`, `FinanceRoundResponse`; `SimulateRoundResponse` += `finance`.

## 12. Error handling

- Standard `{error, message, status}`. New code: `CLUB_NOT_FOUND` (404) for a club not in the save. Foreign user → 404 (existing IDOR policy).
- Negative balance is **not** an error — surfaced only as a UI warning (the frontend computes `balance < 0`).

## 13. Testing (≥80% coverage, Given/When/Then; Testcontainers backend, RTL frontend)

**Unit:**
- `FinanceCalculatorTest` — `weeklyWage` (overall/age, exact values, higher overall ⇒ higher wage); `reputation` from a squad; `stadiumCapacity`/`startingBalance`/`sponsorshipPerRound`/`upkeepPerRound` formulas; `gateIncome` (attendance clamped ≤ capacity, higher reputations ⇒ higher gate); `prizeMoney` (monotonically decreasing by position, champion highest, all `> 0`). Deterministic exact assertions.

**Integration (Testcontainers Postgres):**
- `FinanceServiceIT` — createCareer populates every club's `balance`/`reputation`/`stadiumCapacity`/`sponsorshipPerRound`/`stadiumUpkeepPerRound` (`> 0`) and every player's `wage` (`> 0`); advancing a round changes **every** club's balance by the expected `net` (home clubs include gate); the managed club has itemized `finance_transaction` rows for that round (4 incl. `GATE` when home, 3 when away) with correct signs; advancing to season end awards prize (managed `PRIZE` row; the champion's balance increased by the top prize); `getFinances` returns balance + newest-first transactions for the owner and throws 404 for a foreign user and for a club not in the save.
- Rollover finance behavior — after `SeasonRolloverService.rollover`, the prior season's `finance_transaction` rows are purged and club balances are carried forward (unchanged by the purge); newgens have a non-zero wage.
- `SaveGameServiceIT` extended — assert wages + club financial profile are populated on career creation.

**Frontend (RTL):**
- `FinancesPage.test` — renders balance, the transactions table, and the negative-balance warning when `balance < 0`.
- Extended `AdvancePanel.test` — the round finance summary (and new balance) renders after advancing.

## 14. Forward-compatibility & out of scope

- **2C Transfers:** every club already has a `balance` and every player a `wage`, so buying/selling adjusts balance and the wage bill directly; transfer-budget enforcement (blocking buys you can't afford) belongs to 2C, not here.
- **2D Multiple competitions:** prize money is computed per the save's (single) competition today; when 2D lands, `processSeasonEnd` generalizes to award per competition.
- **Out of scope now:** transfers/contracts, board objectives, loans/overdraft mechanics, bankruptcy consequences, dynamic reputation/capacity growth, ticket-price management, detailed sponsorship negotiation — later phases.

## 15. Acceptance criteria

A manager can open a finances screen showing the club balance and an itemized ledger; advancing a round visibly moves the money (gate at home, sponsorship in, wages + upkeep out) for the managed club and silently for all AI clubs; finishing a season pays prize money by final position; a season is roughly break-even with the risk of going negative (allowed, warned). Backend and frontend suites green; coverage ≥80%; engine determinism preserved (finances use no RNG).
