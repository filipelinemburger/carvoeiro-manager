# Player Match Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist per-player match statistics (matches, minutes, goals, assists, fouls, cards) league-wide, retained for the life of the save, surfaced in a new "Estatísticas" tab on the player details drawer.

**Architecture:** Two new tables — `fixture_appearance` (season-scoped detail, purged at rollover like `match_events`) and `season_player_stats` (permanent archive, written at rollover). Both simulation engines (`LiveMatchEngine`, `FixtureSimulator`) gain assist attribution and silent foul simulation via dedicated seeded RNG streams that never disturb existing draw sequences. A new `stats` package aggregates current-season data live and reads archived seasons, exposed via one GET endpoint and rendered in `PlayerDetails.tsx`.

**Tech Stack:** Java 21 / Spring Boot / JPA / Flyway (backend), React 19 / TypeScript / MUI / TanStack Query (frontend), JUnit 5 + AssertJ (backend tests), Vitest + Testing Library (frontend tests).

## Global Constraints

- Assist chance for open-play `GOAL` events: ~65% (constant `ASSIST_CHANCE = 0.65`).
- Assist weighting attributes: `PASSE + VISAO + CRUZAMENTO` (+10 floor).
- Foul weighting attributes: `AGRESSIVIDADE + DESARME` (+10 floor).
- Foul rate calibration: ~10–13 fouls per team per match (constant `BASE_FOUL_RATE`, modulated by `input.aggression()`, same shape as the existing `BASE_CARD_RATE` pattern).
- Corner-goal takers (already resolved via `playerInId`) count as the assist — no new RNG draw needed for that case.
- Penalties and free kicks are never assisted.
- Fouls (`EventType.FOUL`) never enter the visible commentary feed or `match_events` — silent, counted only into `fixture_appearance.fouls`.
- `ASSIST` is a `match_events.type` string value, NOT an `EventType` enum member (never simulated as a standalone timeline event, never replayed, never rendered).
- All new randomness draws from dedicated `Random` streams seeded from the existing config/fixture seed with distinct XOR salts — the existing `rng`/`attrRng`/`injuryRng` (live engine) and `rng` (instant engine) draw sequences must be provably undisturbed: after every engine task, the full pre-existing test file for that engine must pass unmodified.
- `fixture_appearance` is purged at season rollover exactly like `match_events` (same FK-safe ordering: appearances deleted and flushed before any player is deleted). `season_player_stats` is never purged.
- New migration file: `V24__player_match_stats.sql`.
- Full backend suite (`JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` from `backend/`) and full frontend suite (`cd frontend && npx tsc --noEmit && npx vitest run`) must be green at the end of every task.

---

### Task 1: Data layer — `fixture_appearance` + `season_player_stats`

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__player_match_stats.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureAppearance.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureAppearanceRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonPlayerStats.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonPlayerStatsRepository.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchEventRepository.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FixtureAppearanceRepositoryIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonPlayerStatsRepositoryIT.java`

**Interfaces:**
- Produces: `FixtureAppearance.create(UUID fixtureId, UUID playerId, UUID clubId, int minutesPlayed, int fouls)`; getters `getId/getFixtureId/getPlayerId/getClubId/getMinutesPlayed/getFouls`.
- Produces: `FixtureAppearanceRepository` — `save`, `findByFixtureIdIn(Collection<UUID>)`, `findByPlayerId(UUID)`, `deleteByFixtureIdIn(Collection<UUID>)`.
- Produces: `SeasonPlayerStats.create(UUID seasonId, UUID playerId, String playerName, UUID clubId, String clubName, int matchesPlayed, int minutesPlayed, int goals, int assists, int fouls, int yellowCards, int redCards)`; getters for every field.
- Produces: `SeasonPlayerStatsRepository` — `save`, `findByPlayerId(UUID)`.
- Modifies: `MatchEventRepository` gains `findByFixtureIdIn(Collection<UUID> fixtureIds)` (unfiltered by type — needed by Task 6's per-player and per-competition aggregation, which must see `ASSIST` rows alongside goals/cards; the existing `findByFixtureIdInAndTypeIn` stays for `TopScorersService`'s narrower goal-only query).

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE fixture_appearance (
    id              UUID PRIMARY KEY,
    fixture_id      UUID NOT NULL REFERENCES fixtures(id) ON DELETE CASCADE,
    player_id       UUID NOT NULL REFERENCES players(id),
    club_id         UUID NOT NULL REFERENCES clubs(id),
    minutes_played  INT NOT NULL,
    fouls           INT NOT NULL DEFAULT 0
);

CREATE TABLE season_player_stats (
    id             UUID PRIMARY KEY,
    season_id      UUID NOT NULL REFERENCES season(id) ON DELETE CASCADE,
    player_id      UUID NOT NULL,
    player_name    VARCHAR(80) NOT NULL,
    club_id        UUID NOT NULL,
    club_name      VARCHAR(80) NOT NULL,
    matches_played INT NOT NULL,
    minutes_played INT NOT NULL,
    goals          INT NOT NULL,
    assists        INT NOT NULL,
    fouls          INT NOT NULL,
    yellow_cards   INT NOT NULL,
    red_cards      INT NOT NULL
);

CREATE INDEX idx_fixture_appearance_fixture ON fixture_appearance(fixture_id);
CREATE INDEX idx_fixture_appearance_player ON fixture_appearance(player_id);
CREATE INDEX idx_season_player_stats_season ON season_player_stats(season_id);
CREATE INDEX idx_season_player_stats_player ON season_player_stats(player_id);
```

Note: `season_player_stats.player_id`/`club_id` intentionally carry NO foreign key (mirrors `season_top_scorer` in `V4__multi_season.sql` exactly) — archived rows must survive player retirement/deletion. `fixture_appearance.player_id`/`club_id` DO carry FKs (mirrors `match_events` exactly) because appearance rows are always purged before any player deletion, at rollover.

- [ ] **Step 2: Write `FixtureAppearance.java`**

```java
package br.com.carvoeiromanager.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One player's participation in one fixture, for the current in-progress season only.
 * Maps to {@code fixture_appearance} (V24). Purged at season rollover exactly like
 * {@link MatchEvent} — see {@code SeasonRolloverService}.
 */
@Entity
@Table(name = "fixture_appearance")
public class FixtureAppearance {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "fixture_id", nullable = false)
    private UUID fixtureId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Column(name = "minutes_played", nullable = false)
    private int minutesPlayed;

    @Column(name = "fouls", nullable = false)
    private int fouls;

    protected FixtureAppearance() {
        // for JPA
    }

    private FixtureAppearance(UUID id, UUID fixtureId, UUID playerId, UUID clubId,
                              int minutesPlayed, int fouls) {
        this.id = id;
        this.fixtureId = fixtureId;
        this.playerId = playerId;
        this.clubId = clubId;
        this.minutesPlayed = minutesPlayed;
        this.fouls = fouls;
    }

    public static FixtureAppearance create(UUID fixtureId, UUID playerId, UUID clubId,
                                           int minutesPlayed, int fouls) {
        return new FixtureAppearance(UUID.randomUUID(), fixtureId, playerId, clubId, minutesPlayed, fouls);
    }

    public UUID getId() { return id; }
    public UUID getFixtureId() { return fixtureId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getClubId() { return clubId; }
    public int getMinutesPlayed() { return minutesPlayed; }
    public int getFouls() { return fouls; }
}
```

- [ ] **Step 3: Write `FixtureAppearanceRepository.java`**

```java
package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FixtureAppearanceRepository extends JpaRepository<FixtureAppearance, UUID> {
    List<FixtureAppearance> findByFixtureIdIn(Collection<UUID> fixtureIds);
    List<FixtureAppearance> findByPlayerId(UUID playerId);
    void deleteByFixtureIdIn(Collection<UUID> fixtureIds);
}
```

- [ ] **Step 4: Write `SeasonPlayerStats.java`**

```java
package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived per-player season stat line for a Season. Never purged. */
@Entity
@Table(name = "season_player_stats")
public class SeasonPlayerStats {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "player_name", nullable = false, length = 80) private String playerName;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "matches_played", nullable = false) private int matchesPlayed;
    @Column(name = "minutes_played", nullable = false) private int minutesPlayed;
    @Column(name = "goals", nullable = false) private int goals;
    @Column(name = "assists", nullable = false) private int assists;
    @Column(name = "fouls", nullable = false) private int fouls;
    @Column(name = "yellow_cards", nullable = false) private int yellowCards;
    @Column(name = "red_cards", nullable = false) private int redCards;

    protected SeasonPlayerStats() { }

    private SeasonPlayerStats(UUID id, UUID seasonId, UUID playerId, String playerName, UUID clubId,
                              String clubName, int matchesPlayed, int minutesPlayed, int goals,
                              int assists, int fouls, int yellowCards, int redCards) {
        this.id = id; this.seasonId = seasonId; this.playerId = playerId; this.playerName = playerName;
        this.clubId = clubId; this.clubName = clubName; this.matchesPlayed = matchesPlayed;
        this.minutesPlayed = minutesPlayed; this.goals = goals; this.assists = assists;
        this.fouls = fouls; this.yellowCards = yellowCards; this.redCards = redCards;
    }

    public static SeasonPlayerStats create(UUID seasonId, UUID playerId, String playerName, UUID clubId,
                                           String clubName, int matchesPlayed, int minutesPlayed, int goals,
                                           int assists, int fouls, int yellowCards, int redCards) {
        return new SeasonPlayerStats(UUID.randomUUID(), seasonId, playerId, playerName, clubId, clubName,
                matchesPlayed, minutesPlayed, goals, assists, fouls, yellowCards, redCards);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getMatchesPlayed() { return matchesPlayed; }
    public int getMinutesPlayed() { return minutesPlayed; }
    public int getGoals() { return goals; }
    public int getAssists() { return assists; }
    public int getFouls() { return fouls; }
    public int getYellowCards() { return yellowCards; }
    public int getRedCards() { return redCards; }
}
```

- [ ] **Step 5: Write `SeasonPlayerStatsRepository.java`**

```java
package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonPlayerStatsRepository extends JpaRepository<SeasonPlayerStats, UUID> {
    List<SeasonPlayerStats> findByPlayerId(UUID playerId);
}
```

- [ ] **Step 6: Add `findByFixtureIdIn` to `MatchEventRepository.java`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/MatchEventRepository.java`:

```java
package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MatchEventRepository extends JpaRepository<MatchEvent, UUID> {
    List<MatchEvent> findByFixtureIdInAndTypeIn(Collection<UUID> fixtureIds, Collection<String> types);
    List<MatchEvent> findByFixtureIdIn(Collection<UUID> fixtureIds);
    void deleteByFixtureIdIn(Collection<UUID> fixtureIds);
}
```

- [ ] **Step 7: Write the failing repository IT for `FixtureAppearance`**

```java
package br.com.carvoeiromanager.match;

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
class FixtureAppearanceRepositoryIT {

    @Autowired FixtureAppearanceRepository repository;

