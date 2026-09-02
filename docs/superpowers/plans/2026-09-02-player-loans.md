# Player Loans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager loan players out to AI clubs and borrow from them, over a negotiation the AI can counter, with the wage bill split by a negotiated share.

**Architecture:** `players.club_id` keeps meaning *where he plays*, so the ~38 files that read it stay correct untouched; ownership arrives as a new nullable `owner_club_id`, and `owner_club_id IS NOT NULL` **is** the definition of "on loan". The negotiation is a transient `loan_offer` table mirroring `transfer_offer`; the loan itself is three columns on the player, the same way a completed transfer is just the player's new `club_id`. Accepting a loan runs the existing `SquadDeparture` contract, because a loan is the third way out of the managed squad.

**Tech Stack:** Java 25 / Spring Boot, JPA with plain UUID FK columns (no associations), Flyway, JUnit 5 + AssertJ + Testcontainers (Postgres). React + TypeScript + MUI + TanStack Query + Vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-02-player-loans-design.md`

## About the test code in this plan

Most tests below are written out in full and should be used verbatim. **Four are specified by their
assertions and their fixture requirements rather than written out**, and are marked where they
appear:

- Task 10's top-scorer-ordering and squad-overflow tests
- Task 11's `LoanFixtureEligibilityIT`
- Tasks 13 and 14's dialog and page tests

Each depends on fixture helpers in classes this plan's author did not read (`SeasonRolloverServiceIT`'s
top-scorer arrangement, `AiTransferEngineIT`'s squad `filler`, the transfer market page's test
harness). Writing them from pattern-memory is how the previous plan for this repo shipped four test
snippets that diverged from the real files. **Read the named class first, then write the test in
full** — the assertions given are the requirement, not a suggestion, and none of them may be
dropped or weakened to make the fixture easier.

## Global Constraints

- **Every Gradle command MUST pin `JAVA_HOME`.** The shell profile exports Java 8 and the build dies before compiling:
  `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '...'`
- **Write the exit code INTO the log and read it back:** `./gradlew ... > /tmp/x.txt 2>&1; echo "EXIT=$?" >> /tmp/x.txt`. Never trust a shell status for a compound command ending in `echo`, and never capture after a pipe — both report success for a failed build.
- **Integration tests (`*IT`) need Docker (OrbStack).** Every IT failing at once with the chain ending at `DockerClientProviderStrategy` is a Docker outage, not a code failure: `open -a OrbStack`, retry, say so.
- **Frontend type-checking MUST use `npx tsc -b`** (or `npm run build`). Bare `npx tsc --noEmit` resolves zero files here and always reports clean.
- **Money is `BigDecimal` scaled to 2**, never `double`; compare with `isEqualByComparingTo`.
- **Deterministic, no RNG** in the plausibility rule, the negotiator, or any guard.
- **UI copy is pt-BR.**
- **`LEVEL_TOLERANCE = 3`** — the borrower-level test is `player.overall >= borrowerClub.reputation - 3`.
- **A loan requires `contractYears >= 2`**, in both directions.
- **`SquadRules.MAX_SQUAD_SIZE` becomes 45** (from 40).
- **Work directly on `main`** — solo repo, no branches or worktrees. `git commit` is authorized; `git push` is not (the controller pushes).
- `TransferServiceSellingIT` is a known ~26% flake from a correlated `Random` seed. One failure → re-run that class alone before concluding anything.

---

### Task 1: The loan columns on `Player`

The fact of a loan, and the two domain mutators that set and clear it. Nothing reads them yet.

**Files:**
- Create: `backend/src/main/resources/db/migration/V54__player_loans.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerLoanEntityIT.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `UUID Player.getOwnerClubId()`, `Integer Player.getLoanWageShare()`, `Boolean Player.getLoanMayFaceOwner()`
  - `boolean Player.isOnLoan()` — `ownerClubId != null`
  - `void Player.loanTo(UUID borrowerClubId, int wageShare, boolean mayFaceOwner)` — records the current club as owner, moves him to the borrower, stores the terms
  - `void Player.returnFromLoan()` — moves him back to his owner and clears all three columns

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/player/PlayerLoanEntityIT.java`. Follow the existing `PlayerContractEntityIT` in the same package for the `@SpringBootTest` / `PostgresTestContainer` setup — read it first and mirror its field block and career creation.

```java
package br.com.carvoeiromanager.player;

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

