# Contract Termination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager terminate a player's contract — paying a fee proportional to what the club still owes — sending the player into the free-agent pool and barring the club from re-signing him this season.

**Architecture:** Termination is a new operation on `ContractService`, sibling to `renewContract`. The fee is a pure function on `ContractValuation`. The two halves of "a player leaves the managed squad" (the departure guard, the lineup cleanup) are extracted from `TransferService` / `TransferExecutor` into a shared `SquadDeparture` component that selling and terminating both use. A new nullable `players.released_by_club_id` column records who released a player and is cleared at season rollover.

**Tech Stack:** Java 25 / Spring Boot, JPA with plain UUID FK columns, Flyway migrations, JUnit 5 + AssertJ + Testcontainers (Postgres) on the backend. React + TypeScript + MUI + TanStack Query + Vitest + React Testing Library on the frontend.

**Spec:** `docs/superpowers/specs/2026-09-01-contract-termination-design.md`

## Global Constraints

- **Backend build/test commands MUST pin `JAVA_HOME`.** The shell profile exports Java 8 and the build dies before compiling anything. Always:
  `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '...'`
- **Capture Gradle's exit code directly**, never after a pipe: `./gradlew ... > out.txt 2>&1; echo "EXIT=$?"`. After a pipe you record `tail`'s status and a failed build reports success.
- **Integration tests (`*IT`) need Docker running** (OrbStack) for Testcontainers. If every IT fails at once at `DefaultCacheAwareContextLoaderDelegate`, Docker is down: `open -a OrbStack`. That is not a code failure.
- **Frontend type-checking MUST use `npx tsc -b`** (or `npm run build`). Bare `npx tsc --noEmit` resolves zero files in this repo and always reports clean — it is a false negative.
- **Money is always `BigDecimal` scaled to 2**, never `double`. Compare with `isEqualByComparingTo`, never `isEqualTo`.
- **Deterministic, no RNG** anywhere in the fee or the guards.
- **UI copy is pt-BR.** Ledger descriptions follow the existing `"Compra: <name>"` / `"Venda: <name>"` shape.
- **Never run `git push`.** Commit only.

---

### Task 1: The release mark on `Player`

Records which club released a player, and expires that mark at season rollover. Nothing reads it yet — Task 5 does.

**Files:**
- Create: `backend/src/main/resources/db/migration/V53__contract_termination.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `UUID Player.getReleasedByClubId()`
  - `void Player.releaseFrom(UUID clubId)` — the whole termination mutation on the entity: nulls `clubId`, zeroes `wage` and `contractYears`, calls `unlist()`, sets `releasedByClubId = clubId`.
  - `Player.resetSeasonState()` additionally clears `releasedByClubId`.

- [ ] **Step 1: Write the failing test**

Append to `RolloverContractsIT` (it already has `playToSeasonEnd()`, `probePlayer(...)` and the `Career` record):

```java
    @Test
    void aReleaseMarkExpiresAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> squad = playerRepository.findByClubId(c.clubId());
        squad.forEach(p -> p.setContractYears(5));   // keep everyone else off the lapse path
        Player released = probePlayer(c.id(), squad);
        playerRepository.saveAll(squad);

        released.releaseFrom(c.clubId());
        playerRepository.save(released);
        assertThat(playerRepository.findById(released.getId()).orElseThrow().getReleasedByClubId())
                .isEqualTo(c.clubId());

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(released.getId()).orElseThrow();
        assertThat(reloaded.getReleasedByClubId()).isNull();
        assertThat(reloaded.isFreeAgent()).isTrue();
    }
```

`probePlayer` already filters to non-prospects aged ≤ 33 with no pre-contract, so the released player survives the rollover's retirement roll instead of being deleted.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*RolloverContractsIT' > /tmp/t1.txt 2>&1; echo "EXIT=$?"
```

Expected: FAIL — compile error, `cannot find symbol: method releaseFrom(UUID)`.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V53__contract_termination.sql`:

```sql
-- Which club released this player. Set when a manager terminates a contract; read by
-- signFreeAgent to refuse re-signing your own cast-off, and cleared at season rollover so
-- the block lasts exactly one season.
ALTER TABLE players ADD COLUMN released_by_club_id UUID;
```

- [ ] **Step 4: Add the field, accessor and mutators to `Player`**

Add the column next to `listedReason` (the other nullable UUID/enum contract-adjacent state):

```java
    /**
     * The club that terminated this player's contract, or null. Blocks that club — and only
     * that club — from re-signing him as a free agent, until {@link #resetSeasonState()}
     * clears it at rollover.
     */
    @Column(name = "released_by_club_id")
    private UUID releasedByClubId;
```

Add the getter beside `getListedReason()`:

```java
    public UUID getReleasedByClubId() { return releasedByClubId; }
```

Add the domain mutator beside `unlist()`:

```java
    /**
     * Terminates this player's contract on behalf of {@code clubId}: he leaves the squad as a
     * free agent with no terms, carrying the mark that bars that club from re-signing him this
     * season. The fee, the ledger row and the squad's morale are the service's business.
     */
    public void releaseFrom(UUID clubId) {
        this.clubId = null;
        this.wage = BigDecimal.ZERO;
        this.contractYears = 0;
        this.releasedByClubId = clubId;
        unlist();
    }
```

In `resetSeasonState()`, add the clearing line beside the other per-season resets:

```java
        this.releasedByClubId = null;
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*RolloverContractsIT' > /tmp/t1.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS, all four tests in the class.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V53__contract_termination.sql backend/src/main/java/br/com/carvoeiromanager/player/Player.java backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java
git commit -m "feat(contract): record which club released a player, clear it at rollover"
```

---

### Task 2: The termination fee

A pure function, no RNG, alongside the other deterministic contract economics.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractValuation.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractValuationTest.java`

