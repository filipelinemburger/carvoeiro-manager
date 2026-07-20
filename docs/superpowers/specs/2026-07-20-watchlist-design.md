# Watchlist ("Observados") — Design Spec

**Date:** 2026-07-20
**Cycle:** 3 of 3 (real free agents ✓ → pre-contract offers ✓ → **watchlist**) — final cycle of the improvement bundle.

## Goal

Let the manager mark rival-club players and free agents they're tracking, see them in a
dedicated "Observados" list with each one's current status, and get proactively notified
when a watched player is sold, transfer-listed, or becomes a free agent. Separately and
unconditionally, alert the manager whenever any of **their own** squad players receives a
transfer offer from another club.

## Scope decisions (from brainstorming)

- **Watchlist is rival-only.** You watch players who are NOT on your club — rival-club
  players and free agents. Watching your own player is a no-op, so the toggle is simply
  not offered for own-squad players. (The user's own words: "it should only work for
  those players from other clubs.")
- **Own-squad offer alerts are a separate, unconditional feature**, not gated by the
  watchlist. "We need always to get notified when a player from our own squad receives an
  offer." It rides the same alert plumbing but fires for every incoming offer regardless
  of any watchlist state.
- **Alert events: sold, listed, free agent** (watchlist-gated) **plus offer-received**
  (own-squad, unconditional). Deliberately excluded: pre-contract-eligibility alerts
  (would need new day-over-day diffing of a stateless computed query) and rival-player
  offer alerts (AI-to-AI negotiation is instantaneous and has no observable pending-offer
  state — exposing it is a large engine change, out of scope for this cycle).

## Context (verified against current code)

- **No event bus exists** anywhere in the backend (no `ApplicationEventPublisher`,
  no `@EventListener`). Every candidate trigger is a raw entity mutation inside a service
  method. So alerts are wired as **direct calls** into a new notifier at each trigger
  site — the exact pattern `PreContractService.persistPreContractNews` already
  established in cycle 2 (an independent second writer of `news_item` rows, building an
  ad-hoc payload `Map`, not tied to the season-advance `AdvanceResponse`).
- **News `type` is a plain `String` column, max length 16** (`NewsItem.java`). Existing
  values: `MATCH, TRANSFER, FINISHED, TRAINING_REPORT, PRECONTRACT`. New value:
  `WATCHLIST` (9 chars, fits).
- **`AdvanceEventCard.tsx`** dispatches rendering by literal `result.type === '...'`
  checks with inline pt-BR labels — a new `WATCHLIST` branch follows the same pattern.
- **Trigger choke points** (all confirmed single points that every path funnels through):
  - Transfer completes → `TransferExecutor.execute` (all transfers, offer-based AND
    AI-to-AI, funnel here).
  - Player transfer-listed → `TransferService.listPlayer` (manager) and
    `AiListingEngine` (all AI listing reasons).
  - Player lapses to free agent → `SeasonRolloverService`'s contract-expiry branch (now
    covers both managed and AI players since cycle 2).
  - Incoming offer created → `TransferService.generateIncomingOffers` (the only place AI
    clubs bid on the manager's players).
- **`PlayerDetails.tsx`** is a shared MUI dialog reused across Squad/Market/Free
  Agents/Pre-Contracts. It already threads optional action props (`contractAction`,
  `listingAction`) per caller — a watch star in its title bar appears consistently
  across every surface from one integration point.
- **Rollover purges the news feed** before the player loop runs
  (`newsItemRepository.deleteBySaveGameId`), so the `FREE_AGENT` alert (which happens
  during rollover) cannot be a news item — it rides a new `RolloverResponse` field,
  exactly as cycle 2's pre-contract execution events do.

## 1. Data model

New table `watchlist_entry` (JPA entity `WatchlistEntry`):

| column | type | notes |
|---|---|---|
| id | UUID | PK |
| save_game_id | UUID | FK → save_games(id) ON DELETE CASCADE |
| player_id | UUID | FK → players(id) ON DELETE CASCADE |
| created_at | TIMESTAMP | when it was added |

`UNIQUE (save_game_id, player_id)` — one entry per player per save. Flyway migration
`V31` (or next free number at implementation time), declaring both FKs and the unique
constraint up front. Entity follows `PreContractAgreement`'s exact shape: protected
no-arg constructor, private full constructor, static `create(saveGameId, playerId,
createdAt)` factory, getters only. Repository `WatchlistEntryRepository`:
`List<WatchlistEntry> findBySaveGameId(UUID)`,
`boolean existsBySaveGameIdAndPlayerId(UUID, UUID)`,
`List<UUID> ... ` (see notifier below — needs a bulk "which of these player ids are
watched" query; expose `List<WatchlistEntry> findBySaveGameIdAndPlayerIdIn(UUID,
Collection<UUID>)` or a projection of watched player ids),
`@Transactional void deleteBySaveGameIdAndPlayerId(UUID, UUID)`,
`@Transactional void deleteBySaveGameId(UUID)`.

Note: `save_game_id` needs an explicit FK here (unlike `pre_contract_agreement`, which
omitted it) — `news_item` needed a follow-up migration to add exactly this, so declare
it up front.

## 2. Alert generation

One shared response record `PlayerAlertResponse(String kind, String playerName, String
clubName, String detail)` — `kind` ∈ `{SOLD, LISTED, FREE_AGENT, OFFER_RECEIVED}`;
`detail` carries the human-readable extra (e.g. the fee for a sale, the offer amount, the
listing reason), nullable. Mirrors cycle 2's `PreContractEventResponse` multi-kind
pattern.

A new `WatchlistNotifier` service is the single writer. Two entry points:

- `recordIfWatched(SaveGame save, Player player, String kind, String clubName, String
  detail)` — checks `existsBySaveGameIdAndPlayerId`; if watched, persists a `WATCHLIST`
  news item (ad-hoc payload `Map` with `type`, `date`, and a `playerAlerts` list — same
  shape as `persistPreContractNews`). Used by `SOLD` and `LISTED`.
- `recordOfferReceived(SaveGame save, Player ownPlayer, String biddingClubName, BigDecimal
  amount)` — **unconditional** (no watchlist check; the player is the manager's own).
  Persists a `WATCHLIST` news item with an `OFFER_RECEIVED` alert.

All four in-season alert kinds occur **during a season advance** (AI-to-AI trading,
AI listing, and incoming-offer generation all run inside a TRANSFER-day market pulse;
the manager's own out-of-band transfers never fire a watchlist alert — see the SOLD rule
below). So the notifier **writes each in-season alert as its own standalone `WATCHLIST`
news item directly** (the `persistPreContractNews` precedent — an independent `news_item`
writer with an ad-hoc `Map` payload `{type: "WATCHLIST", date, playerAlerts: [...]}`),
rather than threading a return value up through the transfer-engine layers. Consequence:
in-season alerts appear in the **Notícias feed** (refetched after each advance), not in
the immediate advance-result card — acceptable, and the feed is exactly where a manager
looks for "what happened to the players I'm tracking." The backend Java `AdvanceResponse`
record is therefore NOT modified; only the frontend TS `AdvanceResponse` type gains a
`playerAlerts` field to read the stored `WATCHLIST` payload (same as cycle 2's
`preContractEvents`).

Trigger wiring:

- **SOLD** — in `TransferExecutor.execute`, after ownership changes, call
  `recordIfWatched(..., "SOLD", <new club name>, <fee>)` for the transferred player,
  **only when the destination club is NOT the manager's club**. A watched rival moving to
  any other club is the actionable "you missed out" signal; the manager buying the watched
  player themselves is their own deliberate action and fires no alert. Covers AI↔AI and
  AI→other-AI moves of a watched rival.
- **LISTED** — in `TransferService.listPlayer` and each `AiListingEngine` listing site,
  call `recordIfWatched(..., "LISTED", <owning club name>, <reason>)`. Fires for a watched
  rival player being transfer-listed by anyone.
- **OFFER_RECEIVED** — in `TransferService.generateIncomingOffers`, when an incoming
  offer is created for one of the manager's own players, call `recordOfferReceived(...)`.
  Unconditional — complements the existing aggregate "N proposta(s) recebida(s)" card with
  a per-player callout.
- **FREE_AGENT** — in `SeasonRolloverService`'s contract-expiry lapse branch, collect a
  `PlayerAlertResponse` for each watched player that lapses. Because rollover purges the
  news feed, these do NOT become news items — they surface via a new
  `RolloverResponse.watchlistEvents` field (a `List<PlayerAlertResponse>`), rendered in
  the season-summary card. Watchlist entries for players who lapse are otherwise left
  intact (the manager may keep watching a now-free agent — see cleanup below).

Watchlist row cleanup: a watchlist entry becomes stale only if its player is deleted
(retirement, youth departure) — the `ON DELETE CASCADE` FK handles that automatically.
A `SOLD`/`FREE_AGENT` player still exists, so the manager keeps watching them (their
status in the Observados list just updates). No manual purge needed beyond the cascade.

## 3. API & frontend

Backend — `WatchlistController` (`/save-games/{saveGameId}/watchlist`):

- `POST /{playerId}` — add to watchlist. Validates the player exists in the save and is
  NOT on the manager's club (reject with a new `ErrorCode` —
  `CANNOT_WATCH_OWN_PLAYER`, 422 `UNPROCESSABLE_CONTENT` — if it is). Idempotent on the
  unique constraint (adding an already-watched player is a no-op success).
- `DELETE /{playerId}` — remove (idempotent).
- `GET /` — the watched list. Each `WatchlistPlayerResponse` carries: `playerId, name,
  position, age, overall, clubName` (or a free-agent marker when `clubId == null`),
  `transferListed`, and `preContractEligible` (computed read-only from the existing
  `PreContractWindow.isOpen(currentDate) && contractYears == 1` check — no new state).
  Sorted by overall desc (consistent with the free-agents/eligible lists).

New `ErrorCode`: `CANNOT_WATCH_OWN_PLAYER(HttpStatus.UNPROCESSABLE_CONTENT)`, inserted
alphabetically.

Frontend:

- **Star toggle** — a MUI `IconButton` (`StarIcon` filled / `StarBorderIcon` outline —
  the app's first icon-toggle) in `PlayerDetails.tsx`'s `DialogTitle`, next to the Close
  button. Rendered **only when the player is not on the manager's club** (the dialog
  already knows the managed club id via its callers). `watchMut`/`unwatchMut` follow the
  existing `listMut`/`unlistMut` shape, invalidating `['watchlist', saveGameId]`.
  `aria-label` matches the app's convention: `` `${watched ? 'Remover da' : 'Adicionar à'}
  lista de observação de ${player.name}` ``.
- **"Observados" tab** — a 5th tab in `TransferMarketPage.tsx` (after Pré-contratos, both
  desktop `Tabs` and mobile `NativeSelect`), rendering the watched list: name, position,
  age, overall, current club or "Agente livre", status chips (Listado / Elegível a
  pré-contrato), and an unwatch action per row. Empty-state copy when nothing is watched.
- **News rendering** — a `result.type === 'WATCHLIST'` branch in `AdvanceEventCard.tsx`
  rendering the alert kinds with pt-BR + emoji, keyed on the stored `playerAlerts` payload
  field (null-guarded like `preContractEvents`): e.g. `🔭 <b>Fulano</b> foi vendido ao
  <b>Clube</b>`, `📋 <b>Fulano</b> foi listado para transferência`, `📥 <b>Fulano</b>
  recebeu proposta do <b>Clube</b> (R$…)`. Each `WATCHLIST` news item is a standalone entry
  written by the notifier (see §2) — no change to the backend `AdvanceResponse` record.
- **Rollover summary** — a "Observados que viraram agentes livres" section in the
  season-summary card, from `RolloverResponse.watchlistEvents`, null-guarded.
- **Cache invalidation** — `['watchlist', saveGameId]` invalidated on advance and rollover
  (alongside the pre-contract keys) since a watched player's status changes when the
  world advances.

## 4. Frontend types

`WatchlistPlayerResponse` and `PlayerAlertResponse` added to `frontend/src/api/types.ts`,
matching the backend records field-for-field. The frontend TS `AdvanceResponse` type gains
`playerAlerts?: PlayerAlertResponse[] | null` (to read stored `WATCHLIST` news payloads —
the backend Java record is unchanged, see §2); the `type` union gains `'WATCHLIST'`. The
frontend `RolloverResponse` type gains `watchlistEvents: PlayerAlertResponse[]` (required,
since `RolloverResponse` is never persisted — same reasoning cycle 2 used for its rollover
fields; the backend `RolloverResponse` record gains the matching field). New api module
`frontend/src/api/watchlist.ts` (`getWatchlist`, `addToWatchlist`, `removeFromWatchlist`).

## 5. Testing

- **Unit:** `WatchlistNotifier` — `recordIfWatched` fires only for watched players and
  no-ops for unwatched; `recordOfferReceived` always fires; payload shape/kind correctness.
- **IT (Testcontainers):** toggle API (add, remove, idempotent re-add, reject own-squad
  player with `CANNOT_WATCH_OWN_PLAYER`, GET returns correct status/flags); each hook
  end-to-end — selling a watched rival produces a `SOLD` news item; listing a watched
  rival produces `LISTED`; an incoming offer on an own player produces `OFFER_RECEIVED`
  (and un-watched rivals produce nothing); a watched player lapsing at rollover appears in
  `RolloverResponse.watchlistEvents`; watchlist entry auto-removed when its player is
  deleted (cascade).
- **Frontend:** star-toggle component test (renders only for rival players, hidden for own
  players, calls the right mutation); Observados tab test (renders watched rows with
  status, unwatch action calls the mutation).

## Out of scope (this cycle)

- Pre-contract-eligibility alerts (needs day-over-day diffing of a computed query;
  the Observados list DOES show a static "elegível a pré-contrato" flag, but no push
  alert when a player crosses into eligibility).
- Rival-player offer alerts (AI-to-AI negotiation is instantaneous, no observable pending
  state — a separate engine change).
- Watching your own squad players (explicitly rival-only per the user).
- Notes, tags, priority levels, or scouting reports on watched players — pure on/off.

## Numbers / naming summary

| item | value |
|---|---|
| New table | `watchlist_entry` (V31) |
| News type string | `WATCHLIST` (9 chars) |
| Alert kinds | `SOLD`, `LISTED`, `FREE_AGENT`, `OFFER_RECEIVED` |
| New ErrorCode | `CANNOT_WATCH_OWN_PLAYER` (422) |
| New tab label | "Observados" |
| Watchlist scope | rival-club players + free agents only (never own squad) |
| Offer-received alert | unconditional, own-squad only |
