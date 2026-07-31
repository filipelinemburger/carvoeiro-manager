# News Inbox (CM-style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the News page into a Championship-Manager-style master-detail inbox — a compact headline list on top, the selected item's full card below — with read/unread state and an unread-count badge on the News nav entry.

**Architecture:** Backend adds an `is_read` flag to `news_item` (existing rows backfilled read), a `NewsFeedItemResponse` wrapper carrying each item's `id`+`read`+payload, a new `NewsService` + `NewsController` (moving the feed endpoint off `AdvanceController`), and mark-read / mark-all-read / unread-count endpoints. Frontend adds `api/news.ts`, a `newsMeta` title/icon helper, rewrites `NewsPage` to master-detail with optimistic read mutations, and puts an unread `<Badge>` on the News nav.

**Tech Stack:** Java 21 / Spring Boot, Flyway, Jackson 3 (`tools.jackson.databind`), Postgres (Testcontainers), React 19, TanStack Query v5, MUI, Vitest.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-21-news-inbox-design.md`.
- JSON: Jackson 3 — the ObjectMapper type is `tools.jackson.databind.ObjectMapper` (NOT `com.fasterxml`).
- New news items are born **unread**; the migration backfills all **existing** items to **read**.
- The feed URL path is unchanged: `/api/v1/save-games/{saveGameId}/news` — only the serving controller class changes, so the frontend base path stays identical.
- `NewsItem.create(...)` must keep producing unread items with no change at its ~6 call sites (SeasonAdvanceService MATCH/TRANSFER/FINISHED/TRAINING_REPORT, `persistPreContractNews`, `WatchlistNotifier`).
- `ErrorCode` entries are alphabetically ordered; add `NEWS_ITEM_NOT_FOUND` between `MATCH_OVER` and `NO_GK_FOUND`.
- pt-BR UI copy.
- Backend tests: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` from `backend/`.
- Frontend typecheck: `npx tsc -b` from `frontend/` (`tsc --noEmit` is a no-op in this repo). Frontend tests: `npx vitest run <path>` from `frontend/`.
- Local commits on `main` are pre-authorized for this project (no push).

## File Structure

**Backend — create:**
- `backend/src/main/resources/db/migration/V32__news_read_flag.sql` — add `is_read`, backfill existing rows read.
- `backend/src/main/java/br/com/carvoeiromanager/season/NewsFeedItemResponse.java` — feed wrapper record.
- `backend/src/main/java/br/com/carvoeiromanager/season/NewsUnreadCountResponse.java` — unread-count record.
- `backend/src/main/java/br/com/carvoeiromanager/season/NewsService.java` — feed/read logic behind `requireOwned`.
- `backend/src/main/java/br/com/carvoeiromanager/season/NewsController.java` — the four news endpoints.
- `backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java` — read-flag repo/service/controller tests.

**Backend — modify:**
- `NewsItem.java` — `read` field + `isRead()` + `markRead()`.
- `NewsItemRepository.java` — `countBySaveGameIdAndReadFalse`, `markAllRead`.
- `AdvanceController.java` — remove the news endpoint + now-unused deps.
- `ErrorCode.java` — `NEWS_ITEM_NOT_FOUND`.
- `NewsFeedIT.java` — retarget the feed calls to `NewsController`, retype content to `NewsFeedItemResponse`.

**Frontend — create:**
- `frontend/src/api/news.ts` — `getNews` (moved) + `getNewsUnreadCount` + `markNewsRead` + `markAllNewsRead`.
- `frontend/src/features/news/newsMeta.tsx` — `newsTitle` + `newsIcon`.
- `frontend/src/features/news/newsMeta.test.ts` — `newsTitle` unit tests.

**Frontend — modify:**
- `api/types.ts` — `NewsFeedItem` type.
- `api/career.ts` — remove `getNews`.
- `features/news/NewsPage.tsx` — master-detail rewrite.
- `features/news/NewsPage.test.tsx` — rewrite for master-detail.
- `components/Layout.tsx` — unread-count query + News badge + advance invalidation.
- `components/Layout.test.tsx` — mock `api/news`, assert the badge.

---

## Task 1: Backend data layer — read flag

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__news_read_flag.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/NewsItem.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/NewsItemRepository.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java` (create)

**Interfaces:**
- Produces: `NewsItem.isRead()`, `NewsItem.markRead()`; `NewsItemRepository.countBySaveGameIdAndReadFalse(UUID)`, `NewsItemRepository.markAllRead(UUID)`.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V32__news_read_flag.sql`:

```sql
ALTER TABLE news_item ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_item SET is_read = TRUE;
```

- [ ] **Step 2: Add the read flag to the entity**

In `NewsItem.java`, add the field after `createdAt` (line 42):

```java
    @Column(name = "is_read", nullable = false)
    private boolean read;
```

Add the getter + mutator alongside the other getters (after `getCreatedAt()`, line 69):

```java
    public boolean isRead() { return read; }

    public void markRead() { this.read = true; }
```

Leave the private constructor and `create(...)` unchanged — `read` defaults to `false`, so every newly created item is unread.

- [ ] **Step 3: Add the repository methods**

In `NewsItemRepository.java`, add imports and methods:

```java
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

```java
    int countBySaveGameIdAndReadFalse(UUID saveGameId);

    @Modifying
    @Transactional
    @Query("update NewsItem n set n.read = true where n.saveGameId = :saveGameId and n.read = false")
    int markAllRead(@Param("saveGameId") UUID saveGameId);
