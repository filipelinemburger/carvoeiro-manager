# Phase 2E — Multi-Season Careers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-season game into an ongoing multi-year career — finish a season, archive it (full table + top scorers + champion), age/retire/replace players, regenerate the calendar, and browse a growing history.

**Architecture:** Approach A — the `SaveGame` rolls forward in place. A `SeasonRolloverService` (one `@Transactional` method) archives the finished season into new `season`/`season_standing`/`season_top_scorer` tables, then mutates the live world (age curve, retirement + newgens, season-state reset, lineup clear, fixture regeneration). All existing live read/advance paths stay unchanged. All randomness derives from one seed (`saveGameId.msb ^ finishingSeasonYear`) consumed over id-sorted players, matching the project's deterministic-engine discipline.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.6.1, Flyway, JPA, PostgreSQL, Testcontainers + JUnit 5 + AssertJ (backend); React 19 + TypeScript (strict) + Vite + TanStack Query + React Router + Vitest/RTL (frontend).

## Global Constraints

- **Build env:** the default `java` is 8 and aborts Gradle. Every backend command MUST be prefixed: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"`. Run backend commands from `backend/`, frontend from `frontend/`.
- **Layering:** Controller → Service → Repository. Controllers never touch repositories. Records for DTOs. Constructor injection.
- **IDs:** `UUID`, assigned by the caller via `UUID.randomUUID()` inside a static `create(...)` factory (no JPA generation). FKs stored as plain `UUID` columns (no JPA associations).
- **Types:** `Instant`/`LocalDate` (never `java.util.Date`); `BigDecimal` for money (N/A this slice); enums with behavior.
- **IDOR:** every save-game-scoped service call starts with `saveGameService.requireOwned(saveGameId, userId)`, which throws `ApiException("SAVE_GAME_NOT_FOUND", ..., HttpStatus.NOT_FOUND)` (404, never 403 — ids must not leak).
- **Errors:** throw `ApiException(code, message, HttpStatus)`; the global handler renders `{error, message, status}`. New codes: `SEASON_NOT_FINISHED` → `HttpStatus.UNPROCESSABLE_CONTENT` (422); `SEASON_NOT_FOUND` → `HttpStatus.NOT_FOUND` (404).
- **REST:** `/api/v1`, plural nouns, precise status codes. The rollover is a POST action (`/seasons/rollover`), consistent with the existing `/advance`.
- **Determinism:** rollover uses a single `new Random(saveGameId.getMostSignificantBits() ^ (long) finishingSeasonYear)` consumed in a fixed order over players sorted by `id`.
- **FK delete order (critical):** `match_events.player_id` and `lineup_players.player_id` and the lineup taker columns reference `players(id)` with **no** `ON DELETE CASCADE`. Therefore rollover MUST purge a competition's fixtures (which `ON DELETE CASCADE`s its `match_events`) **and** delete the managed club's lineup **before** deleting any retired player. Force a `flush()` at those boundaries so the deletes execute in order.
- **Tests:** Given/When/Then, descriptive sentence names, ≥80% coverage. Backend integration tests use `@SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)`. Frontend uses RTL with `vi.mock('../../api/career', ...)`.
- **Commits:** every commit message ends with the trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Direct-to-`main` per-task commits are authorized for this project.
- **Catarinense facts:** 10 clubs, `totalRounds = 2*(10-1) = 18`, `10*(10-1) = 90` fixtures. Managed club short name `CRI` (Criciúma).

## File Structure

**New backend files**
- `player/OverallCalculator.java` — `@Component`; single source of truth for `overall` from position + 6 attributes (extracted from `SaveGameService`).
- `player/PlayerAgingService.java` — `@Component`; deterministic age curve (`applyAgeCurve`) + `retirementProbability(age)`.
- `player/NewgenGenerator.java` — `@Component`; generates a replacement youth player (name from pool, age 16–19, position bands).
- `seed/NewgenNames.java` — record `(List<String> firstNames, List<String> lastNames)`.
- `seed/newgen-names.json` — Brazilian first/last name pools (resources).
- `competition/TopScorerRow.java` — record `(UUID playerId, String playerName, UUID clubId, String clubName, int goals)`.
- `competition/TopScorerResponse.java` — record `(String playerName, String clubName, int goals)`.
- `competition/TopScorersService.java` — `@Service`; aggregates `GOAL` events for a competition (live + archival).
- `season/Season.java`, `season/SeasonStanding.java`, `season/SeasonTopScorer.java` — archive entities.
- `season/SeasonRepository.java`, `season/SeasonStandingRepository.java`, `season/SeasonTopScorerRepository.java`.
- `season/RolloverResponse.java`, `season/SquadChangeResponse.java`, `season/SeasonSummaryResponse.java`, `season/SeasonDetailResponse.java`, `season/SeasonStandingResponse.java` — DTO records.
- `season/SeasonRolloverService.java` — `@Service`; the `@Transactional` rollover.
- `season/SeasonHistoryService.java` — `@Service`; history list + detail.
- `season/SeasonController.java` — POST `/rollover`, GET list, GET `/{seasonYear}`.
- `db/migration/V4__multi_season.sql` — three archive tables (additive).

**Modified backend files**
- `player/Player.java` — add `evolveAttributes(...)`, `resetSeasonState()`.
- `career/SaveGame.java` — add `advanceToNextSeason()`.
- `competition/Competition.java` — add `setSeasonYear(int)`, `setTotalRounds(int)`.
- `career/SaveGameService.java` — inject `OverallCalculator`, replace private `computeOverall`.
- `match/MatchEventRepository.java` — add `findByFixtureIdInAndType(...)`, `deleteByFixtureIdIn(...)`.
- `competition/CompetitionController.java` — add the live top-scorers endpoint.

**New / modified frontend files**
- `api/types.ts` — add `TopScorerResponse`, `SquadChange`, `RolloverResponse`, `SeasonSummary`, `SeasonStandingRow`, `SeasonDetail`.
- `api/career.ts` — add `getTopScorers`, `rolloverSeason`, `getSeasonHistory`, `getSeason`.
- `features/season/SeasonSummaryPage.tsx` (+ test) — end-of-season screen.
- `features/season/SeasonHistoryPage.tsx` (+ test) — history list + detail.
- `features/match/AdvancePanel.tsx` (+ test) — link to the summary when the season finishes.
- `features/career/DashboardPage.tsx` — links to summary (when finished) and history.
- `router.tsx` — add `/season-summary` and `/history` routes.

---

### Task 1: OverallCalculator (extract + reuse)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/OverallCalculator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/OverallCalculatorTest.java`

**Interfaces:**
- Produces: `OverallCalculator` (`@Component`) with `int compute(Position position, int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina)`. Later used by `PlayerAgingService`, `NewgenGenerator`, and `SaveGameService`.

- [ ] **Step 1: Write the failing test**

Create `OverallCalculatorTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverallCalculatorTest {

    private final OverallCalculator calculator = new OverallCalculator();

    @Test
    void goalkeeperOverallIsDominatedByGoalkeeping() {
        // Given a GK with goalkeeping 80, others 40
        // GK weights: gk .60, stamina .15, passing .15, pace .10
        // 80*.60 + 40*.15 + 40*.15 + 40*.10 = 48 + 6 + 6 + 4 = 64
        int overall = calculator.compute(Position.GK, 40, 40, 40, 40, 80, 40);
        assertThat(overall).isEqualTo(64);
    }

    @Test
    void attackerOverallIsDominatedByFinishingAndPace() {
        // ATT weights: finishing .40, pace .25, passing .15, stamina .10, tackling .10
        // pace 70, finishing 80, passing 60, tackling 50, gk 10, stamina 60
        // 80*.40 + 70*.25 + 60*.15 + 60*.10 + 50*.10 = 32 + 17.5 + 9 + 6 + 5 = 69.5 -> 70
        int overall = calculator.compute(Position.ATT, 70, 80, 60, 50, 10, 60);
        assertThat(overall).isEqualTo(70);
    }

    @Test
    void defenderWeightsTacklingHighest() {
        // DEF weights: tackling .40, pace .20, stamina .15, passing .15, finishing .10
        // pace 60, finishing 40, passing 55, tackling 75, gk 10, stamina 65
        // 75*.40 + 60*.20 + 65*.15 + 55*.15 + 40*.10 = 30 + 12 + 9.75 + 8.25 + 4 = 64 -> 64
        int overall = calculator.compute(Position.DEF, 60, 40, 55, 75, 10, 65);
        assertThat(overall).isEqualTo(64);
    }

    @Test
    void midfielderWeightsPassingHighest() {
        // MID weights: passing .35, stamina .20, tackling .20, pace .15, finishing .10
        // pace 65, finishing 50, passing 78, tackling 60, gk 10, stamina 70
        // 78*.35 + 70*.20 + 60*.20 + 65*.15 + 50*.10 = 27.3 + 14 + 12 + 9.75 + 5 = 68.05 -> 68
        int overall = calculator.compute(Position.MID, 65, 50, 78, 60, 10, 70);
        assertThat(overall).isEqualTo(68);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.OverallCalculatorTest'`
Expected: COMPILE FAILURE — `OverallCalculator` does not exist.

- [ ] **Step 3: Create the OverallCalculator**

Create `OverallCalculator.java` (weights copied verbatim from `SaveGameService.computeOverall`):

```java
package br.com.carvoeiromanager.player;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for a player's overall as a position-weighted average
 * of the 6 attributes. Extracted from SaveGameService so career creation, season
 * aging, and newgen generation all compute overall identically.
 *
 * Weights (sum = 1.0):
 *   GK:  goalkeeping .60, stamina .15, passing .15, pace .10
 *   DEF: tackling .40, pace .20, stamina .15, passing .15, finishing .10
 *   MID: passing .35, stamina .20, tackling .20, pace .15, finishing .10
 *   ATT: finishing .40, pace .25, passing .15, stamina .10, tackling .10
 */
@Component
public class OverallCalculator {

    public int compute(Position position, int pace, int finishing, int passing,
                       int tackling, int goalkeeping, int stamina) {
        double weighted = switch (position) {
            case GK -> goalkeeping * 0.60 + stamina * 0.15 + passing * 0.15 + pace * 0.10;
            case DEF -> tackling * 0.40 + pace * 0.20 + stamina * 0.15 + passing * 0.15 + finishing * 0.10;
            case MID -> passing * 0.35 + stamina * 0.20 + tackling * 0.20 + pace * 0.15 + finishing * 0.10;
            case ATT -> finishing * 0.40 + pace * 0.25 + passing * 0.15 + stamina * 0.10 + tackling * 0.10;
        };
        return (int) Math.round(weighted);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.OverallCalculatorTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Refactor SaveGameService to use it**

In `SaveGameService.java`:
1. Add import: `import br.com.carvoeiromanager.player.OverallCalculator;` (already in `br.com.carvoeiromanager.player`? no — `SaveGameService` is in `career`; add the import).
2. Add a field `private final OverallCalculator overallCalculator;` and add `OverallCalculator overallCalculator` as the **last** constructor parameter, assigning `this.overallCalculator = overallCalculator;`.
3. Replace the call site (line ~90) `int overall = computeOverall(seedPlayer, position);` with:

```java
int overall = overallCalculator.compute(position,
        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina());
```

4. Delete the private `computeOverall(SeedPlayer p, Position position)` method (lines ~182–199) and its now-unused `SeedPlayer` import only if nothing else uses it (it is still used elsewhere in the method — keep the `SeedPlayer` import).

- [ ] **Step 6: Run the existing career test to verify nothing broke**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.career.SaveGameServiceIT' --tests 'br.com.carvoeiromanager.player.OverallCalculatorTest'`
Expected: PASS (career seeding still produces the same overalls; `managedClubName` contains "Criciúma").

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/OverallCalculator.java \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
        backend/src/test/java/br/com/carvoeiromanager/player/OverallCalculatorTest.java