import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PlayerLoanEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanentity_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anotherClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
    }

    @Test
    void loaningOutRecordsTheOwnerTheTermsAndTheNewClub() {
        Career c = createCareer();
        UUID borrower = anotherClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        p.loanTo(borrower, 40, false);
        playerRepository.save(p);

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getClubId()).isEqualTo(borrower);
        assertThat(reloaded.getLoanWageShare()).isEqualTo(40);
        assertThat(reloaded.getLoanMayFaceOwner()).isFalse();
    }

    @Test
    void returningSendsHimHomeAndClearsEveryLoanColumn() {
        Career c = createCareer();
        UUID borrower = anotherClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        p.loanTo(borrower, 40, false);
        playerRepository.save(p);

        Player onLoan = playerRepository.findById(p.getId()).orElseThrow();
        onLoan.returnFromLoan();
        playerRepository.save(onLoan);

        Player home = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(home.isOnLoan()).isFalse();
        assertThat(home.getClubId()).isEqualTo(c.clubId());
        assertThat(home.getOwnerClubId()).isNull();
        assertThat(home.getLoanWageShare()).isNull();
        assertThat(home.getLoanMayFaceOwner()).isNull();
    }

    @Test
    void aPlayerWhoIsNotOnLoanHasNoLoanState() {
        Career c = createCareer();
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThat(p.isOnLoan()).isFalse();
        assertThat(p.getOwnerClubId()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*PlayerLoanEntityIT' > /tmp/t1.txt 2>&1; echo "EXIT=$?" >> /tmp/t1.txt
```

Expected: FAIL — compile error, `cannot find symbol: method loanTo(UUID,int,boolean)`.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V54__player_loans.sql`:

```sql
-- A loan splits "who owns the player" from "who he plays for". club_id keeps meaning the latter,
-- so every existing reader stays correct; owner_club_id being non-null IS the definition of
-- "on loan". loan_wage_share is the percentage the OWNER pays, 0-100.
ALTER TABLE players ADD COLUMN owner_club_id UUID;
ALTER TABLE players ADD COLUMN loan_wage_share INT;
ALTER TABLE players ADD COLUMN loan_may_face_owner BOOLEAN;
```

- [ ] **Step 4: Add the fields and mutators to `Player`**

Add the columns beside `releasedByClubId`:

```java
    /**
     * The club that owns this player while he is out on loan, or null when he plays for his
     * owner. Non-null is the definition of "on loan": {@code clubId} always means the club he
     * currently plays for, which is what the rest of the game asks.
     */
    @Column(name = "owner_club_id")
    private UUID ownerClubId;

    /** Percentage of this player's wage the OWNER pays while he is on loan; null when he is not. */
    @Column(name = "loan_wage_share")
    private Integer loanWageShare;

    /** Whether the borrowing club may field him against his owner; null when he is not on loan. */
    @Column(name = "loan_may_face_owner")
    private Boolean loanMayFaceOwner;
```

Add the accessors beside `getReleasedByClubId()`:

```java
    public UUID getOwnerClubId() { return ownerClubId; }
    public Integer getLoanWageShare() { return loanWageShare; }
    public Boolean getLoanMayFaceOwner() { return loanMayFaceOwner; }
    public boolean isOnLoan() { return ownerClubId != null; }
```

And the two domain mutators beside `releaseFrom(UUID)`:

```java
    /**
     * Sends this player out on loan: his current club becomes his owner, and he starts playing
     * for {@code borrowerClubId} under the agreed terms. The fee-free nature of a loan, the
     * lineup cleanup and the outstanding-offer rejection are the service's business.
     */
    public void loanTo(UUID borrowerClubId, int wageShare, boolean mayFaceOwner) {
        this.ownerClubId = this.clubId;
        this.clubId = borrowerClubId;
        this.loanWageShare = wageShare;
        this.loanMayFaceOwner = mayFaceOwner;
    }

    /** Ends the loan: he goes home to his owner and carries none of its terms with him. */
    public void returnFromLoan() {
        this.clubId = this.ownerClubId;
        this.ownerClubId = null;
        this.loanWageShare = null;
        this.loanMayFaceOwner = null;
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*PlayerLoanEntityIT' > /tmp/t1.txt 2>&1; echo "EXIT=$?" >> /tmp/t1.txt
```

Expected: PASS, three tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V54__player_loans.sql backend/src/main/java/br/com/carvoeiromanager/player/Player.java backend/src/test/java/br/com/carvoeiromanager/player/PlayerLoanEntityIT.java
git commit -m "feat(loan): split who owns a player from who he plays for"
```

---

### Task 2: Raise the squad ceiling to 45

A one-line constant change with four behavioural consequences. It gets its own commit so a reviewer can weigh it on its own.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/common/SquadRulesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `SquadRules.MAX_SQUAD_SIZE == 45`.

**Every reader**, verified — all four take the new value automatically, which is the point:
`ContractService:169` (signing a free agent), `PreContractService:411` (pre-contract arrivals),
`AiTransferEngine:115` (which AI clubs may buy), `TransferService:406` (managed buying).
`ContractServiceSignIT` and `TransferServiceBuyingIT` build squads *relative to* the constant, so
they adapt without edits — do not touch them.

- [ ] **Step 1: Update the failing test first**

`SquadRulesTest` pins the value by direct assertion, so it is the test that must change before the constant:

```java
    @Test
    void squadSizeLimitsAreTheOnesEveryDeparturePathCountsAgainst() {
        assertThat(SquadRules.MAX_SQUAD_SIZE).isEqualTo(45);
        assertThat(SquadRules.MIN_SQUAD_SIZE).isEqualTo(14);
    }
```

Replace the existing assertion on 40 with this. Keep whatever other tests the class already has.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadRulesTest' > /tmp/t2.txt 2>&1; echo "EXIT=$?" >> /tmp/t2.txt
```

Expected: FAIL — `expected: 45 but was: 40`.

- [ ] **Step 3: Raise the constant**

In `SquadRules`:

```java
    /**
     * Ceiling on the professional squad. Raised from 40 to 45 when loans arrived: a club that
     * lends several players can have them all return at the same rollover, and the cap is
     * otherwise only checked when adding one player at a time.
     */
    public static final int MAX_SQUAD_SIZE = 45;
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadRulesTest' --tests '*ContractServiceSignIT' --tests '*TransferServiceBuyingIT' > /tmp/t2.txt 2>&1; echo "EXIT=$?" >> /tmp/t2.txt
```

Expected: PASS. The two ITs are included because they build squads against the constant — they should adapt with no edit, and if either fails, that is a real finding worth reporting rather than patching around.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java backend/src/test/java/br/com/carvoeiromanager/common/SquadRulesTest.java
git commit -m "feat(squad): raise the professional squad ceiling to 45"
```

---

### Task 3: When a loan is plausible

The two questions that decide whether a loan is worth discussing at all, as a pure deterministic component. No RNG, no database writes.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanPlausibility.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanRefusal.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanPlausibilityTest.java`

**Interfaces:**
- Consumes: `SellerContext(boolean listed, boolean keyPlayer, boolean noCover, ClubFinancialHealthEvaluator.FinancialHealth health)` from `br.com.carvoeiromanager.transfer`, built by `SellerContextFactory.build(Player, Club)`.
- Produces:
  - `enum LoanRefusal { KEY_PLAYER, BELOW_LEVEL, TERMS_REJECTED }`
  - `Optional<LoanRefusal> LoanPlausibility.check(SellerContext ownerContext, int playerOverall, int borrowerReputation)` — empty when the loan is worth negotiating.
  - `LoanPlausibility.LEVEL_TOLERANCE = 3`

Why these two questions produce football's real loan flows, with no rule about club size: a small club's reserve is below a big club's level and fails the second test; a small club's star is a key player there and fails the first. Both directions of that asymmetry are pinned by tests below.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanPlausibilityTest.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth;
import br.com.carvoeiromanager.transfer.SellerContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPlausibilityTest {

    private final LoanPlausibility plausibility = new LoanPlausibility();

    /** Spare at his owner: not a key player, and his position has cover. */
    private static SellerContext spare() {
        return new SellerContext(false, false, false, FinancialHealth.HEALTHY);
    }

    private static SellerContext keyPlayer() {
        return new SellerContext(false, true, false, FinancialHealth.HEALTHY);
    }

    private static SellerContext onlyOneInHisPosition() {
        return new SellerContext(false, false, true, FinancialHealth.HEALTHY);
    }

    @Test
    void aSparePlayerAtTheBorrowersLevelIsWorthNegotiating() {
        assertThat(plausibility.check(spare(), 70, 70)).isEmpty();
    }

    @Test
    void aClubWillNotLendSomeoneItNeeds() {
        assertThat(plausibility.check(keyPlayer(), 70, 70)).contains(LoanRefusal.KEY_PLAYER);
        assertThat(plausibility.check(onlyOneInHisPosition(), 70, 70))
                .contains(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void aBorrowerWillNotTakeSomeoneBeneathItsLevel() {
        // reputation 70, tolerance 3 -> 66 is refused, 67 is accepted.
        assertThat(plausibility.check(spare(), 66, 70)).contains(LoanRefusal.BELOW_LEVEL);
        assertThat(plausibility.check(spare(), 67, 70)).isEmpty();
    }

    @Test
    void theOwnersRefusalIsReportedBeforeTheBorrowersLevel() {
        // Both fail; the owner's answer is the one that ends the conversation first, because
        // no improvement to the offer can move it.
        assertThat(plausibility.check(keyPlayer(), 40, 80)).contains(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void loansUpwardAreRareByConstruction() {
        // A small club (reputation 50) and a big borrower (reputation 78).
        // Its reserve is beneath the big club's level...
        assertThat(plausibility.check(spare(), 52, 78)).contains(LoanRefusal.BELOW_LEVEL);
        // ...and its star, who would pass the level test, is a key player there.
        assertThat(plausibility.check(keyPlayer(), 79, 78)).contains(LoanRefusal.KEY_PLAYER);
        // The narrow door: a standout who is nonetheless spare at his club.
        assertThat(plausibility.check(spare(), 79, 78)).isEmpty();
    }

    @Test
    void loansDownwardAreTheEasyCase() {
        // A big club's reserve, offered to a smaller club, clears both tests comfortably.
        assertThat(plausibility.check(spare(), 72, 55)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanPlausibilityTest' > /tmp/t3.txt 2>&1; echo "EXIT=$?" >> /tmp/t3.txt
```

Expected: FAIL — compile error, package `br.com.carvoeiromanager.loan` does not exist.

- [ ] **Step 3: Create the refusal reasons**

Create `backend/src/main/java/br/com/carvoeiromanager/loan/LoanRefusal.java`:

```java
package br.com.carvoeiromanager.loan;

/**
 * Why a loan was refused. The distinction is load-bearing for the UI: the first two are refusals
 * about the PLAYER and no better offer will move them, while the third is about the OFFER and is
 * the only one worth answering with improved terms.
 */
public enum LoanRefusal {
    /** His club needs him — he is a key player there, or his position has no cover. */
    KEY_PLAYER,
    /** He is beneath the borrowing club's level. */
    BELOW_LEVEL,
    /** Plausible player, unacceptable terms. */
    TERMS_REJECTED
}
```

- [ ] **Step 4: Create the plausibility check**

Create `backend/src/main/java/br/com/carvoeiromanager/loan/LoanPlausibility.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.SellerContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether a loan is worth discussing at all, before any term is considered. Two questions, and
 * the shape of real loan markets falls out of them without a rule about club size: a small club's
 * reserve is beneath a big club's level, and its star is a key player it will not lend.
 */
@Component
public class LoanPlausibility {

    /**
     * How far below a club's reputation a player may sit and still interest it. A squad is not
     * made only of its mean, so a small margin is right; more than this and a club would take
     * players plainly beneath it.
     */
    static final int LEVEL_TOLERANCE = 3;

    /**
     * @param ownerContext      the owning club's view of the player, from {@code SellerContextFactory}
     * @param playerOverall     the player's overall
     * @param borrowerReputation the borrowing club's reputation, which is its squad quality
     * @return the reason to refuse, or empty when the loan is worth negotiating
     */
    public Optional<LoanRefusal> check(SellerContext ownerContext, int playerOverall,
                                       int borrowerReputation) {
        if (ownerContext.keyPlayer() || ownerContext.noCover()) {
            return Optional.of(LoanRefusal.KEY_PLAYER);
        }
        if (playerOverall < borrowerReputation - LEVEL_TOLERANCE) {
            return Optional.of(LoanRefusal.BELOW_LEVEL);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanPlausibilityTest' > /tmp/t3.txt 2>&1; echo "EXIT=$?" >> /tmp/t3.txt
```

Expected: PASS, six tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/ backend/src/test/java/br/com/carvoeiromanager/loan/
git commit -m "feat(loan): decide when a loan is worth negotiating at all"
```

---

### Task 4: The `loan_offer` table

The negotiation's storage, mirroring `transfer_offer` one for one. Transient and season-scoped: the offer records the haggling, and the loan itself is the three columns on the player.

**Files:**
- Create: `backend/src/main/resources/db/migration/V55__loan_offers.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOffer.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferStatus.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferRepository.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanOfferEntityIT.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum LoanOfferStatus { PENDING, COUNTERED, ACCEPTED, REJECTED, WITHDRAWN }`
  - `LoanOffer.create(saveGameId, playerId, ownerClubId, borrowerClubId, ownerWageShare, mayFaceOwner, round)` → status `PENDING`
  - `markCountered(int counterOwnerWageShare, boolean counterMayFaceOwner)`, `markAccepted()`, `markRejected()`, `markWithdrawn()`
  - getters for every column
  - `LoanOfferRepository.findBySaveGameIdAndStatusIn(...)`, `findBySaveGameIdAndPlayerIdAndStatusIn(...)`, `deleteBySaveGameId(...)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanOfferEntityIT.java`:

```java
package br.com.carvoeiromanager.loan;

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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanOfferEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired LoanOfferRepository loanOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanoffer_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private LoanOffer aPendingOffer(Career c) {
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        return LoanOffer.create(c.id(), p.getId(), c.clubId(), borrower, 40, false, 1);
    }

    @Test
    void anOfferIsBornPendingAndRoundTripsItsTerms() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        loanOfferRepository.save(offer);

        LoanOffer reloaded = loanOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LoanOfferStatus.PENDING);
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getOwnerWageShare()).isEqualTo(40);
        assertThat(reloaded.isMayFaceOwner()).isFalse();
        assertThat(reloaded.getCounterOwnerWageShare()).isNull();
        assertThat(reloaded.getCounterMayFaceOwner()).isNull();
    }

    @Test
    void counteringStoresBothCounterTermsAndFlipsTheStatus() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        offer.markCountered(65, true);
        loanOfferRepository.save(offer);

        LoanOffer reloaded = loanOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(reloaded.getCounterOwnerWageShare()).isEqualTo(65);
        assertThat(reloaded.getCounterMayFaceOwner()).isTrue();
    }

    @Test
    void openOffersForAPlayerAreFoundByStatus() {
        Career c = createCareer();
        LoanOffer offer = aPendingOffer(c);
        loanOfferRepository.save(offer);

        List<LoanOffer> open = loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(
                c.id(), offer.getPlayerId(),
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED));

        assertThat(open).extracting(LoanOffer::getId).containsExactly(offer.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanOfferEntityIT' > /tmp/t4.txt 2>&1; echo "EXIT=$?" >> /tmp/t4.txt
```

Expected: FAIL — compile error, `LoanOffer` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V55__loan_offers.sql`:

```sql
-- A loan negotiation. Season-scoped and purged at rollover, exactly like transfer_offer: the
-- offer records the haggling, while the loan itself lives on the player's three loan columns.
CREATE TABLE loan_offer (
    id                       UUID PRIMARY KEY,
    save_game_id             UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id                UUID NOT NULL,
    owner_club_id            UUID NOT NULL,
    borrower_club_id         UUID NOT NULL,
    owner_wage_share         INT NOT NULL,
    may_face_owner           BOOLEAN NOT NULL,
    counter_owner_wage_share INT,
    counter_may_face_owner   BOOLEAN,
    status                   VARCHAR(12) NOT NULL,
    round                    INT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_loan_offer_save_status ON loan_offer(save_game_id, status);
CREATE INDEX idx_loan_offer_save_player ON loan_offer(save_game_id, player_id);
```

- [ ] **Step 4: Create the status enum**

Create `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferStatus.java`:

```java
package br.com.carvoeiromanager.loan;

/** Mirrors TransferOfferStatus — a loan negotiation has the same five states as a transfer one. */
public enum LoanOfferStatus {
    PENDING, COUNTERED, ACCEPTED, REJECTED, WITHDRAWN
}
```

- [ ] **Step 5: Create the entity**

Create `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOffer.java`, following `TransferOffer`'s compact field style:

```java
package br.com.carvoeiromanager.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single loan negotiation (season-scoped; purged at rollover). {@code ownerWageShare} is the
 * percentage of the wage the OWNER pays, and {@code mayFaceOwner} whether the borrower may field
 * him against his owner — the two terms the AI can counter.
 */
@Entity
@Table(name = "loan_offer")
public class LoanOffer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "owner_club_id", nullable = false) private UUID ownerClubId;
    @Column(name = "borrower_club_id", nullable = false) private UUID borrowerClubId;
    @Column(name = "owner_wage_share", nullable = false) private int ownerWageShare;
    @Column(name = "may_face_owner", nullable = false) private boolean mayFaceOwner;
    @Column(name = "counter_owner_wage_share") private Integer counterOwnerWageShare;
    @Column(name = "counter_may_face_owner") private Boolean counterMayFaceOwner;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12) private LoanOfferStatus status;
    @Column(name = "round", nullable = false) private int round;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected LoanOffer() { }

    private LoanOffer(UUID id, UUID saveGameId, UUID playerId, UUID ownerClubId, UUID borrowerClubId,
                      int ownerWageShare, boolean mayFaceOwner, LoanOfferStatus status, int round,
                      Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.playerId = playerId;
        this.ownerClubId = ownerClubId; this.borrowerClubId = borrowerClubId;
        this.ownerWageShare = ownerWageShare; this.mayFaceOwner = mayFaceOwner;
        this.status = status; this.round = round; this.createdAt = createdAt;
    }

    public static LoanOffer create(UUID saveGameId, UUID playerId, UUID ownerClubId,
                                   UUID borrowerClubId, int ownerWageShare, boolean mayFaceOwner,
                                   int round) {
        return new LoanOffer(UUID.randomUUID(), saveGameId, playerId, ownerClubId, borrowerClubId,
                ownerWageShare, mayFaceOwner, LoanOfferStatus.PENDING, round, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getOwnerClubId() { return ownerClubId; }
    public UUID getBorrowerClubId() { return borrowerClubId; }
    public int getOwnerWageShare() { return ownerWageShare; }
    public boolean isMayFaceOwner() { return mayFaceOwner; }
    public Integer getCounterOwnerWageShare() { return counterOwnerWageShare; }
    public Boolean getCounterMayFaceOwner() { return counterMayFaceOwner; }
    public LoanOfferStatus getStatus() { return status; }
    public int getRound() { return round; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCountered(int counterOwnerWageShare, boolean counterMayFaceOwner) {
        this.counterOwnerWageShare = counterOwnerWageShare;
        this.counterMayFaceOwner = counterMayFaceOwner;
        this.status = LoanOfferStatus.COUNTERED;
    }
    public void markAccepted() { this.status = LoanOfferStatus.ACCEPTED; }
    public void markRejected() { this.status = LoanOfferStatus.REJECTED; }
    public void markWithdrawn() { this.status = LoanOfferStatus.WITHDRAWN; }
}
```

- [ ] **Step 6: Create the repository**

Create `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferRepository.java`:

```java
package br.com.carvoeiromanager.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LoanOfferRepository extends JpaRepository<LoanOffer, UUID> {
    List<LoanOffer> findBySaveGameIdAndStatusIn(UUID saveGameId, Collection<LoanOfferStatus> statuses);

    List<LoanOffer> findBySaveGameIdAndPlayerIdAndStatusIn(UUID saveGameId, UUID playerId,
                                                           Collection<LoanOfferStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 7: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanOfferEntityIT' > /tmp/t4.txt 2>&1; echo "EXIT=$?" >> /tmp/t4.txt
```

Expected: PASS, three tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V55__loan_offers.sql backend/src/main/java/br/com/carvoeiromanager/loan/ backend/src/test/java/br/com/carvoeiromanager/loan/LoanOfferEntityIT.java
git commit -m "feat(loan): store a loan negotiation and its counter terms"
```

---

### Task 5: What the AI does with the terms

Given a plausible loan, whether the offered terms work — and if not, what to counter. Pure, deterministic, no database.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanTerms.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanNegotiationResult.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanNegotiator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanNegotiatorTest.java`

**Interfaces:**
- Consumes: `NegotiationDecision {ACCEPT, REJECT, COUNTER}` from `br.com.carvoeiromanager.transfer`; `LoanPlausibility.LEVEL_TOLERANCE` (Task 3).
- Produces:
  - `record LoanTerms(int ownerWageShare, boolean mayFaceOwner)`
  - `record LoanNegotiationResult(NegotiationDecision decision, LoanTerms counter)` — `counter` is null on ACCEPT
  - `LoanNegotiationResult LoanNegotiator.respond(LoanTerms offered, int playerOverall, int borrowerReputation, boolean aiIsOwner)`

**The model, in one sentence:** there is a single market-clearing owner-share for a given player and borrower, and both sides are measured against it — the AI as borrower accepts anything at or above it, the AI as owner accepts anything at or below it.

```
surplus  = playerOverall - (borrowerReputation - LEVEL_TOLERANCE)      // how far above the floor
required = BASE_SHARE(60) - surplus * SHARE_PER_OVERALL(10)
           + (mayFaceOwner ? 0 : FIXTURE_RELEASE_PREMIUM(15))          // withholding costs
clamped to 0..100
```

A barely-acceptable player needs heavy subsidy; a clear upgrade needs none. Worked values, which the tests pin: overall 70 at a reputation-70 club → surplus 3 → 30%; the same with the fixture withheld → 45%; overall 72 at a reputation-55 club → surplus 20 → 0%.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanNegotiatorTest.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanNegotiatorTest {

    private final LoanNegotiator negotiator = new LoanNegotiator();

    @Test
    void theRequiredShareFallsAsThePlayerRisesAboveTheBorrowersFloor() {
        // reputation 70, tolerance 3 -> floor 67. overall 70 -> surplus 3 -> 60 - 30 = 30.
        assertThat(negotiator.requiredOwnerShare(70, 70, true)).isEqualTo(30);
        // overall 67 -> surplus 0 -> the full base share.
        assertThat(negotiator.requiredOwnerShare(67, 70, true)).isEqualTo(60);
        // A clear upgrade for a smaller club costs the owner nothing.
        assertThat(negotiator.requiredOwnerShare(72, 55, true)).isZero();
    }

    @Test
    void withholdingTheFixtureReleaseCostsFifteenPoints() {
        assertThat(negotiator.requiredOwnerShare(70, 70, false)).isEqualTo(45);
    }

    @Test
    void theShareNeverLeavesZeroToOneHundred() {
        assertThat(negotiator.requiredOwnerShare(99, 40, true)).isZero();
        assertThat(negotiator.requiredOwnerShare(40, 99, false)).isEqualTo(100);
    }

    @Test
    void anAiBorrowerAcceptsAtOrAboveTheRequiredSubsidy() {
        LoanNegotiationResult exact = negotiator.respond(new LoanTerms(30, true), 70, 70, false);
        assertThat(exact.decision()).isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(exact.counter()).isNull();

        assertThat(negotiator.respond(new LoanTerms(80, true), 70, 70, false).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void anAiBorrowerCountersUpwardsAndAsksForTheFixtureRelease() {
        LoanNegotiationResult r = negotiator.respond(new LoanTerms(10, false), 70, 70, false);

        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        // It wants to be allowed to field him against the owner...
        assertThat(r.counter().mayFaceOwner()).isTrue();
        // ...and prices the share for that term, not for the one it was offered.
        assertThat(r.counter().ownerWageShare()).isEqualTo(30);
    }

    @Test
    void anAiOwnerAcceptsAtOrBelowWhatItIsWillingToPay() {
        assertThat(negotiator.respond(new LoanTerms(30, true), 70, 70, true).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
        assertThat(negotiator.respond(new LoanTerms(0, true), 70, 70, true).decision())
                .isEqualTo(NegotiationDecision.ACCEPT);
    }

    @Test
    void anAiOwnerCountersDownwardsAndWithholdsTheFixtureRelease() {
        LoanNegotiationResult r = negotiator.respond(new LoanTerms(90, true), 70, 70, true);

        assertThat(r.decision()).isEqualTo(NegotiationDecision.COUNTER);
        assertThat(r.counter().mayFaceOwner()).isFalse();
        // Priced with the fixture withheld: 30 + 15.
        assertThat(r.counter().ownerWageShare()).isEqualTo(45);
    }

    @Test
    void aCounterAlwaysMovesBothTermsTowardTheResponder() {
        LoanTerms ownerCounter = negotiator.respond(new LoanTerms(90, true), 70, 70, true).counter();
        LoanTerms borrowerCounter = negotiator.respond(new LoanTerms(0, false), 70, 70, false).counter();

        assertThat(ownerCounter.mayFaceOwner()).isFalse();     // the owner withholds
        assertThat(borrowerCounter.mayFaceOwner()).isTrue();   // the borrower asks for it
        assertThat(ownerCounter.ownerWageShare()).isLessThan(90);
        assertThat(borrowerCounter.ownerWageShare()).isGreaterThan(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanNegotiatorTest' > /tmp/t5.txt 2>&1; echo "EXIT=$?" >> /tmp/t5.txt
```

Expected: FAIL — compile error, `LoanNegotiator` does not exist.

- [ ] **Step 3: Create the two records**

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanTerms.java`:

```java
package br.com.carvoeiromanager.loan;

/**
 * The two negotiable terms of a loan: the percentage of the wage the OWNER pays, and whether the
 * borrowing club may field him against his owner.
 */
public record LoanTerms(int ownerWageShare, boolean mayFaceOwner) {
}
```

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanNegotiationResult.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;

/** The AI's answer to a set of loan terms; {@code counter} is null unless the decision is COUNTER. */
public record LoanNegotiationResult(NegotiationDecision decision, LoanTerms counter) {
}
```

- [ ] **Step 4: Create the negotiator**

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanNegotiator.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.springframework.stereotype.Component;

/**
 * Prices a plausible loan. There is one market-clearing owner-share for a given player and
 * borrower, and both sides are measured against it: the AI as borrower takes anything at or above
 * it, the AI as owner anything at or below it. Deterministic, no RNG.
 */
@Component
public class LoanNegotiator {

    /** What a player sitting exactly on the borrower's floor costs the owner in subsidy. */
    static final int BASE_SHARE = 60;
    /** How much of that subsidy each point of overall above the floor buys back. */
    static final int SHARE_PER_OVERALL = 10;
    /** Refusing to let him face his owner is a concession the other side charges for. */
    static final int FIXTURE_RELEASE_PREMIUM = 15;

    public LoanNegotiationResult respond(LoanTerms offered, int playerOverall,
                                         int borrowerReputation, boolean aiIsOwner) {
        int required = requiredOwnerShare(playerOverall, borrowerReputation, offered.mayFaceOwner());
        boolean acceptable = aiIsOwner
                ? offered.ownerWageShare() <= required   // the owner will not pay more than this
                : offered.ownerWageShare() >= required;  // the borrower needs at least this much
        if (acceptable) {
            return new LoanNegotiationResult(NegotiationDecision.ACCEPT, null);
        }
        // A counter moves both terms toward the responder: a borrower wants to field him against
        // his owner, an owner wants to prevent it — and the share is re-priced for that term.
        boolean counterMayFaceOwner = !aiIsOwner;
        int counterShare = requiredOwnerShare(playerOverall, borrowerReputation, counterMayFaceOwner);
        return new LoanNegotiationResult(NegotiationDecision.COUNTER,
                new LoanTerms(counterShare, counterMayFaceOwner));
    }

    /** The owner-paid share that clears the market for this player at this borrower, 0-100. */
    int requiredOwnerShare(int playerOverall, int borrowerReputation, boolean mayFaceOwner) {
        int floor = borrowerReputation - LoanPlausibility.LEVEL_TOLERANCE;
        int surplus = playerOverall - floor;
        int share = BASE_SHARE - surplus * SHARE_PER_OVERALL;
        if (!mayFaceOwner) {
            share += FIXTURE_RELEASE_PREMIUM;
        }
        return Math.max(0, Math.min(100, share));
    }
}
```

`LoanPlausibility.LEVEL_TOLERANCE` is package-private and both classes are in `br.com.carvoeiromanager.loan`, so the reference compiles.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanNegotiatorTest' > /tmp/t5.txt 2>&1; echo "EXIT=$?" >> /tmp/t5.txt
```

Expected: PASS, eight tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/ backend/src/test/java/br/com/carvoeiromanager/loan/LoanNegotiatorTest.java
git commit -m "feat(loan): price a loan's terms from one market-clearing wage share"
```

---

### Task 6: Loaning a player out

The manager offers one of his own players to an AI club. This task builds the service, its guards, and the shared decide-and-execute core that Task 7 reuses for the other direction.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/common/ErrorCodeTest.java` (it pins the enum's arity)
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceOutIT.java`

**Interfaces:**
- Consumes: `Player.loanTo(UUID,int,boolean)` and `Player.isOnLoan()` (Task 1); `SquadRules.MAX_SQUAD_SIZE == 45` (Task 2); `LoanPlausibility.check(...)` and `LoanRefusal` (Task 3); `LoanOffer` / `LoanOfferStatus` / `LoanOfferRepository` (Task 4); `LoanTerms`, `LoanNegotiationResult`, `LoanNegotiator.respond(...)` (Task 5); `SquadDeparture.seniorSquad`, `.requireDepartureAllowed`, `.requireNoLiveMatch`, `.cleanManagedLineup`, `.rejectOpenOffersFor`; `SellerContextFactory.build(Player, Club)`.
- Produces:
  - `record LoanOfferResponse(UUID offerId, UUID playerId, String playerName, LoanOfferStatus status, int ownerWageShare, boolean mayFaceOwner, Integer counterOwnerWageShare, Boolean counterMayFaceOwner, LoanRefusal refusal)`
  - `LoanOfferResponse LoanService.offerLoanOut(UUID saveGameId, UUID userId, UUID playerId, UUID borrowerClubId, int ownerWageShare, boolean mayFaceOwner)`
  - package-private `LoanOfferResponse LoanService.negotiate(SaveGame save, Player player, Club owner, Club borrower, LoanTerms offered, boolean aiIsOwner)` — the shared core Task 7 calls
  - `ErrorCode.LOAN_CONTRACT_TOO_SHORT` and `ErrorCode.PLAYER_ALREADY_ON_LOAN`, both `UNPROCESSABLE_CONTENT`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceOutIT.java`. Note the fixture work each case needs — the seeded squads do not hand you a spare player at a convenient club, so build the situation explicitly rather than hunting for one.

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.NegotiationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanServiceOutIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;
    @Autowired LoanOfferRepository loanOfferRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanout_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * A player the managed club can spare, found rather than forced: outside the top 11 by
     * overall (so SellerContextFactory does not call him a key player) and sharing a position
     * with a squadmate within 3 overall (so his position has cover). Those are exactly the two
     * conditions SellerContextFactory checks, at KEY_PLAYER_RANK_THRESHOLD = 11 and
     * COVER_OVERALL_MARGIN = 3.
     */
    private Player aSparePlayer(Career c) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        Player spare = squad.stream().skip(11)
                .filter(p -> p.getPosition() != Position.GK)
                .filter(p -> squad.stream().anyMatch(o -> !o.getId().equals(p.getId())
                        && o.getPosition() == p.getPosition()
                        && o.getOverall() >= p.getOverall() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the seed has no spare outfielder with positional cover — the fixture "
                                + "assumption behind every test in this class has changed"));
        spare.setContractYears(3);
        playerRepository.save(spare);
        return playerRepository.findById(spare.getId()).orElseThrow();
    }

    /** Makes the borrower's level low enough that the spare player clears it comfortably. */
    private Club aWillingBorrower(Career c, Player forPlayer) {
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(Math.max(1, forPlayer.getOverall() - 15));
        clubRepository.save(borrower);
        return borrower;
    }

    @Test
    void aGenerousOfferForASparePlayerIsAcceptedAndMovesHim() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        assertThat(r.refusal()).isNull();

        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getClubId()).isEqualTo(borrower.getId());
        assertThat(reloaded.getLoanWageShare()).isEqualTo(100);
        assertThat(reloaded.getLoanMayFaceOwner()).isTrue();
    }

    @Test
    void aKeyPlayerIsRefusedWithoutNegotiation() {
        Career c = createCareer();
        Player best = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        best.setContractYears(3);
        playerRepository.save(best);
        Club borrower = aWillingBorrower(c, best);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), best.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.KEY_PLAYER);
        assertThat(playerRepository.findById(best.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerBeneathTheBorrowersLevelIsRefusedWithoutNegotiation() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + 20);   // far above him
        clubRepository.save(borrower);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.BELOW_LEVEL);
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void meanTermsOnAPlausiblePlayerAreCounteredNotRefused() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        // Reputation just above his level, so the borrower needs real subsidy.
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse r = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.TERMS_REJECTED);
        assertThat(r.counterOwnerWageShare()).isNotNull().isPositive();
        assertThat(r.counterMayFaceOwner()).isTrue();   // the borrower asks for the release
        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerWithOneYearLeftCannotBeLoaned() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        spare.setContractYears(1);
        playerRepository.save(spare);
        Club borrower = aWillingBorrower(c, spare);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_CONTRACT_TOO_SHORT));

        assertThat(playerRepository.findById(spare.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aPlayerAlreadyOutOnLoanCannotBeLoanedAgain() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);
        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        assertThatThrownBy(() -> loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void acceptingALoanClearsTheOwnersLineupAndKillsOpenBids() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = aWillingBorrower(c, spare);

        loanService.offerLoanOut(c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);

        // The same obligations selling and terminating carry — a live bid must not outlive the
        // loan, because the sell guard would then pass trivially on a player who has left.
        assertThat(loanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(), spare.getId(),
                List.of(LoanOfferStatus.ACCEPTED))).hasSize(1);
    }
}
```

**On the fixture helper:** `Player` has **no** `setPosition` — only `setPositions(DetailedPosition, List<DetailedPosition>)` — so `aSparePlayer` searches the seeded squad for a player who already satisfies both conditions rather than manufacturing one. Its `orElseThrow` carries a message so a future seed change fails loudly instead of silently picking someone unsuitable. Do not weaken what the tests assert to make the search easier.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanServiceOutIT' > /tmp/t6.txt 2>&1; echo "EXIT=$?" >> /tmp/t6.txt
```

Expected: FAIL — compile error, `LoanService` does not exist.

- [ ] **Step 3: Add the two error codes**

In `ErrorCode`, keeping the alphabetical ordering the enum already follows:

```java
    LOAN_CONTRACT_TOO_SHORT(HttpStatus.UNPROCESSABLE_CONTENT),
    PLAYER_ALREADY_ON_LOAN(HttpStatus.UNPROCESSABLE_CONTENT),
```

`ErrorCodeTest` pins the enum's arity — raise the expected count by two and add status assertions for the new pair, matching how that test treats its neighbours.

- [ ] **Step 4: Create the response record**

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanOfferResponse.java`:

```java
package br.com.carvoeiromanager.loan;

import java.util.UUID;

/**
 * The outcome of proposing a loan. {@code refusal} is null on ACCEPTED; on REJECTED it says why
 * the player is off the table, and on COUNTERED it is TERMS_REJECTED — the only refusal a better
 * offer can answer.
 */
public record LoanOfferResponse(UUID offerId, UUID playerId, String playerName,
                                LoanOfferStatus status, int ownerWageShare, boolean mayFaceOwner,
                                Integer counterOwnerWageShare, Boolean counterMayFaceOwner,
                                LoanRefusal refusal) {
}
```

- [ ] **Step 5: Create the service**

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanService.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadDeparture;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.transfer.NegotiationDecision;
import br.com.carvoeiromanager.transfer.SellerContext;
import br.com.carvoeiromanager.transfer.SellerContextFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loans, in both directions. A loan is the third way out of the managed squad, so it runs the same
 * {@link SquadDeparture} contract selling and terminating do; what is specific to it is that the
 * contract must outlast the loan, and that the terms are negotiable.
 */
@Service
public class LoanService {

    /** A loan runs to the rollover, where contracts tick — so it must close inside one contract. */
    static final int MIN_CONTRACT_YEARS_TO_LOAN = 2;

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final LoanOfferRepository loanOfferRepository;
    private final LoanPlausibility loanPlausibility;
    private final LoanNegotiator loanNegotiator;
    private final SellerContextFactory sellerContextFactory;
    private final SquadDeparture squadDeparture;

    public LoanService(SaveGameService saveGameService, PlayerRepository playerRepository,
                       ClubRepository clubRepository, LoanOfferRepository loanOfferRepository,
                       LoanPlausibility loanPlausibility, LoanNegotiator loanNegotiator,
                       SellerContextFactory sellerContextFactory, SquadDeparture squadDeparture) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.loanOfferRepository = loanOfferRepository;
        this.loanPlausibility = loanPlausibility;
        this.loanNegotiator = loanNegotiator;
        this.sellerContextFactory = sellerContextFactory;
        this.squadDeparture = squadDeparture;
    }

    /** The manager lends one of his own players to an AI club. */
    @Transactional
    public LoanOfferResponse offerLoanOut(UUID saveGameId, UUID userId, UUID playerId,
                                          UUID borrowerClubId, int ownerWageShare,
                                          boolean mayFaceOwner) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .filter(p -> !p.isYouthProspect())
                .filter(p -> !p.isOnLoan())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player not found in your professional squad"));

        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(managedClubId), player);

        Club owner = clubRepository.findById(managedClubId).orElseThrow();
        Club borrower = clubRepository.findById(borrowerClubId)
                .filter(club -> club.getSaveGameId().equals(saveGameId))
                .filter(club -> !club.getId().equals(managedClubId))
                .orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found"));
        requireRoomFor(borrower.getId());

        return negotiate(save, player, owner, borrower,
                new LoanTerms(ownerWageShare, mayFaceOwner), false);
    }

    /**
     * The shared core: decide, then either execute or record what the AI said. {@code aiIsOwner}
     * says which side the AI sits on, which is the only thing that differs between directions.
     */
    LoanOfferResponse negotiate(SaveGame save, Player player, Club owner, Club borrower,
                                LoanTerms offered, boolean aiIsOwner) {
        LoanOffer offer = LoanOffer.create(save.getId(), player.getId(), owner.getId(),
                borrower.getId(), offered.ownerWageShare(), offered.mayFaceOwner(),
                save.getCurrentRound());

        SellerContext ownerContext = sellerContextFactory.build(player, owner);
        Optional<LoanRefusal> refusal = loanPlausibility.check(
                ownerContext, player.getOverall(), borrower.getReputation());
        if (refusal.isPresent()) {
            offer.markRejected();
            loanOfferRepository.save(offer);
            return toResponse(offer, player, refusal.get());
        }

        LoanNegotiationResult result = loanNegotiator.respond(
                offered, player.getOverall(), borrower.getReputation(), aiIsOwner);
        if (result.decision() == NegotiationDecision.COUNTER) {
            offer.markCountered(result.counter().ownerWageShare(), result.counter().mayFaceOwner());
            loanOfferRepository.save(offer);
            return toResponse(offer, player, LoanRefusal.TERMS_REJECTED);
        }

        execute(save, player, borrower.getId(), offered);
        offer.markAccepted();
        loanOfferRepository.save(offer);
        return toResponse(offer, player, null);
    }

    /**
     * Moves the player and settles everything leaving the squad implies. The offer rejection is
     * not optional: a live bid that outlived the loan would meet a sell guard that now passes
     * trivially, because the player is no longer in the squad it counts.
     */
    void execute(SaveGame save, Player player, UUID borrowerClubId, LoanTerms terms) {
        UUID formerClubId = player.getClubId();
        player.loanTo(borrowerClubId, terms.ownerWageShare(), terms.mayFaceOwner());
        playerRepository.save(player);
        squadDeparture.cleanManagedLineup(save.getId(), formerClubId, player.getId());
        squadDeparture.rejectOpenOffersFor(save.getId(), player.getId());
    }

    void requireLoanableContract(Player player) {
        if (player.getContractYears() < MIN_CONTRACT_YEARS_TO_LOAN) {
            throw new ApiException(ErrorCode.LOAN_CONTRACT_TOO_SHORT,
                    "A loan must end before the contract does; this one has "
                            + player.getContractYears() + " year(s) left");
        }
    }

    void requireRoomFor(UUID clubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(clubId).size()
                >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "That squad is full");
        }
    }

    LoanOfferResponse toResponse(LoanOffer offer, Player player, LoanRefusal refusal) {
        return new LoanOfferResponse(offer.getId(), player.getId(), player.getName(),
                offer.getStatus(), offer.getOwnerWageShare(), offer.isMayFaceOwner(),
                offer.getCounterOwnerWageShare(), offer.getCounterMayFaceOwner(), refusal);
    }
}
```

`cleanManagedLineup` is called with the player's **former** club, captured before `loanTo` moves him — it is a no-op for an AI club, which has no stored lineup, and does the real work when the managed club is the one losing him.

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanServiceOutIT' --tests '*ErrorCodeTest' > /tmp/t6.txt 2>&1; echo "EXIT=$?" >> /tmp/t6.txt
```

Expected: PASS, seven loan tests plus the error-code suite.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/ backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceOutIT.java backend/src/test/java/br/com/carvoeiromanager/common/ErrorCodeTest.java
git commit -m "feat(loan): lend a player to an AI club over a negotiable deal"
```

---

### Task 7: Borrowing a player

The mirror direction: the manager asks an AI club for one of its players. The AI now sits on the owner's side, which is the only thing that changes.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceInIT.java`

**Interfaces:**
- Consumes: everything Task 6 produced, in particular the package-private `negotiate(save, player, owner, borrower, offered, aiIsOwner)`.
- Produces: `LoanOfferResponse LoanService.offerLoanIn(UUID saveGameId, UUID userId, UUID playerId, int ownerWageShare, boolean mayFaceOwner)` — the owner is whichever club currently holds the player, so it is not a parameter.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceInIT.java`, mirroring `LoanServiceOutIT`'s imports and `Career` / `aRivalClubId` helpers:

```java
    /** A spare player at an AI club: outside its top 11, with cover in his position. */
    private Player aSparePlayerAt(UUID clubId) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        Player spare = squad.stream().skip(11)
                .filter(p -> p.getPosition() != Position.GK)
                .filter(p -> squad.stream().anyMatch(o -> !o.getId().equals(p.getId())
                        && o.getPosition() == p.getPosition()
                        && o.getOverall() >= p.getOverall() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no spare outfielder with cover at " + clubId));
        spare.setContractYears(3);
        playerRepository.save(spare);
        return playerRepository.findById(spare.getId()).orElseThrow();
    }

    /** Drops the managed club's reputation so the borrowed player clears its level test. */
    private void makeManagedClubModest(Career c, Player target) {
        Club managed = clubRepository.findById(c.clubId()).orElseThrow();
        managed.setReputation(Math.max(1, target.getOverall() - 10));
        clubRepository.save(managed);
    }

    @Test
    void borrowingASparePlayerMovesHimToTheManagedClubUnderHisOwner() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        makeManagedClubModest(c, target);

        // Asking the owner to pay nothing is the offer an owner most readily accepts.
        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getOwnerClubId()).isEqualTo(ownerClubId);
        assertThat(reloaded.getLoanWageShare()).isZero();
    }

    @Test
    void anOwnerAskedToPayTooMuchCountersDownAndWithholdsTheFixture() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        makeManagedClubModest(c, target);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.TERMS_REJECTED);
        assertThat(r.counterOwnerWageShare()).isLessThan(100);
        assertThat(r.counterMayFaceOwner()).isFalse();
        assertThat(playerRepository.findById(target.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aClubWillNotLendItsKeyPlayerToYou() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player best = playerRepository.findByClubIdAndYouthProspectFalse(ownerClubId).stream()
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        best.setContractYears(3);
        playerRepository.save(best);
        makeManagedClubModest(c, best);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), best.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void aPlayerBeneathYourLevelIsNotWorthBorrowing() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        Club managed = clubRepository.findById(c.clubId()).orElseThrow();
        managed.setReputation(target.getOverall() + 20);
        clubRepository.save(managed);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.BELOW_LEVEL);
    }

    @Test
    void aPlayerWithOneYearLeftCannotBeBorrowedEither() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        target.setContractYears(1);
        playerRepository.save(target);
        makeManagedClubModest(c, target);

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_CONTRACT_TOO_SHORT));
    }

    @Test
    void yourOwnPlayerCannotBeBorrowedFromYourself() {
        Career c = createCareer();
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), mine.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanServiceInIT' > /tmp/t7.txt 2>&1; echo "EXIT=$?" >> /tmp/t7.txt
```

Expected: FAIL — compile error, `cannot find symbol: method offerLoanIn`.

- [ ] **Step 3: Add the borrowing entry point**

In `LoanService`, beside `offerLoanOut`:

```java
    /** The manager borrows a player from the AI club that currently holds him. */
    @Transactional
    public LoanOfferResponse offerLoanIn(UUID saveGameId, UUID userId, UUID playerId,
                                         int ownerWageShare, boolean mayFaceOwner) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> p.getClubId() != null)
                .filter(p -> !managedClubId.equals(p.getClubId()))   // not already yours
                .filter(p -> !p.isYouthProspect())
                .filter(p -> !p.isOnLoan())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "No such player at another club"));

        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        requireRoomFor(managedClubId);

        Club owner = clubRepository.findById(player.getClubId()).orElseThrow();
        Club borrower = clubRepository.findById(managedClubId).orElseThrow();

        return negotiate(save, player, owner, borrower,
                new LoanTerms(ownerWageShare, mayFaceOwner), true);
    }
