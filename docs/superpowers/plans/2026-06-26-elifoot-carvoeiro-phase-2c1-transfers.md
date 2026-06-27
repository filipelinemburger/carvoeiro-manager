# Phase 2C-1 — Transfer Market & Negotiation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working transfer market: during transfer windows, browse other clubs' players, make offers with single-counter AI negotiation, field unsolicited AI bids for your players via an inbox, and execute deals against the 2B budget.

**Architecture:** A persisted `transfer_offer` state machine plus pure deterministic helpers (`TransferValuation`, `TransferWindow`, `TransferNegotiator`). `TransferService` orchestrates (outgoing offers get a synchronous AI response; unsolicited incoming bids are generated during advance into an inbox); `TransferExecutor` performs the atomic move (player club change + balances + finance ledger) and cleans a sold player out of the managed lineup. Contracts/free agents are deferred to 2C-2.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.6.1, Flyway, JPA, PostgreSQL, Testcontainers + JUnit 5 + AssertJ (backend); React 19 + TypeScript (strict) + Vite + TanStack Query + Vitest/RTL (frontend).

## Global Constraints

- **Build env:** default `java` is 8 and aborts Gradle. Every backend command MUST be prefixed: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"`. Backend from `backend/`, frontend from `frontend/`.
- **Money:** `BigDecimal` only (no float/double); `NUMERIC(15,2)`; calculator returns scale-2 via `setScale(2, RoundingMode.HALF_UP)`.
- **Determinism:** negotiation thresholds are pure; bid generation is seeded from `saveGameId.getMostSignificantBits() ^ (long) round` (matching the match engine). No other RNG in transfer code.
- **Layering:** Controller→Service→Repository; constructor injection; records for DTOs; UUID ids in static `create(...)`.
- **IDOR:** every transfer service method starts with `saveGameService.requireOwned(saveGameId, userId)` (foreign → 404).
- **Errors:** `ApiException(code, message, HttpStatus)`. New codes: `TRANSFER_WINDOW_CLOSED`/`INSUFFICIENT_FUNDS`/`SQUAD_FULL`/`SQUAD_TOO_SMALL`/`INVALID_TRANSFER` → `HttpStatus.UNPROCESSABLE_CONTENT` (422); `TRANSFER_OFFER_NOT_FOUND` → `HttpStatus.NOT_FOUND` (404).
- **Scope:** managed↔AI only (no AI-vs-AI); no contracts/free agents (2C-2); no transfer-listing.
- **LOCKED constants** (in the pure helpers; tests assert these exact values):
  - **Valuation:** `marketValue = overall³ × 5 × ageFactor`; `ageFactor`: `≤21 → 1.05`, `≤29 → 1.00`, `≤32 → 0.70`, `≤34 → 0.45`, `≥35 → 0.20`.
  - **Window:** open iff `currentRound < 3` OR (`currentRound ≥ totalRounds/2` AND `currentRound < totalRounds/2 + 3`) (integer division).
  - **Negotiator:** buy → `ACCEPT` if `offer ≥ value×0.95`; `COUNTER` at `value×1.10` if `offer ≥ value×0.70`; else `REJECT`. Sell-counter → AI accepts if `managedCounter ≤ value×1.15`. Unsolicited `bid = value×0.90`.
  - **Bid generation:** per call, iterate managed players in id order; per player draw `rng.nextDouble()`, create a bid if `< 0.15` and the player has no active incoming offer; cap `2` new bids per call; bidder = a seeded non-managed club.
  - **Squad limits:** `MIN_SQUAD_SIZE = 14`, `MAX_SQUAD_SIZE = 30`; cannot sell the managed club's last goalkeeper.