git commit -m "$(cat <<'EOF'
feat(player): extract OverallCalculator as shared overall computation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: V4 migration + season archive entities + repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__multi_season.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/Season.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonStanding.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonTopScorer.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonStandingRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonTopScorerRepository.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonArchiveEntityIT.java`

**Interfaces:**
- Produces:
  - `Season.create(UUID saveGameId, UUID competitionId, int seasonYear, String competitionName, UUID championClubId, String championClubName, UUID managedClubId, String managedClubName, int managedClubPosition)` + getters + `getId()`.
  - `SeasonStanding.create(UUID seasonId, int position, UUID clubId, String clubName, int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst, int goalDifference, int points)`.
  - `SeasonTopScorer.create(UUID seasonId, int rank, UUID playerId, String playerName, UUID clubId, String clubName, int goals)`.
  - `SeasonRepository.findBySaveGameIdOrderBySeasonYearDesc(UUID)`, `findBySaveGameIdAndSeasonYear(UUID, int) : Optional<Season>`.
  - `SeasonStandingRepository.findBySeasonIdOrderByPosition(UUID)`.
  - `SeasonTopScorerRepository.findBySeasonIdOrderByRank(UUID)`.

- [ ] **Step 1: Write the migration**

Create `V4__multi_season.sql`. Archive tables are denormalized historical snapshots: only `season_id` has an FK (season rows are never deleted); `player_id`/`club_id` are plain UUIDs because players (and, in future slices, clubs) can be deleted between seasons.

```sql
CREATE TABLE season (
    id                    UUID PRIMARY KEY,
    save_game_id          UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id        UUID NOT NULL,
    season_year           INT NOT NULL,
    competition_name      VARCHAR(80) NOT NULL,
    champion_club_id      UUID NOT NULL,
    champion_club_name    VARCHAR(80) NOT NULL,
    managed_club_id       UUID NOT NULL,
    managed_club_name     VARCHAR(80) NOT NULL,
    managed_club_position INT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (save_game_id, season_year, competition_id)
);

CREATE TABLE season_standing (
    id               UUID PRIMARY KEY,
    season_id        UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    position         INT NOT NULL,
    club_id          UUID NOT NULL,
    club_name        VARCHAR(80) NOT NULL,
    played           INT NOT NULL,
    won              INT NOT NULL,
    drawn            INT NOT NULL,
    lost             INT NOT NULL,
    goals_for        INT NOT NULL,
    goals_against    INT NOT NULL,
    goal_difference  INT NOT NULL,
    points           INT NOT NULL
);

CREATE TABLE season_top_scorer (
    id           UUID PRIMARY KEY,
    season_id    UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    rank         INT NOT NULL,
    player_id    UUID NOT NULL,
    player_name  VARCHAR(80) NOT NULL,
    club_id      UUID NOT NULL,
    club_name    VARCHAR(80) NOT NULL,
    goals        INT NOT NULL
);

CREATE INDEX idx_season_save_game ON season(save_game_id);
CREATE INDEX idx_season_standing_season ON season_standing(season_id);
CREATE INDEX idx_season_top_scorer_season ON season_top_scorer(season_id);
```

- [ ] **Step 2: Write the failing entity round-trip test**

Create `SeasonArchiveEntityIT.java`:

```java
package br.com.carvoeiromanager.season;

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
class SeasonArchiveEntityIT {

    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonStandingRepository seasonStandingRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;

    @Test
    void persistsAndReadsBackAnArchivedSeasonWithTableAndScorers() {
        // Given an archived season with two table rows and two scorers
        UUID saveGameId = UUID.randomUUID();
        UUID competitionId = UUID.randomUUID();
        UUID championId = UUID.randomUUID();
        UUID managedId = UUID.randomUUID();

        Season season = Season.create(saveGameId, competitionId, 2026, "Campeonato Catarinense",
                championId, "Campeao FC", managedId, "Criciúma EC", 3);
        seasonRepository.save(season);

        seasonStandingRepository.save(SeasonStanding.create(season.getId(), 1, championId, "Campeao FC",
                18, 14, 2, 2, 40, 12, 28, 44));
        seasonStandingRepository.save(SeasonStanding.create(season.getId(), 2, managedId, "Criciúma EC",
                18, 12, 4, 2, 35, 15, 20, 40));

        UUID scorerId = UUID.randomUUID();
        seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), 1, scorerId, "Artilheiro",
                championId, "Campeao FC", 21));
        seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), 2, UUID.randomUUID(), "Segundo",
                managedId, "Criciúma EC", 14));

        // When reading back via the finders
        List<Season> seasons = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(saveGameId);
        List<SeasonStanding> table = seasonStandingRepository.findBySeasonIdOrderByPosition(season.getId());
        List<SeasonTopScorer> scorers = seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId());

        // Then the data round-trips with correct ordering
        assertThat(seasons).hasSize(1);
        assertThat(seasons.get(0).getChampionClubName()).isEqualTo("Campeao FC");
        assertThat(seasons.get(0).getManagedClubPosition()).isEqualTo(3);
        assertThat(seasonRepository.findBySaveGameIdAndSeasonYear(saveGameId, 2026)).isPresent();

        assertThat(table).extracting(SeasonStanding::getPosition).containsExactly(1, 2);
        assertThat(table.get(0).getPoints()).isEqualTo(44);
        assertThat(scorers).extracting(SeasonTopScorer::getRank).containsExactly(1, 2);
        assertThat(scorers.get(0).getGoals()).isEqualTo(21);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonArchiveEntityIT'`
Expected: COMPILE FAILURE — entities/repositories do not exist.

- [ ] **Step 4: Create the entities**

`Season.java`:

```java
package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Archived finished season (one row per competition per year). Denormalized snapshot. */
@Entity
@Table(name = "season")
public class Season {

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "competition_id", nullable = false) private UUID competitionId;
    @Column(name = "season_year", nullable = false) private int seasonYear;
    @Column(name = "competition_name", nullable = false, length = 80) private String competitionName;
    @Column(name = "champion_club_id", nullable = false) private UUID championClubId;
    @Column(name = "champion_club_name", nullable = false, length = 80) private String championClubName;
    @Column(name = "managed_club_id", nullable = false) private UUID managedClubId;
    @Column(name = "managed_club_name", nullable = false, length = 80) private String managedClubName;
    @Column(name = "managed_club_position", nullable = false) private int managedClubPosition;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected Season() { }

    private Season(UUID id, UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                   UUID championClubId, String championClubName, UUID managedClubId, String managedClubName,
                   int managedClubPosition, Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.competitionId = competitionId;
        this.seasonYear = seasonYear; this.competitionName = competitionName;
        this.championClubId = championClubId; this.championClubName = championClubName;
        this.managedClubId = managedClubId; this.managedClubName = managedClubName;
        this.managedClubPosition = managedClubPosition; this.createdAt = createdAt;
    }

    public static Season create(UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                                UUID championClubId, String championClubName, UUID managedClubId,
                                String managedClubName, int managedClubPosition) {
        return new Season(UUID.randomUUID(), saveGameId, competitionId, seasonYear, competitionName,
                championClubId, championClubName, managedClubId, managedClubName, managedClubPosition,
                Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getSeasonYear() { return seasonYear; }
    public String getCompetitionName() { return competitionName; }
    public UUID getChampionClubId() { return championClubId; }
    public String getChampionClubName() { return championClubName; }
    public UUID getManagedClubId() { return managedClubId; }
    public String getManagedClubName() { return managedClubName; }
    public int getManagedClubPosition() { return managedClubPosition; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`SeasonStanding.java`:

```java
package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived final-table row for a Season. */
@Entity
@Table(name = "season_standing")
public class SeasonStanding {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "position", nullable = false) private int position;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "played", nullable = false) private int played;
    @Column(name = "won", nullable = false) private int won;
    @Column(name = "drawn", nullable = false) private int drawn;
    @Column(name = "lost", nullable = false) private int lost;
    @Column(name = "goals_for", nullable = false) private int goalsFor;
    @Column(name = "goals_against", nullable = false) private int goalsAgainst;
    @Column(name = "goal_difference", nullable = false) private int goalDifference;
    @Column(name = "points", nullable = false) private int points;

    protected SeasonStanding() { }

    private SeasonStanding(UUID id, UUID seasonId, int position, UUID clubId, String clubName,
                           int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst,
                           int goalDifference, int points) {
        this.id = id; this.seasonId = seasonId; this.position = position; this.clubId = clubId;
        this.clubName = clubName; this.played = played; this.won = won; this.drawn = drawn;
        this.lost = lost; this.goalsFor = goalsFor; this.goalsAgainst = goalsAgainst;
        this.goalDifference = goalDifference; this.points = points;
    }

    public static SeasonStanding create(UUID seasonId, int position, UUID clubId, String clubName,
                                        int played, int won, int drawn, int lost, int goalsFor,
                                        int goalsAgainst, int goalDifference, int points) {
        return new SeasonStanding(UUID.randomUUID(), seasonId, position, clubId, clubName,
                played, won, drawn, lost, goalsFor, goalsAgainst, goalDifference, points);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public int getPosition() { return position; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getPlayed() { return played; }
    public int getWon() { return won; }
    public int getDrawn() { return drawn; }
    public int getLost() { return lost; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public int getGoalDifference() { return goalDifference; }
    public int getPoints() { return points; }
}
```

`SeasonTopScorer.java`:

```java
package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived top-scorer row for a Season. */
@Entity
@Table(name = "season_top_scorer")
public class SeasonTopScorer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "rank", nullable = false) private int rank;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "player_name", nullable = false, length = 80) private String playerName;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "goals", nullable = false) private int goals;

    protected SeasonTopScorer() { }

    private SeasonTopScorer(UUID id, UUID seasonId, int rank, UUID playerId, String playerName,
                            UUID clubId, String clubName, int goals) {
        this.id = id; this.seasonId = seasonId; this.rank = rank; this.playerId = playerId;
        this.playerName = playerName; this.clubId = clubId; this.clubName = clubName; this.goals = goals;
    }

    public static SeasonTopScorer create(UUID seasonId, int rank, UUID playerId, String playerName,
                                         UUID clubId, String clubName, int goals) {
        return new SeasonTopScorer(UUID.randomUUID(), seasonId, rank, playerId, playerName,
                clubId, clubName, goals);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public int getRank() { return rank; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getGoals() { return goals; }
}
```

- [ ] **Step 5: Create the repositories**

`SeasonRepository.java`:

```java
package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends JpaRepository<Season, UUID> {
    List<Season> findBySaveGameIdOrderBySeasonYearDesc(UUID saveGameId);
    // One competition per save today; generalizes when 2D (multiple competitions) lands.
    Optional<Season> findBySaveGameIdAndSeasonYear(UUID saveGameId, int seasonYear);
}
```

`SeasonStandingRepository.java`:

```java
package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonStandingRepository extends JpaRepository<SeasonStanding, UUID> {
    List<SeasonStanding> findBySeasonIdOrderByPosition(UUID seasonId);
}
```

`SeasonTopScorerRepository.java`:

```java
package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonTopScorerRepository extends JpaRepository<SeasonTopScorer, UUID> {
    List<SeasonTopScorer> findBySeasonIdOrderByRank(UUID seasonId);
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonArchiveEntityIT'`
Expected: PASS (Flyway applies V4, entities round-trip).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V4__multi_season.sql \
        backend/src/main/java/br/com/carvoeiromanager/season/Season.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonStanding.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonTopScorer.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonStandingRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonTopScorerRepository.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonArchiveEntityIT.java
git commit -m "$(cat <<'EOF'
feat(season): V4 archive tables + Season/SeasonStanding/SeasonTopScorer entities

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Entity rollover mutations

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/player/Player.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/Competition.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerEvolutionTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameRolloverTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionMutationTest.java`

**Interfaces:**
- Produces: `Player.evolveAttributes(int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina, int overall)`, `Player.resetSeasonState()`; `SaveGame.advanceToNextSeason()`; `Competition.setSeasonYear(int)`, `Competition.setTotalRounds(int)`.

- [ ] **Step 1: Write the failing tests**

`PlayerEvolutionTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerEvolutionTest {

    private Player aPlayer() {
        // create(saveGameId, clubId, name, position, age, nationality, pace, fin, pas, tac, gk, sta, overall)
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Tester", Position.MID,
                25, "Brasil", 60, 55, 70, 65, 10, 72, 66);
    }

    @Test
    void evolveAttributesReplacesAllSixAttributesAndOverall() {
        // Given a player
        Player p = aPlayer();

        // When evolving its attributes
        p.evolveAttributes(62, 57, 68, 63, 12, 70, 64);

        // Then every attribute and overall reflect the new values
        assertThat(p.getPace()).isEqualTo(62);
        assertThat(p.getFinishing()).isEqualTo(57);
        assertThat(p.getPassing()).isEqualTo(68);
        assertThat(p.getTackling()).isEqualTo(63);
        assertThat(p.getGoalkeeping()).isEqualTo(12);
        assertThat(p.getStamina()).isEqualTo(70);
        assertThat(p.getOverall()).isEqualTo(64);
    }

    @Test
    void resetSeasonStateZeroesFormCardsAndSuspension() {
        // Given a player carrying season state
        Player p = aPlayer();
        p.applyForm(3);
        p.addYellowCard();
        p.addSuspension(2);

        // When resetting season state
        p.resetSeasonState();

        // Then form, yellow cards and suspension are all zero (player eligible again)
        assertThat(p.getForm()).isZero();
        assertThat(p.getYellowCards()).isZero();
        assertThat(p.getSuspensionRemaining()).isZero();
        assertThat(p.isEligible()).isTrue();
    }
}
```

`SaveGameRolloverTest.java`:

```java
package br.com.carvoeiromanager.career;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SaveGameRolloverTest {

    @Test
    void advanceToNextSeasonBumpsYearResetsRoundAndReopensTheSave() {
        // Given a finished save in season 2026 at round 18
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setCurrentRound(18);
        save.setStatus(SaveGameStatus.FINISHED);

        // When advancing to the next season
        save.advanceToNextSeason();

        // Then the year increments, the round resets, and the save is in progress again
        assertThat(save.getSeasonYear()).isEqualTo(2027);
        assertThat(save.getCurrentRound()).isZero();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
    }
}
```

`CompetitionMutationTest.java`:

```java
package br.com.carvoeiromanager.competition;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionMutationTest {

    @Test
    void seasonYearAndTotalRoundsAreMutableForRollover() {
        // Given a competition for season 2026 with 18 rounds
        Competition competition = Competition.create(UUID.randomUUID(), "Campeonato Catarinense",
                "LEAGUE", 2026, 18);

        // When rolling it into the next season
        competition.setSeasonYear(2027);
        competition.setTotalRounds(18);

        // Then the new values are reflected
        assertThat(competition.getSeasonYear()).isEqualTo(2027);
        assertThat(competition.getTotalRounds()).isEqualTo(18);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.PlayerEvolutionTest' --tests 'br.com.carvoeiromanager.career.SaveGameRolloverTest' --tests 'br.com.carvoeiromanager.competition.CompetitionMutationTest'`
Expected: COMPILE FAILURE — the new methods do not exist.

- [ ] **Step 3: Add the Player methods**

In `Player.java`, after `decrementSuspension()` / `isEligible()`:

```java
    /** Replaces all six attributes and overall after the season aging curve. */
    public void evolveAttributes(int pace, int finishing, int passing,
                                 int tackling, int goalkeeping, int stamina, int overall) {
        this.pace = pace;
        this.finishing = finishing;
        this.passing = passing;
        this.tackling = tackling;
        this.goalkeeping = goalkeeping;
        this.stamina = stamina;
        this.overall = overall;
    }

    /** Clears per-season state when a player carries over into a new season. */
    public void resetSeasonState() {
        this.form = 0;
        this.yellowCards = 0;
        this.suspensionRemaining = 0;
    }
```

- [ ] **Step 4: Add the SaveGame method**

In `SaveGame.java`, after `setStatus(...)`:

```java
    /** Rolls this career forward into the next season. */
    public void advanceToNextSeason() {
        this.seasonYear += 1;
        this.currentRound = 0;
        this.status = SaveGameStatus.IN_PROGRESS;
    }
```

- [ ] **Step 5: Add the Competition setters**

In `Competition.java`, after the getters:

```java
    public void setSeasonYear(int seasonYear) { this.seasonYear = seasonYear; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.PlayerEvolutionTest' --tests 'br.com.carvoeiromanager.career.SaveGameRolloverTest' --tests 'br.com.carvoeiromanager.competition.CompetitionMutationTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/Player.java \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/Competition.java \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerEvolutionTest.java \
        backend/src/test/java/br/com/carvoeiromanager/career/SaveGameRolloverTest.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/CompetitionMutationTest.java
git commit -m "$(cat <<'EOF'
feat(domain): rollover mutations - player evolve/reset, save+competition season advance

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: PlayerAgingService (age curve + retirement)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/PlayerAgingService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/PlayerAgingServiceTest.java`

**Interfaces:**
- Consumes: `OverallCalculator.compute(...)` (Task 1); `Player.evolveAttributes(...)` + `Player.setAge(...)` (Task 3).
- Produces: `PlayerAgingService` (`@Component`) with `void applyAgeCurve(Player player, java.util.Random rng)` (mutates the player's six attributes + overall based on its **current** age; the caller increments age first) and `double retirementProbability(int age)`.

**Algorithm (locked, so tests are deterministic):**
- Per-attribute delta by age band, drawn in canonical order `pace, finishing, passing, tackling, goalkeeping, stamina`:
  - `age ≤ 21` → `rng.nextInt(4)` (0..+3)
  - `age ≤ 29` → `rng.nextInt(3) - 1` (−1..+1)
  - `age ≤ 32` → `rng.nextInt(3) - 2` (−2..0)
  - else → `-(1 + rng.nextInt(4))` (−4..−1)
- After applying deltas and clamping to `[1,99]`: if `age ≥ 33`, apply an extra `-1` to `pace` and `stamina` (re-clamped) — pace/stamina decline fastest late.
- Recompute overall via `OverallCalculator`.
- `retirementProbability`: `<35 → 0.0`, `35 → 0.25`, `36 → 0.45`, `37 → 0.70`, `≥38 → 1.0`.

- [ ] **Step 1: Write the failing test**

`PlayerAgingServiceTest.java`:

```java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerAgingServiceTest {

    private final PlayerAgingService aging = new PlayerAgingService(new OverallCalculator());

    private Player player(Position position, int age, int attr) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", position,
                age, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    @Test
    void youthNeverLoseAttributesAndMayImprove() {
        // Given a 20-year-old with mid attributes
        Player p = player(Position.MID, 20, 50);

        // When the age curve is applied
        aging.applyAgeCurve(p, new Random(7));

        // Then every attribute is in [50, 53] (deltas 0..3) and overall did not drop
        assertThat(p.getPace()).isBetween(50, 53);
        assertThat(p.getFinishing()).isBetween(50, 53);
        assertThat(p.getPassing()).isBetween(50, 53);
        assertThat(p.getTackling()).isBetween(50, 53);
        assertThat(p.getGoalkeeping()).isBetween(50, 53);
        assertThat(p.getStamina()).isBetween(50, 53);
        assertThat(p.getOverall()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void oldPlayersDeclineAndPaceStaminaFallFastest() {
        // Given a 35-year-old with attributes 70
        Player p = player(Position.MID, 35, 70);

        // When the age curve is applied (deltas -4..-1; pace/stamina get an extra -1)
        aging.applyAgeCurve(p, new Random(7));

        // Then non-pace/stamina attributes are in [66, 69]; pace and stamina in [65, 68]
        assertThat(p.getFinishing()).isBetween(66, 69);
        assertThat(p.getPassing()).isBetween(66, 69);
        assertThat(p.getTackling()).isBetween(66, 69);
        assertThat(p.getGoalkeeping()).isBetween(66, 69);
        assertThat(p.getPace()).isBetween(65, 68);
        assertThat(p.getStamina()).isBetween(65, 68);
        assertThat(p.getOverall()).isLessThan(70);
    }

    @Test
    void attributesAreClampedToTheNinetyNineCeiling() {
        // Given a youth maxed at 99
        Player p = player(Position.ATT, 19, 99);

        // When the (positive) youth curve is applied
        aging.applyAgeCurve(p, new Random(3));

        // Then attributes never exceed 99
        assertThat(p.getPace()).isEqualTo(99);
        assertThat(p.getFinishing()).isEqualTo(99);
        assertThat(p.getStamina()).isEqualTo(99);
    }

    @Test
    void attributesAreClampedToTheFloorOfOne() {
        // Given a very old player at the floor
        Player p = player(Position.DEF, 36, 1);

        // When the (negative) decline curve is applied
        aging.applyAgeCurve(p, new Random(3));

        // Then attributes never drop below 1
        assertThat(p.getPace()).isEqualTo(1);
        assertThat(p.getTackling()).isEqualTo(1);
        assertThat(p.getStamina()).isEqualTo(1);
    }

    @Test
    void theCurveIsDeterministicForAGivenSeed() {
        // Given two identical players
        Player a = player(Position.MID, 26, 60);
        Player b = player(Position.MID, 26, 60);

        // When evolved with the same seed
        aging.applyAgeCurve(a, new Random(123));
        aging.applyAgeCurve(b, new Random(123));

        // Then they end up identical
        assertThat(b.getPace()).isEqualTo(a.getPace());
        assertThat(b.getFinishing()).isEqualTo(a.getFinishing());
        assertThat(b.getOverall()).isEqualTo(a.getOverall());
    }

    @Test
    void retirementProbabilityRisesWithAge() {
        assertThat(aging.retirementProbability(30)).isEqualTo(0.0);
        assertThat(aging.retirementProbability(34)).isEqualTo(0.0);
        assertThat(aging.retirementProbability(35)).isEqualTo(0.25);
        assertThat(aging.retirementProbability(36)).isEqualTo(0.45);
        assertThat(aging.retirementProbability(37)).isEqualTo(0.70);
        assertThat(aging.retirementProbability(38)).isEqualTo(1.0);
        assertThat(aging.retirementProbability(41)).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.PlayerAgingServiceTest'`
Expected: COMPILE FAILURE — `PlayerAgingService` does not exist.

- [ ] **Step 3: Create PlayerAgingService**

```java
package br.com.carvoeiromanager.player;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Deterministic season-to-season player evolution. The caller increments the
 * player's age first, then calls {@link #applyAgeCurve}; randomness comes from a
 * shared, seeded RNG consumed in a fixed order so a rollover is reproducible.
 */
@Component
public class PlayerAgingService {

    private final OverallCalculator overallCalculator;

    public PlayerAgingService(OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
    }

    /** Applies the age curve to the player's six attributes (based on its current age) and recomputes overall. */
    public void applyAgeCurve(Player player, Random rng) {
        int age = player.getAge();
        int pace = clamp(player.getPace() + delta(age, rng));
        int finishing = clamp(player.getFinishing() + delta(age, rng));
        int passing = clamp(player.getPassing() + delta(age, rng));
        int tackling = clamp(player.getTackling() + delta(age, rng));
        int goalkeeping = clamp(player.getGoalkeeping() + delta(age, rng));
        int stamina = clamp(player.getStamina() + delta(age, rng));

        if (age >= 33) {
            pace = clamp(pace - 1);
            stamina = clamp(stamina - 1);
        }

        int overall = overallCalculator.compute(player.getPosition(),
                pace, finishing, passing, tackling, goalkeeping, stamina);
        player.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    /** Probability (0..1) that a player of the given age retires at season rollover. */
    public double retirementProbability(int age) {
        if (age >= 38) return 1.0;
        return switch (age) {
            case 37 -> 0.70;
            case 36 -> 0.45;
            case 35 -> 0.25;
            default -> 0.0;
        };
    }

    private int delta(int age, Random rng) {
        if (age <= 21) return rng.nextInt(4);       // 0..+3
        if (age <= 29) return rng.nextInt(3) - 1;   // -1..+1
        if (age <= 32) return rng.nextInt(3) - 2;   // -2..0
        return -(1 + rng.nextInt(4));               // -4..-1
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(99, value));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.player.PlayerAgingServiceTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/player/PlayerAgingService.java \
        backend/src/test/java/br/com/carvoeiromanager/player/PlayerAgingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(player): deterministic age curve + retirement probability

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: NewgenGenerator + name pool

**Files:**
- Create: `backend/src/main/resources/seed/newgen-names.json`
- Create: `backend/src/main/java/br/com/carvoeiromanager/seed/NewgenNames.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/player/NewgenGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/player/NewgenGeneratorTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderNewgenTest.java`

**Interfaces:**
- Consumes: `OverallCalculator` (Task 1); `Player.create(...)`; `SeedLoader`.
- Produces:
  - `NewgenNames` record `(java.util.List<String> firstNames, java.util.List<String> lastNames)`.
  - `SeedLoader.loadNewgenNames() : NewgenNames`.
  - `NewgenGenerator` (`@Component`) with `Player generate(UUID saveGameId, UUID clubId, Position position, java.util.Random rng)`.

**Draw order (locked):** firstName index, lastName index, age, then attributes in canonical order `pace, finishing, passing, tackling, goalkeeping, stamina` (position-specific base+span via `base + rng.nextInt(span)`).

- [ ] **Step 1: Create the name-pool resource**

`backend/src/main/resources/seed/newgen-names.json`:

```json
{
  "firstNames": ["Gabriel", "Lucas", "Matheus", "Pedro", "João", "Rafael", "Felipe", "Bruno",
    "Vinícius", "Gustavo", "Eduardo", "Leonardo", "Thiago", "Diego", "Caio", "André",
    "Marcelo", "Rodrigo", "Henrique", "Igor", "Murilo", "Wesley"],
  "lastNames": ["Silva", "Santos", "Oliveira", "Souza", "Costa", "Pereira", "Almeida", "Ferreira",
    "Rodrigues", "Gomes", "Martins", "Araújo", "Ribeiro", "Carvalho", "Lima", "Barbosa",
    "Cardoso", "Teixeira", "Moraes", "Nascimento", "Rocha", "Cavalcante"]
}
```

- [ ] **Step 2: Write the failing tests**

`SeedLoaderNewgenTest.java`:

```java
package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderNewgenTest {

    private final SeedLoader loader = new SeedLoader();

    @Test
    void loadsNonEmptyNewgenNamePools() {
        // When loading the newgen name pools
        NewgenNames names = loader.loadNewgenNames();

        // Then both pools are present and non-trivial
        assertThat(names.firstNames()).hasSizeGreaterThan(10);
        assertThat(names.lastNames()).hasSizeGreaterThan(10);
    }
}
```

`NewgenGeneratorTest.java`:

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NewgenGeneratorTest {

    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final NewgenGenerator generator = new NewgenGenerator(new SeedLoader(), overallCalculator);

    @Test
    void generatesAYouthPlayerInTheRequestedClubAndPosition() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        Player p = generator.generate(saveGameId, clubId, Position.ATT, new Random(5));

        assertThat(p.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(p.getClubId()).isEqualTo(clubId);
        assertThat(p.getPosition()).isEqualTo(Position.ATT);
        assertThat(p.getAge()).isBetween(16, 19);
        assertThat(p.getNationality()).isEqualTo("Brasil");
        assertThat(p.getName()).contains(" ");
    }

    @Test
    void attributesStayInRangeAndOverallMatchesTheCalculator() {
        Player p = generator.generate(UUID.randomUUID(), UUID.randomUUID(), Position.GK, new Random(9));

        assertThat(p.getPace()).isBetween(1, 99);
        assertThat(p.getFinishing()).isBetween(1, 99);
        assertThat(p.getGoalkeeping()).isGreaterThanOrEqualTo(45); // GK band starts at 45
        assertThat(p.getOverall()).isEqualTo(overallCalculator.compute(Position.GK,
                p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina()));
    }

    @Test
    void attackersGetAHighFinishingFloor() {
        Player p = generator.generate(UUID.randomUUID(), UUID.randomUUID(), Position.ATT, new Random(2));
        assertThat(p.getFinishing()).isGreaterThanOrEqualTo(45); // ATT finishing band starts at 45
    }

    @Test
    void generationIsDeterministicForAGivenSeed() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        Player a = generator.generate(saveGameId, clubId, Position.MID, new Random(42));
        Player b = generator.generate(saveGameId, clubId, Position.MID, new Random(42));

        assertThat(b.getName()).isEqualTo(a.getName());
        assertThat(b.getAge()).isEqualTo(a.getAge());
        assertThat(b.getPassing()).isEqualTo(a.getPassing());
        assertThat(b.getOverall()).isEqualTo(a.getOverall());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderNewgenTest' --tests 'br.com.carvoeiromanager.player.NewgenGeneratorTest'`
Expected: COMPILE FAILURE — `NewgenNames`, `loadNewgenNames`, `NewgenGenerator` do not exist.

- [ ] **Step 4: Create NewgenNames + extend SeedLoader**

`NewgenNames.java`:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

public record NewgenNames(List<String> firstNames, List<String> lastNames) {}
```

In `SeedLoader.java`, add a method (mirroring `loadCatarinense`):

```java
    public NewgenNames loadNewgenNames() {
        try (InputStream in = new ClassPathResource("seed/newgen-names.json").getInputStream()) {
            return mapper.readValue(in, NewgenNames.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load newgen names seed", e);
        }
    }
```

- [ ] **Step 5: Create NewgenGenerator**

```java
package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a replacement youth player ("newgen") when a player retires, so a
 * club's squad size stays constant. Deterministic for a given RNG: draws name,
 * age, then attributes in a fixed order. The youth-growth curve develops them
 * over subsequent seasons.
 */
@Component
public class NewgenGenerator {

    private final OverallCalculator overallCalculator;
    private final List<String> firstNames;
    private final List<String> lastNames;

    public NewgenGenerator(SeedLoader seedLoader, OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public Player generate(UUID saveGameId, UUID clubId, Position position, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 16 + rng.nextInt(4); // 16..19

        int pace, finishing, passing, tackling, goalkeeping, stamina;
        switch (position) {
            case GK -> {
                pace = band(30, 20, rng); finishing = band(20, 15, rng); passing = band(35, 20, rng);
                tackling = band(25, 15, rng); goalkeeping = band(45, 20, rng); stamina = band(40, 20, rng);
            }
            case DEF -> {
                pace = band(40, 20, rng); finishing = band(25, 15, rng); passing = band(35, 20, rng);
                tackling = band(45, 20, rng); goalkeeping = band(1, 8, rng); stamina = band(40, 20, rng);
            }
            case MID -> {
                pace = band(40, 20, rng); finishing = band(35, 20, rng); passing = band(45, 20, rng);
                tackling = band(40, 20, rng); goalkeeping = band(1, 8, rng); stamina = band(45, 20, rng);
            }
            case ATT -> {
                pace = band(45, 20, rng); finishing = band(45, 20, rng); passing = band(40, 20, rng);
                tackling = band(25, 15, rng); goalkeeping = band(1, 8, rng); stamina = band(35, 20, rng);
            }
            default -> throw new IllegalStateException("Unknown position: " + position);
        }

        int overall = overallCalculator.compute(position, pace, finishing, passing,
                tackling, goalkeeping, stamina);
        return Player.create(saveGameId, clubId, name, position, age, "Brasil",
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    private int band(int base, int span, Random rng) {
        return Math.min(99, base + rng.nextInt(span));
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderNewgenTest' --tests 'br.com.carvoeiromanager.player.NewgenGeneratorTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/seed/newgen-names.json \
        backend/src/main/java/br/com/carvoeiromanager/seed/NewgenNames.java \
        backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java \
        backend/src/main/java/br/com/carvoeiromanager/player/NewgenGenerator.java \
        backend/src/test/java/br/com/carvoeiromanager/player/NewgenGeneratorTest.java \
        backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderNewgenTest.java
git commit -m "$(cat <<'EOF'
feat(player): NewgenGenerator + Brazilian newgen name pool

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: TopScorersService + live top-scorers endpoint

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchEventRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/TopScorerRow.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/TopScorerResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/TopScorersService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/competition/TopScorersServiceIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned`, `CompetitionRepository`, `FixtureRepository.findBySaveGameIdAndCompetitionId`, `PlayerRepository.findBySaveGameId`, `ClubRepository.findBySaveGameId`, `MatchEvent` (`getPlayerId`, `getType`).
- Produces:
  - `MatchEventRepository.findByFixtureIdInAndType(java.util.Collection<UUID> fixtureIds, String type) : List<MatchEvent>`; `deleteByFixtureIdIn(java.util.Collection<UUID> fixtureIds)` (used by Task 7).
  - `TopScorerRow(UUID playerId, String playerName, UUID clubId, String clubName, int goals)`.
  - `TopScorerResponse(String playerName, String clubName, int goals)`.
  - `TopScorersService.getTopScorers(UUID saveGameId, UUID competitionId, UUID userId) : List<TopScorerRow>` (sorted goals desc, then name).

- [ ] **Step 1: Add the repository finders**

In `MatchEventRepository.java`:

```java
package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MatchEventRepository extends JpaRepository<MatchEvent, UUID> {
    List<MatchEvent> findByFixtureIdInAndType(Collection<UUID> fixtureIds, String type);
    void deleteByFixtureIdIn(Collection<UUID> fixtureIds);
}
```

- [ ] **Step 2: Write the failing test**

`TopScorersServiceIT.java`:

```java
package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.match.RoundSimulationService;
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
class TopScorersServiceIT {

    @Autowired TopScorersService topScorersService;
    @Autowired StandingsService standingsService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired ClubRepository clubRepository;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("scorers_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void aggregatesGoalsAcrossPlayedFixturesSortedDescending() {
        // Given a career advanced through a full season
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).get(0).getId();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }

        // When computing top scorers
        List<TopScorerRow> scorers = topScorersService.getTopScorers(c.id(), competitionId, c.userId());

        // Then every scorer has >=1 goal, the list is sorted descending, and the
        // total goals equals the league-wide goals-for (every goal attributed once)
        assertThat(scorers).isNotEmpty();
        assertThat(scorers).allMatch(s -> s.goals() >= 1);
        for (int i = 1; i < scorers.size(); i++) {
            assertThat(scorers.get(i - 1).goals()).isGreaterThanOrEqualTo(scorers.get(i).goals());
        }
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        int totalGoalsFor = standingsService.getStandings(c.id(), competitionId, c.userId()).stream()
                .mapToInt(StandingRow::goalsFor).sum();
        int totalScored = scorers.stream().mapToInt(TopScorerRow::goals).sum();
        assertThat(totalScored).isEqualTo(totalGoalsFor);
        assertThat(scorers.get(0).clubName()).isIn(clubs.stream().map(Club::getName).toList());
    }

    @Test
    void foreignUserCannotReadTopScorers() {
        Career owner = createCareer();
        Career intruder = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(owner.id()).get(0).getId();
        assertThatThrownBy(() -> topScorersService.getTopScorers(owner.id(), competitionId, intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.competition.TopScorersServiceIT'`
Expected: COMPILE FAILURE — `TopScorersService`/`TopScorerRow` do not exist.

- [ ] **Step 4: Create TopScorerRow + TopScorerResponse**

`TopScorerRow.java`:

```java
package br.com.carvoeiromanager.competition;

import java.util.UUID;

public record TopScorerRow(UUID playerId, String playerName, UUID clubId, String clubName, int goals) {}
```

`TopScorerResponse.java`:

```java
package br.com.carvoeiromanager.competition;

public record TopScorerResponse(String playerName, String clubName, int goals) {}
```

- [ ] **Step 5: Create TopScorersService**

```java
package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregates GOAL match-events into a sorted top-scorer table for a competition.
 * Used live (end-of-season screen) and by the season archive at rollover.
 */
@Service
public class TopScorersService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final MatchEventRepository matchEventRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;

    public TopScorersService(SaveGameService saveGameService,
                             CompetitionRepository competitionRepository,
                             FixtureRepository fixtureRepository,
                             MatchEventRepository matchEventRepository,
                             PlayerRepository playerRepository,
                             ClubRepository clubRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
    }

    public List<TopScorerRow> getTopScorers(UUID saveGameId, UUID competitionId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Competition competition = competitionRepository.findById(competitionId)
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(
                        "COMPETITION_NOT_FOUND", "Competition not found", HttpStatus.NOT_FOUND));

        List<UUID> fixtureIds = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                .map(br.com.carvoeiromanager.competition.Fixture::getId)
                .toList();
        if (fixtureIds.isEmpty()) {
            return List.of();
        }

        List<MatchEvent> goals = matchEventRepository.findByFixtureIdInAndType(fixtureIds, "GOAL");
        if (goals.isEmpty()) {
            return List.of();
        }

        Map<UUID, Player> playerById = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Map<UUID, String> clubNameById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Long> goalsByPlayer = goals.stream()
                .collect(Collectors.groupingBy(MatchEvent::getPlayerId, Collectors.counting()));

        return goalsByPlayer.entrySet().stream()
                .map(entry -> {
                    Player p = playerById.get(entry.getKey());
                    String playerName = p != null ? p.getName() : "";
                    UUID clubId = p != null ? p.getClubId() : null;
                    String clubName = clubId != null ? clubNameById.getOrDefault(clubId, "") : "";
                    return new TopScorerRow(entry.getKey(), playerName, clubId, clubName,
                            entry.getValue().intValue());
                })
                .sorted(Comparator.comparingInt(TopScorerRow::goals).reversed()
                        .thenComparing(TopScorerRow::playerName))
                .toList();
    }
}
```

- [ ] **Step 6: Add the live top-scorers endpoint**

In `CompetitionController.java`: add `private final TopScorersService topScorersService;` as a second constructor parameter (assign it), add imports (`java.util.stream.Collectors` not needed — use `.map(...).toList()`), and add:

```java
    /** Current-season top scorers for a competition (used by the end-of-season screen). */
    @GetMapping("/{competitionId}/top-scorers")
    public ResponseEntity<List<TopScorerResponse>> getTopScorers(
            @PathVariable UUID saveGameId,
            @PathVariable UUID competitionId,
            @CurrentUser UUID userId) {
        List<TopScorerResponse> body = topScorersService
                .getTopScorers(saveGameId, competitionId, userId).stream()
                .map(r -> new TopScorerResponse(r.playerName(), r.clubName(), r.goals()))
                .toList();
        return ResponseEntity.ok(body);
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.competition.TopScorersServiceIT'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchEventRepository.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/TopScorerRow.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/TopScorerResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/TopScorersService.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionController.java \
        backend/src/test/java/br/com/carvoeiromanager/competition/TopScorersServiceIT.java
git commit -m "$(cat <<'EOF'
feat(competition): top-scorers aggregation + live endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: SeasonRolloverService (core rollover)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SquadChangeResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned`; `SaveGameRepository`; `CompetitionRepository.findBySaveGameId`; `FixtureRepository.findBySaveGameIdAndCompetitionId` + `deleteAll` + `save`; `MatchEventRepository.deleteByFixtureIdIn` (Task 6); `ClubRepository.findBySaveGameId`; `PlayerRepository` (`findBySaveGameId`/`saveAll`/`deleteAll`/`flush`); `LineupRepository.findBySaveGameIdAndClubId`/`delete`/`flush`; `LineupPlayerRepository.deleteByLineupId`; `StandingsService.getStandings` (Task baseline); `TopScorersService.getTopScorers` (Task 6); `SeasonRepository`/`SeasonStandingRepository`/`SeasonTopScorerRepository` (Task 2); `PlayerAgingService` (Task 4); `NewgenGenerator` (Task 5); `FixtureScheduler.doubleRoundRobin`; `Fixture.create`; `Competition.setSeasonYear`; `SaveGame.advanceToNextSeason`.
- Produces:
  - `SquadChangeResponse(String playerName, String position, int age)`.
  - `RolloverResponse(int seasonYear, int currentRound, List<SquadChangeResponse> retirements, List<SquadChangeResponse> newcomers)`.
  - `SeasonRolloverService.rollover(UUID saveGameId, UUID userId) : RolloverResponse` (`@Transactional`). Throws `ApiException("SEASON_NOT_FINISHED", ..., HttpStatus.UNPROCESSABLE_CONTENT)` (422) when the season is not finished, and 404 (via `requireOwned`) for a foreign user.

**Sequence (FK-safe — see Global Constraints):** owner+guard → archive each competition (standings + scorers + champion + managed position) → purge each competition's events then fixtures → **flush** → clear managed lineup → **flush** → age every player (id order, one seeded RNG), partition retiring vs carried, generate newgens, collect managed squad changes → delete retiring (**after flush**) then save carried+newgens → regenerate fixtures + bump competition year → `advanceToNextSeason` → return.

- [ ] **Step 1: Write the DTOs**

`SquadChangeResponse.java`:

```java
package br.com.carvoeiromanager.season;

public record SquadChangeResponse(String playerName, String position, int age) {}
```

`RolloverResponse.java`:

```java
package br.com.carvoeiromanager.season;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> newcomers
) {}
```

- [ ] **Step 2: Write the failing test**

`SeasonRolloverServiceIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.RoundSimulationService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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
class SeasonRolloverServiceIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired StandingsService standingsService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonStandingRepository seasonStandingRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("roll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private void finishSeason(Career c) {
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
    }

    @Test
    void rollingOverArchivesTheSeasonAndStartsAFreshOne() {
        // Given a finished season with a saved managed lineup
        Career c = createCareer();
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi), c.userId());
        finishSeason(c);

        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        UUID competitionId = competition.getId();
        int oldYear = competition.getSeasonYear();
        int totalRounds = competition.getTotalRounds();
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        int clubCount = clubs.size();
        List<StandingRow> finalTable = standingsService.getStandings(c.id(), competitionId, c.userId());
        String championName = finalTable.get(0).clubName();
        Map<UUID, Integer> agesBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Player::getId, Player::getAge));
        Map<UUID, Long> squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.groupingBy(Player::getClubId, Collectors.counting()));

        // When rolling over
        RolloverResponse response = rolloverService.rollover(c.id(), c.userId());

        // Then the save advanced to a fresh season
        assertThat(response.seasonYear()).isEqualTo(oldYear + 1);
        assertThat(response.currentRound()).isZero();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(save.getSeasonYear()).isEqualTo(oldYear + 1);
        assertThat(save.getCurrentRound()).isZero();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);

        // Competition rolled forward; fixtures regenerated and all unplayed
        Competition rolled = competitionRepository.findBySaveGameId(c.id()).get(0);
        assertThat(rolled.getSeasonYear()).isEqualTo(oldYear + 1);
        assertThat(rolled.getTotalRounds()).isEqualTo(totalRounds);
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), competitionId);
        assertThat(fixtures).hasSize(clubCount * (clubCount - 1));
        assertThat(fixtures).allMatch(f -> !f.isPlayed());
        assertThat(fixtures).allMatch(f -> f.getHomeGoals() == null && f.getAwayGoals() == null);

        // Archive captured the finished season
        List<Season> archived = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id());
        assertThat(archived).hasSize(1);
        Season season = archived.get(0);
        assertThat(season.getSeasonYear()).isEqualTo(oldYear);
        assertThat(season.getChampionClubName()).isEqualTo(championName);
        assertThat(season.getManagedClubPosition()).isBetween(1, clubCount);
        assertThat(seasonStandingRepository.findBySeasonIdOrderByPosition(season.getId()))
                .hasSize(clubCount)
                .extracting(SeasonStanding::getPosition).first().isEqualTo(1);
        assertThat(seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId())).isNotEmpty();

        // Players: every club's squad size is unchanged; carried players aged +1 and have clean state
        Map<UUID, Long> squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.groupingBy(Player::getClubId, Collectors.counting()));
        assertThat(squadSizeAfter).isEqualTo(squadSizeBefore);
        List<Player> after = playerRepository.findBySaveGameId(c.id());
        assertThat(after).allMatch(p -> p.getForm() == 0 && p.getYellowCards() == 0
                && p.getSuspensionRemaining() == 0);
        // Carried-over players (still present) are exactly one year older
        Map<UUID, Player> afterById = after.stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        agesBefore.forEach((id, ageBefore) -> {
            Player still = afterById.get(id);
            if (still != null) {
                assertThat(still.getAge()).isEqualTo(ageBefore + 1);
            }
        });

        // Managed lineup was cleared so the user re-picks the XI
        assertThat(lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId())).isEmpty();
    }

    @Test
    void rollingOverAnUnfinishedSeasonIsRejected() {
        // Given a fresh (not finished) career
        Career c = createCareer();

        // When attempting to roll over, then a 422 SEASON_NOT_FINISHED is thrown
        assertThatThrownBy(() -> rolloverService.rollover(c.id(), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SEASON_NOT_FINISHED");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void foreignUserCannotRollOver() {
        Career owner = createCareer();
        Career intruder = createCareer();
        finishSeason(owner);
        assertThatThrownBy(() -> rolloverService.rollover(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonRolloverServiceIT'`
Expected: COMPILE FAILURE — `SeasonRolloverService` does not exist.

- [ ] **Step 4: Create SeasonRolloverService**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.FixtureScheduler;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TopScorerRow;
import br.com.carvoeiromanager.competition.TopScorersService;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.NewgenGenerator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerAgingService;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rolls a finished career into the next season: archives the finished season,
 * then mutates the live world in place. Deterministic for a given save+year.
 *
 * FK-safe ordering: a competition's fixtures (whose match_events cascade) and the
 * managed club's lineup are deleted, and the persistence context flushed, BEFORE
 * any player is deleted, because match_events.player_id and lineup_players.player_id
 * reference players(id) WITHOUT ON DELETE CASCADE.
 */
@Service
public class SeasonRolloverService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final MatchEventRepository matchEventRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final StandingsService standingsService;
    private final TopScorersService topScorersService;
    private final SeasonRepository seasonRepository;
    private final SeasonStandingRepository seasonStandingRepository;
    private final SeasonTopScorerRepository seasonTopScorerRepository;
    private final PlayerAgingService playerAgingService;
    private final NewgenGenerator newgenGenerator;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public SeasonRolloverService(SaveGameService saveGameService,
                                 SaveGameRepository saveGameRepository,
                                 CompetitionRepository competitionRepository,
                                 FixtureRepository fixtureRepository,
                                 MatchEventRepository matchEventRepository,
                                 ClubRepository clubRepository,
                                 PlayerRepository playerRepository,
                                 LineupRepository lineupRepository,
                                 LineupPlayerRepository lineupPlayerRepository,
                                 StandingsService standingsService,
                                 TopScorersService topScorersService,
                                 SeasonRepository seasonRepository,
                                 SeasonStandingRepository seasonStandingRepository,
                                 SeasonTopScorerRepository seasonTopScorerRepository,
                                 PlayerAgingService playerAgingService,
                                 NewgenGenerator newgenGenerator) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.standingsService = standingsService;
        this.topScorersService = topScorersService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
        this.playerAgingService = playerAgingService;
        this.newgenGenerator = newgenGenerator;
    }

    @Transactional
    public RolloverResponse rollover(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() != SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_NOT_FINISHED",
                    "The current season is not finished yet", HttpStatus.UNPROCESSABLE_CONTENT);
        }

        final int finishingYear = saveGame.getSeasonYear();
        final UUID managedClubId = saveGame.getManagedClubId();
        final Random rng = new Random(
                saveGameId.getMostSignificantBits() ^ (long) finishingYear);

        final List<Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        final Map<UUID, String> clubNameById = clubs.stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        final List<Competition> competitions = competitionRepository.findBySaveGameId(saveGameId);

        // 1. Archive each competition from live data, then purge its results.
        for (Competition competition : competitions) {
            archive(saveGameId, userId, competition, finishingYear, managedClubId, clubNameById);

            List<UUID> fixtureIds = fixtureRepository
                    .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                    .map(Fixture::getId).toList();
            if (!fixtureIds.isEmpty()) {
                matchEventRepository.deleteByFixtureIdIn(fixtureIds);
            }
            fixtureRepository.deleteAll(
                    fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()));
        }
        // Ensure events + fixtures are gone before any player is deleted (FK safety).
        fixtureRepository.flush();

        // 2. Clear the managed club's lineup before deleting players (FK safety).
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
            lineupRepository.delete(lineup);
        });
        lineupRepository.flush();

        // 3. Age every player (id order, one seeded RNG); partition retiring vs carried.
        List<Player> players = new ArrayList<>(playerRepository.findBySaveGameId(saveGameId));
        players.sort(Comparator.comparing(Player::getId));

        List<SquadChangeResponse> retirements = new ArrayList<>();
        List<SquadChangeResponse> newcomers = new ArrayList<>();
        List<Player> toDelete = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();

        for (Player player : players) {
            player.setAge(player.getAge() + 1);
            playerAgingService.applyAgeCurve(player, rng);
            boolean retires = rng.nextDouble() < playerAgingService.retirementProbability(player.getAge());
            boolean managed = player.getClubId().equals(managedClubId);
            if (retires) {
                if (managed) {
                    retirements.add(new SquadChangeResponse(
                            player.getName(), player.getPosition().name(), player.getAge()));
                }
                Player newgen = newgenGenerator.generate(
                        saveGameId, player.getClubId(), player.getPosition(), rng);
                toSave.add(newgen);
                if (managed) {
                    newcomers.add(new SquadChangeResponse(
                            newgen.getName(), newgen.getPosition().name(), newgen.getAge()));
                }
                toDelete.add(player);
            } else {
                player.resetSeasonState();
                toSave.add(player);
            }
        }
        playerRepository.deleteAll(toDelete);
        playerRepository.flush();
        playerRepository.saveAll(toSave);

        // 4. Regenerate fixtures and roll each competition's year forward.
        List<Club> clubsSorted = clubs.stream()
                .sorted(Comparator.comparing(Club::getId)).toList();
        int clubCount = clubsSorted.size();
        for (Competition competition : competitions) {
            fixtureScheduler.doubleRoundRobin(clubCount).forEach(scheduled -> {
                UUID home = clubsSorted.get(scheduled.homeIndex()).getId();
                UUID away = clubsSorted.get(scheduled.awayIndex()).getId();
                fixtureRepository.save(Fixture.create(
                        saveGameId, competition.getId(), scheduled.round(), home, away));
            });
            competition.setSeasonYear(finishingYear + 1);
            competitionRepository.save(competition);
        }

        // 5. Advance the save into the new season.
        saveGame.advanceToNextSeason();
        saveGameRepository.save(saveGame);

        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers));
    }

    private void archive(UUID saveGameId, UUID userId, Competition competition, int finishingYear,
                         UUID managedClubId, Map<UUID, String> clubNameById) {
        List<StandingRow> table = standingsService.getStandings(saveGameId, competition.getId(), userId);
        List<TopScorerRow> scorers = topScorersService.getTopScorers(saveGameId, competition.getId(), userId);

        StandingRow champion = table.get(0);
        int managedPosition = positionOf(table, managedClubId);
        String managedName = clubNameById.getOrDefault(managedClubId, "");

        Season season = Season.create(saveGameId, competition.getId(), finishingYear,
                competition.getName(), champion.clubId(), champion.clubName(),
                managedClubId, managedName, managedPosition);
        seasonRepository.save(season);

        int position = 1;
        for (StandingRow row : table) {
            seasonStandingRepository.save(SeasonStanding.create(season.getId(), position++,
                    row.clubId(), row.clubName(), row.played(), row.won(), row.drawn(), row.lost(),
                    row.goalsFor(), row.goalsAgainst(), row.goalDifference(), row.points()));
        }
        int rank = 1;
        for (TopScorerRow scorer : scorers) {
            seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), rank++,
                    scorer.playerId(), scorer.playerName(), scorer.clubId(), scorer.clubName(),
                    scorer.goals()));
        }
    }

    private int positionOf(List<StandingRow> table, UUID managedClubId) {
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).clubId().equals(managedClubId)) {
                return i + 1;
            }
        }
        return table.size(); // defensive: managed club should always be present
    }
}
```

> Note (vs spec §8 step ordering): the spec listed "clear lineup" after retirement; this plan moves the lineup clear (and event/fixture purge) **before** player deletion and adds explicit `flush()` calls, because the `players(id)` FKs have no cascade. Functionally identical end state; this is the only correct delete order.

> Note (determinism): cross-save re-run determinism is not asserted at the IT level (two careers have different `saveGameId`s → different seeds). Determinism is guaranteed by the seeded building blocks unit-tested in Tasks 4–5 (`PlayerAgingServiceTest.theCurveIsDeterministicForAGivenSeed`, `NewgenGeneratorTest.generationIsDeterministicForAGivenSeed`).

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonRolloverServiceIT'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SquadChangeResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java
git commit -m "$(cat <<'EOF'
feat(season): SeasonRolloverService - archive, age/retire/regen, advance

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: SeasonHistoryService + SeasonController (history + rollover endpoints)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonSummaryResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonStandingResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonDetailResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonHistoryService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonHistoryServiceIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned`; `SeasonRepository` (Task 2); `SeasonStandingRepository`; `SeasonTopScorerRepository`; `TopScorerResponse` (Task 6); `SeasonRolloverService.rollover` + `RolloverResponse` (Task 7).
- Produces:
  - `SeasonSummaryResponse(int seasonYear, String competitionName, String championClubName, int managedClubPosition, String topScorerName, int topScorerGoals)`.
  - `SeasonStandingResponse(int position, String clubName, int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst, int goalDifference, int points)`.
  - `SeasonDetailResponse(int seasonYear, String competitionName, String championClubName, List<SeasonStandingResponse> table, List<TopScorerResponse> topScorers)`.
  - `SeasonHistoryService.getHistory(UUID saveGameId, UUID userId) : List<SeasonSummaryResponse>` (newest first); `getSeason(UUID saveGameId, int seasonYear, UUID userId) : SeasonDetailResponse` (throws `ApiException("SEASON_NOT_FOUND", ..., HttpStatus.NOT_FOUND)`).
  - `SeasonController`: `POST /api/v1/save-games/{saveGameId}/seasons/rollover`, `GET /api/v1/save-games/{saveGameId}/seasons`, `GET /api/v1/save-games/{saveGameId}/seasons/{seasonYear}`.