```

(`@Transactional` is already imported in this file.)

- [ ] **Step 4: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NewsReadIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, UUID saveGameId) { }

    /** Fresh career with any incidental news (e.g. initial AI bids) cleared, so tests
     *  assert only on the items they create. */
    private Career createCareer() {
        User user = User.create("news_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        newsItemRepository.deleteBySaveGameId(save.id());
        return new Career(user.getId(), save.id());
    }

    @Test
    void newlyCreatedItemsAreUnreadAndCounted() {
        Career c = createCareer();
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 1, null, "MATCH", "{}"));
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 2, null, "MATCH", "{}"));

        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isEqualTo(2);
    }

    @Test
    void markAllReadClearsTheUnreadCount() {
        Career c = createCareer();
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 1, null, "MATCH", "{}"));
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 2, null, "MATCH", "{}"));

        int updated = newsItemRepository.markAllRead(c.saveGameId());

        assertThat(updated).isEqualTo(2);
        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isZero();
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.NewsReadIT' --rerun` (from `backend/`)
Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add -- backend/src/main/resources/db/migration/V32__news_read_flag.sql \
  backend/src/main/java/br/com/carvoeiromanager/season/NewsItem.java \
  backend/src/main/java/br/com/carvoeiromanager/season/NewsItemRepository.java \
  backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java
git commit -m "feat(news): add read flag to news items"
```

---

## Task 2: Backend DTOs, ErrorCode, and NewsService

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/NewsFeedItemResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/NewsUnreadCountResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/NewsService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java` (extend)

**Interfaces:**
- Consumes: `NewsItem.isRead()`, `NewsItemRepository.countBySaveGameIdAndReadFalse`, `NewsItemRepository.markAllRead` (Task 1).
- Produces: `NewsFeedItemResponse(UUID id, boolean read, AdvanceResponse payload)`; `NewsUnreadCountResponse(int count)`; `NewsService.getFeed(UUID,UUID,int,int) → PageResponse<NewsFeedItemResponse>`, `unreadCount(UUID,UUID) → int`, `markRead(UUID,UUID,UUID)`, `markAllRead(UUID,UUID)`.

- [ ] **Step 1: Add the ErrorCode**

In `ErrorCode.java`, insert between `MATCH_OVER(...)` and `NO_GK_FOUND(...)`:

```java
    NEWS_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
```

- [ ] **Step 2: Create the DTO records**

Create `NewsFeedItemResponse.java`:

```java
package br.com.carvoeiromanager.season;

import java.util.UUID;

/** One entry in the news feed: the item's id and read flag plus its full deserialized payload. */
public record NewsFeedItemResponse(UUID id, boolean read, AdvanceResponse payload) { }
```

Create `NewsUnreadCountResponse.java`:

```java
package br.com.carvoeiromanager.season;

/** Unread-item count for a save game, for the News nav badge. */
public record NewsUnreadCountResponse(int count) { }
```

- [ ] **Step 3: Create the service**

Create `NewsService.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Read-side of the persisted news feed. Loads items newest-first, deserializes each stored
 * {@link AdvanceResponse} payload, and manages per-item read state. All entry points require
 * the save game to be owned by the caller.
 */
@Service
public class NewsService {

    private final SaveGameService saveGameService;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public NewsService(SaveGameService saveGameService,
                       NewsItemRepository newsItemRepository,
                       ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public PageResponse<NewsFeedItemResponse> getFeed(UUID saveGameId, UUID userId, int page, int size) {
        saveGameService.requireOwned(saveGameId, userId);
        List<NewsFeedItemResponse> responses = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId)
                .stream()
                .map(item -> new NewsFeedItemResponse(item.getId(), item.isRead(), deserialize(item)))
                .toList();
        return PageResponse.of(responses, page, size);
    }

    public int unreadCount(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return newsItemRepository.countBySaveGameIdAndReadFalse(saveGameId);
    }

    @Transactional
    public void markRead(UUID saveGameId, UUID userId, UUID newsItemId) {
        saveGameService.requireOwned(saveGameId, userId);
        NewsItem item = newsItemRepository.findById(newsItemId)
                .filter(n -> n.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.NEWS_ITEM_NOT_FOUND, "News item not found"));
        item.markRead();
        newsItemRepository.save(item);
    }

    @Transactional
    public void markAllRead(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        newsItemRepository.markAllRead(saveGameId);
    }

    private AdvanceResponse deserialize(NewsItem item) {
        try {
            return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize news item " + item.getId(), e);
        }
    }
}
```

- [ ] **Step 4: Write the failing tests**

In `NewsReadIT.java`, add imports:

```java
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.PageResponse;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Add the service autowire + a valid-payload helper + tests:

```java
    @Autowired NewsService newsService;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    /** Persists a MATCH item carrying a real (deserializable) AdvanceResponse payload. */
    private UUID persistMatch(UUID saveGameId, int seq, boolean read) {
        AdvanceResponse minimal = new AdvanceResponse("MATCH", null, null, null, List.of(), List.of(),
                List.of(), List.of(), null, 0, false, null, null, null, null, null);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(minimal);
        } catch (Exception e) {
            throw new AssertionError("Failed to serialize test payload", e);
        }
        NewsItem item = NewsItem.create(saveGameId, seq, null, "MATCH", payload);
        if (read) {
            item.markRead();
        }
        newsItemRepository.save(item);
        return item.getId();
    }

    @Test
    void getFeedReturnsWrappersCarryingIdReadFlagAndPayload() {
        Career c = createCareer();
        UUID unreadId = persistMatch(c.saveGameId(), 1, false);
        UUID readId = persistMatch(c.saveGameId(), 2, true);

        PageResponse<NewsFeedItemResponse> feed = newsService.getFeed(c.saveGameId(), c.userId(), 1, 10);

        assertThat(feed.content()).hasSize(2);
        // Newest-first: seq 2 (read) at index 0, seq 1 (unread) at index 1.
        assertThat(feed.content().get(0).id()).isEqualTo(readId);
        assertThat(feed.content().get(0).read()).isTrue();
        assertThat(feed.content().get(1).id()).isEqualTo(unreadId);
        assertThat(feed.content().get(1).read()).isFalse();
        assertThat(feed.content().get(0).payload().type()).isEqualTo("MATCH");
    }

    @Test
    void unreadCountCountsOnlyUnreadItems() {
        Career c = createCareer();
        persistMatch(c.saveGameId(), 1, false);
        persistMatch(c.saveGameId(), 2, false);
        persistMatch(c.saveGameId(), 3, true);

        assertThat(newsService.unreadCount(c.saveGameId(), c.userId())).isEqualTo(2);
    }

    @Test
    void markReadFlipsOneItemAndIsIdempotent() {
        Career c = createCareer();
        UUID id = persistMatch(c.saveGameId(), 1, false);

        newsService.markRead(c.saveGameId(), c.userId(), id);
        newsService.markRead(c.saveGameId(), c.userId(), id); // idempotent

        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isZero();
    }

    @Test
    void markReadOnAForeignItemThrowsNotFound() {
        Career owner = createCareer();
        Career other = createCareer();
        UUID foreignItem = persistMatch(other.saveGameId(), 1, false);

        assertThatThrownBy(() -> newsService.markRead(owner.saveGameId(), owner.userId(), foreignItem))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NEWS_ITEM_NOT_FOUND));
    }

    @Test
    void markAllReadViaServiceClearsUnread() {
        Career c = createCareer();
        persistMatch(c.saveGameId(), 1, false);
        persistMatch(c.saveGameId(), 2, false);

        newsService.markAllRead(c.saveGameId(), c.userId());

        assertThat(newsService.unreadCount(c.saveGameId(), c.userId())).isZero();
    }
```

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.NewsReadIT' --rerun` (from `backend/`)
Expected: BUILD SUCCESSFUL, all 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add -- backend/src/main/java/br/com/carvoeiromanager/season/NewsFeedItemResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/season/NewsUnreadCountResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/season/NewsService.java \
  backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java \
  backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java
git commit -m "feat(news): NewsService with feed wrapper, unread count, and read mutations"
```

---

## Task 3: Backend NewsController + move the feed endpoint off AdvanceController

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/NewsController.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java` (extend), `NewsFeedIT.java`

**Interfaces:**
- Consumes: `NewsService` (Task 2), `NewsUnreadCountResponse`, `NewsFeedItemResponse`.
- Produces: `NewsController.getNews(UUID,UUID,int,int)`, `unreadCount(UUID,UUID)`, `markRead(UUID,UUID,UUID)`, `markAllRead(UUID,UUID)`.

- [ ] **Step 1: Create the controller**

Create `NewsController.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Persisted news feed for a save game: paginated newest-first, plus per-item read state
 * and an unread count for the News nav badge.
 */
@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/news")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<NewsFeedItemResponse>> getNews(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(newsService.getFeed(saveGameId, userId, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<NewsUnreadCountResponse> unreadCount(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(new NewsUnreadCountResponse(newsService.unreadCount(saveGameId, userId)));
    }

    @PostMapping("/{newsItemId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID saveGameId,
            @PathVariable UUID newsItemId,
            @CurrentUser UUID userId) {
        newsService.markRead(saveGameId, userId, newsItemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        newsService.markAllRead(saveGameId, userId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: Strip the feed endpoint out of AdvanceController**

Replace the entire body of `AdvanceController.java` with (removes `getNews` and the now-unused `newsItemRepository`, `saveGameService`, `objectMapper` deps + imports):

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Date-driven season advance endpoint. Each POST processes exactly one calendar
 * event (a MATCH round or a TRANSFER bid window stop) and returns its outcome.
 */
@RestController
@RequestMapping("/api/v1/save-games")
public class AdvanceController {

    private final SeasonAdvanceService seasonAdvanceService;
    private final AdvancePreviewService advancePreviewService;

    public AdvanceController(SeasonAdvanceService seasonAdvanceService,
                             AdvancePreviewService advancePreviewService) {
        this.seasonAdvanceService = seasonAdvanceService;
        this.advancePreviewService = advancePreviewService;
    }

    @PostMapping("/{saveGameId}/advance")
    public ResponseEntity<AdvanceResponse> advance(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(seasonAdvanceService.advance(saveGameId, userId));
    }

    @GetMapping("/{saveGameId}/advance-preview")
    public ResponseEntity<AdvancePreviewResponse> advancePreview(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(advancePreviewService.preview(saveGameId, userId));
    }
}
```

- [ ] **Step 3: Retarget NewsFeedIT to the new controller**

In `NewsFeedIT.java`:

Replace the autowire (line 33):

```java
    @Autowired NewsController newsController;
```
(remove `@Autowired AdvanceController advanceController;`)

In the three pagination tests (`newsFeedDefaultsToPageOneSizeTen`, `newsFeedSecondPageReturnsTheRemainingOlderItems`, `newsFeedOutOfRangePageReturnsEmptyContentWithCorrectTotals`), change the two references per test from:

```java
        PageResponse<AdvanceResponse> page = advanceController.getNews(c.id(), c.userId(), 1, 10).getBody();
```
to:

```java
        PageResponse<NewsFeedItemResponse> page = newsController.getNews(c.id(), c.userId(), 1, 10).getBody();
```
(adjust the `page`/`size` arguments per test — 1,10 / 2,10 / 5,10 as they already are). The `.content()`, `.page()`, `.size()`, `.totalElements()`, `.totalPages()` assertions are unchanged — none read a content element's fields. The payload round-trip test (`newsEndpointDeserializesPayloadsCorrectly`) uses the repository directly and is unchanged.

- [ ] **Step 4: Add a controller wiring test to NewsReadIT**

In `NewsReadIT.java`, add the autowire + test:

```java
    @Autowired NewsController newsController;
```

```java
    @Test
    void newsControllerExposesUnreadCountAndMarkRead() {
        Career c = createCareer();
        UUID id = persistMatch(c.saveGameId(), 1, false);

        assertThat(newsController.unreadCount(c.saveGameId(), c.userId()).getBody().count()).isEqualTo(1);

        newsController.markRead(c.saveGameId(), id, c.userId());

        assertThat(newsController.unreadCount(c.saveGameId(), c.userId()).getBody().count()).isZero();
    }
```

Note the controller method signature order: `markRead(saveGameId, newsItemId, userId)`.

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.NewsReadIT' --tests 'br.com.carvoeiromanager.season.NewsFeedIT' --rerun` (from `backend/`)
Expected: BUILD SUCCESSFUL, all NewsReadIT + NewsFeedIT tests pass.

- [ ] **Step 6: Commit**

```bash
git add -- backend/src/main/java/br/com/carvoeiromanager/season/NewsController.java \
  backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java \
  backend/src/test/java/br/com/carvoeiromanager/season/NewsFeedIT.java \
  backend/src/test/java/br/com/carvoeiromanager/season/NewsReadIT.java
git commit -m "refactor(news): dedicated NewsController; move feed off AdvanceController"
```

---

## Task 4: Frontend — newsMeta helper, NewsFeedItem type, api/news.ts

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/news.ts`
- Create: `frontend/src/features/news/newsMeta.tsx`
- Test: `frontend/src/features/news/newsMeta.test.ts` (create)

**Interfaces:**
- Produces: `NewsFeedItem` type; `newsTitle(payload) → string`, `newsIcon(type) → ReactNode`; `getNewsUnreadCount`, `markNewsRead`, `markAllNewsRead`.

- [ ] **Step 1: Add the type**

In `api/types.ts`, immediately after the `AdvanceResponse` type block, add:

```ts
/** One news-feed entry: the persisted item's id + read flag plus its full payload. */
export type NewsFeedItem = {
  id: string
  read: boolean
  payload: AdvanceResponse
}
```

- [ ] **Step 2: Create api/news.ts (new read functions only)**

Create `frontend/src/api/news.ts`. `getNews` still lives in `career.ts` at this point; it moves here in Task 5.

```ts
import { apiGet, apiPost } from './client'

// Unread news-item count for the nav badge.
export function getNewsUnreadCount(saveGameId: string, token: string): Promise<number> {
  return apiGet<{ count: number }>(`/save-games/${saveGameId}/news/unread-count`, token).then(r => r.count)
}

// Mark one news item read (idempotent server-side).
export function markNewsRead(saveGameId: string, newsItemId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/news/${newsItemId}/read`, {}, token)
}

// Mark every unread news item read.
export function markAllNewsRead(saveGameId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/news/read-all`, {}, token)
}
```

- [ ] **Step 3: Create the newsMeta helper**

Create `frontend/src/features/news/newsMeta.tsx`:

```tsx
import type { ReactNode } from 'react'
import SportsSoccerIcon from '@mui/icons-material/SportsSoccer'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import VisibilityIcon from '@mui/icons-material/Visibility'
import FitnessCenterIcon from '@mui/icons-material/FitnessCenter'
import EmojiEventsIcon from '@mui/icons-material/EmojiEvents'
import EditIcon from '@mui/icons-material/Edit'
import ArticleIcon from '@mui/icons-material/Article'
import type { AdvanceResponse } from '../../api/types'

/** Short headline for a news list row, derived from the payload type. */
export function newsTitle(payload: AdvanceResponse): string {
  switch (payload.type) {
    case 'MATCH':
      return payload.round !== null ? `Rodada ${payload.round + 1} — resultados` : 'Resultados'
    case 'TRANSFER':
      return `Janela de transferências — ${payload.bidsReceived} proposta(s)`
    case 'WATCHLIST':
      return `Alertas — ${payload.playerAlerts?.length ?? 0} observado(s)`
    case 'PRECONTRACT':
      return 'Pré-contratos'
    case 'TRAINING_REPORT':
      return 'Relatório de treino'
    case 'FINISHED':
      return 'Temporada encerrada'
    default:
      return 'Notícia'
  }
}

/** Leading icon for a news list row. */
export function newsIcon(type: AdvanceResponse['type']): ReactNode {
  switch (type) {
    case 'MATCH': return <SportsSoccerIcon fontSize="small" />
    case 'TRANSFER': return <SwapHorizIcon fontSize="small" />
    case 'WATCHLIST': return <VisibilityIcon fontSize="small" />
    case 'TRAINING_REPORT': return <FitnessCenterIcon fontSize="small" />
    case 'FINISHED': return <EmojiEventsIcon fontSize="small" />
    case 'PRECONTRACT': return <EditIcon fontSize="small" />
    default: return <ArticleIcon fontSize="small" />
  }
}
```

- [ ] **Step 4: Write the failing test**

Create `frontend/src/features/news/newsMeta.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { newsTitle } from './newsMeta'
import type { AdvanceResponse } from '../../api/types'

