# Phase 2B — Finances Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every club a budget that moves each round — gate income (home games), per-player wages, sponsorship, stadium upkeep, and end-of-season prize money — with a finances screen + itemized ledger for the managed club.

**Architecture:** A pure `FinanceCalculator` holds every money formula (no RNG, so determinism is untouched). A `FinanceService` orchestrates persistence: it runs each round and at season-end inside the existing advance flow, and serves the managed club's finances to the API. Career creation seeds each club's financial profile and each player's wage; rollover purges the season's ledger while balances persist on the club. Approach A: stored balance per club + a managed-club transaction ledger.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.6.1, Flyway, JPA, PostgreSQL, Testcontainers + JUnit 5 + AssertJ (backend); React 19 + TypeScript (strict) + Vite + TanStack Query + Vitest/RTL (frontend).

## Global Constraints

- **Build env:** default `java` is 8 and aborts Gradle. Every backend command MUST be prefixed: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"`. Backend commands run from `backend/`, frontend from `frontend/`.
- **Money:** `BigDecimal` everywhere on the backend, never float/double. Columns `NUMERIC(15,2)` (balances/amounts) / `NUMERIC(12,2)` (wages/sponsorship/upkeep). `FinanceCalculator` returns `BigDecimal` with scale 2 (`setScale(2, RoundingMode.HALF_UP)`).
- **Determinism:** finances use **no RNG** — gate income is a pure function of stored attributes. Do not introduce `Random` anywhere in the finance code.
- **Layering:** Controller → Service → Repository; constructor injection; records for DTOs; UUID ids assigned in static `create(...)` factories.
- **IDOR:** the finances endpoint calls `saveGameService.requireOwned(saveGameId, userId)` first (foreign → 404); the club must belong to the save else `ApiException("CLUB_NOT_FOUND", ..., HttpStatus.NOT_FOUND)`.
- **Scope:** **all clubs' balances evolve** each round; **only the managed club** records itemized `finance_transaction` rows and has finance UI.
- **Bankruptcy:** negative balances are allowed (no error); the frontend shows a warning when `balance < 0`.
- **Tests:** Given/When/Then, descriptive names, ≥80% coverage. Backend ITs use `@SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)`. Frontend uses RTL with `vi.mock('../../api/career', ...)`.
- **Commits:** every message ends with the trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Per-task commits direct to `main` are authorized for this project.
- **Finance constants (LOCKED — `FinanceCalculator`; initial calibration, may be tuned later but tests assert these exact values):**
  - ticket price `50`; wage base `overall² × 20`; wage age factor `0.60` (age ≤ 20), `0.80` (age ≥ 32), `1.00` otherwise.
  - reputation = round(avg squad overall), clamped `[40, 90]`.
  - stadium capacity = `8000 + reputation × 500`.
  - starting balance = `reputation × 200000`.
  - sponsorship/round = `reputation × 8000`.
  - upkeep/round = `capacity × 4`.
  - gate: `fill = clamp(0.40 + homeRep/200 + awayRep/400, 0.25, 1.0)`; `attendance = min(capacity, round(capacity × fill))`; `gate = attendance × 50`.
  - prize money = `500000 × (clubCount − position + 1)` (position 1-based).
- **Catarinense facts:** 10 clubs, `totalRounds = 18`, `90` fixtures, managed club short name `CRI`. Latest migration `V4` → this slice adds **`V5`**.

## File Structure

**New backend files**
- `db/migration/V5__finances.sql` — club finance columns, player wage column, `finance_transaction` table.
- `finance/FinanceTransaction.java`, `finance/FinanceTransactionType.java`, `finance/FinanceTransactionRepository.java` — ledger entity/enum/repo.
- `finance/FinanceCalculator.java` — pure formulas (`@Component`).
- `finance/FinanceService.java` — orchestration (`@Service`).
- `finance/FinanceController.java` — `GET .../finances`.
- `finance/FinancesResponse.java`, `finance/FinanceTransactionResponse.java`, `finance/FinanceRoundResponse.java` — DTO records.

**Modified backend files**
- `club/Club.java` — add `balance`/`reputation`/`stadiumCapacity`/`sponsorshipPerRound`/`stadiumUpkeepPerRound` + `adjustBalance` + setters.
- `player/Player.java` — add `wage` + `getWage`/`setWage`.
- `career/SaveGameService.java` — seed club financial profiles + player wages at career creation.
- `player/NewgenGenerator.java` — set newgen wage (inject `FinanceCalculator`).
- `match/RoundSimulationService.java` — run `processRound` + season-end `processSeasonEnd`; enrich the response.
- `match/SimulateRoundResponse.java` — add `finance` field.
- `season/SeasonRolloverService.java` — purge the season's `finance_transaction` rows.

**New / modified frontend files**
- `api/types.ts` — `FinancesResponse`, `FinanceTransaction`, `FinanceRoundResponse`; `SimulateRoundResponse` += `finance`.
- `api/career.ts` — `getFinances`.
- `features/finance/FinancesPage.tsx` (+ test) — balance + ledger.
- `features/match/AdvancePanel.tsx` (+ test) — round finance summary.
- `features/career/DashboardPage.tsx` — balance + "Finanças" link.
- `router.tsx` — `/finances` route.

---

### Task 1: V5 migration + finance schema (Club/Player columns, FinanceTransaction)

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__finances.sql`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/club/Club.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionType.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransaction.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionRepository.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceSchemaIT.java`

**Interfaces:**
- Produces:
  - `Club`: `getBalance()/adjustBalance(BigDecimal)/setBalance(BigDecimal)`, `getReputation()/setReputation(int)`, `getStadiumCapacity()/setStadiumCapacity(int)`, `getSponsorshipPerRound()/setSponsorshipPerRound(BigDecimal)`, `getStadiumUpkeepPerRound()/setStadiumUpkeepPerRound(BigDecimal)`.
  - `Player`: `getWage()/setWage(BigDecimal)`.
  - `FinanceTransactionType` enum: `GATE, WAGES, SPONSOR, UPKEEP, PRIZE`.
  - `FinanceTransaction.create(UUID saveGameId, UUID clubId, int seasonYear, int round, FinanceTransactionType type, BigDecimal amount, String description)` + getters + `getId()`.
  - `FinanceTransactionRepository.findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(UUID, UUID)`, `deleteBySaveGameId(UUID)`.

- [ ] **Step 1: Write the migration**

Create `V5__finances.sql` (additive; `finance_transaction.club_id` is a plain UUID — denormalized ledger, no FK):

```sql
ALTER TABLE clubs
  ADD COLUMN balance                  NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN reputation               INT           NOT NULL DEFAULT 0,
  ADD COLUMN stadium_capacity         INT           NOT NULL DEFAULT 0,
  ADD COLUMN sponsorship_per_round    NUMERIC(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN stadium_upkeep_per_round NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE players
  ADD COLUMN wage NUMERIC(12,2) NOT NULL DEFAULT 0;

CREATE TABLE finance_transaction (
    id           UUID PRIMARY KEY,
    save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id      UUID NOT NULL,
    season_year  INT NOT NULL,
    round        INT NOT NULL,
    type         VARCHAR(16) NOT NULL,
    amount       NUMERIC(15,2) NOT NULL,
    description  VARCHAR(120) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_finance_transaction_save_club ON finance_transaction(save_game_id, club_id);
```

- [ ] **Step 2: Write the failing schema round-trip test**

