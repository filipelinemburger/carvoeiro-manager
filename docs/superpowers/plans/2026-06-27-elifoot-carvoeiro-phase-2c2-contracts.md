# Phase 2C-2 — Contracts & Free Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every player a contract (length in years + the existing wage) and introduce free agents, so the manager renews their own players, signs free agents for no fee, and agrees personal terms when buying — while the AI auto-renews its own squads.

**Architecture:** Contract data lives on the existing `Player` entity (`contract_years` int; `club_id` becomes nullable — a free agent is a player with `club_id = NULL`). Wage demand is a pure function (`ContractValuation`, calibrated to the 2B `FinanceCalculator.weeklyWage`). Renewals/free-agent signing live in a new `contract` package; buying extends the existing 2C-1 transfer offer/executor. Rollover (2E) ticks contracts: AI auto-renews, managed players lapse into the free-agent pool, the pool ages/retires and is replenished to a floor. All randomness uses save-derived seeds; the match engine is untouched.

**Tech Stack:** Java 25 / Spring Boot 4.0.7 / Gradle (wrapper at `backend/gradlew`) / Flyway / JPA / Testcontainers Postgres / JUnit5 + AssertJ. Frontend React 19 + TypeScript (strict) + Vite + TanStack Query + React Router + Vitest/RTL.

**Spec:** `docs/superpowers/specs/2026-06-27-elifoot-carvoeiro-phase-2c2-contracts-design.md`

## Global Constraints

