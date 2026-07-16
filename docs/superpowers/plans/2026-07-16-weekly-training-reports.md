# Weekly Training + Staff Training Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move player training from a once-per-season rollover event to a weekly in-season tick, and have the club's Preparador Físico deliver periodic training reports into the persisted news feed.

**Architecture:** `TrainingService` gains a `processWeek(saveGameId, eventDate)` method mirroring `FinanceService.processMonth`'s idempotent YYYYWW-guard pattern, called from both `SeasonAdvanceService.advance()` and `completeUserMatch()`. Attribute gains accumulate in an integer `training_progress` meter (one unit = one week of bonus-1 training; a point fires every 40 units), exactly preserving the old per-season totals. The managed club's report is returned by `processWeek` and persisted as a new `TRAINING_REPORT` news item rendered by a new branch in `AdvanceEventCard.tsx`.

**Tech Stack:** Java 21/Spring Boot 4, Flyway, Postgres Testcontainers, React 19 + MUI + Vitest.

**Spec:** `docs/superpowers/specs/2026-07-16-weekly-training-reports-design.md`

## Global Constraints

- All UI copy and report messages are **pt-BR**; exact strings are given in each task — use them verbatim.
- `WEEKS_PER_SEASON = 40`; weekly tick adds `intensityBonus (LEVE 1 / NORMAL 2 / INTENSA 3) + staff trainingBonus` progress units; an attribute point fires at 40 units (clamped 1..99 via the existing pattern).
- Weekly injury risk = old per-season risk / 40: LEVE `0.0`, NORMAL `0.03/40`, INTENSA `0.08/40`.
- Only the 6 anchor attributes are trainable (`VELOCIDADE, FINALIZACAO, PASSE, DESARME, REFLEXOS, FOLEGO`); anything else throws `IllegalArgumentException`.
- Free agents (`clubId == null`) and youth prospects (`isYouthProspect()`) never take the weekly tick.
- AI-club players train with `heuristicFocusFor(position)` at `NORMAL`, `managed=false`.
- Reports: managed club only, require a hired `PREPARADOR_FISICO`; caps REGULAR 3 / BOM 6 / EXCELENTE 10; entry priority order evolutions → injuries → ceilings → risky-regime → near-evolution; zero entries ⇒ no news item.
- News type string is exactly `TRAINING_REPORT` (fits `news_item.type` varchar(16)).
- Determinism: weekly RNG is `new Random(saveGameId.getMostSignificantBits() ^ week)` where `week = WEEK_BASED_YEAR*100 + WEEK_OF_WEEK_BASED_YEAR` (`java.time.temporal.IsoFields`); players iterated sorted by id.
- Backend tests: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "<pattern>"`. Frontend: `cd frontend && npx vitest run <file>` and `npx tsc --noEmit`.
- Commit after each task; work on `main`; never push.

---

### Task 1: Data layer — V28 migration + entity fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__weekly_training.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameRolloverTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerTest.java`

**Interfaces:**
- Consumes: existing `SaveGame.lastFinanceMonth` / `Player.resetSeasonState()` patterns.
- Produces: `SaveGame.getLastTrainingWeek()/setLastTrainingWeek(int)`; `Player.getTrainingProgress()/setTrainingProgress(int)`; `resetSeasonState()` zeroes `trainingProgress`; `advanceToNextSeason()` zeroes `lastTrainingWeek`. Task 2 builds on all four.

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V28__weekly_training.sql`:

```sql
ALTER TABLE save_games ADD COLUMN last_training_week INTEGER NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN training_progress INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Write the failing tests**

Append to `SaveGameRolloverTest.java` (same style as the existing `advanceToNextSeasonResetsLastFinanceMonthToZero`):

```java
@Test
void advanceToNextSeasonResetsLastTrainingWeekToZero() {
    // Given a save whose weekly training already ran late into the season
    SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
    save.setLastTrainingWeek(202645); // ISO week 45 of 2026 was the last trained week
    save.setStatus(SaveGameStatus.FINISHED);

    // When advancing to the next season
    save.advanceToNextSeason();

    // Then lastTrainingWeek is reset so the new season trains fresh
    assertThat(save.getLastTrainingWeek()).isEqualTo(0);
}
```

