# Board Objectives and Job Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the manager a board (*a diretoria*) that sets three objectives each season (league, state championship, finances), reacts to every managed match through a 0–100 confidence value, issues an ultimatum, and can sack the manager mid-season or at the season-end review. League targets exist for every national division (Série A, B and C).

**Architecture:** A new `board/` package. All arithmetic lives in pure classes (`ClubStrength`, `ObjectiveTargets`, `BoardConfidenceCalculator`, `BoardRules`, `StageLadder`) with no repositories and no RNG, so the tuning is unit-tested exhaustively. `BoardService` gathers inputs and persists outcomes. It is called from exactly four places: career creation, `SeasonAdvanceService.recordMatchRound` (per match and at season end), and the end of `SeasonRolloverService.rollover`. Competitions are found by a new `scope` column (`NATIONAL`/`STATE`) and divisions by a new `tier` column (1/2/3), never by name. Board news reuses the existing news pipeline: a sparse `AdvanceResponse` with a new nullable `board` field.

**Tech Stack:** Java 25 / Spring Boot / JPA (plain UUID FK columns, no associations) / Flyway / Jackson 3 / JUnit 5 + AssertJ + Testcontainers (Postgres); React 19 / TypeScript / MUI / TanStack Query / Vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-21-board-objectives-job-security-design.md`

## About the test code in this plan

Pure-class tests are written out in full and should be used verbatim. **Integration tests and
frontend component tests are specified by their assertions and fixture requirements**, because they
depend on helpers in classes this plan's author read only in part (`SeasonAdvanceServiceIT`'s
career/advance helpers, `LiveMatchServiceIT`'s kickoff/finish flow, `SeasonRolloverServiceIT`, the
dashboard's test harness). **Read the named class first, then write the test in full.** The listed
assertions are the requirement; none may be dropped or weakened to make a fixture easier.

## Global Constraints

- Work on the branch you were given. An untracked `CONTEXT.md` at the repo root is not yours: `git add` only the files you changed, never `git add -A` / `git add .`.
- **Every Gradle call pins Java 25.** On the maintainer's Mac:
  `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '<pattern>'; echo "EXIT=$?"`
  Elsewhere, point `JAVA_HOME` at any JDK 25. Capture the exit code directly, never after a pipe.
- Integration tests (`*IT`) need Docker. If **every** IT fails at once in the context loader, Docker is down: start it and retry, do not debug the code.
- Run Gradle as a **blocking** call. Do not run the full backend suite per task (~1h); run the `--tests` filters each task names. Task 16 runs everything once.
- `TransferServiceSellingIT` fails ~26% of runs on clean `main`. It is not caused by this work.
- Frontend type-check is `npx tsc -b` from `frontend/`. **`tsc --noEmit` is a no-op in this repo.** Tests: `npx vitest run <path>`; lint: `npm run lint`.
- UI copy is pt-BR, exactly as written in this plan. The backend sends enums and numbers only (spec §10).
- TDD: write the failing test, run it and watch it fail, then implement.
- Match the codebase's comment density: every load-bearing ordering or invariant introduced here gets a comment at its site.
- The test profile loads only Catarinense + Série B (`application-test.yml`). Tests that need Série A or C opt back in with `@TestPropertySource(properties = "carvoeiro.seed.competitions=")`, as `TwoCompetitionCareerIT` does.

## Planning rulings against the spec

These were settled while reading the code. They are binding and supersede the spec where they conflict.

- **R1 — Grace season behaves like sacking disabled.** Wherever the manager *would* be sacked during the grace season, the averted path runs (§7.4): `BOARD_SACKING_AVERTED`, confidence raised to 30. The spec said only "cannot sack".
- **R2 — `matches_since_ultimatum` counts after the issuing match.** The match that triggers the ultimatum sets it to 0. Each later managed match increments it **before** the rules are evaluated, so the third match after the ultimatum is the first that can sack.
- **R3 — Stage ladder from the phase list, not phase names.** For a phased competition with `n` knockout phases after one round-robin phase, knockout phase `i` (0-based) is stage `CHAMPION.ordinal() − (n − i)`. With today's three knockout phases that is QUARTERFINAL, SEMIFINAL, FINAL. More than three knockout phases throws at objective-setting time, with a message telling the next author to extend `Stage`.
- **R4 — League matches played** is the count of `isPlayed()` fixtures of the national competition involving the managed club, all phases included.
- **R5 — The trajectory reference for a stage target** is the round-robin phase's `qualifiers` (8 today), read from the phase row, not hard-coded.
- **R6 — Sacking on the season's last round.** `recordMatchRound` still pays the prize, then skips `reviewSeason` when the save is already `SACKED` (spec §8, last paragraph).
- **R7 — `AdvanceResponse` gains a 19th component `board`.** All 8 production constructor call sites pass `null` except `recordMatchRound` and the board's own news writer. Test call sites must be updated too (grep `new AdvanceResponse(` under `backend/src/test`).

---

### Task 1: Schema — board tables, competition scope and tier, `SACKED`

**Files:**
- Create: `backend/src/main/resources/db/migration/V57__board.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/Competition.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameStatus.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionScope.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/board/BoardMigrationIT.java`

**Interfaces produced:**
- `enum CompetitionScope { NATIONAL, STATE }`
- `Competition.getScope()`, `Competition.getTier()` (`Integer`, null for STATE), `Competition.create(saveGameId, name, type, seasonYear, totalRounds, scope, tier)`; the old 5-arg `create` stays and delegates with `NATIONAL, null` so untouched callers and tests compile.
- `SaveGameStatus.SACKED`

- [ ] **Step 1: Write the failing test.** `BoardMigrationIT` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(PostgresTestContainer.class)`, mirror `SeasonAdvanceServiceIT`'s field block). Use `JdbcTemplate`:
  - `board_states`, `board_objectives`, `board_confidence_changes` exist (query `information_schema.tables`).
  - Insert a `competitions` row named `Campeonato Catarinense` and one named `Série B` **without** scope/tier, then run the three backfill `UPDATE`s from the migration text through `JdbcTemplate` and assert `scope = 'STATE'` / `tier IS NULL` and `scope = 'NATIONAL'` / `tier = 2`. (Flyway has already run on the empty schema; re-running the statements is how the backfill is exercised.)
  - A `save_games` row can hold `status = 'SACKED'` (persist through `SaveGameRepository` after `setStatus(SACKED)`, reload, compare).

- [ ] **Step 2: Run it, see it fail.** `./gradlew test --tests 'br.com.carvoeiromanager.board.BoardMigrationIT'`

- [ ] **Step 3: Write the migration.**

```sql
-- Board objectives and job security (spec 2026-10-21). The board is its own feature package, so
-- its state lives in its own tables rather than as more columns on save_games.
CREATE TABLE board_states (
    save_game_id            UUID PRIMARY KEY REFERENCES save_games(id) ON DELETE CASCADE,
    confidence              INT     NOT NULL,
    ultimatum_active        BOOLEAN NOT NULL DEFAULT FALSE,
    matches_since_ultimatum INT     NOT NULL DEFAULT 0,
    sacking_enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    grace_season_year       INT
);

-- Kept for every season: past objectives are the manager's record (the sacked screen lists them).
CREATE TABLE board_objectives (
    id              UUID PRIMARY KEY,
    save_game_id    UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    season_year     INT  NOT NULL,
    kind            VARCHAR(20) NOT NULL,
    competition_id  UUID,
    tier            INT,
    target_position INT,
    target_stage    VARCHAR(20),
    outcome         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    UNIQUE (save_game_id, season_year, kind)
);

-- Season-scoped: purged at rollover with the news feed.
CREATE TABLE board_confidence_changes (
    id               UUID PRIMARY KEY,
    save_game_id     UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date       DATE NOT NULL,
    seq              INT  NOT NULL,
    reason           VARCHAR(40) NOT NULL,
    delta            INT  NOT NULL,
    confidence_after INT  NOT NULL,
    payload          TEXT
);
CREATE INDEX idx_board_changes_save_seq ON board_confidence_changes (save_game_id, seq DESC);

-- Competitions are found by scope and divisions by tier, never by name (spec §5.5). This backfill
-- is the only place that matches by name, and only for saves created before the columns existed.
ALTER TABLE competitions ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'NATIONAL';
ALTER TABLE competitions ADD COLUMN tier  INT;
UPDATE competitions SET scope = 'STATE' WHERE name = 'Campeonato Catarinense';
UPDATE competitions SET tier = 1 WHERE name = 'Série A';
UPDATE competitions SET tier = 2 WHERE name = 'Série B';
UPDATE competitions SET tier = 3 WHERE name = 'Série C';
```

- [ ] **Step 4: Entity changes.** Add to `Competition`:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private CompetitionScope scope = CompetitionScope.NATIONAL;

    /** National division: 1 = Série A, 2 = B, 3 = C. Null for a STATE competition. */
    @Column(name = "tier")
    private Integer tier;
```

with getters, the 7-arg factory, and the 5-arg factory delegating. Add `SACKED` to `SaveGameStatus` with a javadoc line: "The board sacked the manager; the world is frozen (no advance, kickoff or rollover)."

- [ ] **Step 5: Run the test, see it pass. Commit** (`feat(board): schema for board state, objectives and competition scope/tier`).

---

### Task 2: Seed scope and tier

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (the four `competitions` entries)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedCompetition.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedPhaseValidator.java` (or a new `SeedScopeValidator` beside it — builder's choice; it must run where `SeedPhaseValidator.validate` runs in `SaveGameService.createCareer`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Test: extend `backend/src/test/java/br/com/carvoeiromanager/seed/SeedCompetitionFilterTest.java` or add `SeedScopeTest.java`; extend `SaveGameServiceIT`

**Interfaces produced:** `SeedCompetition.scope()` (`String`, null → NATIONAL), `SeedCompetition.tier()` (`Integer`).

- [ ] **Step 1: Failing tests.**
  - Unit: loading the real seed (`new SeedLoader().loadCareer()`) gives Catarinense `scope = "STATE"`, `tier = null`; Série A/B/C `tier` 1/2/3 and scope null or `"NATIONAL"`.
  - Unit: the validator rejects a `SeedCompetition` with `NATIONAL` scope and null tier (`IllegalStateException`, message names the competition), and rejects a `STATE` competition **with** a tier.
  - IT (`SaveGameServiceIT`, new test): a fresh career's Catarinense `Competition` has scope `STATE`; its Série B has scope `NATIONAL`, tier 2.

- [ ] **Step 2: See them fail.**

- [ ] **Step 3: Implement.** Append `String scope, Integer tier` to `SeedCompetition` (record components at the end; Jackson tolerates their absence). In `clubs.json` add `"scope": "STATE"` to the Catarinense entry and `"tier": 1|2|3` to Série A/B/C. In `createCareer`, call the 7-arg `Competition.create` with `CompetitionScope.valueOf(scope == null ? "NATIONAL" : scope)` and the tier. Grep `new SeedCompetition(` in `src/test` and append `null, null` (or real values) at every call site; `SeedLoader.filtered` passes records through untouched.

- [ ] **Step 4: Run** `--tests '*Seed*' --tests '*SaveGameServiceIT'`, **commit** (`feat(board): seed competition scope and division tier`).

---

### Task 3: `ClubStrength`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/board/ClubStrength.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/board/ClubStrengthTest.java`

- [ ] **Step 1: Failing test.**

```java
package br.com.carvoeiromanager.board;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClubStrengthTest {

    @Test
    void averagesTheSixteenBestOverallsAndIgnoresTheRest() {
        // 20 players rated 41..60: the best 16 are 45..60, mean 52.5
        List<Integer> overalls = IntStream.rangeClosed(41, 60).boxed().toList();
        assertThat(ClubStrength.ofOveralls(overalls)).isCloseTo(52.5, within(1e-9));
    }

    @Test
    void averagesEveryoneWhenTheSquadIsShorterThanSixteen() {
        assertThat(ClubStrength.ofOveralls(List.of(50, 60, 70))).isCloseTo(60.0, within(1e-9));
    }

    @Test
    void anEmptySquadHasZeroStrength() {
        assertThat(ClubStrength.ofOveralls(List.of())).isZero();
    }
}
```

- [ ] **Step 2: See it fail.**

- [ ] **Step 3: Implement.**

```java
package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.player.Player;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * How strong a club is right now: the mean overall of its 16 best players. The board uses it both
 * to set targets and to judge single matches, so the two always agree on who is strong.
 *
 * <p>Deliberately NOT {@code Club.reputation}: that is written once at career creation and never
 * updated, so it is stale by season two. Strength is recomputed every time it is needed.
 */
public final class ClubStrength {

    static final int BEST_N = 16;

    private ClubStrength() { }

    /**
     * {@code squad} must be the players currently playing for the club ({@code club_id}), so a
     * loanee counts for the borrower. Youth prospects are excluded here; callers pass the club's
     * roster as {@code PlayerRepository.findByClubIdAndYouthProspectFalse} returns it.
     */
    public static double of(Collection<Player> squad) {
        return ofOveralls(squad.stream().filter(p -> !p.isYouthProspect()).map(Player::getOverall).toList());
    }

    static double ofOveralls(List<Integer> overalls) {
        return overalls.stream()
                .sorted(Comparator.reverseOrder())
                .limit(BEST_N)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);
    }
}
```

- [ ] **Step 4: Pass, commit** (`feat(board): club strength from the 16 best players`).

---

### Task 4: `Stage`, `StageLadder` and `ObjectiveTargets`

**Files:**
- Create: `board/Stage.java`, `board/ObjectiveKind.java`, `board/ObjectiveOutcome.java`, `board/LeagueTarget.java`, `board/ObjectiveTargets.java`, `board/StageLadder.java`
- Test: `board/ObjectiveTargetsTest.java`, `board/StageLadderTest.java`

**Interfaces produced:**

```java
public enum Stage { NOT_QUALIFIED, QUARTERFINAL, SEMIFINAL, FINAL, CHAMPION }
public enum ObjectiveKind { LEAGUE, STATE, FINANCE }
public enum ObjectiveOutcome { PENDING, MET, FAILED }

/** Exactly one of position/stage is non-null. */
public record LeagueTarget(Integer position, Stage stage) { }

public final class ObjectiveTargets {
    /** 1-based rank of clubId among members by strength desc, then name asc. */
    public static int expectedRank(UUID clubId, Map<UUID, Double> strengthByClub, Map<UUID, String> nameByClub);
    public static LeagueTarget league(int tier, int expectedRank, int fieldSize);
    public static Stage state(int expectedRank, int fieldSize);
}

public final class StageLadder {
    /** Knockout phase i of n (0-based) → its stage (R3). Throws for n > 3. */
    public static Stage stageOfKnockout(int index, int knockoutCount);
}
```

- [ ] **Step 1: Failing tests.** `ObjectiveTargetsTest`, using parameterized tests (`@ParameterizedTest @CsvSource`), with **one row per band edge**:
  - Tier 1 (A), field 20: rank 1→4, 3→4, 4→6, 8→6, 9→12, 14→12, 15→16, 20→16 (all `position`, `stage` null).
  - Tier 2 (B), field 20: 1→4, 3→4, 4→8, 7→8, 8→10, 13→10, 14→16, 20→16.
  - Tier 3 (C), field 20: 1→FINAL, 2→FINAL, 3→SEMIFINAL, 5→SEMIFINAL, 6→QUARTERFINAL, 10→QUARTERFINAL (stage, position null); 11→position 14; 20→position 14.
  - STATE, field 12: 1→CHAMPION, 2→CHAMPION, 3→FINAL, 4→FINAL, 5→SEMIFINAL, 6→SEMIFINAL, 7→QUARTERFINAL, 12→QUARTERFINAL.
  - Scaling: tier 2 with field 10, rank 2 → position 2 (`ceil(4·10/20)`); rank 10 → position 8.
  - Unknown tier (4) → `IllegalArgumentException`.
  - `expectedRank`: three clubs with strengths 50/60/60 named "B"/"Z"/"A" → "A" is 1, "Z" is 2, "B" is 3.

  `StageLadderTest`: `(0,3)→QUARTERFINAL`, `(1,3)→SEMIFINAL`, `(2,3)→FINAL`, `(0,1)→FINAL`, `(0,2)→SEMIFINAL`; `(0,4)` throws `IllegalStateException` whose message contains "extend Stage".

- [ ] **Step 2: See them fail.**

- [ ] **Step 3: Implement.** Bands live in one place as data, written for a 20-club field (state: 12), scaled with `ceil(bound · n / base)` for both the rank bounds and target positions:

```java
    // Upper rank bound of each band (for a 20-club field) → target. Spec §5.2.
    private static final int[] A_BOUNDS = {3, 8, 14, 20};
    private static final int[] A_TARGETS = {4, 6, 12, 16};
    private static final int[] B_BOUNDS = {3, 7, 13, 20};
    private static final int[] B_TARGETS = {4, 8, 10, 16};
    private static final int[] C_BOUNDS = {2, 5, 10, 20};
    private static final Stage[] C_STAGES = {Stage.FINAL, Stage.SEMIFINAL, Stage.QUARTERFINAL, null};
    private static final int C_WEAKEST_POSITION = 14;
    // State championship, written for a 12-club field. Spec §5.3.
    private static final int[] STATE_BOUNDS = {2, 4, 6, 12};
    private static final Stage[] STATE_STAGES = {Stage.CHAMPION, Stage.FINAL, Stage.SEMIFINAL, Stage.QUARTERFINAL};
```

The builder writes the band lookup; the tests above fix its behaviour.

- [ ] **Step 4: Pass, commit** (`feat(board): objective targets for every division and the state championship`).

---

### Task 5: `BoardConfidenceCalculator`

**Files:**
- Create: `board/BoardChangeReason.java`, `board/BoardConfidenceCalculator.java`
- Test: `board/BoardConfidenceCalculatorTest.java`

**Interfaces produced:**

```java
public enum BoardChangeReason {
    MATCH_RESULT, HEAVY_DEFEAT, TABLE_POSITION, NEGATIVE_BALANCE,
    STATE_MET, STATE_FAILED, TITLE_WON,
    LEAGUE_MET, LEAGUE_FAILED, FINANCE_MET, FINANCE_FAILED,
    NEW_SEASON, SACKING_AVERTED
}

public final class BoardConfidenceCalculator {
    public static final int START = 60;
    public record Part(BoardChangeReason reason, int delta) { }

    /**
     * leaguePosition / leagueReference are null when the trajectory rule does not apply (not a
     * national-league match, no round-robin table running, or fewer than 7 league matches played).
     */
    public record MatchInput(double ownStrength, double opponentStrength, boolean home,
                             int goalsFor, int goalsAgainst, boolean stateCompetition,
                             Integer leaguePosition, Integer leagueReference, boolean negativeBalance) { }

    public static List<Part> afterMatch(MatchInput in);              // already clamped to ±8, zero parts dropped
    public static int leaguePositionDelta(int finalPosition, int target, boolean relegationZone, int fieldSize);
    public static int leagueStageDelta(Stage reached, Stage target);
    public static int stateDelta(boolean met);                       // +6 / −8
    public static final int TITLE_WON = 4;
    public static int financeDelta(boolean met);                     // +4 / −10
    public static int regress(int confidence);                       // halfway to 60, rounding toward 60
    public static int clamp(int confidence);                         // 0..100
}
```

- [ ] **Step 1: Failing test, in full.**

```java
package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.board.BoardConfidenceCalculator.MatchInput;
import br.com.carvoeiromanager.board.BoardConfidenceCalculator.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static br.com.carvoeiromanager.board.BoardChangeReason.*;
import static org.assertj.core.api.Assertions.assertThat;

class BoardConfidenceCalculatorTest {

    private static MatchInput match(double own, double opp, boolean home, int gf, int ga) {
        return new MatchInput(own, opp, home, gf, ga, false, null, null, false);
    }

    private static int total(List<Part> parts) {
        return parts.stream().mapToInt(Part::delta).sum();
    }

    @Test
    void homeDrawBetweenEqualsCostsTwo() {
        // expected = 1.35 + 0.30 = 1.65; (1 − 1.65)·3 = −1.95 → −2
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, true, 1, 1)))
                .containsExactly(new Part(MATCH_RESULT, -2));
    }

    @Test
    void awayDrawBetweenEqualsIsNeutral() {
        // expected = 1.05; (1 − 1.05)·3 = −0.15 → 0, so no row at all
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, false, 0, 0))).isEmpty();
    }

    @Test
    void awayWinAgainstAMuchStrongerSideIsWorthEight() {
        // diff −10: expected = clamp(1.35 − 0.8 − 0.3) = 0.30 (floor); (3 − 0.30)·3 = 8.1 → 8
        assertThat(total(BoardConfidenceCalculator.afterMatch(match(45, 55, false, 1, 0)))).isEqualTo(8);
    }

    @Test
    void homeLossToAMuchWeakerSideCostsTheMost() {
        // diff +10: expected = clamp(1.35 + 0.8 + 0.3) = 2.45; (0 − 2.45)·3 = −7.35 → −7
        assertThat(BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 1)))
                .containsExactly(new Part(MATCH_RESULT, -7));
    }

    @Test
    void aFourGoalDefeatAddsAHeavyDefeatRowAndTheTotalIsClampedAtMinusEight() {
        List<Part> parts = BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 4));
        assertThat(parts).extracting(Part::reason).containsExactly(MATCH_RESULT, HEAVY_DEFEAT);
        // −7 −3 = −10 → clamped to −8; the trim comes off the last row written
        assertThat(parts).containsExactly(new Part(MATCH_RESULT, -7), new Part(HEAVY_DEFEAT, -1));
    }

    @Test
    void stateMatchesCountHalf() {
        MatchInput in = new MatchInput(55, 45, true, 0, 1, true, null, null, false);
        // round(−7.35) = −7, halved = −3.5, and Math.round(−3.5) = −3 (half rounds up, toward zero here)
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(MATCH_RESULT, -3));
    }

    @ParameterizedTest
    @CsvSource({
            "4, 4, 1",    // at the reference
            "1, 4, 1",    // above it
            "8, 4, 0",    // 4 below: nothing
            "9, 4, -1",   // 5 below
    })
    void trajectoryNudgesByTablePosition(int position, int reference, int expected) {
        MatchInput in = new MatchInput(50, 50, false, 0, 0, false, position, reference, false);
        int got = BoardConfidenceCalculator.afterMatch(in).stream()
                .filter(p -> p.reason() == TABLE_POSITION).mapToInt(Part::delta).sum();
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void aNegativeBalanceCostsOnePerMatch() {
        MatchInput in = new MatchInput(50, 50, false, 0, 0, false, null, null, true);
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(NEGATIVE_BALANCE, -1));
    }

    @ParameterizedTest
    @CsvSource({
            // final, target, relegationZone, expected
            "1,  8, true,  20",   // 7 better
            "4,  8, true,  20",   // exactly 4 better
            "5,  8, true,  12",
            "8,  8, true,  12",
            "9,  8, true, -12",
            "11, 8, true, -12",
            "12, 8, true, -25",
            "16, 16, true, 12",
            "17, 10, true, -35",  // Z-4 overrides the place-count rows
            "17, 14, false, -12", // Série C: no relegation zone
            "20, 14, false, -25",
    })
    void leaguePositionResolution(int finalPos, int target, boolean zone, int expected) {
        assertThat(BoardConfidenceCalculator.leaguePositionDelta(finalPos, target, zone, 20)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "SEMIFINAL, SEMIFINAL, 12",
            "CHAMPION, QUARTERFINAL, 12",   // a stage target resolves on reaching it: never +20
            "QUARTERFINAL, SEMIFINAL, -12",
            "QUARTERFINAL, FINAL, -25",
            "NOT_QUALIFIED, QUARTERFINAL, -12",
            "NOT_QUALIFIED, SEMIFINAL, -25",
    })
    void leagueStageResolution(Stage reached, Stage target, int expected) {
        assertThat(BoardConfidenceCalculator.leagueStageDelta(reached, target)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "100, 80", "81, 70", "45, 53", "0, 30", "59, 60", "61, 60"})
    void regressionMovesHalfwayTowardSixtyRoundingTowardSixty(int before, int after) {
        assertThat(BoardConfidenceCalculator.regress(before)).isEqualTo(after);
    }

    @Test
    void objectiveDeltas() {
        assertThat(BoardConfidenceCalculator.stateDelta(true)).isEqualTo(6);
        assertThat(BoardConfidenceCalculator.stateDelta(false)).isEqualTo(-8);
        assertThat(BoardConfidenceCalculator.financeDelta(true)).isEqualTo(4);
        assertThat(BoardConfidenceCalculator.financeDelta(false)).isEqualTo(-10);
        assertThat(BoardConfidenceCalculator.TITLE_WON).isEqualTo(4);
    }

    @Test
    void confidenceIsClampedToZeroToHundred() {
        assertThat(BoardConfidenceCalculator.clamp(-5)).isZero();
        assertThat(BoardConfidenceCalculator.clamp(105)).isEqualTo(100);
    }
}
```

  > **Note on the spec's sanity sentence.** Spec §6.1 says a home draw between equals scores −1, an away draw +1 and an away upset "about +6". Under the formula they are −2, 0 and +8. The formula is the design (spec §6: "the *shape* is the design"), so these tests pin the formula and the builder corrects the spec's sentence in the same commit.

- [ ] **Step 2: See it fail.**

- [ ] **Step 3: Implement.** Constants grouped at the top with one comment each. `afterMatch` builds parts in the order MATCH_RESULT, HEAVY_DEFEAT, TABLE_POSITION, NEGATIVE_BALANCE; applies the half weight to the MATCH_RESULT row only (`Math.round(delta / 2.0)` — note `Math.round(-3.5) == -3`, which the test pins); drops zero rows; then trims from the last row backwards until the sum is within ±8, dropping a row that reaches zero. `regress`: `int gap = START - c; return c + Integer.signum(gap) * ((Math.abs(gap) + 1) / 2);` with a comment on why `+1` rounds toward 60.

- [ ] **Step 4: Pass, fix the two spec sentences, commit** (`feat(board): confidence calculator`).

---

### Task 6: `BoardRules` — ultimatum, recovery, sacking

**Files:**
- Create: `board/BoardRules.java`
- Test: `board/BoardRulesTest.java`

**Interfaces produced:**

```java
public final class BoardRules {
    public static final int ULTIMATUM_AT = 30, LIFT_AT = 45, SACK_AT = 15, SEASON_END_SACK_BELOW = 25,
                            MATCHES_AFTER_ULTIMATUM = 3, LEAGUE_MATCHES_BEFORE_SACKING = 6, AVERTED_FLOOR = 30;

    public enum Verdict { NONE, ISSUE_ULTIMATUM, LIFT_ULTIMATUM, SACK, SACKING_AVERTED }

    /** matchesSinceUltimatum is the value AFTER this match's increment (R2). */
    public static Verdict afterMatch(int confidence, boolean ultimatumActive, int matchesSinceUltimatum,
                                     int leagueMatchesPlayed, boolean canSack);

    public static Verdict atSeasonEnd(int confidence, boolean canSack);
}
```

`canSack` is `sackingEnabled && seasonYear != graceSeasonYear`; when it is false and the sack conditions otherwise hold, the verdict is `SACKING_AVERTED` (R1).

- [ ] **Step 1: Failing tests** (write them all out; one test per row):
  - 31, no ultimatum → NONE; 30, no ultimatum → ISSUE_ULTIMATUM; 30 with ultimatum active → not ISSUE again (NONE).
  - Ultimatum active, 45 → LIFT_ULTIMATUM; 44 → NONE.
  - Ultimatum active, 15, `matchesSince` 3, league 6, canSack → SACK.
  - Same but confidence 16 → NONE; `matchesSince` 2 → NONE; league 5 → NONE; ultimatum inactive and confidence 15 → ISSUE_ULTIMATUM (never SACK in the same match that issues it).
  - Same as the SACK row but `canSack` false → SACKING_AVERTED.
  - `atSeasonEnd`: 24 canSack → SACK; 25 → NONE; 24 !canSack → SACKING_AVERTED.

- [ ] **Step 2–4: Fail, implement (checks in the spec's order: ultimatum, recovery, sacking), pass, commit** (`feat(board): ultimatum and sacking rules`).

---

### Task 7: Board entities and repositories

**Files:**
- Create: `board/BoardState.java`, `board/BoardStateRepository.java`, `board/BoardObjective.java`, `board/BoardObjectiveRepository.java`, `board/BoardConfidenceChange.java`, `board/BoardConfidenceChangeRepository.java`
- Test: `board/BoardEntitiesIT.java`

Follow `NewsItem` for entity style: `protected` no-arg constructor, a static `create(...)` that assigns `UUID.randomUUID()`, getters, and only the mutators the service needs.

**Interfaces produced:**
- `BoardState.create(saveGameId, confidence, sackingEnabled, Integer graceSeasonYear)`; `getConfidence/setConfidence`, `isUltimatumActive`, `issueUltimatum()` (active, counter 0), `liftUltimatum()`, `countMatchSinceUltimatum()` (increments only while active), `getMatchesSinceUltimatum`, `isSackingEnabled`, `getGraceSeasonYear`, `canSack(int seasonYear)`.
- `BoardObjective.create(saveGameId, seasonYear, kind, competitionId, tier, targetPosition, targetStage)`; `resolve(ObjectiveOutcome)` throws `IllegalStateException` if not PENDING.
- `BoardObjectiveRepository`: `findBySaveGameIdAndSeasonYear`, `findBySaveGameIdOrderBySeasonYearAsc`, `findBySaveGameIdAndSeasonYearAndKind`.
- `BoardConfidenceChange.create(saveGameId, date, seq, reason, delta, confidenceAfter, payloadJson)`; repository: `countBySaveGameId`, `findTop8BySaveGameIdOrderBySeqDesc`, `deleteBySaveGameId`.

- [ ] **Steps:** IT round-trips one of each, asserts the `UNIQUE (save_game_id, season_year, kind)` constraint rejects a duplicate objective (`DataIntegrityViolationException`), and that `resolve` twice throws. Fail → implement → pass → commit (`feat(board): board entities`).

---

### Task 8: `BoardEventResponse`, the `board` field on `AdvanceResponse`, and the GET endpoint DTOs

**Files:**
- Create: `board/BoardObjectiveResponse.java`, `board/BoardChangeResponse.java`, `board/BoardEventResponse.java`, `board/BoardResponse.java`
- Modify: `season/AdvanceResponse.java` and every `new AdvanceResponse(` call site (R7)

**Interfaces produced** (records, spec §9.1):

```java
public record BoardObjectiveResponse(String kind, String competitionName, Integer tier,
        Integer targetPosition, String targetStage, Integer currentPosition,
        String progress, String outcome) { }
public record BoardChangeResponse(String date, String reason, int delta, Map<String, Object> payload) { }
public record BoardEventResponse(int confidence, int delta, boolean ultimatumIssued, boolean ultimatumLifted,
        boolean sacked, boolean sackingAverted, List<BoardObjectiveResponse> objectives) { }
public record BoardResponse(int confidence, boolean ultimatumActive, boolean sackingEnabled,
        boolean graceSeason, List<BoardObjectiveResponse> objectives, List<BoardChangeResponse> recentChanges) { }
```

- [ ] **Step 1:** Append `BoardEventResponse board` as the 19th `AdvanceResponse` component. Extend the class javadoc: "`board` — the board's reaction: on MATCH items the confidence change; on `BOARD_*` items the whole payload. Null on every other item and on payloads persisted before the feature existed — null-guard it."
- [ ] **Step 2:** Pass `null` at every existing call site (`grep -rn "new AdvanceResponse(" backend/src`). Compile: `./gradlew compileJava compileTestJava`.
- [ ] **Step 3:** Run `--tests '*NewsFeedIT' --tests '*SeasonAdvanceServiceIT'` to prove old payloads still deserialize. Commit (`feat(board): board payload on AdvanceResponse`).

---

### Task 9: `BoardService` — initialize, objectives, `ensureState`

**Files:**
- Create: `board/BoardService.java`, `board/BoardNewsWriter.java`
- Modify: `career/SaveGameService.java`, `career/CreateSaveGameRequest.java`
- Test: `board/BoardObjectivesIT.java`

**Interfaces produced:**
- `CreateSaveGameRequest(String managerName, String managerNationality, Boolean sackingEnabled)` — null → true. Keep the 2-arg convenience constructor (`this(name, nationality, null)`) so existing callers compile.
- `BoardService.initialize(SaveGame save, boolean sackingEnabled)` — new careers: state at 60, no grace, then `setSeasonObjectives`.
- `BoardService.ensureState(SaveGame save)` → `BoardState` — lazy creation for existing careers (spec §7.5): confidence 60, sacking enabled, `grace_season_year = save.getSeasonYear()`, objectives for the current season; a STATE objective whose competition's run is already over is created resolved from the stage reached **with no confidence row**.
- `BoardService.setSeasonObjectives(SaveGame save)` — finds the managed club's competitions through `CompetitionClubRepository.findBySaveGameIdAndClubId` → `Competition` by scope; writes up to three rows; posts one `BOARD_OBJECTIVES` news item dated the season's first calendar day (earliest `SeasonEvent.eventDate` of the save; fall back to `save.getCurrentDate()`).
- `BoardNewsWriter.write(UUID saveGameId, LocalDate date, String type, BoardEventResponse board)` — builds the sparse `AdvanceResponse` (`type`, `date`, `board`, everything else empty/null/0/false), `seq = countBySaveGameId + 1`, exactly like `PhaseResolver.persistNews`. Never puts pt-BR text in `competitionName` (spec §10).
- Package-private helper `stageReached(SaveGame, Competition)` → `Stage` (or null while still undecided), walking the phases with `StageLadder` (R3), `StandingsService.computeStandings(save, competitionId, phaseId, null)` and `TieResolver`.

Objective computation: strengths come from `playerRepository.findByClubIdAndYouthProspectFalse(clubId)` for each member (one query per club is acceptable: 20 clubs at most, three times a season; note it in a comment). The LEAGUE row stores `tier`. A national competition whose tier yields a stage target stores `target_stage`; otherwise `target_position`. A STATE competition with more than three knockout phases propagates `StageLadder`'s exception (a seed error, not a runtime condition).

- [ ] **Step 1: Failing IT** `BoardObjectivesIT`:
  - A new career (`createCareer` via the service) has a `board_states` row at 60 with null grace, three objectives for the season (LEAGUE tier 2 with a non-null `target_position`, STATE with a non-null `target_stage`, FINANCE), and exactly one `BOARD_OBJECTIVES` news item whose payload deserializes with `board.objectives` of size 3.
  - `sackingEnabled=false` in the request is stored.
  - Delete the save's `board_states` and `board_objectives` rows via `JdbcTemplate`, call `ensureState`: grace year equals the season year and objectives are recreated.
  - **Full seed** (`@TestPropertySource(properties = "carvoeiro.seed.competitions=")`, in a separate nested class or separate IT `BoardDivisionTargetsIT`): move the managed club's `competition_club` row from Série B to Série A (`UPDATE competition_club SET competition_id = :serieA WHERE club_id = :cri AND competition_id = :serieB` through `JdbcTemplate`, and delete one Série A club's row so the field stays 20), delete the existing objectives, call `setSeasonObjectives`: LEAGUE has tier 1 and a position in {4, 6, 12, 16}. Repeat for Série C: tier 3, and either a stage in {FINAL, SEMIFINAL, QUARTERFINAL} or position 14.
- [ ] **Step 2: See it fail.**
- [ ] **Step 3: Implement**, and call `boardService.initialize(saveGame, req.sackingEnabled() == null || req.sackingEnabled())` in `SaveGameService.createCareer` after step 5 (managed club set) and **before** step 6's `processMarketDay`. Comment why: objectives must exist before anything can post news on day one.
- [ ] **Step 4: Run** `--tests '*Board*IT' --tests '*SaveGameServiceIT' --tests '*CareerCreationCalendarIT'`. Commit (`feat(board): season objectives at career creation`).

---

### Task 10: After every managed match

**Files:**
- Modify: `board/BoardService.java`
- Modify: `competition/PhaseResolver.java` (make `phaseComplete` public as `isPhaseComplete`)
- Modify: `season/SeasonAdvanceService.java`
- Test: `board/BoardMatchIT.java`

**Interfaces produced:** `BoardEventResponse BoardService.afterManagedMatch(SaveGame save, SeasonEvent event, List<Fixture> fixtures)` — returns null when the managed club has no fixture in the round.

Order inside `afterManagedMatch` (comment each step):
1. Find the managed club's fixture in `fixtures`; none → return null (spec §6.1: the rounds `advance` keeps popping after an elimination). It must be `isPlayed()` here.
2. `ensureState(save)`.
3. Build `MatchInput`: strengths of both clubs now; `stateCompetition` from the competition's scope; for the national league, compute league matches played (R4) and, when ≥ 7 and the round-robin table is running (unphased, or the event's phase is the round-robin phase), the current position from `computeStandings(save, competitionId, phaseId, null)` and the reference (`target_position`, or the phase's `qualifiers` for a stage target, R5). `negativeBalance` from the managed `Club.getBalance().signum() < 0`.
4. Apply `afterMatch` parts: for each, write a `board_confidence_changes` row (`payload` = `{"opponent": name, "score": "gf–ga", "home": bool}` for MATCH_RESULT/HEAVY_DEFEAT, `{"position": n}` for TABLE_POSITION). Seq = `countBySaveGameId + 1`.
5. If the event has a phase and `phaseResolver.isPhaseComplete(saveId, phase, event.getId())` — note `recordMatchRound` calls this **after** `resolveIfPhaseComplete`, and `isPhaseComplete` treats the just-processed event as processed — resolve any PENDING stage objective on this competition (STATE, or LEAGUE with `target_stage`), and a LEAGUE `target_position` on a phased competition's round-robin phase. Write STATE_MET/STATE_FAILED/LEAGUE_MET/LEAGUE_FAILED rows. If the phase was the final and the managed club won it, write TITLE_WON (any phased managed competition, spec §6.2).
6. `state.countMatchSinceUltimatum()` then `BoardRules.afterMatch(...)` with `canSack = state.canSack(save.getSeasonYear())`:
   - ISSUE_ULTIMATUM → `issueUltimatum()`, news `BOARD_ULTIMATUM`.
   - LIFT_ULTIMATUM → `liftUltimatum()`, news `BOARD_ULTIMATUM_LIFTED`.
   - SACK → `save.setStatus(SACKED)`, news `BOARD_SACKED`.
   - SACKING_AVERTED → confidence `max(confidence, 30)` logged as a SACKING_AVERTED row, news `BOARD_SACKING_AVERTED`.
7. Return the `BoardEventResponse` (delta = sum of this match's rows).

Hook in `recordMatchRound`, directly after `phaseResolver.resolveIfPhaseComplete(save, event);`:

```java
        // The board judges the match after the phase is resolved (a knockout exit or the title is
        // only knowable then) and before the season-finished check, so a sacking on the last round
        // is already on the save when that check runs. Null when the managed club had no fixture.
        BoardEventResponse board = boardService.afterManagedMatch(save, event, fixtures);
```

and pass `board` as the 19th argument of the MATCH `AdvanceResponse`.

- [ ] **Step 1: Failing IT** `BoardMatchIT` (read `SeasonAdvanceServiceIT` first; reuse its `createCareer`/`advanceToNextMatch` helpers by copying them):
  - After the first managed match, the response's `board` is non-null, `board_confidence_changes` has ≥ 0 rows whose deltas sum to `board.delta()`, and `board_states.confidence == 60 + board.delta()`.
  - **Forced elimination**: before the Catarinense's last first-phase round, make the managed club unable to qualify. Check how `SeasonAdvanceServiceIT` / `PhaseResolver*IT` rig a table first and reuse that; if neither does, pre-record the managed club's remaining first-phase fixtures except the last as 0–5 losses with `Fixture.recordResult` and give the clubs around 8th place wins the same way (the simulator skips fixtures already `isPlayed()` — verify that in `FixtureSimulator` before relying on it). Assert the STATE objective is FAILED with that round's date on the STATE_FAILED row. Then keep advancing to the next Catarinense event the club is not in: confidence and `matches_since_ultimatum` are unchanged by it, and its MATCH response has `board == null`.
  - **Reaching the target early**: with STATE target forced to QUARTERFINAL (update the row via `JdbcTemplate`) and a qualifying club, the objective is MET at the end of the first phase while the run continues.
  - The live-match finish path calls the hook exactly once: read `LiveMatchServiceIT`, kick off and finish one match, assert exactly one MATCH_RESULT-or-zero set of rows for that date (count rows with that `event_date` before and after == the response's part count).
- [ ] **Step 2: See it fail.** **Step 3: Implement.** **Step 4:** Run `--tests '*Board*' --tests '*SeasonAdvanceServiceIT' --tests '*LiveMatchServiceIT' --tests '*PhaseResolver*'`. Commit (`feat(board): confidence after every managed match`).

---

### Task 11: The sacking guards

**Files:**
- Modify: `common/ErrorCode.java` (`MANAGER_SACKED(HttpStatus.CONFLICT)`, alphabetical)
- Modify: `season/SeasonAdvanceService.java` (`advance`), `match/LiveMatchService.java` (`kickoff`), `season/SeasonRolloverService.java` (`rollover`)
- Test: `board/BoardSackingIT.java`

- [ ] **Step 1: Failing IT** `BoardSackingIT`:
  - Force a sack: advance until six Série B fixtures of the managed club are played. Then set `board_states` to confidence **7**, ultimatum active, `matches_since_ultimatum` 2 (`JdbcTemplate`). The next managed match can move confidence by at most +8, so it ends ≤ 15 whatever the result, and its increment makes the counter 3 (R2). Advance to it. Assert status `SACKED`, a `BOARD_SACKED` news item, and `board.sacked()` true on the MATCH response.
  - Then `advance` → `ApiException` with `MANAGER_SACKED`; `kickoff` → same; `rollover` → same (not `SEASON_NOT_FINISHED`).
  - With `sacking_enabled = false` and the same setup: status stays `IN_PROGRESS`, `BOARD_SACKING_AVERTED` posted, confidence ≥ 30.
  - In the grace season (set `grace_season_year` = season year): same as disabled.
- [ ] **Step 2: See it fail.**
- [ ] **Step 3: Implement.** In each of the three entry points, right after `requireOwned`:

```java
        if (save.getStatus() == SaveGameStatus.SACKED) {
            throw new ApiException(ErrorCode.MANAGER_SACKED, "The board has sacked the manager");
        }
```

In `rollover`, the check goes **before** the `FINISHED` check so a sacked save gets the right code. In `kickoff`, before the resume branch — a sacked save can never have a live match (the hook runs inside `completeUserMatch`), so nothing is stranded. Add the code to `frontend/src/api/errorMessages.ts` in Task 14.
- [ ] **Step 4:** Run `--tests '*BoardSackingIT' --tests '*ErrorCode*'` (if an exhaustive error-code test exists, it will tell you where else to register the code). Commit (`feat(board): sacking freezes the save`).

---

### Task 12: The season-end review

**Files:**
- Modify: `board/BoardService.java` (`reviewSeason`)
- Modify: `season/SeasonAdvanceService.java`
- Test: `board/BoardReviewIT.java`

**Interfaces produced:** `BoardEventResponse BoardService.reviewSeason(SaveGame save)` — sets the status to `FINISHED` or `SACKED`.

Replace the season-finished block in `recordMatchRound`:

```java
        if (seasonFinished) {
            // The prize is part of the season's money, so the board judges the balance AFTER it is
            // paid (spec §5.4); the status is therefore decided after the prize, by the board.
            prize = financeService.processSeasonEnd(saveGameId, competitionId, eventDate);
            // A manager sacked by this very round's afterManagedMatch stays sacked (R6).
            if (save.getStatus() != SaveGameStatus.SACKED) {
                board = mergeReview(board, boardService.reviewSeason(save));
            }
        }
```

`mergeReview(match, review)` returns a `BoardEventResponse` with the review's `confidence`, the match's `delta` plus the review's, `sacked`/`sackingAverted` OR'd from both, the match's ultimatum flags, and the review's `objectives`. The frontend (Task 15) reads `sacked` from the MATCH response to redirect without a reload, so it must be true when the review sacks. `match` may be null (no managed fixture cannot happen on the last managed round, but guard it).

Also make the early-return `FINISHED` branch at the top of `advance` (no events left) go through the same review when no review has run for this season (`board_objectives` for the season still has a PENDING FINANCE row). Comment that this path is only reachable on saves whose last managed event was processed before this feature existed.

`reviewSeason`:
1. `ensureState`.
2. Resolve PENDING objectives: an unphased LEAGUE by final position (`computeStandings(save, competitionId, null)` — unbounded; the season is over) with `relegationZone = tier ∈ {1, 2}`; any still-PENDING stage objective by `stageReached`; FINANCE by `Club.getBalance().signum() > 0`. One row per resolution.
3. Post `BOARD_REVIEW` with every objective of the season and the resulting confidence.
4. `BoardRules.atSeasonEnd` → SACK (status `SACKED`, `BOARD_SACKED`), SACKING_AVERTED (raise to 30, news), or NONE (status `FINISHED`).

- [ ] **Step 1: Failing IT** `BoardReviewIT`:
  - Advance a whole season (copy `SeasonAdvanceServiceIT`'s full-season loop): status ends `FINISHED` (fresh careers rarely end below 25; if the run does, set `sacking_enabled=false` for this test), one `BOARD_REVIEW` item, no PENDING objective left for the season.
  - **Finance after the prize**: before the last managed match, set the managed club's balance to `−(prize − 1)` where `prize` is computed the way `FinanceService.processSeasonEnd` computes it for the current table position (read it; if that is impractical, set the balance to −1 and assert the prize is positive) — FINANCE is MET.
  - **Season-end sack**: before the last match set confidence 5 with sacking enabled and no grace → status `SACKED`, `rollover` rejects with `MANAGER_SACKED`.
- [ ] **Steps 2–4:** fail, implement, pass (`--tests '*Board*' --tests '*SeasonAdvanceServiceIT' --tests '*FinanceService*'`), commit (`feat(board): season-end review`).

---

### Task 13: Rollover

**Files:**
- Modify: `board/BoardService.java` (`startNewSeason`)
- Modify: `season/SeasonRolloverService.java`
- Test: `board/BoardRolloverIT.java`

`startNewSeason(save)`: `ensureState`; purge `board_confidence_changes`; regress confidence (`BoardConfidenceCalculator.regress`) and log it as the first NEW_SEASON row of the new season; lift any ultimatum; `setSeasonObjectives`. Call it at the very end of `rollover`, after `saveGame.advanceToNextSeason()` and **before** `matchPreviewService.maybePersistPreview` — after the news purge so the objectives item survives, and after the new calendar exists so the item can be dated on its first day. Comment both reasons at the call.

- [ ] **Step 1: Failing IT:** finish a season (as Task 12), set confidence 81, rollover → confidence 70; exactly one change row (NEW_SEASON, −11); three objectives for the new season and the previous season's three still present with non-PENDING outcomes; one `BOARD_OBJECTIVES` news item.
- [ ] **Steps 2–4**, run `--tests '*BoardRolloverIT' --tests '*SeasonRollover*'`, commit (`feat(board): new season regression and objectives at rollover`).

---

### Task 14: `GET /board`, `CreateSaveGameRequest` wire field, career listing status

**Files:**
- Create: `board/BoardController.java`
- Modify: `board/BoardService.java` (`getBoard(saveGameId, userId)`)
- Test: `board/BoardControllerIT.java` (follow an existing controller IT, e.g. `NewsReadIT` if it goes through MockMvc; otherwise call the service)

`getBoard`: `requireOwned`, `ensureState`, the current season's objectives with live `currentPosition`/`progress` (spec §9.1: LEAGUE `ON_TRACK`/`AT_RISK`/`OFF_TRACK` against the target or the R5 reference, null before the first league round or once a stage target's round-robin phase is over; FINANCE by balance sign; STATE null), `graceSeason = graceSeasonYear == seasonYear`, the last 8 changes newest first with their payload parsed to a `Map`. Read-only: allowed on a SACKED save.

```java
@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/board")
public class BoardController {
    private final BoardService boardService;
    public BoardController(BoardService boardService) { this.boardService = boardService; }

    @GetMapping
    public BoardResponse get(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return boardService.getBoard(saveGameId, userId);
    }
}
```

- [ ] **Steps:** IT asserts the shape for a fresh career (60, three objectives, LEAGUE progress null before any league match, FINANCE `ON_TRACK`), that another user's save returns 404 `SAVE_GAME_NOT_FOUND`, and that a sacked save still returns 200. Fail → implement → pass → commit (`feat(board): board endpoint`).

---

### Task 15: Frontend

**Files:**
- Modify: `frontend/src/api/types.ts` (both `status` unions gain `'SACKED'`; `CreateSaveGameRequest.sackingEnabled?: boolean`; `BoardResponse`, `BoardObjective`, `BoardChange`, `BoardEvent` types; `AdvanceResponse.type` union gains the six `BOARD_*` types; `AdvanceResponse.board?: BoardEvent | null` with the same null-guard comment style)
- Create: `frontend/src/api/board.ts` (`getBoard(saveGameId, token)` via `apiGet`)
- Modify: `frontend/src/api/errorMessages.ts` (`MANAGER_SACKED: 'Você foi demitido pela diretoria.'`)
- Create: `frontend/src/features/board/boardLabels.ts` (+ test) — mood label (spec §6.4), objective text per kind/tier/target (spec §5.2–5.4 tables, verbatim pt-BR), stage names, progress/outcome chip labels, change-reason labels
- Create: `frontend/src/features/board/BoardCard.tsx` (+ test)
- Create: `frontend/src/features/board/SackedPage.tsx` (+ test)
- Modify: `frontend/src/features/career/DashboardPage.tsx`, `frontend/src/components/Layout.tsx`, `frontend/src/features/news/newsMeta.tsx`, `frontend/src/features/match/AdvanceEventCard.tsx`, `frontend/src/features/season/SeasonSummaryPage.tsx`, `frontend/src/features/career/NewCareerDialog.tsx`, `frontend/src/features/career/LoadCareerDialog.tsx`

Copy (exact):
- Mood: `≥80 Muito satisfeita`, `60–79 Satisfeita`, `45–59 Neutra`, `30–44 Preocupada`, `15–29 Insatisfeita`, `<15 Cargo por um fio`.
- Stages: QUARTERFINAL `quartas de final`, SEMIFINAL `semifinais`, FINAL `final`, CHAMPION `título`.
- Progress chips: `no caminho` / `em risco` / `fora da meta`; outcome chips: `cumprido` / `não cumprido`.
- Change reasons: MATCH_RESULT `Vitória sobre {opp} ({score})` / `Empate com {opp} ({score})` / `Derrota para {opp} ({score})`; HEAVY_DEFEAT `Goleada sofrida`; TABLE_POSITION `Posição na tabela ({n}º)`; NEGATIVE_BALANCE `Saldo negativo`; STATE_MET/LEAGUE_MET/FINANCE_MET `Meta cumprida`; …_FAILED `Meta não cumprida`; TITLE_WON `Título conquistado`; NEW_SEASON `Nova temporada`; SACKING_AVERTED `Demissão evitada`.
- News titles: BOARD_OBJECTIVES `Metas da diretoria`; BOARD_ULTIMATUM `Ultimato da diretoria`; BOARD_ULTIMATUM_LIFTED `Diretoria mais tranquila`; BOARD_REVIEW `Avaliação da temporada`; BOARD_SACKED `Você foi demitido`; BOARD_SACKING_AVERTED `A diretoria queria sua saída`.
- Ultimatum body: `A diretoria exige reação imediata.` Averted body: `A diretoria queria sua saída, mas decidiu mantê-lo no cargo.`
- Grace note: `Temporada de adaptação: a diretoria avalia, mas não demite.` Disabled note: `Demissão desativada nesta carreira.`
- New-career checkbox: `A diretoria pode me demitir`, hint `Desmarcado, você recebe metas e avisos, mas nunca é demitido.`
- Sacked screen: heading `Você foi demitido`, actions `Nova carreira`, `Ver tabela final`; load-dialog chip `Demitido`.
- Advance card: `Diretoria +3` / `Diretoria −3` (U+2212 minus, as elsewhere in the UI — check `formatSigned` or similar before writing a new helper).

Steps (TDD per component; read each component's existing test first and follow its render harness):
- [ ] **Step 1:** Types, `board.ts`, `errorMessages`. `npx tsc -b`.
- [ ] **Step 2:** `boardLabels.test.ts` — every mood boundary (79/80, 59/60, 44/45, 29/30, 14/15); LEAGUE text for tiers 1, 2, 3 (both stage and position targets); STATE text for each stage. Implement. Commit.
- [ ] **Step 3:** `BoardCard` (TanStack `useQuery(['board', saveGameId])`, MUI `LinearProgress` with `color` warning below 45 and error below 30; ultimatum `Alert`; objectives list with chips; last changes). Tests: each mood band renders its label; ultimatum banner; grace note; disabled note; a change line renders `−3 · Derrota para Avaí (0–2)`. Add to `DashboardPage` beside the career card. Invalidate `['board', id]` wherever the advance/live-finish mutations already invalidate `['saveGame', id]` (grep `invalidateQueries`).
- [ ] **Step 4:** News: `newsTitle`/`newsIcon` entries for the six types (extend `newsMeta.test.ts` so each returns a non-default title); an `AdvanceEventCard` branch rendering from `payload.board` (objectives list for OBJECTIVES/REVIEW, the bodies above for ULTIMATUM/AVERTED); the MATCH branch shows `Diretoria ±n` when `board?.delta` is non-zero. Tests for each.
- [ ] **Step 5:** `SackedPage` + `Layout`: when `saveGame.status === 'SACKED'`, render `SackedPage` instead of `children` for every route except `/table` (read-only page stays reachable via "Ver tabela final"). The career record (spec §9.4) comes from `getBoard` plus the existing season-history endpoint (`getHistory`/season summaries in `api/career.ts`) — no new endpoint unless those lack W/D/L (spec §12 leaves the query to the builder). When the advance response has `board?.sacked`, invalidate `['saveGame', id]` so the redirect happens without a reload. Tests: redirect on SACKED, `/table` still renders, "Nova carreira" opens `NewCareerDialog`.
- [ ] **Step 6:** `NewCareerDialog` checkbox (checked by default, sends `sackingEnabled`); `LoadCareerDialog` chip (`SaveGameSummary.status === 'SACKED'`); `SeasonSummaryPage` *Diretoria* section listing the finished season's objectives with outcome chips and the confidence, from `getBoard`. **Read the page first to confirm it renders before rollover** (the season is FINISHED and the objectives are still the current season's); if it turns out to render after rollover, read the objectives from the latest `BOARD_REVIEW` news payload instead. Tests for each.
- [ ] **Step 7:** `npx tsc -b`, `npx vitest run`, `npm run lint` — report each exit code. Commit (`feat(board): diretoria card, news, sacked screen and career option`).

---

### Task 16: Full verification

- [ ] **Step 1:** Full backend suite once: `./gradlew test; echo "EXIT=$?"`. Fix anything this work broke. Known: tests asserting an exact news-item count for a fresh career now see one more item (`BOARD_OBJECTIVES`), and tests asserting the exact item count after a season or rollover see the board items — update those assertions with a comment naming the board items, never by filtering board news out of the production feed.
- [ ] **Step 2:** Frontend: `npx tsc -b && npx vitest run && npm run lint`.
- [ ] **Step 3:** Manual check (the `run` skill, or `docker-compose up` per `README.md`): create a career, see the *Diretoria* card at 60 with three objectives, advance five matches and watch the change log, open the news feed and find *Metas da diretoria*.
- [ ] **Step 4:** Update the spec's status line to "Built" with the date, and commit (`docs(spec): board objectives built`).

---

## Self-review against the spec

| Spec section | Task |
|---|---|
| §4 data model, `SACKED` | 1, 7 |
| §4 seed scope/tier | 2 |
| §5.1 strength | 3 |
| §5.2 every division, §5.3 state, §5.5 finding competitions | 4, 9 |
| §5.4 finance after prize | 12 |
| §6.1 per-match | 5, 10 |
| §6.2 resolutions | 5, 10, 12 |
| §6.3 between seasons | 5, 13 |
| §6.4 mood label | 15 |
| §7.1 ultimatum/sacking | 6, 10, 11 |
| §7.2 review | 12 |
| §7.3 sacked guards | 11 |
| §7.4 disabled, R1 grace | 6, 10, 11 |
| §7.5 existing careers | 9 |
| §8 hooks | 9, 10, 12, 13 |
| §9 API and UI | 8, 14, 15 |
| §11 testing | every task |