Create `FinanceSchemaIT.java`:

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceSchemaIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    @Test
    void persistsClubBalancePlayerWageAndLedgerRow() {
        // Given a save game with a club, a player, and a finance transaction
        User user = User.create("fin_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2026);
        saveGameRepository.save(save);

        Club club = Club.create(save.getId(), "Test FC", "TST");
        club.setBalance(new BigDecimal("1000000.00"));
        club.setReputation(67);
        club.setStadiumCapacity(41500);
        club.setSponsorshipPerRound(new BigDecimal("536000.00"));
        club.setStadiumUpkeepPerRound(new BigDecimal("166000.00"));
        clubRepository.save(club);

        Player player = Player.create(save.getId(), club.getId(), "Tester", Position.MID,
                25, "Brasil", 60, 55, 70, 65, 10, 72, 66);
        player.setWage(new BigDecimal("84500.00"));
        playerRepository.save(player);

        financeTransactionRepository.save(FinanceTransaction.create(save.getId(), club.getId(),
                2026, 3, FinanceTransactionType.GATE, new BigDecimal("1935000.00"), "Renda de bilheteria"));

        // When reading back
        Club reloaded = clubRepository.findById(club.getId()).orElseThrow();
        Player reloadedPlayer = playerRepository.findById(player.getId()).orElseThrow();
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(save.getId(), club.getId());

        // Then the financial fields round-trip
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1000000.00");
        assertThat(reloaded.getReputation()).isEqualTo(67);
        assertThat(reloaded.getStadiumCapacity()).isEqualTo(41500);
        assertThat(reloaded.getSponsorshipPerRound()).isEqualByComparingTo("536000.00");
        assertThat(reloaded.getStadiumUpkeepPerRound()).isEqualByComparingTo("166000.00");
        assertThat(reloadedPlayer.getWage()).isEqualByComparingTo("84500.00");
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getType()).isEqualTo(FinanceTransactionType.GATE);
        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("1935000.00");

        // adjustBalance mutates by a signed delta
        reloaded.adjustBalance(new BigDecimal("-250000.00"));
        assertThat(reloaded.getBalance()).isEqualByComparingTo("750000.00");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceSchemaIT'`
Expected: COMPILE FAILURE — finance classes and the new getters/setters don't exist.

- [ ] **Step 4: Add the Club financial fields**

In `Club.java`: add `import java.math.BigDecimal;`. Add fields after `secondaryColor`:

```java
    @Column(name = "balance", nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "reputation", nullable = false)
    private int reputation;

    @Column(name = "stadium_capacity", nullable = false)
    private int stadiumCapacity;

    @Column(name = "sponsorship_per_round", nullable = false)
    private BigDecimal sponsorshipPerRound = BigDecimal.ZERO;

    @Column(name = "stadium_upkeep_per_round", nullable = false)
    private BigDecimal stadiumUpkeepPerRound = BigDecimal.ZERO;
```

Add getters/setters after the existing color setters:

```java
    public BigDecimal getBalance() { return balance; }
    public int getReputation() { return reputation; }
    public int getStadiumCapacity() { return stadiumCapacity; }
    public BigDecimal getSponsorshipPerRound() { return sponsorshipPerRound; }
    public BigDecimal getStadiumUpkeepPerRound() { return stadiumUpkeepPerRound; }

    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setReputation(int reputation) { this.reputation = reputation; }
    public void setStadiumCapacity(int stadiumCapacity) { this.stadiumCapacity = stadiumCapacity; }
    public void setSponsorshipPerRound(BigDecimal v) { this.sponsorshipPerRound = v; }
    public void setStadiumUpkeepPerRound(BigDecimal v) { this.stadiumUpkeepPerRound = v; }

    /** Applies a signed delta to the balance (income positive, costs negative). */
    public void adjustBalance(BigDecimal delta) { this.balance = this.balance.add(delta); }
```

- [ ] **Step 5: Add the Player wage field**

In `Player.java`: add `import java.math.BigDecimal;`. Add field after `suspensionRemaining`:

```java
    @Column(name = "wage", nullable = false)
    private BigDecimal wage = BigDecimal.ZERO;
```

Add accessors after `resetSeasonState()`:

```java
    public BigDecimal getWage() { return wage; }
    public void setWage(BigDecimal wage) { this.wage = wage; }
```

- [ ] **Step 6: Create the finance ledger classes**

`FinanceTransactionType.java`:

```java
package br.com.elifootcarvoeiro.finance;

public enum FinanceTransactionType {
    GATE, WAGES, SPONSOR, UPKEEP, PRIZE
}
```

`FinanceTransaction.java`:

```java
package br.com.elifootcarvoeiro.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Itemized finance ledger row (recorded for the managed club only). */
@Entity
@Table(name = "finance_transaction")
public class FinanceTransaction {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "season_year", nullable = false) private int seasonYear;
    @Column(name = "round", nullable = false) private int round;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16) private FinanceTransactionType type;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "description", nullable = false, length = 120) private String description;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected FinanceTransaction() { }

    private FinanceTransaction(UUID id, UUID saveGameId, UUID clubId, int seasonYear, int round,
                              FinanceTransactionType type, BigDecimal amount, String description,
                              Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.clubId = clubId; this.seasonYear = seasonYear;
        this.round = round; this.type = type; this.amount = amount; this.description = description;
        this.createdAt = createdAt;
    }

    public static FinanceTransaction create(UUID saveGameId, UUID clubId, int seasonYear, int round,
                                            FinanceTransactionType type, BigDecimal amount, String description) {
        return new FinanceTransaction(UUID.randomUUID(), saveGameId, clubId, seasonYear, round,
                type, amount, description, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public int getSeasonYear() { return seasonYear; }
    public int getRound() { return round; }
    public FinanceTransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`FinanceTransactionRepository.java`:

```java
package br.com.elifootcarvoeiro.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinanceTransactionRepository extends JpaRepository<FinanceTransaction, UUID> {
    List<FinanceTransaction> findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(UUID saveGameId, UUID clubId);
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceSchemaIT'`
Expected: PASS (Flyway applies V5, all fields round-trip).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V5__finances.sql \
        backend/src/main/java/br/com/elifootcarvoeiro/club/Club.java \
        backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java \
        backend/src/main/java/br/com/elifootcarvoeiro/finance/ \
        backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceSchemaIT.java
git commit -m "$(cat <<'EOF'
feat(finance): V5 schema - club balance/profile, player wage, transaction ledger

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: FinanceCalculator (pure formulas)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceCalculator.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceCalculatorTest.java`

**Interfaces:**
- Consumes: `Player.getOverall()` (for `reputation`).
- Produces: `FinanceCalculator` (`@Component`):
  - `BigDecimal weeklyWage(int overall, int age)`
  - `int reputation(java.util.List<Player> squad)`
  - `int stadiumCapacity(int reputation)`
  - `BigDecimal startingBalance(int reputation)`
  - `BigDecimal sponsorshipPerRound(int reputation)`
  - `BigDecimal upkeepPerRound(int stadiumCapacity)`
  - `BigDecimal gateIncome(int homeReputation, int awayReputation, int stadiumCapacity)`
  - `BigDecimal prizeMoney(int position, int clubCount)`

- [ ] **Step 1: Write the failing test**

`FinanceCalculatorTest.java`:

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCalculatorTest {

    private final FinanceCalculator calc = new FinanceCalculator();

    private Player withOverall(int overall) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "P", Position.MID,
                25, "Brasil", 50, 50, 50, 50, 50, 50, overall);
    }

    @Test
    void weeklyWageRisesWithOverallAndAppliesAgeFactor() {
        // base = overall^2 * 20; prime age factor 1.00
        assertThat(calc.weeklyWage(70, 25)).isEqualByComparingTo("98000.00"); // 4900*20
        // youth (<=20) factor 0.60
        assertThat(calc.weeklyWage(70, 19)).isEqualByComparingTo("58800.00");
        // veteran (>=32) factor 0.80
        assertThat(calc.weeklyWage(70, 33)).isEqualByComparingTo("78400.00");
        // higher overall => strictly higher wage
        assertThat(calc.weeklyWage(80, 25)).isGreaterThan(calc.weeklyWage(70, 25));
    }

    @Test
    void reputationIsClampedSquadAverage() {
        assertThat(calc.reputation(List.of(withOverall(70), withOverall(70), withOverall(60)))).isEqualTo(67);
        // clamp floor 40
        assertThat(calc.reputation(List.of(withOverall(10), withOverall(10)))).isEqualTo(40);
        // clamp ceiling 90
        assertThat(calc.reputation(List.of(withOverall(99), withOverall(99)))).isEqualTo(90);
    }

    @Test
    void stadiumAndProfileFormulas() {
        assertThat(calc.stadiumCapacity(67)).isEqualTo(41500);        // 8000 + 67*500
        assertThat(calc.startingBalance(67)).isEqualByComparingTo("13400000.00"); // 67*200000
        assertThat(calc.sponsorshipPerRound(67)).isEqualByComparingTo("536000.00"); // 67*8000
        assertThat(calc.upkeepPerRound(41500)).isEqualByComparingTo("166000.00");   // 41500*4
    }

    @Test
    void gateIncomeNeverExceedsCapacityAndRisesWithReputation() {
        // fill = 0.40 + 70/200 + 60/400 = 0.90; attendance = round(43000*0.90)=38700; gate=*50
        assertThat(calc.gateIncome(70, 60, 43000)).isEqualByComparingTo("1935000.00");
        // a small stadium caps attendance at capacity: fill would be >1 only via clamp; here capacity binds
        BigDecimalAssertCap(calc, 90, 90, 1000);
        // higher reputations => higher (or equal) gate for the same capacity
        assertThat(calc.gateIncome(80, 70, 50000)).isGreaterThan(calc.gateIncome(50, 50, 50000));
    }

    private static void BigDecimalAssertCap(FinanceCalculator calc, int homeRep, int awayRep, int capacity) {
        // attendance can never exceed capacity, so gate <= capacity * 50
        assertThat(calc.gateIncome(homeRep, awayRep, capacity))
                .isLessThanOrEqualTo(new java.math.BigDecimal(capacity * 50).setScale(2));
    }

    @Test
    void prizeMoneyDecreasesWithPositionAndIsAlwaysPositive() {
        assertThat(calc.prizeMoney(1, 10)).isEqualByComparingTo("5000000.00");  // 500000*10
        assertThat(calc.prizeMoney(10, 10)).isEqualByComparingTo("500000.00");  // 500000*1
        assertThat(calc.prizeMoney(1, 10)).isGreaterThan(calc.prizeMoney(2, 10));
        assertThat(calc.prizeMoney(10, 10).signum()).isPositive();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceCalculatorTest'`
Expected: COMPILE FAILURE — `FinanceCalculator` does not exist.

- [ ] **Step 3: Create FinanceCalculator**

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.player.Player;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, deterministic money formulas (no RNG). All amounts are BigDecimal scaled to 2.
 * Constants are the locked initial calibration (see the plan's Global Constraints).
 */
@Component
public class FinanceCalculator {

    private static final BigDecimal TICKET_PRICE = new BigDecimal("50");
    private static final int WAGE_OVERALL_FACTOR = 20;
    private static final BigDecimal YOUTH_FACTOR = new BigDecimal("0.60");
    private static final BigDecimal VETERAN_FACTOR = new BigDecimal("0.80");
    private static final BigDecimal PRIME_FACTOR = new BigDecimal("1.00");
    private static final int REP_FLOOR = 40;
    private static final int REP_CEILING = 90;
    private static final BigDecimal STARTING_BALANCE_PER_REP = new BigDecimal("200000");
    private static final BigDecimal SPONSORSHIP_PER_REP = new BigDecimal("8000");
    private static final BigDecimal UPKEEP_PER_SEAT = new BigDecimal("4");
    private static final BigDecimal PRIZE_BASE = new BigDecimal("500000");

    public BigDecimal weeklyWage(int overall, int age) {
        BigDecimal base = BigDecimal.valueOf((long) overall * overall * WAGE_OVERALL_FACTOR);
        BigDecimal factor = age <= 20 ? YOUTH_FACTOR : age >= 32 ? VETERAN_FACTOR : PRIME_FACTOR;
        return scaled(base.multiply(factor));
    }

    public int reputation(List<Player> squad) {
        if (squad.isEmpty()) {
            return REP_FLOOR;
        }
        int avg = (int) Math.round(squad.stream().mapToInt(Player::getOverall).average().orElse(0));
        return Math.max(REP_FLOOR, Math.min(REP_CEILING, avg));
    }

    public int stadiumCapacity(int reputation) {
        return 8000 + reputation * 500;
    }

    public BigDecimal startingBalance(int reputation) {
        return scaled(STARTING_BALANCE_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

    public BigDecimal sponsorshipPerRound(int reputation) {
        return scaled(SPONSORSHIP_PER_REP.multiply(BigDecimal.valueOf(reputation)));
    }

    public BigDecimal upkeepPerRound(int stadiumCapacity) {
        return scaled(UPKEEP_PER_SEAT.multiply(BigDecimal.valueOf(stadiumCapacity)));
    }

    public BigDecimal gateIncome(int homeReputation, int awayReputation, int stadiumCapacity) {
        double fill = 0.40 + homeReputation / 200.0 + awayReputation / 400.0;
        fill = Math.max(0.25, Math.min(1.0, fill));
        int attendance = Math.min(stadiumCapacity, (int) Math.round(stadiumCapacity * fill));
        return scaled(TICKET_PRICE.multiply(BigDecimal.valueOf(attendance)));
    }

    public BigDecimal prizeMoney(int position, int clubCount) {
        return scaled(PRIZE_BASE.multiply(BigDecimal.valueOf((long) clubCount - position + 1)));
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceCalculatorTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceCalculator.java \
        backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceCalculatorTest.java
git commit -m "$(cat <<'EOF'
feat(finance): pure deterministic FinanceCalculator (wages, gate, prize, profile)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Seed wages + club financial profiles at career creation (and for newgens)

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/NewgenGenerator.java`
- Modify: `backend/src/test/java/br/com/elifootcarvoeiro/player/NewgenGeneratorTest.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/career/SaveGameServiceIT.java` (extend)

**Interfaces:**
- Consumes: `FinanceCalculator` (Task 2); `Club` setters + `Player.setWage` (Task 1).
- Produces: after `createCareer`, every club has `balance/reputation/stadiumCapacity/sponsorshipPerRound/stadiumUpkeepPerRound > 0` and every player a `wage > 0`; every newgen carries a wage.

- [ ] **Step 1: Extend the career-creation test (add to `SaveGameServiceIT`)**

Add this test method inside `SaveGameServiceIT` (it already autowires `saveGameService`, `userRepository`, `clubRepository`, `playerRepository`):

```java
    @Test
    void creatingCareerSeedsClubFinancialProfilesAndPlayerWages() {
        // Given a persisted user
        User user = User.create("tigre_finance", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then every club has a populated financial profile
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).isNotEmpty();
        clubs.forEach(club -> {
            assertThat(club.getReputation()).as("reputation").isGreaterThan(0);
            assertThat(club.getStadiumCapacity()).as("capacity").isGreaterThan(0);
            assertThat(club.getBalance()).as("balance").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getSponsorshipPerRound()).as("sponsorship").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getStadiumUpkeepPerRound()).as("upkeep").isGreaterThan(java.math.BigDecimal.ZERO);
        });

        // And every player has a positive wage
        assertThat(playerRepository.findBySaveGameId(saveGameId))
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getWage()).isGreaterThan(java.math.BigDecimal.ZERO));
    }
```

(If `SaveGameServiceIT` does not already `import java.util.UUID;`, it does — the existing test uses it. No new imports needed beyond inline `java.math.BigDecimal`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.career.SaveGameServiceIT'`
Expected: FAIL — reputation/balance/wage are still 0 (defaults).

- [ ] **Step 3: Wire FinanceCalculator into createCareer**

In `SaveGameService.java`:
1. Add import: `import br.com.elifootcarvoeiro.finance.FinanceCalculator;` and `import java.util.ArrayList;` (already imported).
2. Add field + constructor param (append as the **last** parameter):

```java
    private final FinanceCalculator financeCalculator;
```
```java
                           FixtureRepository fixtureRepository,
                           OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator) {
```
```java
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
```

3. In the club/player creation loop, collect each club's players to compute reputation, set each player's wage, and set the club profile. Replace the inner club loop body (the `for (SeedClub seedClub : seedClubs)` block, currently lines ~78–105) so it reads:

```java
        for (SeedClub seedClub : seedClubs) {
            Club club = Club.create(saveGame.getId(), seedClub.name(), seedClub.shortName());
            club.setNickname(seedClub.nickname());
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
            clubRepository.save(club);

            clubIds.add(club.getId());

            if (MANAGED_CLUB_SHORT_NAME.equals(seedClub.shortName())) {
                managedClubId = club.getId();
                managedClubName = club.getName();
            }

            List<Player> squad = new ArrayList<>();
            for (SeedPlayer seedPlayer : seedClub.players()) {
                Position position = Position.valueOf(seedPlayer.position());
                int overall = overallCalculator.compute(position,
                        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina());
                Player player = Player.create(
                        saveGame.getId(), club.getId(), seedPlayer.name(), position,
                        seedPlayer.age(), seedPlayer.nationality(),
                        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(),
                        overall);
                player.setWage(financeCalculator.weeklyWage(overall, seedPlayer.age()));
                playerRepository.save(player);
                squad.add(player);
            }

            // Financial profile derived from squad strength.
            int reputation = financeCalculator.reputation(squad);
            int capacity = financeCalculator.stadiumCapacity(reputation);
            club.setReputation(reputation);
            club.setStadiumCapacity(capacity);
            club.setBalance(financeCalculator.startingBalance(reputation));
            club.setSponsorshipPerRound(financeCalculator.sponsorshipPerRound(reputation));
            club.setStadiumUpkeepPerRound(financeCalculator.upkeepPerRound(capacity));
            clubRepository.save(club);
        }
```

(Add `import br.com.elifootcarvoeiro.player.Player;` — already imported.)

- [ ] **Step 4: Run the career test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.career.SaveGameServiceIT'`
Expected: PASS (profiles + wages populated; existing career tests still green).

- [ ] **Step 5: Set newgen wages — write the failing test addition**

In `NewgenGeneratorTest.java`, the calculator is constructed as `new NewgenGenerator(new SeedLoader(), overallCalculator)`. Update that construction to `new NewgenGenerator(new SeedLoader(), overallCalculator, new FinanceCalculator())` (add `import br.com.elifootcarvoeiro.finance.FinanceCalculator;`), and add a test:

```java
    @Test
    void newgensReceiveAPositiveWage() {
        Player p = generator.generate(UUID.randomUUID(), UUID.randomUUID(), Position.ATT, new Random(5));
        assertThat(p.getWage()).isGreaterThan(java.math.BigDecimal.ZERO);
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.player.NewgenGeneratorTest'`
Expected: COMPILE FAILURE — `NewgenGenerator` has no 3-arg constructor.

- [ ] **Step 7: Inject FinanceCalculator into NewgenGenerator and set the wage**

In `NewgenGenerator.java`:
1. Add import: `import br.com.elifootcarvoeiro.finance.FinanceCalculator;`.
2. Add a field and a third constructor parameter:

```java
    private final FinanceCalculator financeCalculator;

    public NewgenGenerator(SeedLoader seedLoader, OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator) {
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }
```

3. In `generate(...)`, set the wage before returning. Replace the final `return Player.create(...)` with:

```java
        Player newgen = Player.create(saveGameId, clubId, name, position, age, "Brasil",
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
        newgen.setWage(financeCalculator.weeklyWage(overall, age));
        return newgen;
```

- [ ] **Step 8: Run the newgen test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.player.NewgenGeneratorTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java \
        backend/src/main/java/br/com/elifootcarvoeiro/player/NewgenGenerator.java \
        backend/src/test/java/br/com/elifootcarvoeiro/player/NewgenGeneratorTest.java \
        backend/src/test/java/br/com/elifootcarvoeiro/career/SaveGameServiceIT.java
git commit -m "$(cat <<'EOF'
feat(finance): seed club financial profiles + player/newgen wages at creation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: FinanceService + finances endpoint + DTOs

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceRoundResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinancesResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceService.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceController.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceServiceIT.java`

**Interfaces:**
- Consumes: `FinanceCalculator` (Task 2); `Club.adjustBalance`/profile getters (Task 1); `FinanceTransaction`/`FinanceTransactionRepository` (Task 1); `SaveGameRepository`, `SaveGameService.requireOwned`, `ClubRepository.findBySaveGameId`, `PlayerRepository.findBySaveGameId`, `FixtureRepository`, `StandingsCalculator.compute`.
- Produces:
  - `FinanceRoundResponse(BigDecimal gate, BigDecimal sponsorship, BigDecimal wages, BigDecimal upkeep, BigDecimal prize, BigDecimal net, BigDecimal balance)` — managed-club magnitudes (gate/sponsorship/wages/upkeep/prize ≥ 0; net signed; balance the new balance).
  - `FinanceTransactionResponse(int seasonYear, int round, String type, BigDecimal amount, String description)`.
  - `FinancesResponse(BigDecimal balance, List<FinanceTransactionResponse> transactions)`.
  - `FinanceService`:
    - `FinanceRoundResponse processRound(UUID saveGameId, int round, List<Fixture> roundFixtures)` — applies per-round finances to ALL clubs; writes itemized rows for the managed club; returns the managed club's breakdown (`prize` = 0).
    - `BigDecimal processSeasonEnd(UUID saveGameId)` — awards prize money to all clubs by final position; managed gets a `PRIZE` row; returns the managed club's prize.
    - `FinancesResponse getFinances(UUID saveGameId, UUID clubId, UUID userId)` — owner + club-belongs guarded.

- [ ] **Step 1: Write the DTOs**

`FinanceRoundResponse.java`:

```java
package br.com.elifootcarvoeiro.finance;

import java.math.BigDecimal;

public record FinanceRoundResponse(
        BigDecimal gate,
        BigDecimal sponsorship,
        BigDecimal wages,
        BigDecimal upkeep,
        BigDecimal prize,
        BigDecimal net,
        BigDecimal balance
) {}
```

`FinanceTransactionResponse.java`:

```java
package br.com.elifootcarvoeiro.finance;

import java.math.BigDecimal;

public record FinanceTransactionResponse(
        int seasonYear,
        int round,
        String type,
        BigDecimal amount,
        String description
) {}
```

`FinancesResponse.java`:

```java
package br.com.elifootcarvoeiro.finance;

import java.math.BigDecimal;
import java.util.List;

public record FinancesResponse(BigDecimal balance, List<FinanceTransactionResponse> transactions) {}
```

- [ ] **Step 2: Write the failing test**

`FinanceServiceIT.java`:

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceServiceIT {

    @Autowired FinanceService financeService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void processRoundMovesEveryClubBalanceAndItemizesTheManagedClub() {
        // Given a fresh career
        Career c = createCareer();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        List<Fixture> round0 = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competition.getId(), 0);
        Map<UUID, BigDecimal> before = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Club::getBalance));

        // When the round's finances are processed
        FinanceRoundResponse managed = financeService.processRound(c.id(), 0, round0);

        // Then every club's balance changed (income/wages/upkeep applied)
        Map<UUID, BigDecimal> after = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getBalance()));
        after.forEach((id, bal) -> assertThat(bal).as("club %s balance moved", id)
                .isNotEqualByComparingTo(before.get(id)));

        // And the managed club has itemized rows for round 0 (SPONSOR + WAGES + UPKEEP, plus GATE if home)
        boolean managedIsHome = round0.stream().anyMatch(f -> f.getHomeClubId().equals(c.clubId()));
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        assertThat(ledger).extracting(t -> t.getType().name())
                .contains("SPONSOR", "WAGES", "UPKEEP");
        assertThat(ledger.stream().anyMatch(t -> t.getType() == FinanceTransactionType.GATE))
                .isEqualTo(managedIsHome);
        // WAGES and UPKEEP are recorded as negative amounts; SPONSOR positive
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.WAGES)
                .findFirst().orElseThrow().getAmount().signum()).isNegative();
        assertThat(ledger.stream().filter(t -> t.getType() == FinanceTransactionType.SPONSOR)
                .findFirst().orElseThrow().getAmount().signum()).isPositive();
        // The returned breakdown's net equals gate + sponsorship - wages - upkeep
        assertThat(managed.net()).isEqualByComparingTo(
                managed.gate().add(managed.sponsorship()).subtract(managed.wages()).subtract(managed.upkeep()));
    }

    @Test
    void processSeasonEndAwardsPrizeMoney() {
        // Given a fully-played season
        Career c = createCareer();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        BigDecimal managedBalanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        // When season-end finances run
        BigDecimal prize = financeService.processSeasonEnd(c.id());

        // Then the managed club received prize money and has a PRIZE row
        assertThat(prize).isGreaterThan(BigDecimal.ZERO);
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(managedBalanceBefore.add(prize));
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.PRIZE);
    }

    @Test
    void getFinancesReturnsBalanceAndTransactionsForOwner() {
        Career c = createCareer();
        financeService.processRound(c.id(), 0, fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competitionRepository.findBySaveGameId(c.id()).get(0).getId(), 0));

        FinancesResponse finances = financeService.getFinances(c.id(), c.clubId(), c.userId());
        assertThat(finances.balance()).isGreaterThan(BigDecimal.ZERO);
        assertThat(finances.transactions()).isNotEmpty();
    }

    @Test
    void getFinancesRejectsForeignUserAndUnknownClub() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> financeService.getFinances(owner.id(), owner.clubId(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> financeService.getFinances(owner.id(), UUID.randomUUID(), owner.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("CLUB_NOT_FOUND");
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceServiceIT'`
Expected: COMPILE FAILURE — `FinanceService` does not exist.

- [ ] **Step 4: Create FinanceService**

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs club finances each round and at season end, and serves the managed club's
 * finances to the API. Deterministic (delegates all amounts to FinanceCalculator).
 * Itemized ledger rows are written ONLY for the managed club.
 */
@Service
public class FinanceService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final FinanceCalculator financeCalculator;
    private final StandingsCalculator standingsCalculator = new StandingsCalculator();

    public FinanceService(SaveGameService saveGameService,
                          SaveGameRepository saveGameRepository,
                          ClubRepository clubRepository,
                          PlayerRepository playerRepository,
                          CompetitionRepository competitionRepository,
                          FixtureRepository fixtureRepository,
                          FinanceTransactionRepository financeTransactionRepository,
                          FinanceCalculator financeCalculator) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.financeCalculator = financeCalculator;
    }

    public FinanceRoundResponse processRound(UUID saveGameId, int round, List<Fixture> roundFixtures) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Map<UUID, Club> clubById = clubs.stream().collect(Collectors.toMap(Club::getId, c -> c));
        Map<UUID, BigDecimal> wageBillByClub = wageBills(saveGameId);

        // Map each home club to its opponent (for gate attendance).
        Map<UUID, UUID> opponentOfHome = new HashMap<>();
        for (Fixture f : roundFixtures) {
            opponentOfHome.put(f.getHomeClubId(), f.getAwayClubId());
        }

        FinanceRoundResponse managedBreakdown = null;
        for (Club club : clubs) {
            BigDecimal wages = wageBillByClub.getOrDefault(club.getId(), BigDecimal.ZERO);
            BigDecimal sponsorship = club.getSponsorshipPerRound();
            BigDecimal upkeep = club.getStadiumUpkeepPerRound();
            BigDecimal gate = BigDecimal.ZERO;
            if (opponentOfHome.containsKey(club.getId())) {
                Club away = clubById.get(opponentOfHome.get(club.getId()));
                int awayRep = away != null ? away.getReputation() : 0;
                gate = financeCalculator.gateIncome(club.getReputation(), awayRep, club.getStadiumCapacity());
            }
            BigDecimal net = gate.add(sponsorship).subtract(wages).subtract(upkeep);
            club.adjustBalance(net);

            if (club.getId().equals(managedClubId)) {
                if (gate.signum() > 0) {
                    record(saveGameId, managedClubId, seasonYear, round,
                            FinanceTransactionType.GATE, gate, "Renda de bilheteria");
                }
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.WAGES, wages.negate(), "Salários");
                record(saveGameId, managedClubId, seasonYear, round,
                        FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção do estádio");
                managedBreakdown = new FinanceRoundResponse(gate, sponsorship, wages, upkeep,
                        BigDecimal.ZERO, net, club.getBalance());
            }
        }
        clubRepository.saveAll(clubs);

        if (managedBreakdown == null) { // managed club had no fixture nor profile (defensive)
            BigDecimal balance = clubById.containsKey(managedClubId)
                    ? clubById.get(managedClubId).getBalance() : BigDecimal.ZERO;
            managedBreakdown = new FinanceRoundResponse(BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, balance);
        }
        return managedBreakdown;
    }

    public BigDecimal processSeasonEnd(UUID saveGameId) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId).orElseThrow();
        final UUID managedClubId = saveGame.getManagedClubId();
        final int seasonYear = saveGame.getSeasonYear();

        List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> played = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId());
        List<StandingRow> table = standingsCalculator.compute(clubs, played);

        Map<UUID, Club> clubById = clubs.stream().collect(Collectors.toMap(Club::getId, c -> c));
        BigDecimal managedPrize = BigDecimal.ZERO;
        int position = 1;
        for (StandingRow row : table) {
            BigDecimal prize = financeCalculator.prizeMoney(position, clubs.size());
            Club club = clubById.get(row.clubId());
            if (club != null) {
                club.adjustBalance(prize);
                if (club.getId().equals(managedClubId)) {
                    managedPrize = prize;
                    record(saveGameId, managedClubId, seasonYear, competition.getTotalRounds(),
                            FinanceTransactionType.PRIZE, prize, "Premiação - " + position + "º lugar");
                }
            }
            position++;
        }
        clubRepository.saveAll(clubs);
        return managedPrize;
    }

    public FinancesResponse getFinances(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Club club = clubRepository.findById(clubId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException("CLUB_NOT_FOUND", "Club not found", HttpStatus.NOT_FOUND));

        List<FinanceTransactionResponse> transactions = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(saveGameId, clubId).stream()
                .map(t -> new FinanceTransactionResponse(t.getSeasonYear(), t.getRound(),
                        t.getType().name(), t.getAmount(), t.getDescription()))
                .toList();
        return new FinancesResponse(club.getBalance(), transactions);
    }

    private Map<UUID, BigDecimal> wageBills(UUID saveGameId) {
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.groupingBy(Player::getClubId,
                        Collectors.reducing(BigDecimal.ZERO, Player::getWage, BigDecimal::add)));
    }

    private void record(UUID saveGameId, UUID clubId, int seasonYear, int round,
                        FinanceTransactionType type, BigDecimal amount, String description) {
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGameId, clubId, seasonYear, round, type, amount, description));
    }
}
```

- [ ] **Step 5: Create FinanceController**

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/finances")
public class FinanceController {

    private final FinanceService financeService;

    public FinanceController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public ResponseEntity<FinancesResponse> getFinances(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(financeService.getFinances(saveGameId, clubId, userId));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceServiceIT'`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceRoundResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceTransactionResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/finance/FinancesResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceService.java \
        backend/src/main/java/br/com/elifootcarvoeiro/finance/FinanceController.java \
        backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceServiceIT.java