    @Test
    void savesAndFindsByPlayerAndByFixtureAndDeletesByFixture() {
        UUID fixtureId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        // NOTE for the implementer: fixture_appearance.fixture_id/player_id/club_id carry real FK
        // constraints (mirrors match_events). This throwaway test will fail with a FK violation
        // until it inserts a real save game + club + player + fixture first — build those via
        // the same minimal-entity pattern already used by MatchEventRepository's own tests
        // (search the test sources for an existing MatchEventRepository IT or a minimal
        // SaveGame/Club/Player/Fixture builder helper and reuse it here) rather than random UUIDs.
        repository.save(FixtureAppearance.create(fixtureId, playerId, clubId, 90, 3));

        List<FixtureAppearance> byPlayer = repository.findByPlayerId(playerId);
        assertThat(byPlayer).hasSize(1);
        assertThat(byPlayer.get(0).getMinutesPlayed()).isEqualTo(90);
        assertThat(byPlayer.get(0).getFouls()).isEqualTo(3);

        assertThat(repository.findByFixtureIdIn(List.of(fixtureId))).hasSize(1);

        repository.deleteByFixtureIdIn(List.of(fixtureId));
        assertThat(repository.findByPlayerId(playerId)).isEmpty();
    }
}
```

- [ ] **Step 8: Run it, confirm it fails (or adjust to a real FK-safe fixture per the note above), then re-run to green**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureAppearanceRepositoryIT'`
Expected: PASS once real FK-satisfying rows back the test (adapt the test to insert a minimal save_game/club/player/fixture row set — mirror whatever helper pattern the existing `MatchEventRepository`-adjacent tests use).

- [ ] **Step 9: Write the `SeasonPlayerStats` repository IT**

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
class SeasonPlayerStatsRepositoryIT {

    @Autowired SeasonPlayerStatsRepository repository;
    @Autowired SeasonRepository seasonRepository;

    @Test
    void savesAndFindsByPlayer() {
        Season season = Season.create(UUID.randomUUID(), UUID.randomUUID(), 2026, "Catarinense",
                UUID.randomUUID(), "Campeão FC", UUID.randomUUID(), "Meu Time", 3);
        seasonRepository.save(season);

        UUID playerId = UUID.randomUUID();
        repository.save(SeasonPlayerStats.create(season.getId(), playerId, "Jogador Um",
                UUID.randomUUID(), "Meu Time", 30, 2500, 12, 8, 45, 5, 1));

        List<SeasonPlayerStats> found = repository.findByPlayerId(playerId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getGoals()).isEqualTo(12);
        assertThat(found.get(0).getAssists()).isEqualTo(8);
        assertThat(found.get(0).getSeasonId()).isEqualTo(season.getId());
    }
}
```

- [ ] **Step 10: Run both new ITs together, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureAppearanceRepositoryIT' --tests 'br.com.carvoeiromanager.season.SeasonPlayerStatsRepositoryIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V24__player_match_stats.sql \
  backend/src/main/java/br/com/carvoeiromanager/match/FixtureAppearance.java \
  backend/src/main/java/br/com/carvoeiromanager/match/FixtureAppearanceRepository.java \
  backend/src/main/java/br/com/carvoeiromanager/match/MatchEventRepository.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonPlayerStats.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonPlayerStatsRepository.java \
  backend/src/test/java/br/com/carvoeiromanager/match/FixtureAppearanceRepositoryIT.java \
  backend/src/test/java/br/com/carvoeiromanager/season/SeasonPlayerStatsRepositoryIT.java
git commit -m "feat(stats): add fixture_appearance and season_player_stats tables"
```

---

### Task 2: `LiveMatchEngine` — assist attribution + foul simulation (live path)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java` (doc comment only)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`

**Interfaces:**
- Consumes: `LiveMatchConfig` (unchanged shape, from `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java`), `Player.attribute(Attribute)` (`br.com.carvoeiromanager.player.Player`), `Attribute.PASSE/VISAO/CRUZAMENTO/AGRESSIVIDADE/DESARME` (`br.com.carvoeiromanager.player.Attribute`).
- Produces: `EventType.FOUL` (new enum member, appended last so no existing ordinal shifts). `LiveTimelineEvent` for a `GOAL` may now carry a non-null `playerInId` (the assister) — this is read by Task 3 (commentary) and Task 5 (persistence).