- [ ] **Step 1: Write the DTOs**

`SeasonSummaryResponse.java`:

```java
package br.com.carvoeiromanager.season;

public record SeasonSummaryResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        int managedClubPosition,
        String topScorerName,
        int topScorerGoals
) {}
```

`SeasonStandingResponse.java`:

```java
package br.com.carvoeiromanager.season;

public record SeasonStandingResponse(
        int position,
        String clubName,
        int played,
        int won,
        int drawn,
        int lost,
        int goalsFor,
        int goalsAgainst,
        int goalDifference,
        int points
) {}
```

`SeasonDetailResponse.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.competition.TopScorerResponse;

import java.util.List;

public record SeasonDetailResponse(
        int seasonYear,
        String competitionName,
        String championClubName,
        List<SeasonStandingResponse> table,
        List<TopScorerResponse> topScorers
) {}
```

- [ ] **Step 2: Write the failing test**

`SeasonHistoryServiceIT.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.match.RoundSimulationService;
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
class SeasonHistoryServiceIT {

    @Autowired SeasonHistoryService historyService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("hist_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private int finishAndRollOver(Career c) {
        int year = competitionRepository.findBySaveGameId(c.id()).get(0).getSeasonYear();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
        rolloverService.rollover(c.id(), c.userId());
        return year;
    }

    @Test
    void historyListsArchivedSeasonsNewestFirst() {
        // Given a career rolled over once
        Career c = createCareer();
        int firstYear = finishAndRollOver(c);

        // When listing history
        List<SeasonSummaryResponse> history = historyService.getHistory(c.id(), c.userId());

        // Then the finished season appears with its champion and the managed placing
        assertThat(history).hasSize(1);
        SeasonSummaryResponse summary = history.get(0);
        assertThat(summary.seasonYear()).isEqualTo(firstYear);
        assertThat(summary.championClubName()).isNotBlank();
        assertThat(summary.managedClubPosition()).isBetween(1, 10);
        assertThat(summary.topScorerGoals()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void detailReturnsTheFullArchivedTableAndScorers() {
        // Given a career rolled over once
        Career c = createCareer();
        int year = finishAndRollOver(c);

        // When fetching the season detail
        SeasonDetailResponse detail = historyService.getSeason(c.id(), year, c.userId());

        // Then the full table (10 clubs, ordered) and scorers are present
        assertThat(detail.seasonYear()).isEqualTo(year);
        assertThat(detail.table()).hasSize(10);
        assertThat(detail.table().get(0).position()).isEqualTo(1);
        assertThat(detail.topScorers()).isNotEmpty();
    }

    @Test
    void unknownSeasonYearIsNotFound() {
        Career c = createCareer();
        assertThatThrownBy(() -> historyService.getSeason(c.id(), 1999, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SEASON_NOT_FOUND");
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void foreignUserCannotReadHistoryOrDetail() {
        Career owner = createCareer();
        Career intruder = createCareer();
        int year = finishAndRollOver(owner);
        assertThatThrownBy(() -> historyService.getHistory(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> historyService.getSeason(owner.id(), year, intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonHistoryServiceIT'`
