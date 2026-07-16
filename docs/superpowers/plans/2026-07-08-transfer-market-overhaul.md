# Transfer Market Overhaul — Club Browsing, Listings, Living Market, Filtered Pagination

## Context

Today the transfer market shows **every player of every AI club** (unpaginated, unfiltered), offers are decided by a pure value-ratio negotiator (`accept ≥0.95×value / counter in 0.70–0.95 / reject`), and AI clubs have no market life of their own — they never list, buy, or sell players (the only AI activity is random 15%-chance bids on managed players). There is also no way to browse clubs or view a rival squad in the UI, even though the backend squad endpoint is already club-generic.

This plan delivers: (1) club browsing (find clubs → view squad → player details), (2) a real **transfer-listing** mechanism with AI listing triggers (low morale, unused players, financial trouble), symmetric for the managed club, (3) a market page showing **only listed players**, paginated with filters (club, position, age, value, overall), (4) context-aware offer acceptance for unlisted players (player importance, position cover, club finances), and (5) a **living market** — AI clubs buy listed players from each other in the background, surfaced in News.

User decisions: full AI-to-AI living market; managed club can list its own players; one plan with phased tasks (each phase ships working software).

## Key existing code to reuse (verified)

- `GET /save-games/{id}/clubs/{clubId}/players` (`SquadController` → `LineupService.getSquad`) already works for ANY club — no new squad endpoint needed.
- `PlayerDetails.tsx` modal (CM-style attribute board) is standalone-reusable (props: `player`, `onClose`, optional action ReactNodes).
- `TransferExecutor.execute` already generically moves money/player between two clubs; managed-only ledger rows are already correct.
- AI club balances genuinely evolve (`FinanceService.processMatchday/processMonth` iterate all clubs) — usable for financial triggers.
- `Player.morale` [-5..5] evolves for all clubs each round. **No appearance counter exists** — must be added.
- Season-advance TRANSFER events (`SeasonAdvanceService.processTransfer` → `TransferService.generateIncomingOffers`) are the existing periodic AI hook.
- News = `NewsItem` persisting JSON `AdvanceResponse`; extending that record surfaces AI transfers in the existing news timeline.
- `ErrorCode` enum for new ApiException codes; pt-BR UI copy; page convention `?page=1&size=20`; next migration **V22**.

---

### Task 1: Phase A — Club browsing

**Backend** (new `club/ClubController.java`, `club/ClubService.java`, `club/ClubSummaryResponse.java`):
- `GET /api/v1/save-games/{saveGameId}/clubs` → `List<ClubSummaryResponse>`: `record(UUID id, String name, String commonName, String shortName, String primaryColor, String secondaryColor, int reputation, int squadSize)` — squadSize excludes youth; sorted reputation desc. Unpaginated (~28 clubs).
- Extend `match/PlayerResponse` + `LineupService.getSquad` mapping with computed `BigDecimal value` (`TransferValuation.marketValue`) and `BigDecimal wageDemand` (`ContractValuation.wageDemand`) so any squad view can show fee/wage guidance.

**Frontend**:
- `api/career.ts` `getClubs`; types for `ClubSummary`; `PlayerResponse` += `value`, `wageDemand`.
- New `features/club/ClubsPage.tsx` (route `/clubs`, nav "Clubes" in `Layout.tsx` saveLinks): responsive table/cards (copy `TablePage.tsx` pattern), row → `/club?clubId=…`.
- New `features/club/ClubViewPage.tsx` (route `/club`): read-only roster via existing `getSquad`, `PlayerDetails` modal on row click, per-player "Fazer proposta" (hidden for managed club) opening:
- New `features/transfer/MakeOfferDialog.tsx` — extract the inline offer form (amount/wage/years, `MoneyField`) from `TransferMarketPage.tsx`; move `MoneyField` to `components/MoneyField.tsx`.
- Make standings club names clickable links in `TablePage.tsx` (StandingRow already has `clubId`).

**Tests**: `ClubControllerIT` (clubs of save, youth excluded from squadSize, foreign-save 404); squad IT asserts value/wageDemand; frontend `ClubsPage`/`ClubViewPage` tests (render, navigate, offer dialog fires `makeOffer`).

### Task 2: Phase B — Listings, appearances, AI listing triggers, managed list/unlist

**Migration `V22__transfer_listings.sql`**: `players` += `transfer_listed BOOLEAN NOT NULL DEFAULT FALSE`, `listed_reason VARCHAR(16)`, `season_appearances INT NOT NULL DEFAULT 0`.

