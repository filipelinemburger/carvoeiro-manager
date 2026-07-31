# Pre-Contract Offers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bosman-style pre-contracts — sign a rival club's final-contract-year player after July 15 so he joins free at season rollover, with AI clubs proactively renewing their own players, poaching yours, and a counter-renew drama in both directions.

**Architecture:** Derived contract expiry (`contractYears == 1` + date ≥ July 15 — no new Player column). A new `pre_contract_agreement` table holds pending deals (AGREED → LOCKED/CANCELLED), resolved on TRANSFER calendar days and executed inside `SeasonRolloverService`'s existing contract-tick pass, which also stops silently auto-renewing AI players — unrenewed ones now lapse into the real free-agent pool. New TRANSFER calendar stops are generated for the July-15→season-end window (none exist there today).

**Tech Stack:** Java 21/Spring Boot 3 + Flyway + Postgres (Testcontainers ITs), React 19 + TanStack Query + MUI (pt-BR UI).

**Spec:** `docs/superpowers/specs/2026-07-19-pre-contract-offers-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task that touches tracked files ends by committing its own tested work. `git push` is never run without an explicit user ask. This authorization is standing project policy; do not withhold commits.
- Backend test command: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '...'` with a long timeout (600000ms) — `*IT` tests boot Testcontainers Postgres.
- Frontend type-check is `npx tsc -b` (NEVER bare `npx tsc --noEmit` — it is a silent no-op in this repo). Frontend tests: `npx vitest run`.
- All user-facing copy is pt-BR.
- Jackson is **Jackson 3** (`tools.jackson.databind.ObjectMapper`), not `com.fasterxml`.
- The news `type` column is `VARCHAR(16)` — the new news type string is `PRECONTRACT` (11 chars).
- Next Flyway migration number is **V30**.
- Season start is `LocalDate.of(seasonYear, 1, 15)`. Pre-contract window opens **July 15** (= season start + 181 days) and runs to season end. Spec constants (single source in code, verbatim):
  - demand = `ContractValuation.wageDemand(overall, age) × 1.10` (no staff multiplier)
  - counter threshold: offer ≥ `0.70 × demand` → COUNTER at demand; below → reject
  - defense renewal: wage ≥ `1.05 × agreed pre-contract wage`
  - counter-renew keep probability: `clamp(0.30 + 0.05 × (fromRep − toRep) + 0.02 × (age − 28), 0.05, 0.70)`
  - AI release rule: (age ≥ 34 OR overall < squad median − 5) AND remaining squad > 19
  - AI renew/poach contract length: `2 + rng.nextInt(3)` (2–4 years); poach interest: overall ≥ AI squad median AND age ≤ 33; max ONE new poach per TRANSFER day league-wide (best-reputation interested club wins); poach chance 0.5/day