Append to `PlayerTest.java` (match the file's existing imports/factory style — it already tests `Player` behavior; use `Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 24, "Brasil", 50, 50, 50, 50, 50, 50, 50)` if no local factory exists):

```java
@Test
void resetSeasonStateZeroesTrainingProgress() {
    Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID,
            24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
    p.setTrainingProgress(25);

    p.resetSeasonState();

    assertThat(p.getTrainingProgress()).isZero();
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.career.SaveGameRolloverTest" --tests "br.com.carvoeiromanager.player.PlayerTest"`
Expected: COMPILE FAILURE (`setLastTrainingWeek`/`setTrainingProgress` undefined).

- [ ] **Step 4: Add the entity fields**

In `SaveGame.java`, next to the `lastFinanceMonth` field (line ~53):

```java
@Column(name = "last_training_week", nullable = false)
private int lastTrainingWeek = 0;
```

Getters/setters next to the `lastFinanceMonth` pair (line ~106):

```java
public int getLastTrainingWeek() { return lastTrainingWeek; }
public void setLastTrainingWeek(int lastTrainingWeek) { this.lastTrainingWeek = lastTrainingWeek; }
```

In `advanceToNextSeason()` (line ~110), after the `lastFinanceMonth` reset:

```java
this.lastTrainingWeek = 0; // reset so each new season trains weekly from scratch
```

In `Player.java`, next to the `trainingIntensity` field (line ~103):

```java
/** Accumulated weekly training, in fortieths of an attribute point (a point fires at 40). */
@Column(name = "training_progress", nullable = false)
private int trainingProgress = 0;
```

Getter/setter next to `getTrainingIntensity()` (line ~279):

```java
public int getTrainingProgress() { return trainingProgress; }
public void setTrainingProgress(int trainingProgress) { this.trainingProgress = trainingProgress; }
```

In `resetSeasonState()` (line ~253), after `this.seasonAppearances = 0;`:

```java
this.trainingProgress = 0;
```

- [ ] **Step 5: Run tests to verify they pass**

Run: same command as Step 3.
Expected: PASS (all tests in both classes).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V28__weekly_training.sql \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java \
        backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/test/java/br/com/carvoeiromanager/career/SaveGameRolloverTest.java \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerTest.java
git commit -m "feat(training): add last_training_week and training_progress columns"
```

---

### Task 2: TrainingService weekly model + TrainingWeekReport

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java` (full rewrite below)
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingWeekReport.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java` (full rewrite below)

**Interfaces:**
- Consumes: Task 1's `SaveGame.getLastTrainingWeek()/setLastTrainingWeek(int)` and `Player.getTrainingProgress()/setTrainingProgress(int)`; existing `StaffEffectsService.trainingBonusFor(UUID clubId, boolean managed)`, `StaffRepository.findByClubIdAndRole(UUID, StaffRole)` → `Optional<Staff>`, `Staff.getName()/getQuality()`, `Attribute.label()`, `OverallCalculator.compute(...)`, `Player.evolveAttributes(...)/addInjury(int)`.
- Produces: `public TrainingWeekReport processWeek(UUID saveGameId, LocalDate eventDate)` (null when the week was already processed, no physio is hired, or the report is empty — training itself still runs in the last two cases); `TrainingWeekReport(String staffName, String quality, List<TrainingWeekReport.Entry> entries)` with `Entry(String playerName, String message)`; `heuristicFocusFor(Position)` unchanged. **The one-shot `applyTraining(...)` method is deleted** — Task 3 removes its callers; between Task 2 and Task 3 the build would break, so Task 2 only compiles because Task 3's call-site removal happens in the same task? No — to keep every task independently green, Task 2 KEEPS `applyTraining` untouched and only ADDS `processWeek`; Task 3 deletes `applyTraining` together with its rollover call sites.

- [ ] **Step 1: Create the report record**

`backend/src/main/java/br/com/carvoeiromanager/player/TrainingWeekReport.java`:

```java
package br.com.carvoeiromanager.player;

import java.util.List;

/**
 * The Preparador Físico's weekly digest of notable training events at the managed club,
 * produced by {@link TrainingService#processWeek}. {@code quality} is the physio's
 * {@link br.com.carvoeiromanager.staff.StaffQuality} name; the season layer maps this to its
 * own news DTO (kept here as plain strings so the season package owns the API shape).
 */
public record TrainingWeekReport(String staffName, String quality, List<Entry> entries) {

    public record Entry(String playerName, String message) {
    }
}
```

- [ ] **Step 2: Write the failing tests (full file rewrite)**

Replace the entire content of `TrainingServiceTest.java` with:

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class TrainingServiceTest {

    // 2026-03-16 is the Monday of ISO week 12/2026 → week int 202612; +1 week → 202613.
    private static final LocalDate WEEK_12 = LocalDate.of(2026, 3, 16);
    private static final LocalDate WEEK_13 = LocalDate.of(2026, 3, 23);

    private final StaffEffectsService staffEffectsService = Mockito.mock(StaffEffectsService.class);
    private final SaveGameRepository saveGameRepository = Mockito.mock(SaveGameRepository.class);
    private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final TrainingService training = new TrainingService(new OverallCalculator(),
            staffEffectsService, saveGameRepository, playerRepository, staffRepository);

    // Pinned MSB so the weekly RNG is reproducible across runs: new Random(1L ^ 202612) and
    // new Random(1L ^ 202613) both open with nextDouble() == 0.588..., far above every weekly
    // injury risk — no test here can flake on a surprise injury entry. (The injury test below
    // pins its own id the opposite way.)
    private final UUID saveGameId = new UUID(1L, 0L);
    private final UUID managedClubId = UUID.randomUUID();
    private final UUID aiClubId = UUID.randomUUID();

    private SaveGame stubSave(UUID id) {
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(id)).thenReturn(Optional.of(save));
        return save;
    }

    private Player managedPlayer(int attr) {
        return Player.create(saveGameId, managedClubId, "Fulano", Position.MID,
                24, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    private void stubPlayers(Player... players) {
        Mockito.when(playerRepository.findBySaveGameId(saveGameId)).thenReturn(List.of(players));
    }

    private void hirePhysio(StaffQuality quality) {
        Mockito.when(staffRepository.findByClubIdAndRole(managedClubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(Staff.create(saveGameId, managedClubId,
                        StaffRole.PREPARADOR_FISICO, quality, "Preparador Teste", new BigDecimal("45000"))));
    }

    // ---- weekly guard ----

    @Test
    void theSameWeekIsNeverProcessedTwice() {
        // Given a save already ticked for week 202612 by a first call
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(p);
        training.processWeek(saveGameId, WEEK_12);
        int progressAfterFirst = p.getTrainingProgress();

        // When called again with a date in the same ISO week (Friday of week 12)
        TrainingWeekReport second = training.processWeek(saveGameId, LocalDate.of(2026, 3, 20));

        // Then nothing happens: no report, no extra progress
        assertThat(second).isNull();
        assertThat(p.getTrainingProgress()).isEqualTo(progressAfterFirst);
    }

    @Test
    void aNewWeekTicksAndAdvancesTheGuard() {
        SaveGame save = stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);
        training.processWeek(saveGameId, WEEK_13);

        // NORMAL (+2/week, no staff bonus — unstubbed mock int returns 0) over two weeks = 4
        assertThat(p.getTrainingProgress()).isEqualTo(4);
        assertThat(save.getLastTrainingWeek()).isEqualTo(202613);
    }

    // ---- progress accumulation & ticks ----

    @Test
    void fortyProgressUnitsBecomeOnePointOnTheFocusAttribute() {
        // Given a player one LEVE week (+1) away from a full point
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then Finalização ticked +1 and the meter emptied; other anchors untouched
        assertThat(p.getFinishing()).isEqualTo(51);
        assertThat(p.getTrainingProgress()).isZero();
        assertThat(p.getPace()).isEqualTo(50);
        assertThat(p.getPassing()).isEqualTo(50);
    }

    @Test
    void theRemainderCarriesOverAfterATick() {
        // Given a NORMAL (+2) player at 39/40
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then 39+2=41 → one tick fires, 1 unit remains
        assertThat(p.getPassing()).isEqualTo(51);
        assertThat(p.getTrainingProgress()).isEqualTo(1);
    }

    @Test
    void theFocusAttributeIsClampedAtNinetyNine() {
        // Given a maxed player whose meter is about to fire
        stubSave(saveGameId);
        Player p = managedPlayer(99);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then the tick is consumed but the attribute stays capped
        assertThat(p.getFinishing()).isEqualTo(99);
        assertThat(p.getTrainingProgress()).isEqualTo(1);
    }

    @Test
    void overallIsRecomputedWhenATickFires() {
        stubSave(saveGameId);
        Player p = Player.create(saveGameId, managedClubId, "Fulano", Position.ATT,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);
        int overallBefore = p.getOverall();

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getOverall()).isGreaterThan(overallBefore);
    }

    // ---- who trains ----

    @Test
    void aManagedPlayerWithNoFocusIsUntouched() {
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getTrainingProgress()).isZero();
        assertThat(p.getPassing()).isEqualTo(50);
    }

    @Test
    void freeAgentsAndYouthProspectsAreSkipped() {
        stubSave(saveGameId);
        Player freeAgent = Player.create(saveGameId, null, "Livre", Position.MID,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        Player prospect = managedPlayer(50);
        prospect.markAsProspect(80);
        prospect.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(freeAgent, prospect);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(freeAgent.getTrainingProgress()).isZero();
        assertThat(prospect.getTrainingProgress()).isZero();
    }

    @Test
    void aiClubPlayersTrainWithTheHeuristicFocusAtNormal() {
        // Given an AI-club attacker and the AI baseline coach bonus (+1)
        stubSave(saveGameId);
        Mockito.when(staffEffectsService.trainingBonusFor(any(), eq(false))).thenReturn(1);
        Player ai = Player.create(saveGameId, aiClubId, "Adversário", Position.ATT,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        stubPlayers(ai);

        training.processWeek(saveGameId, WEEK_12);

        // Then NORMAL (+2) + baseline (+1) accumulated toward FINALIZACAO (the ATT heuristic)
        assertThat(ai.getTrainingProgress()).isEqualTo(3);
    }

    @Test
    void theCoachBonusAcceleratesTheManagedWeeklyRate() {
        // Given an Excelente coach (+2) on top of LEVE (+1)
        stubSave(saveGameId);
        Mockito.when(staffEffectsService.trainingBonusFor(managedClubId, true)).thenReturn(2);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getTrainingProgress()).isEqualTo(3);
    }

    // ---- weekly injury roll ----

    @Test
    void leveNeverRollsATrainingInjury() {
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FOLEGO, TrainingIntensity.LEVE);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void intensaCanRollATrainingInjury() {
        // Given a save-game id hand-picked so the weekly RNG's first draw is under Intensa's
        // 0.002 weekly risk: new Random(9824L ^ 202612).nextDouble() == 0.000457...; the
        // following nextInt(2) draws 0, so the layoff is exactly 1 match.
        UUID pickedId = new UUID(9824L, 0L);
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(pickedId)).thenReturn(Optional.of(save));
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FOLEGO, TrainingIntensity.INTENSA);
        Mockito.when(playerRepository.findBySaveGameId(pickedId)).thenReturn(List.of(p));

        training.processWeek(pickedId, WEEK_12);

        assertThat(p.getInjuryMatches()).isEqualTo(1);
    }

    // ---- the report ----

    @Test
    void noPhysioMeansTrainingStillRunsButNoReport() {
        // Given an evolving player and NO Preparador Físico (mock Optional default = empty)
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNull();
        assertThat(p.getPassing()).isEqualTo(51); // the tick still happened
    }

    @Test
    void aQuietWeekProducesNoReportEvenWithAPhysio() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.EXCELENTE);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE); // 0+1=1 unit: nothing notable
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNull();
    }

    @Test
    void regularPhysioReportsAnEvolutionVaguely() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.REGULAR);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNotNull();
        assertThat(report.staffName()).isEqualTo("Preparador Teste");
        assertThat(report.quality()).isEqualTo("REGULAR");
        assertThat(report.entries()).singleElement().satisfies(e -> {
            assertThat(e.playerName()).isEqualTo("Fulano");
            assertThat(e.message()).isEqualTo("Fulano evoluiu nos treinos.");
        });
    }

    @Test
    void bomPhysioNamesTheAttributeAndExcelenteAddsTheValues() {
        stubSave(saveGameId);
        Player bomPlayer = managedPlayer(50);
        bomPlayer.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        bomPlayer.setTrainingProgress(39);
        stubPlayers(bomPlayer);
        hirePhysio(StaffQuality.BOM);

        TrainingWeekReport bom = training.processWeek(saveGameId, WEEK_12);
        assertThat(bom.entries().get(0).message()).isEqualTo("Fulano evoluiu em Passe.");

        // Fresh scenario for EXCELENTE (new save id → fresh guard)
        UUID otherId = UUID.randomUUID();
        SaveGame other = SaveGame.create(UUID.randomUUID(), 2026);
        other.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(otherId)).thenReturn(Optional.of(other));
        Player excPlayer = managedPlayer(50);
        excPlayer.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        excPlayer.setTrainingProgress(39);
        Mockito.when(playerRepository.findBySaveGameId(otherId)).thenReturn(List.of(excPlayer));
        hirePhysio(StaffQuality.EXCELENTE);

        TrainingWeekReport exc = training.processWeek(otherId, WEEK_12);
        assertThat(exc.entries().get(0).message()).isEqualTo("Fulano evoluiu em Passe (50 → 51).");
    }

    @Test
    void aCeilingTickSuggestsSwappingTheFocus() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.BOM);
        Player p = managedPlayer(99);
        p.setTrainingAssignment(Attribute.VELOCIDADE, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo(
                        "Fulano não tem mais o que evoluir em Velocidade — considere trocar o foco."));
    }

    @Test
    void intensaOnAVeteranEmitsARiskWarning() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.BOM);
        Player veteran = Player.create(saveGameId, managedClubId, "Veterano", Position.DEF,
                32, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        veteran.setTrainingAssignment(Attribute.DESARME, TrainingIntensity.INTENSA);
        stubPlayers(veteran);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo(
                        "Treino intenso para Veterano (32 anos) traz alto risco de lesão."));
    }

    @Test
    void excelenteAddsNearEvolutionLines() {
        // Given progress that lands at 20/40 (half a point) with no tick this week
        stubSave(saveGameId);
        hirePhysio(StaffQuality.EXCELENTE);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.NORMAL);
        p.setTrainingProgress(18); // 18 + 2 = 20
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo("Fulano está perto de evoluir Finalização."));
    }

    @Test
    void entriesAreCappedByQualityInPriorityOrder() {
        // Given a REGULAR physio (cap 3) and four players all evolving this week
        stubSave(saveGameId);
        hirePhysio(StaffQuality.REGULAR);
        Player[] four = new Player[4];
        for (int i = 0; i < 4; i++) {
            four[i] = Player.create(saveGameId, managedClubId, "Atleta" + i, Position.MID,
                    24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
            four[i].setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
            four[i].setTrainingProgress(39);
        }
        stubPlayers(four);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).hasSize(3);
        assertThat(report.entries()).allSatisfy(e ->
                assertThat(e.message()).endsWith("evoluiu nos treinos."));
    }

    // ---- heuristic (unchanged) ----

    @Test
    void heuristicFocusMapsEachPositionToItsMostValuableAnchor() {
        assertThat(TrainingService.heuristicFocusFor(Position.GK)).isEqualTo(Attribute.REFLEXOS);
        assertThat(TrainingService.heuristicFocusFor(Position.DEF)).isEqualTo(Attribute.DESARME);
        assertThat(TrainingService.heuristicFocusFor(Position.MID)).isEqualTo(Attribute.PASSE);
        assertThat(TrainingService.heuristicFocusFor(Position.ATT)).isEqualTo(Attribute.FINALIZACAO);
    }
}
```

Note on Mockito defaults: unstubbed `int`-returning mocks return `0` (used for the "no staff bonus" cases) and unstubbed `Optional`-returning mocks return `Optional.empty()` (used for the "no physio" cases) — both intentional here.

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: COMPILE FAILURE (new constructor arity, `processWeek` and `TrainingWeekReport` undefined).

- [ ] **Step 4: Implement processWeek**

Replace `TrainingService.java` entirely with (note: `applyTraining` is KEPT verbatim so `SeasonRolloverService` still compiles — Task 3 deletes it):

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Weekly, manager-directed training. Once per in-game ISO week ({@link #processWeek}, guarded by
 * {@code SaveGame.lastTrainingWeek} exactly like {@code FinanceService.processMonth} guards
 * monthly costs), every contracted senior player accumulates progress toward +1 on their focus
 * anchor attribute; a point fires every {@value #WEEKS_PER_SEASON} units so the expected
 * per-season total matches the old one-shot rollover bonus. The managed club's Preparador
 * Físico (when hired) turns the week's notable events into a {@link TrainingWeekReport}.
 */
@Component
public class TrainingService {

    /** Nominal training weeks per season; one progress unit = one week of bonus-1 training. */
    static final int WEEKS_PER_SEASON = 40;

    // Weekly injury risk = the old per-season risk (LEVE 0, NORMAL 0.03, INTENSA 0.08) / 40.
    private static final double NORMAL_WEEKLY_RISK = 0.03 / WEEKS_PER_SEASON;
    private static final double INTENSA_WEEKLY_RISK = 0.08 / WEEKS_PER_SEASON;

    /** EXCELENTE physios flag players within half a point of their next evolution. */
    private static final int NEAR_EVOLUTION_THRESHOLD = WEEKS_PER_SEASON / 2;

    /** INTENSA training at this age or older draws a risk warning in the report. */
    private static final int VETERAN_AGE = 30;

    private final OverallCalculator overallCalculator;
    private final StaffEffectsService staffEffectsService;
    private final SaveGameRepository saveGameRepository;
    private final PlayerRepository playerRepository;
    private final StaffRepository staffRepository;

    public TrainingService(OverallCalculator overallCalculator, StaffEffectsService staffEffectsService,
                           SaveGameRepository saveGameRepository, PlayerRepository playerRepository,
                           StaffRepository staffRepository) {
        this.overallCalculator = overallCalculator;
        this.staffEffectsService = staffEffectsService;
        this.saveGameRepository = saveGameRepository;
        this.playerRepository = playerRepository;
        this.staffRepository = staffRepository;
    }

    /**
     * Applies one weekly training tick to every contracted senior player in the save and returns
     * the Preparador Físico's report of the managed club's notable events — or null when the
     * event date's ISO week was already processed, no physio is hired, or nothing notable
     * happened (training itself always runs on a new week).
     */
    @Transactional
    public TrainingWeekReport processWeek(UUID saveGameId, LocalDate eventDate) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        int week = eventDate.get(IsoFields.WEEK_BASED_YEAR) * 100
                + eventDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        // Self-idempotency guard: one tick per ISO week, mirroring FinanceService.processMonth.
        if (week <= save.getLastTrainingWeek()) {
            return null;
        }
        save.setLastTrainingWeek(week);
        saveGameRepository.save(save);

        final UUID managedClubId = save.getManagedClubId();
        // Seeded per (save, week) and consumed in sorted-player order: reproducible rolls.
        Random rng = new Random(saveGameId.getMostSignificantBits() ^ week);
        // Both coach bonuses are constant for the whole week — resolve once, not per player.
        int managedBonus = staffEffectsService.trainingBonusFor(managedClubId, true);
        int aiBonus = staffEffectsService.trainingBonusFor(managedClubId, false);

        Optional<Staff> physio = staffRepository
                .findByClubIdAndRole(managedClubId, StaffRole.PREPARADOR_FISICO);
        StaffQuality reportQuality = physio.map(Staff::getQuality).orElse(null);

        // Notable-event buckets in report priority order.
        List<TrainingWeekReport.Entry> evolutions = new ArrayList<>();
        List<TrainingWeekReport.Entry> injuries = new ArrayList<>();
        List<TrainingWeekReport.Entry> ceilings = new ArrayList<>();
        List<TrainingWeekReport.Entry> risky = new ArrayList<>();
        List<TrainingWeekReport.Entry> nearEvolutions = new ArrayList<>();

        List<Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect())
                .sorted(Comparator.comparing(Player::getId))
                .toList();

        for (Player p : players) {
            boolean managed = p.getClubId().equals(managedClubId);
            Attribute focus = managed ? p.getTrainingFocus() : heuristicFocusFor(p.getPosition());
            TrainingIntensity intensity = managed ? p.getTrainingIntensity() : TrainingIntensity.NORMAL;
            if (focus == null) {
                continue; // "not training" leaves the player untouched
            }
            int bonus = intensityBonus(intensity) + (managed ? managedBonus : aiBonus);

            int before = anchorValue(p, focus);
            int after = before;
            boolean atCeiling = false;
            int progress = p.getTrainingProgress() + bonus;
            while (progress >= WEEKS_PER_SEASON) {
                progress -= WEEKS_PER_SEASON;
                if (after < 99) {
                    after++;
                } else {
                    atCeiling = true; // the point is lost at the cap
                }
            }
            p.setTrainingProgress(progress);
            if (after != before) {
                applyAnchor(p, focus, after);
            }

            double risk = switch (intensity) {
                case LEVE -> 0.0;
                case NORMAL -> NORMAL_WEEKLY_RISK;
                case INTENSA -> INTENSA_WEEKLY_RISK;
            };
            int layoff = 0;
            if (risk > 0 && rng.nextDouble() < risk) {
                layoff = 1 + rng.nextInt(2);
                p.addInjury(layoff);
            }

            if (managed && reportQuality != null) {
                if (after != before) {
                    evolutions.add(new TrainingWeekReport.Entry(p.getName(),
                            evolutionMessage(reportQuality, p.getName(), focus, before, after)));
                }
                if (layoff > 0) {
                    injuries.add(new TrainingWeekReport.Entry(p.getName(),
                            p.getName() + " se lesionou durante os treinos (fora por " + layoff + " partida(s))."));
                }
                if (atCeiling) {
                    ceilings.add(new TrainingWeekReport.Entry(p.getName(),
                            ceilingMessage(reportQuality, p.getName(), focus)));
                }
                if (intensity == TrainingIntensity.INTENSA && p.getAge() >= VETERAN_AGE) {
                    risky.add(new TrainingWeekReport.Entry(p.getName(),
                            "Treino intenso para " + p.getName() + " (" + p.getAge() + " anos) traz alto risco de lesão."));
                }
                if (reportQuality == StaffQuality.EXCELENTE && after == before && !atCeiling
                        && progress >= NEAR_EVOLUTION_THRESHOLD) {
                    nearEvolutions.add(new TrainingWeekReport.Entry(p.getName(),
                            p.getName() + " está perto de evoluir " + focus.label() + "."));
                }
            }
        }
        playerRepository.saveAll(players);

        if (reportQuality == null) {
            return null;
        }
        int cap = switch (reportQuality) {
            case REGULAR -> 3;
            case BOM -> 6;
            case EXCELENTE -> 10;
        };
        List<TrainingWeekReport.Entry> entries = new ArrayList<>();
        entries.addAll(evolutions);
        entries.addAll(injuries);
        entries.addAll(ceilings);
        entries.addAll(risky);
        entries.addAll(nearEvolutions);
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() > cap) {
            entries = new ArrayList<>(entries.subList(0, cap));
        }
        return new TrainingWeekReport(physio.get().getName(), reportQuality.name(), List.copyOf(entries));
    }

    /** Fixed position-based focus used for every AI-controlled club's players. */
    public static Attribute heuristicFocusFor(Position position) {
        return switch (position) {
            case GK -> Attribute.REFLEXOS;
            case DEF -> Attribute.DESARME;
            case MID -> Attribute.PASSE;
            case ATT -> Attribute.FINALIZACAO;
        };
    }

    private static int intensityBonus(TrainingIntensity intensity) {
        return switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };
    }

    private static String evolutionMessage(StaffQuality quality, String name, Attribute focus,
                                           int before, int after) {
        return switch (quality) {
            case REGULAR -> name + " evoluiu nos treinos.";
            case BOM -> name + " evoluiu em " + focus.label() + ".";
            case EXCELENTE -> name + " evoluiu em " + focus.label() + " (" + before + " → " + after + ").";
        };
    }

    private static String ceilingMessage(StaffQuality quality, String name, Attribute focus) {
        return quality == StaffQuality.REGULAR
                ? name + " não tem mais o que evoluir — considere trocar o foco."
                : name + " não tem mais o que evoluir em " + focus.label() + " — considere trocar o foco.";
    }

    private static int anchorValue(Player p, Attribute focus) {
        return switch (focus) {
            case VELOCIDADE -> p.getPace();
            case FINALIZACAO -> p.getFinishing();
            case PASSE -> p.getPassing();
            case DESARME -> p.getTackling();
            case REFLEXOS -> p.getGoalkeeping();
            case FOLEGO -> p.getStamina();
            default -> throw new IllegalArgumentException("Attribute is not trainable: " + focus);
        };
    }

    private void applyAnchor(Player p, Attribute focus, int value) {
        int pace = p.getPace();
        int finishing = p.getFinishing();
        int passing = p.getPassing();
        int tackling = p.getTackling();
        int goalkeeping = p.getGoalkeeping();
        int stamina = p.getStamina();
        switch (focus) {
            case VELOCIDADE -> pace = value;
            case FINALIZACAO -> finishing = value;
            case PASSE -> passing = value;
            case DESARME -> tackling = value;
            case REFLEXOS -> goalkeeping = value;
            case FOLEGO -> stamina = value;
            default -> throw new IllegalArgumentException("Attribute is not trainable: " + focus);
        }
        int overall = overallCalculator.compute(p.getPosition(),
                pace, finishing, passing, tackling, goalkeeping, stamina);
        p.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    // ------------------------------------------------------------------
    // Legacy one-shot rollover training — deleted in the next task together
    // with its SeasonRolloverService call sites (kept here so the build stays
    // green between tasks).
    // ------------------------------------------------------------------

    /** No-op when {@code focus} is null — "not training" must leave the player untouched. */
    public void applyTraining(Player player, Attribute focus, TrainingIntensity intensity, Random rng,
                              UUID clubId, boolean managed) {
        if (focus == null) {
            return;
        }

        int bonus = intensityBonus(intensity);
        bonus += staffEffectsService.trainingBonusFor(clubId, managed);

        applyAnchor(player, focus, clamp(anchorValue(player, focus) + bonus));

        double risk = switch (intensity) {
            case LEVE -> 0.0;
            case NORMAL -> 0.03;
            case INTENSA -> 0.08;
        };
        if (rng.nextDouble() < risk) {
            player.addInjury(1 + rng.nextInt(2));
        }
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: PASS (21 tests). Also run `--tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT"` to confirm the kept `applyTraining` still satisfies the (not-yet-rewritten) rollover ITs.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java \
        backend/src/main/java/br/com/carvoeiromanager/player/TrainingWeekReport.java \
        backend/src/test/java/br/com/carvoeiromanager/player/TrainingServiceTest.java
git commit -m "feat(training): weekly processWeek tick with physio report generation"
```

---

### Task 3: Remove rollover training

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java` (lines ~252-260 + constructor/field/import)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java` (delete the legacy block)
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java`

**Interfaces:**
- Consumes: Task 1's `Player.getTrainingProgress()/setTrainingProgress(int)` (for the rewritten IT).
- Produces: `SeasonRolloverService` no longer references `TrainingService` at all; `TrainingService.applyTraining` and its private `clamp` are gone.

- [ ] **Step 1: Rewrite the rollover training ITs**

In `SeasonRolloverServiceIT.java`:

1. **Replace** `trainingFocusBoostsAManagedPlayerAndPersistsAcrossRollover` (line ~293) entirely with:

```java
@Test
void aTrainingAssignmentSurvivesRolloverAndTheProgressMeterResets() {
    // Given a managed player mid-way toward his next evolution. Training no longer applies at
    // rollover (it is a weekly in-season tick — see TrainingService.processWeek); rollover's
    // only training-related contract is: the standing assignment persists, the meter resets.
    Career c = createCareer();
    Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
    trainee.setAge(24);
    trainee.setContractYears(5);
    trainee.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.INTENSA);
    trainee.setTrainingProgress(25);
    playerRepository.save(trainee);

    List<Player> others = playerRepository.findBySaveGameId(c.id()).stream()
            .filter(p -> !p.getId().equals(trainee.getId())).toList();
    others.forEach(p -> p.setContractYears(5));
    playerRepository.saveAll(others);

    finishSeason(c);

    // When rolling over
    rolloverService.rollover(c.id(), c.userId());

    // Then the assignment is untouched and the partial progress was zeroed
    Player after = playerRepository.findById(trainee.getId()).orElseThrow();
    assertThat(after.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
    assertThat(after.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
    assertThat(after.getTrainingProgress()).isZero();
}
```

2. **Delete** `aiClubPlayersGetTheHeuristicFocusEvenWithoutManagerInput` (line ~331) entirely — AI weekly training is now covered by `TrainingServiceTest.aiClubPlayersTrainWithTheHeuristicFocusAtNormal`.

3. **Keep** `aManagedPlayerWithNoFocusKeepsItNullAfterRollover` unchanged.

- [ ] **Step 2: Run the IT to verify the new test fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT"`
Expected: `aTrainingAssignmentSurvivesRolloverAndTheProgressMeterResets` FAILS — rollover still calls `applyTraining` and `resetSeasonState()` already zeroes progress, BUT the INTENSA training may also roll an injury… actually the failure mode is not guaranteed here: `resetSeasonState` (Task 1) already zeroes the meter, so this test may PASS before the removal. That is fine — this step documents current behavior; the REAL red/green is Step 3's compile check. If it passes, note it and continue.

- [ ] **Step 3: Remove the rollover call sites and the legacy method**

In `SeasonRolloverService.java`:
- Delete the training block inside the carried-over branch (lines ~252-260), leaving:

```java
            // Carried over: reset per-season state, then tick the contract.
            player.resetSeasonState();
            if (!freeAgent) {
```

- Remove the `trainingService` field, its constructor parameter/assignment, and the `TrainingService`/`TrainingIntensity` imports if now unused (check `TrainingIntensity` — it may still be referenced elsewhere in the file; remove only if unused).

In `TrainingService.java`:
- Delete the entire legacy block: the `applyTraining` method, the `clamp` method, and the "Legacy one-shot rollover training" banner comment. Also update the class javadoc's first sentence if it still references rollover-time application.

- [ ] **Step 4: Run the affected suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT" --tests "br.com.carvoeiromanager.player.TrainingServiceTest"`
Expected: PASS. `grep -rn "applyTraining" backend/src` returns nothing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java
git commit -m "feat(training): rollover no longer trains — weekly ticks own it now"
```

---

### Task 4: Season DTOs + advance wiring + TRAINING_REPORT news

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportEntry.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Test: Create `backend/src/test/java/br/com/carvoeiromanager/season/TrainingReportNewsIT.java`

**Interfaces:**
- Consumes: Task 2's `TrainingService.processWeek(UUID, LocalDate)` → `TrainingWeekReport(String staffName, String quality, List<Entry(playerName, message)>)` or null.
- Produces: `AdvanceResponse` gains a 14th (final) component `TrainingReportResponse trainingReport`; news items of type `TRAINING_REPORT`; `TrainingReportResponse(String staffName, String quality, List<TrainingReportEntry> entries)`; `TrainingReportEntry(String playerName, String message)`. Task 5's frontend types mirror these exactly.

- [ ] **Step 1: Write the failing IT**

`backend/src/test/java/br/com/carvoeiromanager/season/TrainingReportNewsIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.TrainingIntensity;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the weekly training report reaches the persisted news feed as its own
 * TRAINING_REPORT item, and that reporting stays gated on a hired Preparador Físico.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TrainingReportNewsIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired StaffRepository staffRepository;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("trainrep_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void anEvolutionWeekPersistsATrainingReportNewsItem() throws Exception {
        // Given a hired EXCELENTE physio (also grants coach bonus +2) and a managed player one
        // unit from evolving: LEVE(+1) + coach(+2) pushes 39 → 42, guaranteeing an evolution.
        Career c = createCareer();
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO,
                StaffQuality.EXCELENTE, "Preparador Teste", new BigDecimal("80000")));
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        trainee.setTrainingProgress(39);
        playerRepository.save(trainee);

        // When advancing once (the first advance always crosses into a fresh training week)
        seasonAdvanceService.advance(c.id(), c.userId());

        // Then a TRAINING_REPORT news item was persisted alongside the advance's own item
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        NewsItem reportItem = items.stream()
                .filter(i -> i.getType().equals("TRAINING_REPORT"))
                .findFirst().orElseThrow();

        // And its payload round-trips with the report populated and pt-BR message text
        AdvanceResponse payload = objectMapper.readValue(reportItem.getPayload(), AdvanceResponse.class);
        assertThat(payload.type()).isEqualTo("TRAINING_REPORT");
        assertThat(payload.trainingReport()).isNotNull();
        assertThat(payload.trainingReport().staffName()).isEqualTo("Preparador Teste");
        assertThat(payload.trainingReport().quality()).isEqualTo("EXCELENTE");
        assertThat(payload.trainingReport().entries())
                .anySatisfy(e -> assertThat(e.message()).contains("evoluiu em Passe"));
    }

    @Test
    void noPhysioMeansNoTrainingReportItem() {
        // Given the same evolving player but no staff hired at all
        Career c = createCareer();
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        trainee.setTrainingProgress(39);
        playerRepository.save(trainee);

        // When advancing once
        seasonAdvanceService.advance(c.id(), c.userId());

        // Then no TRAINING_REPORT item exists (the tick itself still ran)
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(items).noneMatch(i -> i.getType().equals("TRAINING_REPORT"));
        Player after = playerRepository.findById(trainee.getId()).orElseThrow();
        assertThat(after.getPassing()).isGreaterThanOrEqualTo(trainee.getPassing());
        assertThat(after.getTrainingProgress()).isLessThan(39); // meter consumed by the tick
    }

    @Test
    void oldNewsPayloadsWithoutTheFieldStillDeserialize() throws Exception {
        // Given a pre-feature MATCH payload with no trainingReport key
        String legacy = """
                {"type":"MATCH","date":"2026-03-01","competitionName":"Camp","round":0,
                 "results":[],"bookings":[],"newSuspensions":[],"newInjuries":[],
                 "finance":null,"bidsReceived":0,"seasonFinished":false}
                """;

        AdvanceResponse parsed = objectMapper.readValue(legacy, AdvanceResponse.class);

        assertThat(parsed.trainingReport()).isNull();
    }
}
```

- [ ] **Step 2: Run the IT to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.TrainingReportNewsIT"`
Expected: COMPILE FAILURE (`AdvanceResponse.trainingReport()` undefined).