function payload(overrides: Partial<AdvanceResponse>): AdvanceResponse {
  return {
    type: 'MATCH', date: null, competitionName: null, round: null,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: 0, seasonFinished: false, ...overrides,
  }
}

describe('newsTitle', () => {
  it('MATCH shows the 1-based round', () => {
    expect(newsTitle(payload({ type: 'MATCH', round: 11 }))).toBe('Rodada 12 — resultados')
  })
  it('MATCH without a round falls back', () => {
    expect(newsTitle(payload({ type: 'MATCH', round: null }))).toBe('Resultados')
  })
  it('TRANSFER shows the bid count', () => {
    expect(newsTitle(payload({ type: 'TRANSFER', bidsReceived: 3 }))).toBe('Janela de transferências — 3 proposta(s)')
  })
  it('WATCHLIST shows the alert count, 0 when absent', () => {
    expect(newsTitle(payload({ type: 'WATCHLIST' }))).toBe('Alertas — 0 observado(s)')
    expect(newsTitle(payload({ type: 'WATCHLIST', playerAlerts: [
      { kind: 'OFFER_RECEIVED', playerName: 'X', clubName: 'Avaí', detail: null },
    ] }))).toBe('Alertas — 1 observado(s)')
  })
  it('fixed titles for TRAINING_REPORT, FINISHED, PRECONTRACT', () => {
    expect(newsTitle(payload({ type: 'TRAINING_REPORT' }))).toBe('Relatório de treino')
    expect(newsTitle(payload({ type: 'FINISHED' }))).toBe('Temporada encerrada')
    expect(newsTitle(payload({ type: 'PRECONTRACT' }))).toBe('Pré-contratos')
  })
})
```

(`PlayerAlert` is `{ kind, playerName, clubName: string, detail: string | null }` — only the array length matters here.)

- [ ] **Step 5: Run the test + typecheck**

Run: `npx vitest run src/features/news/newsMeta.test.ts` (from `frontend/`)
Expected: all tests pass.
Run: `npx tsc -b` (from `frontend/`)
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add -- frontend/src/api/types.ts frontend/src/api/news.ts \
  frontend/src/features/news/newsMeta.tsx frontend/src/features/news/newsMeta.test.ts
git commit -m "feat(news): NewsFeedItem type, api/news module, newsMeta title/icon helper"
```

---

## Task 5: Frontend — master-detail NewsPage rewrite

**Files:**
- Modify: `frontend/src/api/career.ts` (remove `getNews`)
- Modify: `frontend/src/api/news.ts` (add `getNews`)
- Modify: `frontend/src/features/news/NewsPage.tsx`
- Test: `frontend/src/features/news/NewsPage.test.tsx` (rewrite)

**Interfaces:**
- Consumes: `getNews` (moved), `markNewsRead`, `markAllNewsRead` (Task 4); `newsTitle`, `newsIcon` (Task 4); `NewsFeedItem` (Task 4); `AdvanceEventCard`, `formatDatePtBR`.

- [ ] **Step 1: Move getNews into api/news.ts**

In `api/news.ts`, update the imports and add `getNews`:

```ts
import { apiGet, apiPost } from './client'
import type { NewsFeedItem, PageResponse } from './types'

// Persisted news feed, paginated newest-first; page is 1-based.
export function getNews(
  saveGameId: string, page: number, size: number, token: string,
): Promise<PageResponse<NewsFeedItem>> {
  return apiGet<PageResponse<NewsFeedItem>>(`/save-games/${saveGameId}/news?page=${page}&size=${size}`, token)
}
```

In `api/career.ts`, delete the `getNews` function and its leading comment (the `// Persisted news feed, paginated newest-first; page is 1-based.` block). Leave the `PageResponse` and `AdvanceResponse` imports — both are still used by other functions in the file.

- [ ] **Step 2: Write the failing test (rewrite NewsPage.test.tsx)**

Replace `frontend/src/features/news/NewsPage.test.tsx` entirely:

```tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NewsPage } from './NewsPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { AdvanceResponse, NewsFeedItem, SaveGameResponse } from '../../api/types'

vi.mock('../../api/career', () => ({ getSaveGame: vi.fn() }))
vi.mock('../../api/news', () => ({
  getNews: vi.fn(),
  markNewsRead: vi.fn(),
  markAllNewsRead: vi.fn(),
}))

import { getSaveGame } from '../../api/career'
import { getNews, markNewsRead, markAllNewsRead } from '../../api/news'

const SAVE_GAME: SaveGameResponse = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'comp-1', competitionName: 'Série B',
  managedClubId: 'club-1', managedClubName: 'Criciúma EC', currentRound: 12, totalRounds: 38,
}

function match(round: number): AdvanceResponse {
  return {
    type: 'MATCH', date: '2026-07-21', competitionName: 'Série B', round,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: 0, seasonFinished: false,
  }
}
function transfer(bids: number): AdvanceResponse {
  return {
    type: 'TRANSFER', date: '2026-07-18', competitionName: null, round: null,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: bids, seasonFinished: false,
  }
}

const ITEMS: NewsFeedItem[] = [
  { id: 'n1', read: false, payload: match(11) },
  { id: 'n2', read: false, payload: transfer(3) },
]

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/news?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <NewsPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('NewsPage (master-detail)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
    vi.mocked(getNews).mockResolvedValue({ content: ITEMS, page: 1, size: 10, totalElements: 2, totalPages: 1 })
    vi.mocked(markNewsRead).mockResolvedValue()
    vi.mocked(markAllNewsRead).mockResolvedValue()
  })

  it('lists derived headlines and auto-selects the newest, showing its detail card + marking it read', async () => {
    renderPage()
    // Both headlines appear in the list.
    await waitFor(() => expect(screen.getByText('Rodada 12 — resultados')).toBeInTheDocument())
    expect(screen.getByText('Janela de transferências — 3 proposta(s)')).toBeInTheDocument()
    // Newest (MATCH) is auto-selected: its detail card header shows the round label.
    await waitFor(() => expect(screen.getByText(/Série B · Rodada 12/)).toBeInTheDocument())
    // Newest was unread → marked read on landing.
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n1', 'test-token'))
  })

  it('selecting a headline swaps the detail card and marks that item read', async () => {
    renderPage()
    const transferRow = await screen.findByText('Janela de transferências — 3 proposta(s)')
    fireEvent.click(transferRow)
    // TRANSFER detail card header appears.
    await waitFor(() => expect(screen.getByText('Janela de Transferências')).toBeInTheDocument())
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n2', 'test-token'))
  })

  it('"Marcar todas como lidas" marks all read', async () => {
    renderPage()
    const button = await screen.findByRole('button', { name: /marcar todas como lidas/i })
    fireEvent.click(button)
    await waitFor(() => expect(markAllNewsRead).toHaveBeenCalledWith('sg-1', 'test-token'))
  })
})
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `npx vitest run src/features/news/NewsPage.test.tsx` (from `frontend/`)
Expected: FAIL (NewsPage still renders the old full-card list; no headlines / mark-read wiring).

- [ ] **Step 4: Rewrite NewsPage.tsx**

Replace `frontend/src/features/news/NewsPage.tsx` entirely:

```tsx
import { useEffect, useState } from 'react'
import { useQuery, useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Box, Button, List, ListItemButton, ListItemIcon, ListItemText, Stack, Typography } from '@mui/material'
import { getSaveGame } from '../../api/career'
import { getNews, markNewsRead, markAllNewsRead } from '../../api/news'
import { useAuth } from '../../auth/AuthContext'
import { AdvanceEventCard, formatDatePtBR } from '../match/AdvanceEventCard'
import { newsTitle, newsIcon } from './newsMeta'
import type { NewsFeedItem, PageResponse, SaveGameResponse } from '../../api/types'

const NEWS_PAGE_SIZE = 10

/**
 * CM-style news inbox: a compact headline list on top, the selected item's full
 * AdvanceEventCard below. The newest item is auto-selected (and marked read) on landing,
 * so the just-played result shows immediately after Avançar. Read state is optimistic.
 */