git commit -m "$(cat <<'EOF'
feat(finance): FinanceService (round/season-end/getFinances) + finances endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Hook finances into the advance flow

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/SimulateRoundResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceAdvanceIT.java`

**Interfaces:**
- Consumes: `FinanceService.processRound`/`processSeasonEnd` + `FinanceRoundResponse` (Task 4).
- Produces: `SimulateRoundResponse` gains a trailing `FinanceRoundResponse finance` field. Advancing applies per-round finances (and season-end prize when the season finishes) and returns the managed club's breakdown.

- [ ] **Step 1: Write the failing test**

`FinanceAdvanceIT.java`:

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.match.SimulateRoundResponse;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceAdvanceIT {

    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void advancingReturnsManagedFinanceBreakdownAndMovesTheBalance() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing one round
        SimulateRoundResponse response = roundSimulationService.simulateNextRound(c.id(), c.userId());

        // Then the response carries the managed finance breakdown matching the DB balance
        assertThat(response.finance()).isNotNull();
        assertThat(response.finance().net()).isEqualByComparingTo(
                response.finance().gate().add(response.finance().sponsorship())
                        .subtract(response.finance().wages()).subtract(response.finance().upkeep()));
        assertThat(response.finance().prize()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
    }

    @Test
    void theSeasonFinishingAdvanceAwardsPrizeInTheBreakdown() {
        // Given a career advanced to the final round
        Career c = createCareer();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        SimulateRoundResponse last = null;
        for (int i = 0; i < totalRounds; i++) {
            last = roundSimulationService.simulateNextRound(c.id(), c.userId());
        }

        // Then the final advance reports the season finished, prize money, and the post-prize balance
        assertThat(last).isNotNull();
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(BigDecimal.ZERO);
        assertThat(last.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceAdvanceIT'`