**Player.java**: fields + `listForTransfer(ListedReason)` / `unlist()`; `recordAppearance` increments `seasonAppearances`; `resetSeasonState` clears all three. New `player/ListedReason.java`: `PLAYER_REQUEST, UNUSED, FINANCIAL, SURPLUS, OWNER` (pt-BR chips: "pediu para sair", "pouco utilizado", "problemas financeiros", "excesso no elenco").

**New `transfer/ClubFinancialHealthEvaluator.java`**: `FinancialHealth {HEALTHY, TIGHT, DESPERATE}` from balance vs monthly wage bill (same `FinanceCalculator` formula FinanceService charges): DESPERATE < 1× bill, TIGHT < 3× bill.

**New `transfer/AiListingEngine.java`** — `refreshListings(saveGameId, managedClubId, currentRound)`, deterministic (no RNG, pure predicates), per AI club (id-sorted):
- Skip club if squad ≤ 14 (MIN_SQUAD_SIZE). Never list: GK when club has ≤ 2 GKs; club's best player unless DESPERATE. Cap 4 active listings/club.
- Triggers (first match wins): **PLAYER_REQUEST** morale ≤ -3; **UNUSED** `currentRound ≥ 6 && seasonAppearances*3 < currentRound`; **FINANCIAL** if DESPERATE → list up-to-2 highest-value players; **SURPLUS floor** if still zero listings and squad ≥ 17 → list lowest-overall non-GK (guarantees market liquidity; note: fires only for Série B clubs — seeded Catarinense-only squads are 14–15, which is fine/realistic).
- Listings persist until sold or season rollover.