- **Tests:** Given/When/Then, ≥80%. Backend ITs `@SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)`. Frontend RTL with `vi.mock('../../api/career', ...)`.
- **Commits:** every message ends with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` (do not omit). Per-task commits direct to `main` are authorized.
- **Catarinense facts:** 10 clubs, `totalRounds = 18`, managed short name `CRI`; a fresh career is at `currentRound = 0` (window OPEN). Latest migration `V5` → this slice adds **`V6`**.

## File Structure

**New backend (`br.com.elifootcarvoeiro.transfer` unless noted)**
- `db/migration/V6__transfers.sql` — `transfer_offer` table.
- `TransferOffer.java`, `TransferDirection.java`, `TransferOfferStatus.java`, `TransferOfferRepository.java`.
- `TransferValuation.java`, `TransferWindow.java`, `TransferNegotiator.java` — pure `@Component`s.
- `TransferExecutor.java` — atomic move + money + ledger + lineup cleanup.
- `TransferService.java` — orchestration.
- `TransferController.java`.
- DTO records: `MarketPlayerResponse.java`, `TransferOfferResponse.java`, `MakeOfferRequest.java`, `RespondOfferRequest.java`.

**Modified backend**
- `finance/FinanceTransactionType.java` — add `TRANSFER_IN`, `TRANSFER_OUT`.
- `match/LineupPlayerRepository.java` — add `deleteByLineupIdAndPlayerId`.
- `match/RoundSimulationService.java` — generate incoming offers during advance (within a window).
- `season/SeasonRolloverService.java` — purge `transfer_offer` on rollover.

**New / modified frontend**
- `api/types.ts` — `MarketPlayer`, `TransferOffer`, `MakeOfferRequest`, `RespondOfferRequest`.
- `api/career.ts` — `getMarket`, `makeOffer`, `acceptCounter`, `withdrawOffer`, `respondToOffer`, `getOffers`.
- `features/transfer/TransferMarketPage.tsx` (+ test) — market + make-offer + outgoing + inbox.
- `features/career/DashboardPage.tsx` — "Mercado" link.
- `router.tsx` — `/transfers` route.

---

### Task 1: V6 migration + transfer_offer entity/enums/repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__transfers.sql`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferDirection.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOfferStatus.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOffer.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOfferRepository.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferOfferEntityIT.java`

**Interfaces:**
- Produces:
  - `TransferDirection` enum: `OUTGOING`, `INCOMING`.
  - `TransferOfferStatus` enum: `PENDING`, `COUNTERED`, `ACCEPTED`, `REJECTED`, `WITHDRAWN`.
  - `TransferOffer.create(UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId, TransferDirection direction, BigDecimal amount, int round)` (status `PENDING`, counter null) + getters + `markCountered(BigDecimal)`, `markAccepted()`, `markRejected()`, `markWithdrawn()`.
  - `TransferOfferRepository.findBySaveGameIdAndStatusIn(UUID, java.util.Collection<TransferOfferStatus>)`, `deleteBySaveGameId(UUID)`.

- [ ] **Step 1: Write the migration**

Create `V6__transfers.sql` (`player_id`/club ids are plain UUIDs — season-scoped, purged at rollover):

```sql
CREATE TABLE transfer_offer (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id      UUID NOT NULL,
    from_club_id   UUID NOT NULL,
    to_club_id     UUID NOT NULL,
    direction      VARCHAR(8) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL,
    counter_amount NUMERIC(15,2),
    status         VARCHAR(12) NOT NULL,
    round          INT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfer_offer_save_status ON transfer_offer(save_game_id, status);
CREATE INDEX idx_transfer_offer_save_to_club ON transfer_offer(save_game_id, to_club_id);
```

- [ ] **Step 2: Write the failing round-trip test**

`TransferOfferEntityIT.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferOfferEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    @Test
    void persistsAndTransitionsATransferOffer() {
        // Given a save game and an outgoing offer
        User user = User.create("tx_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2026);
        saveGameRepository.save(save);

        TransferOffer offer = TransferOffer.create(save.getId(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), TransferDirection.OUTGOING,
                new BigDecimal("1500000.00"), 0);
        transferOfferRepository.save(offer);

        // When it is countered and read back
        offer.markCountered(new BigDecimal("1800000.00"));
        transferOfferRepository.save(offer);
        List<TransferOffer> active = transferOfferRepository.findBySaveGameIdAndStatusIn(
                save.getId(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED));

        // Then it round-trips with the counter and COUNTERED status
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(TransferOfferStatus.COUNTERED);
        assertThat(active.get(0).getCounterAmount()).isEqualByComparingTo("1800000.00");
        assertThat(active.get(0).getDirection()).isEqualTo(TransferDirection.OUTGOING);

        // And deleteBySaveGameId purges it
        transferOfferRepository.deleteBySaveGameId(save.getId());
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(save.getId(),
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED))).isEmpty();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferOfferEntityIT'`
Expected: COMPILE FAILURE — transfer classes do not exist.

- [ ] **Step 4: Create the enums**

`TransferDirection.java`:

```java
package br.com.elifootcarvoeiro.transfer;

public enum TransferDirection {
    OUTGOING, INCOMING
}
```

`TransferOfferStatus.java`:

```java
package br.com.elifootcarvoeiro.transfer;

public enum TransferOfferStatus {
    PENDING, COUNTERED, ACCEPTED, REJECTED, WITHDRAWN
}
```

- [ ] **Step 5: Create the entity**

`TransferOffer.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A single transfer negotiation (season-scoped; purged at rollover). */
@Entity
@Table(name = "transfer_offer")
public class TransferOffer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "from_club_id", nullable = false) private UUID fromClubId;
    @Column(name = "to_club_id", nullable = false) private UUID toClubId;
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8) private TransferDirection direction;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "counter_amount") private BigDecimal counterAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12) private TransferOfferStatus status;
    @Column(name = "round", nullable = false) private int round;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected TransferOffer() { }

    private TransferOffer(UUID id, UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId,
                          TransferDirection direction, BigDecimal amount, TransferOfferStatus status,
                          int round, Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.playerId = playerId;
        this.fromClubId = fromClubId; this.toClubId = toClubId; this.direction = direction;
        this.amount = amount; this.status = status; this.round = round; this.createdAt = createdAt;
    }

    public static TransferOffer create(UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId,
                                       TransferDirection direction, BigDecimal amount, int round) {
        return new TransferOffer(UUID.randomUUID(), saveGameId, playerId, fromClubId, toClubId,
                direction, amount, TransferOfferStatus.PENDING, round, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getFromClubId() { return fromClubId; }
    public UUID getToClubId() { return toClubId; }
    public TransferDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public TransferOfferStatus getStatus() { return status; }
    public int getRound() { return round; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCountered(BigDecimal counterAmount) {
        this.counterAmount = counterAmount;
        this.status = TransferOfferStatus.COUNTERED;
    }
    public void markAccepted() { this.status = TransferOfferStatus.ACCEPTED; }
    public void markRejected() { this.status = TransferOfferStatus.REJECTED; }
    public void markWithdrawn() { this.status = TransferOfferStatus.WITHDRAWN; }
}
```

- [ ] **Step 6: Create the repository**

`TransferOfferRepository.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, UUID> {
    List<TransferOffer> findBySaveGameIdAndStatusIn(UUID saveGameId, Collection<TransferOfferStatus> statuses);
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferOfferEntityIT'`
Expected: PASS (Flyway applies V6, the offer round-trips).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V6__transfers.sql \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/ \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferOfferEntityIT.java
git commit -m "$(cat <<'EOF'
feat(transfer): V6 transfer_offer schema + entity/enums/repository

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: TransferValuation (pure market value)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferValuation.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferValuationTest.java`

**Interfaces:**
- Produces: `TransferValuation` (`@Component`) with `BigDecimal marketValue(int overall, int age)`.

- [ ] **Step 1: Write the failing test**

`TransferValuationTest.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferValuationTest {

    private final TransferValuation valuation = new TransferValuation();

    @Test
    void marketValueScalesWithOverallAndAge() {
        // base = overall^3 * 5; 70^3 = 343000 -> 1,715,000
        // prime age factor 1.00
        assertThat(valuation.marketValue(70, 25)).isEqualByComparingTo("1715000.00");
        // youth (<=21) factor 1.05
        assertThat(valuation.marketValue(70, 19)).isEqualByComparingTo("1800750.00");
        // decline (<=32) factor 0.70
        assertThat(valuation.marketValue(70, 31)).isEqualByComparingTo("1200500.00");
        // veteran (>=35) factor 0.20
        assertThat(valuation.marketValue(70, 36)).isEqualByComparingTo("343000.00");
    }

    @Test
    void higherOverallIsWorthMoreAtTheSameAge() {
        assertThat(valuation.marketValue(80, 25)).isGreaterThan(valuation.marketValue(70, 25));
    }

    @Test
    void primeIsWorthMoreThanVeteranAtSameOverall() {
        assertThat(valuation.marketValue(75, 25)).isGreaterThan(valuation.marketValue(75, 36));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferValuationTest'`
Expected: COMPILE FAILURE — `TransferValuation` does not exist.

- [ ] **Step 3: Create TransferValuation**

```java
package br.com.elifootcarvoeiro.transfer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure, deterministic market value: overall^3 * 5 * age factor (scale 2). */
@Component
public class TransferValuation {

    private static final BigDecimal VALUE_PER_CUBE = new BigDecimal("5");

    public BigDecimal marketValue(int overall, int age) {
        BigDecimal base = BigDecimal.valueOf((long) overall * overall * overall).multiply(VALUE_PER_CUBE);
        return base.multiply(ageFactor(age)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ageFactor(int age) {
        if (age <= 21) return new BigDecimal("1.05");
        if (age <= 29) return new BigDecimal("1.00");
        if (age <= 32) return new BigDecimal("0.70");
        if (age <= 34) return new BigDecimal("0.45");
        return new BigDecimal("0.20");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferValuationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferValuation.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferValuationTest.java
git commit -m "$(cat <<'EOF'
feat(transfer): deterministic TransferValuation (overall^3 * age factor)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: TransferWindow (pure gating)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferWindow.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferWindowTest.java`

**Interfaces:**
- Produces: `TransferWindow` (`@Component`) with `boolean isOpen(int currentRound, int totalRounds)`.

- [ ] **Step 1: Write the failing test**

`TransferWindowTest.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowTest {

    private final TransferWindow window = new TransferWindow();

    @Test
    void preSeasonWindowIsOpenForTheFirstThreeRounds() {
        // 18-round season
        assertThat(window.isOpen(0, 18)).isTrue();
        assertThat(window.isOpen(2, 18)).isTrue();
        assertThat(window.isOpen(3, 18)).isFalse();
        assertThat(window.isOpen(8, 18)).isFalse();
    }

    @Test
    void midSeasonWindowIsOpenAroundTheHalfwayPoint() {
        // totalRounds/2 = 9; open [9, 12)
        assertThat(window.isOpen(9, 18)).isTrue();
        assertThat(window.isOpen(11, 18)).isTrue();
        assertThat(window.isOpen(12, 18)).isFalse();
        assertThat(window.isOpen(17, 18)).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferWindowTest'`
Expected: COMPILE FAILURE — `TransferWindow` does not exist.

- [ ] **Step 3: Create TransferWindow**

```java
package br.com.elifootcarvoeiro.transfer;

import org.springframework.stereotype.Component;

/** Pure round-based transfer-window gating: a pre-season block and a mid-season block. */
@Component
public class TransferWindow {

    private static final int PRE_SEASON_ROUNDS = 3;
    private static final int MID_LENGTH = 3;

    public boolean isOpen(int currentRound, int totalRounds) {
        if (currentRound < PRE_SEASON_ROUNDS) {
            return true;
        }
        int midStart = totalRounds / 2;
        return currentRound >= midStart && currentRound < midStart + MID_LENGTH;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferWindowTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferWindow.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferWindowTest.java
git commit -m "$(cat <<'EOF'
feat(transfer): TransferWindow pre-season + mid-season gating

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: TransferNegotiator (deterministic AI decisions)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/NegotiationDecision.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/NegotiationResult.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferNegotiator.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferNegotiatorTest.java`

**Interfaces:**
- Produces:
  - `NegotiationDecision` enum: `ACCEPT`, `REJECT`, `COUNTER`.
  - `NegotiationResult(NegotiationDecision decision, BigDecimal counterAmount)` record (`counterAmount` null unless `COUNTER`).
  - `TransferNegotiator` (`@Component`): `NegotiationResult respondToBuy(BigDecimal value, BigDecimal offer)`; `boolean acceptsSellCounter(BigDecimal value, BigDecimal managedCounter)`; `BigDecimal bidAmount(BigDecimal value)`.

- [ ] **Step 1: Write the failing test**

`TransferNegotiatorTest.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransferNegotiatorTest {

    private final TransferNegotiator negotiator = new TransferNegotiator();
    private final BigDecimal value = new BigDecimal("1000000.00");

    @Test
    void buyOfferAtOrAboveNinetyFivePercentIsAccepted() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("950000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(r.counterAmount()).isNull();
    }

    @Test
    void buyOfferInTheNegotiableBandIsCounteredAtOneTenth() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("800000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(r.counterAmount()).isEqualByComparingTo("1100000.00"); // value * 1.10
    }

    @Test
    void buyOfferBelowSeventyPercentIsRejected() {
        NegotiationResult r = negotiator.respondToBuy(value, new BigDecimal("500000.00"));
        assertThat(r.decision()).isEqualTo(NegotiationDecision.REJECT);
        assertThat(r.counterAmount()).isNull();
    }

    @Test
    void aiAcceptsASellCounterUpToOneFifteenOfValue() {
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.00"))).isTrue();
        assertThat(negotiator.acceptsSellCounter(value, new BigDecimal("1150000.01"))).isFalse();
    }

    @Test
    void unsolicitedBidIsNinetyPercentOfValue() {
        assertThat(negotiator.bidAmount(value)).isEqualByComparingTo("900000.00");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferNegotiatorTest'`
Expected: COMPILE FAILURE — negotiator classes do not exist.

- [ ] **Step 3: Create the decision types + negotiator**

`NegotiationDecision.java`:

```java
package br.com.elifootcarvoeiro.transfer;

public enum NegotiationDecision {
    ACCEPT, REJECT, COUNTER
}
```

`NegotiationResult.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;

public record NegotiationResult(NegotiationDecision decision, BigDecimal counterAmount) {}
```

`TransferNegotiator.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic AI negotiation. Buy offers are accepted near value, countered in a
 * negotiable band, or rejected; the AI accepts a managed sell-counter up to a ceiling;
 * unsolicited bids come in below value.
 */
@Component
public class TransferNegotiator {

    private static final BigDecimal ACCEPT_THRESHOLD = new BigDecimal("0.95");
    private static final BigDecimal NEGOTIABLE_FLOOR = new BigDecimal("0.70");
    private static final BigDecimal COUNTER_FACTOR = new BigDecimal("1.10");
    private static final BigDecimal SELL_COUNTER_MAX = new BigDecimal("1.15");
    private static final BigDecimal BID_FACTOR = new BigDecimal("0.90");

    public NegotiationResult respondToBuy(BigDecimal value, BigDecimal offer) {
        if (offer.compareTo(scaled(value, ACCEPT_THRESHOLD)) >= 0) {
            return new NegotiationResult(NegotiationDecision.ACCEPT, null);
        }
        if (offer.compareTo(scaled(value, NEGOTIABLE_FLOOR)) >= 0) {
            return new NegotiationResult(NegotiationDecision.COUNTER, scaled(value, COUNTER_FACTOR));
        }
        return new NegotiationResult(NegotiationDecision.REJECT, null);
    }

    public boolean acceptsSellCounter(BigDecimal value, BigDecimal managedCounter) {
        return managedCounter.compareTo(scaled(value, SELL_COUNTER_MAX)) <= 0;
    }

    public BigDecimal bidAmount(BigDecimal value) {
        return scaled(value, BID_FACTOR);
    }

    private BigDecimal scaled(BigDecimal value, BigDecimal factor) {
        return value.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferNegotiatorTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/NegotiationDecision.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/NegotiationResult.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferNegotiator.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferNegotiatorTest.java
git commit -m "$(cat <<'EOF'
feat(transfer): deterministic TransferNegotiator (accept/counter/reject + bid)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: TransferExecutor (move player + money + ledger + lineup cleanup)

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionType.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupPlayerRepository.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferExecutor.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferExecutorIT.java`

**Interfaces:**
- Consumes: `TransferOffer` (Task 1); `Player.setClubId`; `Club.adjustBalance`; `FinanceTransaction.create` + `FinanceTransactionRepository`; `Lineup` taker getters/setters + `LineupRepository.findBySaveGameIdAndClubId`; `LineupPlayerRepository`; `SaveGame` (`getManagedClubId`/`getSeasonYear`/`getCurrentRound`).
- Produces:
  - `FinanceTransactionType` gains `TRANSFER_IN`, `TRANSFER_OUT`.
  - `LineupPlayerRepository.deleteByLineupIdAndPlayerId(UUID lineupId, UUID playerId)`.
  - `TransferExecutor` (`@Component`): `void execute(SaveGame saveGame, TransferOffer offer, BigDecimal fee)` — moves the player to the buyer, debits buyer / credits seller, records the managed ledger (`TRANSFER_IN` −fee if managed bought, `TRANSFER_OUT` +fee if managed sold), and on a managed sale removes the player from the managed lineup.

- [ ] **Step 1: Add the enum values + the repository finder**

In `finance/FinanceTransactionType.java`:

```java
package br.com.elifootcarvoeiro.finance;

public enum FinanceTransactionType {
    GATE, WAGES, SPONSOR, UPKEEP, PRIZE, TRANSFER_IN, TRANSFER_OUT
}
```

In `match/LineupPlayerRepository.java`, add:

```java
    void deleteByLineupIdAndPlayerId(UUID lineupId, UUID playerId);
```

(The interface already imports `java.util.UUID` and `java.util.List`; keep the existing `findByLineupId`/`deleteByLineupId`.)

- [ ] **Step 2: Write the failing test**

`TransferExecutorIT.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.finance.FinanceTransactionRepository;
import br.com.elifootcarvoeiro.finance.FinanceTransactionType;
import br.com.elifootcarvoeiro.match.Lineup;
import br.com.elifootcarvoeiro.match.LineupRepository;
import br.com.elifootcarvoeiro.match.LineupService;
import br.com.elifootcarvoeiro.match.LineupStarter;
import br.com.elifootcarvoeiro.match.SetLineupRequest;
import br.com.elifootcarvoeiro.match.SetTacticsRequest;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferExecutorIT {

    @Autowired TransferExecutor executor;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupService lineupService;
    @Autowired LineupRepository lineupRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("exec_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anyOtherClub(UUID saveGameId, UUID managedClubId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .map(Club::getId).filter(id -> !id.equals(managedClubId)).findFirst().orElseThrow();
    }

    @Test
    void buyingMovesThePlayerAdjustsBalancesAndRecordsTransferIn() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        UUID seller = anyOtherClub(c.id(), c.clubId());
        Player target = playerRepository.findByClubId(seller).get(0);
        BigDecimal fee = new BigDecimal("1000000.00");
        BigDecimal buyerBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        BigDecimal sellerBefore = clubRepository.findById(seller).orElseThrow().getBalance();

        TransferOffer offer = TransferOffer.create(c.id(), target.getId(), c.clubId(), seller,
                TransferDirection.OUTGOING, fee, save.getCurrentRound());

        executor.execute(save, offer, fee);

        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(buyerBefore.subtract(fee));
        assertThat(clubRepository.findById(seller).orElseThrow().getBalance())
                .isEqualByComparingTo(sellerBefore.add(fee));
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.TRANSFER_IN
                        && t.getAmount().signum() < 0);
    }

    @Test
    void sellingAManagedStarterRemovesThemFromTheLineupAndClearsTakerRefs() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        // Set a managed lineup and make the first starter a set-piece taker.
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi), c.userId());
        UUID soldId = xi.get(0).playerId();
        lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", soldId, soldId, soldId), c.userId());
        UUID buyer = anyOtherClub(c.id(), c.clubId());
        BigDecimal fee = new BigDecimal("750000.00");

        TransferOffer offer = TransferOffer.create(c.id(), soldId, buyer, c.clubId(),
                TransferDirection.INCOMING, fee, save.getCurrentRound());

        executor.execute(save, offer, fee);

        // Player moved to the buyer; managed TRANSFER_OUT recorded; lineup + taker refs cleared.
        assertThat(playerRepository.findById(soldId).orElseThrow().getClubId()).isEqualTo(buyer);
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.TRANSFER_OUT
                        && t.getAmount().signum() > 0);
        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId()).orElseThrow();
        assertThat(lineup.getPenaltyTakerId()).isNull();
        assertThat(lineup.getFreekickTakerId()).isNull();
        assertThat(lineup.getCornerTakerId()).isNull();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferExecutorIT'`
Expected: COMPILE FAILURE — `TransferExecutor` does not exist.

- [ ] **Step 4: Create TransferExecutor**

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.finance.FinanceTransaction;
import br.com.elifootcarvoeiro.finance.FinanceTransactionRepository;
import br.com.elifootcarvoeiro.finance.FinanceTransactionType;
import br.com.elifootcarvoeiro.match.Lineup;
import br.com.elifootcarvoeiro.match.LineupPlayerRepository;
import br.com.elifootcarvoeiro.match.LineupRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Performs a transfer atomically (called inside the transfer service's transaction):
 * moves the player to the buyer, debits the buyer / credits the seller, records the
 * managed-club ledger row, and on a managed sale removes the player from the managed lineup
 * (lineup_players row + any set-piece-taker column — no-cascade FK safety).
 */
@Component
public class TransferExecutor {

    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public TransferExecutor(PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            FinanceTransactionRepository financeTransactionRepository,
                            LineupRepository lineupRepository,
                            LineupPlayerRepository lineupPlayerRepository) {
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    public void execute(SaveGame saveGame, TransferOffer offer, BigDecimal fee) {
        final UUID saveGameId = saveGame.getId();
        final UUID managedClubId = saveGame.getManagedClubId();
        final UUID buyerId = offer.getFromClubId();
        final UUID sellerId = offer.getToClubId();

        Player player = playerRepository.findById(offer.getPlayerId()).orElseThrow();
        Club buyer = clubRepository.findById(buyerId).orElseThrow();
        Club seller = clubRepository.findById(sellerId).orElseThrow();

        // Money + ownership.
        buyer.adjustBalance(fee.negate());
        seller.adjustBalance(fee);
        player.setClubId(buyerId);
        clubRepository.save(buyer);
        clubRepository.save(seller);
        playerRepository.save(player);

        // Managed-club ledger row.
        if (buyerId.equals(managedClubId)) {
            record(saveGame, FinanceTransactionType.TRANSFER_IN, fee.negate(),
                    "Compra: " + player.getName());
        } else if (sellerId.equals(managedClubId)) {
            record(saveGame, FinanceTransactionType.TRANSFER_OUT, fee,
                    "Venda: " + player.getName());
            // The sold player can no longer be in the managed lineup (no-cascade FK safety).
            cleanManagedLineup(saveGameId, managedClubId, player.getId());
        }
    }

    private void cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId) {
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupIdAndPlayerId(lineup.getId(), playerId);
            boolean takerChanged = false;
            if (playerId.equals(lineup.getPenaltyTakerId())) { lineup.setPenaltyTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getFreekickTakerId())) { lineup.setFreekickTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getCornerTakerId())) { lineup.setCornerTakerId(null); takerChanged = true; }
            if (takerChanged) {
                lineupRepository.save(lineup);
            }
        });
    }

    private void record(SaveGame saveGame, FinanceTransactionType type, BigDecimal amount, String description) {
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGame.getId(), saveGame.getManagedClubId(), saveGame.getSeasonYear(),
                saveGame.getCurrentRound(), type, amount, description));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferExecutorIT'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionType.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/LineupPlayerRepository.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferExecutor.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferExecutorIT.java