**Interfaces:**
- Consumes: `FinanceCalculator.monthlyWage(int overall, int age): BigDecimal` (already exists).
- Produces:
  - `public static final int FinanceCalculator.MONTHS_PER_SEASON = 10`
  - `BigDecimal ContractValuation.terminationFee(int overall, int age, int contractYears)`

**Reference arithmetic** (`weeklyWage = overall² × 20 × ageFactor`; ageFactor 0.60 at ≤20, 0.80 at ≥32, else 1.00; `monthlyWage = weeklyWage × 4`):

| overall | age | years | monthlyWage | fee |
|---|---|---|---|---|
| 70 | 25 | 3 | 392 000.00 | 5 880 000.00 |
| 70 | 25 | 1 | 392 000.00 | 1 960 000.00 |
| 60 | 25 | 2 | 288 000.00 | 2 880 000.00 |
| 70 | 33 | 2 | 313 600.00 | 3 136 000.00 |

- [ ] **Step 1: Write the failing test**

Append to `ContractValuationTest`:

```java
    @Test
    void terminationFeeIsHalfOfTheRemainingWageBill() {
        // monthlyWage(70,25) = 70*70*20*1.00*4 = 392000.00
        // fee = years * 10 months * 392000.00 * 0.50
        assertThat(valuation.terminationFee(70, 25, 3)).isEqualByComparingTo("5880000.00");
        assertThat(valuation.terminationFee(70, 25, 1)).isEqualByComparingTo("1960000.00");
        assertThat(valuation.terminationFee(60, 25, 2)).isEqualByComparingTo("2880000.00");
    }

    @Test
    void terminationFeeFollowsTheAgeCurveLikeEveryOtherWageFormula() {
        // A veteran's 0.80 factor makes him cheaper to release than the same player in his prime.
        assertThat(valuation.terminationFee(70, 33, 2)).isEqualByComparingTo("3136000.00");
        assertThat(valuation.terminationFee(70, 33, 2))
                .isLessThan(valuation.terminationFee(70, 25, 2));
    }

    @Test
    void aLongerContractIsProportionallyMoreExpensiveToTerminate() {
        assertThat(valuation.terminationFee(70, 25, 4))
                .isEqualByComparingTo(valuation.terminationFee(70, 25, 2).multiply(new BigDecimal("2")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractValuationTest' > /tmp/t2.txt 2>&1; echo "EXIT=$?"
```

Expected: FAIL — compile error, `cannot find symbol: method terminationFee(int,int,int)`.

- [ ] **Step 3: Publish the season's month count**

In `FinanceCalculator`, promote the figure the class javadoc already documents ("10 months per season, Jan–Oct") to a real constant, placed with the other monthly constants:

```java
    /**
     * Charged months in one season (Jan–Oct). The whole monthly model — wages, sponsorship,
     * upkeep — bills this many times per season, so anything priced in "a season of wages"
     * must multiply by this and not by 12.
     */
    public static final int MONTHS_PER_SEASON = 10;
```

- [ ] **Step 4: Add the fee to `ContractValuation`**

Add the constant beside `DEMAND_PREMIUM`:

```java
    /** Share of the remaining wage bill a club buys out to tear up a contract. */
    static final BigDecimal TERMINATION_FACTOR = new BigDecimal("0.50");
```

And the method beside `wageDemand`:

```java
    /**
     * What it costs to tear up a contract with {@code contractYears} still to run: half of the
     * wages the club would otherwise still pay. Denominated in monthly wages so it lands in the
     * same currency as the wage bill it replaces.
     */
    public BigDecimal terminationFee(int overall, int age, int contractYears) {
        return financeCalculator.monthlyWage(overall, age)
                .multiply(BigDecimal.valueOf((long) contractYears * FinanceCalculator.MONTHS_PER_SEASON))
                .multiply(TERMINATION_FACTOR)
                .setScale(2, RoundingMode.HALF_UP);
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractValuationTest' > /tmp/t2.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS, seven tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java backend/src/main/java/br/com/carvoeiromanager/contract/ContractValuation.java backend/src/test/java/br/com/carvoeiromanager/contract/ContractValuationTest.java
git commit -m "feat(contract): price a contract termination at half the remaining wage bill"
```

---

### Task 3: Extract `SquadDeparture`

Pure refactor, no behaviour change. Today `TransferService.requireSellAllowed` (the guard) and `TransferExecutor.cleanManagedLineup` (the cleanup) are both private, and termination needs both verbatim. The gate for this task is that the existing transfer suite still passes.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/common/SquadDeparture.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java:40` (constant), `:383-392` (`requireSellAllowed`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferExecutor.java:111-124` (`cleanManagedLineup`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/AiTransferEngine.java:195` (constant reference)
- Test: no new test — `TransferServiceSellingIT`, `TransferServiceBuyingIT`, `AiTransferEngineIT` are the regression gate.

**Interfaces:**
- Consumes: `PlayerRepository`, `LineupRepository`, `LineupPlayerRepository`.
- Produces:
  - `public static final int SquadRules.MIN_SQUAD_SIZE = 14`
  - `List<Player> SquadDeparture.seniorSquad(UUID clubId)`
  - `void SquadDeparture.requireDepartureAllowed(List<Player> seniorSquad, Player leaving)` — throws `ApiException(SQUAD_TOO_SMALL)`
  - `void SquadDeparture.cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId)`

**Note:** `TransferServiceSellingIT` is genuinely flaky (~26% failure from a correlated `Random` seed), unrelated to this change. If it fails, re-run it before investigating; only a *consistent* failure is this task's problem.

- [ ] **Step 1: Move `MIN_SQUAD_SIZE` to `SquadRules`**

In `SquadRules`, add beside `MAX_SQUAD_SIZE`:

```java
    /** Floor the managed squad may not drop below — every departure path counts against it. */
    public static final int MIN_SQUAD_SIZE = 14;