export function NewsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const queryClient = useQueryClient()

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const {
    data, isLoading, error, fetchNextPage, hasNextPage, isFetchingNextPage,
  } = useInfiniteQuery<PageResponse<NewsFeedItem>>({
    queryKey: ['news', saveGameId],
    queryFn: ({ pageParam }) => getNews(saveGameId, pageParam as number, NEWS_PAGE_SIZE, token ?? ''),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => (lastPage.page < lastPage.totalPages ? lastPage.page + 1 : undefined),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const markReadMutation = useMutation({
    mutationFn: (newsItemId: string) => markNewsRead(saveGameId, newsItemId, token ?? ''),
    onMutate: async (newsItemId: string) => {
      await queryClient.cancelQueries({ queryKey: ['news', saveGameId] })
      const prevNews = queryClient.getQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId])
      const prevCount = queryClient.getQueryData<number>(['newsUnreadCount', saveGameId])
      let wasUnread = false
      queryClient.setQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId], (old) => {
        if (!old) return old
        return {
          ...old,
          pages: old.pages.map(pg => ({
            ...pg,
            content: pg.content.map(it => {
              if (it.id === newsItemId && !it.read) { wasUnread = true; return { ...it, read: true } }
              return it
            }),
          })),
        }
      })
      if (wasUnread && typeof prevCount === 'number') {
        queryClient.setQueryData<number>(['newsUnreadCount', saveGameId], Math.max(0, prevCount - 1))
      }
      return { prevNews, prevCount }
    },
    onError: (_e, _id, ctx) => {
      if (ctx?.prevNews) queryClient.setQueryData(['news', saveGameId], ctx.prevNews)
      if (ctx?.prevCount !== undefined) queryClient.setQueryData(['newsUnreadCount', saveGameId], ctx.prevCount)
    },
    onSettled: () => { void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] }) },
  })

  const markAllReadMutation = useMutation({
    mutationFn: () => markAllNewsRead(saveGameId, token ?? ''),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['news', saveGameId] })
      const prevNews = queryClient.getQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId])
      const prevCount = queryClient.getQueryData<number>(['newsUnreadCount', saveGameId])
      queryClient.setQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId], (old) => {
        if (!old) return old
        return {
          ...old,
          pages: old.pages.map(pg => ({ ...pg, content: pg.content.map(it => (it.read ? it : { ...it, read: true })) })),
        }
      })
      queryClient.setQueryData<number>(['newsUnreadCount', saveGameId], 0)
      return { prevNews, prevCount }
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prevNews) queryClient.setQueryData(['news', saveGameId], ctx.prevNews)
      if (ctx?.prevCount !== undefined) queryClient.setQueryData(['newsUnreadCount', saveGameId], ctx.prevCount)
    },
    onSettled: () => { void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] }) },
  })

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const items = (data?.pages ?? []).flatMap(p => p.content)

  // Auto-select the newest item once loaded, and re-select if the current selection drops
  // out of the list. Marks the newly-selected item read if it was unread.
  useEffect(() => {
    if (items.length === 0) {
      if (selectedId !== null) setSelectedId(null)
      return
    }
    const stillPresent = selectedId !== null && items.some(it => it.id === selectedId)
    if (!stillPresent) {
      const newest = items[0]
      setSelectedId(newest.id)
      if (!newest.read) markReadMutation.mutate(newest.id)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, selectedId])

  if (isLoading) return <Typography>Carregando notícias…</Typography>
  if (error) return <Typography color="error">Erro ao carregar notícias.</Typography>

  const competitionId = saveGame?.competitionId ?? ''
  const managedClubId = saveGame?.managedClubId ?? ''
  const selected = items.find(it => it.id === selectedId) ?? items[0] ?? null
  const loadedUnread = items.filter(it => !it.read).length

  const handleSelect = (item: NewsFeedItem) => {
    setSelectedId(item.id)
    if (!item.read) markReadMutation.mutate(item.id)
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5" component="h2" sx={{ fontWeight: 700, m: 0 }}>
          Notícias
        </Typography>
        <Button
          size="small"
          variant="outlined"
          disabled={loadedUnread === 0 || markAllReadMutation.isPending}
          onClick={() => markAllReadMutation.mutate()}
        >
          Marcar todas como lidas
        </Button>
      </Stack>

      {items.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Nenhuma notícia ainda. Clique em <Box component="strong">Avançar</Box> para jogar a próxima rodada.
        </Typography>
      ) : (
        <>
          <List sx={{ border: '1px solid rgba(255,255,255,0.12)', borderRadius: 2, p: 0, mb: 2 }}>
            {items.map(item => {
              const isSelected = item.id === selected?.id
              return (
                <ListItemButton
                  key={item.id}
                  selected={isSelected}
                  onClick={() => handleSelect(item)}
                  sx={{
                    borderLeft: isSelected ? '3px solid var(--cec-yellow)' : '3px solid transparent',
                    opacity: item.read ? 0.7 : 1,
                  }}
                >
                  <ListItemIcon sx={{ minWidth: 36, color: 'inherit' }}>
                    {newsIcon(item.payload.type)}
                  </ListItemIcon>
                  <ListItemText
                    primary={
                      <Typography component="span" sx={{ fontWeight: item.read ? 400 : 700 }}>
                        {newsTitle(item.payload)}
                      </Typography>
                    }
                    secondary={formatDatePtBR(item.payload.date)}
                  />
                  {!item.read && (
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'var(--cec-yellow)', ml: 1 }} />
                  )}
                </ListItemButton>
              )
            })}
          </List>

          {hasNextPage && (
            <Box sx={{ display: 'flex', justifyContent: 'center', mb: 2 }}>
              <Button variant="text" disabled={isFetchingNextPage} onClick={() => fetchNextPage()}>
                {isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
              </Button>
            </Box>
          )}

          {selected && (
            <AdvanceEventCard
              result={selected.payload}
              saveGameId={saveGameId}
              competitionId={competitionId}
              managedClubId={managedClubId}
            />
          )}
        </>
      )}
    </Box>
  )
}
```

- [ ] **Step 5: Run the test + typecheck**

Run: `npx vitest run src/features/news/NewsPage.test.tsx` (from `frontend/`)
Expected: all 3 tests pass.
Run: `npx tsc -b` (from `frontend/`)
Expected: no errors (confirms `getNews` was cleanly removed from `career.ts` and nothing else imported it).

- [ ] **Step 6: Commit**

```bash
git add -- frontend/src/api/news.ts frontend/src/api/career.ts \
  frontend/src/features/news/NewsPage.tsx frontend/src/features/news/NewsPage.test.tsx
git commit -m "feat(news): master-detail inbox with optimistic read state"
```

---

## Task 6: Frontend — News nav badge

**Files:**
- Modify: `frontend/src/components/Layout.tsx`
- Test: `frontend/src/components/Layout.test.tsx`

**Interfaces:**
- Consumes: `getNewsUnreadCount` (Task 4).

- [ ] **Step 1: Write the failing test**

In `Layout.test.tsx`, add the news-module mock after the existing mocks (near line 31):

```ts
vi.mock('../api/news', () => ({ getNewsUnreadCount: vi.fn() }))
```

Add the import with the other api imports (near line 8):

```ts
import { getNewsUnreadCount } from '../api/news'
```

In the `beforeEach`, add a default resolve (matching how the other mocks are seeded):

```ts
    vi.mocked(getNewsUnreadCount).mockResolvedValue(3)
```

Add a test (place it beside the other rendering tests):

```ts
  it('shows the unread news badge on the News nav', async () => {
    renderLayout()
    // The News link renders with an unread badge showing the server count.
    expect(await screen.findByText('3')).toBeInTheDocument()
  })
```

(Use the file's existing render helper — match its name, e.g. `renderLayout()` / `renderWithProviders()`. If the helper needs a saved game to show nav links, the existing tests already seed `getSaveGame`/preview in `beforeEach`; reuse that setup.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/components/Layout.test.tsx` (from `frontend/`)
Expected: FAIL — no badge/`3` yet.