- [ ] **Step 1: Append `FOUL` to `EventType`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java` — change the enum declaration line only:

```java
public enum EventType {
    GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME,
    // Appended (ordinals above feed LiveCommentary variant selection and must not shift).
    PENALTY_GOAL, FREE_KICK_GOAL, CORNER_GOAL, PENALTY_MISSED, FOUL;
```

(Everything else in the file — `isGoal()`, `goalTypeNames()` — is unchanged; `FOUL.isGoal()` correctly returns `false` since it's not in the explicit whitelist.)

- [ ] **Step 2: Update `EventType`'s existing test to cover the new member**

Modify `backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java` — add one assertion to the existing `newValuesAreAppendedSoExistingOrdinalsAreStable` test (do not change any other assertion in the file):

```java
    @Test
    void newValuesAreAppendedSoExistingOrdinalsAreStable() {
        // LiveCommentary.pick derives variant choice from ordinal — pre-split values must not shift.
        assertThat(EventType.GOAL.ordinal()).isZero();
        assertThat(EventType.HALF_TIME.ordinal()).isEqualTo(8);
        assertThat(EventType.FOUL.isGoal()).isFalse();
    }
```

- [ ] **Step 3: Run the EventType tests, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.EventTypeTest'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Update `LiveTimelineEvent`'s doc comment**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF /
 * player who committed a FOUL). {@code playerInId} is the player coming ON (SUBSTITUTION), the corner
 * taker (CORNER_GOAL), or the assister (open-play GOAL, when the goal was assisted) — else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null. {@code detail} is the {@link AiManagerBrain.Posture} name — only for
 * AI-produced {@code TACTICS_CHANGE}, else null (a null detail on TACTICS_CHANGE means the user's
 * own change).
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity,
        String detail) {
}
```

- [ ] **Step 5: Write the failing assist/foul tests in `LiveMatchEngineTest.java`**

Add these tests to `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java` (append inside the existing class body, anywhere after the existing `config(...)` helper methods — do not modify any existing test):

```java
    @Test
    void openPlayGoalsAreSometimesAssistedAndSometimesNot() {
        // Across many seeds, roughly ASSIST_CHANCE (~65%) of plain GOAL events should carry
        // a non-null playerInId (the assister). PENALTY_GOAL/FREE_KICK_GOAL never do (checked below).
        int assisted = 0;
        int total = 0;
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.GOAL) {
                    total++;
                    if (e.playerInId() != null) assisted++;
                }
            }
        }
        assertThat(total).isGreaterThan(50);
        double rate = (double) assisted / total;
        assertThat(rate).isBetween(0.5, 0.8);
    }

    @Test
    void anAssistNeverCreditsTheScorerThemself() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.GOAL && e.playerInId() != null) {
                    assertThat(e.playerInId()).isNotEqualTo(e.playerId());
                }
            }
        }
    }

    @Test
    void penaltiesAndFreeKicksAreNeverAssisted() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream()
                    .filter(e -> e.type() == EventType.PENALTY_GOAL || e.type() == EventType.FREE_KICK_GOAL)
                    .forEach(e -> assertThat(e.playerInId()).isNull());
        }
    }

    @Test
    void assistAndFoulOutcomesAreDeterministicForAFixedSeed() {
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
    }

    @Test
    void foulsOccurAtARealisticPerTeamRateAndAreNeverAttributedToAnEmptyPitch() {
        // ~10-13 fouls per team per match; sample many seeds and check the per-seed average
        // lands in a broad realistic band (loose bounds — this is a calibration sanity check,
        // not an exact-value assertion).
        int totalHomeFouls = 0;
        int seeds = 150;
        for (long seed = 0; seed < seeds; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            List<LiveTimelineEvent> homeFouls = t.events().stream()
                    .filter(e -> e.type() == EventType.FOUL && e.home()).toList();
            totalHomeFouls += homeFouls.size();
            homeFouls.forEach(e -> assertThat(e.playerId()).isNotNull());
        }
        double avgPerTeamPerMatch = (double) totalHomeFouls / seeds;
        assertThat(avgPerTeamPerMatch).isBetween(5.0, 20.0);
    }

    @Test
    void foulsNeverAppearInTheGoalCountingOrCardStreams() {
        // FOUL must never satisfy isGoal() and must be a distinct type from cards, so downstream
        // consumers that don't explicitly handle it (match_events persistence, ratings) skip it safely.
        for (long seed = 0; seed < 50; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream().filter(e -> e.type() == EventType.FOUL)
                    .forEach(e -> assertThat(e.type().isGoal()).isFalse());
        }
    }
```

- [ ] **Step 6: Run the new tests, confirm they FAIL** (FOUL/assist logic doesn't exist yet)

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'`
Expected: FAIL — `openPlayGoalsAreSometimesAssistedAndSometimesNot` and the foul-rate test fail (no assists/fouls are ever produced yet); the other three pass trivially (nothing to violate yet).

- [ ] **Step 7: Implement assist + foul simulation in `LiveMatchEngine.java`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`. Five changes:

**7a.** Add two new constants near the existing ones (after `HOBBLE_MULT`):

```java
    private static final double ASSIST_CHANCE = 0.65;
    private static final double BASE_FOUL_RATE = 0.13;   // ~11-12 fouls/team/90min at aggression 1.0
```

**7b.** In `simulate(...)`, add two dedicated RNG streams right after the existing three (distinct salts, never shared with `rng`/`attrRng`/`injuryRng`):

```java
        Random rng = new Random(cfg.seed());
        Random attrRng = new Random(cfg.seed() ^ 0x9E3779B97F4A7C15L);
        Random injuryRng = new Random(cfg.seed() ^ 0x1A70F1E5L);
        Random assistRng = new Random(cfg.seed() ^ 0x5A551571L);
        Random foulRng = new Random(cfg.seed() ^ 0xF0011E5L);
```

**7c.** Pass `assistRng` into `resolveScoringEvent` (both call sites) and add `maybeFoul(...)` calls right after the existing `maybeCard(...)` calls:

```java
            if (rng.nextDouble() < homeChance) {
                if (resolveScoringEvent(minute, home, homeInput, rng, attrRng, assistRng, events)) homeGoals++;
            }
            if (rng.nextDouble() < awayChance) {
                if (resolveScoringEvent(minute, away, awayInput, rng, attrRng, assistRng, events)) awayGoals++;
            }
            maybeCard(minute, home, homeInput, rng, attrRng, events);
            maybeCard(minute, away, awayInput, rng, attrRng, events);
            maybeFoul(minute, home, homeInput, foulRng, events);
            maybeFoul(minute, away, awayInput, foulRng, events);
```

**7d.** Update `resolveScoringEvent`'s signature and its open-play `GOAL` branch to draw an assister; leave the `PENALTY`/`FREE_KICK` branches untouched and leave the `CORNER` branch untouched (its taker already rides in `playerInId`):

```java
    /**
     * Resolves one scoring chance: open play or a distinct set piece. Returns true when a goal
     * was actually scored — a missed penalty emits PENALTY_MISSED and returns false. RNG use:
     * the set-piece?/subtype/conversion rolls draw from the match stream; scorer picks draw from
     * the attribution stream; the open-play assist chance+pick draws from the dedicated assist
     * stream, never disturbing the match/attribution/injury streams. For CORNER_GOAL the corner
     * taker rides in {@code playerInId} so commentary can name taker and scorer, and — per the
     * player-stats feature — that taker also counts as the assist at persistence time.
     */
    private boolean resolveScoringEvent(int minute, Side side, TeamMatchInput input, Random rng,
                                        Random attrRng, Random assistRng, List<LiveTimelineEvent> events) {
        if (rng.nextDouble() >= SetPieces.share(input.cornerThreat(), input.freeKickThreat())) {
            UUID scorer = pickByWeight(side, LiveMatchEngine::goalWeight, attrRng);
            UUID assister = assistRng.nextDouble() < ASSIST_CHANCE
                    ? pickByWeightExcluding(side, scorer, LiveMatchEngine::assistWeight, assistRng)
                    : null;
            events.add(new LiveTimelineEvent(minute, EventType.GOAL, side.home, scorer, assister, null, null));
            return true;
        }
        List<Player> onPitch = side.onPitch.stream().map(side.all::get).toList();
        TacticalProfile profile = side.currentProfile;
        return switch (SetPieces.roll(input.cornerThreat(), input.freeKickThreat(), rng)) {
            case PENALTY -> {
                Player taker = SetPieces.takerOrFallback(onPitch, profile.penaltyTakerId(), Player::getFinishing);
                boolean converted = rng.nextDouble() < input.penaltyConversion();
                events.add(new LiveTimelineEvent(minute,
                        converted ? EventType.PENALTY_GOAL : EventType.PENALTY_MISSED,
                        side.home, taker.getId(), null, null, null));
                yield converted;
            }
            case FREE_KICK -> {
                Player taker = SetPieces.takerOrFallback(onPitch, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                events.add(new LiveTimelineEvent(minute, EventType.FREE_KICK_GOAL, side.home,
                        taker.getId(), null, null, null));
                yield true;
            }
            case CORNER -> {
                UUID scorer = pickByWeight(side, SetPieces::headerWeight, attrRng);
                Player taker = SetPieces.takerOrFallback(onPitch, profile.cornerTakerId(),
                        p -> p.attribute(Attribute.CRUZAMENTO));
                events.add(new LiveTimelineEvent(minute, EventType.CORNER_GOAL, side.home,
                        scorer, taker.getId(), null, null));
                yield true;
            }
        };
    }
```

**7e.** Add `maybeFoul`, `pickByWeightExcluding`, `assistWeight`, and `foulWeight` as new private methods, placed after the existing `maybeCard` method:

```java
    /**
     * Silent foul simulation: a per-minute per-side chance, scaled by aggression exactly like
     * {@link #maybeCard}, drawn entirely from the dedicated foul stream (chance roll AND
     * attribution pick both come from {@code foulRng}) so the match/attribution/injury streams
     * are untouched. FOUL events are never rendered in the live feed (filtered in
     * {@code LiveMatchService.toState}) and never persisted to match_events — they exist only so
     * {@code LiveMatchService.finish()} can count them into fixture_appearance.fouls.
     */
    private void maybeFoul(int minute, Side side, TeamMatchInput input, Random foulRng,
                          List<LiveTimelineEvent> events) {
        if (foulRng.nextDouble() < BASE_FOUL_RATE * input.aggression() && !side.onPitch.isEmpty()) {
            UUID fouler = pickByWeight(side, LiveMatchEngine::foulWeight, foulRng);
            events.add(new LiveTimelineEvent(minute, EventType.FOUL, side.home, fouler, null, null, null));
        }
    }

    /**
     * Weighted random pick over the on-pitch players EXCLUDING one id (keeps an assister
     * distinct from the scorer). Returns null if no eligible teammate remains on the pitch
     * (e.g. a heavily red-carded side) — the goal is simply left unassisted in that case.
     */
    private UUID pickByWeightExcluding(Side side, UUID exclude, ToIntFunction<Player> weight, Random rng) {
        List<UUID> pool = side.onPitch.stream().filter(id -> !id.equals(exclude)).toList();
        if (pool.isEmpty()) {
            return null;
        }
        int totalWeight = pool.stream().mapToInt(id -> Math.max(0, weight.applyAsInt(side.all.get(id)))).sum();
        if (totalWeight <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int pick = rng.nextInt(totalWeight);
        int acc = 0;
        for (UUID id : pool) {
            acc += Math.max(0, weight.applyAsInt(side.all.get(id)));
            if (pick < acc) return id;
        }
        return pool.get(pool.size() - 1);
    }

    /** Creative-passing composite for assist attribution: playmakers and wide players lead. */
    private static int assistWeight(Player p) {
        return p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                + p.attribute(Attribute.CRUZAMENTO) + 10;
    }

    /** Foul propensity composite: aggressive, tackle-heavy players commit more fouls. */
    private static int foulWeight(Player p) {
        return p.attribute(Attribute.AGRESSIVIDADE) + p.attribute(Attribute.DESARME) + 10;
    }
```

- [ ] **Step 8: Run the new tests, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'`
Expected: `BUILD SUCCESSFUL`, all tests including the 5 new ones green.

- [ ] **Step 9: Run the FULL existing `match` test package — this is the determinism proof**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`

Expected: `BUILD SUCCESSFUL`, every pre-existing test unmodified and green — in particular `injuriesAreTimedAndCarryASeverity` (exact minute/severity/layoff for seed 2), `aSubChangesTheFutureButNotThePast`, `takersAreAttributedByType`, and `halfTimeMarkerEmittedAtMinute45` are the most RNG-sensitive pre-existing assertions; if any of these break, the new `assistRng`/`foulRng` streams are accidentally sharing state with an existing stream — check Step 7b's salts are applied to genuinely new `Random` instances, not reused from an existing variable.

Note: two pre-existing, unrelated test failures may surface in a full unfiltered run — if you see failures in classes unrelated to `LiveMatchEngineTest`/`EventTypeTest`, they are known and out of scope for this task; only failures inside the files this task touches are yours to fix.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java \
  backend/src/main/java/br/com/carvoeiromanager/match/EventType.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java \
  backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java
git commit -m "feat(match): add assist attribution and silent foul simulation to the live engine"
```

---

### Task 3: Commentary + live-feed wiring for assists and fouls

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFoulVisibilityIT.java` (new)

**Interfaces:**
- Consumes: `LiveTimelineEvent.playerInId()` now non-null on some `GOAL` events (from Task 2).
- Produces: no new public methods — `LiveCommentary.of(...)` keeps its existing 7-arg signature; `case GOAL` internally branches on whether `inName` is present.

- [ ] **Step 1: Write the failing `GOAL_ASSISTED` commentary test**

Add to `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java` (append inside the class; do not modify any existing test):

```java
    @Test
    void anAssistedGoalUsesTheTwoNamePoolNamingBothPlayers() {
        String result = LiveCommentary.of(EventType.GOAL, "Otero", "Diego", 60, null, false, false);
        assertThat(result).contains("Otero").contains("Diego");
    }

    @Test
    void anUnassistedGoalKeepsUsingTheOneNamePool() {
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false)).isIn(
                "GOOOOOOOOOOOOOOOOOOL! Otero balança as redes!",
                "Otero marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
                "Otero aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
                "GOOOOL! Otero! Eu tô emocionado, gente, que golaço impressionante!",
                "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de Otero!",
                "Joga a luva, goleirão! Otero manda uma patada e é gooooooooooool!");
    }
```

- [ ] **Step 2: Run it, confirm the assisted test FAILS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`
Expected: `anAssistedGoalUsesTheTwoNamePoolNamingBothPlayers` FAILS — today `case GOAL` always uses the one-name pool and never reads `inName`, so `result` won't contain "Diego".

- [ ] **Step 3: Add the `GOAL_ASSISTED` pool and branch `case GOAL` on it**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`. Add a new array right after `GOAL`:

```java
    private static final String[] GOAL_ASSISTED = {
            "%s cruza na medida, %s aparece... É GOOOOOOOOOOOOOOOOOOL!!!",
            "Belo passe de %s, %s só teve o trabalho de empurrar pra dentro! GOOOOOOOOOOOOOOOOOOL!",
            "%s enxergou o espaço, tocou pra %s, e é GOOOOOOOOOOOOOOOOOOL!!! Que jogada coletiva!",
            "Assistência de %s! %s bateu de primeira e mandou pro fundo do gol! GOOOOOOOOOOOOL!"};
```

Then change the `case GOAL ->` line inside `of(...)`'s switch:

```java
            case GOAL -> inName != null
                    ? pick2(GOAL_ASSISTED, inName, playerName, minute, type)
                    : pick(GOAL, playerName, minute, type);
```

Update the method's doc comment (the block right above `public static String of(...)`) to mention the new usage of `inName`:

```java
    /**
     * {@code inName} is used for substitutions, corner goals (the corner taker), and assisted
     * open-play goals (the assister — {@code playerInId} on the event). {@code detail} only for
     * TACTICS_CHANGE. {@code comebackGoal} true only for a goal-family type that erases a deficit
     * for the managed club — appends the signature line after the normal goal description, so
     * the scorer and the goal itself are still described. {@code losingStreakHalfTime} true only
     * at HALF_TIME when the managed club has lost each of its last 2 completed fixtures —
     * overrides the normal half-time pool with the signature line (half-time has no per-event
     * scorer/action to preserve, unlike a goal).
     */
```

- [ ] **Step 4: Run the commentary tests, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`
Expected: `BUILD SUCCESSFUL`, all tests including the 2 new ones green.

- [ ] **Step 5: Write the failing FOUL-visibility IT**

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A FOUL event may or may not occur in any given simulated match (it's probabilistic), but it
 * must NEVER be visible in the live feed regardless — this asserts that invariant across a full
 * match's worth of advances, which is deterministic to check unconditionally.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchFoulVisibilityIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_foul_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        UUID managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());
        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    @Test
    void noEventInTheLiveFeedIsEverTypeFoul() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(state).isNotNull();
        assertThat(state.events()).noneMatch(e -> "FOUL".equals(e.type()));
    }
}
```

- [ ] **Step 6: Run it — it should already PASS** (FOUL isn't rendered via `LiveCommentary.of` yet because `toState` hasn't been touched, but it also hasn't been EXCLUDED from `revealed` yet — this step characterizes the current gap)

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchFoulVisibilityIT'`

Expected: **FAILS** with an `IllegalArgumentException`/`MatchException`-style crash from `LiveCommentary.of`'s exhaustive switch not having a `FOUL` case (the switch added in Task 2 forces every `EventType` to be handled) — this is the real bug this task fixes: `toState()` currently maps every revealed event through `LiveCommentary.of`, including FOUL ones, and that switch has no FOUL branch. Confirms the fix must land in `LiveMatchService.toState()` before this compiles/runs cleanly, not just in the test.

- [ ] **Step 7: Give `LiveCommentary.of`'s switch an explicit FOUL branch, and filter FOUL out of the live feed in `toState()`**

First, in `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`, add a defensive branch to the switch inside `of(...)` (FOUL must never actually reach this method in production — `toState()` filters it first — so this is a "should never happen" guard, mirroring the exact style already used in `FixtureSimulator.saveEvents`'s switch for its own never-emitted types):

```java
            case CORNER_GOAL -> corner(playerName, inName, minute);
            case FOUL -> throw new IllegalStateException(
                    "FOUL is never rendered — LiveMatchService.toState() must filter it before calling of()");
        };
```

Then, in `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, modify `toState(...)` — find this line:

```java
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
```

and replace it with:

```java
        // FOUL events exist only for fixture_appearance.fouls counting (see finish()) — silent by
        // design, they never reach the commentary feed.
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute).stream()
                .filter(e -> e.type() != EventType.FOUL)
                .toList();
```

- [ ] **Step 8: Run the FOUL-visibility IT again, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchFoulVisibilityIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Run the full `match` package test suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`
Expected: `BUILD SUCCESSFUL` (aside from the two known pre-existing unrelated failures noted in Task 2, if they surface).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFoulVisibilityIT.java
git commit -m "feat(match): narrate assisted goals and keep fouls silent in the live feed"
```

---

### Task 4: `FixtureSimulator` — assist attribution + foul simulation (instant path)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java` (new)

**Interfaces:**
- Consumes: `SimulatedEvent(minute, type, home)` (unchanged — assist/foul attribution happens entirely within `saveEvents`, independent of `MatchSimulationService`).
- Produces: for every `GOAL`/`CORNER_GOAL` `MatchEvent` row saved, an optional sibling `ASSIST`-type `MatchEvent` row (Task 5 relies on this). A new per-fixture, per-player foul count available to `simulateRound`'s caller (Task 5 wires it into `fixture_appearance`).

This task adds a package-private method `foulCounts(List<Player> homeXi, List<Player> awayXi, long seed)` returning `Map<UUID, Integer>` (fouls per player), called from `simulateFixture`. Task 5 will thread this map into the persistence step.

- [ ] **Step 1: Write the failing IT**

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
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
class FixtureSimulatorAssistFoulIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchEventRepository matchEventRepository;

