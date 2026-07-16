# Frontend Caching / Network-Request Reduction — Design Spec

**Date:** 2026-07-16
**Status:** Approved

## Problem

Every query in the frontend currently runs with TanStack Query's default `staleTime: 0`
(confirmed: `frontend/src/main.tsx` constructs `new QueryClient()` with no `defaultOptions` at
all), so every page mount and every window refocus triggers a fresh fetch — even for data that
hasn't changed since it was last loaded. A handful of queries (`saveGame`, `advancePreview`,
`liveMatch`, `lineup`) locally override this with a short `staleTime` (5–30s), but Squad, News,
Standings, Market, Free Agents, Season History, and Base all refetch unconditionally on every
visit. The user asked for these seven areas (plus the Base/youth tab, added mid-session) to be
held in client memory and only re-fetched when something has actually changed, with optimistic
updates where the change is genuinely predictable client-side.

## Global Mechanism

Every targeted query moves to `staleTime: Infinity` — it never goes stale on its own. The
*only* way it refetches is an explicit `invalidateQueries` call, fired by a mutation this app
already performs (a save, a sign, an advance, a rollover). This is a deliberate trade from
today's "eventually self-heals via refetch-on-mount" behavior to "correct only if every trigger
is wired" — which means the design's real work is enumerating every action that can change each
piece of data and making sure each one fires the right invalidation. Section by section below,
existing triggers are marked **(existing, correct)**; missing ones are marked **(gap — added by
this work)**.

No global `QueryClient` default changes — `staleTime: Infinity` is set per-query (via each
`useQuery` call's options), not globally, since some queries (`saveGame`, `advancePreview`,
`liveMatch`) are deliberately short-lived/polling and must keep their existing behavior
untouched.