```

Delete `static final int MIN_SQUAD_SIZE = 14;` from `TransferService` (line 40) and point both remaining readers at the new home: `TransferService:385` and `AiTransferEngine:195` become `SquadRules.MIN_SQUAD_SIZE` (add the `br.com.carvoeiromanager.common.SquadRules` import where missing).

- [ ] **Step 2: Create `SquadDeparture`**

```java
package br.com.carvoeiromanager.common;

import br.com.carvoeiromanager.match.Lineup;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The two rules every way out of the managed squad shares — selling a player and terminating
 * his contract: what makes a departure legal, and what must be cleaned up once it happens.
 * Kept in one place so the two paths cannot drift apart.
 */
@Component
public class SquadDeparture {

    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public SquadDeparture(PlayerRepository playerRepository,
                          LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository) {
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    /** The professional squad: youth prospects never count toward the departure rules. */
    public List<Player> seniorSquad(UUID clubId) {
        return playerRepository.findByClubIdAndYouthProspectFalse(clubId);
    }

    /**
     * Refuses a departure that would leave the club unable to field a team: below the squad
     * floor, or without a goalkeeper.
     */
    public void requireDepartureAllowed(List<Player> seniorSquad, Player leaving) {
        if (seniorSquad.size() <= SquadRules.MIN_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Squad would be too small");
        }
        if (leaving.getPosition() == Position.GK
                && seniorSquad.stream().filter(p -> p.getPosition() == Position.GK).count() <= 1) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Cannot release your last goalkeeper");
        }
    }

    /**
     * Removes a departing player from the managed lineup: the lineup_players row and any
     * set-piece-taker column pointing at him (no-cascade FK safety).
     */
    public void cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId) {
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
}
```

The sell-path message stays `"Cannot sell your last goalkeeper"` in spirit but is now shared; `"Cannot release your last goalkeeper"` covers both. `ApiException` and `ErrorCode` are already in `br.com.carvoeiromanager.common`, so no import is needed for them.

- [ ] **Step 3: Delegate from `TransferService`**

Inject `SquadDeparture squadDeparture` through the constructor, then replace the body of `requireSellAllowed`:

```java
    private void requireSellAllowed(UUID managedClubId, Player player) {
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(managedClubId), player);
    }
```

- [ ] **Step 4: Delegate from `TransferExecutor`**

Inject `SquadDeparture squadDeparture` through the constructor. Delete the private `cleanManagedLineup` method entirely, and change its single call site inside `execute(...)` to:

```java
            squadDeparture.cleanManagedLineup(saveGameId, managedClubId, player.getId());
```

`LineupRepository` and `LineupPlayerRepository` are now unused in `TransferExecutor` — remove those two constructor parameters, fields and imports.

- [ ] **Step 5: Run the transfer suite to verify no behaviour changed**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*Transfer*' > /tmp/t3.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS. On a `TransferServiceSellingIT` failure, re-run the same command once — it is a known flake. A repeated failure is real.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/SquadDeparture.java backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java backend/src/main/java/br/com/carvoeiromanager/transfer/
git commit -m "refactor(squad): one definition of leaving the managed squad, shared by selling"
```

---

### Task 4: `terminateContract`

The operation itself: guards, fee, ledger, morale, departure. Includes the controller mapping — `ContractController` has no test today, and this task does not invent one convention for one endpoint; the IT covers the service.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/contract/TerminationResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceTransactionType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceTerminateIT.java`

**Interfaces:**
- Consumes: `Player.releaseFrom(UUID)` (Task 1); `ContractValuation.terminationFee(int,int,int)` (Task 2); `SquadDeparture.seniorSquad`, `.requireDepartureAllowed`, `.cleanManagedLineup` (Task 3).
- Produces:
  - `record TerminationResponse(UUID playerId, String playerName, BigDecimal fee, BigDecimal newBalance)`
  - `TerminationResponse ContractService.terminateContract(UUID saveGameId, UUID userId, UUID playerId)`
  - `POST /api/v1/save-games/{saveGameId}/players/{playerId}/terminate`
  - `FinanceTransactionType.TERMINATION`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceTerminateIT.java`:

```java
package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.finance.FinanceTransaction;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionType;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.match.LineupResponse;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceTerminateIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired FinanceTransactionRepository financeTransactionRepository;
    @Autowired br.com.carvoeiromanager.match.LineupService lineupService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("terminate_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<Player> squad(Career c) {
        return playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
    }

    /** An outfielder, chosen by lowest id so the pick is stable across seed changes. */
    private Player anOutfielder(Career c) {
        return squad(c).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId))
                .orElseThrow();
    }

    /** Gives the club far more money than any fee, so solvency never confounds a test. */
    private void makeRich(Career c) {
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        club.adjustBalance(new BigDecimal("1000000000").subtract(club.getBalance()));
        clubRepository.save(club);
    }

    @Test
    void terminatingReleasesThePlayerAndChargesTheFee() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        BigDecimal expectedFee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        TerminationResponse r = contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(r.playerName()).isEqualTo(p.getName());
        assertThat(r.fee()).isEqualByComparingTo(expectedFee);
        assertThat(r.newBalance()).isEqualByComparingTo(balanceBefore.subtract(expectedFee));

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(reloaded.getContractYears()).isZero();
        assertThat(reloaded.getWage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reloaded.getReleasedByClubId()).isEqualTo(c.clubId());

        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore.subtract(expectedFee));
    }

    @Test
    void theFeeIsRecordedAsATerminationInTheLedger() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        BigDecimal expectedFee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .filteredOn(t -> t.getType() == FinanceTransactionType.TERMINATION)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getAmount()).isEqualByComparingTo(expectedFee.negate());
                    assertThat(t.getDescription()).isEqualTo("Rescisão: " + p.getName());
                });
    }

    @Test
    void theRestOfTheSquadLosesMoraleButNotTheReleasedPlayer() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        List<UUID> othersAtZero = squad(c).stream()
                .filter(o -> !o.getId().equals(p.getId()))
                .filter(o -> o.getMorale() == 0)
                .map(Player::getId).toList();
        assertThat(othersAtZero).isNotEmpty();

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        for (UUID id : othersAtZero) {
            assertThat(playerRepository.findById(id).orElseThrow().getMorale())
                    .as("morale of squad member %s", id)
                    .isEqualTo(-1);
        }
    }

    @Test
    void aSquadAtTheFloorCannotShrinkFurther() {
        Career c = createCareer();
        makeRich(c);
        List<Player> squad = squad(c);
        Player p = anOutfielder(c);
        // Trim to exactly the floor, keeping the probe and every goalkeeper.
        List<Player> expendable = squad.stream()
                .filter(o -> !o.getId().equals(p.getId()))
                .filter(o -> o.getPosition() != Position.GK)
                .toList();
        int mustDelete = squad.size() - SquadRules.MIN_SQUAD_SIZE;
        playerRepository.deleteAll(expendable.subList(0, mustDelete));
        playerRepository.flush();
        assertThat(squad(c)).hasSize(SquadRules.MIN_SQUAD_SIZE);

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), p.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isFalse();
    }

    @Test
    void theLastGoalkeeperCannotBeReleased() {
        Career c = createCareer();
        makeRich(c);
        List<Player> keepers = squad(c).stream()
                .filter(o -> o.getPosition() == Position.GK)
                .sorted(Comparator.comparing(Player::getId))
                .toList();
        Player lastKeeper = keepers.get(0);
        playerRepository.deleteAll(keepers.subList(1, keepers.size()));
        playerRepository.flush();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), lastKeeper.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        assertThat(playerRepository.findById(lastKeeper.getId()).orElseThrow().isFreeAgent()).isFalse();
    }

    @Test
    void aClubThatCannotAffordTheFeeCannotTerminate() {
        Career c = createCareer();
        Player p = anOutfielder(c);
        BigDecimal fee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        club.adjustBalance(fee.subtract(new BigDecimal("0.01")).subtract(club.getBalance()));
        clubRepository.save(club);
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), p.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isFalse();
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
    }

    @Test
    void anotherClubsPlayerCannotBeTerminated() {
        Career c = createCareer();
        makeRich(c);
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(o -> o.getClubId() != null && !o.getClubId().equals(c.clubId()))
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), rival.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void aYouthProspectCannotBeTerminated() {
        Career c = createCareer();
        makeRich(c);
        Player prospect = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(Player::isYouthProspect)
                .filter(o -> c.clubId().equals(o.getClubId()))
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), prospect.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void aReleasedStarterLeavesTheLineup() {
        Career c = createCareer();
        makeRich(c);
        List<Player> bestXi = lineupService.bestXi(c.clubId());
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(),
                        RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(c.clubId()).stream()
                .filter(o -> !xiIds.contains(o.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(o -> new LineupStarter(o.getId(),
                        RoleArchetype.defaultFor(o.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(),
                new SetLineupRequest("4-4-2", starters, bench), c.userId());

        Player starter = bestXi.stream()
                .filter(o -> o.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        contractService.terminateContract(c.id(), c.userId(), starter.getId());

        LineupResponse lineup = lineupService.getLineup(c.id(), c.clubId(), c.userId()).orElseThrow();
        assertThat(lineup.starters()).extracting(LineupStarter::playerId)
                .doesNotContain(starter.getId());
        assertThat(lineup.bench()).extracting(LineupStarter::playerId)
                .doesNotContain(starter.getId());
    }

    @Test
    void terminatingWorksWhileTheTransferWindowIsShut() {
        // Deliberate design rule: termination is not a transfer. A career starts mid-window, so
        // walk the save's date past the window and confirm the operation still succeeds — the
        // guard that signFreeAgent applies must NOT be copied here.
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isTrue();
    }
}
```

**On the last test:** if `TransferWindow.isOpen(save.getCurrentDate())` is already `false` at career start, keep it as written — it proves the rule directly. If it is `true`, advance the save's `currentDate` past the window (`saveGameRepository`, `save.setCurrentDate(...)`, consult `TransferWindow` for the closed range) before calling, and update the comment accordingly. Do not delete the test.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractServiceTerminateIT' > /tmp/t4.txt 2>&1; echo "EXIT=$?"
```

Expected: FAIL — compile error, `cannot find symbol: method terminateContract(...)` and `class TerminationResponse`.

- [ ] **Step 3: Add the ledger type and the response record**

In `FinanceTransactionType`, add `TERMINATION` to the enum:

```java
public enum FinanceTransactionType {
    GATE, MATCH_COST, WAGES, SPONSOR, UPKEEP, PRIZE, TRANSFER_IN, TRANSFER_OUT, STAFF_WAGES,
    TERMINATION
}
```

Create `backend/src/main/java/br/com/carvoeiromanager/contract/TerminationResponse.java`:

```java
package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;
import java.util.UUID;