- [ ] **Step 3: Add the DTOs and the AdvanceResponse field**

`backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportEntry.java`:

```java
package br.com.carvoeiromanager.season;

/** One notable line in the Preparador Físico's weekly training report. */
public record TrainingReportEntry(String playerName, String message) {
}
```

`backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportResponse.java`:

```java
package br.com.carvoeiromanager.season;

import java.util.List;

/**
 * The Preparador Físico's weekly training digest carried by a {@code TRAINING_REPORT}
 * news item. {@code quality} is the physio's StaffQuality name (REGULAR/BOM/EXCELENTE).
 */
public record TrainingReportResponse(String staffName, String quality, List<TrainingReportEntry> entries) {
}
```

In `AdvanceResponse.java`, add the final component and extend the javadoc:

```java
public record AdvanceResponse(
        String type,
        String date,
        String competitionName,
        Integer round,
        List<FixtureResultResponse> results,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions,
        List<InjuryResponse> newInjuries,
        FinanceRoundResponse finance,
        int bidsReceived,
        boolean seasonFinished,
        List<PlayerRating> playerRatings,
        List<CompletedTransferResponse> transfers,
        TrainingReportResponse trainingReport) {
}
```

Add to the record's javadoc list (after the `transfers` paragraph):

```
 * {@code trainingReport} is populated only on {@code TRAINING_REPORT} items (the Preparador
 * Físico's weekly digest); it deserializes as {@code null} from every older payload and from
 * MATCH/TRANSFER/FINISHED items — callers must null-guard it.
```

