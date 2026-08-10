# Série A and Background Competitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the real 2026 Brasileirão Série A as a fully simulated competition the managed club does not play in, without lengthening the player's season.

**Architecture:** Competitions split into *managed* (the managed club has a `competition_club` row — interleaved and stopping the advance, exactly as today) and *background* (independently dated, resolved silently as the calendar passes them). The split is derived from membership, never stored, so it stays correct when promotion later moves the club between divisions.

**Tech Stack:** Java 21 / Spring Boot, Flyway, JPA, Postgres, Testcontainers, JUnit 5; React + TypeScript + MUI + vitest.

**Spec:** `docs/superpowers/specs/2026-08-08-serie-a-background-competitions-design.md`

## Global Constraints

- **The managed club id must be passed into `SeasonCalendarGenerator.generate`, not read from the save.** The spec says the generator "already has access to `getManagedClubId()` via the save" — that is **wrong**. `SaveGameService` calls `generate` at step 4 and sets the managed club at step 5, so the save's `managedClubId` is still null at generation time. Both callers know the club and must pass it.
- **"Managed" is derived from `competition_club` membership.** No `background` column, no seed flag. A stored flag would need flipping on two competitions at once during promotion, and a missed flip yields a division you play in whose matches silently skip themselves.
- **Managed competitions' dates must not move.** The 5-day `MATCH_INTERVAL_DAYS` interleave and the resulting 270-day season are unchanged. Adding a background competition must not shift a single managed fixture — there is a test for exactly this.
- **Determinism.** Background rounds are seeded from `(saveGameId, fixture)` like every other fixture and resolved in date order. No RNG, no wall-clock, no hash-ordered iteration in the generator.
- **The advance stops only on the player's own matches.** Background results never enter `AdvanceResponse`.
- Flyway is forward-only; V49 is the current highest. This slice needs **no migration** — it is seed data plus behaviour.
- Money is `BigDecimal`; dates are `LocalDate`; ids are `UUID`; constructor injection only; DTOs are records.
- Backend: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew` from `backend/`. Frontend type-check is `npx tsc -b` (`tsc --noEmit` is a no-op here).
- The Postgres test container has died mid-run several times under load from unrelated Docker stacks. `CannotCreateTransactionException` or "Unable to rollback against JDBC Connection" are infrastructure, not defects — re-run the class in isolation before changing anything.

## File Structure

**New backend files**
- `season/BackgroundCompetitionService.java` — finds and resolves due background rounds

**Modified backend files**
- `season/SeasonCalendarGenerator.java` — partition managed vs background; date each independently
- `season/SeasonAdvanceService.java` — call the resolver; scope `noMoreMatchEvents` to managed competitions
- `career/SaveGameService.java` — pass the managed club id to `generate`
- `season/SeasonRolloverService.java` — pass it too; skip archiving background competitions
- `resources/seed/clubs.json` — 19 new clubs, Série A competition entry

**No new frontend files.** Série A appears in the existing competition switcher because it is a competition in the save.

---

### Task 1: Partition the calendar into managed and background

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java:260`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java:344`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorIT.java`

**Interfaces:**
- Consumes: `CompetitionClubRepository.findByCompetitionId(UUID)` (existing)
- Produces: `SeasonCalendarGenerator.generate(UUID saveGameId, int seasonYear, List<Competition> competitions, UUID managedClubId)` — the 3-arg overload is **removed**, not deprecated, so no caller can silently keep the old behaviour

- [ ] **Step 1: Write the failing tests**

Add to `SeasonCalendarGeneratorIT`:

```java
    @Test
    void aBackgroundCompetitionDoesNotMoveAnyManagedFixture() {
        // Given: the same save generated with and without a background competition.
        // The managed competition's dates must be byte-identical -- adding a division the player
        // does not play in must not change their season by one day.
        generator.generate(saveGameId, 2026, List.of(catarinense), managedClubId);
        List<LocalDate> before = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId()).stream()
                .map(Fixture::getMatchDate).sorted().toList();

        fixtureRepository.deleteAll(fixtureRepository.findBySaveGameId(saveGameId));
        seasonEventRepository.deleteAll(seasonEventRepository.findBySaveGameId(saveGameId));

        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);
        List<LocalDate> after = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId()).stream()
                .map(Fixture::getMatchDate).sorted().toList();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void aBackgroundCompetitionIsDatedAcrossTheSameSeasonWindow() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        LocalDate lastManaged = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> catarinense.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).max(LocalDate::compareTo).orElseThrow();
        List<LocalDate> background = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> serieA.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).sorted().toList();

        // One event per round, all inside the managed season's window, strictly increasing.
        assertThat(background).hasSize(serieA.getTotalRounds());
        assertThat(background.get(0)).isAfterOrEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(background.get(background.size() - 1)).isBeforeOrEqualTo(lastManaged);
        assertThat(background).isSorted();
    }

    @Test
    void aBackgroundCompetitionMaterializesAllItsFixtures() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        // A plain 20-club league: 380 fixtures, all dated, none phased.
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, serieA.getId());
        assertThat(fixtures).hasSize(380);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getMatchDate()).isNotNull());
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
    }
```

The fixture setup must create `serieA` as a 20-club competition whose members **exclude** `managedClubId`, and `catarinense` as one that includes it. Follow the existing setup in this file for creating competitions and `competition_club` rows.

- [ ] **Step 2: Run to confirm they fail**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonCalendarGeneratorIT*' ; echo "EXIT=$?"
```

Expected: compilation failure — `generate` has no 4-arg form.

- [ ] **Step 3: Add the managed club parameter and partition**

In `SeasonCalendarGenerator`, change the signature and split the competition list before the existing round-building loop:

```java
    /**
     * @param managedClubId the club the player manages. Competitions it belongs to are interleaved
     *                      onto one shared 5-day cadence (their matches must not collide); every
     *                      other competition is dated independently across the same window, so
     *                      adding a division the player does not play in never lengthens their
     *                      season. Passed in rather than read from the save because
     *                      SaveGameService generates the calendar BEFORE it sets the managed club.
     */
    @Transactional
    public void generate(UUID saveGameId, int seasonYear, List<Competition> competitions,
                         UUID managedClubId) {
        final LocalDate seasonStart = LocalDate.of(seasonYear, 1, 15);

        List<Competition> comps = competitions.stream()
                .sorted(Comparator.comparing(Competition::getId)).toList();

        List<Competition> managed = new ArrayList<>();
        List<Competition> background = new ArrayList<>();
        for (Competition comp : comps) {
            boolean playsHere = competitionClubRepository.findByCompetitionId(comp.getId()).stream()
                    .anyMatch(cc -> cc.getClubId().equals(managedClubId));
            (playsHere ? managed : background).add(comp);
        }
```

The rest of the existing method body now iterates `managed` instead of `comps`. Leave every other line — the interleave, the 5-day dating, the transfer-event injection — exactly as it is.

- [ ] **Step 4: Date the background competitions**

After the managed dating loop and before `addTransferEvents`, add:

```java
        // Background competitions run in parallel across the same window the managed season
        // occupies, so their tables advance at a believable pace without consuming any of the
        // player's match slots.
        int managedSeasonDays = Math.max(1, (ordered.size() - 1) * MATCH_INTERVAL_DAYS);
        for (Competition comp : background) {
            generateBackground(saveGameId, seasonStart, comp, managedSeasonDays);
        }
```

and the method:

```java
    /**
     * Dates one background competition's rounds evenly across the managed season's window and
     * persists its fixtures and MATCH events. The events exist so BackgroundCompetitionService
     * can tell what is due; they are distinguished from managed ones by their competition's
     * membership, never by a column.
     */
    private void generateBackground(UUID saveGameId, LocalDate seasonStart, Competition comp,
                                    int managedSeasonDays) {
        List<UUID> memberIds = competitionClubRepository.findByCompetitionId(comp.getId()).stream()
                .map(cc -> cc.getClubId()).sorted().toList();
        int rounds = comp.getTotalRounds();
        int interval = Math.max(1, managedSeasonDays / Math.max(1, rounds));

        List<List<Fixture>> byRound = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            byRound.add(new ArrayList<>());
        }
        fixtureScheduler.doubleRoundRobin(memberIds.size()).forEach(scheduled -> {
            UUID home = memberIds.get(scheduled.homeIndex());
            UUID away = memberIds.get(scheduled.awayIndex());
            byRound.get(scheduled.round()).add(
                    Fixture.create(saveGameId, comp.getId(), scheduled.round(), home, away, null));
        });

        for (int r = 0; r < rounds; r++) {
            LocalDate date = seasonStart.plusDays((long) r * interval);
            for (Fixture fx : byRound.get(r)) {
                fx.setMatchDate(date);
                entityManager.persist(fx);
            }
            entityManager.persist(SeasonEvent.createMatch(saveGameId, date, comp.getId(), r, 0));
        }
    }
