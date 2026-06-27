# Phase 2C-1 — Transfer Market & Negotiation — Design Spec

**Status:** Approved (brainstorm complete) — ready for implementation plan.
**Date:** 2026-06-26
**Slice:** Phase 2C-1 of the Elifoot Carvoeiro roadmap. 2A (Tactics), 2B (Finances), 2E (Multi-season) are complete and on `main`. This is the first half of the transfer market; **2C-2 (Contracts & free agents)** is the planned follow-up. 2D (Multiple competitions) remains.

---

## 1. Goal

Add a working transfer market with negotiation. During transfer windows, the manager can browse other clubs' players, make offers (with a single round of AI accept/reject/counter), and field unsolicited AI bids for their own players via an inbox (accept/reject/counter). Deals execute against the 2B budget — moving the player, debiting/crediting balances, and recording the finance ledger.

**Playable result:** buy and sell players in transfer windows, negotiating fees within Criciúma's budget.

## 2. Context & current state (what we build on)

- **`Player`** (`player/`): `id`, `saveGameId`, `clubId` (mutable via `setClubId`), `name`, `position`, `age`, `overall`, attributes, Phase-2A state, and `wage` (`BigDecimal`, Phase 2B). The wage stays on the player, so when a player changes club the buyer's wage bill automatically includes them (2B `FinanceService.processRound` sums wages by `clubId`).
- **`Club`** (`club/`): has `balance` (`BigDecimal`) + financial profile + `adjustBalance(BigDecimal)` (Phase 2B).
- **`FinanceTransaction`** + `FinanceTransactionType` enum (`GATE/WAGES/SPONSOR/UPKEEP/PRIZE`), `FinanceTransactionRepository` (Phase 2B). The managed-club ledger is itemized; the column is `VARCHAR(16)`.
- **Lineup** (`match/`): `Lineup` (one per save+club; `penalty_taker_id`/`freekick_taker_id`/`corner_taker_id` UUID columns, **no `ON DELETE CASCADE`**), `LineupPlayer` (`lineup_id`, `player_id`, …; `player_id` references `players(id)` **no cascade**), `LineupRepository.findBySaveGameIdAndClubId`, `LineupPlayerRepository.deleteByLineupId`. (The 2E rollover already navigates this no-cascade hazard.)
- **Advance** — `RoundSimulationService.simulateNextRound(saveGameId, userId)` is `@Transactional`; it simulates the round, applies the player lifecycle, runs 2B finances, bumps `currentRound`, and returns `SimulateRoundResponse`.
- **Rollover** — `SeasonRolloverService.rollover(...)` archives + ages/retires/regenerates + regenerates fixtures + purges season-scoped data (match events/fixtures, finance ledger).
- **`SaveGameService.requireOwned(saveGameId, userId)`** → 404 IDOR guard. Standard error JSON `{error, message, status}`. `HttpStatus.UNPROCESSABLE_CONTENT` = 422.
- **`PlayerRepository.findBySaveGameId`/`findByClubId`**, `ClubRepository.findBySaveGameId`, `CompetitionRepository.findBySaveGameId`. Catarinense: 10 clubs, `totalRounds = 18`. Latest migration `V5` → this slice adds **`V6`**.
- Conventions: Controller→Service→Repository; records for DTOs; UUID ids in static `create(...)`; `BigDecimal` money; constructor injection; enums with behavior; REST `/api/v1`; owner checks 404; deterministic seeded engine; ≥80% coverage, Given/When/Then, Testcontainers + RTL.

## 3. Locked decisions (from brainstorming)

1. **Mechanism:** offer / counter-offer **negotiation** (not instant-buy).
2. **Depth:** **single counter, then decide** — you offer; the AI accepts, rejects, or makes ONE counter; you accept the counter or walk away (and may start a fresh offer). Symmetric for incoming bids.
3. **Timing:** **transfer windows** — a pre-season block and a mid-season block; all transfer actions gated to open windows.
4. **Incoming interest:** **unsolicited** AI bids — during open windows AI clubs may bid for any of your players (an inbox you act on).
5. **Scope split:** this is **2C-1 (transfers & negotiation)**; **contracts, expiry, renewals, and free agents are 2C-2** (not here). Players keep their existing 2B wage; the bought player's wage transfers with them.
6. **Architecture:** **Approach A — persisted `transfer_offer` entity + focused services.** Pure helpers (`TransferValuation`, `TransferWindow`, `TransferNegotiator`); `TransferService` orchestrates; `TransferExecutor` performs the move + money + ledger.
7. **Out of scope:** AI-vs-AI transfers (only managed↔AI); player contracts (2C-2); transfer-listing flags (unsolicited bids make listing unnecessary).