And a fourth bullet in the type list:

```
 *   <li>{@code TRAINING_REPORT} — only {@code date} and {@code trainingReport} are meaningful;
 *       persisted as its own news item when the weekly training tick produced notable events.</li>
```

- [ ] **Step 4: Wire SeasonAdvanceService**

In `SeasonAdvanceService.java`:

1. Add the import and constructor dependency (field + parameter + assignment, matching the existing style):

```java
import br.com.carvoeiromanager.player.TrainingService;
import br.com.carvoeiromanager.player.TrainingWeekReport;
```

```java
    private final TrainingService trainingService;
```

(constructor gains `TrainingService trainingService` and `this.trainingService = trainingService;`.)

2. Update the three existing `new AdvanceResponse(...)` sites to pass the new final argument:
   - FINISHED early-return (line ~115): append `, null` → `..., List.of(), List.of(), null);`
   - `recordMatchRound` (line ~192): append `, null` after `List.of()`
   - `processTransfer` (line ~248): append `, null` after `transfers`

3. In `advance()`, right after `financeService.processMonth(saveGameId, eventDate);` (line ~126):

```java
        // Weekly training: tick every club's training once per in-game ISO week. processWeek
        // self-guards like processMonth; when the physio produced a report, persist it as its
        // own TRAINING_REPORT feed item (lower seq than the event's item → shows as older news).
        maybePersistTrainingReport(saveGameId, eventDate);
```