```

- [ ] **Step 5: Update both callers**

`SaveGameService.java:260` — the managed club is chosen from the seed but not yet set on the save at this point, so pass it directly:

```java
        seasonCalendarGenerator.generate(saveGame.getId(), seasonYear, competitions, managedClubId);
```

using whichever local already holds the managed club's id in that method (the same value step 5 sets on the save).

`SeasonRolloverService.java:344` — this method already has `managedClubId` in scope (line 170):

```java
        seasonCalendarGenerator.generate(saveGameId, finishingYear + 1, competitions, managedClubId);
```

- [ ] **Step 6: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonCalendarGeneratorIT*' --tests '*CareerCreation*' --tests '*SaveGameServiceIT*' ; echo "EXIT=$?"
```

Expected: PASS. Existing saves have no background competitions, so every current count is unchanged.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorIT.java
git commit -m "feat(season): date background competitions in parallel with the managed season"
```

---

### Task 2: Resolve background rounds as the calendar passes them

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/BackgroundCompetitionService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/BackgroundCompetitionIT.java`

**Interfaces:**
- Consumes: `FixtureSimulator.simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures)`; `FinanceService.processMatchday(UUID saveGameId, UUID competitionId, List<Fixture> fixtures, LocalDate date)`; `FixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(UUID, UUID, int)`; `SeasonEventRepository.findBySaveGameIdAndProcessedFalse(UUID)`
- Produces: `BackgroundCompetitionService.resolveDue(SaveGame save, LocalDate throughDate) -> int` (rounds resolved); `BackgroundCompetitionService.isManaged(UUID competitionId, UUID managedClubId) -> boolean`

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/br/com/carvoeiromanager/season/BackgroundCompetitionIT.java`:

```java
    @Test
    void anAdvanceResolvesEveryBackgroundRoundDueByItsDate() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());

        LocalDate now = saveGameRepository.findById(c.id()).orElseThrow().getCurrentDate();
        List<SeasonEvent> backgroundEvents = seasonEventRepository.findBySaveGameId(c.id()).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> serieAId(c).equals(e.getCompetitionId())).toList();

        assertThat(backgroundEvents.stream().filter(e -> !e.getEventDate().isAfter(now)))
                .allMatch(SeasonEvent::isProcessed);
        assertThat(backgroundEvents.stream().filter(e -> e.getEventDate().isAfter(now)))
                .noneMatch(SeasonEvent::isProcessed);
    }

    @Test
    void backgroundResultsNeverAppearInTheAdvanceResponse() {
        Career c = createCareer();
        AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());

        // The advance stops only on the player's own matches. Every result it reports must belong
        // to a competition the managed club plays in.
        UUID serieA = serieAId(c);
        assertThat(r.results()).noneMatch(res ->
                fixtureRepository.findById(res.fixtureId()).orElseThrow()
                        .getCompetitionId().equals(serieA));
    }

    @Test
    void aResolvedBackgroundRoundIsActuallyPlayed() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());

        List<Fixture> round0 = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), serieAId(c), 0);
        assertThat(round0).hasSize(10).allMatch(Fixture::isPlayed);
        assertThat(round0).allSatisfy(f ->
                assertThat(f.getHomeGoals()).isNotNull());
    }

    @Test
    void resolvingIsIdempotent() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        int again = backgroundCompetitionService.resolveDue(save, save.getCurrentDate());

        assertThat(again).isZero();
    }