```

There is deliberately no `requireDepartureAllowed` here: that guard protects the *managed* squad from being emptied, and in this direction the managed club is receiving, not losing. The AI owner's squad floor is not the manager's to police, and the plausibility rule already refuses anyone that club actually needs.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanServiceInIT' --tests '*LoanServiceOutIT' > /tmp/t7.txt 2>&1; echo "EXIT=$?" >> /tmp/t7.txt
```

Expected: PASS, both classes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/LoanService.java backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceInIT.java
git commit -m "feat(loan): borrow a player from an AI club"
```

---

### Task 8: Accepting a counter, and the endpoints

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanController.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanOutRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanInRequest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceCounterIT.java`

**Interfaces:**
- Consumes: `LoanService.execute(...)`, `offerLoanOut`, `offerLoanIn`, `LoanOffer.markAccepted()`.
- Produces:
  - `record LoanOutRequest(UUID playerId, UUID borrowerClubId, int ownerWageShare, boolean mayFaceOwner)`
  - `record LoanInRequest(UUID playerId, int ownerWageShare, boolean mayFaceOwner)`
  - `LoanOfferResponse LoanService.acceptCounter(UUID saveGameId, UUID userId, UUID offerId)`
  - `POST /api/v1/save-games/{saveGameId}/loans/out`, `.../loans/in`, `.../loans/{offerId}/accept-counter`