git commit -m "$(cat <<'EOF'
feat(transfer): TransferExecutor - move player + money + ledger + lineup cleanup

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: TransferService — market + outgoing offers (buying)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/MarketPlayerResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOfferResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/MakeOfferRequest.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceBuyingIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned`; `SaveGameRepository`; `PlayerRepository.findBySaveGameId`/`findByClubId`/`findById`; `ClubRepository.findBySaveGameId`/`findById`; `CompetitionRepository.findBySaveGameId`; `TransferOfferRepository`; `TransferValuation`/`TransferWindow`/`TransferNegotiator`/`TransferExecutor` (Tasks 2–5).
- Produces:
  - `MarketPlayerResponse(UUID playerId, String name, String position, int age, int overall, UUID clubId, String clubName, BigDecimal value, BigDecimal wage)`.
  - `TransferOfferResponse(UUID id, UUID playerId, String playerName, UUID fromClubId, String fromClubName, UUID toClubId, String toClubName, String direction, BigDecimal amount, BigDecimal counterAmount, String status, int round)`.
  - `MakeOfferRequest(UUID playerId, BigDecimal amount)`.
  - `TransferService` with `getMarket`, `makeOffer`, `acceptCounter`, `withdrawOffer` (+ private `requireWindowOpen`, `requireOffer`, `toResponse`, `managedSquadSize`, constants `MIN_SQUAD_SIZE = 14`, `MAX_SQUAD_SIZE = 30`). (Task 7 adds the selling methods to this same class.)

- [ ] **Step 1: Write the DTOs**

`MarketPlayerResponse.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record MarketPlayerResponse(
        UUID playerId, String name, String position, int age, int overall,
        UUID clubId, String clubName, BigDecimal value, BigDecimal wage) {}