- Seeded-RNG conventions (copy exactly): per-date `new Random(saveGameId.getMostSignificantBits() ^ eventDate.toEpochDay() ^ SALT)`; per-season `new Random(saveGameId.getMostSignificantBits() ^ (long) seasonYear ^ SALT)`. AI-renewal decisions are seeded **per season** so re-running the pass on every TRANSFER day is idempotent (same decisions each day).
- Two spec adaptations forced by verified code reality (both approved in spirit by the spec's own mechanics — flag them to reviewers, do not "fix" them back):
  1. **No TRANSFER calendar days exist after ~June 24 today** (they're only generated inside the two transfer windows). Task 4 extends `SeasonCalendarGenerator` with TRANSFER stops every 10 days from offset 181 (July 15) up to the last scheduled match. Offsets ≡ 1 (mod 5), so they never collide with MATCH dates and always precede the season finale.
  2. **Rollover purges the news feed** (`newsItemRepository.deleteBySaveGameId`), so execution-day visibility rides on `RolloverResponse` (two new `SquadChangeResponse` lists), exactly like retirements/expiredContracts already do. In-season events (agreed/locked/cancelled/poached) DO produce news.
- `ErrorCode` entries are alphabetically ordered and carry only an `HttpStatus`. 422 is spelled `HttpStatus.UNPROCESSABLE_CONTENT` in this codebase.
- One active agreement per player. Existing behavior that must NOT change: managed players' expired contracts lapse into the free-agent pool; free agents (clubId null) never tick contracts; no generated free agents anywhere.

---

### Task 1: Data layer — V30 migration, entity, status, repository, window

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__pre_contract.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractStatus.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractAgreement.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractAgreementRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractWindow.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/PreContractWindowTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces (later tasks rely on these exact signatures):
  - `PreContractStatus { AGREED, LOCKED, CANCELLED }`
  - `PreContractAgreement.create(UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId, BigDecimal wage, int contractYears, LocalDate createdDate)` → status AGREED; instance methods `lock()`, `cancel()`, getters `getId/getSaveGameId/getPlayerId/getFromClubId/getToClubId/getWage/getContractYears/getStatus/getCreatedDate`.
  - `PreContractAgreementRepository`: `List<PreContractAgreement> findBySaveGameId(UUID)`, `List<PreContractAgreement> findBySaveGameIdAndStatus(UUID, PreContractStatus)`, `List<PreContractAgreement> findBySaveGameIdAndPlayerIdAndStatusIn(UUID, UUID, Collection<PreContractStatus>)`, `@Transactional void deleteBySaveGameId(UUID)`.
  - `PreContractWindow.isOpen(LocalDate)` — true from July 15 of the date's year onward.

- [ ] **Step 1: Write the failing window test**

`backend/src/test/java/br/com/carvoeiromanager/contract/PreContractWindowTest.java`:

```java
package br.com.carvoeiromanager.contract;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PreContractWindowTest {

    private final PreContractWindow window = new PreContractWindow();

    @Test
    void closedBeforeJulyFifteen() {
        assertThat(window.isOpen(LocalDate.of(2026, 1, 15))).isFalse();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 14))).isFalse();
    }

    @Test
    void openFromJulyFifteenToYearEnd() {
        assertThat(window.isOpen(LocalDate.of(2026, 7, 15))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 10, 3))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void nextSeasonStartsClosedAgain() {
        assertThat(window.isOpen(LocalDate.of(2027, 1, 15))).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractWindowTest'`
Expected: FAIL — compilation error, `PreContractWindow` does not exist.

- [ ] **Step 3: Create the migration, enum, entity, repository, window**

`backend/src/main/resources/db/migration/V30__pre_contract.sql`:

```sql
CREATE TABLE pre_contract_agreement (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL,
    player_id      UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    from_club_id   UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    to_club_id     UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    wage           NUMERIC(14,2) NOT NULL,
    contract_years INT NOT NULL,
    status         VARCHAR(10) NOT NULL,
    created_date   DATE NOT NULL
);

CREATE INDEX idx_pre_contract_save_game ON pre_contract_agreement(save_game_id);
CREATE INDEX idx_pre_contract_player ON pre_contract_agreement(player_id);
```

`PreContractStatus.java`:

```java
package br.com.carvoeiromanager.contract;

public enum PreContractStatus {
    AGREED, LOCKED, CANCELLED
}
```

`PreContractAgreement.java`:

```java
package br.com.carvoeiromanager.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A Bosman-style pre-contract: a final-contract-year player has agreed terms with a new
 * club and joins for free when his contract lapses at rollover. Lifecycle: AGREED (the
 * current club may still counter-renew on the next TRANSFER day) -> LOCKED (binding) or
 * CANCELLED (the player renewed with his current club instead). Rows are season-scoped:
 * rollover executes LOCKED deals and purges the table.
 */
@Entity
@Table(name = "pre_contract_agreement")
public class PreContractAgreement {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "from_club_id", nullable = false) private UUID fromClubId;
    @Column(name = "to_club_id", nullable = false) private UUID toClubId;
    @Column(name = "wage", nullable = false) private BigDecimal wage;
    @Column(name = "contract_years", nullable = false) private int contractYears;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10) private PreContractStatus status;
    @Column(name = "created_date", nullable = false) private LocalDate createdDate;

    protected PreContractAgreement() { }

    private PreContractAgreement(UUID id, UUID saveGameId, UUID playerId, UUID fromClubId,
                                 UUID toClubId, BigDecimal wage, int contractYears,
                                 PreContractStatus status, LocalDate createdDate) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.playerId = playerId;
        this.fromClubId = fromClubId;
        this.toClubId = toClubId;
        this.wage = wage;
        this.contractYears = contractYears;
        this.status = status;
        this.createdDate = createdDate;
    }

    public static PreContractAgreement create(UUID saveGameId, UUID playerId, UUID fromClubId,
                                              UUID toClubId, BigDecimal wage, int contractYears,
                                              LocalDate createdDate) {
        return new PreContractAgreement(UUID.randomUUID(), saveGameId, playerId, fromClubId,
                toClubId, wage, contractYears, PreContractStatus.AGREED, createdDate);
    }

    public void lock() { this.status = PreContractStatus.LOCKED; }
    public void cancel() { this.status = PreContractStatus.CANCELLED; }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getFromClubId() { return fromClubId; }
    public UUID getToClubId() { return toClubId; }
    public BigDecimal getWage() { return wage; }
    public int getContractYears() { return contractYears; }
    public PreContractStatus getStatus() { return status; }
    public LocalDate getCreatedDate() { return createdDate; }
}
```

`PreContractAgreementRepository.java`:

```java
package br.com.carvoeiromanager.contract;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PreContractAgreementRepository extends JpaRepository<PreContractAgreement, UUID> {

    List<PreContractAgreement> findBySaveGameId(UUID saveGameId);

    List<PreContractAgreement> findBySaveGameIdAndStatus(UUID saveGameId, PreContractStatus status);

    List<PreContractAgreement> findBySaveGameIdAndPlayerIdAndStatusIn(
            UUID saveGameId, UUID playerId, Collection<PreContractStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

`PreContractWindow.java`:

```java
package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Date gate for pre-contract negotiation: open from July 15 (exactly 6 months before the
 * mid-January rollover where final-year contracts lapse) to the end of the season year.
 * Deliberately independent of TransferWindow — pre-contracts work outside transfer windows.
 */
@Component
public class PreContractWindow {

    public boolean isOpen(LocalDate currentDate) {
        return !currentDate.isBefore(LocalDate.of(currentDate.getYear(), 7, 15));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractWindowTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V30__pre_contract.sql backend/src/main/java/br/com/carvoeiromanager/contract/PreContractStatus.java backend/src/main/java/br/com/carvoeiromanager/contract/PreContractAgreement.java backend/src/main/java/br/com/carvoeiromanager/contract/PreContractAgreementRepository.java backend/src/main/java/br/com/carvoeiromanager/contract/PreContractWindow.java backend/src/test/java/br/com/carvoeiromanager/contract/PreContractWindowTest.java
git commit -m "feat(contract): pre-contract data layer — V30 migration, agreement entity, window gate"
```

---

### Task 2: Pure policy components — negotiation math + AI renewal policy

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractNegotiation.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/AiContractRenewalPolicy.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/PreContractNegotiationTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/AiContractRenewalPolicyTest.java`

**Interfaces:**
- Consumes: `ContractValuation.wageDemand(int overall, int age): BigDecimal` (existing bean).
- Produces:
  - `PreContractNegotiation` (Spring `@Component`): `BigDecimal demand(int overall, int age)`; `OfferVerdict classify(BigDecimal offered, BigDecimal demand)` with nested `enum OfferVerdict { AGREE, COUNTER, REJECT }`; `double keepProbability(int fromClubReputation, int toClubReputation, int age)`; public constants `PREMIUM` (`new BigDecimal("1.10")`), `COUNTER_FLOOR` (`new BigDecimal("0.70")`), `DEFENSE_FACTOR` (`new BigDecimal("1.05")`).
  - `AiContractRenewalPolicy` (plain class, `@Component`): `boolean shouldRelease(int age, int overall, int squadMedianOverall, int remainingSquadSize)`; `int renewYears(java.util.Random rng)` → 2–4. Public constants `RELEASE_AGE = 34`, `MEDIAN_TOLERANCE = 5`, `SQUAD_FLOOR = 19`.

- [ ] **Step 1: Write the failing tests**

`PreContractNegotiationTest.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.contract.PreContractNegotiation.OfferVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PreContractNegotiationTest {

    // demand() needs ContractValuation (Spring bean); test the pure parts directly and
    // demand() via a hand-built instance in the IT (Task 3). classify/keepProbability are pure.
    private final PreContractNegotiation negotiation = new PreContractNegotiation(null);

    @Test
    void offerAtOrAboveDemandAgrees() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("1000.00"), demand)).isEqualTo(OfferVerdict.AGREE);
        assertThat(negotiation.classify(new BigDecimal("1500.00"), demand)).isEqualTo(OfferVerdict.AGREE);
    }

    @Test
    void offerBetweenSeventyPercentAndDemandCounters() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("700.00"), demand)).isEqualTo(OfferVerdict.COUNTER);
        assertThat(negotiation.classify(new BigDecimal("999.99"), demand)).isEqualTo(OfferVerdict.COUNTER);
    }

    @Test
    void offerBelowSeventyPercentRejects() {
        BigDecimal demand = new BigDecimal("1000.00");
        assertThat(negotiation.classify(new BigDecimal("699.99"), demand)).isEqualTo(OfferVerdict.REJECT);
        assertThat(negotiation.classify(new BigDecimal("1.00"), demand)).isEqualTo(OfferVerdict.REJECT);
    }

    @Test
    void keepProbabilityFollowsTheSpecFormula() {
        // base case: equal reputations, age 28 -> 0.30
        assertThat(negotiation.keepProbability(60, 60, 28)).isCloseTo(0.30, within(1e-9));
        // rep advantage and age both raise it: 0.30 + 0.05*4 + 0.02*4 = 0.58
        assertThat(negotiation.keepProbability(64, 60, 32)).isCloseTo(0.58, within(1e-9));
    }

    @Test
    void keepProbabilityIsClampedToTheSpecBounds() {
        assertThat(negotiation.keepProbability(40, 90, 18)).isCloseTo(0.05, within(1e-9));
        assertThat(negotiation.keepProbability(90, 40, 40)).isCloseTo(0.70, within(1e-9));
    }
}
```

`AiContractRenewalPolicyTest.java`:

```java
package br.com.carvoeiromanager.contract;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AiContractRenewalPolicyTest {

    private final AiContractRenewalPolicy policy = new AiContractRenewalPolicy();

    @Test
    void primeAgePlayerAtSquadStandardIsKept() {
        assertThat(policy.shouldRelease(28, 60, 60, 25)).isFalse();
    }

    @Test
    void oldPlayerIsReleasedWhenTheSquadCanAffordIt() {
        assertThat(policy.shouldRelease(34, 70, 60, 25)).isTrue();
    }

    @Test
    void weakPlayerBelowMedianMinusToleranceIsReleased() {
        assertThat(policy.shouldRelease(25, 54, 60, 25)).isTrue();   // 54 < 60 - 5
        assertThat(policy.shouldRelease(25, 55, 60, 25)).isFalse();  // 55 == 60 - 5, kept
    }

    @Test
    void nobodyIsReleasedWhenTheSquadWouldDropBelowTheFloor() {
        assertThat(policy.shouldRelease(36, 40, 60, 19)).isFalse();
        assertThat(policy.shouldRelease(36, 40, 60, 20)).isTrue();
    }

    @Test
    void renewYearsAreBetweenTwoAndFour() {
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            assertThat(policy.renewYears(rng)).isBetween(2, 4);
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractNegotiationTest' --tests 'br.com.carvoeiromanager.contract.AiContractRenewalPolicyTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement both components**

`PreContractNegotiation.java`:

```java
package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure pre-contract economics (spec constants live here, nowhere else):
 * a player under contract demands a 10% premium over his normal wage demand; an offer
 * at 70-99% of the demand draws a single counter at the demand; the current club's
 * counter-renew chance follows the spec's clamped linear formula.
 */
@Component
public class PreContractNegotiation {

    public static final BigDecimal PREMIUM = new BigDecimal("1.10");
    public static final BigDecimal COUNTER_FLOOR = new BigDecimal("0.70");
    public static final BigDecimal DEFENSE_FACTOR = new BigDecimal("1.05");

    private final ContractValuation contractValuation;

    public PreContractNegotiation(ContractValuation contractValuation) {
        this.contractValuation = contractValuation;
    }

    public enum OfferVerdict { AGREE, COUNTER, REJECT }

    public BigDecimal demand(int overall, int age) {
        return contractValuation.wageDemand(overall, age)
                .multiply(PREMIUM).setScale(2, RoundingMode.HALF_UP);
    }

    public OfferVerdict classify(BigDecimal offered, BigDecimal demand) {
        if (offered.compareTo(demand) >= 0) {
            return OfferVerdict.AGREE;
        }
        if (offered.compareTo(demand.multiply(COUNTER_FLOOR)) >= 0) {
            return OfferVerdict.COUNTER;
        }
        return OfferVerdict.REJECT;
    }

    /** clamp(0.30 + 0.05*(fromRep - toRep) + 0.02*(age - 28), 0.05, 0.70) per the spec. */
    public double keepProbability(int fromClubReputation, int toClubReputation, int age) {
        double p = 0.30 + 0.05 * (fromClubReputation - toClubReputation) + 0.02 * (age - 28);
        return Math.max(0.05, Math.min(0.70, p));
    }
}
```

`AiContractRenewalPolicy.java`:

```java
package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * AI clubs' keep-or-release decision for their own final-contract-year players, run
 * during the pre-contract window. Conservative by design: only clearly surplus players
 * (old, or well below squad standard) are released, and never below the squad floor —
 * released players become pre-contract targets and, if unsigned, real free agents.
 */
@Component
public class AiContractRenewalPolicy {

    public static final int RELEASE_AGE = 34;
    public static final int MEDIAN_TOLERANCE = 5;
    public static final int SQUAD_FLOOR = 19;

    public boolean shouldRelease(int age, int overall, int squadMedianOverall, int remainingSquadSize) {
        if (remainingSquadSize <= SQUAD_FLOOR) {
            return false;
        }
        return age >= RELEASE_AGE || overall < squadMedianOverall - MEDIAN_TOLERANCE;
    }

    public int renewYears(Random rng) {
        return 2 + rng.nextInt(3);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/contract/PreContractNegotiation.java backend/src/main/java/br/com/carvoeiromanager/contract/AiContractRenewalPolicy.java backend/src/test/java/br/com/carvoeiromanager/contract/PreContractNegotiationTest.java backend/src/test/java/br/com/carvoeiromanager/contract/AiContractRenewalPolicyTest.java
git commit -m "feat(contract): pre-contract negotiation math and AI renewal policy"
```

---

### Task 3: Offer flows — ErrorCodes, DTOs, PreContractService (manager side), controller

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java` (insert 5 entries alphabetically)
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractEligibleResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractAgreementResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractOfferRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/AcceptCounterRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractOfferResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractEventResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/PreContractOfferIT.java`

**Interfaces:**
- Consumes: Task 1's entity/repo/window, Task 2's `PreContractNegotiation`. Existing: `SaveGameService.requireOwned`, `PlayerRepository` (`findById`, `findBySaveGameId`, `findByClubIdAndYouthProspectFalse`), `ClubRepository.findById`, `SquadRules.MAX_SQUAD_SIZE`, `NewsItemRepository` (`countBySaveGameId`, `save`), `tools.jackson.databind.ObjectMapper`, `NewsItem.create`.
- Produces (Tasks 4/6/7/8 rely on these):
  - `PreContractService` public methods:
    - `List<PreContractEligibleResponse> getEligiblePlayers(UUID saveGameId, UUID userId)`
    - `PreContractOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId, BigDecimal wage, int contractYears)`
    - `PreContractOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID playerId, int contractYears)`
    - `List<PreContractAgreementResponse> getAgreements(UUID saveGameId, UUID userId)`
    - `void withdraw(UUID saveGameId, UUID userId, UUID agreementId)`
  - `PreContractEventResponse(String kind, String playerName, String clubName, BigDecimal wage, Integer contractYears)` — kind values used across the feature: `AGREED_OUT`, `POACHED_IN`, `LOCKED_OUT`, `CANCELLED_OUT`, `LOCKED_IN`.
  - News type string `"PRECONTRACT"`; synthetic payload = an `AdvanceResponse` JSON (Task 4 adds the `preContractEvents` field; until then this task serializes a small map — see Step 3 note).

- [ ] **Step 1: Add the ErrorCode entries**

In `ErrorCode.java`, insert alphabetically (exact lines, status names as in this codebase):

```java
    PLAYER_NOT_PRE_CONTRACT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
    PRE_CONTRACT_ALREADY_EXISTS(HttpStatus.CONFLICT),
    PRE_CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRE_CONTRACT_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_CONTENT),
    RENEWAL_BLOCKED_BY_PRE_CONTRACT(HttpStatus.UNPROCESSABLE_CONTENT),
```

- [ ] **Step 2: Write the failing IT**

`PreContractOfferIT.java` (pattern copied from `ContractServiceSignIT`):

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractOfferIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractService preContractService;
    @Autowired PreContractNegotiation negotiation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("precon_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        // Move into the pre-contract window (July 15+).
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 8, 1));
        saveGameRepository.save(save);
        return c;
    }

    /** A rival final-year outfield senior, made eligible by pinning contractYears to 1. */
    private Player makeEligibleRival(Career c) {
        Player p = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(x -> !x.isYouthProspect() && !x.isFreeAgent())
                .filter(x -> !c.clubId().equals(x.getClubId()))
                .findFirst().orElseThrow();
        p.setContractYears(1);
        playerRepository.save(p);
        return p;
    }

    @Test
    void eligibleListContainsOnlyFinalYearRivalsInsideTheWindow() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);

        List<PreContractEligibleResponse> eligible = preContractService.getEligiblePlayers(c.id(), c.userId());

        assertThat(eligible).extracting(PreContractEligibleResponse::playerId).contains(rival.getId());
        // Nobody from the managed club, nobody with more than a year left.
        List<UUID> managedIds = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId())
                .stream().map(Player::getId).toList();
        assertThat(eligible).extracting(PreContractEligibleResponse::playerId)
                .doesNotContainAnyElementsOf(managedIds);
    }

    @Test
    void offerAtTheDemandAgreesAndCreatesAnAgreement() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());

        PreContractOfferResponse r = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3);

        assertThat(r.status()).isEqualTo("AGREED");
        assertThat(r.agreement()).isNotNull();
        assertThat(r.agreement().wage()).isEqualByComparingTo(demand);
        assertThat(agreementRepository.findBySaveGameId(c.id())).hasSize(1);
    }

    @Test
    void lowballDrawsACounterWhichCanBeAccepted() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        BigDecimal eighty = demand.multiply(new BigDecimal("0.80"));

        PreContractOfferResponse counter = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), eighty, 3);
        assertThat(counter.status()).isEqualTo("COUNTERED");
        assertThat(counter.demandedWage()).isEqualByComparingTo(demand);
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();

        PreContractOfferResponse accepted = preContractService.acceptCounter(
                c.id(), c.userId(), rival.getId(), 3);
        assertThat(accepted.status()).isEqualTo("AGREED");
        assertThat(accepted.agreement().wage()).isEqualByComparingTo(demand);
    }

    @Test
    void insultingOfferIsRejectedOutright() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand.multiply(new BigDecimal("0.50")), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_REJECTS_TERMS));
    }

    @Test
    void offersOutsideTheWindowAreBlocked() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1));
        saveGameRepository.save(save);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), new BigDecimal("99999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PRE_CONTRACT_WINDOW_CLOSED));
    }

    @Test
    void aPlayerWithAnActiveAgreementCannotReceiveASecondOffer() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        preContractService.makeOffer(c.id(), c.userId(), rival.getId(), demand, 3);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PRE_CONTRACT_ALREADY_EXISTS));
    }

    @Test
    void aPlayerWithYearsLeftIsNotEligible() {
        Career c = createCareer();
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(x -> !x.isYouthProspect() && !x.isFreeAgent())
                .filter(x -> !c.clubId().equals(x.getClubId()))
                .findFirst().orElseThrow();
        rival.setContractYears(3);
        playerRepository.save(rival);

        assertThatThrownBy(() -> preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), new BigDecimal("99999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_PRE_CONTRACT_ELIGIBLE));
    }

    @Test
    void withdrawingAnAgreedDealDeletesIt() {
        Career c = createCareer();
        Player rival = makeEligibleRival(c);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        PreContractOfferResponse r = preContractService.makeOffer(
                c.id(), c.userId(), rival.getId(), demand, 3);

        preContractService.withdraw(c.id(), c.userId(), r.agreement().id());

        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }
}
```

- [ ] **Step 3: Run it to verify it fails, then implement**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractOfferIT'`
Expected: FAIL — `PreContractService` etc. do not exist.

DTO records (each its own file, package `br.com.carvoeiromanager.contract`):

```java
public record PreContractEligibleResponse(UUID playerId, String name, String position,
                                          int age, int overall, String clubName,
                                          BigDecimal wageDemand) {}
```

```java
public record PreContractAgreementResponse(UUID id, UUID playerId, String playerName,
                                           String position, int age, int overall,
                                           String fromClubName, String toClubName,
                                           BigDecimal wage, int contractYears,
                                           String status, boolean incoming) {}
```

```java
public record PreContractOfferRequest(UUID playerId, BigDecimal wage, int contractYears) {}
```

```java
public record AcceptCounterRequest(int contractYears) {}
```

```java
/** status: AGREED (agreement set) or COUNTERED (demandedWage set, no agreement row). */
public record PreContractOfferResponse(String status, BigDecimal demandedWage,
                                       PreContractAgreementResponse agreement) {}
```

```java
/**
 * One pre-contract happening, rendered in news/advance cards.
 * kind: AGREED_OUT (manager's offer accepted), POACHED_IN (an AI club agreed terms with a
 * managed player), LOCKED_OUT / CANCELLED_OUT (manager's deal locked / broken by counter-renew),
 * LOCKED_IN (the manager's player's departure became final).
 */
public record PreContractEventResponse(String kind, String playerName, String clubName,
                                       BigDecimal wage, Integer contractYears) {}
```

`PreContractService.java` (manager-side flows only; Task 4 adds `processMarketDay`, Task 6 adds rollover hooks):

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bosman-style pre-contract negotiation (spec: 2026-07-19-pre-contract-offers-design.md).
 * Manager-facing flows: list eligible final-year rivals, offer terms (single counter
 * round), track agreements, withdraw before lock. The player's current club fights back
 * on TRANSFER days (processMarketDay, Task 4); deals execute at rollover (Task 6).
 */
@Service
public class PreContractService {

    static final List<PreContractStatus> ACTIVE = List.of(PreContractStatus.AGREED, PreContractStatus.LOCKED);

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final PreContractAgreementRepository agreementRepository;
    private final PreContractNegotiation negotiation;
    private final PreContractWindow preContractWindow;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public PreContractService(SaveGameService saveGameService,
                              PlayerRepository playerRepository,
                              ClubRepository clubRepository,
                              PreContractAgreementRepository agreementRepository,
                              PreContractNegotiation negotiation,
                              PreContractWindow preContractWindow,
                              NewsItemRepository newsItemRepository,
                              ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.agreementRepository = agreementRepository;
        this.negotiation = negotiation;
        this.preContractWindow = preContractWindow;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public List<PreContractEligibleResponse> getEligiblePlayers(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        if (!preContractWindow.isOpen(save.getCurrentDate())) {
            return List.of();
        }
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        List<UUID> underAgreement = agreementRepository.findBySaveGameId(saveGameId).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()))
                .map(PreContractAgreement::getPlayerId)
                .toList();
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !save.getManagedClubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .filter(p -> !underAgreement.contains(p.getId()))
                .map(p -> new PreContractEligibleResponse(p.getId(), p.getName(),
                        p.getPosition().name(), p.getAge(), p.getOverall(),
                        clubNames.getOrDefault(p.getClubId(), "?"),
                        negotiation.demand(p.getOverall(), p.getAge())))
                .sorted(Comparator.comparing(PreContractEligibleResponse::overall).reversed())
                .toList();
    }

    @Transactional
    public PreContractOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId,
                                              BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireEligible(save, playerId);
        requireSquadRoom(save.getManagedClubId());
        if (contractYears < 1 || contractYears > 5 || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
        BigDecimal demand = negotiation.demand(player.getOverall(), player.getAge());
        return switch (negotiation.classify(wage, demand)) {
            case AGREE -> agree(save, player, wage, contractYears);
            case COUNTER -> new PreContractOfferResponse("COUNTERED", demand, null);
            case REJECT -> throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects the approach outright; demands around " + demand);
        };
    }

    @Transactional
    public PreContractOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID playerId,
                                                  int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireEligible(save, playerId);
        requireSquadRoom(save.getManagedClubId());
        if (contractYears < 1 || contractYears > 5) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT, "Contract length must be 1-5 years");
        }
        // Accepting the counter means paying exactly the demanded wage.
        BigDecimal demand = negotiation.demand(player.getOverall(), player.getAge());
        return agree(save, player, demand, contractYears);
    }

    public List<PreContractAgreementResponse> getAgreements(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        return agreementRepository.findBySaveGameId(saveGameId).stream()
                .map(a -> {
                    Player p = players.get(a.getPlayerId());
                    return new PreContractAgreementResponse(a.getId(), a.getPlayerId(),
                            p != null ? p.getName() : "?",
                            p != null ? p.getPosition().name() : "?",
                            p != null ? p.getAge() : 0,
                            p != null ? p.getOverall() : 0,
                            clubNames.getOrDefault(a.getFromClubId(), "?"),
                            clubNames.getOrDefault(a.getToClubId(), "?"),
                            a.getWage(), a.getContractYears(), a.getStatus().name(),
                            a.getFromClubId().equals(save.getManagedClubId()));
                })
                .toList();
    }

    @Transactional
    public void withdraw(UUID saveGameId, UUID userId, UUID agreementId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        PreContractAgreement agreement = agreementRepository.findById(agreementId)
                .filter(a -> a.getSaveGameId().equals(saveGameId))
                .filter(a -> a.getToClubId().equals(save.getManagedClubId()))
                .filter(a -> a.getStatus() == PreContractStatus.AGREED)
                .orElseThrow(() -> new ApiException(ErrorCode.PRE_CONTRACT_NOT_FOUND,
                        "No withdrawable pre-contract agreement found"));
        agreementRepository.delete(agreement);
    }

    // ---- shared internals ----

    private PreContractOfferResponse agree(SaveGame save, Player player,
                                           BigDecimal wage, int contractYears) {
        PreContractAgreement agreement = PreContractAgreement.create(save.getId(), player.getId(),
                player.getClubId(), save.getManagedClubId(), wage, contractYears,
                save.getCurrentDate());
        agreementRepository.save(agreement);
        String fromName = clubRepository.findById(player.getClubId()).map(Club::getName).orElse("?");
        persistPreContractNews(save, new PreContractEventResponse(
                "AGREED_OUT", player.getName(), fromName, wage, contractYears));
        PreContractAgreementResponse dto = new PreContractAgreementResponse(agreement.getId(),
                player.getId(), player.getName(), player.getPosition().name(), player.getAge(),
                player.getOverall(), fromName,
                clubRepository.findById(save.getManagedClubId()).map(Club::getName).orElse("?"),
                wage, contractYears, agreement.getStatus().name(), false);
        return new PreContractOfferResponse("AGREED", null, dto);
    }

    private Player requireEligible(SaveGame save, UUID playerId) {
        if (!preContractWindow.isOpen(save.getCurrentDate())) {
            throw new ApiException(ErrorCode.PRE_CONTRACT_WINDOW_CLOSED,
                    "Pre-contract talks only open on July 15, six months before contracts lapse");
        }
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(save.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found"));
        boolean rival = !player.isFreeAgent() && !save.getManagedClubId().equals(player.getClubId());
        if (player.isYouthProspect() || !rival || player.getContractYears() != 1) {
            throw new ApiException(ErrorCode.PLAYER_NOT_PRE_CONTRACT_ELIGIBLE,
                    "Only rival players in their final contract year can be approached");
        }
        if (!agreementRepository.findBySaveGameIdAndPlayerIdAndStatusIn(
                save.getId(), playerId, ACTIVE).isEmpty()) {
            throw new ApiException(ErrorCode.PRE_CONTRACT_ALREADY_EXISTS,
                    "This player already has an active pre-contract agreement");
        }
        return player;
    }

    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size()
                >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }

    /**
     * Persist a PRECONTRACT news item so the feed records the deal. The payload is an
     * AdvanceResponse-shaped map (the frontend renders news payloads with the same card
     * it uses for advances); only type/date/preContractEvents are meaningful.
     */
    void persistPreContractNews(SaveGame save, PreContractEventResponse event) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "PRECONTRACT",
                    "date", save.getCurrentDate().toString(),
                    "preContractEvents", List.of(event)));
            int seq = newsItemRepository.countBySaveGameId(save.getId()) + 1;
            newsItemRepository.save(NewsItem.create(save.getId(), seq, save.getCurrentDate(),
                    "PRECONTRACT", payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize pre-contract news item", e);
        }
    }
}
```

`PreContractController.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/pre-contracts")
public class PreContractController {