**One round of counter, as with transfers:** the manager accepts the AI's terms or walks away. There is no counter-the-counter; a fresh offer is the same thing with a cleaner state machine.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceCounterIT.java`, copying `LoanServiceOutIT`'s `Career`, `createCareer`, `aRivalClubId` and `aSparePlayer` helpers (separate classes, so copy rather than share):

```java
    @Test
    void acceptingTheCounterExecutesTheLoanOnTheAisTerms() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(spare.getOverall() + LoanPlausibility.LEVEL_TOLERANCE);
        clubRepository.save(borrower);

        LoanOfferResponse countered = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 0, false);
        assertThat(countered.status()).isEqualTo(LoanOfferStatus.COUNTERED);

        LoanOfferResponse accepted = loanService.acceptCounter(
                c.id(), c.userId(), countered.offerId());

        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        Player reloaded = playerRepository.findById(spare.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        // The COUNTER's terms are what execute, not the ones originally offered.
        assertThat(reloaded.getLoanWageShare()).isEqualTo(countered.counterOwnerWageShare());
        assertThat(reloaded.getLoanMayFaceOwner()).isEqualTo(countered.counterMayFaceOwner());
    }

    @Test
    void thereIsNothingToAcceptOnAnOfferThatWasNotCountered() {
        Career c = createCareer();
        Player spare = aSparePlayer(c);
        Club borrower = clubRepository.findById(aRivalClubId(c)).orElseThrow();
        borrower.setReputation(Math.max(1, spare.getOverall() - 15));
        clubRepository.save(borrower);

        LoanOfferResponse accepted = loanService.offerLoanOut(
                c.id(), c.userId(), spare.getId(), borrower.getId(), 100, true);
        assertThat(accepted.status()).isEqualTo(LoanOfferStatus.ACCEPTED);

        assertThatThrownBy(() -> loanService.acceptCounter(c.id(), c.userId(), accepted.offerId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSFER));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanServiceCounterIT' > /tmp/t8.txt 2>&1; echo "EXIT=$?" >> /tmp/t8.txt
```