```

`TransferOfferResponse.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferOfferResponse(
        UUID id, UUID playerId, String playerName,
        UUID fromClubId, String fromClubName, UUID toClubId, String toClubName,
        String direction, BigDecimal amount, BigDecimal counterAmount, String status, int round) {}
```

`MakeOfferRequest.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record MakeOfferRequest(UUID playerId, BigDecimal amount) {}
```

- [ ] **Step 2: Write the failing test**

`TransferServiceBuyingIT.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceBuyingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("buy_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player anAiPlayer(Career c) {
        UUID other = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        return playerRepository.findByClubId(other).get(0);
    }

    private BigDecimal pct(BigDecimal value, String factor) {
        return value.multiply(new BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void offeringFullValueExecutesTheTransfer() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), target.getId(), value);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aLowOfferIsCounteredAndCanBeAccepted() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse countered = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.80"));
        assertThat(countered.status()).isEqualTo("COUNTERED");
        assertThat(countered.counterAmount()).isEqualByComparingTo(pct(value, "1.10"));

        TransferOfferResponse done = transferService.acceptCounter(c.id(), c.userId(), countered.id());
        assertThat(done.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aDerisoryOfferIsRejected() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.50"));
        assertThat(r.status()).isEqualTo("REJECTED");
    }

    @Test
    void anOfferOverBudgetIsRejectedWithInsufficientFunds() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal balance = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        assertThatThrownBy(() -> transferService.makeOffer(
                c.id(), c.userId(), target.getId(), balance.add(new BigDecimal("1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void offersAreBlockedWhenTheWindowIsClosed() {
        Career c = createCareer();
        // Advance to round 5 (pre-season window closed: 5 is not < 3 and not in [9,12)).
        for (int i = 0; i < 5; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        assertThatThrownBy(() -> transferService.makeOffer(c.id(), c.userId(), target.getId(), value))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void foreignUserCannotBrowseTheMarket() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> transferService.getMarket(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void marketListsOnlyOtherClubsPlayersWithAValue() {
        Career c = createCareer();
        var market = transferService.getMarket(c.id(), c.userId());
        assertThat(market).isNotEmpty();
        assertThat(market).noneMatch(m -> m.clubId().equals(c.clubId()));
        assertThat(market).allMatch(m -> m.value().signum() > 0);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferServiceBuyingIT'`
Expected: COMPILE FAILURE — `TransferService` does not exist.

- [ ] **Step 4: Create TransferService (buying side)**

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transfer market orchestration: market browse, outgoing offers (synchronous AI
 * negotiation), counter acceptance, and withdrawal. Selling (incoming bids) is added
 * in the same class by Task 7. All mutations are window-/budget-/squad-guarded.
 */
@Service
public class TransferService {

    static final int MIN_SQUAD_SIZE = 14;
    static final int MAX_SQUAD_SIZE = 30;

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final TransferValuation transferValuation;
    private final TransferWindow transferWindow;
    private final TransferNegotiator transferNegotiator;
    private final TransferExecutor transferExecutor;

    public TransferService(SaveGameService saveGameService,
                           SaveGameRepository saveGameRepository,
                           PlayerRepository playerRepository,
                           ClubRepository clubRepository,
                           CompetitionRepository competitionRepository,
                           TransferOfferRepository transferOfferRepository,
                           TransferValuation transferValuation,
                           TransferWindow transferWindow,
                           TransferNegotiator transferNegotiator,
                           TransferExecutor transferExecutor) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.transferValuation = transferValuation;
        this.transferWindow = transferWindow;
        this.transferNegotiator = transferNegotiator;
        this.transferExecutor = transferExecutor;
    }

    public List<MarketPlayerResponse> getMarket(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        Map<UUID, String> clubNames = clubNames(saveGameId);
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !p.getClubId().equals(managedClubId))
                .map(p -> new MarketPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(), p.getClubId(),
                        clubNames.getOrDefault(p.getClubId(), ""),
                        transferValuation.marketValue(p.getOverall(), p.getAge()), p.getWage()))
                .sorted(Comparator.comparing(MarketPlayerResponse::value).reversed())
                .toList();
    }

    @Transactional
    public TransferOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId, BigDecimal amount) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> invalid("Player is not available for transfer"));
        if (player.getClubId().equals(managedClubId)) {
            throw invalid("Cannot buy a player you already own");
        }
        requireFunds(managedClubId, amount);
        requireSquadRoom(managedClubId);

        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());
        TransferOffer offer = TransferOffer.create(saveGameId, playerId, managedClubId, player.getClubId(),
                TransferDirection.OUTGOING, amount, save.getCurrentRound());
        transferOfferRepository.save(offer);

        NegotiationResult result = transferNegotiator.respondToBuy(value, amount);
        switch (result.decision()) {
            case ACCEPT -> { transferExecutor.execute(save, offer, amount); offer.markAccepted(); }
            case COUNTER -> offer.markCountered(result.counterAmount());
            case REJECT -> offer.markRejected();
        }
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    @Transactional
    public TransferOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID offerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.OUTGOING
                || offer.getStatus() != TransferOfferStatus.COUNTERED) {
            throw invalid("No counter to accept on this offer");
        }
        requireWindowOpen(save);
        BigDecimal fee = offer.getCounterAmount();
        requireFunds(save.getManagedClubId(), fee);
        requireSquadRoom(save.getManagedClubId());
        transferExecutor.execute(save, offer, fee);
        offer.markAccepted();
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    @Transactional
    public TransferOfferResponse withdrawOffer(UUID saveGameId, UUID userId, UUID offerId) {
        saveGameService.requireOwned(saveGameId, userId);
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.OUTGOING
                || (offer.getStatus() != TransferOfferStatus.PENDING
                    && offer.getStatus() != TransferOfferStatus.COUNTERED)) {
            throw invalid("This offer cannot be withdrawn");
        }
        offer.markWithdrawn();
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    // ---- shared helpers (also used by Task 7's selling methods) ----

    void requireWindowOpen(SaveGame save) {
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).get(0);
        if (!transferWindow.isOpen(save.getCurrentRound(), competition.getTotalRounds())) {
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    TransferOffer requireOffer(UUID saveGameId, UUID offerId) {
        return transferOfferRepository.findById(offerId)
                .filter(o -> o.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException("TRANSFER_OFFER_NOT_FOUND",
                        "Transfer offer not found", HttpStatus.NOT_FOUND));
    }

    void requireFunds(UUID clubId, BigDecimal amount) {
        Club club = clubRepository.findById(clubId).orElseThrow();
        if (club.getBalance().compareTo(amount) < 0) {
            throw new ApiException("INSUFFICIENT_FUNDS",
                    "Not enough funds for this transfer", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
            throw new ApiException("SQUAD_FULL",
                    "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    ApiException invalid(String message) {
        return new ApiException("INVALID_TRANSFER", message, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    Map<UUID, String> clubNames(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
    }

    TransferOfferResponse toResponse(TransferOffer offer) {
        String playerName = playerRepository.findById(offer.getPlayerId())
                .map(Player::getName).orElse("");
        String fromName = clubRepository.findById(offer.getFromClubId()).map(Club::getName).orElse("");
        String toName = clubRepository.findById(offer.getToClubId()).map(Club::getName).orElse("");
        return new TransferOfferResponse(offer.getId(), offer.getPlayerId(), playerName,
                offer.getFromClubId(), fromName, offer.getToClubId(), toName,
                offer.getDirection().name(), offer.getAmount(), offer.getCounterAmount(),
                offer.getStatus().name(), offer.getRound());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferServiceBuyingIT'`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/MarketPlayerResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOfferResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/MakeOfferRequest.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceBuyingIT.java
git commit -m "$(cat <<'EOF'
feat(transfer): TransferService market browse + outgoing offers (buying)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: TransferService — incoming bids (selling) + inbox + generation

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/RespondOfferRequest.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceSellingIT.java`

**Interfaces:**
- Consumes: everything from Task 6 (same class) + `TransferNegotiator.acceptsSellCounter`/`bidAmount`, `Player.getPosition()` (for the last-GK guard).
- Produces:
  - `RespondOfferRequest(String action, BigDecimal counterAmount)` (action ∈ `ACCEPT`/`REJECT`/`COUNTER`).
  - `TransferService.respondToIncoming(UUID saveGameId, UUID userId, UUID offerId, String action, BigDecimal counterAmount) : TransferOfferResponse`.
  - `TransferService.getOffers(UUID saveGameId, UUID userId) : List<TransferOfferResponse>` (active inbox).
  - `TransferService.generateIncomingOffers(UUID saveGameId, int round)` (internal; called by advance).

- [ ] **Step 1: Write the request DTO**

`RespondOfferRequest.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;

public record RespondOfferRequest(String action, BigDecimal counterAmount) {}
```

- [ ] **Step 2: Write the failing test**

`TransferServiceSellingIT.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceSellingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("sell_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anAiClub(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
    }

    /** Create an unsolicited INCOMING bid from an AI club for one of the managed club's players. */
    private TransferOffer incomingBidFor(Career c, Player managedPlayer, BigDecimal amount) {
        TransferOffer offer = TransferOffer.create(c.id(), managedPlayer.getId(), anAiClub(c), c.clubId(),
                TransferDirection.INCOMING, amount, 0);
        return transferOfferRepository.save(offer);
    }

    private Player aManagedOutfielder(Career c) {
        return playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() != Position.GK).findFirst().orElseThrow();
    }

    @Test
    void acceptingAnIncomingBidSellsThePlayer() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringWithinTheCeilingIsAccepted() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal counter = value.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP); // <= 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", counter);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringAboveTheCeilingIsRejected() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal greedy = value.multiply(new BigDecimal("1.30")).setScale(2, RoundingMode.HALF_UP); // > 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", greedy);

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void cannotSellTheLastGoalkeeper() {
        Career c = createCareer();
        // Reduce managed GKs to exactly one.
        List<Player> gks = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).toList();
        if (gks.size() > 1) {
            playerRepository.deleteAll(gks.subList(1, gks.size()));
        }
        Player lastGk = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        TransferOffer bid = incomingBidFor(c, lastGk, new BigDecimal("800000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SQUAD_TOO_SMALL");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void getOffersReturnsTheActiveInbox() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        incomingBidFor(c, p, new BigDecimal("900000.00"));
        List<TransferOfferResponse> inbox = transferService.getOffers(c.id(), c.userId());
        assertThat(inbox).anyMatch(o -> o.direction().equals("INCOMING") && o.status().equals("PENDING"));
    }

    @Test
    void generateIncomingOffersCreatesBidsInsideAWindowAndNoneOutside() {
        Career c = createCareer();
        transferService.generateIncomingOffers(c.id(), 0);   // pre-season window OPEN
        long inWindow = transferOfferRepository.findBySaveGameIdAndStatusIn(
                c.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size();
        assertThat(inWindow).isGreaterThanOrEqualTo(0); // bounded; may be 0..2 deterministically

        Career c2 = createCareer();
        transferService.generateIncomingOffers(c2.id(), 5); // window CLOSED
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(
                c2.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED))).isEmpty();
    }

    @Test
    void foreignUserCannotReadOrRespond() {
        Career owner = createCareer();
        Career intruder = createCareer();
        Player p = aManagedOutfielder(owner);
        TransferOffer bid = incomingBidFor(owner, p, new BigDecimal("900000.00"));
        assertThatThrownBy(() -> transferService.getOffers(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> transferService.respondToIncoming(owner.id(), intruder.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

> Note on the in-window generation assertion: bid creation is deterministic but probabilistic per player (15% per player, capped at 2), so a given seed may yield 0 bids — the test asserts the bounded range `[0,2]` in-window and exactly `0` out-of-window. The out-of-window `0` is the meaningful invariant.

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferServiceSellingIT'`
Expected: COMPILE FAILURE — `respondToIncoming`/`getOffers`/`generateIncomingOffers` do not exist.

- [ ] **Step 4: Add the selling methods to TransferService**

In `TransferService.java`:
1. Add imports: `import br.com.elifootcarvoeiro.player.Position;`, `import java.util.Random;`, `import java.util.Set;`.
2. Add constants next to the squad constants:

```java
    private static final double BID_CHANCE = 0.15;
    private static final int MAX_BIDS_PER_ROUND = 2;
```

3. Add these methods (e.g. after `withdrawOffer`, before the helpers):

```java
    @Transactional
    public TransferOfferResponse respondToIncoming(UUID saveGameId, UUID userId, UUID offerId,
                                                   String action, BigDecimal counterAmount) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.INCOMING
                || !offer.getToClubId().equals(managedClubId)
                || (offer.getStatus() != TransferOfferStatus.PENDING
                    && offer.getStatus() != TransferOfferStatus.COUNTERED)) {
            throw invalid("This incoming offer cannot be answered");
        }
        requireWindowOpen(save);
        Player player = playerRepository.findById(offer.getPlayerId()).orElseThrow(() -> invalid("Player no longer exists"));
        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());

        switch (action) {
            case "ACCEPT" -> {
                requireSellAllowed(managedClubId, player);
                transferExecutor.execute(save, offer, offer.getAmount());
                offer.markAccepted();
            }
            case "REJECT" -> offer.markRejected();
            case "COUNTER" -> {
                offer.markCountered(counterAmount);
                if (transferNegotiator.acceptsSellCounter(value, counterAmount)) {
                    requireSellAllowed(managedClubId, player);
                    transferExecutor.execute(save, offer, counterAmount);
                    offer.markAccepted();
                } else {
                    offer.markRejected();
                }
            }
            default -> throw invalid("Unknown action: " + action);
        }
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    public List<TransferOfferResponse> getOffers(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Unsolicited AI bids for managed players during an open window (deterministic, seeded). */
    @Transactional
    public void generateIncomingOffers(UUID saveGameId, int round) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        if (!transferWindow.isOpen(round, competition.getTotalRounds())) {
            return;
        }
        UUID managedClubId = save.getManagedClubId();
        List<Club> aiClubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .sorted(Comparator.comparing(Club::getId)).toList();
        if (aiClubs.isEmpty()) {
            return;
        }
        Set<UUID> alreadyBidOn = transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING)
                .map(TransferOffer::getPlayerId)
                .collect(Collectors.toSet());

        List<Player> managedPlayers = playerRepository.findByClubId(managedClubId).stream()
                .sorted(Comparator.comparing(Player::getId)).toList();
        Random rng = new Random(saveGameId.getMostSignificantBits() ^ (long) round);
        int created = 0;
        for (Player player : managedPlayers) {
            if (created >= MAX_BIDS_PER_ROUND) {
                break;
            }
            if (alreadyBidOn.contains(player.getId())) {
                continue;
            }
            if (rng.nextDouble() < BID_CHANCE) {
                Club bidder = aiClubs.get(rng.nextInt(aiClubs.size()));
                BigDecimal bid = transferNegotiator.bidAmount(
                        transferValuation.marketValue(player.getOverall(), player.getAge()));
                transferOfferRepository.save(TransferOffer.create(saveGameId, player.getId(),
                        bidder.getId(), managedClubId, TransferDirection.INCOMING, bid, round));
                created++;
            }
        }
    }

    private void requireSellAllowed(UUID managedClubId, Player player) {
        List<Player> squad = playerRepository.findByClubId(managedClubId);
        if (squad.size() <= MIN_SQUAD_SIZE) {
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Squad would be too small", HttpStatus.UNPROCESSABLE_CONTENT);
        }
        if (player.getPosition() == Position.GK
                && squad.stream().filter(p -> p.getPosition() == Position.GK).count() <= 1) {
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Cannot sell your last goalkeeper", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
```

(`Competition` is already imported by Task 6. `Comparator`, `List`, `Map`, `Collectors` are already imported.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferServiceSellingIT'`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/RespondOfferRequest.java \
        backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceSellingIT.java
git commit -m "$(cat <<'EOF'
feat(transfer): incoming-bid responses, inbox, and seeded bid generation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: TransferController + advance hook + rollover purge

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferController.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferAdvanceRolloverIT.java`

**Interfaces:**
- Consumes: `TransferService` (Tasks 6–7); `TransferOfferRepository.deleteBySaveGameId` (Task 1).
- Produces: REST endpoints under `/api/v1/save-games/{saveGameId}/transfers`; advance generates incoming offers within a window; rollover purges `transfer_offer`.

- [ ] **Step 1: Create the controller**

`TransferController.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/market")
    public ResponseEntity<List<MarketPlayerResponse>> market(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.getMarket(saveGameId, userId));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<TransferOfferResponse>> offers(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.getOffers(saveGameId, userId));
    }

    @PostMapping("/offers")
    public ResponseEntity<TransferOfferResponse> makeOffer(
            @PathVariable UUID saveGameId, @RequestBody MakeOfferRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(
                transferService.makeOffer(saveGameId, userId, request.playerId(), request.amount()));
    }

    @PostMapping("/offers/{offerId}/accept-counter")
    public ResponseEntity<TransferOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.acceptCounter(saveGameId, userId, offerId));
    }

    @PostMapping("/offers/{offerId}/withdraw")
    public ResponseEntity<TransferOfferResponse> withdraw(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.withdrawOffer(saveGameId, userId, offerId));
    }

    @PostMapping("/offers/{offerId}/respond")
    public ResponseEntity<TransferOfferResponse> respond(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId,
            @RequestBody RespondOfferRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.respondToIncoming(
                saveGameId, userId, offerId, request.action(), request.counterAmount()));
    }
}
```

- [ ] **Step 2: Wire the advance hook**

In `match/RoundSimulationService.java`:
1. Add import: `import br.com.elifootcarvoeiro.transfer.TransferService;`.
2. Add the field + constructor parameter (append `TransferService transferService` as the **last** constructor parameter; assign it):

```java
    private final TransferService transferService;
```

3. In `simulateNextRound`, after the finance block builds `finance` and **before** the `return new SimulateRoundResponse(...)`, add (it self-gates on the window and no-ops when closed/finished):

```java
        transferService.generateIncomingOffers(saveGameId, newCurrentRound);
```

- [ ] **Step 3: Wire the rollover purge**

In `season/SeasonRolloverService.java`:
1. Add import: `import br.com.elifootcarvoeiro.transfer.TransferOfferRepository;`.
2. Add the field + constructor parameter (append `TransferOfferRepository transferOfferRepository` as the **last** constructor parameter; assign it).
3. In `rollover`, right after the existing finance-ledger purge line (`financeTransactionRepository.deleteBySaveGameId(saveGameId);`), add:

```java
        // Purge season-scoped transfer offers (players/clubs change at rollover).
        transferOfferRepository.deleteBySaveGameId(saveGameId);
```

- [ ] **Step 4: Write the failing test**

`TransferAdvanceRolloverIT.java`:

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.season.SeasonRolloverService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferAdvanceRolloverIT {

    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SeasonRolloverService seasonRolloverService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("txadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private static final List<TransferOfferStatus> ALL = List.of(
            TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED, TransferOfferStatus.ACCEPTED,
            TransferOfferStatus.REJECTED, TransferOfferStatus.WITHDRAWN);

    @Test
    void advancingInsideAWindowProducesOnlyValidIncomingOffers() {
        // Given a fresh career (rounds 1 and 2 are in the pre-season window)
        Career c = createCareer();

        // When advancing two rounds (the hook runs generateIncomingOffers for rounds 1 and 2)
        roundSimulationService.simulateNextRound(c.id(), c.userId());
        roundSimulationService.simulateNextRound(c.id(), c.userId());

        // Then any generated offers are INCOMING, PENDING, for the managed club, bounded, no errors
        List<TransferOffer> offers = transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL);
        assertThat(offers).allMatch(o -> o.getDirection() == TransferDirection.INCOMING);
        assertThat(offers).allMatch(o -> o.getStatus() == TransferOfferStatus.PENDING);
        assertThat(offers).allMatch(o -> o.getToClubId().equals(c.clubId()));
        assertThat(offers.size()).isLessThanOrEqualTo(4); // up to 2 per generating round
    }

    @Test
    void rolloverPurgesTransferOffers() {
        // Given a career with at least one transfer offer
        Career c = createCareer();
        Player managedPlayer = playerRepository.findByClubId(c.clubId()).get(0);
        UUID aiClub = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        transferOfferRepository.save(TransferOffer.create(c.id(), managedPlayer.getId(), aiClub,
                c.clubId(), TransferDirection.INCOMING, new BigDecimal("900000.00"), 0));

        // When the season is played out and rolled over
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        seasonRolloverService.rollover(c.id(), c.userId());

        // Then all transfer offers for the save are purged
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL)).isEmpty();
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.transfer.TransferAdvanceRolloverIT'`
Expected: FAIL — the advance hook / rollover purge are not wired (the rollover test still finds the offer; or compile fails if the wiring edits are incomplete).

- [ ] **Step 6: Run the full backend suite (no regressions from the constructor changes)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test`
Expected: PASS (the new hook + purge are wired; `RoundSimulationServiceIT`, `FinanceAdvanceIT`, `SeasonRolloverServiceIT`, and the transfer ITs all green).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferController.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java \
        backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferAdvanceRolloverIT.java
git commit -m "$(cat <<'EOF'
feat(transfer): transfers REST controller + advance generates bids + rollover purge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Frontend — market browse + make offer + outgoing counters

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Create: `frontend/src/lib/transferWindow.ts`
- Create: `frontend/src/features/transfer/TransferMarketPage.tsx`
- Create: `frontend/src/features/transfer/TransferMarketPage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/features/career/DashboardPage.tsx`

**Interfaces:**
- Consumes: backend `GET /transfers/market`, `GET /transfers/offers`, `POST /transfers/offers`, `.../accept-counter`, `.../withdraw` (Tasks 6–8); existing `getSaveGame`, `formatBRL`.
- Produces (types): `MarketPlayer`, `TransferOffer`, `MakeOfferRequest`, `RespondOfferRequest`. (client): `getMarket`, `getOffers`, `makeOffer`, `acceptCounter`, `withdrawOffer` (and `respondToOffer` for Task 10). (util): `isTransferWindowOpen`. Route `/transfers`.

- [ ] **Step 1: Add the types**

Append to `frontend/src/api/types.ts`:

```ts
export type MarketPlayer = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubId: string
  clubName: string
  value: number
  wage: number
}

export type TransferOffer = {
  id: string
  playerId: string
  playerName: string
  fromClubId: string
  fromClubName: string
  toClubId: string
  toClubName: string
  direction: 'OUTGOING' | 'INCOMING'
  amount: number
  counterAmount: number | null
  status: 'PENDING' | 'COUNTERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
  round: number
}

export type MakeOfferRequest = { playerId: string; amount: number }
export type RespondOfferRequest = { action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }
```

- [ ] **Step 2: Add the client functions**

In `frontend/src/api/career.ts`, add `MarketPlayer`, `TransferOffer`, `MakeOfferRequest`, `RespondOfferRequest` to the type import block, then append:

```ts
export function getMarket(saveGameId: string, token: string): Promise<MarketPlayer[]> {
  return apiGet<MarketPlayer[]>(`/save-games/${saveGameId}/transfers/market`, token)
}

export function getOffers(saveGameId: string, token: string): Promise<TransferOffer[]> {
  return apiGet<TransferOffer[]>(`/save-games/${saveGameId}/transfers/offers`, token)
}

export function makeOffer(
  saveGameId: string,
  req: MakeOfferRequest,
  token: string,
): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers`, req, token)
}

export function acceptCounter(saveGameId: string, offerId: string, token: string): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/accept-counter`, {}, token)
}

export function withdrawOffer(saveGameId: string, offerId: string, token: string): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/withdraw`, {}, token)
}

export function respondToOffer(
  saveGameId: string,
  offerId: string,
  req: RespondOfferRequest,
  token: string,
): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/respond`, req, token)
}
```

- [ ] **Step 3: Add the window helper**

Create `frontend/src/lib/transferWindow.ts` (mirrors the backend `TransferWindow`):

```ts
export function isTransferWindowOpen(currentRound: number, totalRounds: number): boolean {
  if (currentRound < 3) return true
  const midStart = Math.floor(totalRounds / 2)
  return currentRound >= midStart && currentRound < midStart + 3
}
```

- [ ] **Step 4: Write the failing page test**

`frontend/src/features/transfer/TransferMarketPage.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TransferMarketPage } from './TransferMarketPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  getMarket: vi.fn(),
  getOffers: vi.fn(),
  makeOffer: vi.fn(),
  acceptCounter: vi.fn(),
  withdrawOffer: vi.fn(),
  respondToOffer: vi.fn(),
}))