    private final PreContractService preContractService;

    public PreContractController(PreContractService preContractService) {
        this.preContractService = preContractService;
    }

    @GetMapping("/eligible")
    public ResponseEntity<List<PreContractEligibleResponse>> eligible(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.getEligiblePlayers(saveGameId, userId));
    }

    @PostMapping("/offers")
    public ResponseEntity<PreContractOfferResponse> offer(
            @PathVariable UUID saveGameId, @RequestBody PreContractOfferRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.makeOffer(
                saveGameId, userId, request.playerId(), request.wage(), request.contractYears()));
    }

    @PostMapping("/offers/{playerId}/accept-counter")
    public ResponseEntity<PreContractOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody AcceptCounterRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.acceptCounter(
                saveGameId, userId, playerId, request.contractYears()));
    }

    @GetMapping("/agreements")
    public ResponseEntity<List<PreContractAgreementResponse>> agreements(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.getAgreements(saveGameId, userId));
    }

    @DeleteMapping("/agreements/{agreementId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID saveGameId, @PathVariable UUID agreementId,
            @CurrentUser UUID userId) {
        preContractService.withdraw(saveGameId, userId, agreementId);
        return ResponseEntity.noContent().build();
    }
}
```

Notes for the implementer:
- `ClubRepository.findBySaveGameId(UUID)` exists (used by `TransferService.generateIncomingOffers`). If the exact name differs, mirror whatever `TransferService` imports.
- `INVALID_CONTRACT`, `PLAYER_REJECTS_TERMS`, `PLAYER_NOT_FOUND`, `SQUAD_FULL` already exist in `ErrorCode`.
- The synthetic news payload uses a `Map` (not `AdvanceResponse`) deliberately — Task 4 adds the record field; the map keeps this task self-contained and forward-compatible since the frontend consumes JSON.

- [ ] **Step 4: Run the IT to verify it passes**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractOfferIT'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java backend/src/main/java/br/com/carvoeiromanager/contract/ backend/src/test/java/br/com/carvoeiromanager/contract/PreContractOfferIT.java
git commit -m "feat(contract): pre-contract offer flows — eligible list, negotiation with counter, agreements API"
```