```

- [ ] **Step 2: Run to confirm they fail**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*BackgroundCompetitionIT*' ; echo "EXIT=$?"
```

Expected: compilation failure — `BackgroundCompetitionService` does not exist.

- [ ] **Step 3: Implement the service**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.finance.FinanceService;
import br.com.carvoeiromanager.match.FixtureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Simulates competitions the managed club does not play in, catching them up to the player's
 * in-game date after every advance. Their results are never surfaced through the advance response
 * — the player sees them only in that competition's table.
 */
@Service
public class BackgroundCompetitionService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundCompetitionService.class);

    private final SeasonEventRepository seasonEventRepository;
    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;

    public BackgroundCompetitionService(SeasonEventRepository seasonEventRepository,
                                        CompetitionClubRepository competitionClubRepository,
                                        FixtureRepository fixtureRepository,
                                        FixtureSimulator fixtureSimulator,
                                        FinanceService financeService) {
        this.seasonEventRepository = seasonEventRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
    }

    /** True when the managed club has a membership row in this competition. */
    public boolean isManaged(UUID competitionId, UUID managedClubId) {
        return competitionClubRepository.findByCompetitionId(competitionId).stream()
                .anyMatch(cc -> cc.getClubId().equals(managedClubId));
    }

    /**
     * Simulates every unprocessed background round dated on or before {@code throughDate}, in date
     * order. Returns how many rounds were resolved; zero on a repeat call, which is what makes it
     * safe to call after every advance.
     */
    @Transactional
    public int resolveDue(SaveGame save, LocalDate throughDate) {
        UUID saveGameId = save.getId();
        UUID managedClubId = save.getManagedClubId();

        List<SeasonEvent> due = seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId)
                .stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> !e.getEventDate().isAfter(throughDate))
                .filter(e -> !isManaged(e.getCompetitionId(), managedClubId))
                .sorted(Comparator.comparing(SeasonEvent::getEventDate)
                        .thenComparing(SeasonEvent::getSequence))
                .toList();

        for (SeasonEvent event : due) {
            List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                    saveGameId, event.getCompetitionId(), event.getRound());
            fixtureSimulator.simulateRound(save, event.getCompetitionId(), fixtures);
            // Background clubs take matchday finance like anyone else; without it their balances
            // would drift from the divisions the player can see.
            financeService.processMatchday(saveGameId, event.getCompetitionId(), fixtures,
                    event.getEventDate());
            event.markProcessed();
            seasonEventRepository.save(event);
        }
        if (!due.isEmpty()) {
            log.info("Resolved {} background round(s) through {}", due.size(), throughDate);
        }
        return due.size();
    }
}
```

- [ ] **Step 4: Call it from the advance**

In `SeasonAdvanceService`, inject `BackgroundCompetitionService` and call it in `recordMatchRound` immediately after `financeService.processMatchday(...)` and **before** `noMoreMatchEvents(...)`:

```java
        // Catch the background world up to the date the player just reached. Deliberately ignores
        // the return value: background results never enter the advance response.
        backgroundCompetitionService.resolveDue(save, eventDate);
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*BackgroundCompetitionIT*' ; echo "EXIT=$?"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/BackgroundCompetitionService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/BackgroundCompetitionIT.java
git commit -m "feat(season): resolve background competition rounds on each advance"
```

---

### Task 3: Scope season completion to managed competitions

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java:383-386`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/BackgroundCompetitionIT.java`

**Interfaces:**
- Consumes: `BackgroundCompetitionService.isManaged(UUID, UUID)` (Task 2)
- Produces: no new public API

**This is the highest-risk change in the slice.** `noMoreMatchEvents` currently asks whether *any* unprocessed MATCH event remains. Background events now live in the same table, so unscoped it would hold the season open until Série A's final round — a different date from the player's last match. Scope it wrong the other way and seasons end early.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void theSeasonFinishesWhenTheManagedCompetitionsAreDoneEvenIfBackgroundRoundsRemain() {
        Career c = createCareer();
        advanceToFinish(c);

        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.FINISHED);

        // Every managed competition is played out...
        List<SeasonEvent> unprocessed = seasonEventRepository.findBySaveGameId(c.id()).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> !e.isProcessed()).toList();
        assertThat(unprocessed).allSatisfy(e -> assertThat(
                backgroundCompetitionService.isManaged(e.getCompetitionId(), save.getManagedClubId()))
                .isFalse());
    }

    @Test
    void aSeasonWithUnplayedBackgroundRoundsStillRollsOver() {
        Career c = createCareer();
        advanceToFinish(c);

        // Must not throw, and must produce a fresh season.
        rolloverService.rollover(c.id(), c.userId());

        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
    }

    @Test
    void noSeasonRowIsArchivedForABackgroundCompetition() {
        Career c = createCareer();
        advanceToFinish(c);
        rolloverService.rollover(c.id(), c.userId());

        // NOTE: SeasonRepository has no plain findBySaveGameId -- use the ordered finder.
        assertThat(seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id()))
                .noneMatch(s -> s.getCompetitionId().equals(serieAId(c)));
    }
```