4. In `completeUserMatch()`, right after its `financeService.processMonth(saveGameId, eventDate);` (line ~213), add the same call:

```java
        maybePersistTrainingReport(saveGameId, eventDate);
```

5. Add the private helper next to `persistNewsItem`:

```java
    /** Runs the weekly training tick and persists the physio's report (when any) as news. */
    private void maybePersistTrainingReport(UUID saveGameId, LocalDate eventDate) {
        TrainingWeekReport report = trainingService.processWeek(saveGameId, eventDate);
        if (report == null) {
            return;
        }
        TrainingReportResponse dto = new TrainingReportResponse(report.staffName(), report.quality(),
                report.entries().stream()
                        .map(e -> new TrainingReportEntry(e.playerName(), e.message()))
                        .toList());
        AdvanceResponse response = new AdvanceResponse("TRAINING_REPORT", eventDate.toString(),
                null, null, List.of(), List.of(), List.of(), List.of(), null, 0, false,
                List.of(), List.of(), dto);
        persistNewsItem(saveGameId, eventDate, response);
    }
```

6. Update the class javadoc's "Monthly finance" section heading area with one extra sentence:

```
 * <h3>Weekly training</h3>
 * Likewise, before processing each event the weekly training tick runs once per in-game ISO
 * week ({@code save.lastTrainingWeek}, a YYYYWW int) via {@link TrainingService#processWeek};
 * when the managed club's Preparador Físico produced a report it is persisted as its own
 * {@code TRAINING_REPORT} news item.
```