## 4. Architecture overview

Pure, deterministic helpers own the rules: `TransferValuation` (market value from overall + age — **no RNG**), `TransferWindow` (round-based open/closed), `TransferNegotiator` (threshold-based AI accept/reject/counter + seeded bid generation, consistent with the engine's determinism). `TransferService` orchestrates persistence and the offer state machine; outgoing offers receive a **synchronous** AI response, while unsolicited incoming bids are **generated during advance** within an open window and land in an inbox. `TransferExecutor` performs the atomic move (player club change, balance debit/credit, finance ledger) and, on a managed sale, cleans the sold player out of the managed lineup (no-cascade FK safety). Each unit has one responsibility and is independently testable.

## 5. Data model — migration `V6__transfers.sql` (additive only)

**`transfer_offer`** — one negotiation:
- `id UUID PRIMARY KEY`
- `save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE`
- `player_id UUID NOT NULL` (plain UUID — season-scoped, purged at rollover; players can be deleted at rollover)
- `from_club_id UUID NOT NULL` (buyer)
- `to_club_id UUID NOT NULL` (seller / current owner)
- `direction VARCHAR(8) NOT NULL` (`OUTGOING` = managed is buyer; `INCOMING` = managed is seller)
- `amount NUMERIC(15,2) NOT NULL` (current offer fee)
- `counter_amount NUMERIC(15,2)` (nullable; set when countered)
- `status VARCHAR(12) NOT NULL` (`PENDING`, `COUNTERED`, `ACCEPTED`, `REJECTED`, `WITHDRAWN`)
- `round INT NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- indexes on `(save_game_id, status)` and `(save_game_id, to_club_id)`

New entity `TransferOffer` + `TransferDirection`/`TransferOfferStatus` enums + `TransferOfferRepository`:
- `findBySaveGameIdAndStatusIn(UUID, Collection<TransferOfferStatus>)`, `findBySaveGameIdAndToClubIdAndStatusIn(...)`, `deleteBySaveGameId(UUID)`.

**No player/club schema change.** Market value is computed on demand. `FinanceTransactionType` gains **`TRANSFER_IN`** (managed bought: −fee) and **`TRANSFER_OUT`** (managed sold: +fee) — enum-only (fits the `VARCHAR(16)` column).

## 6. Pure helpers (deterministic, no RNG except seeded bid generation)

- **`TransferValuation`** (`@Component`): `marketValue(int overall, int age) : BigDecimal` — rises steeply with overall (e.g. ∝ `overall³`) and a peak-age multiplier (young = potential premium, prime = full, 30+ = decline), `BigDecimal` scale 2. Constants locked in the plan.
- **`TransferWindow`** (`@Component`): `isOpen(int currentRound, int totalRounds) : boolean` — open in a pre-season block (`currentRound < PRE_SEASON_ROUNDS`) and a mid-season block (`[totalRounds/2, totalRounds/2 + MID_LENGTH)`). Deterministic.
- **`TransferNegotiator`** (`@Component`): threshold AI (locked constants):
  - `respondToBuy(value, offer)` → `ACCEPT` if `offer ≥ value×0.95`; `COUNTER` at `value×1.10` if `offer ≥ value×0.70`; else `REJECT`.
  - `respondToSellCounter(value, managedCounter)` → AI `ACCEPT` if `managedCounter ≤ value×1.15`, else `REJECT` (single counter; AI does not re-counter).
  - `bidAmount(value)` → unsolicited AI bid (e.g. `value×0.90`).
  - Bid **selection** (which managed players, how many per round) is seeded from `saveGameId.msb ^ round` — deterministic, matching the match-engine discipline.

## 7. Services

- **`TransferService`** (owner-guarded; mutations also window-/budget-/squad-guarded):
  - `getMarket(saveGameId, userId) : List<MarketPlayerResponse>` — players not at the managed club, with computed value.
  - `makeOffer(saveGameId, userId, playerId, amount) : TransferOfferResponse` — OUTGOING. Guards: window open; player belongs to a non-managed club in the save; managed `balance ≥ amount` (`INSUFFICIENT_FUNDS`); managed squad `< MAX` (`SQUAD_FULL`). Compute value, call `TransferNegotiator.respondToBuy`. Persist offer; `ACCEPT` → `TransferExecutor.execute(...)` at `amount` → `ACCEPTED`; `COUNTER` → `COUNTERED` (+`counter_amount`); `REJECT` → `REJECTED`.
  - `acceptCounter(saveGameId, userId, offerId)` — OUTGOING `COUNTERED`. Re-check window/budget/squad; execute at `counter_amount`; `ACCEPTED`.
  - `withdrawOffer(saveGameId, userId, offerId)` — OUTGOING → `WITHDRAWN`.
  - `respondToIncoming(saveGameId, userId, offerId, action, counterAmount?)` — INCOMING (AI bid on a managed player). `ACCEPT` → execute (managed sells) at `amount`; `REJECT` → `REJECTED`; `COUNTER` → set `counter_amount`, call `TransferNegotiator.respondToSellCounter` → AI `ACCEPT` → execute at `counter_amount` → `ACCEPTED`, else `REJECTED`. Guards: window; managed squad `> MIN` and not selling the last GK (`SQUAD_TOO_SMALL`).
  - `getOffers(saveGameId, userId) : List<TransferOfferResponse>` — active inbox: INCOMING `PENDING`/`COUNTERED` + OUTGOING `COUNTERED`.
  - `generateIncomingOffers(saveGameId, round)` — called during advance within an open window: for a seeded subset of AI clubs, create INCOMING `PENDING` offers (`bidAmount(value)`) for selected managed players. Bounded per round.
- **`TransferExecutor`**: `execute(saveGameId, offer, fee)` — `player.setClubId(buyerClubId)`; `buyer.adjustBalance(fee.negate())`; `seller.adjustBalance(fee)`; persist; write the managed `finance_transaction` (`TRANSFER_IN` −fee if managed bought; `TRANSFER_OUT` +fee if managed sold). **On a managed sale**, remove the player from the managed lineup: delete their `lineup_player` row and null any taker column on the managed `Lineup` that references them (no-cascade FK safety). Squad-limit invariants re-checked defensively.

## 8. Hooks & integration

- **`RoundSimulationService.simulateNextRound`**: after 2B finances, if `transferWindow.isOpen(newCurrentRound, totalRounds)`, call `transferService.generateIncomingOffers(saveGameId, newCurrentRound)`. (Runs inside the existing `@Transactional` advance.)
- **`SeasonRolloverService.rollover`**: in the purge step, `transferOfferRepository.deleteBySaveGameId(saveGameId)` (season-scoped offers; players/clubs change at rollover).
- **Budget/window/squad** reuse 2B balances and the squad (`PlayerRepository.findByClubId(managedClubId).size()`), with `MIN_SQUAD_SIZE`/`MAX_SQUAD_SIZE` constants and a last-GK guard.

## 9. REST API (`/api/v1`, owner-guarded → 404 on foreign user)

Base: `/save-games/{saveGameId}/transfers`.
- `GET /market` → `List<MarketPlayerResponse(UUID playerId, String name, String position, int age, int overall, UUID clubId, String clubName, BigDecimal value, BigDecimal wage)>`.
- `POST /offers` (body `MakeOfferRequest(UUID playerId, BigDecimal amount)`) → `TransferOfferResponse`. Guards → 422 (`TRANSFER_WINDOW_CLOSED`/`INSUFFICIENT_FUNDS`/`SQUAD_FULL`/`INVALID_TRANSFER`).
- `POST /offers/{offerId}/accept-counter` → `TransferOfferResponse` (executes).
- `POST /offers/{offerId}/withdraw` → `TransferOfferResponse`.
- `POST /offers/{offerId}/respond` (body `RespondOfferRequest(String action, BigDecimal counterAmount)` where action ∈ `ACCEPT`/`REJECT`/`COUNTER`) → `TransferOfferResponse`.
- `GET /offers` → `List<TransferOfferResponse>` (active inbox).

`TransferOfferResponse(UUID id, UUID playerId, String playerName, UUID fromClubId, String fromClubName, UUID toClubId, String toClubName, String direction, BigDecimal amount, BigDecimal counterAmount, String status, int round)`. Money serializes as JSON numbers. Action-style POSTs are consistent with the existing `/advance` and `/seasons/rollover`.

## 10. Error handling

Standard `{error, message, status}`. New codes: `TRANSFER_WINDOW_CLOSED` (422), `INSUFFICIENT_FUNDS` (422), `SQUAD_FULL` (422), `SQUAD_TOO_SMALL` (422; also the last-GK guard), `INVALID_TRANSFER` (422; e.g. wrong owner, bad offer state, offer not for the managed club), `TRANSFER_OFFER_NOT_FOUND` (404). Foreign user → 404 (existing IDOR policy).

## 11. Frontend (React + TS + Vite + TanStack Query)

- **`TransferMarketPage`** (route `/transfers`, reads `saveGameId`/`clubId`): when the window is closed, a banner ("Janela de transferências fechada") and read-only market; when open:
  - **Market** table (AI players: name, position, age, overall, club, value) with a "Fazer proposta" action (enter amount → submit → show the AI response: accepted / countered (with accept-counter / withdraw) / rejected).
  - **Inbox** of incoming AI bids for your players (amount) → accept / reject / counter (enter counter → show AI's accept/reject).
  - **Outgoing** countered offers awaiting your decision (accept-counter / withdraw).
  - Money via the existing `formatBRL`.
- **`DashboardPage`**: a "Mercado" link to `/transfers`.
- **API client + types**: `getMarket`, `makeOffer`, `acceptCounter`, `withdrawOffer`, `respondToOffer`, `getOffers`; types `MarketPlayer`, `TransferOffer`, `MakeOfferRequest`, `RespondOfferRequest`. `/transfers` route registered.

## 12. Testing (≥80%, Given/When/Then; Testcontainers backend, RTL frontend)

**Unit:**
- `TransferValuationTest` — value rises with overall; peak-age multiplier; exact deterministic values.
- `TransferWindowTest` — open in the pre-season + mid-season blocks, closed otherwise (boundary rounds).
- `TransferNegotiatorTest` — buy accept/counter/reject thresholds; sell-counter accept/reject; bid amount; selection deterministic for a seed.

**Integration (Testcontainers):**
- `TransferServiceIT` — `makeOffer` at full value → `ACCEPTED`, player moved to managed club, buyer/seller balances adjusted, managed `TRANSFER_IN` row; low offer → `COUNTERED`, then `acceptCounter` executes at the counter; very low → `REJECTED`; `INSUFFICIENT_FUNDS` when over budget; `SQUAD_FULL` at max; window-closed → `TRANSFER_WINDOW_CLOSED`; `respondToIncoming` ACCEPT → managed sells (player moves, `TRANSFER_OUT` row), `SQUAD_TOO_SMALL`/last-GK guard; `respondToIncoming` COUNTER → AI accept/reject; `getOffers` returns the active inbox; foreign user → 404.
- `TransferExecutorIT` (or folded in) — selling a managed player who is in the lineup AND a set-piece taker clears the `lineup_player` row + nulls the taker column (no FK violation).
- Advance integration — advancing into an open window creates INCOMING inbox offers (`generateIncomingOffers`); advancing outside a window creates none.
- Rollover — `transfer_offer` rows are purged on rollover.

**Frontend (RTL):**
- `TransferMarketPage.test` — market renders with values; making an offer shows the AI response (accepted/countered); the inbox renders incoming bids and accept/counter works; the window-closed banner shows when closed.

## 13. Forward-compatibility & out of scope

- **2C-2 (Contracts & free agents):** contract length/expiry/renewals (ticking at rollover), the free-agent pool (sign for no fee), and value/AI-interest tied to contract status. `TransferValuation` and the offer machine are designed to extend (a free-agent signing is an offer with `fee = 0` and no seller).
- **2D (Multiple competitions):** transfer windows are derived from a competition's `totalRounds`; generalizes when multiple competitions exist.
- **Out of scope now:** AI-vs-AI transfers; loans; agent fees; release clauses; multi-round haggling beyond a single counter; transfer-listing flags.

## 14. Acceptance criteria

In an open transfer window, the manager can: browse other clubs' players with valuations; make an offer and get an immediate AI accept/reject/counter (accepting executes the deal — player moves, money moves both ways, the ledger records it); see and respond to unsolicited AI bids for their own players (accept/reject/counter), with sales cleaning the player out of the lineup; and be blocked with clear errors when the window is closed, funds are short, or squad limits would be violated. Outside windows, transfers are disabled. Offers are purged at season rollover. Backend + frontend suites green; coverage ≥80%; engine determinism preserved.