Expected: FAIL — `cannot find symbol: method acceptCounter`.

- [ ] **Step 3: Add `acceptCounter` to the service**

```java
    /** Takes the AI's counter as offered. There is no counter-the-counter: accept or walk away. */
    @Transactional
    public LoanOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID offerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);

        LoanOffer offer = loanOfferRepository.findById(offerId)
                .filter(o -> o.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_OFFER_NOT_FOUND,
                        "Loan offer not found"));
        if (offer.getStatus() != LoanOfferStatus.COUNTERED) {
            throw new ApiException(ErrorCode.INVALID_TRANSFER, "No counter to accept on this offer");
        }

        Player player = playerRepository.findById(offer.getPlayerId())
                .filter(p -> !p.isOnLoan())
                // He must still be at the club the offer named: he may have been sold, released
                // or lent elsewhere since the AI answered, and the offer's owner would then be
                // stale — executing it would move a player out of a club that no longer holds him.
                .filter(p -> offer.getOwnerClubId().equals(p.getClubId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "That player is no longer available"));
        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        requireRoomFor(offer.getBorrowerClubId());

        execute(save, player, offer.getBorrowerClubId(),
                new LoanTerms(offer.getCounterOwnerWageShare(), offer.getCounterMayFaceOwner()));
        offer.markAccepted();
        loanOfferRepository.save(offer);
        return toResponse(offer, player, null);
    }
```