Expected: COMPILE FAILURE — `SimulateRoundResponse` has no `finance()` accessor.

- [ ] **Step 3: Add the field to SimulateRoundResponse**

Rewrite `SimulateRoundResponse.java`:

```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;

import java.util.List;

public record SimulateRoundResponse(
        int round,
        List<FixtureResultResponse> results,
        boolean seasonFinished,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions,
        FinanceRoundResponse finance
) {
}
```

- [ ] **Step 4: Wire FinanceService into RoundSimulationService**

In `RoundSimulationService.java`:
1. Add imports: `import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;`, `import br.com.elifootcarvoeiro.finance.FinanceService;`, `import java.math.BigDecimal;`.
2. Add the field and constructor parameter (append `FinanceService financeService` as the **last** constructor parameter; assign `this.financeService = financeService;`):

```java
    private final FinanceService financeService;
```

3. Replace the final `return new SimulateRoundResponse(...)` block (currently lines ~130–132) with:

```java
        FinanceRoundResponse roundFinance = financeService.processRound(saveGameId, nextRound, fixtures);
        BigDecimal prize = BigDecimal.ZERO;
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            prize = financeService.processSeasonEnd(saveGameId);
        }
        FinanceRoundResponse finance = new FinanceRoundResponse(
                roundFinance.gate(), roundFinance.sponsorship(), roundFinance.wages(),
                roundFinance.upkeep(), prize, roundFinance.net(), roundFinance.balance().add(prize));

        return new SimulateRoundResponse(newCurrentRound, List.copyOf(results),
                saveGame.getStatus() == SaveGameStatus.FINISHED,
                List.copyOf(summary.bookings()), List.copyOf(summary.newSuspensions()),
                finance);
```