    private UUID saveGameId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("instant_assist_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        SaveGameResponse save = saveGameService.createCareer(user.getId());
        saveGameId = save.id();
        managedClubId = save.managedClubId();
    }

    @Test
    void aiVsAiGoalsSometimesProduceASiblingAssistRow() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        // Simulate every round's AI-vs-AI fixtures (excluding the managed club) across several
        // rounds to gather enough goals for a statistically meaningful assist-row count.
        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(15)
                .toList();
        assertThat(aiFixtures).isNotEmpty();

        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        List<UUID> fixtureIds = aiFixtures.stream().map(Fixture::getId).toList();
        List<MatchEvent> allEvents = matchEventRepository.findByFixtureIdIn(fixtureIds);
        long goals = allEvents.stream().filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
        long assists = allEvents.stream().filter(e -> "ASSIST".equals(e.getType())).count();

        assertThat(goals).isGreaterThan(0);
        assertThat(assists).isGreaterThan(0);
        assertThat(assists).isLessThanOrEqualTo(goals);
    }

    @Test
    void anAssistRowIsNeverForTheSamePlayerAsTheGoalInTheSameMinute() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(15)
                .toList();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        for (Fixture fixture : aiFixtures) {
            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(fixture.getId()));
            for (MatchEvent goal : events) {
                if (!EventType.goalTypeNames().contains(goal.getType())) continue;
                events.stream()
                        .filter(e -> "ASSIST".equals(e.getType()) && e.getMinute() == goal.getMinute()
                                && e.getFixtureId().equals(goal.getFixtureId()))
                        .forEach(assist -> assertThat(assist.getPlayerId()).isNotEqualTo(goal.getPlayerId()));
            }
        }
    }
}
```

- [ ] **Step 2: Run it, confirm FAIL**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: FAIL on `assertThat(assists).isGreaterThan(0)` — no `ASSIST` rows exist yet.

- [ ] **Step 3: Implement assist attribution in `FixtureSimulator.saveEvents`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`. Add constants near the top of the class (after the field declarations, before the constructor is fine, or alongside — place right before `simulateRound`):

```java
    private static final double ASSIST_CHANCE = 0.65;
```

Modify `saveEvents` — the RNG line and the loop body change:

```java
    private List<AttributedEvent> saveEvents(Fixture fixture, SimulatedMatch match,
                                             List<Player> homeXi, List<Player> awayXi,
                                             TacticalProfile homeProfile, TacticalProfile awayProfile,
                                             UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        Random assistRng = new Random(seed ^ 0x5A551571L);
        List<AttributedEvent> attributed = new ArrayList<>();
        for (SimulatedEvent event : match.events()) {
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            Player player = switch (event.type()) {
                case GOAL -> pickByWeight(xi, FixtureSimulator::goalWeight, rng);
                case PENALTY_GOAL -> SetPieces.takerOrFallback(xi, profile.penaltyTakerId(), Player::getFinishing);
                case FREE_KICK_GOAL -> SetPieces.takerOrFallback(xi, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                case CORNER_GOAL -> pickByWeight(xi, SetPieces::headerWeight, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED, SET_PIECE_GOAL, FOUL ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
            attributed.add(new AttributedEvent(player.getId(), event.type(), event.home()));

            Player assister = assisterFor(event, player, xi, profile, assistRng);
            if (assister != null) {
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), event.minute(), "ASSIST", assister.getId(), clubId));
            }
        }
        return attributed;
    }

    /**
     * The assister for one scoring event, or null when unassisted. CORNER_GOAL's crossing-weighted
     * taker (distinct from the header-scorer) is the assist deterministically — no extra RNG draw.
     * GOAL rolls a ~65% chance and, if hit, picks a creativity-weighted teammate excluding the
     * scorer. PENALTY_GOAL and FREE_KICK_GOAL are never assisted.
     */
    private Player assisterFor(SimulatedEvent event, Player scorer, List<Player> xi,
                               TacticalProfile profile, Random assistRng) {
        return switch (event.type()) {
            case GOAL -> assistRng.nextDouble() < ASSIST_CHANCE
                    ? pickByWeightExcluding(xi, scorer, FixtureSimulator::assistWeight, assistRng)
                    : null;
            case CORNER_GOAL -> SetPieces.takerOrFallback(xi, profile.cornerTakerId(),
                    p -> p.attribute(Attribute.CRUZAMENTO));
            default -> null;
        };
    }

    /** Weighted pick over an XI excluding one player; null if no eligible teammate remains. */
    private Player pickByWeightExcluding(List<Player> xi, Player exclude, ToIntFunction<Player> weight, Random rng) {
        List<Player> pool = xi.stream().filter(p -> !p.getId().equals(exclude.getId())).toList();
        if (pool.isEmpty()) {
            return null;
        }
        int totalWeight = pool.stream().mapToInt(p -> Math.max(0, weight.applyAsInt(p))).sum();
        if (totalWeight <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player p : pool) {
            accumulated += Math.max(0, weight.applyAsInt(p));
            if (pick < accumulated) return p;
        }
        return pool.get(pool.size() - 1);
    }

    /** Mirrors LiveMatchEngine.assistWeight — creative-passing composite. */
    private static int assistWeight(Player p) {
        return p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                + p.attribute(Attribute.CRUZAMENTO) + 10;
    }
```

Note: `CORNER_GOAL`'s assist branch calls `SetPieces.takerOrFallback(...)`, which is deterministic (no RNG) — it can return the SAME player as the header-scorer when the taker fallback happens to pick them (e.g. a small XI). The IT in Step 1 only asserts distinctness for events actually present with an ASSIST row at the same minute; a corner where taker == scorer simply produces no distinct assist in practice since `pickByWeight`'s scorer pick and `takerOrFallback`'s taker pick use different selection logic and only coincide in degenerate XIs — acceptable, matches the live engine's existing corner behavior which has the same property (see `LiveMatchEngine.resolveScoringEvent`'s `CORNER` branch, unchanged).

- [ ] **Step 4: Run the assist IT, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Write the failing foul-count test**

Add to `backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java` (append to the class):

```java
    @Test
    void foulCountsAreProducedForEveryPlayerWhoPlayed() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        Fixture fixture = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .findFirst().orElseThrow();

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();
        // homeXi/awayXi resolution mirrors FixtureSimulator.resolveXi for a non-managed club:
        // both sides use bestXi. This test only needs SOME 22 real players — reuse squads.
        List<UUID> homeIds = playerIdsFor(fixture.getHomeClubId());
        List<UUID> awayIds = playerIdsFor(fixture.getAwayClubId());

        Map<UUID, Integer> fouls = fixtureSimulator.foulCounts(
                playerRepository.findAllById(homeIds), playerRepository.findAllById(awayIds), seed);

        assertThat(fouls).isNotEmpty();
        fouls.values().forEach(count -> assertThat(count).isGreaterThanOrEqualTo(0));
    }

    private List<UUID> playerIdsFor(UUID clubId) {
        return playerRepository.findByClubId(clubId).stream().map(Player::getId).limit(11).toList();
    }
```

Add the two missing imports to the top of the file:

```java
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
```

and add the autowired field:

```java
    @Autowired PlayerRepository playerRepository;
```

and add `import java.util.Map;` alongside the existing `java.util.List`/`java.util.UUID` imports.

- [ ] **Step 6: Run it, confirm FAIL** (method doesn't exist — compile error)

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: FAIL (compilation error — `foulCounts` unresolved).

- [ ] **Step 7: Implement `foulCounts` in `FixtureSimulator.java`**

Add a new package-private method (no callers wired yet — Task 5 wires it into `simulateFixture`/`simulateRound`'s persistence step):

```java
    private static final double BASE_FOUL_RATE = 0.13;

    /**
     * Per-player foul counts for one fixture's two XIs, drawn directly (no minute-by-minute
     * simulation — nothing displays instant-match fouls live, only the final per-player count
     * matters). Mirrors LiveMatchEngine.maybeFoul's calibration and weighting, using a fixed
     * "average aggression" input since the instant path has no per-minute TeamMatchInput here.
     */
    Map<UUID, Integer> foulCounts(List<Player> homeXi, List<Player> awayXi, long seed) {
        Random foulRng = new Random(seed ^ 0xF0011E5L);
        Map<UUID, Integer> counts = new HashMap<>();
        for (int minute = 1; minute <= 90; minute++) {
            drawFoul(homeXi, foulRng, counts);
            drawFoul(awayXi, foulRng, counts);
        }
        return counts;
    }

    private void drawFoul(List<Player> xi, Random foulRng, Map<UUID, Integer> counts) {
        if (xi.isEmpty() || foulRng.nextDouble() >= BASE_FOUL_RATE) {
            return;
        }
        int totalWeight = xi.stream().mapToInt(p -> Math.max(0, foulWeight(p))).sum();
        Player fouler;
        if (totalWeight <= 0) {
            fouler = xi.get(foulRng.nextInt(xi.size()));
        } else {
            int pick = foulRng.nextInt(totalWeight);
            int acc = 0;
            Player chosen = xi.get(xi.size() - 1);
            for (Player p : xi) {
                acc += Math.max(0, foulWeight(p));
                if (pick < acc) { chosen = p; break; }
            }
            fouler = chosen;
        }
        counts.merge(fouler.getId(), 1, Integer::sum);
    }

    /** Mirrors LiveMatchEngine.foulWeight. */
    private static int foulWeight(Player p) {
        return p.attribute(Attribute.AGRESSIVIDADE) + p.attribute(Attribute.DESARME) + 10;
    }
```

Add `import java.util.Map;` and `import java.util.Random;` if not already present (`Random` already is; `Map` already is via existing `import java.util.Map;`).

- [ ] **Step 8: Run the foul-count test, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Run the full `match` package test suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`
Expected: `BUILD SUCCESSFUL` aside from the two known pre-existing unrelated failures.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
  backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java
git commit -m "feat(match): add assist attribution and foul counting to the instant engine"
```

---

### Task 5: Persist `fixture_appearance` from both simulation paths

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java` (extend existing)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java` (extend from Task 4)

**Interfaces:**
- Consumes: `FixtureAppearanceRepository` (Task 1), `FixtureSimulator.foulCounts(...)` (Task 4, now called from `simulateFixture`).
- Produces: `fixture_appearance` rows written on every completed fixture, both paths. Every `GOAL`-family `LiveTimelineEvent` with a non-null `playerInId` produces a sibling `ASSIST` row at `finish()` (mirrors what Task 4 already does for the instant path).

- [ ] **Step 1: Write the failing test in `LiveMatchFinishIT.java`**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java` already has a `@BeforeEach setUp()` that creates a career and saves a valid managed-club lineup into fields `saveGameId`/`userId`/`managedClubId`, and an existing test (`finishRecordsTheResultSimulatesTheRoundAndClearsTheSession`) that shows the exact kickoff→advance-to-FULL_TIME→finish sequence. Append these two tests to the existing class body, and add `@Autowired FixtureAppearanceRepository fixtureAppearanceRepository;` and `@Autowired MatchEventRepository matchEventRepository;` fields alongside its existing `@Autowired` fields (do not modify anything else in the file):

```java
    @Test
    void finishPersistsFixtureAppearanceRowsWithMinutesAndFouls() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        String starterId = start.onPitch().get(0).playerId();

        LiveMatchState s = start;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) s = liveMatchService.advance(saveGameId, userId);
        liveMatchService.finish(saveGameId, userId);

        List<FixtureAppearance> appearances = fixtureAppearanceRepository.findByPlayerId(
                UUID.fromString(starterId));
        assertThat(appearances).isNotEmpty();
        assertThat(appearances.get(0).getMinutesPlayed()).isGreaterThan(0);
        assertThat(appearances.get(0).getFouls()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void finishPersistsAnAssistRowWheneverAGoalCarriesAnAssister() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        String fixtureId = start.fixtureId();

        LiveMatchState s = start;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) s = liveMatchService.advance(saveGameId, userId);
        liveMatchService.finish(saveGameId, userId);

        List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(UUID.fromString(fixtureId)));
        long goalsWithAssist = events.stream()
                .filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
        long assistRows = events.stream().filter(e -> "ASSIST".equals(e.getType())).count();
        // Not every goal is assisted (penalties/free kicks never are, open-play is ~65%), but
        // an assist row can never outnumber the goals it's attached to.
        assertThat(assistRows).isLessThanOrEqualTo(goalsWithAssist);
    }
