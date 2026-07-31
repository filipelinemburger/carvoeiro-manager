# Watchlist ("Observados") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager track rival-club players and free agents ("Observados") and get proactively alerted when a watched player is sold, transfer-listed, or becomes a free agent — plus an unconditional alert whenever any of the manager's own squad players receives a transfer offer.

**Architecture:** A new `watchlist_entry` table + `WatchlistNotifier` service that writes standalone `WATCHLIST` news items (the `persistPreContractNews` precedent from cycle 2). Alerts are emitted by direct one-line calls at three existing choke points (`TransferExecutor.execute`, `TransferService.generateIncomingOffers`, `AiListingEngine`), all of which run during a season advance, plus a rollover hook that surfaces free-agent lapses via a new `RolloverResponse` field (rollover purges news, exactly as cycle 2's pre-contract execution events).

**Tech Stack:** Java 21/Spring Boot 3 + Flyway + Postgres (Testcontainers ITs), React 19 + TanStack Query + MUI (pt-BR UI). Jackson 3 (`tools.jackson.databind.ObjectMapper`).

**Spec:** `docs/superpowers/specs/2026-07-20-watchlist-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task that touches tracked files ends by committing its own tested work. `git push` is never run without an explicit user ask. This is standing project policy; do not withhold commits.
- Backend test command: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '...'` with a long timeout (600000ms) — `*IT` tests boot Testcontainers Postgres; the rollover ITs play a full season forward and are slow. Run test suites in the FOREGROUND and wait directly; if backgrounding, redirect straight to a log file (`> logfile 2>&1`) and verify the process is alive before waiting — never pipe through `tail` without `-f`.
- Frontend type-check is `npx tsc -b` (NEVER bare `npx tsc --noEmit` — a silent no-op here). Frontend tests: `npx vitest run`.
- All user-facing copy is pt-BR.
- News `type` column is `VARCHAR(16)`; the new type string is `WATCHLIST` (9 chars).
- Next Flyway migration number is **V31**.
- **Watchlist is rival-only:** you may only watch players NOT on the managed club. The manager's own `listPlayer` action can therefore never involve a watched player — do NOT hook it; only AI listings (`AiListingEngine`) can produce a `LISTED` alert.
- Alert kinds: `SOLD`, `LISTED`, `FREE_AGENT` (watchlist-gated), `OFFER_RECEIVED` (own-squad, unconditional). Shared record `PlayerAlertResponse(String kind, String playerName, String clubName, String detail)`.
- **SOLD fires only when the destination club is NOT the managed club** — a watched rival moving to any other club. The manager buying the watched player themselves fires no alert.
- In-season alerts (`SOLD`/`LISTED`/`OFFER_RECEIVED`) are written as standalone `WATCHLIST` news items by `WatchlistNotifier` (they appear in the Notícias feed, not the immediate advance card). `FREE_AGENT` rides a new `RolloverResponse.watchlistEvents` field (rollover purges the news feed).
- `ErrorCode` entries are alphabetically ordered and carry only an `HttpStatus`; 422 is `HttpStatus.UNPROCESSABLE_CONTENT`. `CANNOT_WATCH_OWN_PLAYER` sorts before `CLUB_NOT_FOUND`.
- Existing behavior that must NOT change: the free-agent signing flow, pre-contract flows, the transfer/listing/rollover mechanics themselves (this plan only ADDS alert emissions + a new table/tab).

---

### Task 1: Data layer — V31 migration, entity, repository, ErrorCode

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__watchlist.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistEntry.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistEntryRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistEntryRepositoryIT.java`

**Interfaces:**
- Produces (later tasks rely on these exact signatures):
  - `WatchlistEntry.create(UUID saveGameId, UUID playerId, java.time.Instant createdAt)` → getters `getId/getSaveGameId/getPlayerId/getCreatedAt`.
  - `WatchlistEntryRepository`: `List<WatchlistEntry> findBySaveGameId(UUID)`, `boolean existsBySaveGameIdAndPlayerId(UUID, UUID)`, `Optional<WatchlistEntry> findBySaveGameIdAndPlayerId(UUID, UUID)`, `@Transactional void deleteBySaveGameIdAndPlayerId(UUID, UUID)`, `@Transactional void deleteBySaveGameId(UUID)`.
  - `ErrorCode.CANNOT_WATCH_OWN_PLAYER` (422).

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V31__watchlist.sql`:

```sql
CREATE TABLE watchlist_entry (
    id           UUID PRIMARY KEY,
    save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id    UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    created_at   TIMESTAMP NOT NULL,
    UNIQUE (save_game_id, player_id)
);

CREATE INDEX idx_watchlist_save_game ON watchlist_entry(save_game_id);
CREATE INDEX idx_watchlist_player ON watchlist_entry(player_id);
```

- [ ] **Step 2: Write the entity** (mirrors `PreContractAgreement`'s shape exactly)

`backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistEntry.java`:

```java
package br.com.carvoeiromanager.watchlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One player the manager is tracking. Rival-club players and free agents only — never a
 * player on the managed club. Rows are removed on unwatch and cascade-deleted when the
 * player or save is deleted.
 */
@Entity
@Table(name = "watchlist_entry")
public class WatchlistEntry {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected WatchlistEntry() { }

    private WatchlistEntry(UUID id, UUID saveGameId, UUID playerId, Instant createdAt) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.playerId = playerId;
        this.createdAt = createdAt;
    }

    public static WatchlistEntry create(UUID saveGameId, UUID playerId, Instant createdAt) {
        return new WatchlistEntry(UUID.randomUUID(), saveGameId, playerId, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Write the repository**

`backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistEntryRepository.java`:

```java
package br.com.carvoeiromanager.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {

    List<WatchlistEntry> findBySaveGameId(UUID saveGameId);

    boolean existsBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    Optional<WatchlistEntry> findBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    @Transactional
    void deleteBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 4: Add the ErrorCode**

In `ErrorCode.java`, insert alphabetically (it sorts before `CLUB_NOT_FOUND` — "CANNOT" < "CLUB"):

```java
    CANNOT_WATCH_OWN_PLAYER(HttpStatus.UNPROCESSABLE_CONTENT),
```

- [ ] **Step 5: Write a repository IT**

`backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistEntryRepositoryIT.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistEntryRepositoryIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistEntryRepository watchlistEntryRepository;

    private UUID createSave() {
        User user = User.create("watch_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    @Test
    void addFindExistsAndRemoveRoundTrip() {
        UUID saveGameId = createSave();
        Player anyPlayer = playerRepository.findBySaveGameId(saveGameId).get(0);
        UUID playerId = anyPlayer.getId();

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isFalse();

        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, playerId, Instant.now()));

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isTrue();
        assertThat(watchlistEntryRepository.findBySaveGameId(saveGameId))
                .extracting(WatchlistEntry::getPlayerId).containsExactly(playerId);

        watchlistEntryRepository.deleteBySaveGameIdAndPlayerId(saveGameId, playerId);
        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isFalse();
    }

    @Test
    void deletingThePlayerCascadesTheWatchlistEntry() {
        UUID saveGameId = createSave();
        Player anyPlayer = playerRepository.findBySaveGameId(saveGameId).get(0);
        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, anyPlayer.getId(), Instant.now()));

        playerRepository.deleteById(anyPlayer.getId());

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, anyPlayer.getId()))
                .isFalse();
    }
}
```

- [ ] **Step 6: Run the IT to verify it passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.WatchlistEntryRepositoryIT'`
Expected: PASS (2 tests). If an existing `ErrorCodeTest` asserts an exact constant count, update it for the new entry (same as cycle 2's Task 3 did).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V31__watchlist.sql backend/src/main/java/br/com/carvoeiromanager/watchlist/ backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistEntryRepositoryIT.java
git commit -m "feat(watchlist): data layer — V31 migration, WatchlistEntry entity, repository"
```

(Include any updated `ErrorCodeTest` in the `git add`.)

---

### Task 2: PlayerAlertResponse + WatchlistNotifier

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/PlayerAlertResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistNotifier.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistNotifierTest.java`

**Interfaces:**
- Consumes: Task 1's `WatchlistEntryRepository`; existing `br.com.carvoeiromanager.season.NewsItem`, `NewsItemRepository`, `br.com.carvoeiromanager.player.Player`, `tools.jackson.databind.ObjectMapper`.
- Produces:
  - `PlayerAlertResponse(String kind, String playerName, String clubName, String detail)`.
  - `WatchlistNotifier` (`@Service`):
    - `void notifySold(UUID saveGameId, LocalDate date, Player player, String newClubName, BigDecimal fee)` — watched-gated.
    - `void notifyListed(UUID saveGameId, LocalDate date, Player player, String clubName, String reasonDetail)` — watched-gated.
    - `void notifyOfferReceived(UUID saveGameId, LocalDate date, Player player, String biddingClubName, BigDecimal amount)` — unconditional.
    - `boolean isWatched(UUID saveGameId, UUID playerId)` — exposed for the rollover hook (Task 6) to reuse the same check.

- [ ] **Step 1: Write the response record**

`PlayerAlertResponse.java`:

```java
package br.com.carvoeiromanager.watchlist;

/**
 * One watchlist/alert happening, rendered in news cards and the season summary.
 * kind: SOLD (a watched rival moved to another club), LISTED (a watched rival was
 * transfer-listed), FREE_AGENT (a watched player's contract lapsed), OFFER_RECEIVED
 * (an AI club bid on one of the manager's own players). detail carries the fee / offer
 * amount / listing reason as a human string, nullable.
 */
public record PlayerAlertResponse(String kind, String playerName, String clubName, String detail) {}
```

- [ ] **Step 2: Write the failing unit test**

`WatchlistNotifierTest.java` (mocked repos — no Spring context):

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistNotifierTest {

    private final WatchlistEntryRepository watchlistRepo = mock(WatchlistEntryRepository.class);
    private final NewsItemRepository newsRepo = mock(NewsItemRepository.class);
    private final WatchlistNotifier notifier =
            new WatchlistNotifier(watchlistRepo, newsRepo, new ObjectMapper());

    private static Player player(UUID saveGameId) {
        return Player.create(saveGameId, UUID.randomUUID(), "Fulano", Position.ATT, 27,
                "Brasil", 70, 70, 60, 40, 8, 70, 70);
    }

    @Test
    void soldFiresOnlyForWatchedPlayers() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(false);
        notifier.notifySold(save, LocalDate.of(2026, 8, 1), p, "Rival FC", new BigDecimal("1000000"));
        verify(newsRepo, never()).save(any());

        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(true);
        when(newsRepo.countBySaveGameId(save)).thenReturn(4);
        notifier.notifySold(save, LocalDate.of(2026, 8, 1), p, "Rival FC", new BigDecimal("1000000"));

        ArgumentCaptor<NewsItem> captor = ArgumentCaptor.forClass(NewsItem.class);
        verify(newsRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("WATCHLIST");
        assertThat(captor.getValue().getPayload()).contains("SOLD").contains("Fulano").contains("Rival FC");
    }

    @Test
    void listedFiresOnlyForWatchedPlayers() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(false);
        notifier.notifyListed(save, LocalDate.of(2026, 8, 1), p, "Rival FC", "FINANCIAL");
        verify(newsRepo, never()).save(any());
    }

    @Test
    void offerReceivedFiresUnconditionally() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        // No watchlist stub — must NOT be consulted for own-player offers.
        when(newsRepo.countBySaveGameId(save)).thenReturn(0);
        notifier.notifyOfferReceived(save, LocalDate.of(2026, 8, 1), p, "Rich AI", new BigDecimal("500000"));

        ArgumentCaptor<NewsItem> captor = ArgumentCaptor.forClass(NewsItem.class);
        verify(newsRepo).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("OFFER_RECEIVED");
        verify(watchlistRepo, never()).existsBySaveGameIdAndPlayerId(any(), any());
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.WatchlistNotifierTest'`
Expected: FAIL — `WatchlistNotifier` does not exist.

- [ ] **Step 4: Write the notifier**

`WatchlistNotifier.java` (mirrors `persistPreContractNews`):

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes WATCHLIST news items when something happens to a watched player. Mirrors
 * PreContractService.persistPreContractNews: an independent news_item writer with an
 * AdvanceResponse-shaped payload ({type, date, playerAlerts}) the frontend renders with
 * the same card it uses for advances. SOLD/LISTED are gated on the watchlist;
 * OFFER_RECEIVED is unconditional (the player is the manager's own).
 */
@Service
public class WatchlistNotifier {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public WatchlistNotifier(WatchlistEntryRepository watchlistEntryRepository,
                             NewsItemRepository newsItemRepository,
                             ObjectMapper objectMapper) {
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public boolean isWatched(UUID saveGameId, UUID playerId) {
        return watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId);
    }

    public void notifySold(UUID saveGameId, LocalDate date, Player player,
                           String newClubName, BigDecimal fee) {
        if (!isWatched(saveGameId, player.getId())) {
            return;
        }
        persist(saveGameId, date, new PlayerAlertResponse(
                "SOLD", player.getName(), newClubName, fee == null ? null : fee.toPlainString()));
    }

    public void notifyListed(UUID saveGameId, LocalDate date, Player player,
                             String clubName, String reasonDetail) {
        if (!isWatched(saveGameId, player.getId())) {
            return;
        }
        persist(saveGameId, date, new PlayerAlertResponse(
                "LISTED", player.getName(), clubName, reasonDetail));
    }

    public void notifyOfferReceived(UUID saveGameId, LocalDate date, Player player,
                                    String biddingClubName, BigDecimal amount) {
        // Unconditional — the player is the manager's own; no watchlist check.
        persist(saveGameId, date, new PlayerAlertResponse(
                "OFFER_RECEIVED", player.getName(), biddingClubName,
                amount == null ? null : amount.toPlainString()));
    }

    private void persist(UUID saveGameId, LocalDate date, PlayerAlertResponse alert) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "WATCHLIST",
                    "date", date.toString(),
                    "playerAlerts", List.of(alert)));
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, date, "WATCHLIST", payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize watchlist news item", e);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Same command as Step 3. Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/watchlist/PlayerAlertResponse.java backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistNotifier.java backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistNotifierTest.java
git commit -m "feat(watchlist): PlayerAlertResponse and WatchlistNotifier news writer"
```

---

### Task 3: WatchlistService + WatchlistController + DTO

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistPlayerResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistServiceIT.java`

**Interfaces:**
- Consumes: Task 1's repo; existing `SaveGameService.requireOwned`, `PlayerRepository`, `ClubRepository`, `br.com.carvoeiromanager.contract.PreContractWindow`.
- Produces:
  - `WatchlistPlayerResponse(UUID playerId, String name, String position, int age, int overall, String clubName, boolean freeAgent, boolean transferListed, boolean preContractEligible)`.
  - `WatchlistService`: `void add(UUID saveGameId, UUID userId, UUID playerId)`, `void remove(UUID saveGameId, UUID userId, UUID playerId)`, `List<WatchlistPlayerResponse> getWatchlist(UUID saveGameId, UUID userId)`.

- [ ] **Step 1: Write the DTO**

`WatchlistPlayerResponse.java`:

```java
package br.com.carvoeiromanager.watchlist;

import java.util.UUID;

public record WatchlistPlayerResponse(UUID playerId, String name, String position, int age,
                                      int overall, String clubName, boolean freeAgent,
                                      boolean transferListed, boolean preContractEligible) {}
```

- [ ] **Step 2: Write the failing IT**

`WatchlistServiceIT.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistServiceIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistService watchlistService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("wsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player anyRival(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .findFirst().orElseThrow();
    }

    @Test
    void addAndListAndRemoveARivalPlayer() {
        Career c = createCareer();
        Player rival = anyRival(c);

        watchlistService.add(c.id(), c.userId(), rival.getId());

        List<WatchlistPlayerResponse> list = watchlistService.getWatchlist(c.id(), c.userId());
        assertThat(list).extracting(WatchlistPlayerResponse::playerId).contains(rival.getId());
        assertThat(list).allSatisfy(w -> assertThat(w.clubName()).isNotBlank());

        watchlistService.remove(c.id(), c.userId(), rival.getId());
        assertThat(watchlistService.getWatchlist(c.id(), c.userId()))
                .extracting(WatchlistPlayerResponse::playerId).doesNotContain(rival.getId());
    }

    @Test
    void addingIsIdempotent() {
        Career c = createCareer();
        Player rival = anyRival(c);
        watchlistService.add(c.id(), c.userId(), rival.getId());
        watchlistService.add(c.id(), c.userId(), rival.getId()); // no throw, no duplicate
        assertThat(watchlistService.getWatchlist(c.id(), c.userId())).hasSize(1);
    }

    @Test
    void cannotWatchAnOwnSquadPlayer() {
        Career c = createCareer();
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);

        assertThatThrownBy(() -> watchlistService.add(c.id(), c.userId(), mine.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.CANNOT_WATCH_OWN_PLAYER));
    }

    @Test
    void aFreeAgentCanBeWatchedAndShowsAsFreeAgent() {
        Career c = createCareer();
        Player fa = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).get(0);
        watchlistService.add(c.id(), c.userId(), fa.getId());
        assertThat(watchlistService.getWatchlist(c.id(), c.userId()))
                .anySatisfy(w -> {
                    assertThat(w.playerId()).isEqualTo(fa.getId());
                    assertThat(w.freeAgent()).isTrue();
                });
    }
}
```

- [ ] **Step 3: Run it to verify it fails, then implement**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.WatchlistServiceIT'`
Expected: FAIL — service/controller don't exist.

`WatchlistService.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.contract.PreContractWindow;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manager-facing watchlist toggle + listing. Rival-only: watching an own-squad player is
 * rejected. The listing surfaces each watched player's current status (club or free agent,
 * transfer-listed, pre-contract-eligible) computed read-only from existing data.
 */
@Service
public class WatchlistService {

    private final SaveGameService saveGameService;
    private final WatchlistEntryRepository watchlistEntryRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final PreContractWindow preContractWindow;

    public WatchlistService(SaveGameService saveGameService,
                            WatchlistEntryRepository watchlistEntryRepository,
                            PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            PreContractWindow preContractWindow) {
        this.saveGameService = saveGameService;
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.preContractWindow = preContractWindow;
    }

    @Transactional
    public void add(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found"));
        if (save.getManagedClubId().equals(player.getClubId())) {
            throw new ApiException(ErrorCode.CANNOT_WATCH_OWN_PLAYER,
                    "You can only watch players from other clubs");
        }
        if (watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)) {
            return; // idempotent
        }
        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, playerId, Instant.now()));
    }

    @Transactional
    public void remove(UUID saveGameId, UUID userId, UUID playerId) {
        saveGameService.requireOwned(saveGameId, userId);
        watchlistEntryRepository.deleteBySaveGameIdAndPlayerId(saveGameId, playerId);
    }

    public List<WatchlistPlayerResponse> getWatchlist(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        boolean windowOpen = preContractWindow.isOpen(save.getCurrentDate());
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));
        return watchlistEntryRepository.findBySaveGameId(saveGameId).stream()
                .map(e -> players.get(e.getPlayerId()))
                .filter(p -> p != null)
                .map(p -> new WatchlistPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        p.isFreeAgent() ? "Agente livre" : clubNames.getOrDefault(p.getClubId(), "?"),
                        p.isFreeAgent(), p.isTransferListed(),
                        !p.isFreeAgent() && windowOpen && p.getContractYears() == 1))
                .sorted(Comparator.comparing(WatchlistPlayerResponse::overall).reversed())
                .toList();
    }
}
```

`WatchlistController.java` (mirrors `PreContractController`):

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public ResponseEntity<List<WatchlistPlayerResponse>> list(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(watchlistService.getWatchlist(saveGameId, userId));
    }

    @PostMapping("/{playerId}")
    public ResponseEntity<Void> add(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        watchlistService.add(saveGameId, userId, playerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{playerId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        watchlistService.remove(saveGameId, userId, playerId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run the IT to verify it passes**

Same command as Step 3. Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistPlayerResponse.java backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistService.java backend/src/main/java/br/com/carvoeiromanager/watchlist/WatchlistController.java backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistServiceIT.java
git commit -m "feat(watchlist): toggle + listing service, controller, own-player guard"
```

---

### Task 4: SOLD + OFFER_RECEIVED hooks (transfer package)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferExecutor.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistTransferHooksIT.java`

**Interfaces:**
- Consumes: Task 2's `WatchlistNotifier`.
- Produces: no new public interface — two alert emissions.

- [ ] **Step 1: Wire SOLD in TransferExecutor**

Inject `WatchlistNotifier watchlistNotifier` (add field + constructor param + assignment, matching the class's existing 5-dependency constructor style). At the END of the core `execute(...)` method (after the managed-ledger `if/else` block, i.e. after the whole body), add:

```java
        // Watchlist: a watched rival moving to another club is the "you missed out" alert.
        // Fire only when the destination is NOT the managed club (buying the watched player
        // yourself is your own action, no alert).
        if (!buyerId.equals(managedClubId)) {
            watchlistNotifier.notifySold(saveGameId, saveGame.getCurrentDate(), player,
                    buyer.getName(), fee);
        }
```

(`player`, `buyer`, `fee`, `buyerId`, `managedClubId`, `saveGameId`, `saveGame` are all in scope; `saveGame.getCurrentDate()` gives the date.)

- [ ] **Step 2: Wire OFFER_RECEIVED in TransferService.generateIncomingOffers**

Inject `WatchlistNotifier watchlistNotifier` (TransferService already has a 14-dependency constructor — add one more param + field + assignment). Inside `generateIncomingOffers`, immediately after the existing `transferOfferRepository.save(TransferOffer.create(...))` call, add:

```java
                watchlistNotifier.notifyOfferReceived(saveGameId, eventDate, player,
                        bidder.getName(), bid);
```

(`saveGameId`, `eventDate`, `player`, `bidder`, `bid` are all in scope inside the `if (rng.nextDouble() < chance)` block.)

- [ ] **Step 3: Write the IT**

`backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistTransferHooksIT.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.TransferExecutor;
import br.com.carvoeiromanager.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistTransferHooksIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired WatchlistService watchlistService;
    @Autowired WatchlistEntryRepository watchlistEntryRepository;
    @Autowired TransferExecutor transferExecutor;
    @Autowired TransferService transferService;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("wh_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<PlayerAlert> watchlistNews(UUID saveGameId) {
        return newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId).stream()
                .filter(n -> "WATCHLIST".equals(n.getType()))
                .map(NewsItem::getPayload)
                .map(PlayerAlert::from)
                .toList();
    }

    // tiny payload sniffer — asserts on the JSON text rather than deserializing
    private record PlayerAlert(String raw) {
        static PlayerAlert from(String payload) { return new PlayerAlert(payload); }
        boolean has(String kind, String playerName) { return raw.contains(kind) && raw.contains(playerName); }
    }

    @Test
    void sellingAWatchedRivalToAnotherClubFiresSold() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        // Two AI clubs and a rival player on the first.
        List<Club> aiClubs = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).toList();
        Club sellerAi = aiClubs.get(0);
        Club buyerAi = aiClubs.get(1);
        Player rival = playerRepository.findByClubIdAndYouthProspectFalse(sellerAi.getId()).get(0);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        transferExecutor.execute(save, rival.getId(), buyerAi.getId(), sellerAi.getId(),
                new BigDecimal("1000000"), null, null);

        assertThat(watchlistNews(c.id())).anySatisfy(a -> assertThat(a.has("SOLD", rival.getName())).isTrue());
    }

    @Test
    void buyingAWatchedRivalYourselfFiresNoSoldAlert() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        Club sellerAi = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).findFirst().orElseThrow();
        Player rival = playerRepository.findByClubIdAndYouthProspectFalse(sellerAi.getId()).get(0);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        transferExecutor.execute(save, rival.getId(), c.clubId(), sellerAi.getId(),
                new BigDecimal("1000000"), null, null);

        assertThat(watchlistNews(c.id())).noneSatisfy(a -> assertThat(a.has("SOLD", rival.getName())).isTrue());
    }

    @Test
    void anIncomingOfferOnAnOwnPlayerFiresOfferReceivedUnconditionally() {
        Career c = createCareer();
        // Move into an open transfer window (pre-season) so incoming offers generate.
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 1, 20));
        saveGameRepository.save(save);

        // Force offers by transfer-listing the whole squad, then run several market days.
        playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).forEach(p ->
                p.listForTransfer(br.com.carvoeiromanager.player.ListedReason.OWNER));
        playerRepository.saveAll(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()));

        boolean any = false;
        for (int d = 0; d < 5 && !any; d++) {
            transferService.generateIncomingOffers(c.id(), LocalDate.of(2026, 1, 20).plusDays(d * 3L));
            any = watchlistNews(c.id()).stream().anyMatch(a -> a.raw.contains("OFFER_RECEIVED"));
        }
        assertThat(any).as("an OFFER_RECEIVED watchlist news item was produced").isTrue();
    }
}
```

Note: if `ListedReason` is in a different package, fix the import (grep `enum ListedReason`). If `generateIncomingOffers`'s window gate or bid chance makes offers too rare across 5 days, widen to more days — the point is that at least one own-player offer fires the alert.

- [ ] **Step 4: Run the IT + the transfer suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.*' --tests 'br.com.carvoeiromanager.transfer.*'`
Expected: PASS (watchlist hooks green; existing transfer tests unaffected — the hooks are pure additions).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/transfer/TransferExecutor.java backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistTransferHooksIT.java
git commit -m "feat(watchlist): SOLD and OFFER_RECEIVED alerts from the transfer choke points"
```

---

### Task 5: LISTED hook (AiListingEngine)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/AiListingEngine.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java` (thread `eventDate` into the `refreshListings` call)
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistListingHookIT.java`

**Interfaces:**
- Consumes: Task 2's `WatchlistNotifier`.
- Produces: `AiListingEngine.refreshListings` gains a `LocalDate eventDate` parameter.

**Implementer note — verify signatures first:** the plan could not capture `AiListingEngine.refreshListings` (the public per-save method) and `TransferService.processMarketDay` verbatim. Read both before editing. The known facts: `processMarketDay(UUID saveGameId, LocalDate eventDate)` already holds `eventDate` and calls `aiListingEngine.refreshListings(saveGameId, save.getManagedClubId(), save.getCurrentRound())`; the three `listForTransfer(reason)` calls live in the private `refreshClubListings(Club club, int currentRound)`.

- [ ] **Step 1: Thread eventDate + inject the notifier**

In `AiListingEngine`: inject `WatchlistNotifier watchlistNotifier` (add to its 4-dependency constructor). Change `refreshListings(...)` to also accept `LocalDate eventDate`, and change `refreshClubListings(Club club, int currentRound)` to `refreshClubListings(Club club, int currentRound, UUID saveGameId, LocalDate eventDate)` (thread `saveGameId` + `eventDate` down from `refreshListings`). At EACH of the three `p.listForTransfer(reason)` sites, immediately after the `newlyListed.add(p)` line, add:

```java
                watchlistNotifier.notifyListed(saveGameId, eventDate, p, club.getName(), reason.name());
```

For call sites 2 and 3 where the reason is the literal `ListedReason.FINANCIAL` / `ListedReason.SURPLUS`, pass that literal's `.name()` (e.g. `ListedReason.FINANCIAL.name()`). The notifier internally no-ops for unwatched players, so this is safe to call for every newly-listed player.

In `TransferService.processMarketDay`, update the call: `aiListingEngine.refreshListings(saveGameId, save.getManagedClubId(), save.getCurrentRound(), eventDate);`.

- [ ] **Step 2: Write the IT**

`backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistListingHookIT.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistListingHookIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired WatchlistService watchlistService;
    @Autowired TransferService transferService;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    @Test
    void listingAWatchedRivalFiresListed() {
        User user = User.create("wl_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 1, 20)); // open window
        saveGameRepository.save(save);

        // Watch every rival player, then run market days until AI lists someone we watch.
        List<Club> aiClubs = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).toList();
        aiClubs.forEach(club -> playerRepository.findByClubIdAndYouthProspectFalse(club.getId())
                .forEach(p -> watchlistService.add(c.id(), c.userId(), p.getId())));

        boolean any = false;
        for (int d = 0; d < 10 && !any; d++) {
            transferService.processMarketDay(c.id(), LocalDate.of(2026, 1, 20).plusDays(d * 3L));
            any = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id()).stream()
                    .filter(n -> "WATCHLIST".equals(n.getType()))
                    .map(NewsItem::getPayload)
                    .anyMatch(pl -> pl.contains("LISTED"));
        }
        assertThat(any).as("a LISTED watchlist news item was produced by AI listing").isTrue();
    }
}
```

Note: AI listing is heuristic/seeded — 10 market days across a fully-watched league should reliably surface at least one listing, but if it proves flaky, widen the day count or assert against a save id known to list early (follow the same robustness approach cycle 2 used for the poach test — extend the window rather than force internals).

- [ ] **Step 3: Run the IT + transfer suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.*' --tests 'br.com.carvoeiromanager.transfer.*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/transfer/AiListingEngine.java backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistListingHookIT.java
git commit -m "feat(watchlist): LISTED alert when an AI club lists a watched rival"
```

---

### Task 6: FREE_AGENT hook (rollover) + RolloverResponse field

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistRolloverIT.java`

**Interfaces:**
- Consumes: Task 1's `WatchlistEntryRepository`; Task 2's `PlayerAlertResponse`.
- Produces: `RolloverResponse` gains `List<PlayerAlertResponse> watchlistEvents` as its LAST component.

- [ ] **Step 1: Extend RolloverResponse**

Add the field (import `br.com.carvoeiromanager.watchlist.PlayerAlertResponse`):

```java
public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures,
        List<SquadChangeResponse> preContractArrivals,
        List<SquadChangeResponse> preContractDepartures,
        List<PlayerAlertResponse> watchlistEvents
) {}
```

- [ ] **Step 2: Wire SeasonRolloverService**

Inject `WatchlistEntryRepository watchlistEntryRepository` (add to its constructor). Before the player loop (near where `lockedDeals` and the other accumulators are set up, ~line 205-211), add:

```java
        List<PlayerAlertResponse> watchlistEvents = new ArrayList<>();
        java.util.Set<UUID> watchedIds = watchlistEntryRepository.findBySaveGameId(saveGameId).stream()
                .map(br.com.carvoeiromanager.watchlist.WatchlistEntry::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> rolloverClubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        br.com.carvoeiromanager.club.Club::getId,
                        br.com.carvoeiromanager.club.Club::getName));
```

(Import `PlayerAlertResponse`; `Map`/`ArrayList`/`Collectors` are already imported in this file.)

In the no-deal lapse branch (the `else` block that does `player.setClubId(null); player.setContractYears(0);`), capture the club the player is leaving BEFORE nulling clubId, and collect the alert if watched:

```java
                    } else {
                        // No deal: the contract simply lapses — managed AND AI players
                        // alike now fall into the free-agent pool.
                        if (managed) {
                            expiredContracts.add(new SquadChangeResponse(
                                    player.getName(), player.getPosition().name(), player.getAge()));
                        }
                        if (watchedIds.contains(player.getId())) {
                            watchlistEvents.add(new PlayerAlertResponse("FREE_AGENT",
                                    player.getName(),
                                    rolloverClubNames.getOrDefault(player.getClubId(), "?"), null));
                        }
                        player.setClubId(null);
                        player.setContractYears(0);
                    }
```

Add `List.copyOf(watchlistEvents)` as the final argument of the `new RolloverResponse(...)` return statement.

- [ ] **Step 3: Write the IT**

`backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistRolloverIT.java`:

```java
package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.RolloverResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonRolloverService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistService watchlistService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("wroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    @Test
    void aWatchedRivalWhoLapsesAtRolloverAppearsInWatchlistEvents() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        // A rival, age <= 33 so it survives rollover, forced to expire and watched.
        Player rival = all.stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getAge() <= 33)
                .findFirst().orElseThrow();
        rival.setContractYears(1);
        playerRepository.saveAll(all);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        assertThat(r.watchlistEvents())
                .anySatisfy(a -> {
                    assertThat(a.kind()).isEqualTo("FREE_AGENT");
                    assertThat(a.playerName()).isEqualTo(rival.getName());
                });
        assertThat(playerRepository.findById(rival.getId()).orElseThrow().isFreeAgent()).isTrue();
    }
}
```

- [ ] **Step 4: Run the IT + season suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.watchlist.*' --tests 'br.com.carvoeiromanager.season.*'`
Expected: PASS. Fix any other `new RolloverResponse(` construction site the compiler flags (`grep -rn "new RolloverResponse" backend/src`), including tests, by appending `List.of()`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/ backend/src/test/java/br/com/carvoeiromanager/watchlist/WatchlistRolloverIT.java
git commit -m "feat(watchlist): FREE_AGENT alert via RolloverResponse.watchlistEvents"
```

(Include any updated test files touched by the `RolloverResponse` arity change.)

---

### Task 7: Frontend types + api module + cache invalidation

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/watchlist.ts`
- Modify: the advance + rollover invalidation hubs (`Layout.tsx` advance mutation and `SeasonSummaryPage.tsx` rollover mutation — the same two hubs cycle 2 wired; grep `preContractEligible` to find them).

**Interfaces:**
- Produces: types `WatchlistPlayer`, `PlayerAlert`; api fns `getWatchlist`, `addToWatchlist`, `removeFromWatchlist`; query key `['watchlist', saveGameId]`.

- [ ] **Step 1: Add the types**

In `frontend/src/api/types.ts`:

```ts
export type WatchlistPlayer = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubName: string
  freeAgent: boolean
  transferListed: boolean
  preContractEligible: boolean
}

export type PlayerAlert = {
  kind: 'SOLD' | 'LISTED' | 'FREE_AGENT' | 'OFFER_RECEIVED'
  playerName: string
  clubName: string
  detail: string | null
}
```

Extend `AdvanceResponse`: add `'WATCHLIST'` to the `type` union and append `playerAlerts?: PlayerAlert[] | null` (null-guarded like `preContractEvents`). Extend the frontend `RolloverResponse` type with `watchlistEvents: PlayerAlert[]` (required — the backend record always populates it; `RolloverResponse` is never persisted).

- [ ] **Step 2: Create the api module**

`frontend/src/api/watchlist.ts`:

```ts
import { apiDelete, apiGet, apiPost } from './client'
import type { WatchlistPlayer } from './types'

export function getWatchlist(saveGameId: string, token: string): Promise<WatchlistPlayer[]> {
  return apiGet<WatchlistPlayer[]>(`/save-games/${saveGameId}/watchlist`, token)
}

export function addToWatchlist(saveGameId: string, playerId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/watchlist/${playerId}`, {}, token)
}