**Background image** (`frontend/src/theme.css`'s `body { background: url(...) }`) is explicitly
**out of scope** — confirmed it's a remote third-party CSS background, never fetched through the
API or React Query; the browser's own HTTP cache already owns it and no code change here would
have any effect.

## Squad / Escalação

**Query:** `['squad', saveGameId, clubId]`, `staleTime: Infinity`.

**Invalidation triggers:**
- Contract renewal, list/unlist for transfer, youth promote/release, round-advance (broad
  `['squad']`), live-match-finish (broad `['squad']`) — **(existing, correct)**, unchanged.
- Signing a free agent (`FreeAgentsPage.tsx`'s `signMut`) — **(gap)**. Currently invalidates
  only `['freeAgents', saveGameId]`; a signed player joins the managed club's roster, so it must
  also invalidate `['squad', saveGameId, clubId]`.
- Accepting an incoming transfer offer that sells a managed player away
  (`TransferMarketPage.tsx`'s `respondMut`, action `ACCEPT`) — **(gap)**. See the Market section
  below for how this mutation is handled overall; regardless of the optimistic/write-through
  mechanism chosen there, the managed club's squad shrinks and `['squad', saveGameId,
  managedClubId]` must end up invalidated.
- Season rollover (`SeasonSummaryPage.tsx`'s `rollover` mutation) — **(gap)**. This mutation
  currently has **no `onSuccess` handler of any kind** — confirmed by reading the file. Aging,
  retirements, and contract expiry all happen here; it must invalidate `['squad', saveGameId,
  managedClubId]` (see the shared "rollover becomes an invalidation hub" note at the end of this
  spec — the exact `managedClubId` is available on the page via the existing `saveGame` query).

**Write-through instead of invalidate, for the two save actions:**
- `TacticsPanel`'s formation-instructions save currently invalidates **nothing** at all (not
  even its own `['tactics', saveGameId, clubId]` query) — **(gap)**. Fix: invalidate
  `['tactics', saveGameId, clubId]` on success (plain invalidate; the response doesn't carry the
  full squad, so a write-through isn't available here).
- `SquadPage.tsx`'s `trainingMutation` (`setTraining`) already returns a `SquadResponse` —
  **the exact same shape as the `['squad', saveGameId, clubId]` query itself**. Instead of
  `invalidateQueries` (which triggers a second network round-trip to re-fetch what the mutation
  response already contains), write the response directly into the cache:
  `onSuccess: (data) => queryClient.setQueryData(['squad', saveGameId, clubId], data)`. Zero
  extra requests, zero risk (the data is already server-confirmed, not predicted).
- `SquadPage.tsx`'s `mutation` (`setLineup`) returns a `LineupResponse` (lineup/formation/
  starters/bench) — a different shape from `SquadResponse`, so it write-throughs into
  `['lineup', saveGameId, clubId]` instead (same technique, matching shape). Its existing
  `['squad', saveGameId, clubId]` invalidation stays a plain invalidate — the squad cache has no
  lineup-composition field to write through to (`PlayerResponse` carries no per-lineup flag).

This deliberately does **not** use "true" optimistic updates (predict-then-rollback) for
lineup/training saves: both already show a "saved!" confirmation banner *after* `onSuccess`, so
the UI is a save-then-confirm flow rather than an instant-toggle one, and the mutation responses
already carry the authoritative fresh state — write-through gets the same request-reduction with
strictly less risk (no rollback path to get wrong).

## Mercado

**Query:** `['market', saveGameId, filters, page]`, `staleTime: Infinity` (already
server-paginated via the existing `PageResponse<T>` pattern — no shape change needed).

**Invalidation triggers:**
- Round-advance (`Layout.tsx`'s `advanceMutation`) — **(gap)**. Confirmed by reading the
  mutation's `onSuccess`: it invalidates `saveGame`, `advancePreview`, `news`, `standings`,
  `finances`, `offers`, `squad`, `liveMatch` — but never `market`. AI-to-AI trades and new AI
  listings happen during `TRANSFER`-type advance events (`TransferService.processMarketDay`),
  so market data legitimately changes on every advance with nothing telling the cache. Add
  `['market']` (broad) to this mutation's invalidation list. (Live-match-finish does not need
  this — a match-finish always resolves a `MATCH`-type event, never runs `processMarketDay`.)
- A completed purchase from the market page (`offerMut`/`acceptMut`, when the negotiation
  resolves `ACCEPTED`) removes that listing from availability — plain invalidate of `['market',
  saveGameId]` stays the mechanism here (see below for why this isn't write-through).

**Offers (`['offers', saveGameId]`) get the real optimistic/write-through treatment**, since
this is a small, flat, single-shape list well-suited to direct cache patching:
- **True optimistic** (`onMutate` prediction + `onError` rollback) for `withdrawMut`
  (`withdrawOffer`) and for `respondMut` when the manager submits `action: 'ACCEPT'` or
  `'REJECT'`. Both are cases the **client itself decides** — withdrawing is unconditional, and
  responding to an incoming offer is the manager's own explicit choice, not an AI decision the
  client has to wait to find out — so the outcome is fully predictable before the request
  resolves: remove/update the row in the cached `offers` array immediately, snapshot the
  previous array first (`queryClient.getQueryData`) so `onError` can restore it verbatim, and
  reconcile with `invalidateQueries(['offers', saveGameId])` in `onSettled` regardless of
  outcome (belt-and-suspenders against any server-side divergence, e.g. a race with a
  round-advance). `respondMut`'s `ACCEPT` branch additionally invalidates `['squad', saveGameId,
  managedClubId]` in the same `onSettled` — this is the concrete wiring for the Squad section's
  "accepting an incoming offer must invalidate squad" requirement.
- **Write-through from the response** (not predicted, just avoid the extra refetch) for
  `offerMut` (`makeOffer`) and `acceptMut` (`acceptCounter`): the **AI seller's** ACCEPT /
  COUNTER / REJECT decision (`TransferNegotiator`) is genuinely unknown until the response
  arrives, so there's nothing to predict — but the response is already the resolved
  `TransferOffer`, so `onSuccess: (offer) => { upsert offer into the cached ['offers',
  saveGameId] array by id; if (offer.status === 'ACCEPTED') invalidate ['market', saveGameId]
  and ['squad', saveGameId, managedClubId] }` avoids a redundant offers refetch while still
  correctly triggering the market/squad refetches an actually-completed purchase requires.
  `respondMut` with `action: 'COUNTER'` (the one respondMut branch that isn't purely
  client-decided, since the manager is countering but the AI's next move is still pending)
  follows this same write-through pattern rather than the optimistic one above.

## News

**Backend:** `GET /save-games/{id}/news` gains `page`/`size` query params, mirroring the
existing Market endpoint exactly. Reuse `PageResponse.of(list, page, size)`
(`backend/src/main/java/br/com/carvoeiromanager/common/PageResponse.java`) — the same
in-memory-slice-after-fetch pattern Market already uses for the same reason ("bounded
collections, no need for a DB `LIMIT/OFFSET`"). `AdvanceController.getNews` still calls
`newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId)`, maps every row to
`AdvanceResponse` as today, then slices with `PageResponse.of(responses, page, size)` before
returning — `size` defaults to 10 (matching the "latest 10" ask), `page` defaults to 1 (the
existing 1-based convention). Return type becomes `PageResponse<AdvanceResponse>`.

**Frontend:** `NewsPage.tsx` switches from `useQuery` to `useInfiniteQuery`
(`queryKey: ['news', saveGameId]`, `initialPageParam: 1`, `getNextPageParam` reading
`totalPages`/`page` off the response), `staleTime: Infinity`, page size 10. A "Carregar mais"
button at the bottom of the feed calls `fetchNextPage()` while `hasNextPage` is true — older
history stays reachable, just not loaded upfront.

**Invalidation:** the existing triggers (`advanceMutation`, `finishMut`, both already
invalidate `['news', saveGameId]`) stay unchanged. `invalidateQueries` on an infinite query
refetches every currently-loaded page (not just the first) using each page's original params —
since the backend re-slices the full current list fresh on each call, this correctly reflects
newly-inserted items across all loaded pages with no duplication/gaps, at the cost of
re-fetching more than the strict minimum (one new item) when several pages are already loaded.
Given news only changes on advance/finish — not idle browsing — this is judged an acceptable,
simple trade over hand-rolling a partial-prepend; if it ever proves too chatty, revisit.

## Classificação

**Query:** `['standings', saveGameId, competitionId]`, `staleTime: Infinity`. No invalidation
changes needed — `Layout.tsx`'s `advanceMutation` and `LiveMatchPage.tsx`'s `finishMut` already
invalidate `['standings']` (broad) on the only two paths that can actually resolve a round.
"Only refetch after a new round" is already exactly what these two triggers represent; flipping
`staleTime` from `0` to `Infinity` is the entire change here.

## Agentes Livres

**Query:** `['freeAgents', saveGameId]`, `staleTime: Infinity`.

**Invalidation triggers:**
- Signing (`FreeAgentsPage.tsx`'s `signMut`) — **(existing, correct)**, unchanged (removes the
  signed player from the pool).
- Season rollover — **(gap)**. Expired-contract players lapse into the free-agent pool at
  rollover (`SeasonRolloverService`), and the pool is topped up to its floor there too; nothing
  tells the cache today. Add `['freeAgents', saveGameId]` to the rollover mutation's new
  `onSuccess` handler (see Squad section and the closing note).

No optimistic updates here, per the approved scope: signing is infrequent, requires
server-computed contract terms, and plain invalidate-on-success is already effectively instant
at this call frequency.

## Histórico

**Queries:** `['seasonHistory', saveGameId]` and `['season', saveGameId, year]`, both
`staleTime: Infinity`.

**Invalidation trigger:** season rollover — **(gap)**. Confirmed
`SeasonSummaryPage.tsx`'s `rollover` mutation has zero `onSuccess` handling today. Add
`['seasonHistory', saveGameId]` to its new `onSuccess` handler.

## Base

**Query:** `['youth', saveGameId]`, `staleTime: Infinity`.

**Invalidation triggers:**
- Promote/release (`BasePage.tsx`'s `promote`/`release`) — **(existing, correct)**, unchanged.
- Season rollover — **(gap)**. Youth intake and departures happen at rollover; add
  `['youth', saveGameId]` to the rollover mutation's new `onSuccess` handler.

## The rollover mutation becomes a real invalidation hub

`SeasonSummaryPage.tsx`'s `rollover` mutation (`useMutation<RolloverResponse>({ mutationFn: ()
=> rolloverSeason(...) })`) currently has no `onSuccess` at all — under `staleTime: 0` this was
invisible, since every affected page just refetched on its own next mount. Under `Infinity`,
this single action needs to be the one place that tells four different caches "you're stale
now." Its `onSuccess` gains:

```ts
onSuccess: () => {
  void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
  void queryClient.invalidateQueries({ queryKey: ['youth', saveGameId] })
  void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
  void queryClient.invalidateQueries({ queryKey: ['seasonHistory', saveGameId] })
}
```

(`managedClubId` is already available on this page via its existing `saveGame` query.)

## Testing

- **Backend:** unit/IT coverage for `AdvanceController.getNews`'s new pagination (page 1 default
  size 10, a second page, an out-of-range page returning empty content with correct totals —
  mirroring the existing Market pagination tests' shape).
- **Frontend:** for each area, a test proving the query does NOT refetch on a second mount with
  no intervening mutation (the actual behavior change), and a test proving each new/changed
  invalidation trigger does cause a refetch. For the optimistic/write-through mutations: a test
  proving the cache updates immediately (before the mocked API call resolves, for the true
  optimistic ones) and a rollback test for `onError` on `withdrawMut`/`respondMut`
  (ACCEPT/REJECT). For News: an infinite-scroll test (initial 10, "Carregar mais" loads more,
  button disappears when `hasNextPage` is false).
- Full suites: backend `./gradlew test` (heap already 3g), frontend `npx tsc --noEmit && npx
  vitest run`.

## Out of scope

- Background image (confirmed not a fetch — see Global Mechanism).
- Any global `QueryClient` default change — every `staleTime: Infinity` is set per-query.
- True optimistic updates for Squad's save actions (write-through was judged sufficient and
  lower-risk — see Squad section).
- True optimistic updates for Free Agents, Season History, or Base (per the approved "Squad +
  Market only" scope for this pass).
- Handling multiple browser tabs/windows on the same save game diverging from each other —
  `staleTime: Infinity` means a second tab's own actions won't be reflected in a first tab
  without a shared invalidation channel (e.g. a websocket); out of scope, matches today's
  behavior (no such channel exists today either).