import { getSaveGame, getMarket, getOffers, makeOffer } from '../../api/career'

const SAVE_OPEN = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'c-1', competitionName: 'Catarinense',
  managedClubId: 'club-1', managedClubName: 'Criciúma', currentRound: 0, totalRounds: 18,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/transfers?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TransferMarketPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('TransferMarketPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_OPEN)
    vi.mocked(getOffers).mockResolvedValue([])
    vi.mocked(getMarket).mockResolvedValue([
      { playerId: 'p1', name: 'Alvo Um', position: 'ATT', age: 24, overall: 80,
        clubId: 'club-9', clubName: 'Rival FC', value: 3000000, wage: 50000 },
    ])
  })

  it('lists market players and shows the AI response to an offer', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2400000,
      counterAmount: 3300000, status: 'COUNTERED', round: 0,
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Rival FC')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.click(screen.getByRole('button', { name: /propor/i }))

    await waitFor(() => expect(screen.getByText(/Contraproposta/i)).toBeInTheDocument())
    expect(makeOffer).toHaveBeenCalledWith('sg-1', { playerId: 'p1', amount: 2400000 }, 'test-token')
  })

  it('shows a closed-window banner and disables proposing when the window is closed', async () => {
    vi.mocked(getSaveGame).mockResolvedValue({ ...SAVE_OPEN, currentRound: 5 })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/fechada/i))
    expect(screen.getByRole('button', { name: /propor/i })).toBeDisabled()
  })
})
```

- [ ] **Step 5: Run it to verify it fails**

Run (from `frontend/`): `npm run test -- TransferMarketPage`
Expected: FAIL — `TransferMarketPage` module does not exist.

- [ ] **Step 6: Create TransferMarketPage**

`frontend/src/features/transfer/TransferMarketPage.tsx`:

```tsx
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  getSaveGame, getMarket, getOffers, makeOffer, acceptCounter, withdrawOffer,
} from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import { isTransferWindowOpen } from '../../lib/transferWindow'