- [ ] **Step 5: Run the IT and neighbors to verify green**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.season.TrainingReportNewsIT" --tests "br.com.carvoeiromanager.season.NewsFeedIT" --tests "br.com.carvoeiromanager.season.SeasonAdvanceServiceIT"`
Expected: PASS. Note: `NewsFeedIT.advancingWritesANewsItemAndFeedIsNewestFirst` asserts exactly 1 item after the first advance — it stays green because that career hires no physio, so no report item is created.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/TrainingReportEntry.java \
        backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/TrainingReportNewsIT.java
git commit -m "feat(training): persist the physio's weekly report as a TRAINING_REPORT news item"
```

---

### Task 5: Frontend — TRAINING_REPORT card

**Files:**
- Modify: `frontend/src/api/types.ts` (AdvanceResponse, line ~266)
- Modify: `frontend/src/features/match/AdvanceEventCard.tsx`
- Test: `frontend/src/features/match/AdvanceEventCard.test.tsx`

**Interfaces:**
- Consumes: Task 4's payload shape — `type: 'TRAINING_REPORT'`, `trainingReport: { staffName, quality, entries: [{ playerName, message }] }`; existing `StaffQuality` type in `types.ts`.
- Produces: nothing downstream — the News page renders `AdvanceEventCard` as-is.

- [ ] **Step 1: Write the failing tests**

Append to `AdvanceEventCard.test.tsx` (after the FINISHED describe block), and add the fixture next to the others:

```tsx
const TRAINING_REPORT_RESULT: AdvanceResponse = {
  type: 'TRAINING_REPORT',
  date: '2026-03-16',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 0,
  seasonFinished: false,
  trainingReport: {
    staffName: 'Preparador Teste',
    quality: 'EXCELENTE',
    entries: [
      { playerName: 'Fulano', message: 'Fulano evoluiu em Passe (50 → 51).' },
      { playerName: 'Beltrano', message: 'Beltrano está perto de evoluir Finalização.' },
    ],
  },
}
```

```tsx
  describe('TRAINING_REPORT type', () => {
    it('renders the report header and the formatted date', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText('Relatório de Treino')).toBeInTheDocument()
      expect(screen.getByText(/16\/03\/2026/)).toBeInTheDocument()
    })

    it('attributes the report to the physio with the quality label', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText(/Preparador Físico Preparador Teste · Excelente/)).toBeInTheDocument()
    })

    it('renders every entry message', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText('Fulano evoluiu em Passe (50 → 51).')).toBeInTheDocument()
      expect(screen.getByText('Beltrano está perto de evoluir Finalização.')).toBeInTheDocument()
    })

    it('renders nothing report-specific when trainingReport is null (defensive)', () => {
      renderCard({ ...TRAINING_REPORT_RESULT, trainingReport: null })
      expect(screen.getByText('Relatório de Treino')).toBeInTheDocument()
      expect(screen.queryByText(/Preparador Físico/)).not.toBeInTheDocument()
    })
  })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/match/AdvanceEventCard.test.tsx`
Expected: FAIL — TypeScript error on `type: 'TRAINING_REPORT'` (not in the union) / missing `trainingReport` property.

- [ ] **Step 3: Extend the types**

In `frontend/src/api/types.ts`, extend `AdvanceResponse` (line ~266):

```ts
export type TrainingReportEntry = {
  playerName: string
  message: string
}