- [ ] **Step 2: Run to confirm they fail**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*BackgroundCompetitionIT*' ; echo "EXIT=$?"
```

Expected: FAIL — the season never reaches `FINISHED`, because Série A's later rounds keep `noMoreMatchEvents` false.

- [ ] **Step 3: Scope the completion check**

```java
    /**
     * The season ends when the player has no matches left — background competitions may still have
     * rounds pending, and holding the season open for them would strand the player on a date with
     * nothing to do.
     */
    private boolean noMoreMatchEvents(UUID saveGameId, UUID currentEventId, UUID managedClubId) {
        return seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId).stream()
                .filter(e -> !e.getId().equals(currentEventId))
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .noneMatch(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), managedClubId));
    }
```

and at the call site (line 226):

```java
        boolean seasonFinished = noMoreMatchEvents(saveGameId, event.getId(), save.getManagedClubId());
```

**On prize money:** the spec says `processSeasonEnd` returns `ZERO` for a competition the managed
club is not in. With this task's change that path is in fact unreachable — `processSeasonEnd` is
only ever called from `recordMatchRound`, which processes managed events only. No test is added for
it; note this in your report rather than writing a test that cannot fail.

- [ ] **Step 4: Skip archiving background competitions**

In `SeasonRolloverService`, where each competition is archived (the loop around line 186), skip the ones the managed club is not in:

```java
            // A competition the player has no club in is not part of their career history, and
            // Season.managedClubPosition is NOT NULL with no sensible value here.
            if (!backgroundCompetitionService.isManaged(competition.getId(), managedClubId)) {
                continue;
            }
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*BackgroundCompetitionIT*' --tests '*SeasonAdvance*' --tests '*Rollover*' ; echo "EXIT=$?"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/ \
        backend/src/test/java/br/com/carvoeiromanager/season/BackgroundCompetitionIT.java