---

### Task 4: Market days — calendar extension, AI renewals, resolution, poaching, advance wiring

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractService.java` (add `processMarketDay` + helpers)
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/PreContractMarketDayIT.java`
- Test: modify `backend/src/test/java/br/com/carvoeiromanager/season/` calendar test if one asserts TRANSFER-event counts (check `grep -rn "TRANSFER" backend/src/test/java/br/com/carvoeiromanager/season/` and update any count assertion to include the new window's stops).

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `PreContractService.processMarketDay(SaveGame save, LocalDate eventDate): List<PreContractEventResponse>`; `AdvanceResponse` gains a 15th component `List<PreContractEventResponse> preContractEvents` (frontend Task 7 mirrors it).

- [ ] **Step 1: Extend the season calendar**

In `SeasonCalendarGenerator.java` add a constant below the existing ones:

```java
    static final int PRE_CONTRACT_WINDOW_START_DAYS = 181; // July 15 = Jan 15 + 181 days
```

Replace step 4 of `generate` (the two `addTransferEvents` calls) with:

```java
        // 4. TRANSFER events inside each window range (sequence 1), plus pre-contract
        //    market stops from July 15 up to the final scheduled match. Stop offsets are
        //    ≡ 1 (mod 5) so they never share a date with a MATCH event (multiples of 5)
        //    and always precede the season finale that flips the save to FINISHED.
        addTransferEvents(saveGameId, seasonStart, 0, PRE_SEASON_DAYS);
        addTransferEvents(saveGameId, seasonStart, MID_WINDOW_START_DAYS, MID_WINDOW_END_DAYS);
        int lastMatchOffset = (ordered.size() - 1) * MATCH_INTERVAL_DAYS;
        addTransferEvents(saveGameId, seasonStart, PRE_CONTRACT_WINDOW_START_DAYS, lastMatchOffset + 1);
```

- [ ] **Step 2: Add the AdvanceResponse field**

Append `List<PreContractEventResponse> preContractEvents` as the record's final component (import `br.com.carvoeiromanager.contract.PreContractEventResponse`), extend the javadoc with one line ("`preContractEvents` — pre-contract happenings on a TRANSFER day; null in payloads persisted before the feature existed — callers must null-guard"), then fix EVERY construction site by appending an argument:
- `SeasonAdvanceService.advance` FINISHED branch → `List.of()`
- `SeasonAdvanceService.processMatch` → `List.of()`
- `SeasonAdvanceService.processTransfer` → the real list (Step 3)
- `SeasonAdvanceService.maybePersistTrainingReport`'s TRAINING_REPORT response → `List.of()`
- Any test constructing `AdvanceResponse` (run `grep -rn "new AdvanceResponse" backend/src` to catch all sites).

- [ ] **Step 3: Wire processTransfer**

In `SeasonAdvanceService`, inject `PreContractService preContractService` (constructor + field, matching the existing style) and change `processTransfer`:

```java
    private AdvanceResponse processTransfer(SaveGame save, SeasonEvent event) {
        int before = activeIncomingBids(save.getId());
        List<CompletedTransferResponse> transfers = transferService.processMarketDay(save.getId(), event.getEventDate());
        int bidsReceived = Math.max(0, activeIncomingBids(save.getId()) - before);
        List<PreContractEventResponse> preContractEvents =
                preContractService.processMarketDay(save, event.getEventDate());
        return new AdvanceResponse("TRANSFER", event.getEventDate().toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, bidsReceived, false,
                List.of(), transfers, null, preContractEvents);
    }
```

- [ ] **Step 4: Implement processMarketDay in PreContractService**

Add these imports to `PreContractService`: `java.util.ArrayList`, `java.util.HashSet`, `java.util.Optional`, `java.util.Random`, `java.util.Set`, `java.time.LocalDate`, `AiContractRenewalPolicy`. Inject `AiContractRenewalPolicy renewalPolicy` via the constructor. Add:

```java
    // Distinct RNG streams per concern so pre-contract rolls never disturb the
    // incoming-bid RNG on a shared date (same convention as TransferService).
    private static final long RENEWAL_SALT = 0x5052454E45574CL;  // "PRENEWL"
    private static final long MARKET_SALT = 0x505245434D4BL;     // "PRECMK"

    /**
     * The TRANSFER-day pulse inside the pre-contract window, in spec order:
     * 1) AI clubs renew the final-year players they want to keep (seeded PER SEASON, so
     *    rerunning on every stop is idempotent — the same players are released each day);
     * 2) AGREED deals from earlier days resolve — the current club counter-renews or the
     *    deal locks (incoming deals lock unless the manager already renewed the player);
     * 3) at most one AI club agrees a poach with a managed final-year player.
     * Returns the day's events for the advance card / news feed.
     */
    @Transactional
    public List<PreContractEventResponse> processMarketDay(SaveGame save, LocalDate eventDate) {
        if (!preContractWindow.isOpen(eventDate)) {
            return List.of();
        }
        List<PreContractEventResponse> events = new ArrayList<>();
        runAiRenewalPass(save);
        resolvePendingAgreements(save, eventDate, events);
        aiPoach(save, eventDate, events);
        return events;
    }

    private void runAiRenewalPass(SaveGame save) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ (long) save.getSeasonYear() ^ RENEWAL_SALT);
        List<Club> clubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .filter(c -> !c.getId().equals(save.getManagedClubId()))
                .sorted(Comparator.comparing(Club::getId)).toList();
        for (Club club : clubs) {
            List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(club.getId()).stream()
                    .sorted(Comparator.comparing(Player::getId)).toList();
            int median = squadMedianOverall(squad);
            int remaining = squad.size();
            for (Player p : squad) {
                if (p.getContractYears() != 1) {
                    continue;
                }
                // The per-player decision consumes RNG in a fixed (id-sorted) order, so the
                // same seed yields the same renewals on every stop of the season.
                int years = renewalPolicy.renewYears(rng);
                if (renewalPolicy.shouldRelease(p.getAge(), p.getOverall(), median, remaining)) {
                    remaining--;                      // released: stays at 1, poachable
                } else {
                    p.setContractYears(1 + years);    // renewed: current season + 2-4 more
                    playerRepository.save(p);
                }
            }
        }
    }

    private void resolvePendingAgreements(SaveGame save, LocalDate eventDate,
                                          List<PreContractEventResponse> events) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ eventDate.toEpochDay() ^ MARKET_SALT);
        List<PreContractAgreement> pending = agreementRepository
                .findBySaveGameIdAndStatus(save.getId(), PreContractStatus.AGREED).stream()
                .filter(a -> a.getCreatedDate().isBefore(eventDate))
                .sorted(Comparator.comparing(PreContractAgreement::getId)).toList();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
        for (PreContractAgreement a : pending) {
            Optional<Player> playerOpt = playerRepository.findById(a.getPlayerId());
            if (playerOpt.isEmpty()) {
                a.cancel();
                agreementRepository.save(a);
                continue;
            }
            Player player = playerOpt.get();
            boolean outgoing = a.getToClubId().equals(save.getManagedClubId());
            String fromName = clubs.containsKey(a.getFromClubId()) ? clubs.get(a.getFromClubId()).getName() : "?";
            String toName = clubs.containsKey(a.getToClubId()) ? clubs.get(a.getToClubId()).getName() : "?";
            if (outgoing) {
                int fromRep = clubs.get(a.getFromClubId()).getReputation();
                int toRep = clubs.get(a.getToClubId()).getReputation();
                double keep = negotiation.keepProbability(fromRep, toRep, player.getAge());
                if (rng.nextDouble() < keep) {
                    a.cancel();
                    player.setContractYears(1 + renewalPolicy.renewYears(rng));
                    playerRepository.save(player);
                    events.add(new PreContractEventResponse("CANCELLED_OUT",
                            player.getName(), fromName, a.getWage(), a.getContractYears()));
                } else {
                    a.lock();
                    events.add(new PreContractEventResponse("LOCKED_OUT",
                            player.getName(), fromName, a.getWage(), a.getContractYears()));
                }
            } else {
                // Incoming threat: the manager's defense was a renewal before this stop
                // (which cancels the agreement in ContractService, Task 5). Still AGREED
                // here means no successful defense — the deal locks.
                a.lock();
                events.add(new PreContractEventResponse("LOCKED_IN",
                        player.getName(), toName, a.getWage(), a.getContractYears()));
            }
            agreementRepository.save(a);
        }
    }

    private void aiPoach(SaveGame save, LocalDate eventDate, List<PreContractEventResponse> events) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ eventDate.toEpochDay() ^ MARKET_SALT ^ 1L);
        if (rng.nextDouble() >= 0.5) {
            return; // no poach attempt today
        }
        Set<UUID> underAgreement = new HashSet<>(agreementRepository.findBySaveGameId(save.getId()).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()))
                .map(PreContractAgreement::getPlayerId).toList());
        List<Player> targets = playerRepository
                .findByClubIdAndYouthProspectFalse(save.getManagedClubId()).stream()
                .filter(p -> p.getContractYears() == 1 && p.getAge() <= 33)
                .filter(p -> !underAgreement.contains(p.getId()))
                .sorted(Comparator.comparing(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        List<Club> aiClubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .filter(c -> !c.getId().equals(save.getManagedClubId()))
                .sorted(Comparator.comparing(Club::getReputation).reversed()
                        .thenComparing(Club::getId))
                .toList();
        for (Player target : targets) {
            for (Club club : aiClubs) {
                List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(club.getId());
                if (target.getOverall() >= squadMedianOverall(squad)) {
                    BigDecimal wage = negotiation.demand(target.getOverall(), target.getAge());
                    PreContractAgreement agreement = PreContractAgreement.create(save.getId(),
                            target.getId(), save.getManagedClubId(), club.getId(), wage,
                            renewalPolicy.renewYears(rng), eventDate);
                    agreementRepository.save(agreement);
                    events.add(new PreContractEventResponse("POACHED_IN", target.getName(),
                            club.getName(), wage, agreement.getContractYears()));
                    return; // spec: at most ONE new poach per TRANSFER day
                }
            }
        }
    }

    private int squadMedianOverall(List<Player> squad) {
        if (squad.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = squad.stream().map(Player::getOverall).sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
```

Note: `SaveGame.getSeasonYear()` exists (used by rollover). Every TRANSFER-day event returned here is also persisted automatically by `SeasonAdvanceService.persistNewsItem` (the whole `AdvanceResponse` is the news payload) — no extra news write needed.

- [ ] **Step 5: Write the market-day IT**

`backend/src/test/java/br/com/carvoeiromanager/contract/PreContractMarketDayIT.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractMarketDayIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractService preContractService;
    @Autowired PreContractNegotiation negotiation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer(LocalDate date) {
        User user = User.create("mkday_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(date);
        saveGameRepository.save(save);
        return c;
    }

    private SaveGame save(Career c) {
        return saveGameRepository.findById(c.id()).orElseThrow();
    }

    @Test
    void closedWindowDaysDoNothing() {
        Career c = createCareer(LocalDate.of(2026, 6, 4));
        List<PreContractEventResponse> events =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 6, 4));
        assertThat(events).isEmpty();
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }

    @Test
    void aiRenewalPassIsIdempotentAcrossStops() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        // Put every AI final-year decision in play: pin ALL rival contracts to 1 year.
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.stream().filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .forEach(p -> p.setContractYears(1));
        playerRepository.saveAll(all);

        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 15));
        List<UUID> releasedAfterFirst = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .map(Player::getId).sorted().toList();
        // Some were renewed (2-5 total years now), some released (still 1).
        assertThat(releasedAfterFirst.size()).isLessThan(
                (int) playerRepository.findBySaveGameId(c.id()).stream()
                        .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                        .filter(p -> !c.clubId().equals(p.getClubId())).count());

        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        List<UUID> releasedAfterSecond = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .map(Player::getId).sorted().toList();
        // Idempotent: the second stop releases exactly the same set, renews nobody new.
        assertThat(releasedAfterSecond).isEqualTo(releasedAfterFirst);
    }

    @Test
    void anAgreedOutgoingDealResolvesOnTheNextStop() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .findFirst().orElseThrow();
        rival.setContractYears(1);
        playerRepository.save(rival);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        preContractService.makeOffer(c.id(), c.userId(), rival.getId(), demand, 3);

        // Same-day stop must NOT resolve a deal agreed today...
        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 15));
        assertThat(agreementRepository.findBySaveGameId(c.id()).get(0).getStatus())
                .isEqualTo(PreContractStatus.AGREED);

        // ...the next stop resolves it one way or the other, deterministically.
        List<PreContractEventResponse> events =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        PreContractAgreement resolved = agreementRepository.findBySaveGameId(c.id()).get(0);
        assertThat(resolved.getStatus()).isIn(PreContractStatus.LOCKED, PreContractStatus.CANCELLED);
        if (resolved.getStatus() == PreContractStatus.CANCELLED) {
            // Counter-renew won: the player renewed with his club (current year + 2-4 more).
            Player kept = playerRepository.findById(rival.getId()).orElseThrow();
            assertThat(kept.getContractYears()).isBetween(3, 5);
            assertThat(events).extracting(PreContractEventResponse::kind).contains("CANCELLED_OUT");
        } else {
            assertThat(events).extracting(PreContractEventResponse::kind).contains("LOCKED_OUT");
        }
        // Determinism: replaying the same date yields no further changes (already resolved).
        List<PreContractEventResponse> replay =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        assertThat(replay).extracting(PreContractEventResponse::kind)
                .doesNotContain("CANCELLED_OUT", "LOCKED_OUT");
    }

    @Test
    void atMostOnePoachAgreementPerStopAndItTargetsAManagedFinalYearPlayer() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        // Make the whole managed squad prime poach bait.
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        squad.forEach(p -> p.setContractYears(1));
        playerRepository.saveAll(squad);

        // Run several stops; each may create at most one new incoming agreement.
        int previous = 0;
        for (LocalDate d : List.of(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 25),
                LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 24))) {
            preContractService.processMarketDay(save(c), d);
            int now = (int) agreementRepository.findBySaveGameId(c.id()).stream()
                    .filter(a -> a.getFromClubId().equals(c.clubId())).count();
            assertThat(now - previous).isBetween(0, 1);
            previous = now;
        }
        // Every incoming agreement targets a managed final-year senior.
        agreementRepository.findBySaveGameId(c.id()).stream()
                .filter(a -> a.getFromClubId().equals(c.clubId()))
                .forEach(a -> {
                    Player p = playerRepository.findById(a.getPlayerId()).orElseThrow();
                    assertThat(p.getClubId()).isEqualTo(c.clubId());
                    assertThat(p.getAge()).isLessThanOrEqualTo(33);
                });
    }
}
```

- [ ] **Step 6: Run the new IT + the touched suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.*' --tests 'br.com.carvoeiromanager.season.*'`
Expected: PASS. If a season test asserts the total TRANSFER-event count, update it for the new stops (offsets 181..lastMatchOffset step 10).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/ backend/src/main/java/br/com/carvoeiromanager/contract/PreContractService.java backend/src/test/java/br/com/carvoeiromanager/contract/PreContractMarketDayIT.java
git commit -m "feat(season): pre-contract market days — calendar stops, AI renewals, deal resolution, poaching"
```

(Include any updated season test files in the `git add`.)

---

### Task 5: Renewal defense — intercept renewContract when a poacher has agreed terms

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/PreContractDefenseIT.java`

**Interfaces:**
- Consumes: Task 1's repository/status, `PreContractNegotiation.DEFENSE_FACTOR`.
- Produces: no new public interface — `renewContract` behavior extends.

- [ ] **Step 1: Write the failing IT**

`PreContractDefenseIT.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractDefenseIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private record Threat(Career c, Player player, PreContractAgreement agreement) {}

    /** A managed player with an AGREED incoming poach from the first AI club. */
    private Threat createThreat() {
        User user = User.create("def_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 8, 1));
        saveGameRepository.save(save);
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        mine.setContractYears(1);
        playerRepository.save(mine);
        Club poacher = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> !club.getId().equals(c.clubId()))
                .findFirst().orElseThrow();
        BigDecimal agreedWage = contractValuation.wageDemand(mine.getOverall(), mine.getAge())
                .multiply(PreContractNegotiation.PREMIUM).setScale(2, RoundingMode.HALF_UP);
        PreContractAgreement agreement = PreContractAgreement.create(c.id(), mine.getId(),
                c.clubId(), poacher.getId(), agreedWage, 3, LocalDate.of(2026, 7, 25));
        agreementRepository.save(agreement);
        return new Threat(c, mine, agreement);
    }

    @Test
    void aRenewalBeatingTheAgreedWageByFivePercentCancelsThePoach() {
        Threat t = createThreat();
        BigDecimal winning = t.agreement().getWage()
                .multiply(PreContractNegotiation.DEFENSE_FACTOR).setScale(2, RoundingMode.CEILING);

        contractService.renewContract(t.c().id(), t.c().userId(), t.player().getId(), winning, 3);

        PreContractAgreement reloaded = agreementRepository.findById(t.agreement().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PreContractStatus.CANCELLED);
        Player kept = playerRepository.findById(t.player().getId()).orElseThrow();
        assertThat(kept.getClubId()).isEqualTo(t.c().clubId());
        assertThat(kept.getContractYears()).isEqualTo(3);
        assertThat(kept.getWage()).isEqualByComparingTo(winning);
    }

    @Test
    void aRenewalBelowTheDefenseThresholdIsBlocked() {
        Threat t = createThreat();
        BigDecimal losing = t.agreement().getWage(); // 1.00x < required 1.05x

        assertThatThrownBy(() -> contractService.renewContract(
                t.c().id(), t.c().userId(), t.player().getId(), losing, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT));
        assertThat(agreementRepository.findById(t.agreement().getId()).orElseThrow().getStatus())
                .isEqualTo(PreContractStatus.AGREED);
    }

    @Test
    void aLockedDepartureCannotBeRenewedAtAll() {
        Threat t = createThreat();
        PreContractAgreement agreement = agreementRepository.findById(t.agreement().getId()).orElseThrow();
        agreement.lock();
        agreementRepository.save(agreement);

        assertThatThrownBy(() -> contractService.renewContract(
                t.c().id(), t.c().userId(), t.player().getId(), new BigDecimal("999999.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.PreContractDefenseIT'`
Expected: FAIL — first test fails (agreement stays AGREED), second/third fail (no interception, renewal succeeds or fails with the wrong code).

- [ ] **Step 3: Implement the intercept**

In `ContractService`: add fields + constructor params `PreContractAgreementRepository preContractAgreementRepository` (imports: `java.math.RoundingMode`, `java.util.List` already present). Insert into `renewContract` between `requireManagedPlayer` and `applyTerms`:

```java
        interceptPreContractDefense(saveGameId, save.getManagedClubId(), player, wage);
```

and add the private method:

```java
    /**
     * A renewal doubles as the defense against a poacher's pre-contract: while the deal
     * is AGREED the player stays only for at least DEFENSE_FACTOR x the agreed wage
     * (cancelling the deal); once LOCKED the departure is binding and renewal is refused.
     */
    private void interceptPreContractDefense(UUID saveGameId, UUID managedClubId,
                                             Player player, BigDecimal wage) {
        List<PreContractAgreement> threats = preContractAgreementRepository
                .findBySaveGameIdAndPlayerIdAndStatusIn(saveGameId, player.getId(),
                        PreContractService.ACTIVE).stream()
                .filter(a -> a.getFromClubId().equals(managedClubId))
                .toList();
        for (PreContractAgreement threat : threats) {
            if (threat.getStatus() == PreContractStatus.LOCKED) {
                throw new ApiException(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT,
                        "The pre-contract is locked; the player will leave when his contract ends");
            }
            BigDecimal required = threat.getWage()
                    .multiply(PreContractNegotiation.DEFENSE_FACTOR)
                    .setScale(2, RoundingMode.CEILING);
            if (wage == null || wage.compareTo(required) < 0) {
                throw new ApiException(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT,
                        "Player has agreed a pre-contract elsewhere; staying costs at least " + required);
            }
            threat.cancel();
            preContractAgreementRepository.save(threat);
        }
    }
```

- [ ] **Step 4: Run the IT + the existing contract suite to verify**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.*'`
Expected: PASS — including all pre-existing ContractService tests (no active agreement → intercept is a no-op).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java backend/src/test/java/br/com/carvoeiromanager/contract/PreContractDefenseIT.java
git commit -m "feat(contract): renewal doubles as pre-contract defense — beat the agreed wage by 5% or lose him"
```

---

### Task 6: Rollover — execute LOCKED deals, resolve stragglers, end the AI auto-renew era

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/PreContractService.java` (add `resolveRemainingAgreements`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/PreContractRolloverIT.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - `PreContractService.resolveRemainingAgreements(UUID saveGameId, Random rng): Map<UUID, PreContractAgreement>` — resolves still-AGREED deals (outgoing: counter-renew roll; incoming: lock) and returns all LOCKED agreements keyed by playerId.
  - `RolloverResponse` gains `List<SquadChangeResponse> preContractArrivals, List<SquadChangeResponse> preContractDepartures` (appended as the last two components; frontend Task 7 mirrors).

- [ ] **Step 1: Rewrite the obsolete AI-auto-renew test and add the new IT (red)**

In `RolloverContractsIT.java`, replace `anAiPlayerWhoseContractExpiresIsAutoRenewedAndStays` with:

```java
    @Test
    void anUnrenewedAiPlayerWhoseContractExpiresLapsesIntoTheFreeAgentPool() {
        Career c = playToSeasonEnd();
        UUID aiClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        List<Player> aiSquad = playerRepository.findByClubId(aiClub);
        aiSquad.forEach(p -> p.setContractYears(5));
        Player expiring = probePlayer(aiSquad);
        expiring.setContractYears(1); // released by his club, unsigned by anyone
        playerRepository.saveAll(aiSquad);

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();   // organic inflow into the real pool
        assertThat(reloaded.getContractYears()).isZero();
    }
```

`theFreeAgentPoolIsNotReplenishedAtRollover` stays exactly as is (all contracts pinned to 5 → no organic lapse → pool still only shrinks; generated backfill remains forbidden).

New `backend/src/test/java/br/com/carvoeiromanager/season/PreContractRolloverIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.contract.PreContractAgreement;
import br.com.carvoeiromanager.contract.PreContractAgreementRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
class PreContractRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("pcroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    /** Age <= 33 so the probe survives rollover (retirement starts at 35). */
    private Player safeProbe(List<Player> squad) {
        return squad.stream()
                .filter(p -> !p.isYouthProspect())
                .filter(p -> p.getAge() <= 33)
                .findFirst().orElseThrow();
    }

    @Test
    void aLockedIncomingSigningExecutesAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player target = safeProbe(all.stream()
                .filter(p -> !p.isFreeAgent() && !c.clubId().equals(p.getClubId())).toList());
        target.setContractYears(1);
        playerRepository.saveAll(all);
        PreContractAgreement deal = PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), c.clubId(), new BigDecimal("12345.00"), 4,
                LocalDate.of(2026, 8, 1));
        deal.lock();
        agreementRepository.save(deal);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player signed = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(signed.getClubId()).isEqualTo(c.clubId());
        assertThat(signed.getWage()).isEqualByComparingTo(new BigDecimal("12345.00"));
        assertThat(signed.getContractYears()).isEqualTo(4);
        assertThat(r.preContractArrivals()).extracting(SquadChangeResponse::playerName)
                .contains(target.getName());
        // Season-scoped rows are purged.
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }

    @Test
    void aStillAgreedIncomingThreatLocksAndExecutesAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player mine = safeProbe(all.stream()
                .filter(p -> c.clubId().equals(p.getClubId())).toList());
        mine.setContractYears(1);
        playerRepository.saveAll(all);
        UUID poacher = all.stream().map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        agreementRepository.save(PreContractAgreement.create(c.id(), mine.getId(),
                c.clubId(), poacher, new BigDecimal("9999.00"), 2, LocalDate.of(2026, 10, 1)));

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player gone = playerRepository.findById(mine.getId()).orElseThrow();
        assertThat(gone.getClubId()).isEqualTo(poacher);   // incoming threats lock at rollover
        assertThat(r.preContractDepartures()).extracting(SquadChangeResponse::playerName)
                .contains(mine.getName());
    }

    @Test
    void aStillAgreedOutgoingDealResolvesByTheRollAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player target = safeProbe(all.stream()
                .filter(p -> !p.isFreeAgent() && !c.clubId().equals(p.getClubId())).toList());
        target.setContractYears(1);
        playerRepository.saveAll(all);
        agreementRepository.save(PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), c.clubId(), new BigDecimal("5000.00"), 3,
                LocalDate.of(2026, 10, 1)));
        UUID originalClub = target.getClubId();

        rolloverService.rollover(c.id(), c.userId());

        // Deterministic either way: he either joined the manager (deal locked) or renewed
        // with his club (counter-renew won) — never lapsed into the pool.
        Player resolved = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(resolved.getClubId()).isIn(c.clubId(), originalClub);
        assertThat(resolved.getClubId()).isNotNull();
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }
}
```

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.PreContractRolloverIT' --tests 'br.com.carvoeiromanager.season.RolloverContractsIT'`
Expected: FAIL — new tests red (players auto-renew / deals don't execute; `preContractArrivals` doesn't compile yet). Note the rewritten AI test also fails against current code — that is the point (behavior change).

- [ ] **Step 2: Add resolveRemainingAgreements to PreContractService**

```java
    /**
     * Rollover entry point: any deal still AGREED (no TRANSFER stop remained after it was
     * struck) resolves exactly as the next stop would have — outgoing deals run the current
     * club's counter-renew roll, incoming threats lock (the manager's defense window has
     * passed). Returns every LOCKED agreement keyed by player id for the contract-tick pass.
     */
    @Transactional
    public Map<UUID, PreContractAgreement> resolveRemainingAgreements(SaveGame save, Random rng) {
        UUID saveGameId = save.getId();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
        List<PreContractAgreement> pending = agreementRepository
                .findBySaveGameIdAndStatus(saveGameId, PreContractStatus.AGREED).stream()
                .sorted(Comparator.comparing(PreContractAgreement::getId)).toList();
        for (PreContractAgreement a : pending) {
            Optional<Player> playerOpt = playerRepository.findById(a.getPlayerId());
            if (playerOpt.isEmpty()) {
                a.cancel();
                agreementRepository.save(a);
                continue;
            }
            Player player = playerOpt.get();
            boolean outgoing = a.getToClubId().equals(save.getManagedClubId());
            if (outgoing) {
                double keep = negotiation.keepProbability(
                        clubs.get(a.getFromClubId()).getReputation(),
                        clubs.get(a.getToClubId()).getReputation(), player.getAge());
                if (rng.nextDouble() < keep) {
                    a.cancel();
                    player.setContractYears(1 + renewalPolicy.renewYears(rng));
                    playerRepository.save(player);
                } else {
                    a.lock();
                }
            } else {
                a.lock();
            }
            agreementRepository.save(a);
        }
        return agreementRepository.findBySaveGameIdAndStatus(saveGameId, PreContractStatus.LOCKED)
                .stream().collect(Collectors.toMap(PreContractAgreement::getPlayerId,
                        Function.identity()));
    }
```

Note: the method takes the already-loaded `SaveGame` (the rollover holds it) so `PreContractService` needs no new repository dependency.

- [ ] **Step 3: Wire SeasonRolloverService**

Constructor/field: inject `PreContractService preContractService` and `PreContractAgreementRepository preContractAgreementRepository` (imports from `br.com.carvoeiromanager.contract`).

Before the player loop (right after the `rng` is created and the save entity `saveGame` is loaded), add:

```java
        // Resolve any pre-contract deal still AGREED, then map LOCKED deals by player so
        // the contract-tick pass below executes them instead of lapsing/renewing.
        Map<UUID, PreContractAgreement> lockedDeals =
                preContractService.resolveRemainingAgreements(saveGame, rng);
```

Add two lists next to the existing ones:

```java
        List<SquadChangeResponse> preContractArrivals = new ArrayList<>();
        List<SquadChangeResponse> preContractDepartures = new ArrayList<>();
```

Replace the contract-expiry block inside the loop (the current `if (player.getContractYears() <= 0) { if (managed) {...} else { player.setContractYears(2 + rng.nextInt(3)); } }`) with:

```java
                if (player.getContractYears() <= 0) {
                    PreContractAgreement deal = lockedDeals.get(player.getId());
                    if (deal != null) {
                        // A binding pre-contract executes: free move, agreed terms.
                        player.setClubId(deal.getToClubId());
                        player.setWage(deal.getWage());
                        player.setContractYears(deal.getContractYears());
                        SquadChangeResponse change = new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge());
                        if (deal.getToClubId().equals(managedClubId)) {
                            preContractArrivals.add(change);
                        } else if (deal.getFromClubId().equals(managedClubId)) {
                            preContractDepartures.add(change);
                        }
                    } else {
                        // No deal: the contract simply lapses — managed AND AI players
                        // alike now fall into the free-agent pool (the silent AI
                        // auto-renew is gone; AI clubs renew proactively in-season).
                        if (managed) {
                            expiredContracts.add(new SquadChangeResponse(
                                    player.getName(), player.getPosition().name(), player.getAge()));
                        }
                        player.setClubId(null);
                        player.setContractYears(0);
                    }
                }
```

After `playerRepository.saveAll(toSave);` add:

```java
        // Pre-contract agreements are season-scoped: executed or dead, all purged now.
        preContractAgreementRepository.deleteBySaveGameId(saveGameId);
```

Extend `RolloverResponse` with the two new components (append; update its construction site):

```java
public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures,
        List<SquadChangeResponse> preContractArrivals,
        List<SquadChangeResponse> preContractDepartures
) {}
```

and the return statement gains `List.copyOf(preContractArrivals), List.copyOf(preContractDepartures)`. Fix any other `new RolloverResponse(` sites (`grep -rn "new RolloverResponse" backend/src`).

Retirement note (leave as a code comment where retirement is handled): a player who retires at rollover dies with his LOCKED deal — the deal is simply purged; this is intended (the map lookup only happens for non-retired players).

- [ ] **Step 4: Run the rollover ITs to green, then the full season+contract suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.*' --tests 'br.com.carvoeiromanager.contract.*' --tests 'br.com.carvoeiromanager.career.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/ backend/src/main/java/br/com/carvoeiromanager/contract/PreContractService.java backend/src/test/java/br/com/carvoeiromanager/season/
git commit -m "feat(season): rollover executes locked pre-contracts and stops silently renewing AI players"
```

---

### Task 7: Frontend data plumbing — types, api module, cache invalidation

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/precontract.ts`
- Modify: the advance-mutation and rollover-mutation invalidation hubs (locate with `grep -rn "invalidateQueries" frontend/src | grep -i "freeAgents"` — add the two new keys everywhere `['freeAgents', saveGameId]` is invalidated on advance/rollover).

**Interfaces:**
- Consumes: Task 3/4/6 backend API.
- Produces (Task 8 relies on): types `PreContractEligible`, `PreContractAgreement`, `PreContractOfferResult`, `PreContractEvent`; api fns `getPreContractEligible`, `makePreContractOffer`, `acceptPreContractCounter`, `getPreContractAgreements`, `withdrawPreContract`; query keys `['preContractEligible', saveGameId]` and `['preContractAgreements', saveGameId]`.

- [ ] **Step 1: Add the types**

In `frontend/src/api/types.ts`:

```ts
export type PreContractEligible = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubName: string
  wageDemand: number
}

export type PreContractAgreement = {
  id: string
  playerId: string
  playerName: string
  position: string
  age: number
  overall: number
  fromClubName: string
  toClubName: string
  wage: number
  contractYears: number
  status: 'AGREED' | 'LOCKED' | 'CANCELLED'
  /** true when this is a threat: an AI club is taking one of YOUR players. */
  incoming: boolean
}

export type PreContractOfferResult = {
  status: 'AGREED' | 'COUNTERED'
  demandedWage: number | null
  agreement: PreContractAgreement | null
}

export type PreContractEvent = {
  kind: 'AGREED_OUT' | 'POACHED_IN' | 'LOCKED_OUT' | 'CANCELLED_OUT' | 'LOCKED_IN'
  playerName: string
  clubName: string
  wage: number | null
  contractYears: number | null
}
```

Extend `AdvanceResponse`: add `'PRECONTRACT'` to the `type` union and append `preContractEvents?: PreContractEvent[] | null` (null-guarded like `transfers`). Extend the frontend `RolloverResponse` type (same file — find `expiredContracts` in a Rollover-named type) with `preContractArrivals: SquadChange[]` and `preContractDepartures: SquadChange[]` matching the existing list-entry type used by `expiredContracts`.

- [ ] **Step 2: Create the api module**

`frontend/src/api/precontract.ts`:

```ts
import { apiDelete, apiGet, apiPost } from './client'
import type { PreContractAgreement, PreContractEligible, PreContractOfferResult } from './types'

export function getPreContractEligible(saveGameId: string, token: string): Promise<PreContractEligible[]> {
  return apiGet<PreContractEligible[]>(`/save-games/${saveGameId}/pre-contracts/eligible`, token)
}

export function makePreContractOffer(
  saveGameId: string,
  req: { playerId: string; wage: number; contractYears: number },
  token: string,
): Promise<PreContractOfferResult> {
  return apiPost<PreContractOfferResult>(`/save-games/${saveGameId}/pre-contracts/offers`, req, token)
}

export function acceptPreContractCounter(
  saveGameId: string,
  playerId: string,
  req: { contractYears: number },
  token: string,
): Promise<PreContractOfferResult> {
  return apiPost<PreContractOfferResult>(
    `/save-games/${saveGameId}/pre-contracts/offers/${playerId}/accept-counter`, req, token)
}

export function getPreContractAgreements(saveGameId: string, token: string): Promise<PreContractAgreement[]> {
  return apiGet<PreContractAgreement[]>(`/save-games/${saveGameId}/pre-contracts/agreements`, token)
}

export function withdrawPreContract(saveGameId: string, agreementId: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${saveGameId}/pre-contracts/agreements/${agreementId}`, token)
}
```

- [ ] **Step 3: Wire cache invalidation**

Run `grep -rn "freeAgents" frontend/src --include='*.tsx' --include='*.ts' | grep invalidate`. At every advance-driven and rollover-driven hub that invalidates `['freeAgents', saveGameId]`, add alongside:

```ts
void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
```

(Advancing processes TRANSFER days — renewals/locks/poaches change both lists; rollover executes deals.)

- [ ] **Step 4: Type-check and commit**

Run: `cd frontend && npx tsc -b`
Expected: clean.

```bash
git add frontend/src/api/types.ts frontend/src/api/precontract.ts
git commit -m "feat(precontract): frontend types, api module, and cache invalidation wiring"
```

(Include the invalidation-hub files touched in Step 3 in the `git add`.)

---

### Task 8: Frontend UI — Pré-contratos tab, threats defense, news cards, rollover summary

**Files:**
- Create: `frontend/src/features/transfer/PreContractsPanel.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx` (new 4th tab)
- Modify: `frontend/src/features/match/AdvanceEventCard.tsx` (PRECONTRACT type + TRANSFER-day events block)
- Modify: the rollover/season-summary component that renders `expiredContracts` (locate with `grep -rln "expiredContracts" frontend/src`) — add the two new sections.
- Test: `frontend/src/features/transfer/PreContractsPanel.test.tsx`

**Interfaces:**
- Consumes: Task 7's types/api; existing `renewContract` api fn (grep `renew` in `frontend/src/api/` for the exact name/signature — it exists for the squad Renovar flow).

- [ ] **Step 1: Build PreContractsPanel**

`frontend/src/features/transfer/PreContractsPanel.tsx` — follow `FreeAgentsPanel`'s structure (queries with `staleTime: Infinity`, MUI table/cards, dialog state). Complete component:

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import {
  acceptPreContractCounter, getPreContractAgreements, getPreContractEligible,
  makePreContractOffer, withdrawPreContract,
} from '../../api/precontract'
import { renewContract } from '../../api/career'
import type { PreContractAgreement, PreContractEligible } from '../../api/types'
import { formatBRL } from '../../utils/format'

const STATUS_LABEL: Record<string, string> = {
  AGREED: 'Acordado',
  LOCKED: 'Fechado',
  CANCELLED: 'Cancelado',
}

const STATUS_COLOR: Record<string, 'warning' | 'success' | 'default'> = {
  AGREED: 'warning',
  LOCKED: 'success',
  CANCELLED: 'default',
}

type Props = {
  saveGameId: string
  token: string
  managedClubId: string
  windowOpen: boolean
}

export default function PreContractsPanel({ saveGameId, token, managedClubId, windowOpen }: Props) {
  const queryClient = useQueryClient()
  const [target, setTarget] = useState<PreContractEligible | null>(null)
  const [wage, setWage] = useState('')
  const [years, setYears] = useState(3)
  const [counterWage, setCounterWage] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const enabled = Boolean(saveGameId && token)
  const eligible = useQuery({
    queryKey: ['preContractEligible', saveGameId],
    queryFn: () => getPreContractEligible(saveGameId, token),
    enabled: enabled && windowOpen,
    staleTime: Infinity,
  })
  const agreements = useQuery({
    queryKey: ['preContractAgreements', saveGameId],
    queryFn: () => getPreContractAgreements(saveGameId, token),
    enabled,
    staleTime: Infinity,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
  }

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      makePreContractOffer(saveGameId, v, token),
    onSuccess: (r) => {
      if (r.status === 'COUNTERED') {
        setCounterWage(r.demandedWage)
      } else {
        setTarget(null)
        setCounterWage(null)
        invalidate()
      }
    },
    onError: (e: Error) => setError(e.message),
  })

  const acceptCounterMut = useMutation({
    mutationFn: (v: { playerId: string; contractYears: number }) =>
      acceptPreContractCounter(saveGameId, v.playerId, { contractYears: v.contractYears }, token),
    onSuccess: () => {
      setTarget(null)
      setCounterWage(null)
      invalidate()
    },
    onError: (e: Error) => setError(e.message),
  })

  const withdrawMut = useMutation({
    mutationFn: (agreementId: string) => withdrawPreContract(saveGameId, agreementId, token),
    onSuccess: invalidate,
    onError: (e: Error) => setError(e.message),
  })

  const defendMut = useMutation({
    mutationFn: (a: PreContractAgreement) =>
      renewContract(saveGameId, a.playerId,
        { wage: Math.ceil(a.wage * 1.05 * 100) / 100, contractYears: 3 }, token),
    onSuccess: () => {
      invalidate()
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
    },
    onError: (e: Error) => setError(e.message),
  })

  const threats = (agreements.data ?? []).filter(a => a.incoming)
  const outgoing = (agreements.data ?? []).filter(a => !a.incoming)

  return (
    <Box>
      {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>{error}</Alert>}

      {!windowOpen && (
        <Alert severity="info" sx={{ mb: 2 }}>
          A janela de pré-contratos abre em 15 de julho — seis meses antes dos contratos expirarem.
        </Alert>
      )}

      {threats.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>⚠️ Jogadores de saída</Typography>
          {threats.map(a => (
            <Stack key={a.id} direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
              <Typography>
                <strong>{a.playerName}</strong> assinou pré-contrato com {a.toClubName}{' '}
                ({formatBRL(a.wage)}/sem, {a.contractYears} anos)
              </Typography>
              <Chip size="small" color={STATUS_COLOR[a.status]} label={STATUS_LABEL[a.status]} />
              {a.status === 'AGREED' && (
                <Button size="small" variant="contained" color="warning"
                        disabled={defendMut.isPending}
                        onClick={() => defendMut.mutate(a)}>
                  Renovar para defender ({formatBRL(Math.ceil(a.wage * 1.05 * 100) / 100)})
                </Button>
              )}
            </Stack>
          ))}
        </Box>
      )}

      {outgoing.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Meus acordos</Typography>
          {outgoing.map(a => (
            <Stack key={a.id} direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
              <Typography>
                <strong>{a.playerName}</strong> ({a.position}, {a.age}) — vem de {a.fromClubName}{' '}
                por {formatBRL(a.wage)}/sem, {a.contractYears} anos
              </Typography>
              <Chip size="small" color={STATUS_COLOR[a.status]} label={STATUS_LABEL[a.status]} />
              {a.status === 'AGREED' && (
                <Button size="small" disabled={withdrawMut.isPending}
                        onClick={() => withdrawMut.mutate(a.id)}>
                  Desistir
                </Button>
              )}
            </Stack>
          ))}
        </Box>
      )}

      <Typography variant="h6" sx={{ mb: 1 }}>Jogadores em fim de contrato</Typography>
      {windowOpen && (eligible.data?.length ?? 0) === 0 && !eligible.isLoading && (
        <Typography color="text.secondary">Nenhum jogador disponível para pré-contrato.</Typography>
      )}
      {windowOpen && (eligible.data?.length ?? 0) > 0 && (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Jogador</TableCell>
              <TableCell>Pos</TableCell>
              <TableCell align="right">Idade</TableCell>
              <TableCell align="right">Geral</TableCell>
              <TableCell>Clube</TableCell>
              <TableCell align="right">Pedida</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {eligible.data!.map(p => (
              <TableRow key={p.playerId} hover>
                <TableCell>{p.name}</TableCell>
                <TableCell>{p.position}</TableCell>
                <TableCell align="right">{p.age}</TableCell>
                <TableCell align="right">{p.overall}</TableCell>
                <TableCell>{p.clubName}</TableCell>
                <TableCell align="right">{formatBRL(p.wageDemand)}</TableCell>
                <TableCell>
                  <Button size="small" variant="outlined" onClick={() => {
                    setTarget(p)
                    setWage(String(p.wageDemand))
                    setYears(3)
                    setCounterWage(null)
                    setError(null)
                  }}>
                    Propor
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Dialog open={Boolean(target)} onClose={() => setTarget(null)} fullWidth maxWidth="xs">
        <DialogTitle>Pré-contrato — {target?.name}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Pedida do jogador: <strong>{formatBRL(target?.wageDemand ?? 0)}</strong>/semana
          </Typography>
          {counterWage != null ? (
            <Alert severity="warning" sx={{ mb: 2 }}>
              O jogador recusou, mas aceita por <strong>{formatBRL(counterWage)}</strong>/semana.
            </Alert>
          ) : (
            <TextField label="Salário semanal" type="number" fullWidth sx={{ mb: 2 }}
                       value={wage} onChange={e => setWage(e.target.value)} />
          )}
          <TextField label="Anos de contrato" type="number" fullWidth
                     value={years}
                     onChange={e => setYears(Number(e.target.value))}
                     slotProps={{ htmlInput: { min: 1, max: 5 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTarget(null)}>Cancelar</Button>
          {counterWage != null ? (
            <Button variant="contained" disabled={acceptCounterMut.isPending}
                    onClick={() => target && acceptCounterMut.mutate({ playerId: target.playerId, contractYears: years })}>
              Aceitar contraproposta
            </Button>
          ) : (
            <Button variant="contained" disabled={offerMut.isPending || !wage}
                    onClick={() => target && offerMut.mutate({ playerId: target.playerId, wage: Number(wage), contractYears: years })}>
              Enviar proposta
            </Button>
          )}
        </DialogActions>
      </Dialog>
    </Box>
  )
}
```