export type TrainingReportResponse = {
  staffName: string
  quality: StaffQuality
  entries: TrainingReportEntry[]
}

export type AdvanceResponse = {
  type: 'MATCH' | 'TRANSFER' | 'FINISHED' | 'TRAINING_REPORT'
  date: string | null
  competitionName: string | null
  round: number | null
  results: FixtureResultResponse[]
  bookings: Booking[]
  newSuspensions: Suspension[]
  newInjuries: Injury[]
  finance: FinanceRoundResponse | null
  bidsReceived: number
  seasonFinished: boolean
  /** Managed club's 0–10 grades for the user's own live-played match; absent for AI rounds and old items. */
  playerRatings?: PlayerRating[] | null
  /** AI-to-AI trades completed during a TRANSFER event; null (not just empty) on news payloads
   * persisted before this field existed — always null-guard before reading it. */
  transfers?: CompletedTransferResponse[] | null
  /** The Preparador Físico's weekly digest; only on TRAINING_REPORT items, null everywhere
   * else and on payloads persisted before the feature existed — always null-guard it. */
  trainingReport?: TrainingReportResponse | null
}
```

(Only the `type` union and the `trainingReport` field are new — every other field stays exactly as it is today. Place the two new types just above `AdvanceResponse`; `StaffQuality` already exists in this file at line ~453 — reference it, don't redeclare.)

- [ ] **Step 4: Add the card branch**

In `AdvanceEventCard.tsx`:

1. Add a local quality-label map after the imports (3 entries, mirrors StaffPage's local map — kept local because neither file exports it today):

```tsx
const QUALITY_LABEL: Record<string, string> = {
  REGULAR: 'Regular',
  BOM: 'Bom',
  EXCELENTE: 'Excelente',
}
```

2. Header branch, after the `FINISHED` header block (line ~230):

```tsx
          {result.type === 'TRAINING_REPORT' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Relatório de Treino
            </Typography>
          )}