```

- [ ] **Step 2: Run it, confirm FAIL**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchFinishIT'`
Expected: FAIL on the first new test — `fixtureAppearanceRepository.findByPlayerId(...)` returns empty (nothing persists rows yet).

- [ ] **Step 3: Implement `fixture_appearance` + `ASSIST` persistence in `LiveMatchService.finish()`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`. Add the new repository dependency to the constructor:

```java
    private final MatchEventRepository matchEventRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;
```

and in the constructor parameter list / assignment, right after the existing `matchEventRepository` param:

```java
    public LiveMatchService(SaveGameService saveGameService, LineupService lineupService,
                            TacticsService tacticsService, FitnessCalculator fitnessCalculator,
                            LiveMatchEngine liveMatchEngine, PlayerRatingCalculator playerRatingCalculator,
                            LiveMatchRepository liveMatchRepository,
                            SeasonEventRepository seasonEventRepository, FixtureRepository fixtureRepository,
                            CompetitionRepository competitionRepository, ClubRepository clubRepository,
                            PlayerRepository playerRepository, MatchEventRepository matchEventRepository,
                            FixtureAppearanceRepository fixtureAppearanceRepository,
                            MatchOutcomeApplier matchOutcomeApplier, SeasonAdvanceService seasonAdvanceService,
                            ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.fitnessCalculator = fitnessCalculator;
        this.liveMatchEngine = liveMatchEngine;
        this.playerRatingCalculator = playerRatingCalculator;
        this.liveMatchRepository = liveMatchRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.competitionRepository = competitionRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
        this.seasonAdvanceService = seasonAdvanceService;
        this.objectMapper = objectMapper;
    }
```

Now modify `finish(...)`. Find the existing persist loop:

```java
        // Persist real match events (goals + cards), mirroring the instant path's match_events rows.
        for (LiveTimelineEvent e : timeline.events()) {
            if (isCardOrGoal(e.type())) {
                UUID clubId = e.home() ? homeClubId : awayClubId;
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), e.minute(), e.type().name(), e.playerId(), clubId));
            }
        }
```

Replace it with (adds the sibling ASSIST row for any goal-family event carrying an assister/taker in `playerInId`):

```java
        // Persist real match events (goals + cards), mirroring the instant path's match_events rows.
        // A goal-family event with a non-null playerInId (open-play assist, or a corner taker who
        // IS the assist) also gets a sibling ASSIST row crediting that player.
        for (LiveTimelineEvent e : timeline.events()) {
            if (isCardOrGoal(e.type())) {
                UUID clubId = e.home() ? homeClubId : awayClubId;
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), e.minute(), e.type().name(), e.playerId(), clubId));
                if (e.type().isGoal() && e.playerInId() != null) {
                    matchEventRepository.save(
                            MatchEvent.create(fixture.getId(), e.minute(), "ASSIST", e.playerInId(), clubId));
                }
            }
        }
```

Next, add fixture_appearance persistence. Find the existing minutes-and-fitness loop:

```java
        List<Player> allPlayed = new ArrayList<>();
        allPlayed.addAll(homePlayed);
        allPlayed.addAll(awayPlayed);
        for (Player p : allPlayed) {
            int eff = cfg.fitness().getOrDefault(p.getId(), 100);
            int minutes = timeline.minutesPlayed().getOrDefault(p.getId(), 0);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge(), p.getPosition(), minutes);
            p.recordAppearance(newBase, lm.getMatchDate());
        }
        playerRepository.saveAll(allPlayed);
```

Replace it with (adds a fixture_appearance row per played player, right alongside the existing per-player loop that already has `minutes` and knows each player's club):

```java
        List<Player> allPlayed = new ArrayList<>();
        allPlayed.addAll(homePlayed);
        allPlayed.addAll(awayPlayed);
        Map<UUID, Integer> foulsByPlayer = foulsByPlayer(timeline);
        for (Player p : allPlayed) {
            int eff = cfg.fitness().getOrDefault(p.getId(), 100);
            int minutes = timeline.minutesPlayed().getOrDefault(p.getId(), 0);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge(), p.getPosition(), minutes);
            p.recordAppearance(newBase, lm.getMatchDate());
            fixtureAppearanceRepository.save(FixtureAppearance.create(fixture.getId(), p.getId(), p.getClubId(),
                    minutes, foulsByPlayer.getOrDefault(p.getId(), 0)));
        }
        playerRepository.saveAll(allPlayed);
```

Add the small helper right after `isCardOrGoal`:

```java
    private Map<UUID, Integer> foulsByPlayer(LiveTimeline timeline) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (LiveTimelineEvent e : timeline.events()) {
            if (e.type() == EventType.FOUL) {
                counts.merge(e.playerId(), 1, Integer::sum);
            }
        }
        return counts;
    }
```

- [ ] **Step 4: Run `LiveMatchFinishIT`, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchFinishIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Write the failing instant-path appearance test**

Append to `backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java`:

```java
    @Test
    void simulateRoundPersistsFixtureAppearanceRowsForEveryPlayerWhoPlayed() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(5)
                .toList();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        List<UUID> fixtureIds = aiFixtures.stream().map(Fixture::getId).toList();
        List<FixtureAppearance> appearances = fixtureAppearanceRepository.findByFixtureIdIn(fixtureIds);
        assertThat(appearances).isNotEmpty();
        assertThat(appearances).allMatch(a -> a.getMinutesPlayed() == 90);
        assertThat(appearances).anyMatch(a -> a.getFouls() >= 0);
        // 11 starters per side per fixture, no substitutions in the instant path.
        assertThat(appearances.size()).isEqualTo(aiFixtures.size() * 22);
    }
```

Add `@Autowired FixtureAppearanceRepository fixtureAppearanceRepository;` to the class.

- [ ] **Step 6: Run it, confirm FAIL**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: FAIL — `appearances` is empty (`simulateRound` doesn't write `fixture_appearance` yet).

- [ ] **Step 7: Wire `foulCounts` + appearance persistence into `FixtureSimulator`**

Modify `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`. Add the new repository dependency:

```java
    private final MatchEventRepository matchEventRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;
```

and thread it through the constructor (add the parameter right after `matchEventRepository`, assign it the same way as every other field):

```java
    public FixtureSimulator(LineupService lineupService,
                            TacticsService tacticsService,
                            TeamStrengthCalculator teamStrengthCalculator,
                            MatchSimulationService matchSimulationService,
                            MatchEventRepository matchEventRepository,
                            FixtureAppearanceRepository fixtureAppearanceRepository,
                            MatchOutcomeApplier matchOutcomeApplier,
                            PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            FixtureRepository fixtureRepository,
                            FitnessCalculator fitnessCalculator) {
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.fixtureRepository = fixtureRepository;
        this.fitnessCalculator = fitnessCalculator;
    }
```

Modify `simulateFixture` — find where `saveEvents` is called:

```java
        List<AttributedEvent> attributed = saveEvents(fixture, match, homeXi, awayXi,
                homeProfile, awayProfile, homeClubId, awayClubId, seed);
```

Add, immediately after it, the appearance persistence (every starter plays all 90 — the instant path has no substitutions):

```java
        List<AttributedEvent> attributed = saveEvents(fixture, match, homeXi, awayXi,
                homeProfile, awayProfile, homeClubId, awayClubId, seed);
        persistAppearances(fixture, homeXi, awayXi, homeClubId, awayClubId, seed);
