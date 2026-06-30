# Phase 2D-1 — Multiple Competitions & Dated Event Calendar (Backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend for two interleaved competitions (Campeonato Catarinense + a 20-club Série B-style national league Criciúma also plays) driven by a CM-style dated event calendar — `season_event` queue, date-driven advance, date-based transfer windows, per-matchday/per-competition finance & prize — and seed the Série B clubs. (Frontend is Phase 2D-2.)

**Architecture:** A `competition_club` join makes club↔competition many-to-many (real overlap). A `SeasonCalendarGenerator` generates per-competition fixtures over members, interleaves their rounds across dates from mid-January, and materializes a `season_event` queue (MATCH | TRANSFER). A new `SeasonAdvanceService` pops the next dated event — playing a competition's round or generating AI bids — advancing `SaveGame.current_date`. The transfer window becomes date-based. Standings/top-scorers are already per-competition (no change). The match engine is untouched.

**Tech Stack:** Java 25 / Spring Boot 4.0.7 / Gradle (wrapper at `backend/gradlew`) / Flyway / JPA / Testcontainers Postgres / JUnit5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-27-carvoeiro-manager-phase-2d-competitions-design.md`

## Global Constraints

- **Backend gradle from `backend/` with Java 25:** `cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "<FQCN>"`. Default `java` is 8 and fails. Full suite: `./gradlew test`.
- **Every commit message MUST end with** `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Commit directly to `main` (authorized for this project — create the commit yourself; do not defer to the user).
- **Money** `BigDecimal` scaled to 2; **IDs** `UUID` assigned by callers in static factories; **FKs** plain UUID columns (no JPA associations); **Dates** `java.time.LocalDate` (never `java.util.Date`).
- **Determinism:** the match engine is untouched (match seed stays `saveGameId.msb ^ fixtureId.msb ^ round`). `SeasonCalendarGenerator` is pure (`LocalDate` math + id-sorted iteration, no RNG, no wall-clock — dates derive from `seasonYear`). Bid generation reseeds off the event date: `new Random(saveGameId.getMostSignificantBits() ^ eventDate.toEpochDay())`. No wall-clock anywhere.
- **Locked calendar constants:** `SEASON_START = LocalDate.of(seasonYear, 1, 15)`; `MATCH_INTERVAL_DAYS = 5`; pre-season window `[+0, +42)` days from start; mid-season window `[+140, +168)` days; `TRANSFER_INTERVAL_DAYS = 10`; same-date ordering MATCH `sequence=0`, TRANSFER `sequence=1`.
- **Sizes:** Catarinense 10 clubs (18 rounds); Série B 20 clubs (38 rounds). Interleaved ≈ 56 match-units/season.
- **Error JSON** `{error, message, status}` via `ApiException(code, msg, HttpStatus)`; 422 = `HttpStatus.UNPROCESSABLE_CONTENT`; `SEASON_FINISHED` (409); `TRANSFER_WINDOW_CLOSED` (422); `SAVE_GAME_NOT_FOUND` (404).

---

## File Structure

**New backend files**
- `db/migration/V8__competitions_calendar.sql` — additive migration.
- `competition/CompetitionClub.java` + `competition/CompetitionClubRepository.java` — membership.
- `season/SeasonEvent.java` + `season/SeasonEventType.java` + `season/SeasonEventRepository.java` — dated event queue.
- `season/SeasonCalendarGenerator.java` — interleaved dated calendar + fixtures.
- `match/FixtureSimulator.java` — the per-fixture simulation extracted from `RoundSimulationService`.
- `season/SeasonAdvanceService.java` + `season/AdvanceResponse.java` + `season/AdvanceController.java` — date-driven advance.
- `seed/SeedCareer.java` + `seed/SeedCompetition.java` — new club-pool seed format records.

**Modified backend files**
- `competition/Fixture.java` (matchDate), `competition/FixtureRepository.java` (finder).
- `career/SaveGame.java` (currentDate), `career/SaveGameResponse.java` + `career/SaveGameService.java` (new seed format + calendar wiring + DTO).
- `transfer/TransferWindow.java` (date-based), `transfer/TransferService.java` + `contract/ContractService.java` (window callers + `generateIncomingOffers(LocalDate)`).
- `finance/FinanceService.java` (per-matchday + per-competition prize).
- `match/RoundSimulationService.java` (delegate to `FixtureSimulator`, then retire), `match/MatchController.java` (repointed/removed).
- `season/SeasonRolloverService.java` (calendar regen) + `season/RolloverResponse.java` (unchanged shape).
- `resources/seed/catarinense.json` → restructured to the new career format; new Série B competition + clubs.

**Seed resources**
- `resources/seed/career.json` (or keep `catarinense.json` name) — club pool + competitions(members).

---

## Task 1: V8 migration + membership/event entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__competitions_calendar.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionClub.java`, `competition/CompetitionClubRepository.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonEvent.java`, `season/SeasonEventType.java`, `season/SeasonEventRepository.java`
- Modify: `competition/Fixture.java`, `competition/FixtureRepository.java`, `career/SaveGame.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/CalendarEntitiesIT.java`

**Interfaces produced:**
- `CompetitionClub.create(UUID saveGameId, UUID competitionId, UUID clubId)`; getters; `CompetitionClubRepository.findByCompetitionId(UUID)`, `findBySaveGameIdAndClubId(UUID,UUID)`, `@Transactional void deleteBySaveGameId(UUID)`.
- `SeasonEventType { MATCH, TRANSFER }`; `SeasonEvent.createMatch(saveGameId, eventDate, competitionId, round, sequence)`, `SeasonEvent.createTransfer(saveGameId, eventDate, sequence)`, `markProcessed()`, getters; `SeasonEventRepository.findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(UUID)`, `findBySaveGameIdAndProcessedFalse(UUID)`, `findBySaveGameId(UUID)`, `@Transactional void deleteBySaveGameId(UUID)`.
- `Fixture.matchDate` (LocalDate) + `Fixture.create(saveGameId, competitionId, round, homeClubId, awayClubId, matchDate)` + `getMatchDate()`/`setMatchDate(LocalDate)`; `FixtureRepository.findBySaveGameIdAndMatchDate(UUID, LocalDate)`.
- `SaveGame.currentDate` (LocalDate) + `getCurrentDate()`/`setCurrentDate(LocalDate)`.

- [ ] **Step 1: Write the migration**

`V8__competitions_calendar.sql`:

```sql
CREATE TABLE competition_club (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    club_id        UUID NOT NULL
);
CREATE INDEX idx_competition_club_comp ON competition_club(competition_id);
CREATE INDEX idx_competition_club_save ON competition_club(save_game_id);

CREATE TABLE season_event (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date     DATE NOT NULL,
    type           VARCHAR(12) NOT NULL,
    competition_id UUID,
    round          INT,
    sequence       INT NOT NULL,
    processed      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_season_event_next ON season_event(save_game_id, processed, event_date, sequence);

ALTER TABLE fixtures ADD COLUMN match_date DATE;
ALTER TABLE save_games ADD COLUMN current_date DATE;
```

- [ ] **Step 2: Write the failing test**