Adaptation notes for the implementer (verify with grep, keep the panel's behavior identical):
- `formatBRL` — confirm its import path (used by `AdvanceEventCard`); adjust the import.
- `renewContract` api fn — confirm exact exported name/signature in `frontend/src/api/career.ts` (the squad Renovar flow uses it); adjust the import and call.
- If MUI version in this repo uses `inputProps` instead of `slotProps.htmlInput`, match the codebase's existing usage (grep an existing numeric `TextField`).

- [ ] **Step 2: Add the tab in TransferMarketPage**

Desktop `Tabs` block gains `<Tab label="Pré-contratos" />` after "Agentes livres"; the mobile `NativeSelect` gains the matching `<option value={3}>Pré-contratos</option>` (mirror how options 0–2 are declared). After the `{tab === 2 && ...}` line add:

```tsx
      {tab === 3 && (
        <PreContractsPanel
          saveGameId={saveGameId}
          token={token ?? ''}
          managedClubId={managedClubId}
          windowOpen={preContractWindowOpen}
        />
      )}
```

with, near the other derived values:

```tsx
  const currentDate = save.data?.currentDate ? new Date(save.data.currentDate) : null
  const preContractWindowOpen = currentDate
    ? currentDate >= new Date(currentDate.getFullYear(), 6, 15) // July 15 (month is 0-based)
    : false
```

- [ ] **Step 3: Render pre-contract news in AdvanceEventCard**

Header map: add `PRECONTRACT → 'Pré-contrato'` where TRANSFER/FINISHED get their labels. Body: add after the TRANSFER blocks:

```tsx
          {(result.preContractEvents?.length ?? 0) > 0 && (
            <TimelineNode dot="🖋️">
              <Typography component="strong" sx={{ fontWeight: 700 }}>Pré-contratos</Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.preContractEvents!.map((e, i) => (
                  <ListItem key={`${e.playerName}-${i}`} disablePadding>
                    {e.kind === 'AGREED_OUT' && (
                      <>✅ {e.playerName} aceitou seu pré-contrato (vem de {e.clubName})</>
                    )}
                    {e.kind === 'POACHED_IN' && (
                      <>⚠️ {e.playerName} assinou pré-contrato com {e.clubName} — renove para defender!</>
                    )}
                    {e.kind === 'LOCKED_OUT' && (
                      <>🔒 Pré-contrato de {e.playerName} confirmado — chega na próxima temporada</>
                    )}
                    {e.kind === 'CANCELLED_OUT' && (
                      <>❌ {e.clubName} renovou com {e.playerName} — seu pré-contrato caiu</>
                    )}
                    {e.kind === 'LOCKED_IN' && (
                      <>🔒 {e.playerName} vai mesmo para {e.clubName} no fim do contrato</>
                    )}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}
```

This block renders for both `TRANSFER` items (market-day events) and `PRECONTRACT` items (the synthetic agreed-news) since it keys on the field, not the type. No other change to the TRANSFER blocks.

- [ ] **Step 4: Rollover summary sections**

In the component that renders `expiredContracts` (grep `expiredContracts` in `frontend/src`), duplicate that section twice with the new lists and pt-BR titles "Chegadas por pré-contrato" (`preContractArrivals`) and "Saídas por pré-contrato" (`preContractDepartures`), null-guarded (`rollover.preContractArrivals ?? []`) since older cached responses lack the fields.

- [ ] **Step 5: Component tests**

`frontend/src/features/transfer/PreContractsPanel.test.tsx` — mirror the setup style of the existing `TransferMarketPage.test.tsx` (same render helpers/mocks; check that file first and reuse its patterns). Cover, mocking the api module (`vi.mock('../../api/precontract')` + `vi.mock` for the career module's renew fn):

```tsx
// Test 1: renders eligible players with club name and demand, and the Propor button.
// Test 2: offer flow — makePreContractOffer resolves { status: 'COUNTERED', demandedWage: 1100 } →
//         the counter Alert appears with "Aceitar contraproposta"; clicking it calls
//         acceptPreContractCounter with the player id.
// Test 3: an incoming AGREED threat renders the "Renovar para defender" button; clicking it
//         calls renewContract with wage = ceil(agreedWage * 1.05 * 100) / 100.
```

Write the three tests in full following the existing test file's mock/render conventions (the conventions are repo-specific; copy them from `TransferMarketPage.test.tsx`, do not invent new scaffolding).

- [ ] **Step 6: Verify and commit**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: type-check clean, all tests pass (existing + 3 new).

```bash
git add frontend/src/features/transfer/ frontend/src/features/match/AdvanceEventCard.tsx frontend/src/api/
git commit -m "feat(precontract): Pré-contratos tab — offers with counter round, threat defense, news and rollover cards"
```

(Include the rollover-summary component file in the `git add`.)

---

### Task 9: Full suites and consistency sweep

**Files:** none new — fixes only if suites surface fallout.

- [ ] **Step 1: Full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL. Likely fallout to check if red: tests constructing `AdvanceResponse`/`RolloverResponse` with the old arity; season tests asserting TRANSFER-event counts; any test that relied on AI auto-renew keeping squads static across rollover.

- [ ] **Step 2: Full frontend suite**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: clean + all green.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A backend/src frontend/src
git commit -m "test: align remaining suites with pre-contract mechanics"
```

(Skip the commit if Steps 1–2 passed with no changes.)

---

## Self-Review Notes

- **Spec coverage:** derived eligibility (Tasks 1/3), AI proactive renewals + organic FA lapse (Tasks 4/6), offer/counter/reject at 1.10/0.70 (Tasks 2/3), counter-renew formula + TRANSFER-day resolution (Tasks 2/4), AI poaching with 1/day cap + defense at 1.05 (Tasks 4/5), rollover execution incl. still-AGREED rules and purge (Task 6), API+UI+news (Tasks 3/7/8), out-of-scope items untouched throughout.
- **Two deliberate spec adaptations** (Global Constraints, items 1–2): pre-contract TRANSFER calendar stops must be *created* (none exist after June 24 today), and rollover-execution visibility rides `RolloverResponse` because rollover purges news. Both preserve the spec's mechanics; flag both at the final whole-branch review.
- **Idempotent-by-seeding:** the AI renewal pass is seeded per season and ordered by id so re-running it on every stop is a no-op after the first — no "has this run?" flag needed, and the decision set is stable within a season.
- **Existing tests intentionally rewritten:** only `RolloverContractsIT.anAiPlayerWhoseContractExpiresIsAutoRenewedAndStays` (the auto-renew it asserts is the exact behavior this feature removes). `theFreeAgentPoolIsNotReplenishedAtRollover` keeps passing unchanged.
- **Frontend unknowns are named, not hand-waved:** `formatBRL`/`renewContract` import paths, MUI TextField props, and test scaffolding are repo-conventions the implementer verifies by grep against named files, with behavior fully specified here.