```

Add the new method right after `saveEvents`:

```java
    /** Every starter plays all 90 minutes — the instant path has no substitutions. */
    private void persistAppearances(Fixture fixture, List<Player> homeXi, List<Player> awayXi,
                                    UUID homeClubId, UUID awayClubId, long seed) {
        Map<UUID, Integer> fouls = foulCounts(homeXi, awayXi, seed);
        for (Player p : homeXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), homeClubId, 90, fouls.getOrDefault(p.getId(), 0)));
        }
        for (Player p : awayXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), awayClubId, 90, fouls.getOrDefault(p.getId(), 0)));
        }
    }
```

- [ ] **Step 8: Run the new test, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: `BUILD SUCCESSFUL` (aside from any pre-existing known-unrelated flakes — check any failure's class name against this task's touched files before treating it as new).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java \
  backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorAssistFoulIT.java
git commit -m "feat(stats): persist fixture_appearance and assist rows from both simulation paths"
```

---

### Task 6: `PlayerStatsService` (live aggregation) + GET endpoint

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/stats/PlayerStatsService.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/stats/PlayerStatsController.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/stats/SeasonStatsLine.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/stats/PlayerStatsResponse.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/stats/PlayerStatsServiceIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/stats/PlayerStatsControllerIT.java`

**Interfaces:**
- Consumes: `FixtureAppearanceRepository`, `MatchEventRepository.findByFixtureIdIn` (Task 1), `FixtureRepository.findBySaveGameIdAndCompetitionId`, `CompetitionRepository.findBySaveGameId`, `SeasonPlayerStatsRepository`, `SeasonRepository`, `SaveGameService.requireOwned`.
- Produces: `PlayerStatsService.getStats(UUID saveGameId, UUID playerId, UUID userId) -> PlayerStatsResponse`; package-private `aggregateCompetition(UUID saveGameId, UUID competitionId) -> Map<UUID, CompetitionPlayerAggregate>` (reused by Task 7's rollover archival — the shared aggregation math lives here, not duplicated).

- [ ] **Step 1: Write `SeasonStatsLine.java`**

```java
package br.com.carvoeiromanager.stats;

public record SeasonStatsLine(
        int seasonYear,
        String competitionName,
        int matchesPlayed,
        int minutesPlayed,
        int goals,
        int assists,
        int fouls,
        int yellowCards,
        int redCards) {
}
```

- [ ] **Step 2: Write `PlayerStatsResponse.java`**

```java
package br.com.carvoeiromanager.stats;

import java.util.List;

public record PlayerStatsResponse(List<SeasonStatsLine> seasons, SeasonStatsLine totals) {
}
```

- [ ] **Step 3: Write the failing service IT**

```java
package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.match.FixtureAppearance;
import br.com.carvoeiromanager.match.FixtureAppearanceRepository;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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
class PlayerStatsServiceIT {

    @Autowired PlayerStatsService playerStatsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;
    @Autowired MatchEventRepository matchEventRepository;

    @Test
    void currentSeasonStatsAggregateAppearancesAndEventsForOnePlayer() {
        User user = User.create("stats_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();

        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId());
        Fixture fixture = fixtures.get(0);
        Club club = clubRepository.findById(fixture.getHomeClubId()).orElseThrow();
        Player player = playerRepository.findByClubId(club.getId()).get(0);

        fixtureAppearanceRepository.save(
                FixtureAppearance.create(fixture.getId(), player.getId(), club.getId(), 90, 4));
        matchEventRepository.save(
                MatchEvent.create(fixture.getId(), 30, "GOAL", player.getId(), club.getId()));
        matchEventRepository.save(
                MatchEvent.create(fixture.getId(), 55, "YELLOW_CARD", player.getId(), club.getId()));

        PlayerStatsResponse response = playerStatsService.getStats(saveGameId, player.getId(), user.getId());

        assertThat(response.seasons()).isNotEmpty();
        SeasonStatsLine line = response.seasons().stream()
                .filter(l -> l.competitionName().equals(competition.getName()))
                .findFirst().orElseThrow();
        assertThat(line.matchesPlayed()).isEqualTo(1);
        assertThat(line.minutesPlayed()).isEqualTo(90);
        assertThat(line.goals()).isEqualTo(1);
        assertThat(line.fouls()).isEqualTo(4);
        assertThat(line.yellowCards()).isEqualTo(1);

        assertThat(response.totals().goals()).isEqualTo(1);
        assertThat(response.totals().minutesPlayed()).isEqualTo(90);
    }