/** What a termination cost and what it left in the bank — enough for the UI to report it. */
public record TerminationResponse(UUID playerId, String playerName, BigDecimal fee,
                                  BigDecimal newBalance) {
}
```

- [ ] **Step 4: Implement `terminateContract`**

Add `ClubRepository clubRepository`, `FinanceTransactionRepository financeTransactionRepository` and `SquadDeparture squadDeparture` to `ContractService`'s constructor and fields, then add the method after `signFreeAgent`:

```java
    /**
     * Tears up a player's contract: the club buys out half of what it still owes, the player
     * leaves as a free agent, and the dressing room takes it badly. Unlike signing, this is
     * allowed with the transfer window shut — a termination is not a transfer, and a bloated
     * squad in mid-season is exactly when it is needed.
     */
    @Transactional
    public TerminationResponse terminateContract(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .filter(p -> !p.isYouthProspect())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player not found in your professional squad"));

        List<Player> squad = squadDeparture.seniorSquad(managedClubId);
        squadDeparture.requireDepartureAllowed(squad, player);

        BigDecimal fee = contractValuation.terminationFee(
                player.getOverall(), player.getAge(), player.getContractYears());
        Club club = clubRepository.findById(managedClubId).orElseThrow();
        if (club.getBalance().compareTo(fee) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "The club cannot afford the termination fee of " + fee);
        }

        club.adjustBalance(fee.negate());
        clubRepository.save(club);
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGameId, managedClubId, save.getSeasonYear(), save.getCurrentRound(),
                FinanceTransactionType.TERMINATION, fee.negate(), "Rescisão: " + player.getName()));

        // The dressing room reads a release as a threat, so everyone still there takes the hit.
        List<Player> remaining = squad.stream()
                .filter(p -> !p.getId().equals(player.getId()))
                .toList();
        remaining.forEach(p -> p.applyMorale(-1));
        playerRepository.saveAll(remaining);

        player.releaseFrom(managedClubId);
        playerRepository.save(player);
        squadDeparture.cleanManagedLineup(saveGameId, managedClubId, player.getId());

        return new TerminationResponse(player.getId(), player.getName(), fee, club.getBalance());
    }