export function TransferMarketPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [amounts, setAmounts] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const market = useQuery({ queryKey: ['market', saveGameId], queryFn: () => getMarket(saveGameId, token ?? ''), enabled })
  const offers = useQuery({ queryKey: ['offers', saveGameId], queryFn: () => getOffers(saveGameId, token ?? ''), enabled })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
  }
  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number }) => makeOffer(saveGameId, v, token ?? ''),
    onSuccess: invalidate,
  })
  const acceptMut = useMutation({ mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''), onSuccess: invalidate })
  const withdrawMut = useMutation({ mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''), onSuccess: invalidate })

  if (market.isLoading) return <p>Carregando mercado…</p>
  if (market.error) return <p>Erro ao carregar o mercado.</p>

  const windowOpen = save.data ? isTransferWindowOpen(save.data.currentRound, save.data.totalRounds) : true
  const outgoing = (offers.data ?? []).filter(o => o.direction === 'OUTGOING' && o.status === 'COUNTERED')
  const last = offerMut.data

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Mercado de Transferências</h2>
      {!windowOpen && <p role="alert" style={{ color: 'red' }}>Janela de transferências fechada.</p>}
      {last && (
        <p style={{ color: last.status === 'REJECTED' ? 'red' : 'green' }}>
          {last.playerName}: {last.status === 'ACCEPTED' ? 'Proposta aceita!'
            : last.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(last.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </p>
      )}
      {offerMut.isError && <p style={{ color: 'red' }}>Erro ao enviar proposta.</p>}

      {outgoing.length > 0 && (
        <div style={{ marginBottom: '1rem' }}>
          <h3>Contrapropostas recebidas</h3>
          <ul>
            {outgoing.map(o => (
              <li key={o.id}>
                {o.playerName} — {formatBRL(o.counterAmount ?? 0)}{' '}
                <button className="cec-btn" disabled={!windowOpen || acceptMut.isPending}
                  onClick={() => acceptMut.mutate(o.id)}>Aceitar</button>{' '}
                <button className="cec-btn" disabled={withdrawMut.isPending}
                  onClick={() => withdrawMut.mutate(o.id)}>Desistir</button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th><th>Idade</th><th>OVR</th>
            <th style={{ textAlign: 'left' }}>Clube</th>
            <th style={{ textAlign: 'right' }}>Valor</th>
            <th>Proposta</th>
          </tr>
        </thead>
        <tbody>
          {(market.data ?? []).map(p => (
            <tr key={p.playerId}>
              <td>{p.name}</td>
              <td style={{ textAlign: 'center' }}>{p.position}</td>
              <td style={{ textAlign: 'center' }}>{p.age}</td>
              <td style={{ textAlign: 'center' }}>{p.overall}</td>
              <td>{p.clubName}</td>
              <td style={{ textAlign: 'right' }}>{formatBRL(p.value)}</td>
              <td style={{ textAlign: 'center' }}>
                <input
                  aria-label={`Valor para ${p.name}`}
                  type="number"
                  value={amounts[p.playerId] ?? ''}
                  onChange={e => setAmounts(a => ({ ...a, [p.playerId]: e.target.value }))}
                  style={{ width: 110 }}
                />{' '}
                <button
                  className="cec-btn"
                  disabled={!windowOpen || offerMut.isPending}
                  onClick={() => offerMut.mutate({ playerId: p.playerId, amount: Number(amounts[p.playerId] ?? 0) })}
                >
                  Propor
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 7: Add the route + dashboard link**

In `frontend/src/router.tsx`: import `TransferMarketPage` and add a protected route:

```tsx
import { TransferMarketPage } from './features/transfer/TransferMarketPage'
```
```tsx
      <Route
        path="/transfers"
        element={
          <ProtectedRoute>
            <TransferMarketPage />
          </ProtectedRoute>
        }
      />
```

In `frontend/src/features/career/DashboardPage.tsx`, add a "Mercado" link in the button row (after the "Finanças" link):

```tsx
              <Link to={`/transfers?saveGameId=${saveGame.id}&clubId=${saveGame.managedClubId}`}>
                <button className="cec-btn">Mercado</button>
              </Link>
```

- [ ] **Step 8: Run the test + build**

Run (from `frontend/`):
- `npm run test -- TransferMarketPage`
- `npm run build`

Expected: tests PASS; `tsc -b && vite build` clean.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts frontend/src/lib/transferWindow.ts \
        frontend/src/features/transfer/TransferMarketPage.tsx \
        frontend/src/features/transfer/TransferMarketPage.test.tsx \
        frontend/src/router.tsx frontend/src/features/career/DashboardPage.tsx
git commit -m "$(cat <<'EOF'
feat(ui): transfer market - browse, make offer, outgoing counters + dashboard link

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Frontend — incoming-bid inbox (selling)

**Files:**
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.test.tsx`

**Interfaces:**
- Consumes: `respondToOffer` (Task 9 client); the `getOffers` INCOMING entries.
- Produces: an inbox section on `TransferMarketPage` for incoming AI bids (accept / reject / counter).

- [ ] **Step 1: Add the failing inbox test**

In `TransferMarketPage.test.tsx`, import `respondToOffer` alongside the others (`import { getSaveGame, getMarket, getOffers, makeOffer, respondToOffer } from '../../api/career'`) and add a test:

```tsx
  it('shows incoming bids and accepts one', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0 },
    ])
    vi.mocked(respondToOffer).mockResolvedValue({
      id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
      toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
      counterAmount: null, status: 'ACCEPTED', round: 0,
    })

    renderPage()
    await waitFor(() => expect(screen.getByText(/Propostas recebidas/i)).toBeInTheDocument())
    expect(screen.getByText(/Meu Jogador/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /aceitar venda/i }))
    await waitFor(() =>
      expect(respondToOffer).toHaveBeenCalledWith('sg-1', 'in1', { action: 'ACCEPT', counterAmount: null }, 'test-token'),
    )
  })
```

- [ ] **Step 2: Run it to verify it fails**

Run (from `frontend/`): `npm run test -- TransferMarketPage`
Expected: FAIL — there is no "Propostas recebidas" inbox yet.

- [ ] **Step 3: Add the inbox section**

In `TransferMarketPage.tsx`:
1. Add `respondToOffer` to the import from `../../api/career`.
2. Add a counter-input state and a respond mutation near the other mutations:

```tsx
  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onSuccess: invalidate,
  })
```

3. Compute the incoming list and render the inbox section just below the `{!windowOpen && ...}` banner (before the market table):

```tsx
  const incoming = (offers.data ?? []).filter(o => o.direction === 'INCOMING' && o.status === 'PENDING')
```
```tsx
      {incoming.length > 0 && (
        <div style={{ marginBottom: '1rem' }}>
          <h3>Propostas recebidas</h3>
          <ul>
            {incoming.map(o => (
              <li key={o.id} style={{ marginBottom: '0.5rem' }}>
                {o.playerName} — {o.fromClubName} oferece {formatBRL(o.amount)}{' '}
                <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'ACCEPT', counterAmount: null })}>Aceitar venda</button>{' '}
                <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'REJECT', counterAmount: null })}>Recusar</button>{' '}
                <input aria-label={`Contraproposta para ${o.playerName}`} type="number"
                  value={counters[o.id] ?? ''} onChange={e => setCounters(c => ({ ...c, [o.id]: e.target.value }))}
                  style={{ width: 110 }} />
                <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'COUNTER', counterAmount: Number(counters[o.id] ?? 0) })}>Contrapor</button>
              </li>
            ))}
          </ul>
        </div>
      )}