    @Test
    void aPlayerWithNoAppearancesGetsAnEmptySeasonListAndZeroTotals() {
        User user = User.create("stats_empty_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).get(0);
        Player player = playerRepository.findByClubId(fixture.getHomeClubId()).get(0);

        PlayerStatsResponse response = playerStatsService.getStats(saveGameId, player.getId(), user.getId());

        assertThat(response.seasons()).isEmpty();
        assertThat(response.totals().matchesPlayed()).isZero();
        assertThat(response.totals().goals()).isZero();
    }
}
```

- [ ] **Step 4: Run it, confirm FAIL** (`PlayerStatsService` doesn't exist yet — compile error)

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.stats.PlayerStatsServiceIT'`
Expected: compile FAIL.

- [ ] **Step 5: Implement `PlayerStatsService.java`**

```java
package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.match.EventType;
import br.com.carvoeiromanager.match.FixtureAppearance;
import br.com.carvoeiromanager.match.FixtureAppearanceRepository;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.season.Season;
import br.com.carvoeiromanager.season.SeasonPlayerStats;
import br.com.carvoeiromanager.season.SeasonPlayerStatsRepository;
import br.com.carvoeiromanager.season.SeasonRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregates player match statistics: live (current in-progress season, computed on demand from
 * fixture_appearance + match_events) and archived (past seasons, read from season_player_stats).
 * {@link #aggregateCompetition} is the shared per-competition math reused by the live path here
 * AND by {@code SeasonRolloverService}'s archival step, so the two never compute stats differently.
 */
@Service
public class PlayerStatsService {

    private final SaveGameService saveGameService;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchEventRepository matchEventRepository;
    private final SeasonPlayerStatsRepository seasonPlayerStatsRepository;
    private final SeasonRepository seasonRepository;

    public PlayerStatsService(SaveGameService saveGameService, CompetitionRepository competitionRepository,
                              FixtureRepository fixtureRepository,
                              FixtureAppearanceRepository fixtureAppearanceRepository,
                              MatchEventRepository matchEventRepository,
                              SeasonPlayerStatsRepository seasonPlayerStatsRepository,
                              SeasonRepository seasonRepository) {
        this.saveGameService = saveGameService;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchEventRepository = matchEventRepository;
        this.seasonPlayerStatsRepository = seasonPlayerStatsRepository;
        this.seasonRepository = seasonRepository;
    }

    /** One player's aggregate line within one competition's currently-live (unarchived) data. */
    public record CompetitionPlayerAggregate(UUID playerId, UUID clubId, int matchesPlayed, int minutesPlayed,
                                              int goals, int assists, int fouls, int yellowCards, int redCards) {
    }

    public PlayerStatsResponse getStats(UUID saveGameId, UUID playerId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);

        List<SeasonStatsLine> current = currentSeasonLines(saveGameId, playerId);
        List<SeasonStatsLine> archived = seasonPlayerStatsRepository.findByPlayerId(playerId).stream()
                .map(this::toLine)
                .toList();

        List<SeasonStatsLine> seasons = new ArrayList<>();
        seasons.addAll(current);
        seasons.addAll(archived);

        SeasonStatsLine totals = seasons.stream().reduce(
                new SeasonStatsLine(0, "Total", 0, 0, 0, 0, 0, 0, 0),
                (a, b) -> new SeasonStatsLine(0, "Total",
                        a.matchesPlayed() + b.matchesPlayed(), a.minutesPlayed() + b.minutesPlayed(),
                        a.goals() + b.goals(), a.assists() + b.assists(), a.fouls() + b.fouls(),
                        a.yellowCards() + b.yellowCards(), a.redCards() + b.redCards()));

        return new PlayerStatsResponse(List.copyOf(seasons), totals);
    }

    private List<SeasonStatsLine> currentSeasonLines(UUID saveGameId, UUID playerId) {
        List<SeasonStatsLine> lines = new ArrayList<>();
        for (Competition competition : competitionRepository.findBySaveGameId(saveGameId)) {
            CompetitionPlayerAggregate agg = aggregateCompetition(saveGameId, competition.getId()).get(playerId);
            if (agg == null) continue;
            lines.add(new SeasonStatsLine(competition.getSeasonYear(), competition.getName(),
                    agg.matchesPlayed(), agg.minutesPlayed(), agg.goals(), agg.assists(), agg.fouls(),
                    agg.yellowCards(), agg.redCards()));
        }
        return lines;
    }

    private SeasonStatsLine toLine(SeasonPlayerStats row) {
        Season season = seasonRepository.findById(row.getSeasonId()).orElseThrow();
        return new SeasonStatsLine(season.getSeasonYear(), season.getCompetitionName(),
                row.getMatchesPlayed(), row.getMinutesPlayed(), row.getGoals(), row.getAssists(),
                row.getFouls(), row.getYellowCards(), row.getRedCards());
    }

    /**
     * Aggregates EVERY player's stat line for one competition from currently-live season-scoped
     * data. Package-private: called from here for a single player's live display, and from
     * {@code SeasonRolloverService} for every player at archival time — the only place this math
     * is written.
     */
    Map<UUID, CompetitionPlayerAggregate> aggregateCompetition(UUID saveGameId, UUID competitionId) {
        List<UUID> fixtureIds = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId)
                .stream().map(Fixture::getId).toList();
        if (fixtureIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<FixtureAppearance>> appearancesByPlayer = fixtureAppearanceRepository
                .findByFixtureIdIn(fixtureIds).stream()
                .collect(Collectors.groupingBy(FixtureAppearance::getPlayerId));
        Map<UUID, List<MatchEvent>> eventsByPlayer = matchEventRepository
                .findByFixtureIdIn(fixtureIds).stream()
                .collect(Collectors.groupingBy(MatchEvent::getPlayerId));

        Set<UUID> playerIds = new HashSet<>();
        playerIds.addAll(appearancesByPlayer.keySet());
        playerIds.addAll(eventsByPlayer.keySet());

        Map<UUID, CompetitionPlayerAggregate> out = new HashMap<>();
        for (UUID playerId : playerIds) {
            List<FixtureAppearance> apps = appearancesByPlayer.getOrDefault(playerId, List.of());
            List<MatchEvent> events = eventsByPlayer.getOrDefault(playerId, List.of());

            int matches = apps.size();
            int minutes = apps.stream().mapToInt(FixtureAppearance::getMinutesPlayed).sum();
            int fouls = apps.stream().mapToInt(FixtureAppearance::getFouls).sum();
            UUID clubId = apps.isEmpty() ? (events.isEmpty() ? null : events.get(0).getClubId())
                    : apps.get(0).getClubId();

            int goals = (int) events.stream()
                    .filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
            int assists = (int) events.stream().filter(e -> "ASSIST".equals(e.getType())).count();
            int yellows = (int) events.stream().filter(e -> "YELLOW_CARD".equals(e.getType())).count();
            int reds = (int) events.stream().filter(e -> "RED_CARD".equals(e.getType())).count();

            out.put(playerId, new CompetitionPlayerAggregate(playerId, clubId, matches, minutes,
                    goals, assists, fouls, yellows, reds));
        }
        return out;
    }
}
```

- [ ] **Step 6: Run the service IT, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.stats.PlayerStatsServiceIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Write the failing controller IT**

```java
package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.security.JwtService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PlayerStatsControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired JwtService jwtService;

    @Test
    void returnsStatsForAnOwnedSave() throws Exception {
        User user = User.create("stats_ctrl_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        String token = jwtService.generateToken(user.getId(), user.getUsername());

        SaveGameResponse save = saveGameService.createCareer(user.getId());
        Competition competition = competitionRepository.findBySaveGameId(save.id()).get(0);
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionId(save.id(), competition.getId()).get(0);
        Player player = playerRepository.findByClubId(fixture.getHomeClubId()).get(0);

        mockMvc.perform(get("/api/v1/save-games/{saveGameId}/players/{playerId}/stats",
                        save.id(), player.getId())
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons").isArray())
                .andExpect(jsonPath("$.totals.goals").value(0));
    }
}
```

Note for the implementer: search the existing test sources for how other controller ITs mint a bearer token (`JwtService`'s exact method name may differ — e.g. check `LiveMatchControllerIT.java` or `TacticsApiIT.java` for the established pattern and mirror it exactly rather than trusting the method name above verbatim).

- [ ] **Step 8: Run it, confirm FAIL** (no controller/route exists yet — 404)

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.stats.PlayerStatsControllerIT'`
Expected: FAIL (404 Not Found, or compile error if the token-minting call needs adjusting per the note above).

- [ ] **Step 9: Implement `PlayerStatsController.java`**

```java
package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/players/{playerId}")
public class PlayerStatsController {

    private final PlayerStatsService playerStatsService;

    public PlayerStatsController(PlayerStatsService playerStatsService) {
        this.playerStatsService = playerStatsService;
    }

    /** Season-by-season stats (matches, minutes, goals, assists, fouls, cards) for any player
     * in the save — the current in-progress season plus every archived past season. */
    @GetMapping("/stats")
    public ResponseEntity<PlayerStatsResponse> getStats(
            @PathVariable UUID saveGameId,
            @PathVariable UUID playerId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(playerStatsService.getStats(saveGameId, playerId, userId));
    }
}
```

- [ ] **Step 10: Run the controller IT, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.stats.PlayerStatsControllerIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/stats/ \
  backend/src/test/java/br/com/carvoeiromanager/stats/
git commit -m "feat(stats): add PlayerStatsService live aggregation and the GET stats endpoint"
```

---

### Task 7: Archive `season_player_stats` at rollover, purge `fixture_appearance`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java` (extend existing — locate it first)

**Interfaces:**
- Consumes: `PlayerStatsService`'s package-private `aggregateCompetition(...)` (Task 6) — requires `SeasonRolloverService` to live in a position to call it; since `aggregateCompetition` is package-private in `br.com.carvoeiromanager.stats` and `SeasonRolloverService` lives in `br.com.carvoeiromanager.season`, this task widens that one method's visibility to `public` (still internal/unofficial API — not exposed via any controller — but Java package-private cannot cross packages, and duplicating the aggregation math would violate this plan's DRY goal for this exact case).
- Produces: `season_player_stats` rows written before each competition's purge; `fixture_appearance` rows purged alongside `match_events` in the same FK-safe step.

- [ ] **Step 1: Widen `PlayerStatsService.aggregateCompetition` to `public`**

Modify `backend/src/main/java/br/com/carvoeiromanager/stats/PlayerStatsService.java` — change one line:

```java
    public Map<UUID, CompetitionPlayerAggregate> aggregateCompetition(UUID saveGameId, UUID competitionId) {
```

(Just the visibility modifier; the doc comment above it already explains the dual caller. Update that comment's wording from "Package-private" to "Public" to stay accurate:)

```java
    /**
     * Aggregates EVERY player's stat line for one competition from currently-live season-scoped
     * data. Public: called from here for a single player's live display, and from
     * {@code SeasonRolloverService} for every player at archival time — the only place this math
     * is written.
     */
```

- [ ] **Step 2: Write the failing rollover archival test**

`backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java` already has a private `Career` record (`userId`, `save`, with `id()`/`clubId()` accessors), a `Career createCareer()` helper, and a `void finishSeason(Career c)` helper that advances via `seasonAdvanceService.advance(c.id(), c.userId())` in a loop until the save reaches `SaveGameStatus.FINISHED`. Append this test to the existing class body, and add `@Autowired FixtureAppearanceRepository fixtureAppearanceRepository;` and `@Autowired SeasonPlayerStatsRepository seasonPlayerStatsRepository;` fields alongside its existing `@Autowired` fields, plus the import `import br.com.carvoeiromanager.match.FixtureAppearance;` and `import br.com.carvoeiromanager.match.FixtureAppearanceRepository;` (do not modify anything else in the file):

```java
    @Test
    void rolloverArchivesSeasonPlayerStatsAndPurgesFixtureAppearance() {
        Career c = createCareer();
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());

        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionId(c.id(), competition.getId()).get(0);
        Player player = playerRepository.findByClubId(fixture.getHomeClubId()).get(0);
        fixtureAppearanceRepository.save(FixtureAppearance.create(
                fixture.getId(), player.getId(), fixture.getHomeClubId(), 90, 5));

        finishSeason(c);
        seasonRolloverService.rollover(c.id(), c.userId());

        List<SeasonPlayerStats> archived = seasonPlayerStatsRepository.findByPlayerId(player.getId());
        assertThat(archived).isNotEmpty();
        assertThat(archived.get(0).getMinutesPlayed()).isGreaterThanOrEqualTo(90);

        assertThat(fixtureAppearanceRepository.findByPlayerId(player.getId())).isEmpty();
    }
```

- [ ] **Step 3: Run it, confirm FAIL**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonRolloverServiceIT'`
Expected: FAIL — `archived` is empty (rollover doesn't write `season_player_stats` yet) and/or the `fixtureAppearanceRepository` purge assertion fails (rows aren't purged yet).

- [ ] **Step 4: Wire archival + purge into `SeasonRolloverService.java`**

Add the two new dependencies to the class:

```java
    private final PlayerStatsService playerStatsService;
    private final br.com.carvoeiromanager.match.FixtureAppearanceRepository fixtureAppearanceRepository;
```

and to the constructor (add both parameters, assign them like every other field — insert right after the existing `seasonTopScorerRepository` parameter for the first, and right after `matchEventRepository` for the second):

```java
    public SeasonRolloverService(SaveGameService saveGameService,
                                 SaveGameRepository saveGameRepository,
                                 CompetitionRepository competitionRepository,
                                 FixtureRepository fixtureRepository,
                                 MatchEventRepository matchEventRepository,
                                 br.com.carvoeiromanager.match.FixtureAppearanceRepository fixtureAppearanceRepository,
                                 ClubRepository clubRepository,
                                 PlayerRepository playerRepository,
                                 LineupRepository lineupRepository,
                                 LineupPlayerRepository lineupPlayerRepository,
                                 StandingsService standingsService,
                                 TopScorersService topScorersService,
                                 SeasonRepository seasonRepository,
                                 SeasonStandingRepository seasonStandingRepository,
                                 SeasonTopScorerRepository seasonTopScorerRepository,
                                 br.com.carvoeiromanager.stats.PlayerStatsService playerStatsService,
                                 PlayerAgingService playerAgingService,
                                 NewgenGenerator newgenGenerator,
                                 FreeAgentGenerator freeAgentGenerator,
                                 FinanceTransactionRepository financeTransactionRepository,
                                 TransferOfferRepository transferOfferRepository,
                                 SeasonCalendarGenerator seasonCalendarGenerator,
                                 SeasonEventRepository seasonEventRepository,
                                 NewsItemRepository newsItemRepository,
                                 YouthDevelopmentService youthDevelopmentService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.standingsService = standingsService;
        this.topScorersService = topScorersService;
        this.seasonRepository = seasonRepository;
        this.seasonStandingRepository = seasonStandingRepository;
        this.seasonTopScorerRepository = seasonTopScorerRepository;
        this.playerStatsService = playerStatsService;
        this.playerAgingService = playerAgingService;
        this.newgenGenerator = newgenGenerator;
        this.freeAgentGenerator = freeAgentGenerator;
        this.financeTransactionRepository = financeTransactionRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.seasonEventRepository = seasonEventRepository;
        this.newsItemRepository = newsItemRepository;
        this.youthDevelopmentService = youthDevelopmentService;
    }
```

Now modify `rollover(...)`'s purge loop. Find:

```java
            if (!fixtureIds.isEmpty()) {
                matchEventRepository.deleteByFixtureIdIn(fixtureIds);
            }
```

Replace with:

```java
            if (!fixtureIds.isEmpty()) {
                matchEventRepository.deleteByFixtureIdIn(fixtureIds);
                fixtureAppearanceRepository.deleteByFixtureIdIn(fixtureIds);
            }
```

Now modify the `archive(...)` method to also write `season_player_stats`. Find the end of the existing method (right after the top-scorer loop):

```java
        int rank = 1;
        for (TopScorerRow scorer : scorers) {
            seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), rank++,
                    scorer.playerId(), scorer.playerName(), scorer.clubId(), scorer.clubName(),
                    scorer.goals()));
        }
    }
```

Replace with (adds player-stats archival right after top scorers, using the same `season.getId()` and reusing `playerRepository`/`clubNameById` already available in scope for names — a fresh lookup for player names since `TopScorerRow` doesn't carry every player, only scorers):

```java
        int rank = 1;
        for (TopScorerRow scorer : scorers) {
            seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), rank++,
                    scorer.playerId(), scorer.playerName(), scorer.clubId(), scorer.clubName(),
                    scorer.goals()));
        }

        Map<UUID, br.com.carvoeiromanager.player.Player> playersById = playerRepository
                .findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(br.com.carvoeiromanager.player.Player::getId, Function.identity()));
        for (Map.Entry<UUID, br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate> entry
                : playerStatsService.aggregateCompetition(saveGameId, competition.getId()).entrySet()) {
            br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate agg = entry.getValue();
            br.com.carvoeiromanager.player.Player player = playersById.get(entry.getKey());
            String playerName = player != null ? player.getName() : "";
            String clubName = agg.clubId() != null ? clubNameById.getOrDefault(agg.clubId(), "") : "";
            seasonPlayerStatsRepository.save(SeasonPlayerStats.create(season.getId(), entry.getKey(), playerName,
                    agg.clubId(), clubName, agg.matchesPlayed(), agg.minutesPlayed(), agg.goals(),
                    agg.assists(), agg.fouls(), agg.yellowCards(), agg.redCards()));
        }
    }
```

Add the new repository field and constructor wiring for `seasonPlayerStatsRepository` (mirrors `seasonTopScorerRepository` exactly — add the field declaration, constructor parameter, and assignment alongside it):

```java
    private final SeasonPlayerStatsRepository seasonPlayerStatsRepository;
```

and in the constructor parameter list, right after `seasonTopScorerRepository`:

```java
                                 SeasonTopScorerRepository seasonTopScorerRepository,
                                 SeasonPlayerStatsRepository seasonPlayerStatsRepository,
```

and its assignment right after `this.seasonTopScorerRepository = seasonTopScorerRepository;`:

```java
        this.seasonPlayerStatsRepository = seasonPlayerStatsRepository;
```

(Both `SeasonPlayerStats` and `SeasonPlayerStatsRepository` already live in the `br.com.carvoeiromanager.season` package alongside `SeasonRolloverService` — no new import needed for them, matching how `SeasonTopScorer`/`SeasonTopScorerRepository` need none today.)

- [ ] **Step 5: Run the rollover IT, confirm PASS**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.SeasonRolloverServiceIT'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
  backend/src/main/java/br/com/carvoeiromanager/stats/PlayerStatsService.java \
  backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java
git commit -m "feat(stats): archive season_player_stats and purge fixture_appearance at rollover"
```

---

### Task 8: Frontend — "Estatísticas" tab on `PlayerDetails.tsx`

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/playerStats.ts`
- Modify: `frontend/src/features/squad/PlayerDetails.tsx`
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Modify: `frontend/src/features/club/ClubViewPage.tsx`
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx`
- Test: `frontend/src/features/squad/PlayerDetails.test.tsx` (new)

**Interfaces:**
- Consumes: backend `GET /save-games/{saveGameId}/players/{playerId}/stats` (Task 6).
- Produces: `PlayerDetails` gains a required `saveGameId: string` prop (all three call sites already have it in scope).

- [ ] **Step 1: Add the response types to `types.ts`**

Modify `frontend/src/api/types.ts` — add near the other player-related types (e.g. right after `SquadResponse`):

```ts
export type SeasonStatsLine = {
  seasonYear: number
  competitionName: string
  matchesPlayed: number
  minutesPlayed: number
  goals: number
  assists: number
  fouls: number
  yellowCards: number
  redCards: number
}

export type PlayerStatsResponse = {
  seasons: SeasonStatsLine[]
  totals: SeasonStatsLine
}
```

- [ ] **Step 2: Write `frontend/src/api/playerStats.ts`**

```ts
import { apiGet } from './client'
import type { PlayerStatsResponse } from './types'

export function getPlayerStats(
  saveGameId: string,
  playerId: string,
  token: string,
): Promise<PlayerStatsResponse> {
  return apiGet<PlayerStatsResponse>(`/save-games/${saveGameId}/players/${playerId}/stats`, token)
}
```

- [ ] **Step 3: Write the failing frontend test**

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '../../auth/AuthContext'
import { PlayerDetails } from './PlayerDetails'
import type { PlayerResponse } from '../../api/types'

vi.mock('../../api/playerStats', () => ({ getPlayerStats: vi.fn() }))
import { getPlayerStats } from '../../api/playerStats'

const PLAYER: PlayerResponse = {
  id: 'p1', name: 'Jogador Um', position: 'ATT', age: 24, overall: 70,
  pace: 70, finishing: 70, passing: 60, tackling: 40, goalkeeping: 10, stamina: 70,
  form: 0, morale: 70, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0,
  eligible: true, role: null, contractYears: 2, value: 1000000, wageDemand: 5000,
  fitness: 100, injurySeverity: 'NONE', attributes: [], transferListed: false, listedReason: null,
}

function renderDetails() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <AuthProvider>
      <QueryClientProvider client={qc}>
        <PlayerDetails player={PLAYER} onClose={() => {}} saveGameId="sg-1" />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

describe('PlayerDetails — Estatísticas tab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('does not fetch stats until the Estatísticas tab is opened', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [{ seasonYear: 2026, competitionName: 'Catarinense', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 }],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 },
    })
    renderDetails()
    expect(getPlayerStats).not.toHaveBeenCalled()

    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))
    await waitFor(() => expect(getPlayerStats).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('renders a season row and the career totals row', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [{ seasonYear: 2026, competitionName: 'Catarinense', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 }],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 },
    })
    renderDetails()
    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))

    expect(await screen.findByText('Catarinense')).toBeInTheDocument()
    expect(screen.getByText('2026')).toBeInTheDocument()
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)   // goals appears in season + totals row
    expect(screen.getByText(/total/i)).toBeInTheDocument()
  })

  it('shows an empty-state message when the player has no recorded seasons', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 0, minutesPlayed: 0,
        goals: 0, assists: 0, fouls: 0, yellowCards: 0, redCards: 0 },
    })
    renderDetails()
    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))
    expect(await screen.findByText(/nenhuma partida registrada/i)).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: Run it, confirm FAIL**

Run: `cd frontend && npx vitest run src/features/squad/PlayerDetails.test.tsx`
Expected: FAIL — no "Estatísticas" tab exists yet, `PlayerDetails` doesn't accept `saveGameId`.

- [ ] **Step 5: Add the third tab to `PlayerDetails.tsx`**

Modify `frontend/src/features/squad/PlayerDetails.tsx`. Add imports:

```ts
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { getPlayerStats } from '../../api/playerStats'
```

Change the component signature to accept `saveGameId`:

```tsx
export function PlayerDetails({ player, onClose, saveGameId, lineupControl, contractAction, listingAction }: {
  player: PlayerResponse | null
  onClose: () => void
  saveGameId: string
  lineupControl?: ReactNode
  contractAction?: ReactNode
  listingAction?: ReactNode
}) {
  const [tab, setTab] = useState(0)
  const { token } = useAuth()
  const contractEnding = (player?.contractYears ?? 99) <= 1

  // Reset to the Atributos tab whenever a different player opens.
  useEffect(() => { setTab(0) }, [player?.id])

  const attributes = player?.attributes ?? []

  const statsQuery = useQuery({
    queryKey: ['playerStats', saveGameId, player?.id],
    queryFn: () => getPlayerStats(saveGameId, player!.id, token ?? ''),
    enabled: tab === 2 && Boolean(saveGameId && player?.id && token),
  })
```

Add the third `<Tab>` inside the existing `<Tabs>`:

```tsx
            <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="fullWidth" sx={{ mb: 2, minHeight: 40 }}>
              <Tab label="Atributos" sx={{ minHeight: 40 }} />
              <Tab
                sx={{ minHeight: 40 }}
                label={
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                    <span>Contrato</span>
                    {contractEnding && (
                      <Box
                        title="Contrato terminando"
                        sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'error.main' }}
                      />
                    )}
                  </Stack>
                }
              />
              <Tab label="Estatísticas" sx={{ minHeight: 40 }} />
            </Tabs>
```

Add the third tab panel right after the existing `{tab === 1 && (...)}` block, before the closing `</DialogContent>`:

```tsx
            {tab === 2 && (
              <Box>
                {statsQuery.isLoading && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>Carregando…</Typography>
                )}
                {statsQuery.data && statsQuery.data.seasons.length === 0 && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Nenhuma partida registrada ainda.
                  </Typography>
                )}
                {statsQuery.data && statsQuery.data.seasons.length > 0 && (
                  <Box sx={{ overflowX: 'auto' }}>
                    <Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
                      <Box component="thead">
                        <Box component="tr">
                          {['Temporada', 'Competição', 'J', 'Min', 'G', 'A', 'F', 'CA', 'CV'].map(h => (
                            <Box component="th" key={h} sx={{ textAlign: 'left', py: 0.5, borderBottom: '2px solid', borderColor: 'divider', color: 'text.secondary', fontWeight: 700 }}>
                              {h}
                            </Box>
                          ))}
                        </Box>
                      </Box>
                      <Box component="tbody">
                        {statsQuery.data.seasons.map((s, i) => (
                          <Box component="tr" key={`${s.seasonYear}-${s.competitionName}-${i}`}>
                            <Box component="td" sx={{ py: 0.5 }}>{s.seasonYear}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.competitionName}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.matchesPlayed}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.minutesPlayed}</Box>
                            <Box component="td" sx={{ py: 0.5, fontWeight: 700 }}>{s.goals}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.assists}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.fouls}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.yellowCards}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.redCards}</Box>
                          </Box>
                        ))}
                        <Box component="tr" sx={{ borderTop: '2px solid', borderColor: 'divider' }}>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }} colSpan={2}>Total</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.matchesPlayed}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.minutesPlayed}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.goals}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.assists}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.fouls}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.yellowCards}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.redCards}</Box>
                        </Box>
                      </Box>
                    </Box>
                  </Box>
                )}
              </Box>
            )}