Expected: COMPILE FAILURE — `SeasonHistoryService` does not exist.

- [ ] **Step 4: Create SeasonHistoryService**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.TopScorerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SeasonHistoryService {

    private final SaveGameService saveGameService;
    private final SeasonRepository seasonRepository;
    private final SeasonStandingRepository seasonStandingRepository;
    private final SeasonTopScorerRepository seasonTopScorerRepository;

    public SeasonHistoryService(SaveGameService saveGameService,
                                SeasonRepository seasonRepository,
                                SeasonStandingRepository seasonStandingRepository,
                                SeasonTopScorerRepository seasonTopScorerRepository) {
        this.saveGameService = saveGameService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
    }

    public List<SeasonSummaryResponse> getHistory(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(saveGameId).stream()
                .map(this::toSummary)
                .toList();
    }

    public SeasonDetailResponse getSeason(UUID saveGameId, int seasonYear, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Season season = seasonRepository.findBySaveGameIdAndSeasonYear(saveGameId, seasonYear)
                .orElseThrow(() -> new ApiException(
                        "SEASON_NOT_FOUND", "Season not found", HttpStatus.NOT_FOUND));

        List<SeasonStandingResponse> table = seasonStandingRepository
                .findBySeasonIdOrderByPosition(season.getId()).stream()
                .map(s -> new SeasonStandingResponse(s.getPosition(), s.getClubName(), s.getPlayed(),
                        s.getWon(), s.getDrawn(), s.getLost(), s.getGoalsFor(), s.getGoalsAgainst(),
                        s.getGoalDifference(), s.getPoints()))
                .toList();

        List<TopScorerResponse> scorers = seasonTopScorerRepository
                .findBySeasonIdOrderByRank(season.getId()).stream()
                .map(s -> new TopScorerResponse(s.getPlayerName(), s.getClubName(), s.getGoals()))
                .toList();

        return new SeasonDetailResponse(season.getSeasonYear(), season.getCompetitionName(),
                season.getChampionClubName(), table, scorers);
    }

    private SeasonSummaryResponse toSummary(Season season) {
        List<SeasonTopScorer> scorers = seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId());
        String topName = scorers.isEmpty() ? null : scorers.get(0).getPlayerName();
        int topGoals = scorers.isEmpty() ? 0 : scorers.get(0).getGoals();
        return new SeasonSummaryResponse(season.getSeasonYear(), season.getCompetitionName(),
                season.getChampionClubName(), season.getManagedClubPosition(), topName, topGoals);
    }
}
```

- [ ] **Step 5: Create SeasonController**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/seasons")
public class SeasonController {

    private final SeasonRolloverService rolloverService;
    private final SeasonHistoryService historyService;

    public SeasonController(SeasonRolloverService rolloverService, SeasonHistoryService historyService) {
        this.rolloverService = rolloverService;
        this.historyService = historyService;
    }

    /** Rolls the finished season into the next one (archive + age/retire/regen). */
    @PostMapping("/rollover")
    public ResponseEntity<RolloverResponse> rollover(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(rolloverService.rollover(saveGameId, userId));
    }

    /** Lists archived seasons (newest first). */
    @GetMapping
    public ResponseEntity<List<SeasonSummaryResponse>> history(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(historyService.getHistory(saveGameId, userId));
    }

    /** One archived season: full table + top scorers. */
    @GetMapping("/{seasonYear}")
    public ResponseEntity<SeasonDetailResponse> detail(
            @PathVariable UUID saveGameId, @PathVariable int seasonYear, @CurrentUser UUID userId) {
        return ResponseEntity.ok(historyService.getSeason(saveGameId, seasonYear, userId));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonHistoryServiceIT'`