git commit -m "feat(season): end the season on the player's last match, not the world's"
```

---

### Task 4: Seed the real 2026 Série A

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json`
- Test: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`

**Interfaces:**
- Consumes: `SeedCompetition(name, type, seasonYear, memberShortNames, phases)` — `phases` is null for a plain league
- Produces: no code API; seed data only

**The club list** (real 2026 Série A): Atlético-MG, Bahia, Botafogo, Bragantino, Corinthians, Cruzeiro, Flamengo, Fluminense, Grêmio, Internacional, Mirassol, Palmeiras, Santos, São Paulo, Vasco, Vitória, plus the four promoted from the 2025 Série B: Athletico-PR, **Chapecoense**, Coritiba, Remo.

**Three overlaps that will bite if missed:**
1. **Chapecoense (`CHA`) already exists** in the pool as a Catarinense club. Do **not** add it again — add its existing short name to Série A's `memberShortNames`. It legitimately plays both competitions; the managed/background split is per competition, not per club.
2. **`BOT` is already Botafogo-SP** (Série B). Botafogo (RJ) is a different club — give it `BFR`.
3. **Coritiba, Remo and Mirassol were deleted from the pool** during the Série B slice as orphans. They come back here as new entries.

Net: **19 new clubs**, pool 31 → 50.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void serieAHasTwentyMembersAndNoPhases() {
        SeedCareer career = seedLoader.load();
        SeedCompetition serieA = career.competitions().stream()
                .filter(c -> c.name().equals("Série A")).findFirst().orElseThrow();

        assertThat(serieA.memberShortNames()).hasSize(20);
        assertThat(serieA.phases()).isNullOrEmpty();   // a plain double round-robin
    }

    @Test
    void chapecoensePlaysBothTheCatarinenseAndSerieA() {
        SeedCareer career = seedLoader.load();

        long membershipCount = career.competitions().stream()
                .filter(c -> c.memberShortNames().contains("CHA")).count();
        assertThat(membershipCount).isEqualTo(2);
        // and it appears exactly once in the pool
        assertThat(career.clubPool().stream().filter(c -> c.shortName().equals("CHA"))).hasSize(1);
    }

    @Test
    void theTwoBotafogosAreDistinctClubs() {
        SeedCareer career = seedLoader.load();

        SeedClub sp = career.clubPool().stream().filter(c -> c.shortName().equals("BOT"))
                .findFirst().orElseThrow();
        SeedClub rj = career.clubPool().stream().filter(c -> c.shortName().equals("BFR"))
                .findFirst().orElseThrow();
        assertThat(sp.name()).isNotEqualTo(rj.name());
        assertThat(sp.state()).isEqualTo("SP");
        assertThat(rj.state()).isEqualTo("RJ");
    }

    @Test
    void thePoolIsFiftyClubsAllWithAuthoredAttributes() {
        SeedCareer career = seedLoader.load();

        assertThat(career.clubPool()).hasSize(50);
        assertThat(career.clubPool()).allSatisfy(club ->
                assertThat(club.players()).allSatisfy(p ->
                        assertThat(p.attributes()).isNotNull()));
    }

    @Test
    void criciumaDoesNotPlayInSerieA() {
        SeedCareer career = seedLoader.load();
        SeedCompetition serieA = career.competitions().stream()
                .filter(c -> c.name().equals("Série A")).findFirst().orElseThrow();

        // The whole point of this slice: a competition the managed club is not in.
        assertThat(serieA.memberShortNames()).doesNotContain("CRI");
    }
```

- [ ] **Step 2: Run to confirm they fail**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeedLoaderTest*' ; echo "EXIT=$?"
```

Expected: FAIL — there is no Série A competition.

- [ ] **Step 3: Add the 19 clubs**

Add 19 entries to `clubPool` in `backend/src/main/resources/seed/clubs.json`, each following the exact shape of an existing Série B club (e.g. `CEA`, `FOR`): full `name`, `shortName`, `commonName`, `nickname`, `stadium`, `city`, `state`, `climateZone`, `stadiumCapacity`, `primaryColor`, `secondaryColor`, `formation`, `mentality`, `pressing`, `marking`, and a `players` array.

Research each club's real identity and a real 2026 squad of ~20–23 players. Every player carries the six anchor values (`pace`, `finishing`, `passing`, `tackling`, `goalkeeping`, `stamina`) **and** a full authored `attributes` block — this is the pool-wide convention, verified across all 31 existing clubs.

**Calibration:** these are the country's top division, so anchors sit **above** the Série B clubs. Criciúma's squad is the existing reference scale; Série A starters should clearly exceed Série B starters, with the biggest clubs (Flamengo, Palmeiras, Cruzeiro) at the top of the range.

`climateZone` follows the club's region — use the values already present on pool clubs from the same state.

- [ ] **Step 4: Add the competition entry**

```json
{
  "name": "Série A",
  "type": "LEAGUE",
  "seasonYear": 2026,
  "memberShortNames": ["CAM", "BAH", "BFR", "BGT", "COR", "CRU", "FLA", "FLU", "GRE", "INT",
                       "MIR", "PAL", "SAN", "SAO", "VAS", "VIT", "CAP", "CHA", "CFC", "REM"]
}
```

No `phases` key — a plain double round-robin, which the format engine already handles unchanged. Use whatever short names you assigned in step 3; the list above is illustrative of the shape, and `CHA` must be the existing Chapecoense.

- [ ] **Step 5: Validate the JSON parses before running any test**

```bash
python3 -c "import json; d=json.load(open('backend/src/main/resources/seed/clubs.json')); print('pool', len(d['clubPool']), 'competitions', len(d['competitions']))"
```

Expected: `pool 50 competitions 3`. A malformed seed breaks every career-creation test at once and the failure is hard to read, so check this first.

- [ ] **Step 6: Run the seed tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeedLoaderTest*' --tests '*SeedResearchValidation*' ; echo "EXIT=$?"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/seed/clubs.json \
        backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java
git commit -m "feat(seed): real 2026 Série A, 19 new clubs"
```