(`nextRound` and `fixtures` are already in scope from earlier in the method.)

- [ ] **Step 5: Run the finance-advance test + the existing round test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceAdvanceIT' --tests 'br.com.elifootcarvoeiro.match.RoundSimulationServiceIT'`
Expected: PASS. (`RoundSimulationServiceIT` ignores the new field — it never constructs `SimulateRoundResponse` directly.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/match/SimulateRoundResponse.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceAdvanceIT.java
git commit -m "$(cat <<'EOF'
feat(finance): run per-round + season-end finances during advance; expose breakdown

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Purge the season ledger on rollover

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceRolloverIT.java`

**Interfaces:**
- Consumes: `FinanceTransactionRepository.deleteBySaveGameId` (Task 1); `SeasonRolloverService.rollover`.
- Produces: after rollover, the save's `finance_transaction` rows are gone and club balances are carried forward unchanged by the purge.

- [ ] **Step 1: Write the failing test**

`FinanceRolloverIT.java`:

```java
package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.season.SeasonRolloverService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceRolloverIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void rolloverPurgesTheSeasonLedgerAndCarriesBalanceForward() {
        // Given a fully-played season (which accrued managed-club ledger rows)
        Career c = createCareer();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .as("ledger accrued during the season").isNotEmpty();
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        // When the season rolls over
        rolloverService.rollover(c.id(), c.userId());

        // Then the ledger is purged and the balance carried forward unchanged
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId())).isEmpty();
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceRolloverIT'`
Expected: FAIL — after rollover the ledger still contains the season's rows (no purge yet).