Expected: PASS (4 tests).

- [ ] **Step 7: Run the full backend suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test`
Expected: PASS (all prior + new tests green).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonSummaryResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonStandingResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonDetailResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonHistoryService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonController.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonHistoryServiceIT.java
git commit -m "$(cat <<'EOF'
feat(season): history service + seasons REST controller (rollover/list/detail)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Frontend — end-of-season summary + rollover

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Create: `frontend/src/features/season/SeasonSummaryPage.tsx`
- Create: `frontend/src/features/season/SeasonSummaryPage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/features/match/AdvancePanel.tsx`
- Modify: `frontend/src/features/match/AdvancePanel.test.tsx`

**Interfaces:**
- Consumes: backend `GET /save-games/{s}/competitions/{c}/top-scorers` (Task 6), `POST /save-games/{s}/seasons/rollover` (Task 8), existing `getStandings`.
- Produces (types): `TopScorerResponse`, `SquadChange`, `RolloverResponse`. (client): `getTopScorers(saveGameId, competitionId, token)`, `rolloverSeason(saveGameId, token)`. Route `/season-summary`.

- [ ] **Step 1: Add the types**

Append to `frontend/src/api/types.ts`:

```ts
export type TopScorerResponse = {
  playerName: string
  clubName: string
  goals: number
}