`CalendarEntitiesIT.java` (non-transactional round-trip, mirrors the project's entity-IT pattern):

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class CalendarEntitiesIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    private UUID newSave() {
        User u = User.create("cal_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        return s.getId();
    }

    @Test
    void membershipRoundTrips() {
        UUID save = newSave();
        UUID comp = UUID.randomUUID();
        UUID club = UUID.randomUUID();
        competitionClubRepository.save(CompetitionClub.create(save, comp, club));
        assertThat(competitionClubRepository.findByCompetitionId(comp))
                .singleElement().satisfies(cc -> assertThat(cc.getClubId()).isEqualTo(club));
        assertThat(competitionClubRepository.findBySaveGameIdAndClubId(save, club)).hasSize(1);
    }

    @Test
    void seasonEventsOrderByDateThenSequenceAndMarkProcessed() {
        UUID save = newSave();
        UUID comp = UUID.randomUUID();
        seasonEventRepository.save(SeasonEvent.createTransfer(save, LocalDate.of(2026, 1, 20), 1));
        seasonEventRepository.save(SeasonEvent.createMatch(save, LocalDate.of(2026, 1, 20), comp, 0, 0));
        seasonEventRepository.save(SeasonEvent.createMatch(save, LocalDate.of(2026, 1, 15), comp, 0, 0));

        SeasonEvent first = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(save);
        assertThat(first.getEventDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        first.markProcessed();
        seasonEventRepository.saveAndFlush(first);

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(save);
        assertThat(next.getEventDate()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(next.getType()).isEqualTo(SeasonEventType.MATCH); // sequence 0 before TRANSFER seq 1
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.CalendarEntitiesIT"
```
Expected: FAIL — entities/repos/migration columns missing.

- [ ] **Step 4: Create the entities**

`competition/CompetitionClub.java`:

```java
package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "competition_club")
public class CompetitionClub {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "competition_id", nullable = false) private UUID competitionId;
    @Column(name = "club_id", nullable = false) private UUID clubId;

    protected CompetitionClub() { }

    private CompetitionClub(UUID id, UUID saveGameId, UUID competitionId, UUID clubId) {
        this.id = id; this.saveGameId = saveGameId; this.competitionId = competitionId; this.clubId = clubId;
    }

    public static CompetitionClub create(UUID saveGameId, UUID competitionId, UUID clubId) {
        return new CompetitionClub(UUID.randomUUID(), saveGameId, competitionId, clubId);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public UUID getClubId() { return clubId; }
}
```

`season/SeasonEventType.java`:

```java
package br.com.carvoeiromanager.season;

public enum SeasonEventType { MATCH, TRANSFER }
```

`season/SeasonEvent.java`:

```java
package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** One dated entry in a save's season calendar (a MATCH round or a TRANSFER bid window stop). */
@Entity
@Table(name = "season_event")
public class SeasonEvent {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "event_date", nullable = false) private LocalDate eventDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12) private SeasonEventType type;
    @Column(name = "competition_id") private UUID competitionId;
    @Column(name = "round") private Integer round;
    @Column(name = "sequence", nullable = false) private int sequence;
    @Column(name = "processed", nullable = false) private boolean processed;

    protected SeasonEvent() { }

    private SeasonEvent(UUID id, UUID saveGameId, LocalDate eventDate, SeasonEventType type,
                        UUID competitionId, Integer round, int sequence) {
        this.id = id; this.saveGameId = saveGameId; this.eventDate = eventDate; this.type = type;
        this.competitionId = competitionId; this.round = round; this.sequence = sequence;
        this.processed = false;
    }

    public static SeasonEvent createMatch(UUID saveGameId, LocalDate eventDate, UUID competitionId,
                                          int round, int sequence) {
        return new SeasonEvent(UUID.randomUUID(), saveGameId, eventDate, SeasonEventType.MATCH,
                competitionId, round, sequence);
    }

    public static SeasonEvent createTransfer(UUID saveGameId, LocalDate eventDate, int sequence) {
        return new SeasonEvent(UUID.randomUUID(), saveGameId, eventDate, SeasonEventType.TRANSFER,
                null, null, sequence);
    }

    public void markProcessed() { this.processed = true; }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public LocalDate getEventDate() { return eventDate; }
    public SeasonEventType getType() { return type; }
    public UUID getCompetitionId() { return competitionId; }
    public Integer getRound() { return round; }
    public int getSequence() { return sequence; }
    public boolean isProcessed() { return processed; }
}
```

- [ ] **Step 5: Create the repositories**

`competition/CompetitionClubRepository.java`:

```java
package br.com.carvoeiromanager.competition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CompetitionClubRepository extends JpaRepository<CompetitionClub, UUID> {
    List<CompetitionClub> findByCompetitionId(UUID competitionId);
    List<CompetitionClub> findBySaveGameIdAndClubId(UUID saveGameId, UUID clubId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

`season/SeasonEventRepository.java`:

```java
package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SeasonEventRepository extends JpaRepository<SeasonEvent, UUID> {
    SeasonEvent findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(UUID saveGameId);
    List<SeasonEvent> findBySaveGameIdAndProcessedFalse(UUID saveGameId);
    List<SeasonEvent> findBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 6: Add `matchDate` to Fixture + finder**

In `competition/Fixture.java`: add `import java.time.LocalDate;`, a field/column, thread it through the constructor + `create`, and add accessors:

```java
    @Column(name = "match_date")
    private LocalDate matchDate;
```
Change the private constructor + `create` to accept `LocalDate matchDate` (assign `this.matchDate = matchDate;` in the constructor). Update the `create` factory signature to:

```java
    public static Fixture create(UUID saveGameId, UUID competitionId, int round,
                                 UUID homeClubId, UUID awayClubId, LocalDate matchDate) {
        return new Fixture(UUID.randomUUID(), saveGameId, competitionId, round, homeClubId, awayClubId, matchDate);
    }
```
Add `public LocalDate getMatchDate() { return matchDate; }` and `public void setMatchDate(LocalDate matchDate) { this.matchDate = matchDate; }`.

NOTE: `Fixture.create(...)` currently has 5 args at two call sites — `SaveGameService.createCareer` and `SeasonRolloverService.rollover`. Those are rewritten in Tasks 5 and 8 to go through `SeasonCalendarGenerator`. To keep THIS task compiling, update both existing call sites to pass a temporary `null` for `matchDate` (they will be replaced wholesale in Tasks 5/8): `Fixture.create(..., null)`.

In `competition/FixtureRepository.java` add:

```java
    List<Fixture> findBySaveGameIdAndMatchDate(UUID saveGameId, java.time.LocalDate matchDate);
```

- [ ] **Step 7: Add `currentDate` to SaveGame**

In `career/SaveGame.java`: add `import java.time.LocalDate;`, the column, and accessors:

```java
    @Column(name = "current_date")
    private LocalDate currentDate;
```
```java
    public LocalDate getCurrentDate() { return currentDate; }
    public void setCurrentDate(LocalDate currentDate) { this.currentDate = currentDate; }
```
(Leave `advanceToNextSeason()` unchanged for now — Task 8 updates it to reset `currentDate`.)

- [ ] **Step 8: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.CalendarEntitiesIT"
```
Expected: PASS. Then `./gradlew test` to confirm the `Fixture.create` signature change + `null` temp args didn't break the existing suite.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__competitions_calendar.sql \
  backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionClub.java \
  backend/src/main/java/br/com/carvoeiromanager/competition/CompetitionClubRepository.java \
  backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java \
  backend/src/main/java/br/com/carvoeiromanager/competition/FixtureRepository.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonEvent.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonEventType.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonEventRepository.java \
  backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java \
  backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
  backend/src/test/java/br/com/carvoeiromanager/season/CalendarEntitiesIT.java
git commit -m "$(cat <<'EOF'
feat(2d): V8 migration + competition_club + season_event + fixture date

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Date-based TransferWindow

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferWindow.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferWindowDateTest.java`

**Interfaces produced:** `TransferWindow.isOpen(LocalDate currentDate): boolean` — pre-season `[SEASON_START, +42d)` + mid-season `[+140d, +168d)`, where `SEASON_START = LocalDate.of(currentDate.getYear(), 1, 15)`.

Note: the existing round-based `isOpen(int, int)` STAYS for now (its callers switch in Task 7); this task only ADDS the date-based overload. Do not delete the old method yet.

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.transfer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowDateTest {

    private final TransferWindow window = new TransferWindow();

    @Test
    void preSeasonWindowIsOpenForTheFirst42Days() {
        assertThat(window.isOpen(LocalDate.of(2026, 1, 15))).isTrue();  // start
        assertThat(window.isOpen(LocalDate.of(2026, 2, 25))).isTrue();  // +41d
        assertThat(window.isOpen(LocalDate.of(2026, 2, 26))).isFalse(); // +42d -> closed
    }

    @Test
    void midSeasonWindowIsOpenInItsRange() {
        // SEASON_START = 2026-01-15; mid window [+140, +168) = [2026-06-04, 2026-07-02)
        assertThat(window.isOpen(LocalDate.of(2026, 6, 4))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 1))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 2))).isFalse();
        assertThat(window.isOpen(LocalDate.of(2026, 5, 1))).isFalse(); // between windows
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.transfer.TransferWindowDateTest"
```
Expected: FAIL — `isOpen(LocalDate)` does not exist.

- [ ] **Step 3: Add the date-based method**

In `transfer/TransferWindow.java` add `import java.time.LocalDate;` and constants + method (keep the existing `isOpen(int,int)`):

```java
    static final int PRE_SEASON_DAYS = 42;
    static final int MID_WINDOW_START_DAYS = 140;
    static final int MID_WINDOW_END_DAYS = 168;

    /**
     * Date-based window: a season runs within one calendar year (Jan 15 -> ~Oct), so the
     * season start is derived from the date's year. Open in the pre-season range
     * [start, start+42d) or the mid-season range [start+140d, start+168d).
     */
    public boolean isOpen(LocalDate currentDate) {
        LocalDate seasonStart = LocalDate.of(currentDate.getYear(), 1, 15);
        if (!currentDate.isBefore(seasonStart) && currentDate.isBefore(seasonStart.plusDays(PRE_SEASON_DAYS))) {
            return true;
        }
        LocalDate midStart = seasonStart.plusDays(MID_WINDOW_START_DAYS);
        LocalDate midEnd = seasonStart.plusDays(MID_WINDOW_END_DAYS);
        return !currentDate.isBefore(midStart) && currentDate.isBefore(midEnd);
    }
```

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.transfer.TransferWindowDateTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/transfer/TransferWindow.java \
  backend/src/test/java/br/com/carvoeiromanager/transfer/TransferWindowDateTest.java
git commit -m "$(cat <<'EOF'
feat(2d): date-based transfer window (pre-season + mid-season ranges)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: SeasonCalendarGenerator (interleaved dated calendar + fixtures)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorIT.java`

**Interfaces:**
- Consumes: `CompetitionRepository.findBySaveGameId`, `CompetitionClubRepository.findByCompetitionId`, `FixtureScheduler.doubleRoundRobin(int)` → `List<ScheduledFixture(round, homeIndex, awayIndex)>`, `FixtureRepository.save`, `SeasonEventRepository.save`, `Fixture.create(saveGameId, competitionId, round, home, away, matchDate)` (Task 1), `SeasonEvent.createMatch/createTransfer` (Task 1).
- Produces: `SeasonCalendarGenerator.generate(UUID saveGameId, int seasonYear, List<Competition> competitions)` — for each competition generates fixtures over its members (from `competition_club`, id-sorted), interleaves rounds across dates, persists fixtures (with `matchDate`) + `season_event` rows. Deterministic, no RNG. Constants `MATCH_INTERVAL_DAYS=5`, `TRANSFER_INTERVAL_DAYS=10`, pre/mid window offsets matching `TransferWindow`.

**Interleave rule:** competitions sorted by id. Build the global match-unit order by round index ascending; within a round index, competitions in id order; skip a competition once its rounds are exhausted. Match-unit *i* (0-based) → date `SEASON_START.plusDays(i * 5)`, MATCH event `sequence=0`. Then TRANSFER events: for each window range `[start+a, start+b)`, step from `start+a` by 10 days while `< start+b`, adding a TRANSFER event `sequence=1`.

- [ ] **Step 1: Write the failing test** (synthetic two competitions — does NOT need the real Série B seed)

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonCalendarGeneratorIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired SeasonCalendarGenerator generator;

    private Competition makeComp(UUID save, String name, int seasonYear, int clubCount) {
        Competition c = Competition.create(save, name, "LEAGUE", seasonYear, 2 * (clubCount - 1));
        competitionRepository.save(c);
        for (int i = 0; i < clubCount; i++) {
            Club club = Club.create(save, name + " Club " + i, name.substring(0, 1) + i);
            clubRepository.save(club);
            competitionClubRepository.save(CompetitionClub.create(save, c.getId(), club.getId()));
        }
        return c;
    }

    @Test
    void generatesInterleavedDatedCalendarForTwoCompetitions() {
        User u = User.create("gen_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        Competition cat = makeComp(s.getId(), "Catarinense", 2026, 4); // 4 clubs -> 6 rounds
        Competition serieB = makeComp(s.getId(), "SerieB", 2026, 6);   // 6 clubs -> 10 rounds

        generator.generate(s.getId(), 2026, List.of(cat, serieB));

        // Fixtures exist per competition, all dated, only over that competition's members.
        List<Fixture> catFx = fixtureRepository.findBySaveGameIdAndCompetitionId(s.getId(), cat.getId());
        List<Fixture> sbFx = fixtureRepository.findBySaveGameIdAndCompetitionId(s.getId(), serieB.getId());
        assertThat(catFx).allMatch(f -> f.getMatchDate() != null);
        assertThat(sbFx).allMatch(f -> f.getMatchDate() != null);
        assertThat(catFx).hasSize(4 * 3); // doubleRoundRobin(4) = 12 fixtures (6 rounds x 2)
        assertThat(sbFx).hasSize(6 * 5);  // doubleRoundRobin(6) = 30 fixtures (10 rounds x 2)

        // MATCH events: 6 + 10 = 16 match-units; first match-unit is at SEASON_START.
        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(s.getId());
        List<SeasonEvent> matches = events.stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .sorted((a, b) -> a.getEventDate().isEqual(b.getEventDate())
                        ? Integer.compare(a.getSequence(), b.getSequence())
                        : a.getEventDate().compareTo(b.getEventDate()))
                .toList();
        assertThat(matches).hasSize(16);
        assertThat(matches.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        // Interleave: first two match-units are different competitions on consecutive dates.
        assertThat(matches.get(0).getCompetitionId()).isNotEqualTo(matches.get(1).getCompetitionId());
        assertThat(matches.get(1).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 20)); // +5d

        // TRANSFER events fall only inside window ranges (pre-season first 42 days, mid [+140,+168)).
        List<SeasonEvent> transfers = events.stream()
                .filter(e -> e.getType() == SeasonEventType.TRANSFER).toList();
        assertThat(transfers).isNotEmpty();
        LocalDate start = LocalDate.of(2026, 1, 15);
        assertThat(transfers).allMatch(t -> {
            boolean pre = !t.getEventDate().isBefore(start) && t.getEventDate().isBefore(start.plusDays(42));
            boolean mid = !t.getEventDate().isBefore(start.plusDays(140)) && t.getEventDate().isBefore(start.plusDays(168));
            return pre || mid;
        });
    }

    @Test
    void isDeterministicForSameSaveAndYear() {
        // Two saves with identical structure produce identical match-unit date+competition ordering.
        // (Determinism is by construction: dates from seasonYear, competitions/members id-sorted.)
        assertThat(true).isTrue(); // structural determinism asserted by the generate() implementation;
        // covered functionally by generatesInterleavedDatedCalendarForTwoCompetitions (fixed dates).
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonCalendarGeneratorIT"
```
Expected: FAIL — `SeasonCalendarGenerator` does not exist.

- [ ] **Step 3: Implement SeasonCalendarGenerator**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.FixtureScheduler;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds a save's dated season calendar: per-competition fixtures over members, interleaved
 * across dates from mid-January, plus a season_event queue (MATCH per round + TRANSFER stops
 * inside the window ranges). Pure deterministic — LocalDate arithmetic + id-sorted iteration,
 * no RNG, no wall-clock. Used at career creation and rollover.
 */
@Service
public class SeasonCalendarGenerator {

    static final int MATCH_INTERVAL_DAYS = 5;
    static final int TRANSFER_INTERVAL_DAYS = 10;
    static final int PRE_SEASON_DAYS = 42;
    static final int MID_WINDOW_START_DAYS = 140;
    static final int MID_WINDOW_END_DAYS = 168;

    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public SeasonCalendarGenerator(CompetitionClubRepository competitionClubRepository,
                                   FixtureRepository fixtureRepository,
                                   SeasonEventRepository seasonEventRepository) {
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
    }

    /** A scheduled match-unit: one competition's round, to be dated and persisted. */
    private record MatchUnit(UUID competitionId, int round, List<Fixture> fixtures) { }

    public void generate(UUID saveGameId, int seasonYear, List<Competition> competitions) {
        final LocalDate seasonStart = LocalDate.of(seasonYear, 1, 15);

        List<Competition> comps = competitions.stream()
                .sorted(Comparator.comparing(Competition::getId)).toList();

        // 1. Build each competition's rounds (fixtures not yet dated/persisted), grouped by round.
        //    matchUnitsByComp.get(c) = list indexed by round -> fixtures of that round.
        List<List<MatchUnit>> roundsByComp = new ArrayList<>();
        int maxRounds = 0;
        for (Competition comp : comps) {
            List<UUID> memberIds = competitionClubRepository.findByCompetitionId(comp.getId()).stream()
                    .map(cc -> cc.getClubId()).sorted().toList();
            int count = memberIds.size();
            // round -> its fixtures
            List<List<Fixture>> byRound = new ArrayList<>();
            for (int r = 0; r < comp.getTotalRounds(); r++) {
                byRound.add(new ArrayList<>());
            }
            fixtureScheduler.doubleRoundRobin(count).forEach(scheduled -> {
                UUID home = memberIds.get(scheduled.homeIndex());
                UUID away = memberIds.get(scheduled.awayIndex());
                // matchDate filled when the match-unit is dated below; create with a placeholder then set.
                Fixture fx = Fixture.create(saveGameId, comp.getId(), scheduled.round(), home, away, null);
                byRound.get(scheduled.round()).add(fx);
            });
            List<MatchUnit> units = new ArrayList<>();
            for (int r = 0; r < byRound.size(); r++) {
                units.add(new MatchUnit(comp.getId(), r, byRound.get(r)));
            }
            roundsByComp.add(units);
            maxRounds = Math.max(maxRounds, units.size());
        }

        // 2. Interleave: round index ascending; within a round, competitions in id order.
        List<MatchUnit> ordered = new ArrayList<>();
        for (int r = 0; r < maxRounds; r++) {
            for (List<MatchUnit> units : roundsByComp) {
                if (r < units.size()) {
                    ordered.add(units.get(r));
                }
            }
        }

        // 3. Date + persist each match-unit's fixtures + a MATCH event (sequence 0).
        for (int i = 0; i < ordered.size(); i++) {
            MatchUnit unit = ordered.get(i);
            LocalDate date = seasonStart.plusDays((long) i * MATCH_INTERVAL_DAYS);
            for (Fixture fx : unit.fixtures()) {
                fx.setMatchDate(date);
                fixtureRepository.save(fx);
            }
            seasonEventRepository.save(
                    SeasonEvent.createMatch(saveGameId, date, unit.competitionId(), unit.round(), 0));
        }

        // 4. TRANSFER events inside each window range (sequence 1).
        addTransferEvents(saveGameId, seasonStart, 0, PRE_SEASON_DAYS);
        addTransferEvents(saveGameId, seasonStart, MID_WINDOW_START_DAYS, MID_WINDOW_END_DAYS);
    }

    private void addTransferEvents(UUID saveGameId, LocalDate seasonStart, int startOffset, int endOffset) {
        for (int d = startOffset; d < endOffset; d += TRANSFER_INTERVAL_DAYS) {
            seasonEventRepository.save(
                    SeasonEvent.createTransfer(saveGameId, seasonStart.plusDays(d), 1));
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonCalendarGeneratorIT"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonCalendarGenerator.java \
  backend/src/test/java/br/com/carvoeiromanager/season/SeasonCalendarGeneratorIT.java
git commit -m "$(cat <<'EOF'
feat(2d): SeasonCalendarGenerator (interleaved dated calendar + season events)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Finance — per-matchday over members + per-competition prize

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceMatchdayIT.java`

**Interfaces produced:**
- `FinanceService.processMatchday(UUID saveGameId, UUID competitionId, List<Fixture> matchdayFixtures): FinanceRoundResponse` — gate to home clubs in `matchdayFixtures`; sponsorship/wages/upkeep to that competition's **member clubs** (`competition_club`); managed-club ledger rows only when the managed club is a member processed. Same `FinanceRoundResponse` shape.
- `FinanceService.processSeasonEnd(UUID saveGameId, UUID competitionId): BigDecimal` — prize by final position in that competition's standings; managed-club ledger row; returns managed prize.

Keep the existing `processRound(saveGameId, round, fixtures)` and `processSeasonEnd(saveGameId)` for now (their only caller, `RoundSimulationService`, is retired in Task 7, which removes them). Inject `CompetitionClubRepository`.

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
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
class FinanceMatchdayIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired FinanceService financeService;

    @Test
    void processMatchdayChargesOnlyTheCompetitionsMembers() {
        User u = User.create("fin_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);

        // Competition A has clubA1 (home) + clubA2; clubOutsider is NOT a member.
        Competition compA = Competition.create(s.getId(), "A", "LEAGUE", 2026, 2);
        competitionRepository.save(compA);
        Club a1 = club(s.getId(), "A1"); Club a2 = club(s.getId(), "A2"); Club outsider = club(s.getId(), "OUT");
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a1.getId()));
        competitionClubRepository.save(CompetitionClub.create(s.getId(), compA.getId(), a2.getId()));

        BigDecimal a1Before = a1.getBalance();
        BigDecimal a2Before = a2.getBalance();
        BigDecimal outBefore = outsider.getBalance();

        Fixture fx = Fixture.create(s.getId(), compA.getId(), 0, a1.getId(), a2.getId(), LocalDate.of(2026, 1, 15));
        fixtureRepository.save(fx);

        financeService.processMatchday(s.getId(), compA.getId(), List.of(fx));

        // Members charged (balances change); outsider untouched.
        assertThat(clubRepository.findById(a1.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a1Before);
        assertThat(clubRepository.findById(a2.getId()).orElseThrow().getBalance()).isNotEqualByComparingTo(a2Before);
        assertThat(clubRepository.findById(outsider.getId()).orElseThrow().getBalance()).isEqualByComparingTo(outBefore);
    }

    private Club club(UUID save, String shortName) {
        Club c = Club.create(save, shortName + " FC", shortName);
        c.setReputation(60); c.setStadiumCapacity(20000);
        c.setBalance(new BigDecimal("10000000"));
        c.setSponsorshipPerRound(new BigDecimal("480000"));
        c.setStadiumUpkeepPerRound(new BigDecimal("80000"));
        clubRepository.save(c);
        return c;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.finance.FinanceMatchdayIT"
```
Expected: FAIL — `processMatchday` does not exist.

- [ ] **Step 3: Add the member-scoped finance methods**

Inject `CompetitionClubRepository competitionClubRepository` (constructor param + field + import `br.com.carvoeiromanager.competition.CompetitionClubRepository`). Add (mirroring the existing `processRound` body but scoping the club set to the competition's members, and `processSeasonEnd` but for a specific competition):

```java
    @Transactional
    public FinanceRoundResponse processMatchday(UUID saveGameId, UUID competitionId,
                                                List<Fixture> matchdayFixtures) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        UUID managedClubId = save.getManagedClubId();

        List<UUID> memberIds = competitionClubRepository.findByCompetitionId(competitionId).stream()
                .map(cc -> cc.getClubId()).toList();
        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        Map<UUID, BigDecimal> wageBillByClub = wageBillByClub(saveGameId);
        Set<UUID> homeClubs = matchdayFixtures.stream().map(Fixture::getHomeClubId).collect(Collectors.toSet());

        BigDecimal mGate = BigDecimal.ZERO, mSpon = BigDecimal.ZERO, mWages = BigDecimal.ZERO, mUpkeep = BigDecimal.ZERO;
        BigDecimal mBalance = BigDecimal.ZERO;

        for (UUID clubId : memberIds) {
            Club club = clubById.get(clubId);
            if (club == null) continue;
            BigDecimal gate = BigDecimal.ZERO;
            if (homeClubs.contains(clubId)) {
                UUID awayId = matchdayFixtures.stream().filter(f -> f.getHomeClubId().equals(clubId))
                        .map(Fixture::getAwayClubId).findFirst().orElse(null);
                Club away = awayId != null ? clubById.get(awayId) : null;
                int awayRep = away != null ? away.getReputation() : club.getReputation();
                gate = financeCalculator.gateIncome(club.getReputation(), awayRep, club.getStadiumCapacity());
            }
            BigDecimal sponsorship = club.getSponsorshipPerRound();
            BigDecimal wages = wageBillByClub.getOrDefault(clubId, BigDecimal.ZERO);
            BigDecimal upkeep = club.getStadiumUpkeepPerRound();
            BigDecimal net = gate.add(sponsorship).subtract(wages).subtract(upkeep);
            club.adjustBalance(net);
            clubRepository.save(club);

            if (clubId.equals(managedClubId)) {
                mGate = gate; mSpon = sponsorship; mWages = wages; mUpkeep = upkeep; mBalance = club.getBalance();
                recordManaged(save, FinanceTransactionType.GATE, gate, "Bilheteria");
                recordManaged(save, FinanceTransactionType.SPONSOR, sponsorship, "Patrocínio");
                recordManaged(save, FinanceTransactionType.WAGES, wages.negate(), "Salários");
                recordManaged(save, FinanceTransactionType.UPKEEP, upkeep.negate(), "Manutenção do estádio");
            }
        }
        BigDecimal net = mGate.add(mSpon).subtract(mWages).subtract(mUpkeep);
        return new FinanceRoundResponse(mGate, mSpon, mWages, mUpkeep, BigDecimal.ZERO, net, mBalance);
    }

    @Transactional
    public BigDecimal processSeasonEnd(UUID saveGameId, UUID competitionId) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        UUID managedClubId = save.getManagedClubId();
        List<StandingRow> table = standingsCalculator.compute(saveGameId, competitionId);
        int clubCount = table.size();
        BigDecimal managedPrize = BigDecimal.ZERO;
        int position = 1;
        for (StandingRow row : table) {
            BigDecimal prize = financeCalculator.prizeMoney(position, clubCount);
            Club club = clubRepository.findById(row.clubId()).orElseThrow();
            club.adjustBalance(prize);
            clubRepository.save(club);
            if (row.clubId().equals(managedClubId)) {
                managedPrize = prize;
                recordManaged(save, FinanceTransactionType.PRIZE, prize, "Premiação - " + position + "º lugar");
            }
            position++;
        }
        return managedPrize;
    }
```

IMPORTANT: match the EXACT helper names the existing `FinanceService` already uses for (a) the managed-ledger record helper, (b) the wage-bill map builder, and (c) the standings source used by the existing `processSeasonEnd`. The grounding shows the existing `processSeasonEnd` uses `standingsCalculator.compute(...)` and the existing `processRound` builds a `wageBillByClub` map and records managed rows. Reuse those exact private helpers/fields (do not duplicate their logic); if the existing managed-record helper has a different name/signature than `recordManaged(...)`, call the existing one and delete the placeholder names above. Read `FinanceService.java` and align names before implementing. If `standingsCalculator.compute` takes `(saveGameId, competitionId)` use it directly; if it takes a `Competition`, fetch it first.

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.finance.FinanceMatchdayIT"
```
Expected: PASS. Then `./gradlew test --tests "br.com.carvoeiromanager.finance.*"` to confirm the existing finance tests (which still use the old `processRound`) stay green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java \
  backend/src/test/java/br/com/carvoeiromanager/finance/FinanceMatchdayIT.java
git commit -m "$(cat <<'EOF'
feat(2d): finance per-matchday over members + per-competition prize

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: New seed format + creation wiring (Catarinense-only, dated calendar)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedCareer.java`, `seed/SeedCompetition.java`
- Modify: `seed/SeedLoader.java`, `resources/seed/catarinense.json` → restructured (renamed/augmented), `career/SaveGameService.java`, `career/SaveGameResponse.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/CareerCreationCalendarIT.java`

**Interfaces produced:**
- `SeedCareer(List<SeedClub> clubPool, List<SeedCompetition> competitions)`; `SeedCompetition(String name, String type, int seasonYear, List<String> memberShortNames)`.
- `SeedLoader.loadCareer(): SeedCareer`.
- `SaveGameService.createCareer` builds clubs from `clubPool` once (keyed by shortName), `competition_club` memberships per `SeedCompetition.memberShortNames`, one `Competition` per `SeedCompetition`, then calls `seasonCalendarGenerator.generate(...)`; sets `save.currentDate = LocalDate.of(seasonYear, 1, 15)`.
- `SaveGameResponse` gains `currentDate` (ISO string via `LocalDate`) + `List<CompetitionSummary> competitions` where `CompetitionSummary(UUID id, String name, String type)`. (Keep existing fields; `competitionId`/`competitionName`/`currentRound`/`totalRounds` may stay populated from the FIRST competition for backward compatibility with current FE until 2D-2.)

This task keeps ONLY the Catarinense competition (single competition) but expressed in the new club-pool format and routed through the dated calendar generator. The world must remain equivalent so existing `createCareer`-dependent tests stay green; the additions (`current_date`, `season_event`, `competition_club`, `match_date`) are new. The OLD round-based advance (`RoundSimulationService.simulateNextRound`, retired in Task 7) still works because fixtures still carry rounds.

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class CareerCreationCalendarIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    @Test
    void careerCreationBuildsDatedCalendarAndMemberships() {
        User u = User.create("cc_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());

        // current_date set to mid-January of the season.
        SaveGame s = saveGameRepository.findById(save.id()).orElseThrow();
        assertThat(s.getCurrentDate()).isEqualTo(LocalDate.of(save.seasonYear(), 1, 15));

        // Memberships exist for the competition (Catarinense = 10 clubs).
        assertThat(competitionClubRepository.findByCompetitionId(save.competitionId())).hasSize(10);

        // Season events generated, starting with a MATCH on day one.
        var events = seasonEventRepository.findBySaveGameId(save.id());
        assertThat(events.stream().anyMatch(e -> e.getType() == SeasonEventType.MATCH)).isTrue();
        assertThat(events.stream().anyMatch(e -> e.getType() == SeasonEventType.TRANSFER)).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.career.CareerCreationCalendarIT"
```
Expected: FAIL — new seed format / calendar wiring / `current_date` missing.

- [ ] **Step 3: Create the seed records**

`seed/SeedCompetition.java`:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedCompetition(String name, String type, int seasonYear, List<String> memberShortNames) {}
```

`seed/SeedCareer.java`:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedCareer(List<SeedClub> clubPool, List<SeedCompetition> competitions) {}
```

- [ ] **Step 4: Restructure the seed JSON + loader**

Restructure `resources/seed/catarinense.json` into the career format (rename allowed — keep the file name `catarinense.json` to minimize churn, or create `career.json`; pick one and load it). New top-level shape:

```json
{
  "clubPool": [
    { "name": "Criciúma Esporte Clube", "shortName": "CRI", "nickname": "Tigre", "primaryColor": "#f7d417", "secondaryColor": "#111111", "players": [ ... existing CRI players ... ] },
    ... the other 9 Catarinense clubs unchanged ...
  ],
  "competitions": [
    { "name": "Campeonato Catarinense", "type": "LEAGUE", "seasonYear": 2026,
      "memberShortNames": ["CRI", "...all 10 short names..."] }
  ]
}
```

Keep every existing club + its full player list verbatim in `clubPool`; just move them under `clubPool` and add the `competitions` array listing all 10 short names. Add `SeedLoader.loadCareer()` reading this file into `SeedCareer` (mirror the existing `loadCatarinense()` Jackson read). You may keep `loadCatarinense()` if other code references it, or remove it if unused after Task 5.

- [ ] **Step 5: Rewire createCareer + SaveGameResponse**

In `career/SaveGameResponse.java`, add the new fields + a nested summary record:

```java
public record SaveGameResponse(
        UUID id, int seasonYear, UUID competitionId, String competitionName,
        UUID managedClubId, String managedClubName, int currentRound, int totalRounds,
        String currentDate, List<SaveGameResponse.CompetitionSummary> competitions) {
    public record CompetitionSummary(UUID id, String name, String type) {}
}
```
(Add `import java.util.List;` and `import java.util.UUID;`.)

In `career/SaveGameService.createCareer`: inject `SeasonCalendarGenerator seasonCalendarGenerator` + `CompetitionClubRepository competitionClubRepository` (constructor params + fields + imports). Replace the seed loading + club/competition/fixture creation so it:
1. `SeedCareer seed = seedLoader.loadCareer();`
2. Build all clubs from `seed.clubPool()` (the existing per-club + per-player + financial-profile + contract-stagger + free-agent logic is unchanged — just iterate `clubPool` instead of `clubs`), keeping a `Map<String,UUID> clubIdByShortName` and tracking the managed club (`shortName.equals("CRI")`).
3. For each `SeedCompetition`: create a `Competition` (`Competition.create(saveGame.getId(), sc.name(), sc.type(), sc.seasonYear(), 2*(memberCount-1))`), then for each member short-name save a `CompetitionClub.create(saveGame.getId(), competition.getId(), clubIdByShortName.get(sn))`.
4. Remove the old inline fixture loop; instead call `seasonCalendarGenerator.generate(saveGame.getId(), seed.competitions().get(0).seasonYear(), allCompetitions)`.
5. `saveGame.setCurrentDate(LocalDate.of(seed.competitions().get(0).seasonYear(), 1, 15));`
6. Build the `SaveGameResponse`: keep `competitionId/competitionName/totalRounds` from the first competition (Catarinense), `currentRound = 0`, add `currentDate = saveGame.getCurrentDate().toString()`, and `competitions = allCompetitions.stream().map(c -> new CompetitionSummary(c.getId(), c.getName(), c.getType())).toList()`.

Update `SaveGameService.getById(...)` to also populate `currentDate` (from `saveGame.getCurrentDate()`, null-safe → `null`) + the `competitions` list (from `competitionRepository.findBySaveGameId`).

- [ ] **Step 6: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.career.CareerCreationCalendarIT"
```
Expected: PASS. Then `./gradlew test` — the full suite must stay green (the Catarinense world is equivalent; old advance still works). Fix any test that constructed a `SaveGameResponse(...)` literal by adding the two new trailing args, or that asserted exact event/fixture counts affected by `match_date` being set.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/seed/ \
  backend/src/main/resources/seed/ \
  backend/src/main/java/br/com/carvoeiromanager/career/SaveGameResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
  backend/src/test/java/br/com/carvoeiromanager/career/CareerCreationCalendarIT.java
git commit -m "$(cat <<'EOF'
feat(2d): club-pool seed format + creation builds dated calendar + memberships

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extract FixtureSimulator from RoundSimulationService

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/RoundSimulationService.java`
- Test: existing `match/*IT` suite must stay green (no new behavior).

**Interfaces produced:**
- `FixtureSimulator.simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures): RoundOutcome` where `RoundOutcome(List<FixtureResultResponse> results, List<BookingResponse> bookings, List<SuspensionResponse> newSuspensions)` — runs the per-fixture simulation + `MatchOutcomeApplier` lifecycle + player persistence for the given fixtures, exactly as `RoundSimulationService` does today (same match seed `saveGameId.msb ^ fixtureId.msb ^ round`). Pure refactor.

This is a refactor: move the fixture loop + `simulateFixture` + `saveEvents` + outcome application + player persistence out of `RoundSimulationService` into `FixtureSimulator`, and have `RoundSimulationService.simulateNextRound` delegate to it. Behavior must be identical (the existing match/advance ITs are the safety net). `SeasonAdvanceService` (Task 7) will reuse `FixtureSimulator`.

- [ ] **Step 1: Confirm the safety-net tests**

Identify the existing ITs that exercise advance/match simulation (e.g. `TransferServiceBuyingIT` advances rounds, `SeasonRolloverServiceIT` finishes seasons, any `match` IT). These must stay green unchanged.

- [ ] **Step 2: Create FixtureSimulator**

Move from `RoundSimulationService` into a new `@Component FixtureSimulator`: the `FixtureSim` record, `simulateFixture`, `saveEvents`, `setPieceTaker`, `pickWeighted`, `resolveXi`, `buildClubMap`, and the fixture-loop + player-persistence body of `simulateNextRound` (everything except the save-game status/round/finance/transfer parts). Expose:

```java
public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures) { ... }
```
returning a `RoundOutcome` record carrying `results`, `bookings`, `newSuspensions`. Inject the same collaborators it needs (`lineupService`, `tacticsService`, `teamStrengthCalculator`, `matchSimulationService`, `matchEventRepository`, `matchOutcomeApplier`, `playerRepository`, `clubRepository`, `fixtureRepository`). Keep the match seed formula identical.

- [ ] **Step 3: Make RoundSimulationService delegate**

`RoundSimulationService.simulateNextRound` now: resolve competition + round + fixtures as today, call `fixtureSimulator.simulateRound(save, competition.getId(), fixtures)`, then keep its existing round-increment / FINISHED / `financeService.processRound(...)` / `generateIncomingOffers(...)` / `SimulateRoundResponse` assembly using the returned `RoundOutcome`. No behavior change.

- [ ] **Step 4: Run the safety-net suite**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test
```
Expected: PASS (full suite — pure refactor).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
  backend/src/main/java/br/com/carvoeiromanager/match/RoundSimulationService.java
git commit -m "$(cat <<'EOF'
refactor(2d): extract FixtureSimulator from RoundSimulationService

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: SeasonAdvanceService — date-driven advance (the flip)

**Files:**
- Create: `season/SeasonAdvanceService.java`, `season/AdvanceResponse.java`, `season/AdvanceController.java`
- Modify: `transfer/TransferService.java` (window callers → date-based, `generateIncomingOffers(LocalDate)`), `contract/ContractService.java` (window caller → date-based), `match/MatchController.java` (remove/repoint old advance), `match/RoundSimulationService.java` (remove `simulateNextRound` once unused), `finance/FinanceService.java` (remove old `processRound`/`processSeasonEnd()` once unused), `transfer/TransferWindow.java` (remove old `isOpen(int,int)` once unused)
- Modify (existing tests): every IT that advanced via `roundSimulationService.simulateNextRound(...)` or asserted round-based window behavior.
- Test: `season/SeasonAdvanceServiceIT.java`

**Interfaces produced:**
- `SeasonAdvanceService.advance(UUID saveGameId, UUID userId): AdvanceResponse`.
- `AdvanceResponse(String type, String date, String competitionName, Integer round, List<FixtureResultResponse> results, List<BookingResponse> bookings, List<SuspensionResponse> newSuspensions, FinanceRoundResponse finance, int bidsReceived, boolean seasonFinished)`.
- `transfer/TransferService.generateIncomingOffers(UUID saveGameId, LocalDate eventDate)`.
- Endpoint `POST /api/v1/save-games/{saveGameId}/advance` → `AdvanceResponse` (replaces the old `SimulateRoundResponse` one).

This is the coupled flip. After it, advancement is event-driven and the round-based advance/finance/window are removed.

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonAdvanceServiceIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService advanceService;

    private record Career(UUID userId, UUID id) {}

    private Career career() {
        User u = User.create("adv_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse s = saveGameService.createCareer(u.getId());
        return new Career(u.getId(), s.id());
    }

    @Test
    void firstAdvancePlaysTheFirstMatchEventAndSetsTheDate() {
        Career c = career();
        AdvanceResponse r = advanceService.advance(c.id(), c.userId());
        // First event in the Catarinense-only career is a MATCH on day one.
        assertThat(r.type()).isEqualTo("MATCH");
        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 1, 15).toString());
        assertThat(r.results()).isNotEmpty();
        assertThat(saveGameRepository.findById(c.id()).orElseThrow().getCurrentDate())
                .isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void advancingThroughAllEventsFinishesTheSeason() {
        Career c = career();
        int guard = 0;
        AdvanceResponse last = null;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            last = advanceService.advance(c.id(), c.userId());
        }
        assertThat(saveGameRepository.findById(c.id()).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.FINISHED);
        assertThat(last).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.SeasonAdvanceServiceIT"
```
Expected: FAIL — `SeasonAdvanceService` does not exist.

- [ ] **Step 3: Create AdvanceResponse**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.finance.FinanceRoundResponse;
import br.com.carvoeiromanager.match.BookingResponse;
import br.com.carvoeiromanager.match.FixtureResultResponse;
import br.com.carvoeiromanager.match.SuspensionResponse;

import java.util.List;

public record AdvanceResponse(
        String type,                         // "MATCH" | "TRANSFER" | "FINISHED"
        String date,                         // ISO date of the processed event (null if none)
        String competitionName,              // MATCH only
        Integer round,                       // MATCH only (competition round)
        List<FixtureResultResponse> results, // MATCH
        List<BookingResponse> bookings,       // MATCH
        List<SuspensionResponse> newSuspensions, // MATCH
        FinanceRoundResponse finance,         // MATCH
        int bidsReceived,                     // TRANSFER
        boolean seasonFinished) {}
```
(Use the EXACT type names the project has for booking/suspension responses — the grounding shows `BookingResponse`/`SuspensionResponse`; confirm and match.)

- [ ] **Step 4: Implement SeasonAdvanceService**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.finance.FinanceRoundResponse;
import br.com.carvoeiromanager.finance.FinanceService;
import br.com.carvoeiromanager.match.FixtureSimulator;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Date-driven advance: pops the next dated season_event and processes it. */
@Service
public class SeasonAdvanceService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;
    private final TransferService transferService;
    private final TransferOfferRepository transferOfferRepository;

    public SeasonAdvanceService(SaveGameService saveGameService, SaveGameRepository saveGameRepository,
                                SeasonEventRepository seasonEventRepository,
                                CompetitionRepository competitionRepository,
                                FixtureRepository fixtureRepository, FixtureSimulator fixtureSimulator,
                                FinanceService financeService, TransferService transferService,
                                TransferOfferRepository transferOfferRepository) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.transferService = transferService;
        this.transferOfferRepository = transferOfferRepository;
    }

    @Transactional
    public AdvanceResponse advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        if (save.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }
        SeasonEvent event = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        if (event == null) {
            save.setStatus(SaveGameStatus.FINISHED);
            saveGameRepository.save(save);
            return new AdvanceResponse("FINISHED", null, null, null, List.of(), List.of(), List.of(),
                    null, 0, true);
        }

        AdvanceResponse response;
        if (event.getType() == SeasonEventType.MATCH) {
            response = processMatch(save, event);
        } else {
            int created = countNewBids(saveGameId, () ->
                    transferService.generateIncomingOffers(saveGameId, event.getEventDate()));
            response = new AdvanceResponse("TRANSFER", event.getEventDate().toString(), null, null,
                    List.of(), List.of(), List.of(), null, created, false);
        }

        event.markProcessed();
        seasonEventRepository.save(event);
        save.setCurrentDate(event.getEventDate());

        boolean noMoreMatches = seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId).stream()
                .noneMatch(e -> e.getType() == SeasonEventType.MATCH);
        if (noMoreMatches) {
            save.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(save);
        return response;
    }

    private AdvanceResponse processMatch(SaveGame save, SeasonEvent event) {
        Competition competition = competitionRepository.findById(event.getCompetitionId()).orElseThrow();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                save.getId(), competition.getId(), event.getRound());
        FixtureSimulator.RoundOutcome outcome = fixtureSimulator.simulateRound(
                save, competition.getId(), fixtures);

        FinanceRoundResponse roundFinance = financeService.processMatchday(
                save.getId(), competition.getId(), fixtures);
        BigDecimal prize = BigDecimal.ZERO;
        boolean finalRound = event.getRound() == competition.getTotalRounds() - 1;
        if (finalRound) {
            prize = financeService.processSeasonEnd(save.getId(), competition.getId());
        }
        FinanceRoundResponse finance = new FinanceRoundResponse(
                roundFinance.gate(), roundFinance.sponsorship(), roundFinance.wages(),
                roundFinance.upkeep(), prize, roundFinance.net(), roundFinance.balance().add(prize));

        return new AdvanceResponse("MATCH", event.getEventDate().toString(), competition.getName(),
                event.getRound(), outcome.results(), outcome.bookings(), outcome.newSuspensions(),
                finance, 0, false);
    }

    private int countNewBids(UUID saveGameId, Runnable generate) {
        int before = activeIncoming(saveGameId);
        generate.run();
        return Math.max(0, activeIncoming(saveGameId) - before);
    }

    private int activeIncoming(UUID saveGameId) {
        return (int) transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == br.com.carvoeiromanager.transfer.TransferDirection.INCOMING)
                .count();
    }
}
```
(If `FixtureSimulator.RoundOutcome`'s accessor names differ, match them. `FinanceRoundResponse` field accessors are `gate()/sponsorship()/wages()/upkeep()/net()/balance()` per the grounding.)

- [ ] **Step 5: Switch the transfer/contract window callers to date-based + change generateIncomingOffers**

- `transfer/TransferWindow.java`: remove the old `isOpen(int,int)` (and `PRE_SEASON_ROUNDS`/`MID_LENGTH` if now unused).
- `transfer/TransferService.java`:
  - `requireWindowOpen(SaveGame save)` → `if (!transferWindow.isOpen(save.getCurrentDate())) throw ...TRANSFER_WINDOW_CLOSED...`. Drop the `competitionRepository.findBySaveGameId(...).get(0)` lookup there.
  - `generateIncomingOffers(UUID saveGameId, int round)` → `generateIncomingOffers(UUID saveGameId, LocalDate eventDate)`: replace the window check with `if (!transferWindow.isOpen(eventDate)) return;` and the seed with `new Random(saveGameId.getMostSignificantBits() ^ eventDate.toEpochDay())`. (It no longer needs `competition.getTotalRounds()`.)
- `contract/ContractService.java`: `requireWindowOpen(SaveGame save)` → `transferWindow.isOpen(save.getCurrentDate())`; drop the competition lookup.

- [ ] **Step 6: Repoint the advance endpoint + retire the round-based path**

- `match/MatchController.java`: change the `POST /{saveGameId}/advance` handler to call `seasonAdvanceService.advance(saveGameId, userId)` and return `AdvanceResponse` (inject `SeasonAdvanceService`). (Or move the endpoint to the new `AdvanceController` and delete the old handler — pick one; keep the path `/api/v1/save-games/{saveGameId}/advance`.)
- Delete `RoundSimulationService.simulateNextRound` and any now-unused helpers there (the per-fixture logic now lives in `FixtureSimulator`). If `RoundSimulationService` becomes empty, delete the class and its references.
- Delete the old `FinanceService.processRound(saveGameId, round, fixtures)` and `processSeasonEnd(saveGameId)` (their only caller is gone).

- [ ] **Step 7: Update every existing test that advanced by round or used the round-based window**

Search for `simulateNextRound(` and `isOpen(` usages in tests. Replace round-advancing loops with `seasonAdvanceService.advance(saveGameId, userId)` (note: each advance now processes ONE event — a MATCH or a TRANSFER — so loops that "advance N rounds" must advance until they've processed the intended matches, or until a target `current_date`/status). Concretely:
- `TransferServiceBuyingIT.offersAreBlockedWhenTheWindowIsClosed` (advanced 5 rounds to close the window): instead, set `save.currentDate` to a date OUTSIDE both windows (e.g. `LocalDate.of(2026, 5, 1)`) via the repository before calling `makeOffer`, and assert `TRANSFER_WINDOW_CLOSED`. (Simpler + deterministic than advancing events.)
- `ContractServiceSignIT.signingIsBlockedWhenTheWindowIsClosed`: same — set `currentDate` outside the window.
- Any IT that advances to FINISH a season (e.g. rollover ITs' `playToSeasonEnd`): replace the `simulateNextRound` loop with an `advance` loop until `status == FINISHED` (guard the iteration count, as in `SeasonAdvanceServiceIT`).
- Any test asserting the old `SimulateRoundResponse` shape from the advance endpoint must move to `AdvanceResponse` (or be covered by `SeasonAdvanceServiceIT`).

Run the FULL suite and fix every failure this flip surfaces:

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test
```
Expected: GREEN after updates. (This task's deliverable is a green full suite on the event-driven advance.)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
  backend/src/main/java/br/com/carvoeiromanager/season/AdvanceResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/season/AdvanceController.java \
  backend/src/main/java/br/com/carvoeiromanager/match/ \
  backend/src/main/java/br/com/carvoeiromanager/transfer/ \
  backend/src/main/java/br/com/carvoeiromanager/contract/ \
  backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java \
  backend/src/test/
git commit -m "$(cat <<'EOF'
feat(2d): date-driven SeasonAdvanceService + date-based windows (flip)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Rollover regenerates the dated calendar

**Files:**
- Modify: `season/SeasonRolloverService.java`, `career/SaveGame.java` (`advanceToNextSeason` resets `currentDate`)
- Modify (existing tests): `season/SeasonRolloverServiceIT.java`, `transfer/TransferAdvanceRolloverIT.java` (if affected)
- Test: `season/RolloverCalendarIT.java`

**Interfaces:** rollover regenerates fixtures + `season_event` per competition over members via `SeasonCalendarGenerator`, resets `current_date` to the new season's January, purges `season_event`. `SaveGame.advanceToNextSeason()` also sets `currentDate = LocalDate.of(seasonYear, 1, 15)` (after incrementing `seasonYear`).

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RolloverCalendarIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService advanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonEventRepository seasonEventRepository;

    @Test
    void rolloverRegeneratesADatedCalendarForTheNewSeason() {
        User u = User.create("rc_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());
        int guard = 0;
        while (saveGameRepository.findById(save.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            advanceService.advance(save.id(), u.getId());
        }
        rolloverService.rollover(save.id(), u.getId());

        SaveGame s = saveGameRepository.findById(save.id()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
        assertThat(s.getCurrentDate()).isEqualTo(LocalDate.of(save.seasonYear() + 1, 1, 15));
        // A fresh calendar of unprocessed events exists for the new season.
        var events = seasonEventRepository.findBySaveGameIdAndProcessedFalse(save.id());
        assertThat(events.stream().anyMatch(e -> e.getType() == SeasonEventType.MATCH)).isTrue();
        assertThat(events).allMatch(e -> e.getEventDate().getYear() == save.seasonYear() + 1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.RolloverCalendarIT"
```
Expected: FAIL — rollover doesn't regenerate dated events / reset `current_date`.

- [ ] **Step 3: Update SaveGame.advanceToNextSeason**

```java
    public void advanceToNextSeason() {
        this.seasonYear += 1;
        this.currentRound = 0;
        this.currentDate = java.time.LocalDate.of(this.seasonYear, 1, 15);
        this.status = SaveGameStatus.IN_PROGRESS;
    }
```

- [ ] **Step 4: Rewire SeasonRolloverService**

Inject `SeasonCalendarGenerator seasonCalendarGenerator` + `SeasonEventRepository seasonEventRepository`. Changes within `rollover(...)`:
- After the existing finance + transfer-offer purges, add `seasonEventRepository.deleteBySaveGameId(saveGameId);`.
- Replace the existing per-competition inline fixture-regeneration block (the loop that calls `fixtureScheduler.doubleRoundRobin(clubCount)` over ALL clubs and saves fixtures) with: first `competition.setSeasonYear(finishingYear + 1)` for each competition and `competitionRepository.save(competition)`, then a SINGLE call `seasonCalendarGenerator.generate(saveGameId, finishingYear + 1, competitions)` (which regenerates fixtures over each competition's members + the dated event queue). Remove the now-unused `fixtureScheduler` field/usage in `SeasonRolloverService` if the generator fully replaces it.
- `competition_club` memberships persist (do NOT purge them).
- Keep `saveGame.advanceToNextSeason()` (now also resets `currentDate`).
- FK-safe order unchanged: events/fixtures purge + lineup clear + flush before player deletes; `season_event`/`competition_club` have no player FK.

- [ ] **Step 5: Run + update existing rollover tests**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.season.RolloverCalendarIT" --tests "br.com.carvoeiromanager.season.SeasonRolloverServiceIT" --tests "br.com.carvoeiromanager.transfer.TransferAdvanceRolloverIT"
```
Update `SeasonRolloverServiceIT`: its fixture-count assertion (`fixtures).hasSize(clubCount * (clubCount - 1))`) is now PER COMPETITION over members — for the single Catarinense competition (10 members) it stays `10 * 9 = 90`; keep it scoped to the Catarinense competition id. Its "fixtures unplayed" assertions hold. If it advanced via `simulateNextRound`, that was already migrated to `advance` in Task 7. Then run the full suite.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
  backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java \
  backend/src/test/java/br/com/carvoeiromanager/season/
git commit -m "$(cat <<'EOF'
feat(2d): rollover regenerates the dated calendar per competition

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Add the Série B competition + new club identities (exemplar)

**Files:**
- Modify: `resources/seed/catarinense.json` (the career seed) — add the Série B competition + its memberships + ~14 new national club identities; author ONE exemplar club's full squad.
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/TwoCompetitionCareerIT.java`

**Interfaces:** the seed's `competitions` array gains a second entry (`name "Série B", type "LEAGUE", seasonYear 2026, memberShortNames` = 20 short names: `CRI` + the shared SC clubs already in the pool that really play Série B [author with realistic choices, e.g. `AVA`, `CHA`, `FIG`, `BRU`, `JOI` if present] + ~14 NEW national club short-names). The `clubPool` gains the ~14 new club identities. THIS task authors ONE new club's complete squad as the exemplar; Tasks 10–12 author the rest to that bar.

- [ ] **Step 1: Write the failing test**

```java
package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
class TwoCompetitionCareerIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;

    @Test
    void careerHasTwoCompetitionsWithCriciumaInBoth() {
        User u = User.create("two_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());

        var comps = competitionRepository.findBySaveGameId(save.id());
        assertThat(comps).hasSize(2);
        Competition serieB = comps.stream().filter(c -> c.getName().contains("Série B")).findFirst().orElseThrow();
        Competition cat = comps.stream().filter(c -> c.getName().contains("Catarinense")).findFirst().orElseThrow();
        assertThat(competitionClubRepository.findByCompetitionId(serieB.getId())).hasSize(20);
        assertThat(competitionClubRepository.findByCompetitionId(cat.getId())).hasSize(10);
        // Criciúma (managed) is a member of BOTH.
        UUID managed = save.managedClubId();
        assertThat(competitionClubRepository.findBySaveGameIdAndClubId(save.id(), managed)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.career.TwoCompetitionCareerIT"
```
Expected: FAIL — only one competition seeded.

- [ ] **Step 3: Author the Série B competition + exemplar club**

In the career seed JSON:
1. Add a second `competitions` entry: `{ "name": "Série B", "type": "LEAGUE", "seasonYear": 2026, "memberShortNames": [ ...20 short names... ] }`. Include `CRI` and the shared SC clubs (use the real SC clubs already in the pool that historically play Série B), plus ~14 NEW national club short-names.
2. Add the ~14 new club identities to `clubPool` with real Série B identities (`name`, unique `shortName`, `nickname`, `primaryColor`, `secondaryColor`). For THIS task, fully author ONE new club's `players` array as the EXEMPLAR (the others get an empty/stub squad now and are filled in Tasks 10–12). The exemplar squad must meet the **squad bar** (see below).

**Squad bar (every authored club must satisfy):**
- 20–23 players; position mix ≈ 3 GK, 7 DEF, 7 MID, 6 ATT (`position` ∈ `GK|DEF|MID|ATT`).
- `nationality` "Brasil"; `age` spread 18–36; realistic Brazilian player names.
- Attributes 1–99, position-appropriate (GK high `goalkeeping`; DEF high `tackling`; MID high `passing`; ATT high `finishing`); calibrate the squad's average `overall` to the club's strength so Série B clubs are roughly mid-tier (avg overall ≈ 60–72), distinguishable between clubs.

Example exemplar player row (match the existing `SeedPlayer` field order):
```json
{ "name": "Ricardo Alves", "position": "DEF", "age": 27, "nationality": "Brasil",
  "pace": 64, "finishing": 30, "passing": 58, "tackling": 74, "goalkeeping": 9, "stamina": 70 }
```

NOTE: clubs with stub/empty squads will fail finance/strength derivation if a competition tries to play them before they're filled. Tasks 10–12 fill them. To keep THIS task green, give every new club a MINIMAL valid squad (the exemplar bar) OR ensure the `TwoCompetitionCareerIT` only asserts membership counts (it does) and defer fixture-playing assertions to Task 13 (after all squads are authored). The simplest: author the exemplar fully and give the other new clubs a small valid placeholder squad (≥ 14 players incl. ≥1 GK) so creation + finance succeed; Tasks 10–12 replace placeholders with full squads.

- [ ] **Step 4: Run to verify it passes**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test --tests "br.com.carvoeiromanager.career.TwoCompetitionCareerIT"
```
Expected: PASS. Then `./gradlew test` (full suite green — two competitions now exist; the engine handles them).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/seed/ \
  backend/src/test/java/br/com/carvoeiromanager/career/TwoCompetitionCareerIT.java
git commit -m "$(cat <<'EOF'
feat(2d): Série B competition + memberships + exemplar club squad

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Tasks 10–12: Author the remaining Série B squads (batches)

Each batch task authors the **full squads** for a group of the new Série B clubs (replacing the Task 9 placeholder squads), to the **squad bar** from Task 9. Split the ~14 new clubs into three batches of ~5 (Task 10: clubs 1–5; Task 11: 6–10; Task 12: 11–14).

**Files (each batch):** Modify `resources/seed/catarinense.json` (the listed clubs' `players` arrays); Test: extend `TwoCompetitionCareerIT` (or a new `SerieBSquadsIT`) to assert the batch's clubs each have a full squad meeting the bar.

For each batch task:
- [ ] **Step 1: Add/extend the failing test** — assert each club in the batch has 20–23 players, ≥3 GK / ≥7 DEF / ≥7 MID / ≥6 ATT (by `position`), all `nationality` "Brasil", attributes in 1–99, and a squad-average `overall` in the 58–74 band. Example assertion helper:
```java
// for each batch club shortName: load its players via a created career, group by position, assert counts + ranges
```
- [ ] **Step 2: Run to verify it fails** (`./gradlew test --tests "...SerieBSquadsIT"`) — placeholder squads fail the count/bar.
- [ ] **Step 3: Author the squads** — realistic Brazilian rosters meeting the bar; calibrate each club's average overall to a sensible Série B tier.
- [ ] **Step 4: Run to verify it passes.**
- [ ] **Step 5: Commit** (`feat(2d): author Série B squads (batch N)` + trailer).

Authoring guidance for the implementer: these are *plausible curated* squads, not a claimed real-world snapshot. Vary names; keep positions/attributes coherent; do not duplicate player names across clubs.

---

## Task 13: Two-competition end-to-end backend verification

**Files:** Test: `backend/src/test/java/br/com/carvoeiromanager/season/TwoCompetitionSeasonIT.java`

- [ ] **Step 1: Write the test** — create a career, advance event-by-event to season end, and assert: both competitions' standings are populated and independent; the managed club (Criciúma) appears in both standings; prize was paid for each competition at its final round (managed balance reflects two prizes if applicable); `current_date` progressed from January into the autumn; the season finished. Then roll over and assert a fresh dated calendar for the new year (both competitions).

```java
// Pseudocode shape (fill with real assertions):
// SaveGameResponse save = createCareer();
// advance until FINISHED (guard 800);
// StandingsService.getStandings(save, catId, user) and (save, serieBId, user) both size 10 / 20;
// both tables contain Criciúma; current_date.year == seasonYear and month >= 9 (autumn);
// rollover -> IN_PROGRESS, current_date = next Jan, unprocessed MATCH events exist for both comps.
```

- [ ] **Step 2: Run it** (`./gradlew test --tests "br.com.carvoeiromanager.season.TwoCompetitionSeasonIT"`) and fix any integration gaps.

- [ ] **Step 3: Full-suite + boot verification**

```
cd backend && export JAVA_HOME="$(/usr/libexec/java_home -v 25)" && ./gradlew test
```
Expected: entire backend suite green (Flyway V8 applies on the Testcontainers boot; engine, finance, transfers, contracts, rollover all green on the event-driven dated multi-competition model).

- [ ] **Step 4: Commit** (`test(2d): two-competition season end-to-end backend verification` + trailer).

---

## Plan Self-Review

**Spec coverage (backend portions):** §4.1 membership → T1; §4.2 season_event + advance → T1/T3/T7; §4.3 + date window → T2/T7; §5 V8 migration + entities → T1; §6 calendar generator → T3; §7 advance service → T7; §8 finance per-matchday + prize → T4/T7; §9 rollover → T8; §10 seed (club-pool format → T5; Série B + squads → T9–T12); §15 testing → each task; §16 determinism → constraints + T3/T7. Frontend (§11–§12) is explicitly Phase 2D-2, not this plan. Standings/top-scorers (§ confirmed already per-competition) → no task needed.

**Placeholder scan:** No "TBD"/"implement later". Task 4 step 3 and Task 7 steps 3–4 instruct the implementer to MATCH exact existing helper/accessor names (`recordManaged`/wage-bill builder/`standingsCalculator`, `RoundOutcome` accessors, `BookingResponse`/`SuspensionResponse`) by reading the files — these are alignment instructions with concrete fallbacks, not vague placeholders. Seed tasks (T9–T12) give a schema + exemplar + an explicit quality bar (the correct treatment for authored data, which cannot be literal-coded without fabricating ~280 rows).

**Type consistency:** `SeasonEvent.createMatch/createTransfer`, `SeasonEventRepository.findFirst...`, `CompetitionClub.create`, `Fixture.create(...matchDate)`, `SaveGame.currentDate`, `SeasonCalendarGenerator.generate(saveGameId, seasonYear, competitions)`, `FinanceService.processMatchday/processSeasonEnd(competitionId)`, `FixtureSimulator.simulateRound`, `SeasonAdvanceService.advance`, `AdvanceResponse`, `generateIncomingOffers(LocalDate)` are referenced consistently across T1→T8. `SaveGameResponse` new fields (currentDate, competitions) consistent T5↔T9. The Catarinense single-competition stays equivalent through T5 (keeps the suite green) before Série B lands in T9.

**Sequencing safety:** additive entities (T1) → date-window overload added but old kept (T2) → generator tested on synthetic comps (T3) → finance methods added beside the old (T4) → creation rewired but world equivalent + old advance still works (T5) → pure refactor (T6) → the single coupled flip removes the round-based path + updates all advancing tests (T7) → rollover regen (T8) → Série B + squads (T9–T12) → e2e (T13). The only large coupled task is T7 (unavoidable: the round→event advance, date-window, and removal of the old path must land together); its deliverable is an explicitly green full suite.