- [ ] **Step 3: Add the purge to SeasonRolloverService**

In `SeasonRolloverService.java`:
1. Add import: `import br.com.elifootcarvoeiro.finance.FinanceTransactionRepository;`.
2. Add the field + constructor parameter (append `FinanceTransactionRepository financeTransactionRepository` as the **last** constructor parameter; assign it):

```java
    private final FinanceTransactionRepository financeTransactionRepository;
```

3. In `rollover(...)`, after the fixtures purge `fixtureRepository.flush();` line (currently line ~135), add:

```java
        // Purge the finished season's finance ledger (balances persist on the club).
        financeTransactionRepository.deleteBySaveGameId(saveGameId);
```

- [ ] **Step 4: Run the test + the existing rollover test to verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.elifootcarvoeiro.finance.FinanceRolloverIT' --tests 'br.com.elifootcarvoeiro.season.SeasonRolloverServiceIT'`
Expected: PASS.

- [ ] **Step 5: Run the full backend suite (no regressions)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test`
Expected: PASS (all prior + new tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/finance/FinanceRolloverIT.java
git commit -m "$(cat <<'EOF'
feat(finance): purge season finance ledger on rollover (balance carries forward)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Frontend — finances screen + dashboard balance/link

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Create: `frontend/src/lib/money.ts`
- Create: `frontend/src/features/finance/FinancesPage.tsx`
- Create: `frontend/src/features/finance/FinancesPage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/features/career/DashboardPage.tsx`
- Modify: `frontend/src/features/career/DashboardPage.test.tsx`

**Interfaces:**
- Consumes: backend `GET /save-games/{s}/clubs/{c}/finances` (Task 4).
- Produces (types): `FinanceTransaction`, `FinancesResponse`. (client): `getFinances(saveGameId, clubId, token)`. (util): `formatBRL(value)`. Route `/finances`.

- [ ] **Step 1: Add the types**

Append to `frontend/src/api/types.ts`:

```ts
export type FinanceTransaction = {
  seasonYear: number
  round: number
  type: 'GATE' | 'WAGES' | 'SPONSOR' | 'UPKEEP' | 'PRIZE'
  amount: number
  description: string
}