- **Backend gradle commands MUST be prefixed** with `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"` (default `java` is 8). Run from the `backend/` directory: `cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "<FQCN>"`.
- **Frontend tests** run from `frontend/`: `npm test -- --run <path>`. Build check: `npm run build`.
- **Every commit message MUST end with** `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Commit directly to `main` (authorized for this project).
- **Money** is `BigDecimal` scaled to 2 (use `setScale(2, RoundingMode.HALF_UP)`); never `double` for money.
- **IDs** are `UUID` assigned by the caller via `Player.create(...)` / entity factories. FKs are plain UUID columns (no JPA associations).
- **Determinism:** the match engine must remain untouched. `ContractValuation` is pure (no RNG). All new randomness uses save-derived seeds: creation contract stagger `new Random(saveGameId.getMostSignificantBits())`; creation free-agent pool `new Random(saveGameId.getMostSignificantBits() ^ 0xFA11AC0DL)`; rollover auto-renew/replenish reuse the existing rollover `rng` (`saveGameId.msb ^ finishingYear`).
- **Constants (locked):** `DEMAND_PREMIUM = 1.15`, `DEFAULT_CONTRACT_YEARS = 3`, `MIN_CONTRACT_YEARS = 1`, `MAX_CONTRACT_YEARS = 5`, AI auto-renew / newgen term `2 + rng.nextInt(3)` (2–4), `FREE_AGENT_FLOOR = 6`, `INITIAL_FREE_AGENTS = 10`, `FREE_AGENT_SEED = 0xFA11AC0DL`, reused `MIN_SQUAD_SIZE = 14` / `MAX_SQUAD_SIZE = 30`.
- **Error JSON** is `{error, message, status}` via `ApiException(code, message, HttpStatus)`. `HttpStatus.UNPROCESSABLE_CONTENT` = 422. New codes: `PLAYER_REJECTS_TERMS` (422), `INVALID_CONTRACT` (422). Reuse `TRANSFER_WINDOW_CLOSED`, `SQUAD_FULL`, `SAVE_GAME_NOT_FOUND` (404).
- **A buy that falls through on terms is NOT an error** — it returns a `200` with a `REJECTED` offer; the FE shows the demand from the market list.

---

## File Structure

**Backend — new files**
- `backend/src/main/resources/db/migration/V7__contracts.sql` — additive migration.
- `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractValuation.java` — pure wage-demand + acceptance + length validation.
- `backend/src/main/java/br/com/elifootcarvoeiro/contract/FreeAgentGenerator.java` — seeded free-agent generation.
- `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java` — renew, list free agents, sign free agent.
- `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java` — REST endpoints.
- `backend/src/main/java/br/com/elifootcarvoeiro/contract/RenewContractRequest.java`, `SignFreeAgentRequest.java`, `FreeAgentResponse.java`, `PlayerContractResponse.java` — DTO records.

**Backend — modified files**
- `player/Player.java` (contract field + `isFreeAgent`), `player/PlayerRepository.java` (free-agent finder).
- `transfer/TransferOffer.java`, `transfer/MakeOfferRequest.java`, `transfer/MarketPlayerResponse.java`, `transfer/TransferOfferResponse.java`, `transfer/TransferService.java`, `transfer/TransferExecutor.java`, `transfer/TransferController.java` (buy-with-terms).
- `career/SaveGameService.java` (creation seeding).
- `season/SeasonRolloverService.java`, `season/RolloverResponse.java` (contract rollover + expired list).
- `match/PlayerResponse.java`, `match/LineupService.java` (squad contract info).

**Frontend — modified/new files**
- `api/types.ts`, `api/career.ts` (types + client funcs).
- `features/contract/FreeAgentsPage.tsx` (new) + `.test.tsx`, `router.tsx`, `features/career/DashboardPage.tsx` (link).
- `features/squad/SquadPage.tsx` (+ test), `features/transfer/TransferMarketPage.tsx` (+ test), `features/season/SeasonSummaryPage.tsx` (+ test).

---

## Task 1: V7 migration + Player contract fields + free-agent finder

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__contracts.sql`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/PlayerRepository.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/player/PlayerContractEntityIT.java`

**Interfaces:**
- Produces: `Player.getContractYears(): int`, `Player.setContractYears(int)`, `Player.isFreeAgent(): boolean` (`clubId == null`); `Player.setClubId(UUID)` now accepts `null`. `PlayerRepository.findBySaveGameIdAndClubIdIsNull(UUID): List<Player>`.

- [ ] **Step 1: Write the failing test**

Create `PlayerContractEntityIT.java`. It follows the project's non-transactional entity-IT pattern (`@SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)`), creates a career to get a valid `save_game_id`, then round-trips a free agent and a contract length.

```java
package br.com.elifootcarvoeiro.player;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class PlayerContractEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private UUID newSave() {
        User user = User.create("contract_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    @Test
    void contractYearsRoundTrips() {
        UUID saveId = newSave();
        Player p = playerRepository.findBySaveGameId(saveId).get(0);
        p.setContractYears(4);
        playerRepository.saveAndFlush(p);

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getContractYears()).isEqualTo(4);
        assertThat(reloaded.isFreeAgent()).isFalse();
    }

    @Test
    void aFreeAgentHasNoClubAndPersists() {
        UUID saveId = newSave();
        Player fa = Player.create(saveId, null, "Livre Atleta", Position.MID, 28, "Brasil",
                60, 55, 65, 58, 10, 60, 60);
        playerRepository.saveAndFlush(fa);

        Player reloaded = playerRepository.findById(fa.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isNull();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(saveId))
                .extracting(Player::getId).contains(fa.getId());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.player.PlayerContractEntityIT"
```
Expected: FAIL — `setContractYears`/`isFreeAgent`/`findBySaveGameIdAndClubIdIsNull` do not exist (compile error), and the migration column is missing.

- [ ] **Step 3: Write the migration**

Create `V7__contracts.sql`:

```sql
-- Free agents have no club; every player has a contract length (years remaining).
ALTER TABLE players ALTER COLUMN club_id DROP NOT NULL;
ALTER TABLE players ADD COLUMN contract_years INT NOT NULL DEFAULT 0;

-- Personal terms attached to an OUTGOING buy offer (null for AI/incoming bids).
ALTER TABLE transfer_offer ADD COLUMN wage NUMERIC(14,2);
ALTER TABLE transfer_offer ADD COLUMN contract_years INT;
```

- [ ] **Step 4: Add the entity field + free-agent helper**

In `Player.java`, after the `wage` column (around line 76) add:

```java
    @Column(name = "contract_years", nullable = false)
    private int contractYears;
```

Add accessors near `getWage()`/`setWage()`:

```java
    public int getContractYears() { return contractYears; }
    public void setContractYears(int contractYears) { this.contractYears = contractYears; }

    public boolean isFreeAgent() { return clubId == null; }
```

`setClubId(UUID)` already exists and accepts `null` (the column is now nullable) — no change needed.

- [ ] **Step 5: Add the repository finder**

In `PlayerRepository.java`:

```java
    List<Player> findBySaveGameIdAndClubIdIsNull(UUID saveGameId);
```

- [ ] **Step 6: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.player.PlayerContractEntityIT"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__contracts.sql \
  backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java \
  backend/src/main/java/br/com/elifootcarvoeiro/player/PlayerRepository.java \
  backend/src/test/java/br/com/elifootcarvoeiro/player/PlayerContractEntityIT.java
git commit -m "$(cat <<'EOF'
feat(contract): V7 migration + player contract_years + nullable club_id

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ContractValuation (pure wage demand)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractValuation.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractValuationTest.java`

**Interfaces:**
- Consumes: `FinanceCalculator.weeklyWage(int overall, int age): BigDecimal` (existing `@Component`).
- Produces: `ContractValuation` (`@Component`): `wageDemand(int overall, int age): BigDecimal` (= `weeklyWage × 1.15`, scale 2), `acceptsWage(int overall, int age, BigDecimal offered): boolean`, `defaultContractYears(): int` (3), `isValidLength(int years): boolean` (1..5).

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ContractValuationTest {

    private final FinanceCalculator finance = new FinanceCalculator();
    private final ContractValuation valuation = new ContractValuation(finance);

    @Test
    void wageDemandIsFifteenPercentAboveBaselineWage() {
        // weeklyWage(70,25) = 70*70*20 * 1.00 = 98000.00 ; demand = *1.15 = 112700.00
        assertThat(valuation.wageDemand(70, 25)).isEqualByComparingTo("112700.00");
    }

    @Test
    void acceptsWageOnlyAtOrAboveTheDemand() {
        BigDecimal demand = valuation.wageDemand(70, 25); // 112700.00
        assertThat(valuation.acceptsWage(70, 25, demand)).isTrue();
        assertThat(valuation.acceptsWage(70, 25, demand.add(new BigDecimal("0.01")))).isTrue();
        assertThat(valuation.acceptsWage(70, 25, demand.subtract(new BigDecimal("0.01")))).isFalse();
        assertThat(valuation.acceptsWage(70, 25, null)).isFalse();
    }

    @Test
    void higherOverallDemandsMore() {
        assertThat(valuation.wageDemand(80, 25)).isGreaterThan(valuation.wageDemand(70, 25));
    }

    @Test
    void lengthValidationAndDefault() {
        assertThat(valuation.defaultContractYears()).isEqualTo(3);
        assertThat(valuation.isValidLength(1)).isTrue();
        assertThat(valuation.isValidLength(5)).isTrue();
        assertThat(valuation.isValidLength(0)).isFalse();
        assertThat(valuation.isValidLength(6)).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractValuationTest"
```
Expected: FAIL — `ContractValuation` does not exist.

- [ ] **Step 3: Implement ContractValuation**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, deterministic contract economics (no RNG). A player's wage demand is
 * DEMAND_PREMIUM above their 2B baseline wage, so it is in the same currency as
 * existing wages and rises as the 2E age curve grows a young player's overall.
 */
@Component
public class ContractValuation {

    static final BigDecimal DEMAND_PREMIUM = new BigDecimal("1.15");
    static final int DEFAULT_CONTRACT_YEARS = 3;
    static final int MIN_CONTRACT_YEARS = 1;
    static final int MAX_CONTRACT_YEARS = 5;

    private final FinanceCalculator financeCalculator;

    public ContractValuation(FinanceCalculator financeCalculator) {
        this.financeCalculator = financeCalculator;
    }

    public BigDecimal wageDemand(int overall, int age) {
        return financeCalculator.weeklyWage(overall, age)
                .multiply(DEMAND_PREMIUM).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean acceptsWage(int overall, int age, BigDecimal offered) {
        return offered != null && offered.compareTo(wageDemand(overall, age)) >= 0;
    }

    public int defaultContractYears() {
        return DEFAULT_CONTRACT_YEARS;
    }

    public boolean isValidLength(int years) {
        return years >= MIN_CONTRACT_YEARS && years <= MAX_CONTRACT_YEARS;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractValuationTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractValuation.java \
  backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractValuationTest.java
git commit -m "$(cat <<'EOF'
feat(contract): ContractValuation pure wage demand (weeklyWage x1.15)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Seed staggered contract lengths at career creation

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/career/CareerContractSeedingIT.java`

**Interfaces:**
- Consumes: `Player.setContractYears(int)` (Task 1).
- Produces: after `createCareer`, every seeded player has `contractYears ∈ [1,4]`, assigned deterministically by `new Random(saveGameId.getMostSignificantBits())` over id-sorted players.

Rationale: contracts must not all expire together, and (critically) they must not be left at the migration default of 0 — otherwise the first rollover (Task 6) would lapse the entire managed squad at once.

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class CareerContractSeedingIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    @Test
    void everyPlayerGetsAStaggeredContractLength() {
        User user = User.create("seed_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        var players = playerRepository.findBySaveGameId(save.id());
        assertThat(players).isNotEmpty();
        assertThat(players).allMatch(p -> p.getContractYears() >= 1 && p.getContractYears() <= 4);
        // Staggered, not all identical.
        assertThat(players.stream().map(Player::getContractYears).distinct().count()).isGreaterThan(1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.career.CareerContractSeedingIT"
```
Expected: FAIL — players have `contractYears == 0` (migration default).

- [ ] **Step 3: Seed contract lengths in createCareer**

In `SaveGameService.java`, add imports:

```java
import java.util.Comparator;
import java.util.Random;
```

In `createCareer`, immediately after the club/player creation loop closes (after the `for (SeedClub seedClub : seedClubs)` block, i.e. just before `// 3. Create competition`), insert:

```java
        // Stagger contract lengths (1..4) deterministically so deals don't all expire together.
        Random contractRng = new Random(saveGame.getId().getMostSignificantBits());
        List<Player> allPlayers = playerRepository.findBySaveGameId(saveGame.getId()).stream()
                .sorted(Comparator.comparing(Player::getId)).toList();
        for (Player player : allPlayers) {
            player.setContractYears(1 + contractRng.nextInt(4));
        }
        playerRepository.saveAll(allPlayers);
```

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.career.CareerContractSeedingIT"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java \
  backend/src/test/java/br/com/elifootcarvoeiro/career/CareerContractSeedingIT.java
git commit -m "$(cat <<'EOF'
feat(contract): seed staggered contract lengths at career creation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Renewals (ContractService + controller + DTOs)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/RenewContractRequest.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/PlayerContractResponse.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractServiceRenewIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned(UUID, UUID): SaveGame`; `PlayerRepository.findById`; `ContractValuation` (Task 2); `ApiException(code, msg, HttpStatus)`.
- Produces: `ContractService.renewContract(UUID saveGameId, UUID userId, UUID playerId, BigDecimal wage, int contractYears): PlayerContractResponse`. `RenewContractRequest(BigDecimal wage, int contractYears)`. `PlayerContractResponse(UUID playerId, String name, BigDecimal wage, int contractYears)`. Endpoint `POST /api/v1/save-games/{saveGameId}/players/{playerId}/renew`.

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceRenewIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("renew_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player aManagedPlayer(Career c) {
        return playerRepository.findByClubId(c.clubId()).get(0);
    }

    @Test
    void renewingAtTheDemandUpdatesWageAndLength() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());

        PlayerContractResponse r = contractService.renewContract(c.id(), c.userId(), p.getId(), demand, 4);

        assertThat(r.contractYears()).isEqualTo(4);
        assertThat(r.wage()).isEqualByComparingTo(demand);
        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getContractYears()).isEqualTo(4);
        assertThat(reloaded.getWage()).isEqualByComparingTo(demand);
    }

    @Test
    void aLowballWageIsRejected() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        BigDecimal lowball = demand.subtract(new BigDecimal("1"));

        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), lowball, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("PLAYER_REJECTS_TERMS");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getContractYears())
                .isNotEqualTo(3); // unchanged
    }

    @Test
    void anInvalidLengthIsRejected() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        BigDecimal demand = contractValuation.wageDemand(p.getOverall(), p.getAge());
        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), p.getId(), demand, 6))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_CONTRACT"));
    }

    @Test
    void cannotRenewAPlayerFromAnotherClub() {
        Career c = createCareer();
        UUID otherClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        Player aiPlayer = playerRepository.findByClubId(otherClub).get(0);
        BigDecimal demand = contractValuation.wageDemand(aiPlayer.getOverall(), aiPlayer.getAge());
        assertThatThrownBy(() -> contractService.renewContract(c.id(), c.userId(), aiPlayer.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractServiceRenewIT"
```
Expected: FAIL — `ContractService` / DTOs do not exist.

- [ ] **Step 3: Create the DTO records**

`RenewContractRequest.java`:

```java
package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;

public record RenewContractRequest(BigDecimal wage, int contractYears) {}
```

`PlayerContractResponse.java`:

```java
package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record PlayerContractResponse(UUID playerId, String name, BigDecimal wage, int contractYears) {}
```

- [ ] **Step 4: Create ContractService with renewContract**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contract operations the manager controls: renewing an owned player and signing
 * free agents. Acceptance is a pure wage threshold (ContractValuation); no RNG.
 */
@Service
public class ContractService {

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ContractValuation contractValuation;

    public ContractService(SaveGameService saveGameService,
                           PlayerRepository playerRepository,
                           ContractValuation contractValuation) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.contractValuation = contractValuation;
    }

    @Transactional
    public PlayerContractResponse renewContract(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
        applyTerms(player, wage, contractYears);
        playerRepository.save(player);
        return toResponse(player);
    }

    // ---- shared helpers (signFreeAgent in Task 8 reuses applyTerms + validation) ----

    void applyTerms(Player player, BigDecimal wage, int contractYears) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException("INVALID_CONTRACT",
                    "Contract length must be 1-5 years and wage must be positive",
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        if (!contractValuation.acceptsWage(player.getOverall(), player.getAge(), wage)) {
            BigDecimal demand = contractValuation.wageDemand(player.getOverall(), player.getAge());
            throw new ApiException("PLAYER_REJECTS_TERMS",
                    "Player rejects these terms; demands at least " + demand,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        player.setWage(wage);
        player.setContractYears(contractYears);
    }

    Player requireManagedPlayer(UUID saveGameId, UUID managedClubId, UUID playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Player not found at your club", HttpStatus.NOT_FOUND));
    }

    PlayerContractResponse toResponse(Player p) {
        return new PlayerContractResponse(p.getId(), p.getName(), p.getWage(), p.getContractYears());
    }
}
```

- [ ] **Step 5: Create ContractController**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping("/players/{playerId}/renew")
    public ResponseEntity<PlayerContractResponse> renew(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody RenewContractRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.renewContract(
                saveGameId, userId, playerId, request.wage(), request.contractYears()));
    }
}
```

- [ ] **Step 6: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractServiceRenewIT"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/RenewContractRequest.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/PlayerContractResponse.java \
  backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractServiceRenewIT.java
git commit -m "$(cat <<'EOF'
feat(contract): renew owned-player contracts (wage-threshold acceptance)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: FreeAgentGenerator (seeded)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/FreeAgentGenerator.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/contract/FreeAgentGeneratorTest.java`

**Interfaces:**
- Consumes: `SeedLoader` (provides `loadNewgenNames()` → `NewgenNames.firstNames()/lastNames()`), `OverallCalculator.compute(...)` (both as in `NewgenGenerator`).
- Produces: `FreeAgentGenerator` (`@Component`): `generate(UUID saveGameId, Random rng): Player` — `clubId == null`, `contractYears == 0`, `wage == 0`, random position/age (22..34)/attributes, `overall` from `OverallCalculator`.

- [ ] **Step 1: Write the failing test** (mirrors `NewgenGeneratorTest`'s plain-constructor style)

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.player.OverallCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FreeAgentGeneratorTest {

    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final FreeAgentGenerator generator = new FreeAgentGenerator(new SeedLoader(), overallCalculator);

    @Test
    void generatesAClublessPlayerWithNoContract() {
        UUID saveGameId = UUID.randomUUID();
        Player p = generator.generate(saveGameId, new Random(7));

        assertThat(p.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(p.getClubId()).isNull();
        assertThat(p.isFreeAgent()).isTrue();
        assertThat(p.getContractYears()).isZero();
        assertThat(p.getWage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.getAge()).isBetween(22, 34);
        assertThat(p.getName()).contains(" ");
        assertThat(p.getOverall()).isEqualTo(overallCalculator.compute(p.getPosition(),
                p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina()));
    }

    @Test
    void isDeterministicForTheSameSeed() {
        UUID id = UUID.randomUUID();
        Player a = generator.generate(id, new Random(42));
        Player b = generator.generate(id, new Random(42));
        assertThat(a.getName()).isEqualTo(b.getName());
        assertThat(a.getPosition()).isEqualTo(b.getPosition());
        assertThat(a.getAge()).isEqualTo(b.getAge());
        assertThat(a.getOverall()).isEqualTo(b.getOverall());
    }

    @Test
    void attributesStayInRange() {
        Player p = generator.generate(UUID.randomUUID(), new Random(1));
        assertThat(p.getPace()).isBetween(1, 99);
        assertThat(p.getFinishing()).isBetween(1, 99);
        assertThat(p.getGoalkeeping()).isBetween(1, 99);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.FreeAgentGeneratorTest"
```
Expected: FAIL — `FreeAgentGenerator` does not exist.

- [ ] **Step 3: Implement FreeAgentGenerator**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.player.OverallCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.seed.NewgenNames;
import br.com.elifootcarvoeiro.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a clubless free agent (no contract, no wage). Deterministic for a
 * given RNG: draws first/last name, age, position, then attributes in a fixed
 * order. Quality bands are higher than newgens so the pool holds usable pros.
 */
@Component
public class FreeAgentGenerator {

    private final OverallCalculator overallCalculator;
    private final List<String> firstNames;
    private final List<String> lastNames;

    public FreeAgentGenerator(SeedLoader seedLoader, OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public Player generate(UUID saveGameId, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 22 + rng.nextInt(13); // 22..34
        Position position = Position.values()[rng.nextInt(Position.values().length)];

        int pace, finishing, passing, tackling, goalkeeping, stamina;
        switch (position) {
            case GK -> {
                pace = band(35, 18, rng); finishing = band(20, 12, rng); passing = band(40, 18, rng);
                tackling = band(25, 12, rng); goalkeeping = band(60, 18, rng); stamina = band(45, 18, rng);
            }
            case DEF -> {
                pace = band(48, 18, rng); finishing = band(28, 15, rng); passing = band(45, 18, rng);
                tackling = band(58, 18, rng); goalkeeping = band(1, 8, rng); stamina = band(50, 18, rng);
            }
            case MID -> {
                pace = band(48, 18, rng); finishing = band(40, 18, rng); passing = band(58, 18, rng);
                tackling = band(48, 18, rng); goalkeeping = band(1, 8, rng); stamina = band(52, 18, rng);
            }
            case ATT -> {
                pace = band(55, 18, rng); finishing = band(58, 18, rng); passing = band(45, 18, rng);
                tackling = band(28, 15, rng); goalkeeping = band(1, 8, rng); stamina = band(45, 18, rng);
            }
            default -> throw new IllegalStateException("Unknown position: " + position);
        }

        int overall = overallCalculator.compute(position, pace, finishing, passing,
                tackling, goalkeeping, stamina);
        // clubId null => free agent; wage stays 0 (not employed); contractYears stays 0.
        return Player.create(saveGameId, null, name, position, age, "Brasil",
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    private int band(int base, int span, Random rng) {
        return Math.min(99, base + rng.nextInt(span));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.FreeAgentGeneratorTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/contract/FreeAgentGenerator.java \
  backend/src/test/java/br/com/elifootcarvoeiro/contract/FreeAgentGeneratorTest.java
git commit -m "$(cat <<'EOF'
feat(contract): seeded FreeAgentGenerator (clubless, no contract)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Rollover contract handling (tick / auto-renew / lapse / FA aging / replenish)

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/season/RolloverResponse.java`
- Modify (update existing test): `backend/src/test/java/br/com/elifootcarvoeiro/season/SeasonRolloverServiceIT.java`
- Test (new): `backend/src/test/java/br/com/elifootcarvoeiro/season/RolloverContractsIT.java`

**Interfaces:**
- Consumes: `FreeAgentGenerator.generate(UUID, Random)` (Task 5); `Player.getContractYears/setContractYears/isFreeAgent` (Task 1); `PlayerRepository.findBySaveGameIdAndClubIdIsNull` (Task 1).
- Produces: `RolloverResponse` gains `List<SquadChangeResponse> expiredContracts`. Rollover semantics: every carried player's `contractYears` decremented; at 0 — AI auto-renews (`2 + rng.nextInt(3)`), managed lapses (`clubId = null`); free agents age and can retire (deleted, not replaced); pool replenished to `FREE_AGENT_FLOOR`.

The contract logic must live INSIDE the existing aging loop (which iterates all players including free agents). The current loop does `player.getClubId().equals(managedClubId)`, which NPEs on free agents — that is fixed here.

- [ ] **Step 1: Write the failing test** (new contract behaviors)

```java
package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class RolloverContractsIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("roll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        return c;
    }

    @Test
    void aManagedPlayerWhoseContractExpiresBecomesAFreeAgent() {
        Career c = playToSeasonEnd();
        // Force one managed player's contract to expire this rollover; keep the rest safe.
        List<Player> squad = playerRepository.findByClubId(c.clubId());
        squad.forEach(p -> p.setContractYears(5));
        Player expiring = squad.get(0);
        expiring.setContractYears(1); // -> 0 after tick -> lapses
        playerRepository.saveAll(squad);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(reloaded.getContractYears()).isZero();
        assertThat(r.expiredContracts()).extracting(SquadChangeResponse::playerName)
                .contains(expiring.getName());
    }

    @Test
    void anAiPlayerWhoseContractExpiresIsAutoRenewedAndStays() {
        Career c = playToSeasonEnd();
        UUID aiClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        List<Player> aiSquad = playerRepository.findByClubId(aiClub);
        aiSquad.forEach(p -> p.setContractYears(5));
        Player expiring = aiSquad.get(0);
        expiring.setContractYears(1);
        playerRepository.saveAll(aiSquad);

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(aiClub); // stayed
        assertThat(reloaded.getContractYears()).isBetween(2, 4); // auto-renewed
    }

    @Test
    void theFreeAgentPoolIsReplenishedToTheFloor() {
        Career c = playToSeasonEnd();
        playerRepository.findBySaveGameId(c.id()).forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(playerRepository.findBySaveGameId(c.id()));

        rolloverService.rollover(c.id(), c.userId());

        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size())
                .isGreaterThanOrEqualTo(6);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.season.RolloverContractsIT"
```
Expected: FAIL — `RolloverResponse.expiredContracts()` does not exist; rollover does no contract logic.

- [ ] **Step 3: Extend RolloverResponse**

```java
package br.com.elifootcarvoeiro.season;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> newcomers,
        List<SquadChangeResponse> expiredContracts
) {}
```

- [ ] **Step 4: Wire FreeAgentGenerator into SeasonRolloverService + add constants**

In `SeasonRolloverService.java` add imports and the dependency:

```java
import br.com.elifootcarvoeiro.contract.FreeAgentGenerator;
```

Add the field + constructor param (append to the existing constructor parameter list and assignments):

```java
    private static final int FREE_AGENT_FLOOR = 6;

    private final FreeAgentGenerator freeAgentGenerator;
```
Add `FreeAgentGenerator freeAgentGenerator` as the last constructor parameter and `this.freeAgentGenerator = freeAgentGenerator;` in the body.

- [ ] **Step 5: Replace the aging loop with contract-aware logic**

In `rollover(...)`, replace the existing block "// 3. Age every player ..." (the `for (Player player : players)` loop plus the `retirements`/`newcomers`/`toDelete`/`toSave` declarations) with the version below. Add a new `expiredContracts` list and make the managed/AI checks null-safe for free agents.

```java
        // 3. Age every player (id order, one seeded RNG); tick contracts; partition.
        List<Player> players = new ArrayList<>(playerRepository.findBySaveGameId(saveGameId));
        players.sort(Comparator.comparing(Player::getId));

        List<SquadChangeResponse> retirements = new ArrayList<>();
        List<SquadChangeResponse> newcomers = new ArrayList<>();
        List<SquadChangeResponse> expiredContracts = new ArrayList<>();
        List<Player> toDelete = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();

        for (Player player : players) {
            player.setAge(player.getAge() + 1);
            playerAgingService.applyAgeCurve(player, rng);
            boolean retires = rng.nextDouble() < playerAgingService.retirementProbability(player.getAge());
            boolean freeAgent = player.isFreeAgent();
            boolean managed = !freeAgent && player.getClubId().equals(managedClubId);

            if (retires) {
                if (freeAgent) {
                    // A free agent who retires simply leaves the pool (no replacement).
                    toDelete.add(player);
                    continue;
                }
                if (managed) {
                    retirements.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
                Player newgen = newgenGenerator.generate(
                        saveGameId, player.getClubId(), player.getPosition(), rng);
                newgen.setContractYears(2 + rng.nextInt(3));
                toSave.add(newgen);
                if (managed) {
                    newcomers.add(new SquadChangeResponse(
                            newgen.getName(), newgen.getPosition().name(), newgen.getAge()));
                }
                toDelete.add(player);
                continue;
            }

            // Carried over: reset per-season state, then tick the contract.
            player.resetSeasonState();
            if (!freeAgent) {
                player.setContractYears(player.getContractYears() - 1);
                if (player.getContractYears() <= 0) {
                    if (managed) {
                        expiredContracts.add(new SquadChangeResponse(
                                player.getName(), player.getPosition().name(), player.getAge()));
                        player.setClubId(null);          // lapses into the free-agent pool
                        player.setContractYears(0);
                    } else {
                        player.setContractYears(2 + rng.nextInt(3)); // AI auto-renews
                    }
                }
            }
            toSave.add(player);
        }
        playerRepository.deleteAll(toDelete);
        playerRepository.flush();
        playerRepository.saveAll(toSave);

        // Replenish the free-agent pool to the floor so the market never dies.
        int poolSize = playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).size();
        for (int i = poolSize; i < FREE_AGENT_FLOOR; i++) {
            playerRepository.save(freeAgentGenerator.generate(saveGameId, rng));
        }
```

- [ ] **Step 6: Update the final return to include expiredContracts**

Change the `return new RolloverResponse(...)` at the end of `rollover(...)` to:

```java
        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers), List.copyOf(expiredContracts));
```

- [ ] **Step 7: Update the existing SeasonRolloverServiceIT**

The existing IT groups players by `clubId` (NPEs on free agents now in every save) and asserts the managed squad size is unchanged (no longer true if a contract lapses). Make two changes:

(a) Before each `rollover(...)` call in the existing tests, pin contracts high so the legacy retirement/newgen invariants hold:

```java
        // Contracts must not interfere with the retirement/newgen invariants under test.
        playerRepository.findBySaveGameId(c.id()).forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(playerRepository.findBySaveGameId(c.id()));
```

(b) Make the per-club groupings ignore free agents (null club) — replace both `squadSizeBefore`/`squadSizeAfter` groupings with a null-filtered version:

```java
        Map<UUID, Long> squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null)
                .collect(Collectors.groupingBy(Player::getClubId, Collectors.counting()));
```
and the same `.filter(p -> p.getClubId() != null)` for `squadSizeAfter`. The replenish-to-floor adds free agents, which are correctly excluded by this filter, so `squadSizeAfter` (per real club) still equals `squadSizeBefore`.

Also, if the existing test does `RolloverResponse response = rolloverService.rollover(...)` and constructs/asserts the response, no change is needed — the extra `expiredContracts` field is additive. If any test constructs a `RolloverResponse(...)` literal, add `List.of()` as the new last argument.

- [ ] **Step 8: Run both rollover ITs to verify they pass**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.season.RolloverContractsIT" --tests "br.com.elifootcarvoeiro.season.SeasonRolloverServiceIT"
```
Expected: PASS. If `TransferAdvanceRolloverIT` also performs a rollover and now fails on the squad/grouping change, apply the same contract-pinning + null-filter fixes there.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/season/SeasonRolloverService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/season/RolloverResponse.java \
  backend/src/test/java/br/com/elifootcarvoeiro/season/SeasonRolloverServiceIT.java \
  backend/src/test/java/br/com/elifootcarvoeiro/season/RolloverContractsIT.java
git commit -m "$(cat <<'EOF'
feat(contract): rollover ticks contracts (AI auto-renew, managed lapse, FA pool)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Career-creation free-agent pool + market guard + list endpoint

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java` (market null-guard)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java` (getFreeAgents)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java` (GET endpoint)
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/FreeAgentResponse.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/contract/FreeAgentListIT.java`

**Interfaces:**
- Consumes: `FreeAgentGenerator.generate` (Task 5); `ContractValuation.wageDemand` (Task 2); `PlayerRepository.findBySaveGameIdAndClubIdIsNull` (Task 1).
- Produces: 10 free agents seeded at creation; `ContractService.getFreeAgents(UUID, UUID): List<FreeAgentResponse>`; `FreeAgentResponse(UUID playerId, String name, String position, int age, int overall, BigDecimal wageDemand)`; endpoint `GET /api/v1/save-games/{saveGameId}/free-agents`. `getMarket` excludes free agents.

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.transfer.TransferService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FreeAgentListIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired ContractService contractService;
    @Autowired TransferService transferService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("fa_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void careerCreationSeedsAFreeAgentPool() {
        Career c = createCareer();
        var pool = contractService.getFreeAgents(c.id(), c.userId());
        assertThat(pool).hasSize(10);
        assertThat(pool).allMatch(fa -> fa.wageDemand().signum() > 0);
    }

    @Test
    void freeAgentsDoNotAppearInTheBuyMarket() {
        Career c = createCareer();
        var faIds = contractService.getFreeAgents(c.id(), c.userId()).stream()
                .map(FreeAgentResponse::playerId).toList();
        var market = transferService.getMarket(c.id(), c.userId());
        assertThat(market).noneMatch(m -> faIds.contains(m.playerId()));
    }

    @Test
    void foreignUserCannotListFreeAgents() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> contractService.getFreeAgents(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.FreeAgentListIT"
```
Expected: FAIL — no FA pool seeded; `getFreeAgents`/`FreeAgentResponse` do not exist.

- [ ] **Step 3: Create FreeAgentResponse**

```java
package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record FreeAgentResponse(
        UUID playerId, String name, String position, int age, int overall, BigDecimal wageDemand) {}
```

- [ ] **Step 4: Seed the FA pool at creation**

In `SaveGameService.java` inject `FreeAgentGenerator` (add constructor param + field; add import `import br.com.elifootcarvoeiro.contract.FreeAgentGenerator;`). After the contract-stagger block from Task 3 (still before `// 3. Create competition`), add:

```java
        // Seed an initial free-agent pool (clubless) so there is always a market to shop.
        Random faRng = new Random(saveGame.getId().getMostSignificantBits() ^ 0xFA11AC0DL);
        for (int i = 0; i < 10; i++) {
            playerRepository.save(freeAgentGenerator.generate(saveGame.getId(), faRng));
        }
```

- [ ] **Step 5: Guard getMarket against free agents**

In `TransferService.getMarket`, change the filter so clubless players are excluded (they are signed via the free-agents endpoint, not bought):

```java
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.getClubId().equals(managedClubId))
```
(Replace the existing single-condition `.filter(p -> !p.getClubId().equals(managedClubId))`.)

- [ ] **Step 6: Add getFreeAgents to ContractService**

Add imports (`java.util.Comparator`, `java.util.List`) and method:

```java
    public List<FreeAgentResponse> getFreeAgents(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).stream()
                .map(p -> new FreeAgentResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())))
                .sorted(Comparator.comparing(FreeAgentResponse::overall).reversed())
                .toList();
    }
```

- [ ] **Step 7: Add the GET endpoint to ContractController**

Add import `org.springframework.web.bind.annotation.GetMapping`, `java.util.List`, and:

```java
    @GetMapping("/free-agents")
    public ResponseEntity<List<FreeAgentResponse>> freeAgents(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.getFreeAgents(saveGameId, userId));
    }
```

- [ ] **Step 8: Run to verify it passes (and existing buying market test still green)**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.FreeAgentListIT" --tests "br.com.elifootcarvoeiro.transfer.TransferServiceBuyingIT"
```
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/career/SaveGameService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/FreeAgentResponse.java \
  backend/src/test/java/br/com/elifootcarvoeiro/contract/FreeAgentListIT.java
git commit -m "$(cat <<'EOF'
feat(contract): seed free-agent pool at creation + list endpoint + market guard

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Free-agent signing

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java` (signFreeAgent + guards)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java` (sign endpoint)
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/contract/SignFreeAgentRequest.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractServiceSignIT.java`

**Interfaces:**
- Consumes: `TransferWindow.isOpen(int currentRound, int totalRounds): boolean` and `CompetitionRepository.findBySaveGameId(UUID): List<Competition>` (`Competition.getTotalRounds()`); `ClubRepository`/`PlayerRepository.findByClubId(UUID)` for squad size; `ContractValuation` (Task 2).
- Produces: `ContractService.signFreeAgent(UUID saveGameId, UUID userId, UUID playerId, BigDecimal wage, int contractYears): PlayerContractResponse`. `SignFreeAgentRequest(BigDecimal wage, int contractYears)`. Endpoint `POST /api/v1/save-games/{saveGameId}/free-agents/{playerId}/sign`. Constant `MAX_SQUAD_SIZE = 30`.

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceSignIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired PlayerRepository playerRepository;
    @Autowired RoundSimulationService roundSimulationService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("sign_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private FreeAgentResponse anyFreeAgent(Career c) {
        return contractService.getFreeAgents(c.id(), c.userId()).get(0);
    }

    @Test
    void signingAtTheDemandMovesTheFreeAgentToYourClub() {
        Career c = createCareer();
        FreeAgentResponse fa = anyFreeAgent(c);

        PlayerContractResponse r = contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3);

        assertThat(r.contractYears()).isEqualTo(3);
        Player signed = playerRepository.findById(fa.playerId()).orElseThrow();
        assertThat(signed.getClubId()).isEqualTo(c.clubId());
        assertThat(signed.getWage()).isEqualByComparingTo(fa.wageDemand());
        // No longer a free agent.
        assertThat(contractService.getFreeAgents(c.id(), c.userId()))
                .noneMatch(x -> x.playerId().equals(fa.playerId()));
    }

    @Test
    void aLowballWageIsRejected() {
        Career c = createCareer();
        FreeAgentResponse fa = anyFreeAgent(c);
        assertThatThrownBy(() -> contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand().subtract(new BigDecimal("1")), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PLAYER_REJECTS_TERMS"));
    }

    @Test
    void signingIsBlockedWhenTheWindowIsClosed() {
        Career c = createCareer();
        for (int i = 0; i < 5; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        FreeAgentResponse fa = anyFreeAgent(c);
        assertThatThrownBy(() -> contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED"));
    }

    @Test
    void cannotSignAPlayerWhoIsNotAFreeAgent() {
        Career c = createCareer();
        Player owned = playerRepository.findByClubId(c.clubId()).get(0);
        BigDecimal demand = contractValuation.wageDemand(owned.getOverall(), owned.getAge());
        assertThatThrownBy(() -> contractService.signFreeAgent(c.id(), c.userId(), owned.getId(), demand, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractServiceSignIT"
```
Expected: FAIL — `signFreeAgent`/`SignFreeAgentRequest` do not exist.

- [ ] **Step 3: Create SignFreeAgentRequest**

```java
package br.com.elifootcarvoeiro.contract;

import java.math.BigDecimal;

public record SignFreeAgentRequest(BigDecimal wage, int contractYears) {}
```

- [ ] **Step 4: Add signFreeAgent + guards to ContractService**

Add imports and injected deps (`TransferWindow`, `CompetitionRepository`, `Competition`):

```java
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.transfer.TransferWindow;
import org.springframework.http.HttpStatus;
```

Add `private static final int MAX_SQUAD_SIZE = 30;`, the two new constructor params (`TransferWindow transferWindow`, `CompetitionRepository competitionRepository`) with fields, and:

```java
    @Transactional
    public PlayerContractResponse signFreeAgent(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireWindowOpen(save);
        requireSquadRoom(save.getManagedClubId());

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(Player::isFreeAgent)
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Free agent not found", HttpStatus.NOT_FOUND));

        applyTerms(player, wage, contractYears);   // validates length/wage + acceptance
        player.setClubId(save.getManagedClubId()); // joins the squad; no fee, no ledger
        playerRepository.save(player);
        return toResponse(player);
    }

    private void requireWindowOpen(SaveGame save) {
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).get(0);
        if (!transferWindow.isOpen(save.getCurrentRound(), competition.getTotalRounds())) {
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
            throw new ApiException("SQUAD_FULL", "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
```

- [ ] **Step 5: Add the sign endpoint to ContractController**

```java
    @PostMapping("/free-agents/{playerId}/sign")
    public ResponseEntity<PlayerContractResponse> sign(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody SignFreeAgentRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.signFreeAgent(
                saveGameId, userId, playerId, request.wage(), request.contractYears()));
    }
```

- [ ] **Step 6: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.contract.ContractServiceSignIT"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractService.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/ContractController.java \
  backend/src/main/java/br/com/elifootcarvoeiro/contract/SignFreeAgentRequest.java \
  backend/src/test/java/br/com/elifootcarvoeiro/contract/ContractServiceSignIT.java
git commit -m "$(cat <<'EOF'
feat(contract): sign free agents (window+squad-gated, wage-threshold, no fee)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Buy-with-personal-terms (extend the 2C-1 transfer flow)

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOffer.java` (wage + contractYears)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/MakeOfferRequest.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/MarketPlayerResponse.java` (wageDemand)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferOfferResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferExecutor.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/transfer/TransferController.java`
- Modify (existing tests): `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceBuyingIT.java`
- Test (new): `backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferTermsIT.java`

**Interfaces:**
- Consumes: `ContractValuation.acceptsWage` + `wageDemand` (Task 2).
- Produces: `MakeOfferRequest(UUID playerId, BigDecimal amount, BigDecimal wage, int contractYears)`; `TransferOffer.create(... BigDecimal wage, Integer contractYears)` + `getWage()/getContractYears()`; `MarketPlayerResponse` gains `BigDecimal wageDemand`; `TransferOfferResponse` gains `BigDecimal wage, Integer contractYears`; `TransferService.makeOffer(UUID, UUID, UUID, BigDecimal amount, BigDecimal wage, int contractYears)`.

- [ ] **Step 1: Write the failing test (new terms behavior)**

```java
package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.contract.ContractValuation;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
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
class TransferTermsIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired ContractValuation contractValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User u = User.create("terms_" + UUID.randomUUID(), "hash");
        userRepository.save(u);
        return new Career(u.getId(), saveGameService.createCareer(u.getId()));
    }

    private Player anAiPlayer(Career c) {
        UUID other = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        return playerRepository.findByClubId(other).get(0);
    }

    @Test
    void acceptableTermsSetTheBoughtPlayersContract() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal wage = contractValuation.wageDemand(t.getOverall(), t.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, wage, 4);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        Player bought = playerRepository.findById(t.getId()).orElseThrow();
        assertThat(bought.getClubId()).isEqualTo(c.clubId());
        assertThat(bought.getWage()).isEqualByComparingTo(wage);
        assertThat(bought.getContractYears()).isEqualTo(4);
    }

    @Test
    void aLowballWageMakesTheDealFallThrough() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal demand = contractValuation.wageDemand(t.getOverall(), t.getAge());
        BigDecimal lowWage = demand.subtract(new BigDecimal("1"));
        BigDecimal buyerBalanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, lowWage, 3);

        assertThat(r.status()).isEqualTo("REJECTED");
        // No money moved; player stayed at the seller.
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(buyerBalanceBefore);
        assertThat(playerRepository.findById(t.getId()).orElseThrow().getClubId())
                .isNotEqualTo(c.clubId());
    }

    @Test
    void anInvalidLengthIsRejectedUpFront() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal wage = contractValuation.wageDemand(t.getOverall(), t.getAge());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, wage, 0))
                .satisfies(ex -> assertThat(((br.com.elifootcarvoeiro.common.ApiException) ex).getCode())
                        .isEqualTo("INVALID_CONTRACT"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.transfer.TransferTermsIT"
```
Expected: FAIL — `makeOffer` 6-arg signature / offer wage fields do not exist.

- [ ] **Step 3: Add wage + contractYears to TransferOffer**

In `TransferOffer.java` add columns and constructor wiring:

```java
    @Column(name = "wage") private BigDecimal wage;
    @Column(name = "contract_years") private Integer contractYears;
```
Extend the private constructor to accept and assign `wage` + `contractYears`, and change `create(...)` to:

```java
    public static TransferOffer create(UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId,
                                       TransferDirection direction, BigDecimal amount, int round,
                                       BigDecimal wage, Integer contractYears) {
        return new TransferOffer(UUID.randomUUID(), saveGameId, playerId, fromClubId, toClubId,
                direction, amount, TransferOfferStatus.PENDING, round, Instant.now(), wage, contractYears);
    }
```
Add getters:

```java
    public BigDecimal getWage() { return wage; }
    public Integer getContractYears() { return contractYears; }
```

Update the one existing INCOMING-bid call site in `TransferService.generateIncomingOffers` to pass `null, null` for the new params:

```java
                transferOfferRepository.save(TransferOffer.create(saveGameId, player.getId(),
                        bidder.getId(), managedClubId, TransferDirection.INCOMING, bid, round, null, null));
```

- [ ] **Step 4: Extend the DTO records**

`MakeOfferRequest.java`:

```java
public record MakeOfferRequest(UUID playerId, BigDecimal amount, BigDecimal wage, int contractYears) {}
```

`MarketPlayerResponse.java` (add `wageDemand` as the final field):

```java
public record MarketPlayerResponse(
        UUID playerId, String name, String position, int age, int overall,
        UUID clubId, String clubName, BigDecimal value, BigDecimal wage, BigDecimal wageDemand) {}
```

`TransferOfferResponse.java` (add `wage` + `contractYears` as final fields):

```java
public record TransferOfferResponse(
        UUID id, UUID playerId, String playerName,
        UUID fromClubId, String fromClubName, UUID toClubId, String toClubName,
        String direction, BigDecimal amount, BigDecimal counterAmount, String status, int round,
        BigDecimal wage, Integer contractYears) {}
```

- [ ] **Step 5: Wire ContractValuation + terms gate into TransferService**

Add import `import br.com.elifootcarvoeiro.contract.ContractValuation;`, inject it (constructor param + field), and rewrite `getMarket` mapping + `makeOffer` + `acceptCounter` + `toResponse`:

`getMarket` map (add wageDemand):

```java
                .map(p -> new MarketPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(), p.getClubId(),
                        clubNames.getOrDefault(p.getClubId(), ""),
                        transferValuation.marketValue(p.getOverall(), p.getAge()), p.getWage(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())))
```

`makeOffer` (new signature + input validation + terms gate on ACCEPT):

```java
    @Transactional
    public TransferOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId,
                                           BigDecimal amount, BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> invalid("Player is not available for transfer"));
        if (player.getClubId() == null) {
            throw invalid("Free agents are signed, not bought");
        }
        if (player.getClubId().equals(managedClubId)) {
            throw invalid("Cannot buy a player you already own");
        }
        requireValidTerms(wage, contractYears);
        requireFunds(managedClubId, amount);
        requireSquadRoom(managedClubId);

        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());
        TransferOffer offer = TransferOffer.create(saveGameId, playerId, managedClubId, player.getClubId(),
                TransferDirection.OUTGOING, amount, save.getCurrentRound(), wage, contractYears);
        transferOfferRepository.save(offer);

        NegotiationResult result = transferNegotiator.respondToBuy(value, amount);
        switch (result.decision()) {
            case ACCEPT -> completeBuy(save, offer, player, amount);
            case COUNTER -> offer.markCountered(result.counterAmount());
            case REJECT -> offer.markRejected();
        }
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }
```

`acceptCounter` (terms gate before execute):

```java
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
        Player player = playerRepository.findById(offer.getPlayerId())
                .orElseThrow(() -> invalid("Player no longer exists"));
        completeBuy(save, offer, player, fee);
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }
```

Add the shared `completeBuy` + `requireValidTerms` helpers (place near the other private helpers):

```java
    /** Completes a buy iff the player accepts the offered wage, else the deal falls through. */
    private void completeBuy(SaveGame save, TransferOffer offer, Player player, BigDecimal fee) {
        if (!contractValuation.acceptsWage(player.getOverall(), player.getAge(), offer.getWage())) {
            offer.markRejected(); // personal terms not met; no money moves, player stays
            return;
        }
        transferExecutor.execute(save, offer, fee);
        offer.markAccepted();
    }

    private void requireValidTerms(BigDecimal wage, int contractYears) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException("INVALID_CONTRACT",
                    "Contract length must be 1-5 years and wage must be positive",
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
```

`toResponse` (include wage + contractYears):

```java
        return new TransferOfferResponse(offer.getId(), offer.getPlayerId(), playerName,
                offer.getFromClubId(), fromName, offer.getToClubId(), toName,
                offer.getDirection().name(), offer.getAmount(), offer.getCounterAmount(),
                offer.getStatus().name(), offer.getRound(), offer.getWage(), offer.getContractYears());
```

- [ ] **Step 6: Set the bought player's contract in TransferExecutor**

In `TransferExecutor.execute`, after `player.setClubId(buyerId);` (before the `clubRepository.save` calls) add:

```java
        // Outgoing buy carries personal terms; a managed sale (incoming) leaves wage null.
        if (offer.getWage() != null) {
            player.setWage(offer.getWage());
            if (offer.getContractYears() != null) {
                player.setContractYears(offer.getContractYears());
            }
        }
```

- [ ] **Step 7: Pass new fields through the controller**

In `TransferController.makeOffer`:

```java
        return ResponseEntity.ok(transferService.makeOffer(
                saveGameId, userId, request.playerId(), request.amount(),
                request.wage(), request.contractYears()));
```

- [ ] **Step 8: Update the existing TransferServiceBuyingIT for the new signature**

Every `transferService.makeOffer(c.id(), c.userId(), target.getId(), <fee>)` call now needs a wage + length. Add a helper and an autowired `ContractValuation`, and pass an acceptable wage so the existing fee-negotiation assertions still hold:

```java
    @Autowired ContractValuation contractValuation;

    private BigDecimal okWage(Player p) {
        return contractValuation.wageDemand(p.getOverall(), p.getAge());
    }
```
Then update each call, e.g.:

```java
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), value, okWage(target), 3);
```
Apply to all four `makeOffer` call sites (`offeringFullValueExecutesTheTransfer`, `aLowOfferIsCounteredAndCanBeAccepted`, `aDerisoryOfferIsRejected`, `anOfferOverBudgetIsRejectedWithInsufficientFunds`, and the window-closed test). The `INSUFFICIENT_FUNDS`/`TRANSFER_WINDOW_CLOSED` guards run before the terms check, so those assertions are unaffected.

- [ ] **Step 9: Run the new + existing buying ITs**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.transfer.TransferTermsIT" --tests "br.com.elifootcarvoeiro.transfer.TransferServiceBuyingIT" --tests "br.com.elifootcarvoeiro.transfer.TransferServiceSellingIT" --tests "br.com.elifootcarvoeiro.transfer.TransferExecutorIT"
```
Expected: PASS. (Selling/executor ITs exercise INCOMING offers where wage is null — the executor's null check leaves contracts untouched on a sale.)

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/transfer/ \
  backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferTermsIT.java \
  backend/src/test/java/br/com/elifootcarvoeiro/transfer/TransferServiceBuyingIT.java
git commit -m "$(cat <<'EOF'
feat(contract): buy with personal terms (wage+length); lowball wage falls through

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Squad DTO — contract years + wage demand

**Files:**
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/PlayerResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/SquadContractInfoIT.java`

**Interfaces:**
- Consumes: `ContractValuation.wageDemand` (Task 2); `Player.getContractYears` (Task 1).
- Produces: `PlayerResponse` gains `int contractYears, BigDecimal wageDemand` (final fields). Squad responses populate them.

- [ ] **Step 1: Write the failing test**

```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class SquadContractInfoIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired LineupService lineupService;

    @Test
    void squadPlayersExposeContractYearsAndWageDemand() {
        User user = User.create("squadc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        SquadResponse squad = lineupService.getSquad(save.id(), save.managedClubId(), user.getId());

        assertThat(squad.players()).isNotEmpty();
        assertThat(squad.players()).allMatch(p -> p.contractYears() >= 1 && p.contractYears() <= 4);
        assertThat(squad.players()).allMatch(p -> p.wageDemand().signum() > 0);
    }
}
```
NOTE: confirm the exact squad accessor name on `LineupService` (the method backing `GET /clubs/{clubId}/players`). If it differs from `getSquad(saveGameId, clubId, userId)`, call the actual method; the assertions are what matter.

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.match.SquadContractInfoIT"
```
Expected: FAIL — `PlayerResponse.contractYears()/wageDemand()` do not exist.

- [ ] **Step 3: Extend PlayerResponse**

Add two final fields:

```java
public record PlayerResponse(
        UUID id,
        String name,
        String position,
        int age,
        int overall,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        int form,
        int yellowCards,
        int suspensionRemaining,
        boolean eligible,
        String role,
        int contractYears,
        java.math.BigDecimal wageDemand
) {
}
```

- [ ] **Step 4: Populate them in LineupService.toPlayerResponse**

Inject `ContractValuation` into `LineupService` (add `import br.com.elifootcarvoeiro.contract.ContractValuation;`, constructor param + field). Update `toPlayerResponse` to pass the two new args last:

```java
                p.isEligible(),
                role != null ? role.name() : null,
                p.getContractYears(),
                contractValuation.wageDemand(p.getOverall(), p.getAge()));
```

- [ ] **Step 5: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.elifootcarvoeiro.match.SquadContractInfoIT"
```
Expected: PASS.

- [ ] **Step 6: Run the full backend suite (integration checkpoint)**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test
```
Expected: PASS (all classes). Fix any compile fallout from the `PlayerResponse` shape change in other squad/lineup tests by adding the two trailing constructor args where a `PlayerResponse(...)` literal is built (if any).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/match/PlayerResponse.java \
  backend/src/main/java/br/com/elifootcarvoeiro/match/LineupService.java \
  backend/src/test/java/br/com/elifootcarvoeiro/match/SquadContractInfoIT.java
git commit -m "$(cat <<'EOF'
feat(contract): expose contract years + wage demand on squad players

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Frontend types + client functions

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Test: `frontend/src/api/career.contract.test.ts`

**Interfaces:**
- Produces: TS types `FreeAgent`, `RenewContractRequest`, `SignFreeAgentRequest`, `PlayerContract`; extends `PlayerResponse` (+`contractYears`,`wageDemand`), `MarketPlayer` (+`wageDemand`), `TransferOffer` (+`wage`,`contractYears`), `MakeOfferRequest` (+`wage`,`contractYears`), `RolloverResponse` (+`expiredContracts`). Client funcs `getFreeAgents`, `signFreeAgent`, `renewContract`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as client from './client'
import { getFreeAgents, signFreeAgent, renewContract } from './career'

describe('contract client', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('getFreeAgents calls the free-agents endpoint', async () => {
    const spy = vi.spyOn(client, 'apiGet').mockResolvedValue([] as never)
    await getFreeAgents('S1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/free-agents', 'tok')
  })

  it('signFreeAgent posts wage + length', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await signFreeAgent('S1', 'P1', { wage: 100, contractYears: 3 }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/free-agents/P1/sign', { wage: 100, contractYears: 3 }, 'tok')
  })

  it('renewContract posts to the renew endpoint', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await renewContract('S1', 'P1', { wage: 200, contractYears: 2 }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/players/P1/renew', { wage: 200, contractYears: 2 }, 'tok')
  })
})
```

- [ ] **Step 2: Run to verify it fails**

```
cd frontend && npm test -- --run src/api/career.contract.test.ts
```
Expected: FAIL — functions not exported.

- [ ] **Step 3: Extend types.ts**

Add/extend:

```ts
export type FreeAgent = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  wageDemand: number
}

export type RenewContractRequest = { wage: number; contractYears: number }
export type SignFreeAgentRequest = { wage: number; contractYears: number }
export type PlayerContract = { playerId: string; name: string; wage: number; contractYears: number }
```
Add `contractYears: number` and `wageDemand: number` to `PlayerResponse`; add `wageDemand: number` to `MarketPlayer`; add `wage: number | null` and `contractYears: number | null` to `TransferOffer`; change `MakeOfferRequest` to `{ playerId: string; amount: number; wage: number; contractYears: number }`; add `expiredContracts: SquadChange[]` to `RolloverResponse`.

- [ ] **Step 4: Add client funcs to career.ts**

Add to the type import list `FreeAgent, RenewContractRequest, SignFreeAgentRequest, PlayerContract`, then:

```ts
export function getFreeAgents(saveGameId: string, token: string): Promise<FreeAgent[]> {
  return apiGet<FreeAgent[]>(`/save-games/${saveGameId}/free-agents`, token)
}

export function signFreeAgent(
  saveGameId: string,
  playerId: string,
  req: SignFreeAgentRequest,
  token: string,
): Promise<PlayerContract> {
  return apiPost<PlayerContract>(`/save-games/${saveGameId}/free-agents/${playerId}/sign`, req, token)
}

export function renewContract(
  saveGameId: string,
  playerId: string,
  req: RenewContractRequest,
  token: string,
): Promise<PlayerContract> {
  return apiPost<PlayerContract>(`/save-games/${saveGameId}/players/${playerId}/renew`, req, token)
}
```

- [ ] **Step 5: Run to verify it passes**

```
cd frontend && npm test -- --run src/api/career.contract.test.ts
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts frontend/src/api/career.contract.test.ts
git commit -m "$(cat <<'EOF'
feat(contract): frontend types + client funcs (free agents, renew, sign)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Frontend — Free Agents page + route + dashboard link

**Files:**
- Create: `frontend/src/features/contract/FreeAgentsPage.tsx`
- Create: `frontend/src/features/contract/FreeAgentsPage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/features/career/DashboardPage.tsx`

**Interfaces:**
- Consumes: `getSaveGame`, `getFreeAgents`, `signFreeAgent` (Task 11); `isTransferWindowOpen`, `formatBRL`.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FreeAgentsPage } from './FreeAgentsPage'
import * as api from '../../api/career'

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'tok' }) }))

function renderPage() {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
        <FreeAgentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FreeAgentsPage', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists free agents with their wage demand', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ currentRound: 0, totalRounds: 18 } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)

    renderPage()

    expect(await screen.findByText('Livre Um')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify it fails**

```
cd frontend && npm test -- --run src/features/contract/FreeAgentsPage.test.tsx
```
Expected: FAIL — `FreeAgentsPage` does not exist.

- [ ] **Step 3: Implement FreeAgentsPage**

```tsx
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSaveGame, getFreeAgents, signFreeAgent } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import { isTransferWindowOpen } from '../../lib/transferWindow'

export function FreeAgentsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const pool = useQuery({ queryKey: ['freeAgents', saveGameId], queryFn: () => getFreeAgents(saveGameId, token ?? ''), enabled })

  const signMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      signFreeAgent(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
    },
  })

  if (pool.isLoading) return <p>Carregando agentes livres…</p>
  if (pool.error) return <p>Erro ao carregar agentes livres.</p>

  const windowOpen = save.data ? isTransferWindowOpen(save.data.currentRound, save.data.totalRounds) : true

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Agentes Livres</h2>
      {!windowOpen && <p role="alert" style={{ color: 'red' }}>Janela de transferências fechada.</p>}
      {signMut.isError && <p style={{ color: 'red' }}>Não foi possível contratar (salário baixo ou elenco cheio).</p>}
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th><th>Idade</th><th>OVR</th>
            <th style={{ textAlign: 'right' }}>Salário pedido</th>
            <th>Contratar</th>
          </tr>
        </thead>
        <tbody>
          {(pool.data ?? []).map(fa => (
            <tr key={fa.playerId}>
              <td>{fa.name}</td>
              <td style={{ textAlign: 'center' }}>{fa.position}</td>
              <td style={{ textAlign: 'center' }}>{fa.age}</td>
              <td style={{ textAlign: 'center' }}>{fa.overall}</td>
              <td style={{ textAlign: 'right' }}>{formatBRL(fa.wageDemand)}</td>
              <td style={{ textAlign: 'center' }}>
                <input aria-label={`Salário para ${fa.name}`} type="number"
                  value={wages[fa.playerId] ?? ''} onChange={e => setWages(w => ({ ...w, [fa.playerId]: e.target.value }))}
                  style={{ width: 110 }} />{' '}
                <input aria-label={`Anos para ${fa.name}`} type="number" min={1} max={5}
                  value={years[fa.playerId] ?? '3'} onChange={e => setYears(y => ({ ...y, [fa.playerId]: e.target.value }))}
                  style={{ width: 50 }} />{' '}
                <button className="cec-btn" disabled={!windowOpen || signMut.isPending}
                  onClick={() => signMut.mutate({
                    playerId: fa.playerId,
                    wage: Number(wages[fa.playerId] ?? fa.wageDemand),
                    contractYears: Number(years[fa.playerId] ?? 3),
                  })}>Contratar</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 4: Add the route**

In `router.tsx` add the import `import { FreeAgentsPage } from './features/contract/FreeAgentsPage'` and a protected route before the catch-all:

```tsx
      <Route
        path="/free-agents"
        element={
          <ProtectedRoute>
            <FreeAgentsPage />
          </ProtectedRoute>
        }
      />
```

- [ ] **Step 5: Add the dashboard link**

In `DashboardPage.tsx`, in the button row (after the "Mercado" link, around line 128), add:

```tsx
              <Link to={`/free-agents?saveGameId=${saveGame.id}`}>
                <button className="cec-btn">Livres</button>
              </Link>
```

- [ ] **Step 6: Run to verify it passes**

```
cd frontend && npm test -- --run src/features/contract/FreeAgentsPage.test.tsx
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/contract/ frontend/src/router.tsx frontend/src/features/career/DashboardPage.tsx
git commit -m "$(cat <<'EOF'
feat(contract): free agents page + route + dashboard link

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Frontend — Squad page contract column + renew form

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Modify: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: `renewContract` (Task 11); `PlayerResponse.contractYears`/`.wageDemand` (Task 10/11); `formatBRL`.

- [ ] **Step 1: Write the failing test (add to existing SquadPage.test.tsx)**

Add a test asserting the squad shows a player's contract years and offers a renew control. Mock `getSquad` to return a player including `contractYears` + `wageDemand`, render, and assert the contract years text appears and a "Renovar" button exists. (Match the file's existing render harness and mock style.)

```tsx
  it('shows contract years and a renew control', async () => {
    // ...reuse this file's existing mocks; ensure the squad player includes:
    //   contractYears: 2, wageDemand: 90000
    // render the page, then:
    expect(await screen.findByText(/Renovar/)).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run to verify it fails**

```
cd frontend && npm test -- --run src/features/squad/SquadPage.test.tsx
```
Expected: FAIL — no renew control / contract column.

- [ ] **Step 3: Add contract display + renew form to SquadPage**

Add (near the other imports) `import { renewContract } from '../../api/career'` and, alongside the existing mutations, a renew mutation:

```tsx
  const renewMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      renewContract(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] }) },
  })
```
In the squad player row rendering, show contract years (highlight ≤1 yr) and the wage demand, plus a renew control. Use the player's `wageDemand` as the default offered wage and a length input defaulting to 3:

```tsx
              <td style={{ color: p.contractYears <= 1 ? 'red' : undefined }}>{p.contractYears} ano(s)</td>
              <td>{formatBRL(p.wageDemand)}</td>
              <td>
                <button className="cec-btn" disabled={renewMut.isPending}
                  onClick={() => renewMut.mutate({ playerId: p.id, wage: p.wageDemand, contractYears: 3 })}>
                  Renovar
                </button>
              </td>
```
(Adapt the exact cells/headers to the existing squad table; ensure `formatBRL` is imported and `queryClient`/`token`/`saveGameId`/`clubId` are in scope as they already are on this page.)

- [ ] **Step 4: Run to verify it passes**

```
cd frontend && npm test -- --run src/features/squad/SquadPage.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(contract): squad page contract years + renew control

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Frontend — Transfer market wage + length inputs

**Files:**
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.test.tsx`

**Interfaces:**
- Consumes: extended `MakeOfferRequest` (`wage`,`contractYears`) + `MarketPlayer.wageDemand` (Task 11).

- [ ] **Step 1: Write the failing test (add to existing test file)**

Add a test that the make-offer row exposes a wage input (aria-label `Salário para <name>`) and that clicking "Propor" sends `wage` + `contractYears`. Mock `makeOffer` and assert it's called with all four fields. Reuse the file's existing harness.

```tsx
  it('sends wage and length with the fee', async () => {
    // mock getMarket to include wageDemand on the player; mock makeOffer
    // fill fee + wage inputs, click Propor, then:
    expect(makeOfferSpy).toHaveBeenCalledWith(
      'S1', expect.objectContaining({ wage: expect.any(Number), contractYears: expect.any(Number) }), 'tok')
  })
```

- [ ] **Step 2: Run to verify it fails**

```
cd frontend && npm test -- --run src/features/transfer/TransferMarketPage.test.tsx
```
Expected: FAIL — the offer mutation only sends `{playerId, amount}`.

- [ ] **Step 3: Add wage + length inputs to the market row**

Change the offer mutation type and `mutate` call to include wage + contractYears, add per-row state for wage/years, and render the player's `wageDemand` plus the two inputs. In `TransferMarketPage.tsx`:

```tsx
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: invalidate,
  })
```
In the market table, add a "Salário pedido" cell (`{formatBRL(p.wageDemand)}`) and, in the proposal cell, wage + length inputs, then update the button:

```tsx
                <input aria-label={`Salário para ${p.name}`} type="number"
                  value={wages[p.playerId] ?? ''} onChange={e => setWages(w => ({ ...w, [p.playerId]: e.target.value }))}
                  style={{ width: 110 }} />{' '}
                <input aria-label={`Anos para ${p.name}`} type="number" min={1} max={5}
                  value={years[p.playerId] ?? '3'} onChange={e => setYears(y => ({ ...y, [p.playerId]: e.target.value }))}
                  style={{ width: 50 }} />{' '}
                <button className="cec-btn" disabled={!windowOpen || offerMut.isPending}
                  onClick={() => offerMut.mutate({
                    playerId: p.playerId,
                    amount: Number(amounts[p.playerId] ?? 0),
                    wage: Number(wages[p.playerId] ?? p.wageDemand),
                    contractYears: Number(years[p.playerId] ?? 3),
                  })}>Propor</button>
```
Add a `<th>` for "Salário pedido" in the table head to match.

- [ ] **Step 4: Run to verify it passes**

```
cd frontend && npm test -- --run src/features/transfer/TransferMarketPage.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/transfer/TransferMarketPage.tsx frontend/src/features/transfer/TransferMarketPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(contract): market offer form sends wage + contract length

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Frontend — Season summary expired-contracts section

**Files:**
- Modify: `frontend/src/features/season/SeasonSummaryPage.tsx`
- Modify: `frontend/src/features/season/SeasonSummaryPage.test.tsx`

**Interfaces:**
- Consumes: `RolloverResponse.expiredContracts` (Task 11).

- [ ] **Step 1: Write the failing test (add to existing test file)**

Mock the rollover response to include `expiredContracts: [{ playerName: 'Zé Tigre', position: 'MID', age: 31 }]` and assert the summary renders an "Contratos expirados" heading and the player's name. Reuse the file's existing harness/mocks.

```tsx
  it('lists players who lapsed into free agency', async () => {
    // rollover mock includes expiredContracts: [{ playerName: 'Zé Tigre', position: 'MID', age: 31 }]
    expect(await screen.findByText(/Contratos expirados/)).toBeInTheDocument()
    expect(screen.getByText(/Zé Tigre/)).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run to verify it fails**

```
cd frontend && npm test -- --run src/features/season/SeasonSummaryPage.test.tsx
```
Expected: FAIL — no expired-contracts section.

- [ ] **Step 3: Render the expired-contracts section**

In `SeasonSummaryPage.tsx`, where retirements/newcomers are rendered from the rollover response, add a section driven by `expiredContracts` (guard for empty). Mirror the existing retirements list markup:

```tsx
      {(rollover.expiredContracts?.length ?? 0) > 0 && (
        <section>
          <h3>Contratos expirados</h3>
          <ul>
            {rollover.expiredContracts.map((c, i) => (
              <li key={i}>{c.playerName} ({c.position}, {c.age})</li>
            ))}
          </ul>
        </section>
      )}
```
(Use the same variable name the page already uses for the rollover response object.)

- [ ] **Step 4: Run to verify it passes**

```
cd frontend && npm test -- --run src/features/season/SeasonSummaryPage.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Final full-suite verification**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test
cd ../frontend && npm test -- --run && npm run build
```
Expected: backend all green; frontend all green + build clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/season/SeasonSummaryPage.tsx frontend/src/features/season/SeasonSummaryPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(contract): season summary lists expired contracts

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Plan Self-Review

**Spec coverage:** §4.1 V7 migration → T1; §4.2 Player fields → T1; §5 ContractValuation → T2; §6.1 renewals → T4; §6.2 free agents list/sign → T7/T8; §6.3 buy-with-terms → T9; §7 rollover → T6; §8 creation seeding (contract stagger → T3, FA pool → T7); §9 frontend (types/client → T11, free-agents page → T12, squad renew → T13, market inputs → T14, summary expiries → T15; squad contract DTO backend → T10); §10 constants → Global Constraints + tasks; §11 error handling → T4/T8/T9; §12 testing → each task's tests; §13 determinism → Global Constraints + seeded RNG in T3/T6/T7. All covered.

**Placeholder scan:** No TBD/TODO. The only "adapt to the existing file" notes (T10 squad accessor name, T13/T14/T15 reuse existing test harness) are explicit integration instructions with concrete assertions, not vague placeholders — the new code/snippets are given in full.

**Type consistency:** `contractYears` (int) / `wageDemand` (BigDecimal) consistent across `Player`, `ContractValuation`, `PlayerResponse`, `FreeAgentResponse`, `MarketPlayerResponse`. `makeOffer` 6-arg signature consistent T9 ↔ controller ↔ T9 test ↔ existing-test update. `TransferOffer.create` new signature updated at both call sites (makeOffer outgoing, generateIncomingOffers null,null). `RolloverResponse` 5-field shape consistent T6 ↔ FE type T11 ↔ T15. FE `MakeOfferRequest`/`TransferOffer`/`MarketPlayer`/`PlayerResponse` extensions consistent T11 ↔ consumers T12-T15. `signFreeAgent`/`renewContract`/`getFreeAgents` signatures consistent T11 ↔ T12/T13.

**Sequencing safety:** contract-length seeding (T3) precedes rollover ticking (T6) so the first rollover never lapses a default-0 squad; rollover null-safety (T6) precedes creation FA pool (T7) so seeded free agents never NPE the rollover loop; market guard ships with FA seeding (T7); the `makeOffer` signature break (T9) updates its existing callers in the same task; the full-suite checkpoints (T10 step 6, T15 step 5) catch any cross-task fallout.
