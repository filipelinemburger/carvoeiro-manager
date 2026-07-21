# News Inbox (CM-style) — Design Spec

**Date:** 2026-07-21
**Roadmap:** Group 2 of the 15-item improvement roadmap.
**Status:** Approved design, ready for implementation plan.

## Goal

Turn the News page from a flat scroll of full event cards into a **Championship-Manager-style
master-detail inbox**: a compact headline list on top, the selected item's full card below, with
**read/unread** state and an **unread-count badge** on the "News" nav entry.

## Problem / current state (verified against code)

- [`AdvanceController.getNews`](../../backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java)
  loads every `NewsItem`, deserializes each JSON payload into an `AdvanceResponse`, and slices it
  in memory via `PageResponse.of`. It returns `PageResponse<AdvanceResponse>` — **the NewsItem's id
  and no read flag ever reach the frontend.** Deserialization and repository access happen inline in
  the controller (a layering smell).
- [`NewsItem`](../../backend/src/main/java/br/com/carvoeiromanager/season/NewsItem.java) has
  `id, saveGameId, seq, eventDate, type, payload, createdAt` — **no read flag.**
- [`NewsPage.tsx`](../../frontend/src/features/news/NewsPage.tsx) already infinite-scrolls
  ("Carregar mais") and renders a **full** [`AdvanceEventCard`](../../frontend/src/features/match/AdvanceEventCard.tsx)
  for every item. `AdvanceEventCard` already renders all six types (MATCH, TRANSFER, FINISHED,
  TRAINING_REPORT, PRECONTRACT, WATCHLIST) with its own per-type header title.
- [`Layout.tsx`](../../frontend/src/components/Layout.tsx) builds one `navLinks` array reused by the
  desktop bar and the mobile drawer; the "News" link is a plain label with **no badge**.

The roadmap's "unpaginated" note is stale — pagination + infinite scroll already shipped (Caching
Tasks 1–2). What remains is the master-detail UI and read/unread.

## Decisions (locked with the user)

1. **Layout:** master-detail — headline list on top, full detail card below (not inline accordion,
   not a separate route). Stacked vertically so it stays clean on mobile.