```

Add the imports `br.com.carvoeiromanager.club.Club`, `br.com.carvoeiromanager.club.ClubRepository`, `br.com.carvoeiromanager.common.SquadDeparture`, `br.com.carvoeiromanager.finance.FinanceTransaction`, `br.com.carvoeiromanager.finance.FinanceTransactionRepository`, `br.com.carvoeiromanager.finance.FinanceTransactionType`.

- [ ] **Step 5: Add the endpoint**

In `ContractController`, after the `renew` mapping:

```java
    @PostMapping("/players/{playerId}/terminate")
    public ResponseEntity<TerminationResponse> terminate(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.terminateContract(saveGameId, userId, playerId));
    }
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractServiceTerminateIT' > /tmp/t4.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS, ten tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/contract/ backend/src/main/java/br/com/carvoeiromanager/finance/FinanceTransactionType.java backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceTerminateIT.java
git commit -m "feat(contract): let the manager terminate a contract for a buyout fee"
```

---

### Task 5: A released player cannot be re-signed by the club that released him

Without this, terminating and re-signing as a free agent is a wage cut dressed as a fee.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java` (`signFreeAgent`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java`

**Interfaces:**
- Consumes: `Player.getReleasedByClubId()` (Task 1), `ContractService.terminateContract` (Task 4).
- Produces: no new signatures — `signFreeAgent` gains an `INVALID_TRANSFER` rejection path.

- [ ] **Step 1: Write the failing test**

Append to `ContractServiceSignIT`. Match that file's existing `createCareer()` / career-record helpers rather than inventing new ones; autowire `ContractValuation` and `ClubRepository` if the class does not already have them.

```java
    @Test
    void aPlayerThisClubReleasedCannotBeReSignedThisSeason() {
        Career c = createCareer();
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        club.adjustBalance(new BigDecimal("1000000000").subtract(club.getBalance()));
        clubRepository.save(club);

        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(o -> o.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        contractService.terminateContract(c.id(), c.userId(), p.getId());

        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        assertThatThrownBy(() -> contractService.signFreeAgent(c.id(), c.userId(), p.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSFER));

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isTrue();
    }

    @Test
    void aPlayerAnotherClubReleasedSignsNormally() {
        Career c = createCareer();
        Player free = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        UUID rivalClubId = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        free.releaseFrom(rivalClubId);   // released elsewhere; our club is unaffected
        playerRepository.save(free);

        BigDecimal demand = contractValuation.wageDemand(free.getOverall(), free.getAge())
                .multiply(new BigDecimal("2"));   // clear any scout discount comfortably

        contractService.signFreeAgent(c.id(), c.userId(), free.getId(), demand, 3);

        assertThat(playerRepository.findById(free.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractServiceSignIT' > /tmp/t5.txt 2>&1; echo "EXIT=$?"
```

Expected: FAIL — `aPlayerThisClubReleasedCannotBeReSignedThisSeason` signs the player instead of throwing.

- [ ] **Step 3: Add the guard to `signFreeAgent`**

Immediately after the free-agent lookup, before `applyTerms`:

```java
        if (save.getManagedClubId().equals(player.getReleasedByClubId())) {
            throw new ApiException(ErrorCode.INVALID_TRANSFER,
                    "You released this player; he cannot return until next season");
        }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ContractServiceSignIT' > /tmp/t5.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS, every test in the class.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java
git commit -m "feat(contract): bar a club from re-signing the player it just released"
```

---

### Task 6: Ship the fee on every squad player

The UI must state the fee before the manager commits, so it rides along on `PlayerResponse` exactly as `wageDemand` already does — no preview endpoint.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/PlayerResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java:793-826` (the `PlayerResponse` constructor call)
- Modify: `frontend/src/api/types.ts` (the `PlayerResponse` type)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SquadContractInfoIT.java`

**Interfaces:**
- Consumes: `ContractValuation.terminationFee(int,int,int)` (Task 2). `LineupService` already injects `contractValuation`.
- Produces: `PlayerResponse.terminationFee` (Java `BigDecimal`, immediately after `wageDemand`); TS `terminationFee?: number`.

- [ ] **Step 1: Write the failing test**

`SquadContractInfoIT` has no `Career` record — it builds the save inline in each test and reads
the squad through `lineupService.getSquad(...)`. Follow that. Add
`@Autowired ContractValuation contractValuation;` to the field block, then append:

```java
    @Test
    void everySquadPlayerCarriesHisTerminationFee() {
        User user = User.create("termfee_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        SquadResponse squad = lineupService.getSquad(save.id(), save.managedClubId(), user.getId());

        assertThat(squad.players()).isNotEmpty();
        assertThat(squad.players()).allSatisfy(p ->
                assertThat(p.terminationFee()).isNotNull().isPositive());

        PlayerResponse any = squad.players().get(0);
        Player entity = playerRepository.findById(any.id()).orElseThrow();
        assertThat(any.terminationFee()).isEqualByComparingTo(contractValuation.terminationFee(
                entity.getOverall(), entity.getAge(), entity.getContractYears()));
    }
```

Add `br.com.carvoeiromanager.contract.ContractValuation` to the imports. Every other symbol the
test uses is already imported by that file.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadContractInfoIT' > /tmp/t6.txt 2>&1; echo "EXIT=$?"
```

Expected: FAIL — compile error, `cannot find symbol: method terminationFee()`.

- [ ] **Step 3: Add the field to the record and populate it**

In `PlayerResponse`, insert immediately after `BigDecimal wageDemand,`:

```java
        BigDecimal terminationFee,
```

In `LineupService`, insert the matching argument immediately after the `contractValuation.wageDemand(...)` line:

```java
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears()),
```

- [ ] **Step 4: Mirror it in the TypeScript type**

In `frontend/src/api/types.ts`, immediately after `wageDemand: number`:

```ts
  /** What terminating this player's contract would cost. Always sent by the API; optional here
   * only to keep older test fixtures compiling. */
  terminationFee?: number
```

Optional deliberately, matching the `transferListed` precedent three lines below — making it required would force a mechanical edit across eight unrelated test fixtures for no added safety.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SquadContractInfoIT' > /tmp/t6.txt 2>&1; echo "EXIT=$?"
```

Expected: PASS.

```bash
cd frontend && npx tsc -b
```

Expected: no output, exit 0.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/test/java/br/com/carvoeiromanager/match/SquadContractInfoIT.java frontend/src/api/types.ts
git commit -m "feat(squad): send each player's termination fee with the squad payload"
```

---

### Task 7: The API client

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Test: `frontend/src/api/career.contract.test.ts`

**Interfaces:**
- Consumes: `POST /save-games/{id}/players/{playerId}/terminate` (Task 4).
- Produces:
  - `type TerminationResult = { playerId: string; playerName: string; fee: number; newBalance: number }`
  - `terminateContract(saveGameId: string, playerId: string, token: string): Promise<TerminationResult>`

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/api/career.contract.test.ts`, and add `terminateContract` to that file's import list from `./career`:

```ts
  it('terminateContract posts to the terminate endpoint with no body', async () => {
    const spy = vi.spyOn(http, 'apiPost').mockResolvedValue({})
    await terminateContract('S1', 'P1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/players/P1/terminate', {}, 'tok')
  })
```

Match the existing spy/mock setup in that file — the surrounding tests already establish how `apiPost` is stubbed there; follow it rather than introducing a second style.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/api/career.contract.test.ts
```

Expected: FAIL — `terminateContract is not exported`.

- [ ] **Step 3: Add the type**

In `frontend/src/api/types.ts`, beside `PlayerContract`:

```ts
/** What a termination cost and the balance it left behind. */
export type TerminationResult = {
  playerId: string
  playerName: string
  fee: number
  newBalance: number
}
```

- [ ] **Step 4: Add the client function**

In `frontend/src/api/career.ts`, directly after `renewContract`, and add `TerminationResult` to the type import at the top of the file:

```ts
export function terminateContract(
  saveGameId: string,
  playerId: string,
  token: string,
): Promise<TerminationResult> {
  return apiPost<TerminationResult>(`/save-games/${saveGameId}/players/${playerId}/terminate`, {}, token)
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd frontend && npx vitest run src/api/career.contract.test.ts && npx tsc -b
```

Expected: PASS, and a clean `tsc -b`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/career.ts frontend/src/api/types.ts frontend/src/api/career.contract.test.ts
git commit -m "feat(api): client for the contract-termination endpoint"
```

---

### Task 8: The termination dialog

The manager-facing half: a confirm dialog stating the cost, and a button that explains itself when the move is impossible.

**Files:**
- Create: `frontend/src/features/squad/TerminateDialog.tsx`
- Create: `frontend/src/features/squad/TerminateDialog.test.tsx`
- Modify: `frontend/src/features/squad/SquadPage.tsx` (imports, the `['finances', …]` query, `terminateMut`, `terminateButton`, the dialog, the error snackbar)
- Modify: `frontend/src/features/squad/SquadPage.test.tsx` (`getFinances` and `terminateContract` mocks, `STATE` fixture)

**Interfaces:**
- Consumes: `terminateContract` (Task 7); `PlayerResponse.terminationFee` (Task 6); `getFinances(saveGameId, clubId, token): Promise<FinancesResponse>` with `{ balance, transactions }`, cached under `['finances', saveGameId, clubId]`.
- Produces: `TerminateDialog` — props `{ player: PlayerResponse | null; balance: number | undefined; blockedReason: string | null; pending: boolean; onConfirm: () => void; onClose: () => void }`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/squad/TerminateDialog.test.tsx`:

```tsx
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { TerminateDialog } from './TerminateDialog'
import type { PlayerResponse } from '../../api/types'

const PLAYER = {
  id: 'p1', name: 'Jogador Teste', position: 'MID', age: 25, overall: 70,
  contractYears: 3, terminationFee: 5880000,
} as unknown as PlayerResponse

describe('TerminateDialog', () => {
  it('states the fee and the balance the club is left with', () => {
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText(/Jogador Teste/)).toBeInTheDocument()
    expect(screen.getByTestId('termination-fee')).toHaveTextContent('5.880.000')
    expect(screen.getByTestId('termination-new-balance')).toHaveTextContent('14.120.000')
  })

  it('warns that the player cannot be re-signed this season', () => {
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText(/não poderá ser recontratado/i)).toBeInTheDocument()
  })

  it('confirming calls onConfirm', () => {
    const onConfirm = vi.fn()
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={onConfirm} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /confirmar rescisão/i }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('cancelling calls onClose and never confirms', () => {
    const onConfirm = vi.fn()
    const onClose = vi.fn()
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={onConfirm} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('blocks confirmation and shows why when the move is impossible', () => {
    render(<TerminateDialog player={PLAYER} balance={1000} blockedReason="Saldo insuficiente para a multa."
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: /confirmar rescisão/i })).toBeDisabled()
    expect(screen.getByText('Saldo insuficiente para a multa.')).toBeInTheDocument()
  })

  it('renders nothing when no player is selected', () => {
    const { container } = render(<TerminateDialog player={null} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/features/squad/TerminateDialog.test.tsx
```

Expected: FAIL — cannot resolve `./TerminateDialog`.

- [ ] **Step 3: Build the dialog**

Create `frontend/src/features/squad/TerminateDialog.tsx`. Money is formatted with the existing
`formatBRL` from `frontend/src/lib/money.ts` — the same helper `FinancesPage` uses. Do not write a
second formatter.

```tsx
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material'
import type { PlayerResponse } from '../../api/types'
import { formatBRL } from '../../lib/money'

/**
 * Confirms tearing up a contract. Unlike "Renovar", which fires on click, this spends money and
 * cannot be undone — so the cost, the balance it leaves, and the one-season re-signing block are
 * all stated before the manager can commit.
 */
export function TerminateDialog({ player, balance, blockedReason, pending, onConfirm, onClose }: {
  player: PlayerResponse | null
  balance: number | undefined
  blockedReason: string | null
  pending: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  if (!player) return null
  const fee = player.terminationFee ?? 0
  const newBalance = balance === undefined ? undefined : balance - fee

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Rescindir contrato de {player.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          <Typography variant="body2">
            Contrato restante: {player.contractYears} ano(s)
          </Typography>
          <Typography variant="body2">
            Multa rescisória:{' '}
            <Typography component="span" data-testid="termination-fee" sx={{ fontWeight: 600 }}>
              {formatBRL(fee)}
            </Typography>
          </Typography>
          {newBalance !== undefined && (
            <Typography variant="body2">
              Saldo após a rescisão:{' '}
              <Typography component="span" data-testid="termination-new-balance" sx={{ fontWeight: 600 }}>
                {formatBRL(newBalance)}
              </Typography>
            </Typography>
          )}
          <Alert severity="warning">
            {player.name} vira agente livre e não poderá ser recontratado por este clube até a
            próxima temporada. O elenco perde moral.
          </Alert>
          {blockedReason && <Alert severity="error">{blockedReason}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} color="inherit">Cancelar</Button>
        <Button onClick={onConfirm} color="error" variant="contained"
          disabled={pending || blockedReason !== null}>
          Confirmar rescisão
        </Button>
      </DialogActions>
    </Dialog>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && npx vitest run src/features/squad/TerminateDialog.test.tsx
```

Expected: PASS, six tests.

- [ ] **Step 5: Commit the dialog**

```bash
git add frontend/src/features/squad/TerminateDialog.tsx frontend/src/features/squad/TerminateDialog.test.tsx
git commit -m "feat(squad): confirmation dialog for terminating a contract"
```

- [ ] **Step 6: Write the failing wiring test**

In `SquadPage.test.tsx`: add `getFinances: vi.fn()` and `terminateContract: vi.fn()` to the
`vi.mock('../../api/career', …)` factory, add both to the `import { … } from '../../api/career'`
line below it, declare `const mockGetFinances = vi.mocked(getFinances)` and
`const mockTerminateContract = vi.mocked(terminateContract)` beside the other mocks, add
`terminationFee: 5880000` to the shared `STATE` fixture, and add
`mockGetFinances.mockResolvedValue({ balance: 20000000, transactions: [] })` to the `beforeEach`
at line 158. The fixture squad has 18 players and two goalkeepers, so neither the squad floor nor
the last-keeper rule interferes. Then append inside `describe('SquadPage', …)`:

```tsx
  it('opens the termination dialog from the contract tab and terminates on confirm', async () => {
    mockTerminateContract.mockResolvedValue({
      playerId: 'p-m1', playerName: 'Meio Um', fee: 5880000, newBalance: 14120000,
    })
    renderWithProviders()

    fireEvent.click(await screen.findByRole('button', { name: /ver detalhes de meio um/i }))
    fireEvent.click(screen.getByRole('tab', { name: /contrato/i }))
    fireEvent.click(screen.getByRole('button', { name: /^rescindir$/i }))

    expect(screen.getByTestId('termination-fee')).toHaveTextContent('5.880.000')
    fireEvent.click(screen.getByRole('button', { name: /confirmar rescisão/i }))

    await waitFor(() =>
      expect(mockTerminateContract).toHaveBeenCalledWith('sg-1', 'p-m1', expect.any(String)))
  })

  it('blocks termination when the balance will not cover the fee', async () => {
    mockGetFinances.mockResolvedValue({ balance: 1000, transactions: [] })
    renderWithProviders()

    fireEvent.click(await screen.findByRole('button', { name: /ver detalhes de meio um/i }))
    fireEvent.click(screen.getByRole('tab', { name: /contrato/i }))
    fireEvent.click(screen.getByRole('button', { name: /^rescindir$/i }))

    expect(screen.getByRole('button', { name: /confirmar rescisão/i })).toBeDisabled()
    expect(screen.getByText(/saldo insuficiente/i)).toBeInTheDocument()
    expect(mockTerminateContract).not.toHaveBeenCalled()
  })
```

The details dialog's tab labels come from `PlayerDetails.tsx` — confirm the "Contrato" tab's
accessible name there before relying on the regex above, and use the file's own
`await screen.findByText(...)` settling idiom if the dialog needs a tick to mount.

- [ ] **Step 7: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx
```

Expected: FAIL — no "Rescindir" button.

- [ ] **Step 8: Wire it into `SquadPage`**

Add the finances query beside the existing `squad` query (same key `FinancesPage` uses, so the cache is shared):

```tsx
  const finances = useQuery({
    queryKey: ['finances', saveGameId, clubId],
    queryFn: () => getFinances(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })
```

Add the state and the mutation beside `renewMut`:

```tsx
  const [terminatingId, setTerminatingId] = useState<string | null>(null)

  const terminateMut = useMutation({
    mutationFn: (playerId: string) => terminateContract(saveGameId, playerId, token ?? ''),
    onSuccess: () => {
      setTerminatingId(null)
      setDetailsPlayerId(null)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['lineup', saveGameId, clubId] })
    },
  })
```

Add the reason helper and the button beside `renewButton`:

```tsx
  /** Mirrors SquadRules.MIN_SQUAD_SIZE on the backend; the server is still the authority. */
  const MIN_SQUAD_SIZE = 14

  /** Why this player cannot be released right now, or null when he can be. Explains only —
   * the backend enforces all three rules regardless. */
  function terminationBlockedReason(player: PlayerResponse): string | null {
    const senior = players
    if (senior.length <= MIN_SQUAD_SIZE) {
      return `O elenco ficaria abaixo do mínimo de ${MIN_SQUAD_SIZE} jogadores.`
    }
    if (player.position === 'GK' && senior.filter(p => p.position === 'GK').length <= 1) {
      return 'Este é o seu último goleiro.'
    }
    const balance = finances.data?.balance
    if (balance !== undefined && balance < (player.terminationFee ?? 0)) {
      return 'Saldo insuficiente para a multa.'
    }
    return null
  }

  function terminateButton(player: PlayerResponse) {
    return (
      <Button size="small" color="error" disabled={terminateMut.isPending}
        onClick={() => setTerminatingId(player.id)}>
        Rescindir
      </Button>
    )
  }
```

`players` is the squad payload, which the backend already scopes to the professional squad — the same list the server counts.

Pass both buttons into the details dialog by replacing the `contractAction` prop at line ~964:

```tsx
        contractAction={detailsPlayer ? (
          <Stack direction="row" spacing={1}>
            {renewButton(detailsPlayer)}
            {terminateButton(detailsPlayer)}
          </Stack>
        ) : undefined}
```

Render the dialog beside the other dialogs near the end of the component:

```tsx
      <TerminateDialog
        player={players.find(p => p.id === terminatingId) ?? null}
        balance={finances.data?.balance}
        blockedReason={(() => {
          const p = players.find(x => x.id === terminatingId)
          return p ? terminationBlockedReason(p) : null
        })()}
        pending={terminateMut.isPending}
        onConfirm={() => { if (terminatingId) terminateMut.mutate(terminatingId) }}
        onClose={() => setTerminatingId(null)}
      />
```

Add a failure snackbar next to the existing list/unlist one:

```tsx
      <Snackbar open={terminateMut.isError} autoHideDuration={6000} onClose={() => terminateMut.reset()}>
        <Alert severity="error" onClose={() => terminateMut.reset()}>
          {errorMessage(terminateMut.error, 'Erro ao rescindir o contrato.')}
        </Alert>
      </Snackbar>
```

Match the existing snackbar's exact `anchorOrigin` / `Alert` props in this file rather than the abbreviated form above. Add `getFinances` and `terminateContract` to the `../../api/career` import and `TerminateDialog` to the local imports.

- [ ] **Step 9: Run the full frontend check**

```bash
cd frontend && npx vitest run && npx tsc -b
```

Expected: the whole suite PASSES and `tsc -b` is clean. Fix any fixture that now fails to type-check rather than loosening the types.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(squad): terminate a contract from the player's contract tab"
```

---

### Task 9: Full-suite verification

Nothing new is written here. The point is to prove the whole system still holds after the `SquadDeparture` extraction and the `PlayerResponse` change, both of which reach beyond their own tests.

**Files:** none.

- [ ] **Step 1: Run the whole backend suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test > /tmp/backend-full.txt 2>&1; echo "EXIT=$?"
```

Expected: `EXIT=0`. On failure, read `/tmp/backend-full.txt` — do not infer from the exit code alone. `TransferServiceSellingIT` is a known ~26% flake: re-run it alone before treating it as a regression.

- [ ] **Step 2: Run the whole frontend suite and the real type-check**

```bash
cd frontend && npx vitest run && npm run build
```

Expected: all tests pass and the production build succeeds. `npm run build` is the gate Vercel actually runs, so it is the only definitive frontend check.

- [ ] **Step 3: Report**

State the actual pass/fail counts from both suites. Do not claim completion without them.