The guards run again rather than being trusted from the original offer: a live match may have started, a squad may have filled, or the player may have moved since the AI answered. That last one is why the lookup re-checks his current club against the offer's owner — a counter accepted after the player was sold would otherwise move him out of a club that no longer holds him.

Add a test for it: make an offer, counter it, then move the player to a third club with `loanTo`, and assert `acceptCounter` throws `PLAYER_NOT_FOUND` and moves nobody.

- [ ] **Step 4: Create the request records and the controller**

`LoanOutRequest.java`:

```java
package br.com.carvoeiromanager.loan;

import java.util.UUID;

public record LoanOutRequest(UUID playerId, UUID borrowerClubId, int ownerWageShare,
                             boolean mayFaceOwner) {}
```

`LoanInRequest.java`:

```java
package br.com.carvoeiromanager.loan;

import java.util.UUID;

public record LoanInRequest(UUID playerId, int ownerWageShare, boolean mayFaceOwner) {}
```

`LoanController.java`, following `TransferController`'s shape:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping("/out")
    public ResponseEntity<LoanOfferResponse> loanOut(
            @PathVariable UUID saveGameId, @RequestBody LoanOutRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.offerLoanOut(saveGameId, userId, request.playerId(),
                request.borrowerClubId(), request.ownerWageShare(), request.mayFaceOwner()));
    }

    @PostMapping("/in")
    public ResponseEntity<LoanOfferResponse> loanIn(
            @PathVariable UUID saveGameId, @RequestBody LoanInRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.offerLoanIn(saveGameId, userId, request.playerId(),
                request.ownerWageShare(), request.mayFaceOwner()));
    }

    @PostMapping("/{offerId}/accept-counter")
    public ResponseEntity<LoanOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.acceptCounter(saveGameId, userId, offerId));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*Loan*' > /tmp/t8.txt 2>&1; echo "EXIT=$?" >> /tmp/t8.txt
```

Expected: PASS, every loan class.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/ backend/src/test/java/br/com/carvoeiromanager/loan/LoanServiceCounterIT.java
git commit -m "feat(loan): accept the AI's counter, and expose the loan endpoints"
```

---

### Task 9: Splitting the wage bill

The one place split ownership reaches a hot path — it runs for every club, every processed month.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java:355-362` (`monthlyWageBills`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/finance/LoanWageSplitIT.java`

**Interfaces:**
- Consumes: `Player.isOnLoan()`, `getOwnerClubId()`, `getLoanWageShare()` (Task 1); `Player.loanTo(...)`.
- Produces: no new signature — `monthlyWageBills` keeps returning `Map<UUID, BigDecimal>`.

**The rounding rule is load-bearing:** the borrower's part is `wage.subtract(ownerPart)`, never its own independently-rounded percentage. Two rounded halves can fail to sum to the wage, quietly creating or destroying money every month for every loaned player.

- [ ] **Step 1: Write the failing test**

`monthlyWageBills` is private, and it must stay that way — do **not** add a test-only public accessor. Assert through the public monthly processing instead: run a month, then read the `WAGES` ledger rows.

Create `backend/src/test/java/br/com/carvoeiromanager/finance/LoanWageSplitIT.java`. Read `FinanceService` for the real name and signature of the month-processing method and the ledger query before writing this; the shape below is the assertion you need, not necessarily the call you will make.

```java
    @Test
    void aLoanedPlayersWageIsChargedToBothClubsAndSumsToHisFullWage() {
        Career c = createCareer();
        UUID borrowerClubId = aRivalClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        BigDecimal fullWage = financeCalculator.monthlyWage(p.getOverall(), p.getAge());

        BigDecimal ownerPart = fullWage.multiply(new BigDecimal("40"))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal borrowerPart = fullWage.subtract(ownerPart);

        // The invariant that matters: no money is created or destroyed by the split.
        assertThat(ownerPart.add(borrowerPart)).isEqualByComparingTo(fullWage);

        // Charge a month with the player at home, then again with him out on loan, and compare
        // the WAGES rows: the owner's bill must fall by exactly borrowerPart.
        BigDecimal ownerBillAtHome = chargeAMonthAndReadWages(c, c.clubId());

        p.loanTo(borrowerClubId, 40, true);
        playerRepository.save(p);

        BigDecimal ownerBillOnLoan = chargeAMonthAndReadWages(c, c.clubId());
        assertThat(ownerBillAtHome.subtract(ownerBillOnLoan)).isEqualByComparingTo(borrowerPart);
    }
```

Write `chargeAMonthAndReadWages(Career, UUID clubId)` against `FinanceService`'s actual monthly entry point, advancing the save's date by a month between calls so the second charge is not skipped by `lastFinanceMonth`. If the two-run comparison proves impossible in one test, split it into two careers with identical seeds and compare across them — and say in your report which you did and why.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanWageSplitIT' > /tmp/t9.txt 2>&1; echo "EXIT=$?" >> /tmp/t9.txt
```

Expected: FAIL — the whole wage still falls on the club he plays for, so the owner's bill drops by the full wage rather than by the borrower's share.

- [ ] **Step 3: Split the bill**

Replace `monthlyWageBills`:

```java
    /**
     * Monthly wage bill per club. A loaned player is paid by BOTH clubs: {@code loanWageShare}
     * percent by his owner, the rest by the club he plays for. The borrower's part is the
     * remainder rather than its own percentage, so the halves always sum to the wage exactly —
     * two independently rounded percentages would create or destroy money every month.
     */
    private Map<UUID, BigDecimal> monthlyWageBills(UUID saveGameId) {
        Map<UUID, BigDecimal> bills = new HashMap<>();
        for (Player player : playerRepository.findBySaveGameId(saveGameId)) {
            if (player.getClubId() == null) {
                continue;   // free agents are nobody's payroll
            }
            BigDecimal wage = financeCalculator.monthlyWage(player.getOverall(), player.getAge());
            if (player.isOnLoan()) {
                BigDecimal ownerPart = wage
                        .multiply(BigDecimal.valueOf(player.getLoanWageShare()))
                        .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
                bills.merge(player.getOwnerClubId(), ownerPart, BigDecimal::add);
                bills.merge(player.getClubId(), wage.subtract(ownerPart), BigDecimal::add);
            } else {
                bills.merge(player.getClubId(), wage, BigDecimal::add);
            }
        }
        return bills;
    }
```

Add `private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");` to the class constants, plus `java.util.HashMap` and `java.math.RoundingMode` imports if absent.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanWageSplitIT' --tests '*Finance*' > /tmp/t9.txt 2>&1; echo "EXIT=$?" >> /tmp/t9.txt
```

Expected: PASS. The whole finance suite is included deliberately: this method feeds every club's monthly balance, so a solvency test drifting here is a real finding, not noise.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java backend/src/test/java/br/com/carvoeiromanager/finance/LoanWageSplitIT.java
git commit -m "feat(finance): split a loaned player's wage between his two clubs"
```

---

### Task 10: Bringing loans home at the rollover

Two orderings decide whether this is correct, and both were learned the expensive way on the termination slice.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/LoanRolloverIT.java`

**Interfaces:**
- Consumes: `Player.isOnLoan()`, `Player.returnFromLoan()` (Task 1); `SquadRules.MAX_SQUAD_SIZE == 45` (Task 2); `LoanOfferRepository.deleteBySaveGameId` (Task 4); `ListedReason.SURPLUS`; `SquadChangeResponse(String playerName, String position, int age)`.
- Produces: `RolloverResponse` gains `List<SquadChangeResponse> loanReturns` and `List<SquadChangeResponse> surplusListings`, both after `preContractDepartures` and before `watchlistEvents`.

**Where the return goes, and why:**

- **After the archive** (step 1, `SeasonRolloverService:199`). A loaned player's goals were scored for the club he played at, and that is the club the archived top-scorer row must name. Sending him home first would rewrite history — the exact failure that broke the archive when contract termination shipped.
- **Before the contract tick** (`SeasonRolloverService:298`). His contract is with his owner, so he must be home before it ticks.

That lands it at the very start of step 3, as its own pass just before the player loop.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/season/LoanRolloverIT.java`. Model it on `RolloverContractsIT`, which already has `playToSeasonEnd()` and the `Career` record — read it first and reuse that shape.

```java
    @Test
    void aLoanedPlayerComesHomeAtTheRolloverWithNoLoanStateLeft() {
        Career c = playToSeasonEnd();
        List<Player> squad = playerRepository.findByClubId(c.clubId());
        squad.forEach(p -> p.setContractYears(5));       // keep everyone off the lapse path
        Player lent = probePlayer(c.id(), squad);
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        playerRepository.saveAll(squad);
        lent.loanTo(borrower, 40, true);
        playerRepository.save(lent);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player home = playerRepository.findById(lent.getId()).orElseThrow();
        assertThat(home.isOnLoan()).isFalse();
        assertThat(home.getClubId()).isEqualTo(c.clubId());
        assertThat(home.getLoanWageShare()).isNull();
        assertThat(home.getLoanMayFaceOwner()).isNull();
        assertThat(r.loanReturns()).extracting(SquadChangeResponse::playerName)
                .contains(home.getName());
    }

    @Test
    void aLoanedTopScorerIsArchivedUnderTheClubHePlayedFor() {
        // The ordering guard: the archive runs BEFORE the return, so his goals belong to the
        // club he actually played them for, not to the owner he goes home to.
        Career c = playToSeasonEnd();
        // Find the competition's leading scorer who is currently at a club, lend him elsewhere.
        // (Use TopScorersService the way SeasonRolloverServiceIT does — read that class first.)
        // ... arrange, then:
        rolloverService.rollover(c.id(), c.userId());
        // assert the archived season_top_scorer row names the BORROWING club, not the owner.
    }

    @Test
    void returnsThatOverflowTheSquadListTheWeakestPlayersAsSurplus() {
        Career c = playToSeasonEnd();
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        squad.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(squad);
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();

        // Pad the managed squad to exactly the cap, then lend three away: the three returns
        // push it to 48 and three must come back listed.
        // (Build the padding with the same filler helper AiTransferEngineIT uses — read it.)

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        List<Player> after = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(after).hasSizeGreaterThan(SquadRules.MAX_SQUAD_SIZE);
        assertThat(after.stream().filter(Player::isTransferListed)).hasSize(
                after.size() - SquadRules.MAX_SQUAD_SIZE);
        assertThat(after.stream().filter(Player::isTransferListed))
                .allMatch(p -> p.getListedReason() == ListedReason.SURPLUS);
        assertThat(r.surplusListings()).isNotEmpty();
    }
```