**TransferService**:
- `getMarket` filters `Player::isTransferListed` (also fixes today's youth-prospect leak).
- New `listPlayer`/`unlistPlayer` (managed club only, non-youth, allowed with window closed, reason `OWNER`) → `PUT/DELETE /transfers/listings/{playerId}` in `TransferController`, `record TransferListingResponse(UUID playerId, boolean transferListed, String listedReason)`.
- New orchestrator `processMarketDay(saveGameId, eventDate)`: window-gated → `refreshListings` → `generateIncomingOffers`; called from `SeasonAdvanceService.processTransfer` AND once from `SaveGameService.createCareer` (day-one market).
- `generateIncomingOffers`: listed managed players first, `LISTED_BID_CHANCE = 0.45` vs 0.15, `MAX_BIDS_PER_ROUND` 2→3; `TransferNegotiator.bidAmount(value, listed)` → 0.95 listed / 0.90 unlisted.
- `MarketPlayerResponse` += `listedReason`; `PlayerResponse` += `transferListed`, `listedReason`.

**Frontend**: list/unlist toggle in `PlayerDetails` (new `listingAction` prop, wired from `SquadPage`), "Listado" chips (squad, club view), reason chip on market cards; api `listPlayer`/`unlistPlayer`.

**Tests**: `AiListingEngineIT` (each trigger, protections, cap, idempotency); `ClubFinancialHealthEvaluatorTest` boundaries; selling IT extensions (list/unlist guards → 422 `INVALID_TRANSFER`, listed-only market, boosted bids); rollover IT (listings + appearances cleared).

### Task 3: Phase C — Context-aware negotiation

**New `transfer/SellerContext.java`**: `record(boolean listed, boolean keyPlayer, boolean noCover, FinancialHealth health)`; **new `transfer/SellerContextFactory.java`**: keyPlayer = overall rank ≤ 11 in seller squad; noCover = no other same-position player with `overall ≥ player.overall - 3`; health from evaluator.

**TransferNegotiator** — `respondToBuy(value, offer, SellerContext)` via `askFactor`:
- listed → 0.85; unlisted → 1.00 + 0.20 keyPlayer + 0.15 noCover; × health (DESPERATE 0.75 / TIGHT 0.90 / HEALTHY 1.00); clamp [0.65, 1.50].
- `effective = value × askFactor`; accept ≥ 0.95×effective; counter 1.10×effective when ≥ 0.70×effective; else reject. Deterministic. (Listed+HEALTHY accepts ≈0.81×value; unlisted key player w/o cover ≈1.28×value — the intended "harder" band.)
- `acceptsSellCounter` ceiling stays 1.15 (buyer-side unchanged; tunable later).

`makeOffer` builds context via factory. **Tests**: negotiator unit table (factors, clamps, boundaries); factory IT (rank/cover/health); buying IT (listed lowball accepted; unlisted key player countered high; DESPERATE seller accepts lowball). Existing negotiator tests encode old balance — rewrite, don't patch.

### Task 4: Phase D — Living market (AI-to-AI)

- `TransferDirection` += `AI` (no migration; equality-only usage verified).
- `TransferExecutor`: extract core overload `execute(save, playerId, buyerId, sellerId, fee, wage, contractYears)`; add `player.unlist()` on completion (all transfer paths).
- **New `transfer/AiTransferEngine.java`** — per TRANSFER event, seeded `Random(saveId.msb ^ epochDay ^ 0x9E3779B97F4A7C15L)`:
  - Pool: listed AI players without PENDING/COUNTERED user offers, value desc. Buyers: AI clubs balance desc, squad < 30, health ≠ DESPERATE.
  - Need: position counts below GK 2 / DEF 6 / MID 6 / ATT 4, or upgrade (`player.overall ≥ posAvg + 3`). Price fixed `0.90 × value` (always agreeable vs listed threshold ≈0.81), affordability `price ≤ 0.40 × balance`. Seller re-check: squad stays ≥ 14, keeps a GK. Cap **2 transfers/event**; one move per player.
  - Persist `TransferOffer` (direction AI, ACCEPTED, null wage/years) + `TransferExecutor` core overload.
- `processMarketDay` order: listings → AI transfers → incoming bids; returns completed transfers.
- **News**: `AdvanceResponse` += `List<CompletedTransferResponse> transfers` (`record(String playerName, String fromClubName, String toClubName, BigDecimal fee)`); populate in `processTransfer`; MATCH/FINISHED pass `List.of()`. Frontend NewsPage renders "Transferências" block; **null-guard** `transfers` (old persisted payloads deserialize null).

**Tests**: `AiTransferEngineIT` (need-driven buy, balances move, unlisted after sale, cap, seller floors, pending-offer skip, managed club untouched, determinism); advance IT asserts transfers in response+news payload; NewsPage test w/ and w/o `transfers`.

### Task 5: Phase E — Paginated + filtered market

**Backend** — in-memory filter/sort/page (≤ ~800 players/save; `marketValue` is derived, not stored — don't denormalize):
- New `common/PageResponse.java`: `record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)` + `of(all, page, size)` (1-based, clamp page ≥ 1, size ∈ [1,100]).
- `GET /transfers/market?page=1&size=20&clubId=&position=&ageMin=&ageMax=&overallMin=&overallMax=&valueMin=&valueMax=` → `PageResponse<MarketPlayerResponse>`; new `transfer/MarketFilter.java` record; sort value desc with id tiebreak (stable paging).

**Frontend** — `TransferMarketPage` "Jogadores disponíveis" tab: filter bar (Clube select from `getClubs`, Posição select, Idade/OVR min-max fields, Valor min-max `MoneyField`s, "Filtrar" button), `useQuery(['market', saveGameId, filters, page])`, MUI `<Pagination>`, page reset on filter change, empty state pt-BR. Cards/offer flow unchanged.

**Tests**: `PageResponse` unit; market IT per-filter + combined + clamps + stability; `TransferMarketPage` test (filters refetch, pagination).

---

## Risks / flagged decisions

1. **Market thinness on day one** — SURPLUS floor + career-creation listing run mitigate; thresholds (squad ≥ 17, cap 4) are the key tunables.
2. **`AdvanceResponse` change** ripples through all constructor call sites (compile-safe) and old news payloads (frontend null-guard mandatory).
3. **Negotiator rebalance** intentionally invalidates old threshold tests — rewrite against askFactor math.
4. Unsolicited bids for unlisted managed players remain (listing raises frequency/price, doesn't gate) — matches requirements.
5. AI-AI fixed 0.90×value bypasses the negotiator by design (one-shot; only listed players trade AI-AI).

## Verification

- Full backend suite green per phase: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`); frontend `cd frontend && npx vitest run`.
- End-to-end sanity after D/E: create a fresh career → market tab shows only listed players with reason chips (≈19+ on day one) → advance through a window TRANSFER event → News shows AI-to-AI moves → browse Clubes → open a rival squad → offer for an unlisted key player gets countered ~1.3–1.5×, offer for a listed player accepted near 0.8× value → list a managed player, advance, observe boosted incoming bids.
- Execution per project convention: subagent-driven-development, work on `main`, local commits per task (no push).