export type SquadChange = {
  playerName: string
  position: string
  age: number
}

export type RolloverResponse = {
  seasonYear: number
  currentRound: number
  retirements: SquadChange[]
  newcomers: SquadChange[]
}
```

- [ ] **Step 2: Add the client functions**

In `frontend/src/api/career.ts`, add `RolloverResponse` and `TopScorerResponse` to the type import block, then append:

```ts
export function getTopScorers(
  saveGameId: string,
  competitionId: string,
  token: string,
): Promise<TopScorerResponse[]> {
  return apiGet<TopScorerResponse[]>(
    `/save-games/${saveGameId}/competitions/${competitionId}/top-scorers`,
    token,
  )
}

export function rolloverSeason(saveGameId: string, token: string): Promise<RolloverResponse> {
  return apiPost<RolloverResponse>(`/save-games/${saveGameId}/seasons/rollover`, {}, token)
}
```

- [ ] **Step 3: Write the failing component test**

`frontend/src/features/season/SeasonSummaryPage.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SeasonSummaryPage } from './SeasonSummaryPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getStandings: vi.fn(),
  getTopScorers: vi.fn(),
  rolloverSeason: vi.fn(),
}))

import { getStandings, getTopScorers, rolloverSeason } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/season-summary?saveGameId=sg-1&competitionId=c-1&managedClubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <SeasonSummaryPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('SeasonSummaryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getStandings).mockResolvedValue([
      { clubId: 'club-9', clubName: 'Campeao FC', played: 18, won: 14, drawn: 2, lost: 2, goalsFor: 40, goalsAgainst: 12, goalDifference: 28, points: 44 },
      { clubId: 'club-1', clubName: 'Criciúma EC', played: 18, won: 12, drawn: 4, lost: 2, goalsFor: 35, goalsAgainst: 15, goalDifference: 20, points: 40 },
    ])
    vi.mocked(getTopScorers).mockResolvedValue([
      { playerName: 'Artilheiro', clubName: 'Campeao FC', goals: 21 },
    ])
  })

  it('shows the champion, table and top scorers', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText(/Campeão: Campeao FC/i)).toBeInTheDocument())
    expect(screen.getByText('Criciúma EC')).toBeInTheDocument()
    expect(screen.getByText(/Artilheiro/)).toBeInTheDocument()
  })

  it('rolls over and shows squad changes', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [{ playerName: 'Veterano', position: 'DEF', age: 38 }],
      newcomers: [{ playerName: 'Jovem Silva', position: 'DEF', age: 17 }],
    })

    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    await waitFor(() => expect(screen.getByText(/Aposentadorias/i)).toBeInTheDocument())
    expect(screen.getByText(/Veterano/)).toBeInTheDocument()
    expect(screen.getByText(/Jovem Silva/)).toBeInTheDocument()
    expect(rolloverSeason).toHaveBeenCalledWith('sg-1', 'test-token')
  })
})
```

- [ ] **Step 4: Run the test to verify it fails**

Run (from `frontend/`): `npm run test -- SeasonSummaryPage`
Expected: FAIL — `SeasonSummaryPage` module does not exist.

- [ ] **Step 5: Create SeasonSummaryPage**

`frontend/src/features/season/SeasonSummaryPage.tsx`:

```tsx
import { useQuery, useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { getStandings, getTopScorers, rolloverSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { RolloverResponse } from '../../api/types'

export function SeasonSummaryPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const competitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  const standings = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const scorers = useQuery({
    queryKey: ['topScorers', saveGameId, competitionId],
    queryFn: () => getTopScorers(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const rollover = useMutation<RolloverResponse>({
    mutationFn: () => rolloverSeason(saveGameId, token ?? ''),
  })

  if (standings.isLoading) return <p>Carregando resumo da temporada…</p>
  if (standings.error) return <p>Erro ao carregar o resumo da temporada.</p>

  const table = standings.data ?? []
  const champion = table[0]
  const result = rollover.data

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Fim de Temporada</h2>
      {champion && (
        <p style={{ fontWeight: 'bold', color: 'green' }}>Campeão: {champion.clubName} 🏆</p>
      )}

      <h3>Classificação Final</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'center' }}>Pos</th>
            <th style={{ textAlign: 'left' }}>Clube</th>
            <th style={{ textAlign: 'center' }}>P</th>
            <th style={{ textAlign: 'center' }}>GD</th>
            <th style={{ textAlign: 'center' }}>Pts</th>
          </tr>
        </thead>
        <tbody>
          {table.map((row, index) => {
            const isManaged = row.clubId === managedClubId
            return (
              <tr key={row.clubId} data-managed={isManaged ? 'true' : undefined}
                  style={{ fontWeight: isManaged ? 'bold' : undefined }}>
                <td style={{ textAlign: 'center' }}>{index + 1}</td>
                <td>{row.clubName}</td>
                <td style={{ textAlign: 'center' }}>{row.played}</td>
                <td style={{ textAlign: 'center' }}>{row.goalDifference}</td>
                <td style={{ textAlign: 'center' }}>{row.points}</td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <h3>Artilheiros</h3>
      <ul>
        {(scorers.data ?? []).map((s, i) => (
          <li key={`${s.playerName}-${i}`}>{s.playerName} ({s.clubName}) — {s.goals}</li>
        ))}
      </ul>

      {!result && (
        <button
          className="cec-btn"
          disabled={rollover.isPending}
          onClick={() => rollover.mutate()}
        >
          {rollover.isPending ? 'Processando…' : 'Iniciar próxima temporada'}
        </button>
      )}
      {rollover.isError && <p style={{ color: 'red' }}>Erro ao iniciar a próxima temporada.</p>}

      {result && (
        <div style={{ marginTop: '1rem' }}>
          <p style={{ color: 'green' }}>Temporada {result.seasonYear} iniciada!</p>
          {result.retirements.length > 0 && (
            <div>
              <h4>Aposentadorias</h4>
              <ul>
                {result.retirements.map((c, i) => (
                  <li key={`ret-${i}`}>{c.playerName} ({c.position}, {c.age} anos)</li>
                ))}
              </ul>
            </div>
          )}
          {result.newcomers.length > 0 && (
            <div>
              <h4>Novatos</h4>
              <ul>
                {result.newcomers.map((c, i) => (
                  <li key={`new-${i}`}>{c.playerName} ({c.position}, {c.age} anos)</li>
                ))}
              </ul>
            </div>
          )}
          <Link to="/dashboard"><button className="cec-btn">Voltar ao painel</button></Link>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 6: Add the route**

In `frontend/src/router.tsx`: import `SeasonSummaryPage` and add a protected route:

```tsx
import { SeasonSummaryPage } from './features/season/SeasonSummaryPage'
```

```tsx
      <Route
        path="/season-summary"
        element={
          <ProtectedRoute>
            <SeasonSummaryPage />
          </ProtectedRoute>
        }
      />
```

- [ ] **Step 7: Wire the AdvancePanel to link to the summary**

In `frontend/src/features/match/AdvancePanel.tsx`, import `Link`:

```tsx
import { Link } from 'react-router-dom'
```

Replace the existing "Temporada encerrada!" block (lines ~39–41) with one that also links to the summary, passing the managed club through (add `managedClubId` to the props):

```tsx
interface AdvancePanelProps {
  saveGameId: string
  competitionId: string
  managedClubId: string
}

export function AdvancePanel({ saveGameId, competitionId, managedClubId }: AdvancePanelProps) {
```

```tsx
      {result?.seasonFinished && (
        <div style={{ marginTop: '0.5rem' }}>
          <p style={{ color: 'green', fontWeight: 'bold' }}>Temporada encerrada!</p>
          <Link
            to={`/season-summary?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`}
          >
            <button className="cec-btn">Ver resumo da temporada</button>
          </Link>
        </div>
      )}
```

In `frontend/src/features/career/DashboardPage.tsx`, update the `AdvancePanel` usage to pass the managed club:

```tsx
            <AdvancePanel
              saveGameId={saveGame.id}
              competitionId={saveGame.competitionId}
              managedClubId={saveGame.managedClubId}
            />
```

- [ ] **Step 8: Update the AdvancePanel test for the new required prop + link**

In `frontend/src/features/match/AdvancePanel.test.tsx`, update `renderPanel` to pass `managedClubId="club-1"`, and add a test:

```tsx
  it('links to the season summary when the season finishes', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 18, results: [], seasonFinished: true, bookings: [], newSuspensions: [],
    })
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))
    await waitFor(() => expect(screen.getByText(/Temporada encerrada/i)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
  })
```

(Update the existing `<AdvancePanel saveGameId="sg-1" competitionId="c-1" />` in `renderPanel` to `<AdvancePanel saveGameId="sg-1" competitionId="c-1" managedClubId="club-1" />`.)

- [ ] **Step 9: Run the tests to verify they pass + build**

Run (from `frontend/`):
- `npm run test -- SeasonSummaryPage AdvancePanel`
- `npm run build`

Expected: tests PASS; `tsc -b && vite build` succeeds.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts \
        frontend/src/features/season/SeasonSummaryPage.tsx \
        frontend/src/features/season/SeasonSummaryPage.test.tsx \
        frontend/src/router.tsx \
        frontend/src/features/match/AdvancePanel.tsx \
        frontend/src/features/match/AdvancePanel.test.tsx \
        frontend/src/features/career/DashboardPage.tsx
git commit -m "$(cat <<'EOF'
feat(ui): end-of-season summary screen + season rollover

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Frontend — season history view + dashboard links

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Create: `frontend/src/features/season/SeasonHistoryPage.tsx`
- Create: `frontend/src/features/season/SeasonHistoryPage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/features/career/DashboardPage.tsx`

**Interfaces:**
- Consumes: backend `GET /save-games/{s}/seasons` and `GET /save-games/{s}/seasons/{year}` (Task 8); existing `TopScorerResponse` type (Task 9).
- Produces (types): `SeasonSummary`, `SeasonStandingRow`, `SeasonDetail`. (client): `getSeasonHistory(saveGameId, token)`, `getSeason(saveGameId, seasonYear, token)`. Route `/history`.

- [ ] **Step 1: Add the types**

Append to `frontend/src/api/types.ts`:

```ts
export type SeasonSummary = {
  seasonYear: number
  competitionName: string
  championClubName: string
  managedClubPosition: number
  topScorerName: string | null
  topScorerGoals: number
}

export type SeasonStandingRow = {
  position: number
  clubName: string
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  goalDifference: number
  points: number
}

export type SeasonDetail = {
  seasonYear: number
  competitionName: string
  championClubName: string
  table: SeasonStandingRow[]
  topScorers: TopScorerResponse[]
}
```

- [ ] **Step 2: Add the client functions**

In `frontend/src/api/career.ts`, add `SeasonSummary` and `SeasonDetail` to the type import block, then append:

```ts
export function getSeasonHistory(saveGameId: string, token: string): Promise<SeasonSummary[]> {
  return apiGet<SeasonSummary[]>(`/save-games/${saveGameId}/seasons`, token)
}

export function getSeason(
  saveGameId: string,
  seasonYear: number,
  token: string,
): Promise<SeasonDetail> {
  return apiGet<SeasonDetail>(`/save-games/${saveGameId}/seasons/${seasonYear}`, token)
}
```

- [ ] **Step 3: Write the failing component test**

`frontend/src/features/season/SeasonHistoryPage.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SeasonHistoryPage } from './SeasonHistoryPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getSeasonHistory: vi.fn(),
  getSeason: vi.fn(),
}))

import { getSeasonHistory, getSeason } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/history?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <SeasonHistoryPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('SeasonHistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSeasonHistory).mockResolvedValue([
      { seasonYear: 2026, competitionName: 'Campeonato Catarinense', championClubName: 'Campeao FC', managedClubPosition: 2, topScorerName: 'Artilheiro', topScorerGoals: 21 },
    ])
  })

  it('lists past seasons with champion and placing', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Campeao FC')).toBeInTheDocument())
    expect(screen.getByText(/2026/)).toBeInTheDocument()
  })

  it('loads the season detail when a season is selected', async () => {
    vi.mocked(getSeason).mockResolvedValue({
      seasonYear: 2026,
      competitionName: 'Campeonato Catarinense',
      championClubName: 'Campeao FC',
      table: [
        { position: 1, clubName: 'Campeao FC', played: 18, won: 14, drawn: 2, lost: 2, goalsFor: 40, goalsAgainst: 12, goalDifference: 28, points: 44 },
      ],
      topScorers: [{ playerName: 'Artilheiro', clubName: 'Campeao FC', goals: 21 }],
    })

    renderPage()
    await waitFor(() => screen.getByText('Campeao FC'))
    fireEvent.click(screen.getByRole('button', { name: /ver 2026/i }))

    await waitFor(() => expect(screen.getByText(/Artilheiro/)).toBeInTheDocument())
    expect(getSeason).toHaveBeenCalledWith('sg-1', 2026, 'test-token')
  })
})
```

- [ ] **Step 4: Run the test to verify it fails**

Run (from `frontend/`): `npm run test -- SeasonHistoryPage`
Expected: FAIL — `SeasonHistoryPage` module does not exist.

- [ ] **Step 5: Create SeasonHistoryPage**

`frontend/src/features/season/SeasonHistoryPage.tsx`:

```tsx
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSeasonHistory, getSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'

export function SeasonHistoryPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [selectedYear, setSelectedYear] = useState<number | null>(null)

  const history = useQuery({
    queryKey: ['seasonHistory', saveGameId],
    queryFn: () => getSeasonHistory(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  const detail = useQuery({
    queryKey: ['season', saveGameId, selectedYear],
    queryFn: () => getSeason(saveGameId, selectedYear!, token ?? ''),
    enabled: Boolean(saveGameId && token && selectedYear !== null),
  })

  if (history.isLoading) return <p>Carregando histórico…</p>
  if (history.error) return <p>Erro ao carregar o histórico.</p>

  const seasons = history.data ?? []

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Histórico de Temporadas</h2>
      {seasons.length === 0 && <p>Nenhuma temporada concluída ainda.</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Ano</th>
            <th style={{ textAlign: 'left' }}>Campeão</th>
            <th style={{ textAlign: 'center' }}>Sua posição</th>
            <th style={{ textAlign: 'left' }}>Artilheiro</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {seasons.map(s => (
            <tr key={s.seasonYear}>
              <td>{s.seasonYear}</td>
              <td>{s.championClubName}</td>
              <td style={{ textAlign: 'center' }}>{s.managedClubPosition}º</td>
              <td>{s.topScorerName ?? '—'} {s.topScorerName ? `(${s.topScorerGoals})` : ''}</td>
              <td>
                <button className="cec-btn" onClick={() => setSelectedYear(s.seasonYear)}>
                  Ver {s.seasonYear}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selectedYear !== null && detail.isLoading && <p>Carregando temporada {selectedYear}…</p>}
      {detail.data && (
        <div>
          <h3>{detail.data.competitionName} {detail.data.seasonYear}</h3>
          <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'center' }}>Pos</th>
                <th style={{ textAlign: 'left' }}>Clube</th>
                <th style={{ textAlign: 'center' }}>P</th>
                <th style={{ textAlign: 'center' }}>GD</th>
                <th style={{ textAlign: 'center' }}>Pts</th>
              </tr>
            </thead>
            <tbody>
              {detail.data.table.map(row => (
                <tr key={row.position}>
                  <td style={{ textAlign: 'center' }}>{row.position}</td>
                  <td>{row.clubName}</td>
                  <td style={{ textAlign: 'center' }}>{row.played}</td>
                  <td style={{ textAlign: 'center' }}>{row.goalDifference}</td>
                  <td style={{ textAlign: 'center' }}>{row.points}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h4>Artilheiros</h4>
          <ul>
            {detail.data.topScorers.map((s, i) => (
              <li key={`${s.playerName}-${i}`}>{s.playerName} ({s.clubName}) — {s.goals}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 6: Add the route**

In `frontend/src/router.tsx`: import `SeasonHistoryPage` and add a protected route:

```tsx
import { SeasonHistoryPage } from './features/season/SeasonHistoryPage'
```

```tsx
      <Route
        path="/history"
        element={
          <ProtectedRoute>
            <SeasonHistoryPage />
          </ProtectedRoute>
        }
      />
```

- [ ] **Step 7: Add the dashboard link**

In `frontend/src/features/career/DashboardPage.tsx`, add a history link alongside the existing Escalação/Classificação buttons (inside the `display:flex` button row):

```tsx
              <Link to={`/history?saveGameId=${saveGame.id}`}>
                <button className="cec-btn">Histórico</button>
              </Link>
```

- [ ] **Step 8: Run the tests + full frontend suite + build**

Run (from `frontend/`):
- `npm run test -- SeasonHistoryPage`
- `npm run test`
- `npm run build`

Expected: all PASS; build succeeds.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts \
        frontend/src/features/season/SeasonHistoryPage.tsx \
        frontend/src/features/season/SeasonHistoryPage.test.tsx \
        frontend/src/router.tsx \
        frontend/src/features/career/DashboardPage.tsx
git commit -m "$(cat <<'EOF'
feat(ui): season history view + dashboard link

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

**Spec coverage** (against `...-phase-2e-multi-season-design.md`):
- §5 V4 tables → Task 2. §6 Java mutations → Tasks 1 (OverallCalculator), 3 (Player/SaveGame/Competition). §7 top scorers → Task 6. §8 rollover sequence → Task 7. §9 evolution rules → Tasks 4 (curve+retirement), 5 (newgens). §10 API → Tasks 6 (top-scorers endpoint), 8 (rollover/list/detail). §11 frontend → Tasks 9 (summary), 10 (history). §12 errors (`SEASON_NOT_FINISHED` 422, `SEASON_NOT_FOUND` 404, IDOR 404) → Tasks 7, 8. §13 testing → every task is TDD; coverage matches the spec's test list. No spec requirement is left without a task.
- **Deliberate deviation from spec §8 step order:** lineup-clear + event/fixture purge happen **before** player deletion (with `flush()`), because `players(id)` FKs lack cascade. Documented inline in Task 7.
- **Deliberate test-scope refinement:** cross-save re-run determinism is asserted via the seeded building blocks (Tasks 4–5 unit tests), not at the rollover IT level (distinct saves → distinct seeds). Documented inline in Task 7.

**Placeholder scan:** no TBD/“handle errors”/“similar to”/uncoded steps; every code step shows full code.

**Type consistency:** `RolloverResponse`/`SquadChangeResponse` defined in Task 7 and consumed by Tasks 8–9; `TopScorerRow`/`TopScorerResponse` defined in Task 6 and reused in Tasks 7–9; `Season*` entities/repos from Task 2 used by Tasks 7–8; `OverallCalculator` (Task 1) consumed by Tasks 4, 5, 7; `evolveAttributes`/`resetSeasonState`/`advanceToNextSeason`/`setSeasonYear` (Task 3) consumed by Tasks 4, 7. Frontend `getTopScorers`/`rolloverSeason` (Task 9) and `getSeasonHistory`/`getSeason` (Task 10) match their backend endpoints. `AdvancePanel` gains a required `managedClubId` prop in Task 9 with its test + dashboard caller updated in the same task.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-26-carvoeiro-manager-phase-2e-multi-season.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, two-stage review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session via executing-plans, batch execution with checkpoints.

Which approach?