```

3. Body branch, after the `FINISHED` body block (line ~281), null-guarding like `transfers`:

```tsx
          {result.type === 'TRAINING_REPORT' && result.trainingReport && (
            <TimelineNode dot="🏋️">
              <Typography component="strong" sx={{ fontWeight: 700 }}>
                Preparador Físico {result.trainingReport.staffName}
                {' · '}
                {QUALITY_LABEL[result.trainingReport.quality] ?? result.trainingReport.quality}
              </Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.trainingReport.entries.map((e, i) => (
                  <ListItem key={`${e.playerName}-${i}`} disablePadding>
                    {e.message}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/match/AdvanceEventCard.test.tsx && npx tsc --noEmit`
Expected: PASS (all AdvanceEventCard tests including the 4 new ones), tsc clean.

- [ ] **Step 6: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: PASS (no other component switches on `AdvanceResponse.type`, so nothing else changes).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts \
        frontend/src/features/match/AdvanceEventCard.tsx \
        frontend/src/features/match/AdvanceEventCard.test.tsx
git commit -m "feat(training): render the weekly Relatório de Treino in the news feed"
```

---

## Final verification (before the whole-branch review)

- Full backend suite: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` — expected green (~9 min; heap already at 3g). Known unrelated flake: `AiListingEngineIT.unusedFiresOnlyFromRoundSixOnwardsForABarelyUsedPlayer` — re-run isolated if it trips.
- Full frontend suite + types: `cd frontend && npx vitest run && npx tsc --noEmit`.
- Whole-branch review per superpowers:subagent-driven-development (MERGE_BASE = the commit before Task 1).