- [ ] **Step 3: Add the badge to Layout**

In `Layout.tsx`:

Add `Badge` to the MUI import (line 4-7 block):

```ts
import {
  AppBar, Toolbar, Box, Button, IconButton, Typography, Menu, MenuItem,
  Drawer, List, ListItemButton, ListItemText, Alert, Snackbar, Container, Paper, Divider, Badge,
} from '@mui/material'
```

Add the api import (near line 13-15):

```ts
import { getNewsUnreadCount } from '../api/news'
```

Add the unread-count query (after the `liveMatch` query, ~line 64):

```ts
  const { data: newsUnread = 0 } = useQuery<number>({
    queryKey: ['newsUnreadCount', saveGameId],
    queryFn: () => getNewsUnreadCount(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 30_000,
  })
```

In `advanceMutation.onSuccess`, add the invalidation (alongside the existing `['news', saveGameId]` invalidation, ~line 88):

```ts
      void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] })
```

Give the nav-link objects an optional `badge`. Change the News entry in `saveLinks` (line 204):

```ts
        { to: `/news?saveGameId=${sgId}`, label: 'News', badge: newsUnread },
```

Add a small inline label renderer just before the `return (` (after `barLinkSx`, ~line 223). It flows the badge inline after the label (static position) so it never clips the bar pill or the drawer row:

```tsx
  const renderNavLabel = (link: { label: string; badge?: number }) =>
    link.badge ? (
      <Badge
        color="error"
        badgeContent={link.badge}
        max={99}
        sx={{ '& .MuiBadge-badge': { position: 'static', transform: 'none', ml: 1 } }}
      >
        <Box component="span">{link.label}</Box>
      </Badge>
    ) : (
      link.label
    )
```

Use it in the desktop nav (replace `{link.label}` at line 249):

```tsx
            <Box key={link.to} component={NavLink} to={link.to} sx={barLinkSx}>
              {renderNavLabel(link)}
            </Box>
```

And in the mobile drawer (replace the `ListItemText` at line 318):

```tsx
              <ListItemButton key={link.to} component={NavLink} to={link.to}>
                <ListItemText primary={renderNavLabel(link)} />
              </ListItemButton>
```

The `navLinks` array typing (`{ to: string; label: string; badge?: number }`) is inferred from the object literals — `Início` and the non-News save links simply omit `badge`.

- [ ] **Step 4: Run the test + typecheck**

Run: `npx vitest run src/components/Layout.test.tsx` (from `frontend/`)
Expected: all tests pass, including the new badge test.
Run: `npx tsc -b` (from `frontend/`)
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add -- frontend/src/components/Layout.tsx frontend/src/components/Layout.test.tsx
git commit -m "feat(news): unread badge on the News nav"
```

---

## Task 7: Full suites + cleanup

**Files:** none (verification task).

- [ ] **Step 1: Backend full suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`, heap already configured; ~9 min)
Expected: BUILD SUCCESSFUL. Any failure not in `season`/news scope is a pre-existing flake or concurrent-session work — confirm by checking the failing class against this branch's diff before treating it as ours. Known pre-existing flakes in this repo: `PreContractRolloverIT` (duplicate-key `toMap`, passes on re-run), `TransferServiceSellingIT` (bid flake, passes on re-run).

- [ ] **Step 2: Frontend full suite + typecheck**

Run: `npx tsc -b && npx vitest run` (from `frontend/`)
Expected: typecheck clean, all tests pass.

- [ ] **Step 3: Consistency sweep**

Confirm no dangling references:
- Run: `grep -rn "advanceController.getNews\|newsItemRepository" backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java` — expect no matches.
- Run: `grep -rn "getNews" frontend/src/api/career.ts` — expect no matches.
- Run: `grep -rn "from '../../api/career'" frontend/src/features/news/NewsPage.tsx` — expect only the `getSaveGame` import.

- [ ] **Step 4: Commit (only if the sweep required a fix)**

```bash
git add -A
git commit -m "chore(news): consistency sweep after news inbox"
```

If nothing changed, skip the commit.

---

## Self-Review notes (author)

- **Spec coverage:** V32 migration + backfill (T1); read flag + repo methods (T1); `NewsFeedItemResponse` + `NewsUnreadCountResponse` + `NewsService` + `NEWS_ITEM_NOT_FOUND` (T2); `NewsController` + `AdvanceController` slimming + `NewsFeedIT` retarget (T3); `NewsFeedItem` + `api/news.ts` + `newsMeta` (T4); master-detail `NewsPage` + optimistic read + auto-select newest (T5); nav `<Badge>` + advance invalidation (T6); full suites (T7). All spec sections map to a task.
- **Type consistency:** `NewsFeedItemResponse(id, read, payload)` used identically in T2/T3; `NewsFeedItem { id, read, payload }` used identically in T4/T5; `getNews` returns `PageResponse<NewsFeedItem>` in T5, consumed by `NewsPage`'s infinite query; query keys `['news', saveGameId]` and `['newsUnreadCount', saveGameId]` consistent across NewsPage (T5) and Layout (T6); controller `markRead(saveGameId, newsItemId, userId)` param order matches its call in the NewsReadIT wiring test (T3).
- **Ordering safety:** T4 creates `api/news.ts` without `getNews` and leaves `career.ts` untouched, so the build stays green; T5 performs the `getNews` move + `NewsPage` rewrite together so the retyped signature and its only consumer change in one commit.