---

### Task 5: Full verification

**Files:**
- Create: `backend/src/test/java/br/com/carvoeiromanager/season/ThreeCompetitionSeasonIT.java`

**Interfaces:**
- Consumes: everything above
- Produces: no production code

- [ ] **Step 1: Write the end-to-end test**

```java
    @Test
    void aFullSeasonRunsThreeCompetitionsWithoutLengtheningThePlayersYear() {
        UUID saveGameId = createCareer();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        UUID managed = save.getManagedClubId();

        LocalDate lastManagedDate = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), managed))
                .map(SeasonEvent::getEventDate).max(LocalDate::compareTo).orElseThrow();

        // The player's season still ends inside the calendar year -- 270 days from 15 January.
        assertThat(lastManagedDate).isBefore(LocalDate.of(2027, 1, 1));

        while (saveGameRepository.findById(saveGameId).orElseThrow().getStatus()
                != SaveGameStatus.FINISHED) {
            advanceService.advance(saveGameId, userId);
        }

        // Série A played out in the background: 380 fixtures, a full 20-club table.
        UUID serieA = competitionRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getName().equals("Série A")).findFirst().orElseThrow().getId();
        List<Fixture> serieAFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, serieA);
        assertThat(serieAFixtures).hasSize(380).allMatch(Fixture::isPlayed);

        List<StandingRow> table = standingsService.computeStandings(
                saveGameRepository.findById(saveGameId).orElseThrow(), serieA, null, null);
        assertThat(table).hasSize(20);
        assertThat(table).allSatisfy(r -> assertThat(r.played()).isEqualTo(38));

        // Zero-sum: every goal scored in the competition was conceded in it.
        assertThat(table.stream().mapToInt(StandingRow::goalsFor).sum())
                .isEqualTo(table.stream().mapToInt(StandingRow::goalsAgainst).sum());
    }
```

- [ ] **Step 2: Run it**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ThreeCompetitionSeasonIT*' ; echo "EXIT=$?"
```

Expected: PASS.

- [ ] **Step 3: Run the full backend suite and record the runtime**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ; echo "GRADLE_EXIT=$?"
```

Expected: PASS. **Note the wall-clock time and compare it to the ~20 minutes before this slice** — 380 extra fixtures per simulated season is the largest single addition to the suite's cost, and the spec flags it as a risk. Report the delta; do not silently accept a large regression.

- [ ] **Step 4: Frontend check**

```bash
cd frontend && npm test -- --run ; echo "EXIT=$?"
cd frontend && npx tsc -b ; echo "EXIT=$?"
```

Expected: PASS with no changes — Série A appears in the existing switcher because it is a competition in the save. If anything fails, a "your club" affordance is assuming the managed club is a member; fix it to degrade to absent.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/season/ThreeCompetitionSeasonIT.java
git commit -m "test(season): full three-competition season end to end"
```

---

## Verification Summary

Before declaring the slice done, all of these must be true **with output you have actually seen**:

- [ ] `./gradlew test` passes in full, with the runtime delta reported
- [ ] The managed season is still 270 days — no managed fixture date moved
- [ ] Série A plays 380 fixtures and finishes with a full 20-club table
- [ ] The season reaches `FINISHED` on the player's last match, not Série A's
- [ ] No `Season` row is archived for Série A
- [ ] `npm test` and `npx tsc -b` pass with no frontend changes