2. **After Avançar:** the newest item is auto-selected and marked read on landing, so the just-played
   result shows in the detail card immediately (preserves today's behavior).
3. **Mark all read:** include a "Marcar todas como lidas" action.
4. **Existing items:** the migration backfills all pre-existing items to read = true (no historical
   unread flood); only items created from now on start unread.
5. **Layering:** extract a `NewsService` and a dedicated `NewsController`; remove the news endpoint
   from `AdvanceController`. The URL path is unchanged, so the frontend is unaffected.

## Architecture

### Backend

**Data layer**
- **Migration `V32__news_read_flag.sql`:**
  ```sql
  ALTER TABLE news_item ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
  UPDATE news_item SET is_read = TRUE;
  ```
  New rows default to `FALSE` (unread); the `UPDATE` backfills every existing row to read.
- **`NewsItem`** gains `private boolean read;` mapped `@Column(name = "is_read", nullable = false)`,
  a getter `isRead()`, and a `markRead()` mutator (`this.read = true`). `NewsItem.create(...)` leaves
  `read = false`, so all ~6 creation sites (SeasonAdvanceService MATCH/TRANSFER/FINISHED/TRAINING_REPORT,
  `persistPreContractNews`, `WatchlistNotifier`) produce unread items with **no change at those sites.**

**Repository — `NewsItemRepository`** gains:
- `int countBySaveGameIdAndReadFalse(UUID saveGameId)` — unread count.
- A bulk mark-all:
  ```java
  @Modifying
  @Transactional
  @Query("update NewsItem n set n.read = true where n.saveGameId = :saveGameId and n.read = false")
  int markAllRead(UUID saveGameId);
  ```
  (`findById` from `JpaRepository` covers single-item lookup for mark-one.)

**Response DTO — `NewsFeedItemResponse`** (new record in the `season` package):
```java
public record NewsFeedItemResponse(UUID id, boolean read, AdvanceResponse payload) {}
```
The `payload` is the unchanged `AdvanceResponse`; the frontend derives the list-row title and the
detail card from it. `id` + `read` are what the flat `AdvanceResponse` could not carry.

**`NewsService`** (new; owns all news logic behind `saveGameService.requireOwned`):
- `getFeed(UUID saveGameId, UUID userId, int page, int size) → PageResponse<NewsFeedItemResponse>` —
  requireOwned, load via `findBySaveGameIdOrderBySeqDesc`, map each item to
  `new NewsFeedItemResponse(item.getId(), item.isRead(), deserialize(item.getPayload()))`, then
  `PageResponse.of(...)`. The JSON deserialization helper moves here from the controller.
- `unreadCount(UUID saveGameId, UUID userId) → int` — requireOwned, `countBySaveGameIdAndReadFalse`.
- `markRead(UUID saveGameId, UUID userId, UUID newsItemId)` — requireOwned; `findById` or throw
  `ApiException(NEWS_ITEM_NOT_FOUND)`; if the item's `saveGameId` ≠ the path `saveGameId`, also throw
  `NEWS_ITEM_NOT_FOUND` (prevents cross-save access); `markRead()`; save. Idempotent.
- `markAllRead(UUID saveGameId, UUID userId)` — requireOwned; `repository.markAllRead(saveGameId)`.

**`NewsController`** (new `@RestController`, `@RequestMapping("/api/v1/save-games")`), thin — delegates
to `NewsService`. Remove `getNews` from `AdvanceController`; the `newsItemRepository`, `objectMapper`,
and `saveGameService` constructor fields then become unused there and are removed too (only
`seasonAdvanceService` and `advancePreviewService` remain).
- `GET  /{saveGameId}/news?page&size` → `PageResponse<NewsFeedItemResponse>`
- `GET  /{saveGameId}/news/unread-count` → `NewsUnreadCountResponse(int count)`
- `POST /{saveGameId}/news/{newsItemId}/read` → `204 No Content`
- `POST /{saveGameId}/news/read-all` → `204 No Content`

**`ErrorCode`** gains `NEWS_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND)` (alphabetical placement between
`MATCH_OVER` and `NO_GK_FOUND`).

### Frontend

**API module — new `frontend/src/api/news.ts`** (mirrors `api/watchlist.ts`; `getNews` moves here
from `api/career.ts`, and the single import in `NewsPage.tsx` is updated):
- `getNews(saveGameId, page, size, token) → Promise<PageResponse<NewsFeedItem>>`
- `getNewsUnreadCount(saveGameId, token) → Promise<number>` (reads `.count`)
- `markNewsRead(saveGameId, newsItemId, token) → Promise<void>`
- `markAllNewsRead(saveGameId, token) → Promise<void>`

**Types — `api/types.ts`:**
```ts
export type NewsFeedItem = { id: string; read: boolean; payload: AdvanceResponse }
```

**Title/icon helper — new `frontend/src/features/news/newsMeta.ts`** (pure, unit-tested; keeps
per-type knowledge in one place):
- `newsTitle(payload: AdvanceResponse): string`
  - `MATCH` → `Rodada ${round + 1} — resultados` (falls back to `Resultados` when `round` is null)
  - `TRANSFER` → `Janela de transferências — ${bidsReceived} proposta(s)`
  - `WATCHLIST` → `Alertas — ${playerAlerts?.length ?? 0} observado(s)`
  - `PRECONTRACT` → `Pré-contratos`
  - `TRAINING_REPORT` → `Relatório de treino`
  - `FINISHED` → `Temporada encerrada`
- `newsIcon(type: AdvanceResponse['type']) → ReactNode` — one MUI icon per type (e.g. sports-soccer,
  swap-horiz, visibility, fitness-center, emoji-events, edit).

**`NewsPage.tsx` (rewrite to master-detail)** — keeps the `useInfiniteQuery(['news', saveGameId])`
(now typed `PageResponse<NewsFeedItem>`):
- `items: NewsFeedItem[]` = `data.pages.flatMap(p => p.content)`.
- Local `selectedId` state. An effect selects `items[0].id` (newest) once items load, or when the
  current `selectedId` is no longer present, and fires `markRead` for it if it is unread.
- **List** (a new `NewsList` presentational sub-component, or inline): compact rows — `newsIcon` +
  `newsTitle(payload)` + `formatDatePtBR(payload.date)`. Unread rows show the accent dot + bold title;
  read rows are muted; the selected row gets an accent left-border. Clicking a row selects it and
  (if unread) marks it read.
- **Detail** (below the list): `<AdvanceEventCard result={selected.payload} saveGameId competitionId
  managedClubId />` — reused unchanged.
- **"Marcar todas como lidas"** button in the page header, disabled when unread count is 0.
- Empty state unchanged.

**Read mutations — optimistic** (consistent with Caching Task 4's optimistic pattern):
- `markRead`: `onMutate` flips the item's `read → true` in the `['news', saveGameId]` infinite-query
  cache and decrements the `['newsUnreadCount', saveGameId]` cache; rollback both on error;
  `onSettled` invalidates `['newsUnreadCount', saveGameId]`.
- `markAllRead`: `onMutate` flips every cached item to read and sets `['newsUnreadCount']` to 0;
  rollback on error; `onSettled` invalidates the count.

**Nav badge — `Layout.tsx`:**
- New query `['newsUnreadCount', saveGameId]` → `getNewsUnreadCount` (staleTime ~30s, enabled when
  `saveGameId && token`).
- The nav-link objects gain an optional `badge?: number`; the "News" entry carries the unread count.
  Both the desktop bar and the mobile drawer wrap the label in
  `<Badge badgeContent={count} color="error" max={99}>` (badge hidden at 0 via `invisible`/`badgeContent={0}`).
- `advanceMutation.onSuccess` adds `invalidateQueries(['newsUnreadCount', saveGameId])` (new items grow
  the badge); NewsPage's read mutations shrink it.

## Data flow

1. **Advance** → SeasonAdvanceService/notifiers persist news item(s) with `read = false` → Layout's
   `['newsUnreadCount']` invalidated → badge grows. Navigation lands on `/news`.
2. **Land on News** → list loads → newest auto-selected → its `markRead` fires → detail card shows it,
   badge decrements by one.
3. **Click a headline** → select → detail card swaps → if unread, `markRead` → badge decrements.
4. **Marcar todas como lidas** → all items read, badge → 0.

## Error handling

- All endpoints go through `requireOwned` (404 `SAVE_GAME_NOT_FOUND` on foreign/absent save).
- `POST /news/{id}/read` on an unknown or cross-save item → 404 `NEWS_ITEM_NOT_FOUND`.
- Optimistic mutations roll back the cache on error; the badge reconciles via `onSettled`
  invalidation. Marking read is best-effort UX — a failure quietly reverts, no blocking toast needed.

## Testing

**Backend**
- `NewsService` / IT: `getFeed` returns wrappers carrying `id` + `read` + payload; `unreadCount`
  counts only unread; `markRead` flips one and is idempotent; `markRead` on a foreign item → 404;
  `markAllRead` clears all; new items are born unread; a career's existing items are read after the
  migration semantics (assert via count after inserting a pre-read item + a fresh unread item).
- Update `NewsFeedIT`: switch its `advanceController.getNews` references to the new `NewsController`;
  `page.content()` elements are now `NewsFeedItemResponse` (read `.payload().type()` where it read
  `.type()`); the payload round-trip test is unaffected (it deserializes repository payloads directly).

**Frontend**
- `newsMeta.test.ts`: `newsTitle` per type (incl. `round` null fallback and 0-count phrasings).
- `NewsPage.test.tsx` (rewrite): list renders derived titles; newest auto-selected and its detail card
  shown; clicking a row selects it, swaps the detail, and marks it read; "Marcar todas como lidas"
  clears unread state.
- `Layout` test: the News nav entry shows the unread badge with the count.

## Out of scope (YAGNI)

- Server-side title storage / search (titles are derived client-side).
- Per-alert read state (read is per news item).
- Marking read on mere page visit (only selection marks read, so the badge stays meaningful).
- Filtering/grouping news by type.

## File summary

**Backend (create):** `V32__news_read_flag.sql`, `NewsFeedItemResponse.java`,
`NewsUnreadCountResponse.java`, `NewsService.java`, `NewsController.java`.
**Backend (modify):** `NewsItem.java`, `NewsItemRepository.java`, `AdvanceController.java` (remove
news endpoint + fields), `ErrorCode.java`, `NewsFeedIT.java`.
**Frontend (create):** `api/news.ts`, `features/news/newsMeta.ts`, `features/news/newsMeta.test.ts`.
**Frontend (modify):** `api/types.ts`, `api/career.ts` (remove `getNews`), `features/news/NewsPage.tsx`,
`features/news/NewsPage.test.tsx`, `components/Layout.tsx`, `components/Layout.test.tsx`.