The second and third tests are sketched with their arrangement left to you deliberately: both need fixtures whose exact shape depends on classes you must read (`SeasonRolloverServiceIT` for the top-scorer arrangement, `AiTransferEngineIT`'s `filler` for squad padding). **Write them fully — do not leave them as comments.** If either proves impossible to arrange, report BLOCKED rather than deleting the test; the ordering guarantee and the overflow rule are both spec requirements.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanRolloverIT' > /tmp/t10.txt 2>&1; echo "EXIT=$?" >> /tmp/t10.txt
```

Expected: FAIL — `RolloverResponse` has no `loanReturns`.

- [ ] **Step 3: Widen the response**

In `RolloverResponse`, between `preContractDepartures` and `watchlistEvents`:

```java
        List<SquadChangeResponse> loanReturns,
        List<SquadChangeResponse> surplusListings,
```

Every construction site of this record must pass the two new lists — find them and pass the real values from Step 4, not empty lists.

- [ ] **Step 4: Return the loans and settle the overflow**

In `SeasonRolloverService`, immediately before the player loop (`// 3. Age every player...`, around line 231):

```java
        // Loans come home BEFORE contracts tick — the contract is with the owner — and AFTER the
        // archive above, so a loaned player's goals stay credited to the club he played them for.
        List<SquadChangeResponse> loanReturns = new ArrayList<>();
        List<Player> returning = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(Player::isOnLoan)
                .toList();
        for (Player player : returning) {
            boolean toManaged = managedClubId.equals(player.getOwnerClubId());
            player.returnFromLoan();
            if (toManaged) {
                loanReturns.add(new SquadChangeResponse(
                        player.getName(), player.getPosition().name(), player.getAge()));
            }
        }
        playerRepository.saveAll(returning);
        playerRepository.flush();

        // A club can take several players back at once, and the squad cap is otherwise only
        // checked one signing at a time — so settle any overflow by listing the weakest.
        List<SquadChangeResponse> surplusListings = listSurplusAfterReturns(saveGameId, managedClubId);
```

And the helper, beside the other private methods:

```java
    /**
     * Any club over the squad ceiling after its loans return lists its weakest professionals as
     * SURPLUS until it is back at the cap. Returns the managed club's listings for the summary.
     */
    private List<SquadChangeResponse> listSurplusAfterReturns(UUID saveGameId, UUID managedClubId) {
        List<SquadChangeResponse> managedListings = new ArrayList<>();
        Map<UUID, List<Player>> byClub = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect())
                .collect(Collectors.groupingBy(Player::getClubId));

        List<Player> toSave = new ArrayList<>();
        for (Map.Entry<UUID, List<Player>> entry : byClub.entrySet()) {
            int overflow = entry.getValue().size() - SquadRules.MAX_SQUAD_SIZE;
            if (overflow <= 0) {
                continue;
            }
            List<Player> weakestFirst = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                    .toList();
            for (Player player : weakestFirst.subList(0, overflow)) {
                player.listForTransfer(ListedReason.SURPLUS);
                toSave.add(player);
                if (managedClubId.equals(entry.getKey())) {
                    managedListings.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
            }
        }
        playerRepository.saveAll(toSave);
        return managedListings;
    }
```

`listForTransfer(ListedReason)` is the existing mutator (the counterpart of `unlist()`), used the same way at `TransferService:135`. Import `br.com.carvoeiromanager.player.ListedReason`.

Finally, purge the offers with the other season-scoped tables, beside `transferOfferRepository.deleteBySaveGameId(saveGameId)` at line 217:

```java
        loanOfferRepository.deleteBySaveGameId(saveGameId);
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanRolloverIT' --tests '*Rollover*' --tests '*SeasonRollover*' > /tmp/t10.txt 2>&1; echo "EXIT=$?" >> /tmp/t10.txt
```

Expected: PASS across all of them — the neighbouring rollover suites are included because this task edits the shared rollover path.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/ backend/src/test/java/br/com/carvoeiromanager/season/LoanRolloverIT.java
git commit -m "feat(season): bring loans home at the rollover and settle the overflow"
```

---

### Task 11: He cannot play against the club that owns him

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/loan/LoanEligibility.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (`bestXi`, `startingXi`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (`resolveXi`, and its callers around lines 248-249 and 812)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java:148-149`
- Test: `backend/src/test/java/br/com/carvoeiromanager/loan/LoanEligibilityIT.java`, `backend/src/test/java/br/com/carvoeiromanager/match/LoanFixtureEligibilityIT.java`

**Interfaces:**
- Consumes: `Player.isOnLoan()`, `getOwnerClubId()`, `getLoanMayFaceOwner()` (Task 1).
- Produces:
  - `boolean LoanEligibility.mayFace(Player player, UUID opponentClubId)`
  - `List<Player> LineupService.bestXi(UUID clubId, LocalDate onDate, UUID opponentClubId)` and `List<Player> LineupService.startingXi(UUID saveGameId, UUID clubId, UUID opponentClubId)` — the existing signatures stay, delegating with a null opponent.

**Why it cannot live in `Player.isEligible()`:** that method is `suspensionRemaining == 0 && injuryMatches == 0` — a predicate about the player alone. This rule depends on *who he is playing*, which the player does not know.

**Why the short-XI problem is already solved:** `bestXi` tops its pool up from ineligible players when fewer than 10 eligible outfielders exist, and `startingXi` falls back to `bestXi` when the saved XI comes up short. A player barred from one fixture behaves exactly like a suspended one, through machinery that already exists — add the filter beside `Player::isEligible` and both fallbacks apply for free.

**It must bind the AI too.** If only the managed side enforces it, the AI will field a borrowed player against his owner in a match the manager is watching minute by minute. Both `FixtureSimulator` and `LiveMatchService` assemble XIs; both must pass the opponent.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/loan/LoanEligibilityIT.java`. It is an
integration test rather than a plain unit test for one reason: it needs real `Player` instances in
specific loan states, and loading them from a seeded career avoids guessing at `Player.create`'s
argument list. `LoanEligibility` itself has no dependencies, so construct it directly.

```java
package br.com.carvoeiromanager.loan;

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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanEligibilityIT {

    private final LoanEligibility eligibility = new LoanEligibility();

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanelig_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player aManagedPlayer(Career c) {
        return playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
    }

    private List<UUID> twoOtherClubs(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .distinct().sorted().limit(2).toList();
    }

    @Test
    void aPlayerWhoIsNotOnLoanMayFaceAnyone() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);

        assertThat(eligibility.mayFace(p, others.get(0))).isTrue();
        assertThat(eligibility.mayFace(p, c.clubId())).isTrue();
    }

    @Test
    void aBorrowedPlayerMayFaceEveryClubExceptHisOwner() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);
        UUID owner = c.clubId();
        p.loanTo(others.get(0), 50, false);   // release withheld

        assertThat(eligibility.mayFace(p, others.get(1))).isTrue();
        assertThat(eligibility.mayFace(p, owner)).isFalse();
    }

    @Test
    void theNegotiatedReleaseLetsHimFaceHisOwner() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);
        UUID owner = c.clubId();
        p.loanTo(others.get(0), 50, true);    // release granted

        assertThat(eligibility.mayFace(p, owner)).isTrue();
    }

    @Test
    void aNullOpponentImposesNoRestriction() {
        // The two-argument bestXi/startingXi overloads pass null; that must never bar anyone.
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        p.loanTo(twoOtherClubs(c).get(0), 50, false);

        assertThat(eligibility.mayFace(p, null)).isTrue();
    }
}
```


- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanEligibilityIT' > /tmp/t11.txt 2>&1; echo "EXIT=$?" >> /tmp/t11.txt
```

Expected: FAIL — `LoanEligibility` does not exist.

- [ ] **Step 3: Create the rule**

`backend/src/main/java/br/com/carvoeiromanager/loan/LoanEligibility.java`:

```java
package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.player.Player;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Whether a player may be fielded against a particular opponent. Unlike {@code Player.isEligible},
 * which asks only about the player, this depends on who he is playing: a borrowed player is barred
 * from facing the club that owns him unless the loan negotiated the release.
 */
@Component
public class LoanEligibility {

    /** @param opponentClubId the club he would face, or null when no fixture is in view */
    public boolean mayFace(Player player, UUID opponentClubId) {
        if (opponentClubId == null || !player.isOnLoan()) {
            return true;
        }
        if (Boolean.TRUE.equals(player.getLoanMayFaceOwner())) {
            return true;
        }
        return !opponentClubId.equals(player.getOwnerClubId());
    }
}
```

- [ ] **Step 4: Thread the opponent through the lineup builders**

In `LineupService`, inject `LoanEligibility loanEligibility`, then add the opponent-aware overloads and make the existing ones delegate:

```java
    public List<Player> bestXi(UUID clubId, LocalDate onDate) {
        return bestXi(clubId, onDate, null);
    }

    public List<Player> bestXi(UUID clubId, LocalDate onDate, UUID opponentClubId) { ... }
```

Inside the three-argument `bestXi`, change the eligibility filter — and only that:

```java
        List<Player> eligible = allPlayers.stream()
                .filter(Player::isEligible)
                .filter(p -> loanEligibility.mayFace(p, opponentClubId))
                .toList();
```

Leave the ineligible top-up untouched: a barred player is a last-resort body exactly as a suspended one is, and that fallback is what keeps the engine's XI at eleven.

Do the same in `startingXi`, adding the overload and the extra `.filter(...)` beside `.filter(Player::isEligible)`.

- [ ] **Step 5: Pass the opponent at both match-assembly sites**

`FixtureSimulator.resolveXi` gains the opponent:

```java
    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID opponentClubId,
                                   UUID managedClubId, LocalDate matchDate) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId, opponentClubId);
        }
        return lineupService.bestXi(clubId, matchDate, opponentClubId);
    }
```

Its callers around lines 248-249 pass each side the other club:

```java
        List<Player> homeXi = resolveXi(saveGameId, homeClubId, awayClubId, managedClubId, matchDate);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, homeClubId, managedClubId, matchDate);
```

Line 812's `lineupService.bestXi(clubId, matchDate)` is in a different helper — read its surroundings, and pass the opponent if one is in scope. If no opponent is available there, leave it on the two-argument overload and say so in your report.

In `LiveMatchService` around lines 148-149, each side likewise receives the other club id.

- [ ] **Step 6: Write the integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/LoanFixtureEligibilityIT.java`: lend a managed player to a club the managed team is fixtured against, simulate that fixture, and assert he does not appear in either XI. Then repeat with `mayFaceOwner = true` and assert he does. Read `FixtureSimulator`'s IT neighbours for how to drive a single fixture.

**Assert the AI side specifically** — that is the case a manager would see and disbelieve.

- [ ] **Step 7: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LoanEligibility*' --tests '*LoanFixtureEligibilityIT' --tests '*Lineup*' --tests '*LiveMatch*' > /tmp/t11.txt 2>&1; echo "EXIT=$?" >> /tmp/t11.txt
```