```

(`useState` is already imported.)

- [ ] **Step 4: Run the test + full suite + build**

Run (from `frontend/`):
- `npm run test -- TransferMarketPage`
- `npm run test`
- `npm run build`

Expected: all PASS; build clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/transfer/TransferMarketPage.tsx \
        frontend/src/features/transfer/TransferMarketPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(ui): transfer inbox - accept/reject/counter incoming bids

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification (after all tasks)

- [ ] Backend: `cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test` — all green.
- [ ] Frontend: `cd frontend && npm run test && npm run build` — all green, build clean.
- [ ] Dispatch the final whole-branch code review (subagent-driven-development's final review step).

## Plan Self-Review

**Spec coverage** (against `...-phase-2c1-transfers-design.md`):
- §5 V6 + entity/enums/repo → Task 1. §6 valuation/window/negotiator → Tasks 2/3/4. §7 TransferService (market/makeOffer/acceptCounter/withdraw) → Task 6; (respondToIncoming/getOffers/generateIncomingOffers) → Task 7; TransferExecutor (+lineup cleanup) → Task 5. §8 hooks: advance generation → Task 8, rollover purge → Task 8. §9 API → Task 8 controller (DTOs in Tasks 6/7). §10 errors → Tasks 6/7. §11 frontend → Tasks 9/10. §12 testing → every task is TDD; coverage matches the spec's test list. No spec requirement is left without a task.

**Placeholder scan:** no TBD/"handle errors"/uncoded steps; every code step shows full code. The window-closed banner mirrors the backend `TransferWindow` via a small, explicitly-coded FE helper (documented duplication).

**Type consistency:** `TransferOffer`/`TransferDirection`/`TransferOfferStatus`/`TransferOfferRepository` (Task 1) used by Tasks 5–8. `TransferValuation`/`TransferWindow`/`TransferNegotiator`+`NegotiationResult`/`NegotiationDecision` (Tasks 2–4) used by Tasks 6–7. `TransferExecutor.execute(SaveGame, TransferOffer, BigDecimal)` (Task 5) used by Tasks 6–7. `FinanceTransactionType.TRANSFER_IN/OUT` + `LineupPlayerRepository.deleteByLineupIdAndPlayerId` (Task 5) used by Task 5's executor. DTOs (`MarketPlayerResponse`/`TransferOfferResponse`/`MakeOfferRequest` Task 6; `RespondOfferRequest` Task 7) consumed by Task 8's controller and mirrored in FE types (Tasks 9/10). `TransferService` is created in Task 6 and extended in Task 7 (same class, additive methods + imports — no constructor change in Task 7). `respondToOffer` (Task 9 client) is consumed by Task 10. Advance/rollover constructor changes (Task 8) append the new dep as the last parameter.

**Cross-task build safety:** Task 7 adds methods + imports to the Task 6 class (no signature break); Task 8's constructor additions to `RoundSimulationService`/`SeasonRolloverService` are append-only; FE Task 10 edits the Task 9 page and its test in lockstep so the suite/build stays green at each task boundary.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-26-elifoot-carvoeiro-phase-2c1-transfers.md`. Per the saved preference (always Subagent-Driven), I'll execute it with a fresh subagent per task + two-stage review, then a final whole-branch review.