```

- [ ] **Step 6: Update the three call sites to pass `saveGameId`**

Modify `frontend/src/features/squad/SquadPage.tsx` — find the existing `<PlayerDetails` usage and add the prop:

```tsx
      <PlayerDetails
        player={detailsPlayer}
        onClose={() => setDetailsPlayer(null)}
        saveGameId={saveGameId}
        lineupControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id)
          ? roleSelect(detailsPlayer)
          : undefined}
        contractAction={detailsPlayer ? renewButton(detailsPlayer) : undefined}
        listingAction={detailsPlayer ? listingButton(detailsPlayer) : undefined}
      />
```

Modify `frontend/src/features/club/ClubViewPage.tsx`:

```tsx
      <PlayerDetails player={detailsPlayer} onClose={() => setDetailsPlayer(null)} saveGameId={saveGameId} />
```

Modify `frontend/src/features/transfer/TransferMarketPage.tsx`:

```tsx
      <PlayerDetails player={detailsPlayer} onClose={() => setDetailsPlayer(null)} saveGameId={saveGameId} />
```

- [ ] **Step 7: Run the new test file, confirm PASS**

Run: `cd frontend && npx vitest run src/features/squad/PlayerDetails.test.tsx`
Expected: `Test Files 1 passed`, all 3 tests green.

- [ ] **Step 8: Run the full frontend suite + typecheck**

Run: `cd frontend && npx tsc --noEmit && npx vitest run`
Expected: clean typecheck, all test files passing (the three modified pages' own existing test files must still pass with the new required `saveGameId` prop supplied).

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/playerStats.ts \
  frontend/src/features/squad/PlayerDetails.tsx \
  frontend/src/features/squad/PlayerDetails.test.tsx \
  frontend/src/features/squad/SquadPage.tsx \
  frontend/src/features/club/ClubViewPage.tsx \
  frontend/src/features/transfer/TransferMarketPage.tsx
git commit -m "feat(stats): add the Estatísticas tab to the player details drawer"
```

---

## Final Verification

- [ ] Run the full backend suite: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` — expect `BUILD SUCCESSFUL`.
- [ ] Run the full frontend suite: `cd frontend && npx tsc --noEmit && npx vitest run` — expect clean typecheck and all tests green.
- [ ] Manually verify in the browser: start a career, play/advance a live match to full time, open a scored player's card in the Atuações or squad view, confirm an assisted-goal commentary line appeared at least once across a few matches, open "Estatísticas" on a few different players (managed squad, an AI club via ClubViewPage, a transfer-market listing) and confirm season rows + totals render.