Expected: PASS. The lineup and live-match suites are included because this task edits their shared XI-selection path.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/loan/LoanEligibility.java backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/test/java/br/com/carvoeiromanager/loan/LoanEligibilityIT.java backend/src/test/java/br/com/carvoeiromanager/match/LoanFixtureEligibilityIT.java
git commit -m "feat(match): bar a borrowed player from facing the club that owns him"
```

---

### Task 12: Ship the loan state, and the API client

The seam between the halves: the squad payload must say a player is on loan and from whom, and the frontend needs the three endpoints.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java:793-827` (the `new PlayerResponse(...)` call)
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SquadLoanInfoIT.java`, `frontend/src/api/career.loan.test.ts`

**Interfaces:**
- Consumes: `Player.isOnLoan()`, `getOwnerClubId()` (Task 1); the three endpoints (Task 8).
- Produces:
  - Java: `PlayerResponse.onLoanFromClubName` (`String`, null when not on loan), added **immediately after `terminationFee`**
  - TS: `onLoanFromClubName?: string | null` on `PlayerResponse`
  - TS: `type LoanRefusal = 'KEY_PLAYER' | 'BELOW_LEVEL' | 'TERMS_REJECTED'`
  - TS: `type LoanOfferResult = { offerId: string; playerId: string; playerName: string; status: 'PENDING' | 'COUNTERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'; ownerWageShare: number; mayFaceOwner: boolean; counterOwnerWageShare: number | null; counterMayFaceOwner: boolean | null; refusal: LoanRefusal | null }`
  - TS: `loanOut(saveGameId, req: { playerId; borrowerClubId; ownerWageShare; mayFaceOwner }, token)`, `loanIn(saveGameId, req: { playerId; ownerWageShare; mayFaceOwner }, token)`, `acceptLoanCounter(saveGameId, offerId, token)`

**Positional warning, verified on the termination slice:** `PlayerResponse` is a Java record with ~35 components and record arguments are positional. A new component and its argument at the construction site must land in the *same* slot, or every field after it shifts silently and still compiles. Count both sides.

- [ ] **Step 1: Write the failing backend test**

`SquadLoanInfoIT`: create a career, lend a managed player to another club via `Player.loanTo`, read the **borrowing** club's squad through `lineupService.getSquad(...)`, and assert his `onLoanFromClubName()` is the owner's club name. Then assert a normal player's is null. Follow `SquadContractInfoIT`'s inline style — it has no `Career` record and builds the save in each test.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadLoanInfoIT' > /tmp/t12.txt 2>&1; echo "EXIT=$?" >> /tmp/t12.txt
```

Expected: FAIL — no such record component.

- [ ] **Step 3: Add the component and populate it**

In `PlayerResponse`, immediately after `BigDecimal terminationFee,`:

```java
        String onLoanFromClubName,
```

In `LineupService`'s `new PlayerResponse(...)`, immediately after the `contractValuation.terminationFee(...)` argument:

```java
                p.isOnLoan()
                        ? clubRepository.findById(p.getOwnerClubId()).map(Club::getName).orElse(null)
                        : null,
```

`LineupService` already injects `clubRepository` (`getSquad` uses it), so no new dependency.

- [ ] **Step 4: Mirror it in TypeScript and add the client**

In `frontend/src/api/types.ts`, after `terminationFee?: number`:

```ts
  /** The club that owns him while he is on loan here; null when he plays for his owner.
   * Always sent by the API; optional here only to keep older test fixtures compiling. */
  onLoanFromClubName?: string | null
```

And the loan types, beside `TerminationResult`:

```ts
export type LoanRefusal = 'KEY_PLAYER' | 'BELOW_LEVEL' | 'TERMS_REJECTED'

export type LoanOfferResult = {
  offerId: string
  playerId: string
  playerName: string
  status: 'PENDING' | 'COUNTERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
  ownerWageShare: number
  mayFaceOwner: boolean
  counterOwnerWageShare: number | null
  counterMayFaceOwner: boolean | null
  refusal: LoanRefusal | null
}
```

In `frontend/src/api/career.ts`, after `terminateContract`, adding the two types to the type-import list at the top:

```ts
export function loanOut(
  saveGameId: string,
  req: { playerId: string; borrowerClubId: string; ownerWageShare: number; mayFaceOwner: boolean },
  token: string,
): Promise<LoanOfferResult> {
  return apiPost<LoanOfferResult>(`/save-games/${saveGameId}/loans/out`, req, token)
}

export function loanIn(
  saveGameId: string,
  req: { playerId: string; ownerWageShare: number; mayFaceOwner: boolean },
  token: string,
): Promise<LoanOfferResult> {
  return apiPost<LoanOfferResult>(`/save-games/${saveGameId}/loans/in`, req, token)
}

export function acceptLoanCounter(
  saveGameId: string,
  offerId: string,
  token: string,
): Promise<LoanOfferResult> {
  return apiPost<LoanOfferResult>(`/save-games/${saveGameId}/loans/${offerId}/accept-counter`, {}, token)
}
```

- [ ] **Step 5: Write the client test**

Create `frontend/src/api/career.loan.test.ts` using the idiom `career.contract.test.ts` really uses — `import * as client from './client'` and `vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)`:

```ts
  it('loanOut posts the player, the club and both terms', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await loanOut('S1', { playerId: 'P1', borrowerClubId: 'C2', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans/out',
      { playerId: 'P1', borrowerClubId: 'C2', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
  })
```

Add the mirrors for `loanIn` and `acceptLoanCounter`.

- [ ] **Step 6: Verify both sides**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadLoanInfoIT' --tests '*SquadContractInfoIT' > /tmp/t12.txt 2>&1; echo "EXIT=$?" >> /tmp/t12.txt
```

```bash
cd frontend && npx vitest run src/api/career.loan.test.ts && npx tsc -b
```

Expected: both pass; `tsc -b` silent.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/test/java/br/com/carvoeiromanager/match/SquadLoanInfoIT.java frontend/src/api/
git commit -m "feat(loan): ship loan state with the squad and add the API client"
```

---

### Task 13: Lending a player, from the squad

**Files:**
- Create: `frontend/src/features/squad/LoanOutDialog.tsx`, `frontend/src/features/squad/LoanOutDialog.test.tsx`
- Create: `frontend/src/features/loan/loanMessages.ts`, `frontend/src/features/loan/loanMessages.test.ts`
- Modify: `frontend/src/features/squad/SquadPage.tsx`, `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: `loanOut`, `acceptLoanCounter`, `LoanOfferResult`, `LoanRefusal` (Task 12); `getClubs(saveGameId, token): Promise<ClubSummary[]>` from `career.ts` for the club picker.
- Produces: `refusalMessage(refusal: LoanRefusal): string` in `loanMessages.ts`, shared with Task 14.

**The three refusal messages carry the whole realism rule to the player.** Two of them end the conversation; one invites a better offer, and the UI must not blur them:

```ts
export function refusalMessage(refusal: LoanRefusal): string {
  switch (refusal) {
    case 'KEY_PLAYER':
      return 'O clube não abre mão dele: é peça-chave do elenco.'
    case 'BELOW_LEVEL':
      return 'Ele não está no nível do elenco que pediu o empréstimo.'
    case 'TERMS_REJECTED':
      return 'Não gostaram dos termos — tente melhorar a proposta.'
  }
}

/** Whether a better proposal could still succeed. Only the terms are negotiable. */
export function isNegotiable(refusal: LoanRefusal): boolean {
  return refusal === 'TERMS_REJECTED'
}
```

- [ ] **Step 1: Write the failing tests**

`loanMessages.test.ts`: one assertion per refusal, plus `isNegotiable` true only for `TERMS_REJECTED`.

`LoanOutDialog.test.tsx`: the dialog takes `{ player, clubs, pending, result, onSubmit, onAcceptCounter, onClose }` and must
- render a club picker, a wage-share input and a "pode enfrentar meu clube" switch;
- call `onSubmit` with exactly those three values;
- when `result.status === 'REJECTED'`, show `refusalMessage(result.refusal)` and **not** offer to improve the terms;
- when `result.status === 'COUNTERED'`, show the AI's counter terms and an "Aceitar contraproposta" button wired to `onAcceptCounter`;
- render nothing when `player` is null.

Write all six bodies fully.

- [ ] **Step 2: Run to verify they fail**

```bash
cd frontend && npx vitest run src/features/loan src/features/squad/LoanOutDialog.test.tsx
```

Expected: FAIL — modules not found.

- [ ] **Step 3: Build the messages and the dialog**

Write `loanMessages.ts` as above. Build `LoanOutDialog.tsx` with MUI, following `TerminateDialog.tsx` for structure and `formatBRL` from `../../lib/money` if you show any money. Copy is pt-BR.

- [ ] **Step 4: Wire it into `SquadPage`**

Add a "Emprestar" button to the `contractAction` group beside "Renovar" and "Rescindir"; a `loanOutMut`; and on success invalidate `['squad', …]`, `['finances', …]`, `['market', …]` and `['lineup', …]`. Disable the button when `player.contractYears < 2`, with the reason shown — that is the guard the backend enforces, and failing only after the click is the pattern this codebase has already had to fix once.

Also mark borrowed players in the squad list using `player.onLoanFromClubName`.

- [ ] **Step 5: Verify**

```bash
cd frontend && npx vitest run && npx tsc -b
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/loan frontend/src/features/squad
git commit -m "feat(squad): lend a player to another club"
```

---

### Task 14: Borrowing, from the transfer market

**Files:**
- Create: `frontend/src/features/transfer/LoanInDialog.tsx`, `frontend/src/features/transfer/LoanInDialog.test.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx`, `frontend/src/features/transfer/TransferMarketPage.test.tsx`

**Interfaces:**
- Consumes: `loanIn`, `acceptLoanCounter`, `LoanOfferResult` (Task 12); `refusalMessage`, `isNegotiable` (Task 13).
- Produces: nothing later tasks depend on.

A loan is an alternative to a bid on the same player, so it belongs beside "Fazer proposta" on the market row, not on a separate page.

- [ ] **Step 1: Write the failing tests**

`LoanInDialog.test.tsx` mirrors Task 13's list, minus the club picker (the owner is whoever holds the player) — the dialog takes `{ player, pending, result, onSubmit, onAcceptCounter, onClose }` and submits `{ ownerWageShare, mayFaceOwner }`.

In `TransferMarketPage.test.tsx`: opening the dialog from a market row, submitting, and — the case worth its own test — a `KEY_PLAYER` refusal showing its message with **no** path to improve the offer.

- [ ] **Step 2: Run to verify they fail**

```bash
cd frontend && npx vitest run src/features/transfer
```

- [ ] **Step 3: Build the dialog and wire the market page**

On success invalidate `['squad', …]`, `['market', …]` and `['finances', …]`.

- [ ] **Step 4: Verify**

```bash
cd frontend && npx vitest run && npx tsc -b
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/transfer
git commit -m "feat(transfer): borrow a player from the market"
```

---

### Task 15: Full-suite verification

Nothing new is written. This proves the whole system still holds after changes that reach the wage bill, the rollover and XI selection — three paths whose blast radius is far wider than their own tests.

**Files:** none.

- [ ] **Step 1: Whole backend suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --rerun-tasks > /tmp/backend-full.txt 2>&1; echo "GRADLE_EXIT=$?" >> /tmp/backend-full.txt
```

Read `GRADLE_EXIT` **from inside the file**. Do not trust a shell status for this command — a compound ending in `echo` reports the echo's success even when Gradle failed.

Expected: `GRADLE_EXIT=0`. If the only failure is `TransferServiceSellingIT.listedManagedPlayersDrawBoostedBids`, that is the known ~26% flake: re-run that class alone, confirm the rate, and confirm no coupling path from this work before calling it green. Any other failure is real.

- [ ] **Step 2: Whole frontend suite and the production build**

```bash
cd frontend && npx vitest run && npm run build
```

`npm run build` is the gate Vercel actually runs, so it is the only definitive frontend check.

- [ ] **Step 3: Report**

State the actual pass/fail counts from both suites. Do not claim completion without them.