export type FinancesResponse = {
  balance: number
  transactions: FinanceTransaction[]
}
```

- [ ] **Step 2: Add the client function**

In `frontend/src/api/career.ts`, add `FinancesResponse` to the type import block, then append:

```ts
export function getFinances(
  saveGameId: string,
  clubId: string,
  token: string,
): Promise<FinancesResponse> {
  return apiGet<FinancesResponse>(`/save-games/${saveGameId}/clubs/${clubId}/finances`, token)
}
```

- [ ] **Step 3: Add the money formatter**

Create `frontend/src/lib/money.ts`:

```ts
export function formatBRL(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value)
}
```

- [ ] **Step 4: Write the failing FinancesPage test**

`frontend/src/features/finance/FinancesPage.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FinancesPage } from './FinancesPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({ getFinances: vi.fn() }))
import { getFinances } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/finances?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <FinancesPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('FinancesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders the balance and transactions', async () => {
    vi.mocked(getFinances).mockResolvedValue({
      balance: 12500000,
      transactions: [
        { seasonYear: 2026, round: 3, type: 'GATE', amount: 1935000, description: 'Renda de bilheteria' },
        { seasonYear: 2026, round: 3, type: 'WAGES', amount: -1352000, description: 'Salários' },
      ],
    })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(screen.getByText('Renda de bilheteria')).toBeInTheDocument()
    expect(screen.getByText('Bilheteria')).toBeInTheDocument() // GATE type label cell
  })

  it('shows a warning when the balance is negative', async () => {
    vi.mocked(getFinances).mockResolvedValue({ balance: -500000, transactions: [] })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent(/negativo/i)
  })
})
```

- [ ] **Step 5: Run it to verify it fails**

Run (from `frontend/`): `npm run test -- FinancesPage`
Expected: FAIL — `FinancesPage` module does not exist.

- [ ] **Step 6: Create FinancesPage**

`frontend/src/features/finance/FinancesPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getFinances } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'

const TYPE_LABELS: Record<string, string> = {
  GATE: 'Bilheteria',
  WAGES: 'Salários',
  SPONSOR: 'Patrocínio',
  UPKEEP: 'Manutenção',
  PRIZE: 'Premiação',
}

export function FinancesPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['finances', saveGameId, clubId],
    queryFn: () => getFinances(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  if (isLoading) return <p>Carregando finanças…</p>
  if (error) return <p>Erro ao carregar finanças.</p>

  const balance = data?.balance ?? 0
  const transactions = data?.transactions ?? []
  const negative = balance < 0

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Finanças</h2>
      <p style={{ fontSize: '1.25rem', fontWeight: 'bold', color: negative ? 'red' : undefined }}>
        Saldo: {formatBRL(balance)}
      </p>
      {negative && (
        <p role="alert" style={{ color: 'red' }}>
          Atenção: o clube está com saldo negativo.
        </p>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'center' }}>Rodada</th>
            <th style={{ textAlign: 'left' }}>Tipo</th>
            <th style={{ textAlign: 'left' }}>Descrição</th>
            <th style={{ textAlign: 'right' }}>Valor</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((t, i) => (
            <tr key={`${t.round}-${t.type}-${i}`}>
              <td style={{ textAlign: 'center' }}>{t.round}</td>
              <td>{TYPE_LABELS[t.type] ?? t.type}</td>
              <td>{t.description}</td>
              <td style={{ textAlign: 'right', color: t.amount < 0 ? 'red' : 'green' }}>
                {formatBRL(t.amount)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {transactions.length === 0 && <p>Nenhuma transação ainda.</p>}
    </div>
  )
}
```

- [ ] **Step 7: Add the route**

In `frontend/src/router.tsx`: import `FinancesPage` and add a protected route:

```tsx
import { FinancesPage } from './features/finance/FinancesPage'
```

```tsx
      <Route
        path="/finances"
        element={
          <ProtectedRoute>
            <FinancesPage />
          </ProtectedRoute>
        }
      />
```

- [ ] **Step 8: Add the balance + link to the dashboard**

In `frontend/src/features/career/DashboardPage.tsx`:
1. Add imports: change `import { createCareer, getSaveGame } from '../../api/career'` to `import { createCareer, getSaveGame, getFinances } from '../../api/career'`; add `import { formatBRL } from '../../lib/money'`.
2. After the existing `useQuery` for `saveGame`, add a finances query:

```tsx
  const { data: finances } = useQuery({
    queryKey: ['finances', saveGame?.id, saveGame?.managedClubId],
    queryFn: () => getFinances(saveGame!.id, saveGame!.managedClubId, token ?? ''),
    enabled: Boolean(saveGame && token),
  })
```

3. After the `<p><strong>Rodada:</strong> ...</p>` line, add a balance line:

```tsx
            {finances && (
              <p style={{ color: finances.balance < 0 ? 'red' : undefined }}>
                <strong>Saldo:</strong> {formatBRL(finances.balance)}
              </p>
            )}
```

4. In the button row (the `display: flex` div), add a Finanças link after the Histórico link:

```tsx
              <Link to={`/finances?saveGameId=${saveGame.id}&clubId=${saveGame.managedClubId}`}>
                <button className="cec-btn">Finanças</button>
              </Link>
```

- [ ] **Step 9: Update the dashboard test for the new finances query**

In `frontend/src/features/career/DashboardPage.test.tsx`:
1. Add `getFinances` to the mock factory and the import:

```tsx
vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  createCareer: vi.fn(),
  getFinances: vi.fn(),
}))
```
```tsx
import { getSaveGame, getFinances } from '../../api/career'
```

2. In the `renders the career when the stored save game loads` test, after the `getSaveGame` mock, add a finances mock and assert the balance renders:

```tsx
    ;(getFinances as Mock).mockResolvedValue({ balance: 12500000, transactions: [] })