export function removeFromWatchlist(saveGameId: string, playerId: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${saveGameId}/watchlist/${playerId}`, token)
}
```

- [ ] **Step 3: Wire cache invalidation**

At each hub that invalidates `['preContractEligible', saveGameId]` on advance/rollover (grep to find them — `Layout.tsx` advance, `SeasonSummaryPage.tsx` rollover), add alongside:

```ts
void queryClient.invalidateQueries({ queryKey: ['watchlist', saveGameId] })
```

- [ ] **Step 4: Type-check and commit**

Run: `cd frontend && npx tsc -b`
Expected: clean.

```bash
git add frontend/src/api/types.ts frontend/src/api/watchlist.ts
git commit -m "feat(watchlist): frontend types, api module, cache invalidation"
```

(Include the invalidation-hub files in the `git add`.)

---

### Task 8: Frontend UI — star toggle, Observados tab, news + rollover rendering

**Files:**
- Modify: `frontend/src/features/squad/PlayerDetails.tsx` (star toggle in the title)
- Create: `frontend/src/features/transfer/WatchlistPanel.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx` (5th "Observados" tab; pass `managedClubId` to `PlayerDetails`)
- Modify: `frontend/src/features/match/AdvanceEventCard.tsx` (`WATCHLIST` type branch)
- Modify: `frontend/src/features/season/SeasonSummaryPage.tsx` (`watchlistEvents` section)
- Test: `frontend/src/features/transfer/WatchlistPanel.test.tsx`

**Interfaces:**
- Consumes: Task 7's types/api.

- [ ] **Step 1: Star toggle in PlayerDetails**

`PlayerDetails.tsx` gains two optional props so it can show a watch star only for rival players:

```tsx
export function PlayerDetails({ player, onClose, saveGameId, lineupControl, contractAction, listingAction, isOwnPlayer, watchAction }: {
  player: PlayerResponse | null
  onClose: () => void
  saveGameId: string
  lineupControl?: ReactNode
  contractAction?: ReactNode
  listingAction?: ReactNode
  isOwnPlayer?: boolean
  watchAction?: ReactNode
}) {
```

In the `DialogTitle`, render `watchAction` (when provided) between the name and the Close button:

```tsx
          <DialogTitle sx={{ pr: 6, display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Typography component="span" variant="h6" sx={{ fontWeight: 700 }}>{player.name}</Typography>
            {watchAction}
            <IconButton aria-label="Fechar" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}>
              <CloseIcon />
            </IconButton>
          </DialogTitle>
```

The star itself is built by the caller (TransferMarketPage) and passed in as `watchAction`, so `PlayerDetails` stays free of query wiring — matching how `contractAction`/`listingAction` already work. The caller only passes `watchAction` when the player is NOT own-squad.

- [ ] **Step 2: WatchlistPanel**

`frontend/src/features/transfer/WatchlistPanel.tsx` (follows `PreContractsPanel`'s shape — default export, `staleTime: Infinity`, loading/error states, `errorMessage` mapping):

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Box, Button, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography,
} from '@mui/material'
import { getWatchlist, removeFromWatchlist } from '../../api/watchlist'
import { errorMessage } from '../../api/errorMessages'

type Props = {
  saveGameId: string
  token: string
}

export default function WatchlistPanel({ saveGameId, token }: Props) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const enabled = Boolean(saveGameId && token)

  const watchlist = useQuery({
    queryKey: ['watchlist', saveGameId],
    queryFn: () => getWatchlist(saveGameId, token),
    enabled,
    staleTime: Infinity,
  })

  const unwatchMut = useMutation({
    mutationFn: (playerId: string) => removeFromWatchlist(saveGameId, playerId, token),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['watchlist', saveGameId] }),
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao remover da lista.')),
  })

  if (watchlist.isLoading) return <Typography color="text.secondary">Carregando…</Typography>
  if (watchlist.isError) return <Alert severity="error">Erro ao carregar a lista de observados.</Alert>

  const rows = watchlist.data ?? []

  return (
    <Box>
      {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>{error}</Alert>}
      {rows.length === 0 ? (
        <Typography color="text.secondary">
          Nenhum jogador observado. Abra os detalhes de um jogador de outro clube e toque na estrela.
        </Typography>
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Jogador</TableCell>
              <TableCell>Pos</TableCell>
              <TableCell align="right">Idade</TableCell>
              <TableCell align="right">Geral</TableCell>
              <TableCell>Clube</TableCell>
              <TableCell>Situação</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map(p => (
              <TableRow key={p.playerId} hover>
                <TableCell>{p.name}</TableCell>
                <TableCell>{p.position}</TableCell>
                <TableCell align="right">{p.age}</TableCell>
                <TableCell align="right">{p.overall}</TableCell>
                <TableCell>{p.clubName}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    {p.transferListed && <Chip size="small" color="warning" label="Listado" />}
                    {p.preContractEligible && <Chip size="small" color="info" label="Pré-contrato" />}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Button size="small" disabled={unwatchMut.isPending}
                          onClick={() => unwatchMut.mutate(p.playerId)}>
                    Remover
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Box>
  )
}
```

- [ ] **Step 3: Observados tab + star wiring in TransferMarketPage**

Add the 5th tab to both the desktop `Tabs` (`<Tab label="Observados" />`) and mobile `NativeSelect` (`<option value={4}>Observados</option>`), and the panel block:

```tsx
      {tab === 4 && <WatchlistPanel saveGameId={saveGameId} token={token ?? ''} />}
```

Import `WatchlistPanel` and add the closed-window-alert exclusion for `tab !== 4` if that gate (line ~427) would otherwise show a spurious pre-contract-window alert on the Observados tab.

Wire the star into the `PlayerDetails` render (line ~750). Build a `watchStar` node using `addToWatchlist`/`removeFromWatchlist` + the `['watchlist', saveGameId]` query, rendered only when the resolved details player is a rival (compare the player's club against `managedClubId` — the market/free-agent/pre-contract lists are rival players; incoming-proposal players are own-squad). Pass `watchAction={isRival ? watchStar : undefined}` and `isOwnPlayer={!isRival}`. Use MUI `StarIcon` (filled = watched) / `StarBorderIcon` (outline = not) in an `IconButton size="small"` with `aria-label={`${watched ? 'Remover da' : 'Adicionar à'} lista de observação de ${player.name}`}`. Determining rival-vs-own: the details dialog is opened from the market/FA/pre-contract lists (rival) and from incoming proposals (own) — thread an `isRival` flag from whichever `setDetailsPlayerId`/`setDetailsPlayer` call opened it (add a small piece of state, e.g. `detailsIsRival`, set true when opened from a rival list and false from the incoming-proposals path).

- [ ] **Step 4: WATCHLIST news branch in AdvanceEventCard**

Header label (sibling to the PRECONTRACT header):

```tsx
          {result.type === 'WATCHLIST' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Observados
            </Typography>
          )}
```

Body (keyed on `playerAlerts`, like the pre-contract branch keys on `preContractEvents`):

```tsx
          {(result.playerAlerts?.length ?? 0) > 0 && (
            <TimelineNode dot="🔭">
              <Typography component="strong" sx={{ fontWeight: 700 }}>Observados</Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.playerAlerts!.map((a, i) => (
                  <ListItem key={`${a.playerName}-${i}`} disablePadding>
                    {a.kind === 'SOLD' && (<>🔁 {a.playerName} foi vendido ao {a.clubName}</>)}
                    {a.kind === 'LISTED' && (<>📋 {a.playerName} foi listado para transferência ({a.clubName})</>)}
                    {a.kind === 'FREE_AGENT' && (<>🆓 {a.playerName} ficou livre no mercado</>)}
                    {a.kind === 'OFFER_RECEIVED' && (
                      <>📥 {a.playerName} recebeu proposta do {a.clubName}{a.detail ? ` (R$ ${a.detail})` : ''}</>
                    )}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}
```

- [ ] **Step 5: watchlistEvents section in SeasonSummaryPage**

After the pre-contract sections, add (null-guarded):

```tsx
          {((result.watchlistEvents ?? []).length > 0) && (
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>Observados que ficaram livres</Typography>
              <List dense disablePadding>
                {(result.watchlistEvents ?? []).map((a, i) => (
                  <ListItem key={i} disableGutters sx={{ py: 0.25 }}>
                    <Typography variant="body2">{a.playerName} (deixou {a.clubName})</Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}
```

- [ ] **Step 6: Component test**

`frontend/src/features/transfer/WatchlistPanel.test.tsx` — mirror `PreContractsPanel.test.tsx`'s mock/render conventions (read it first; mock `../../api/watchlist`). Cover: (1) renders watched rows with club + status chips; (2) empty-state message when the list is empty; (3) the Remover button calls `removeFromWatchlist` with the player id. Write the three tests in full following the existing test file's scaffolding.

- [ ] **Step 7: Verify and commit**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: clean + all tests pass.

```bash
git add frontend/src/features/squad/PlayerDetails.tsx frontend/src/features/transfer/ frontend/src/features/match/AdvanceEventCard.tsx frontend/src/features/season/SeasonSummaryPage.tsx
git commit -m "feat(watchlist): Observados tab, star toggle, news and season-summary rendering"
```

---

### Task 9: Full suites and cleanup

**Files:** none new — fixes only if suites surface fallout.

- [ ] **Step 1: Full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL. Likely fallout to check: any test constructing `RolloverResponse` with the old arity; any `ErrorCodeTest` count guard.

- [ ] **Step 2: Full frontend suite**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: clean + all green.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A backend/src frontend/src
git commit -m "test: align remaining suites with watchlist alerts"
```

(Skip the commit if Steps 1–2 passed with no changes.)

---

## Self-Review Notes

- **Spec coverage:** data model (Task 1), notifier + alert record (Task 2), toggle/list API + own-player guard (Task 3), SOLD + OFFER_RECEIVED (Task 4), LISTED (Task 5), FREE_AGENT + RolloverResponse (Task 6), frontend types/api/invalidation (Task 7), star toggle + Observados tab + news + summary (Task 8), full suites (Task 9). Out-of-scope items (pre-contract-eligibility push alerts, rival-offer alerts, own-squad watching, notes/tags) are untouched.
- **Rival-only simplification exploited:** the manager's own `listPlayer` can never involve a watched player, so it is deliberately NOT hooked — only `AiListingEngine`. Documented in Global Constraints so a reviewer doesn't flag the "missing" hook.
- **SOLD guard:** fires only when the buyer is not the managed club (buying the watched player yourself is not a surprise). Tested both ways in Task 4.
- **In-season vs rollover delivery:** all in-season alerts (SOLD/LISTED/OFFER_RECEIVED) are standalone `WATCHLIST` news items (feed, not the immediate advance card); FREE_AGENT rides `RolloverResponse.watchlistEvents` because rollover purges the news feed — the same split cycle 2 used, so the pattern is proven.
- **No new backend `AdvanceResponse` field:** the WATCHLIST news payload is an ad-hoc `Map` (the `persistPreContractNews` precedent); only the frontend TS `AdvanceResponse` type gains `playerAlerts` to read stored payloads.
- **Type consistency:** `PlayerAlertResponse(kind, playerName, clubName, detail)` (backend) ↔ `PlayerAlert` (frontend) match field-for-field; `WatchlistPlayerResponse` ↔ `WatchlistPlayer` match; the `kind` union is identical on both sides.
- **Task 5 signature-change caveat** is flagged for the implementer (verify `refreshListings`/`processMarketDay` verbatim before editing) since those two methods weren't captured verbatim in the digest.