```
```tsx
    await waitFor(() => {
      expect(screen.getByText(/Criciúma Esporte Clube/)).toBeInTheDocument()
    })
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
```

- [ ] **Step 10: Run the finance + dashboard tests and build**

Run (from `frontend/`):
- `npm run test -- FinancesPage DashboardPage`
- `npm run build`

Expected: tests PASS; `tsc -b && vite build` clean.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts frontend/src/lib/money.ts \
        frontend/src/features/finance/FinancesPage.tsx \
        frontend/src/features/finance/FinancesPage.test.tsx \
        frontend/src/router.tsx \
        frontend/src/features/career/DashboardPage.tsx \
        frontend/src/features/career/DashboardPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(ui): finances screen + dashboard balance and link

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Frontend — round finance summary in the advance panel

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/features/match/AdvancePanel.tsx`
- Modify: `frontend/src/features/match/AdvancePanel.test.tsx`

**Interfaces:**
- Consumes: the enriched `SimulateRoundResponse.finance` from the backend (Task 5); `formatBRL` (Task 7).
- Produces (types): `FinanceRoundResponse`; `SimulateRoundResponse` += `finance`.

- [ ] **Step 1: Add the type + extend SimulateRoundResponse**

In `frontend/src/api/types.ts`, add `FinanceRoundResponse` and add a `finance` field to `SimulateRoundResponse`:

```ts
export type FinanceRoundResponse = {
  gate: number
  sponsorship: number
  wages: number
  upkeep: number
  prize: number
  net: number
  balance: number
}
```

Change the existing `SimulateRoundResponse` to include `finance`:

```ts
export type SimulateRoundResponse = {
  round: number
  results: FixtureResultResponse[]
  seasonFinished: boolean
  bookings: Booking[]
  newSuspensions: Suspension[]
  finance: FinanceRoundResponse
}
```

- [ ] **Step 2: Update the AdvancePanel test (add finance to mocks + a new assertion)**

In `frontend/src/features/match/AdvancePanel.test.tsx`:
1. Define a reusable finance fixture near the top of the `describe` (or inline). Add `finance` to BOTH existing `mockResolvedValue` objects so they satisfy the type, e.g.:

```ts
const FINANCE = { gate: 1935000, sponsorship: 560000, wages: 1352000, upkeep: 172000, prize: 0, net: 971000, balance: 12500000 }
```
- In the `shows bookings and suspensions after advancing` test object add `finance: FINANCE,`.
- In the `links to the season summary when the season finishes` test object add `finance: { ...FINANCE, prize: 5000000, balance: 17500000 },`.

2. Add a new test:

```tsx
  it('shows the round finance summary after advancing', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 4, results: [], seasonFinished: false, bookings: [], newSuspensions: [],
      finance: { gate: 1935000, sponsorship: 560000, wages: 1352000, upkeep: 172000, prize: 0, net: 971000, balance: 12500000 },
    })
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))
    await waitFor(() => expect(screen.getByText(/Finanças da rodada/i)).toBeInTheDocument())
    expect(screen.getByText(/Saldo:/)).toBeInTheDocument()
  })
```

- [ ] **Step 3: Run it to verify it fails**

Run (from `frontend/`): `npm run test -- AdvancePanel`
Expected: FAIL — AdvancePanel renders no "Finanças da rodada" section yet.

- [ ] **Step 4: Render the finance summary in AdvancePanel**

In `frontend/src/features/match/AdvancePanel.tsx`, add `import { formatBRL } from '../../lib/money'`. Inside the `{result && (...)}` block (after the suspensions section, before its closing `</div>`), add:

```tsx
          {result.finance && (
            <div style={{ marginTop: '0.75rem' }}>
              <h4>Finanças da rodada</h4>
              <ul>
                <li>Bilheteria: {formatBRL(result.finance.gate)}</li>
                <li>Patrocínio: {formatBRL(result.finance.sponsorship)}</li>
                <li>Salários: −{formatBRL(result.finance.wages)}</li>
                <li>Manutenção: −{formatBRL(result.finance.upkeep)}</li>
                {result.finance.prize > 0 && <li>Premiação: {formatBRL(result.finance.prize)}</li>}
                <li><strong>Resultado:</strong> {formatBRL(result.finance.net + result.finance.prize)}</li>
                <li><strong>Saldo:</strong> {formatBRL(result.finance.balance)}</li>
              </ul>
            </div>
          )}
```

- [ ] **Step 5: Run the AdvancePanel test + full suite + build**

Run (from `frontend/`):
- `npm run test -- AdvancePanel`
- `npm run test`
- `npm run build`

Expected: all PASS; build clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts \
        frontend/src/features/match/AdvancePanel.tsx \
        frontend/src/features/match/AdvancePanel.test.tsx
git commit -m "$(cat <<'EOF'
feat(ui): show round finance summary (gate/wages/prize/balance) in advance panel

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

**Spec coverage** (against `...-phase-2b-finances-design.md`):
- §5 V5 schema → Task 1. §6 Club/Player fields → Task 1. §7 FinanceCalculator → Task 2. §8 FinanceService (processRound/processSeasonEnd/getFinances) → Task 4; createCareer/newgen seeding → Task 3. §9 hooks: createCareer/newgen → Task 3, advance → Task 5, rollover purge → Task 6. §10 API: finances endpoint → Task 4, SimulateRoundResponse.finance → Task 5 (backend) + Task 8 (frontend). §11 frontend: FinancesPage + dashboard → Task 7, AdvancePanel → Task 8. §12 errors (`CLUB_NOT_FOUND` 404, IDOR 404, negative-allowed) → Task 4 (+ UI warning Task 7). §13 testing → every task is TDD; coverage matches the spec's test list. No spec requirement is left without a task.

**Placeholder scan:** no TBD/"handle errors"/uncoded steps; every code step shows full code. The dashboard/AdvancePanel test edits reference the current file contents shown in the plan and add exact snippets.

**Type consistency:** `FinanceTransactionType`/`FinanceTransaction`/`FinanceTransactionRepository` (Task 1) consumed by Tasks 4/6. `FinanceCalculator` (Task 2) consumed by Tasks 3/4. `Club` finance getters/setters + `Player.setWage` (Task 1) consumed by Tasks 3/4. `FinanceRoundResponse`/`FinancesResponse`/`FinanceTransactionResponse` (Task 4) consumed by Task 5 (backend) and mirrored in FE types (Tasks 7/8). The backend `SimulateRoundResponse += finance` (Task 5) is mirrored on the FE in Task 8 — and the FE type change is co-located with its only consumers (`AdvancePanel` + its test) in Task 8, so no task leaves the FE build broken. `getFinances` (Task 7) matches `FinanceController`'s route (Task 4). `formatBRL` (Task 7) reused by Task 8.

**Cross-task build safety note:** the FE `SimulateRoundResponse` gains `finance` only in Task 8, together with the AdvancePanel mocks that must carry it — so Task 7's `npm run build` stays green (it never touches `SimulateRoundResponse`).

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-26-elifoot-carvoeiro-phase-2b-finances.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, two-stage review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session via executing-plans, batch execution with checkpoints.

Which approach?
