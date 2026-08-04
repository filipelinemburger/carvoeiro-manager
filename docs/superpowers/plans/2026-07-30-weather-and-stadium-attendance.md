# Weather Simulation + Stadiums with Visible Attendance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every fixture is played in deterministic, geography-and-season-driven weather that mechanically bites on tactics, and stadiums gain real capacities with a visible, context-driven attendance figure.

**Architecture:** Weather is a *pure function* of `(fixtureId, ClimateZone, matchDate)` — never stored — so the live engine, the instant simulator, the eve-of-match news, and the UI cannot disagree. It reaches the engines through a new `WeatherEffects` static utility applied at the existing `MatchupModifiers` seam in both engines. Attendance is the opposite: a realized outcome computed by a pure `AttendanceCalculator` and persisted on the fixture row at match resolution, from the same two call sites that already share `MatchOutcomeApplier`.

**Tech Stack:** Java 21 Spring Boot + Flyway + Testcontainers ITs (backend), React + TypeScript + MUI + vitest (frontend).

**Reference spec:** `docs/superpowers/specs/2026-07-30-weather-and-stadium-attendance-design.md`

## Global Constraints

- **Determinism contract (non-negotiable):** `WeatherEffects.of` with `ENSOLARADO` and `temperatureC` in `[13, 28]` returns exactly `1.0` on all five fields. This is what keeps existing engine tests byte-identical. Assert it before writing any other weather test.
- Weather is **never** persisted. Attendance **is** (`fixtures.attendance`, nullable).
- Engines call `WeatherGenerator.forFixture` directly. The reveal rule (`WeatherService`) is presentation-only and must never influence simulation.
- Weather always rolls from the **home** club's `ClimateZone`.
- RNG seed for weather: `fixtureId.getMostSignificantBits() ^ fixtureId.getLeastSignificantBits()`, fresh `java.util.Random`.
- **4 climate zones**, not 5 — see the Task 1 note on the spec deviation.
- All new DTO fields nullable; every caller null-guards (back-compat with existing saves).
- All UI copy pt-BR. Numbers formatted pt-BR (`19.300`).
- New Java utility classes are `final` with a private constructor, no Spring, no RNG unless explicitly passed — matching `SetPieces` / `MatchMath` / `MatchupModifiers`.
- Verification: backend `cd backend && ./gradlew test` (use `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`; full suite ~10–12 min); frontend `cd frontend && npx tsc -b && npx vitest run` (`tsc --noEmit` is a no-op in this repo — never use it).
- Commit after each green step. **Never push.**

---

### Task 1: `ClimateZone` + `MonthProfile` + `climate.json`

**Spec deviation to flag:** the spec lists 5 zones including `NORDESTE_INTERIOR`, but mapping all 28 seeded clubs shows **no club** falls in it (the northeastern clubs — Sport, Ceará, Fortaleza, CRB, Náutico — are all coastal). YAGNI: ship 4 zones. Adding the fifth later is a `climate.json` entry plus an enum constant.

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/club/ClimateZone.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/club/MonthProfile.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/club/ClimateTable.java`
- Create: `backend/src/main/resources/seed/climate.json`
- Test: `backend/src/test/java/br/com/carvoeiromanager/club/ClimateTableTest.java`

**Interfaces:**
- Produces: `ClimateZone` enum (`SUL`, `SUDESTE`, `CENTRO_OESTE`, `NORDESTE_LITORAL`); `MonthProfile(int month, int minC, int maxC, double rainChance, double windChance)`; `ClimateTable.profileFor(ClimateZone zone, Month month)` (static, loads `climate.json` once into an immutable `EnumMap<ClimateZone, List<MonthProfile>>`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/club/ClimateTableTest.java`:

```java
package br.com.carvoeiromanager.club;

import org.junit.jupiter.api.Test;

import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

class ClimateTableTest {

    @Test
    void everyZoneDefinesAllTwelveMonths() {
        for (ClimateZone zone : ClimateZone.values()) {
            for (Month month : Month.values()) {
                MonthProfile profile = ClimateTable.profileFor(zone, month);
                assertThat(profile).as("%s/%s", zone, month).isNotNull();
                assertThat(profile.month()).isEqualTo(month.getValue());
                assertThat(profile.minC()).isLessThanOrEqualTo(profile.maxC());
                assertThat(profile.rainChance()).isBetween(0.0, 1.0);
                assertThat(profile.windChance()).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    void southernWinterIsColderThanNortheasternWinter() {
        MonthProfile sul = ClimateTable.profileFor(ClimateZone.SUL, Month.JULY);
        MonthProfile nordeste = ClimateTable.profileFor(ClimateZone.NORDESTE_LITORAL, Month.JULY);
        assertThat(sul.maxC()).isLessThan(nordeste.maxC());
        assertThat(sul.minC()).isLessThan(nordeste.minC());
    }

    @Test
    void northeasternCoastNeverGetsCold() {
        for (Month month : Month.values()) {
            assertThat(ClimateTable.profileFor(ClimateZone.NORDESTE_LITORAL, month).minC())
                    .as("%s", month)
                    .isGreaterThanOrEqualTo(18);
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ClimateTableTest*'
```

Expected: compilation failure — `ClimateZone`, `MonthProfile`, `ClimateTable` do not exist.

- [ ] **Step 3: Create the enum and the record**

`ClimateZone.java`:

```java
package br.com.carvoeiromanager.club;

import java.time.Month;

/**
 * Coarse Brazilian climate zone for a club's home city, driving match-day weather generation.
 * Four zones cover all seeded clubs; the monthly profiles live in {@code seed/climate.json} so
 * they are tunable without a recompile.
 */
public enum ClimateZone {
    SUL("Sul"),
    SUDESTE("Sudeste"),
    CENTRO_OESTE("Centro-Oeste"),
    NORDESTE_LITORAL("Litoral nordestino");

    private final String label;

    ClimateZone(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public MonthProfile profileFor(Month month) {
        return ClimateTable.profileFor(this, month);
    }
}
```

`MonthProfile.java`:

```java
package br.com.carvoeiromanager.club;

/**
 * One zone's climate normals for one calendar month. Temperatures are whole degrees Celsius;
 * chances are probabilities in [0, 1] evaluated in {@code WeatherGenerator}.
 */
public record MonthProfile(int month, int minC, int maxC, double rainChance, double windChance) {
}
```

- [ ] **Step 4: Create `climate.json`**

Create `backend/src/main/resources/seed/climate.json`. Values are coarse Brazilian normals — southern winters cold and wet, northeastern coast hot year-round with an autumn rainy season, centre-west with a sharp wet (Oct–Mar) / dry (May–Sep) split:

```json
{
  "zones": {
    "SUL": [
      { "month": 1,  "minC": 19, "maxC": 30, "rainChance": 0.34, "windChance": 0.08 },
      { "month": 2,  "minC": 19, "maxC": 29, "rainChance": 0.33, "windChance": 0.08 },
      { "month": 3,  "minC": 18, "maxC": 28, "rainChance": 0.30, "windChance": 0.09 },
      { "month": 4,  "minC": 15, "maxC": 25, "rainChance": 0.26, "windChance": 0.10 },
      { "month": 5,  "minC": 12, "maxC": 22, "rainChance": 0.26, "windChance": 0.12 },
      { "month": 6,  "minC": 10, "maxC": 20, "rainChance": 0.27, "windChance": 0.13 },
      { "month": 7,  "minC": 9,  "maxC": 19, "rainChance": 0.29, "windChance": 0.14 },
      { "month": 8,  "minC": 10, "maxC": 20, "rainChance": 0.30, "windChance": 0.15 },
      { "month": 9,  "minC": 12, "maxC": 21, "rainChance": 0.33, "windChance": 0.15 },
      { "month": 10, "minC": 14, "maxC": 24, "rainChance": 0.33, "windChance": 0.13 },
      { "month": 11, "minC": 16, "maxC": 26, "rainChance": 0.30, "windChance": 0.11 },
      { "month": 12, "minC": 18, "maxC": 28, "rainChance": 0.32, "windChance": 0.09 }
    ],
    "SUDESTE": [
      { "month": 1,  "minC": 20, "maxC": 31, "rainChance": 0.42, "windChance": 0.06 },
      { "month": 2,  "minC": 20, "maxC": 31, "rainChance": 0.38, "windChance": 0.06 },
      { "month": 3,  "minC": 19, "maxC": 30, "rainChance": 0.33, "windChance": 0.06 },
      { "month": 4,  "minC": 17, "maxC": 28, "rainChance": 0.20, "windChance": 0.07 },
      { "month": 5,  "minC": 14, "maxC": 26, "rainChance": 0.13, "windChance": 0.08 },
      { "month": 6,  "minC": 12, "maxC": 25, "rainChance": 0.09, "windChance": 0.08 },
      { "month": 7,  "minC": 12, "maxC": 25, "rainChance": 0.08, "windChance": 0.09 },
      { "month": 8,  "minC": 13, "maxC": 27, "rainChance": 0.08, "windChance": 0.10 },
      { "month": 9,  "minC": 15, "maxC": 28, "rainChance": 0.15, "windChance": 0.11 },
      { "month": 10, "minC": 17, "maxC": 29, "rainChance": 0.26, "windChance": 0.09 },
      { "month": 11, "minC": 18, "maxC": 30, "rainChance": 0.35, "windChance": 0.07 },
      { "month": 12, "minC": 19, "maxC": 30, "rainChance": 0.42, "windChance": 0.06 }
    ],
    "CENTRO_OESTE": [
      { "month": 1,  "minC": 22, "maxC": 32, "rainChance": 0.45, "windChance": 0.05 },
      { "month": 2,  "minC": 22, "maxC": 32, "rainChance": 0.42, "windChance": 0.05 },
      { "month": 3,  "minC": 22, "maxC": 32, "rainChance": 0.40, "windChance": 0.05 },
      { "month": 4,  "minC": 21, "maxC": 32, "rainChance": 0.22, "windChance": 0.06 },
      { "month": 5,  "minC": 19, "maxC": 31, "rainChance": 0.10, "windChance": 0.07 },
      { "month": 6,  "minC": 18, "maxC": 31, "rainChance": 0.04, "windChance": 0.07 },
      { "month": 7,  "minC": 17, "maxC": 32, "rainChance": 0.03, "windChance": 0.08 },
      { "month": 8,  "minC": 19, "maxC": 34, "rainChance": 0.05, "windChance": 0.09 },
      { "month": 9,  "minC": 21, "maxC": 35, "rainChance": 0.15, "windChance": 0.09 },
      { "month": 10, "minC": 22, "maxC": 34, "rainChance": 0.32, "windChance": 0.07 },
      { "month": 11, "minC": 22, "maxC": 33, "rainChance": 0.40, "windChance": 0.06 },
      { "month": 12, "minC": 22, "maxC": 32, "rainChance": 0.45, "windChance": 0.05 }
    ],
    "NORDESTE_LITORAL": [
      { "month": 1,  "minC": 24, "maxC": 32, "rainChance": 0.20, "windChance": 0.12 },
      { "month": 2,  "minC": 24, "maxC": 32, "rainChance": 0.25, "windChance": 0.12 },
      { "month": 3,  "minC": 24, "maxC": 31, "rainChance": 0.35, "windChance": 0.11 },
      { "month": 4,  "minC": 23, "maxC": 30, "rainChance": 0.42, "windChance": 0.11 },
      { "month": 5,  "minC": 23, "maxC": 30, "rainChance": 0.45, "windChance": 0.12 },
      { "month": 6,  "minC": 22, "maxC": 29, "rainChance": 0.45, "windChance": 0.13 },
      { "month": 7,  "minC": 22, "maxC": 29, "rainChance": 0.40, "windChance": 0.14 },
      { "month": 8,  "minC": 22, "maxC": 29, "rainChance": 0.28, "windChance": 0.15 },
      { "month": 9,  "minC": 23, "maxC": 30, "rainChance": 0.18, "windChance": 0.15 },
      { "month": 10, "minC": 23, "maxC": 31, "rainChance": 0.12, "windChance": 0.14 },
      { "month": 11, "minC": 24, "maxC": 31, "rainChance": 0.10, "windChance": 0.13 },
      { "month": 12, "minC": 24, "maxC": 32, "rainChance": 0.14, "windChance": 0.12 }
    ]
  }
}
```

- [ ] **Step 5: Create the loader**

`ClimateTable.java`:

```java
package br.com.carvoeiromanager.club;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Month;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, eagerly-loaded climate normals read once from {@code seed/climate.json}.
 * Static because the table is game-wide reference data, not per-save state — the same
 * shape as the other static match utilities. A missing or malformed table is a
 * programming error and fails fast at class-init.
 */
public final class ClimateTable {

    private static final Map<ClimateZone, List<MonthProfile>> ZONES = load();

    private ClimateTable() {
    }

    public static MonthProfile profileFor(ClimateZone zone, Month month) {
        return ZONES.get(zone).get(month.getValue() - 1);
    }

    private record ClimateFile(Map<String, List<MonthProfile>> zones) {
    }

    private static Map<ClimateZone, List<MonthProfile>> load() {
        try (InputStream in = ClimateTable.class.getResourceAsStream("/seed/climate.json")) {
            if (in == null) {
                throw new IllegalStateException("seed/climate.json not found on the classpath");
            }
            ClimateFile file = new ObjectMapper().readValue(in, ClimateFile.class);
            Map<ClimateZone, List<MonthProfile>> byZone = new EnumMap<>(ClimateZone.class);
            for (ClimateZone zone : ClimateZone.values()) {
                List<MonthProfile> months = file.zones().get(zone.name());
                if (months == null || months.size() != 12) {
                    throw new IllegalStateException(
                            "climate.json must define 12 months for zone " + zone);
                }
                List<MonthProfile> sorted = months.stream()
                        .sorted((a, b) -> Integer.compare(a.month(), b.month()))
                        .toList();
                for (int i = 0; i < 12; i++) {
                    if (sorted.get(i).month() != i + 1) {
                        throw new IllegalStateException(
                                "climate.json zone " + zone + " is missing month " + (i + 1));
                    }
                }
                byZone.put(zone, sorted);
            }
            return Map.copyOf(byZone);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed/climate.json", e);
        }
    }
}
```

- [ ] **Step 6: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ClimateTableTest*'
```

Expected: 3 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/club/ClimateZone.java backend/src/main/java/br/com/carvoeiromanager/club/MonthProfile.java backend/src/main/java/br/com/carvoeiromanager/club/ClimateTable.java backend/src/main/resources/seed/climate.json backend/src/test/java/br/com/carvoeiromanager/club/ClimateTableTest.java
git commit -m "feat(club): ClimateZone + monthly climate normals table"
```

---

### Task 2: `V45` — club geography, real capacities, fixture attendance column

**Files:**
- Create: `backend/src/main/resources/db/migration/V45__weather_and_stadiums.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/club/Club.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedClub.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java:114-120`
- Modify: `backend/src/main/resources/seed/clubs.json`
- Test: `backend/src/test/java/br/com/carvoeiromanager/club/ClubGeographyIT.java`

**Interfaces:**
- Consumes: `ClimateZone` (Task 1).
- Produces: `Club.getCity()`, `Club.getState()`, `Club.getClimateZone()` (returns `ClimateZone`, never null — defaults `SUDESTE`), `Club.resolvedLocation()` (returns `"Criciúma/SC"` or `null`); `Fixture.getAttendance()` (`Integer`, nullable), `Fixture.setAttendance(Integer)`; `SeedClub.city()`, `SeedClub.state()`, `SeedClub.climateZone()`, `SeedClub.stadiumCapacity()`.

**Researched club data.** Verify each capacity against a current source while implementing; these are the researched baseline and the values the migration must contain:

| shortName | City | UF | Zone | Capacity |
|---|---|---|---|---|
| CRI | Criciúma | SC | SUL | 19225 |
| AVA | Florianópolis | SC | SUL | 17800 |
| FIG | Florianópolis | SC | SUL | 19584 |
| CHA | Chapecó | SC | SUL | 20089 |
| BRU | Brusque | SC | SUL | 5000 |
| MAR | Itajaí | SC | SUL | 5000 |
| BAR | Balneário Camboriú | SC | SUL | 3000 |
| CON | Concórdia | SC | SUL | 4000 |
| HER | Tubarão | SC | SUL | 7000 |
| CAM | Camboriú | SC | SUL | 4000 |
| OPE | Ponta Grossa | PR | SUL | 10000 |
| LON | Londrina | PR | SUL | 30000 |
| JUV | Caxias do Sul | RS | SUL | 19924 |
| NOV | Novo Horizonte | SP | SUDESTE | 15000 |
| PPT | Campinas | SP | SUDESTE | 19722 |
| BOT | Ribeirão Preto | SP | SUDESTE | 16000 |
| SBE | São Bernardo do Campo | SP | SUDESTE | 15000 |
| AME | Belo Horizonte | MG | SUDESTE | 23018 |
| ATH | São João del-Rei | MG | SUDESTE | 5000 |
| GOI | Goiânia | GO | CENTRO_OESTE | 14450 |
| VIL | Goiânia | GO | CENTRO_OESTE | 13500 |
| ATG | Goiânia | GO | CENTRO_OESTE | 12500 |
| CUI | Cuiabá | MT | CENTRO_OESTE | 44097 |
| SPT | Recife | PE | NORDESTE_LITORAL | 26000 |
| NAU | Recife | PE | NORDESTE_LITORAL | 22000 |
| CEA | Fortaleza | CE | NORDESTE_LITORAL | 20000 |
| FOR | Fortaleza | CE | NORDESTE_LITORAL | 63903 |
| CRB | Maceió | AL | NORDESTE_LITORAL | 17000 |

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/club/ClubGeographyIT.java`. This is the direct sibling of the existing `StadiumNameIT` (the V33 equivalent) — same annotations, same `createCareerClubs()` helper. There is **no** shared IT base class in this repo; each IT carries its own `@SpringBootTest` / `@Import(PostgresTestContainer.class)` trio.

```java
package br.com.carvoeiromanager.club;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers V45 end to end: a freshly created career gives every club a real city, state,
 * climate zone, and researched stadium capacity, replacing the derived 8000 + reputation*500.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubGeographyIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;

    private List<Club> createCareerClubs() {
        User user = User.create("geo_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return clubRepository.findBySaveGameId(save.id());
    }

    @Test
    void everySeededClubHasGeographyAndARealCapacity() {
        List<Club> clubs = createCareerClubs();

        assertThat(clubs).hasSize(28);
        assertThat(clubs).allSatisfy(club -> {
            assertThat(club.getCity()).as("city of %s", club.getName()).isNotBlank();
            assertThat(club.getState()).as("state of %s", club.getName()).hasSize(2);
            assertThat(club.getClimateZone()).as("zone of %s", club.getName()).isNotNull();
            assertThat(club.getStadiumCapacity()).as("capacity of %s", club.getName())
                    .isBetween(2000, 80000);
        });
    }

    @Test
    void capacitiesAreRealRatherThanDerived() {
        List<Club> clubs = createCareerClubs();

        assertThat(byShortName(clubs, "FOR").getStadiumCapacity()).isEqualTo(63903);
        assertThat(byShortName(clubs, "BAR").getStadiumCapacity()).isEqualTo(3000);
        // The old derived formula (8000 + reputation*500) could never produce a sub-8000 capacity.
        assertThat(byShortName(clubs, "BAR").getStadiumCapacity()).isLessThan(8000);
    }

    @Test
    void resolvedLocationFormatsCityAndState() {
        assertThat(byShortName(createCareerClubs(), "CRI").resolvedLocation())
                .isEqualTo("Criciúma/SC");
    }

    private static Club byShortName(List<Club> clubs, String shortName) {
        return clubs.stream()
                .filter(c -> c.getShortName().equals(shortName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no club " + shortName));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ClubGeographyIT*'
```

Expected: compilation failure — `Club.getCity()` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V45__weather_and_stadiums.sql`:

```sql
-- Club geography (drives match-day weather generation) + real stadium capacities,
-- replacing the derived 8000 + reputation*500. Backfilled by club name, following the
-- V33__stadium_name.sql / V43__research_backfill.sql precedent.
ALTER TABLE clubs ADD COLUMN city VARCHAR(60);
ALTER TABLE clubs ADD COLUMN state CHAR(2);
ALTER TABLE clubs ADD COLUMN climate_zone VARCHAR(20);

UPDATE clubs SET city = 'Criciúma',              state = 'SC', climate_zone = 'SUL',              stadium_capacity = 19225 WHERE name = 'Criciúma Esporte Clube';
UPDATE clubs SET city = 'Florianópolis',         state = 'SC', climate_zone = 'SUL',              stadium_capacity = 17800 WHERE name = 'Avaí Futebol Clube';
UPDATE clubs SET city = 'Florianópolis',         state = 'SC', climate_zone = 'SUL',              stadium_capacity = 19584 WHERE name = 'Figueirense Futebol Clube';
UPDATE clubs SET city = 'Chapecó',               state = 'SC', climate_zone = 'SUL',              stadium_capacity = 20089 WHERE name = 'Associação Chapecoense de Futebol';
UPDATE clubs SET city = 'Brusque',               state = 'SC', climate_zone = 'SUL',              stadium_capacity =  5000 WHERE name = 'Brusque Futebol Clube';
UPDATE clubs SET city = 'Itajaí',                state = 'SC', climate_zone = 'SUL',              stadium_capacity =  5000 WHERE name = 'Clube Náutico Marcílio Dias';
UPDATE clubs SET city = 'Balneário Camboriú',    state = 'SC', climate_zone = 'SUL',              stadium_capacity =  3000 WHERE name = 'Associação Desportiva Barra';
UPDATE clubs SET city = 'Concórdia',             state = 'SC', climate_zone = 'SUL',              stadium_capacity =  4000 WHERE name = 'Associação Esportiva Concórdia';
UPDATE clubs SET city = 'Tubarão',               state = 'SC', climate_zone = 'SUL',              stadium_capacity =  7000 WHERE name = 'Hercílio Luz Futebol Clube';
UPDATE clubs SET city = 'Camboriú',              state = 'SC', climate_zone = 'SUL',              stadium_capacity =  4000 WHERE name = 'Camboriú Futebol Clube';
UPDATE clubs SET city = 'Ponta Grossa',          state = 'PR', climate_zone = 'SUL',              stadium_capacity = 10000 WHERE name = 'Operário Ferroviário Esporte Clube';
UPDATE clubs SET city = 'Londrina',              state = 'PR', climate_zone = 'SUL',              stadium_capacity = 30000 WHERE name = 'Londrina Esporte Clube';
UPDATE clubs SET city = 'Caxias do Sul',         state = 'RS', climate_zone = 'SUL',              stadium_capacity = 19924 WHERE name = 'Esporte Clube Juventude';
UPDATE clubs SET city = 'Novo Horizonte',        state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 15000 WHERE name = 'Grêmio Novorizontino';
UPDATE clubs SET city = 'Campinas',              state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 19722 WHERE name = 'Ponte Preta Futebol Clube';
UPDATE clubs SET city = 'Ribeirão Preto',        state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 16000 WHERE name = 'Botafogo Futebol Clube (SP)';
UPDATE clubs SET city = 'São Bernardo do Campo', state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 15000 WHERE name = 'São Bernardo Futebol Clube';
UPDATE clubs SET city = 'Belo Horizonte',        state = 'MG', climate_zone = 'SUDESTE',          stadium_capacity = 23018 WHERE name = 'América Futebol Clube';
UPDATE clubs SET city = 'São João del-Rei',      state = 'MG', climate_zone = 'SUDESTE',          stadium_capacity =  5000 WHERE name = 'Athletic Club';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 14450 WHERE name = 'Goiás Esporte Clube';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 13500 WHERE name = 'Vila Nova Futebol Clube';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 12500 WHERE name = 'Atlético Clube Goianiense';
UPDATE clubs SET city = 'Cuiabá',                state = 'MT', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 44097 WHERE name = 'Cuiabá Esporte Clube';
UPDATE clubs SET city = 'Recife',                state = 'PE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 26000 WHERE name = 'Sport Club do Recife';
UPDATE clubs SET city = 'Recife',                state = 'PE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 22000 WHERE name = 'Clube Náutico Capibaribe';
UPDATE clubs SET city = 'Fortaleza',             state = 'CE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 20000 WHERE name = 'Ceará Sporting Club';
UPDATE clubs SET city = 'Fortaleza',             state = 'CE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 63903 WHERE name = 'Fortaleza Esporte Clube';
UPDATE clubs SET city = 'Maceió',                state = 'AL', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 17000 WHERE name = 'Clube de Regatas Brasil';

-- Defensive: any club row not matched above (the seed is closed, so this should match zero rows)
-- keeps its derived capacity and gets the neutral zone, so weather generation never sees a null.
UPDATE clubs SET climate_zone = 'SUDESTE' WHERE climate_zone IS NULL;
ALTER TABLE clubs ALTER COLUMN climate_zone SET NOT NULL;

-- Realized crowd for a played fixture. Nullable: unplayed fixtures and every fixture in
-- existing saves have none, and all callers null-guard.
ALTER TABLE fixtures ADD COLUMN attendance INT;
```

- [ ] **Step 4: Add the entity fields**

In `Club.java`, after the `stadiumName` field:

```java
    @Column(name = "city", length = 60)
    private String city;

    @Column(name = "state", length = 2)
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "climate_zone", nullable = false, length = 20)
    private ClimateZone climateZone = ClimateZone.SUDESTE;
```

Add the imports `jakarta.persistence.Enumerated` and `jakarta.persistence.EnumType`. Then, next to `resolvedStadiumName()`:

```java
    public String getCity() { return city; }
    public String getState() { return state; }
    public ClimateZone getClimateZone() { return climateZone; }

    /** Display location for match and club screens: {@code "Criciúma/SC"}, or null when unseeded. */
    public String resolvedLocation() {
        return city != null && state != null ? city + "/" + state : null;
    }

    public void setCity(String city) { this.city = city; }
    public void setState(String state) { this.state = state; }
    public void setClimateZone(ClimateZone climateZone) { this.climateZone = climateZone; }
```

In `Fixture.java`, after the `matchDate` field:

```java
    @Column(name = "attendance")
    private Integer attendance;
```

and with the other accessors:

```java
    public Integer getAttendance() { return attendance; }
    public void setAttendance(Integer attendance) { this.attendance = attendance; }
```

- [ ] **Step 5: Extend the seed record and its apply site**

In `SeedClub.java`, add four components after `stadium`:

```java
        String stadium,
        String city,
        String state,
        String climateZone,
        Integer stadiumCapacity,
```

In `SaveGameService.java`, right after the existing `club.setStadiumName(seedClub.stadium());` at line 118:

```java
            club.setCity(seedClub.city());
            club.setState(seedClub.state());
            if (seedClub.climateZone() != null) {
                club.setClimateZone(ClimateZone.valueOf(seedClub.climateZone()));
            }
```

Import `br.com.carvoeiromanager.club.ClimateZone`.

Then find where `stadiumCapacity` is currently assigned in `SaveGameService` (it calls `financeCalculator.stadiumCapacity(reputation)`) and make the seed value win when present:

```java
            club.setStadiumCapacity(seedClub.stadiumCapacity() != null
                    ? seedClub.stadiumCapacity()
                    : financeCalculator.stadiumCapacity(reputation));
```

- [ ] **Step 6: Add the four fields to all 28 clubs in `clubs.json`**

For each entry in `clubPool`, add `city`, `state`, `climateZone`, and `stadiumCapacity` using the table above, matched by `shortName`. Example for the first entry:

```json
{
  "name": "Criciúma Esporte Clube",
  "shortName": "CRI",
  "commonName": "Criciúma",
  "nickname": "Tigre",
  "stadium": "Estádio Heriberto Hülse",
  "city": "Criciúma",
  "state": "SC",
  "climateZone": "SUL",
  "stadiumCapacity": 19225,
  "primaryColor": "#f7d417",
  ...
}
```

- [ ] **Step 7: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*ClubGeographyIT*'
```

Expected: 3 tests PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V45__weather_and_stadiums.sql backend/src/main/java/br/com/carvoeiromanager/club/Club.java backend/src/main/java/br/com/carvoeiromanager/competition/Fixture.java backend/src/main/java/br/com/carvoeiromanager/seed/SeedClub.java backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java backend/src/main/resources/seed/clubs.json backend/src/test/java/br/com/carvoeiromanager/club/ClubGeographyIT.java
git commit -m "feat(db): V45 club geography, real stadium capacities, fixture attendance"
```

---

### Task 3: `Weather` + `WeatherGenerator`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/WeatherCondition.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/Weather.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/WeatherGenerator.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/WeatherGeneratorTest.java`

**Interfaces:**
- Consumes: `ClimateZone`, `MonthProfile` (Task 1).
- Produces: `WeatherCondition` enum with `label()` and `wetness()`; `Weather(WeatherCondition condition, int temperatureC)`; `WeatherGenerator.forFixture(UUID fixtureId, ClimateZone zone, LocalDate matchDate)` returning `Weather`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/WeatherGeneratorTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherGeneratorTest {

    private static final UUID FIXTURE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * A well-spread synthetic fixture id. Sequential UUIDs are degenerate for this generator:
     * {@code new UUID(i, i)} XORs to a constant 0 seed, and small sequential seeds give
     * java.util.Random a badly correlated first draw (for {@code new UUID(i, 7L)} it never falls
     * below 0.58, so a 0.45 rain threshold never fires). Real fixture ids come from
     * UUID.randomUUID(), so tests must model that spread.
     */
    private static UUID fixtureId(long i) {
        return new UUID(i * 0x9E3779B97F4A7C15L, i * 0xC2B2AE3D27D4EB4FL);
    }

    @Test
    void sameFixtureAlwaysYieldsTheSameWeather() {
        LocalDate date = LocalDate.of(2026, 3, 14);
        Weather first = WeatherGenerator.forFixture(FIXTURE, ClimateZone.SUL, date);

        for (int i = 0; i < 1000; i++) {
            assertThat(WeatherGenerator.forFixture(FIXTURE, ClimateZone.SUL, date)).isEqualTo(first);
        }
    }

    @Test
    void differentFixturesDisagreeAtLeastSometimes() {
        LocalDate date = LocalDate.of(2026, 3, 14);
        long distinct = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> new UUID(i, i * 31L))
                .map(id -> WeatherGenerator.forFixture(id, ClimateZone.SUL, date))
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(5);
    }

    @Test
    void southernWinterIsNeverTropical() {
        LocalDate july = LocalDate.of(2026, 7, 12);
        for (int i = 0; i < 500; i++) {
            Weather weather = WeatherGenerator.forFixture(new UUID(i, i), ClimateZone.SUL, july);
            assertThat(weather.temperatureC()).isLessThanOrEqualTo(19);
        }
    }

    @Test
    void northeasternCoastIsNeverCold() {
        for (int month = 1; month <= 12; month++) {
            LocalDate date = LocalDate.of(2026, month, 10);
            for (int i = 0; i < 200; i++) {
                Weather weather = WeatherGenerator.forFixture(
                        new UUID(i, month), ClimateZone.NORDESTE_LITORAL, date);
                assertThat(weather.temperatureC()).as("month %d", month).isGreaterThanOrEqualTo(15);
            }
        }
    }

    @Test
    void rainProducesHeavyRainSometimesButNotAlways() {
        LocalDate may = LocalDate.of(2026, 5, 10);
        long heavy = java.util.stream.IntStream.range(0, 2000)
                .mapToObj(i -> new UUID(i, 7L))
                .map(id -> WeatherGenerator.forFixture(id, ClimateZone.NORDESTE_LITORAL, may))
                .filter(w -> w.condition() == WeatherCondition.CHUVA_FORTE)
                .count();
        assertThat(heavy).isBetween(50L, 500L);
    }

    @Test
    void wetnessIsOrdered() {
        assertThat(WeatherCondition.ENSOLARADO.wetness()).isEqualTo(0.0);
        assertThat(WeatherCondition.CHUVA.wetness()).isEqualTo(0.5);
        assertThat(WeatherCondition.CHUVA_FORTE.wetness()).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherGeneratorTest*'
```

Expected: compilation failure — `Weather` does not exist.

- [ ] **Step 3: Create the condition enum**

`WeatherCondition.java`:

```java
package br.com.carvoeiromanager.match;

/** Match-day sky condition. {@code wetness} is the pitch-wetness the engine reads. */
public enum WeatherCondition {
    ENSOLARADO("Ensolarado", 0.0),
    NUBLADO("Nublado", 0.0),
    VENTANIA("Ventania", 0.0),
    CHUVA("Chuva", 0.5),
    CHUVA_FORTE("Chuva forte", 1.0);

    private final String label;
    private final double wetness;

    WeatherCondition(String label, double wetness) {
        this.label = label;
        this.wetness = wetness;
    }

    public String label() { return label; }

    /** 0.0 dry, 0.5 light rain, 1.0 heavy rain. */
    public double wetness() { return wetness; }
}
```

- [ ] **Step 4: Create the value and the generator**

`Weather.java`:

```java
package br.com.carvoeiromanager.match;

/** The weather one fixture is played in. Derived, never persisted. */
public record Weather(WeatherCondition condition, int temperatureC) {

    /** The neutral weather: guaranteed by {@code WeatherEffects} to be a mechanical no-op. */
    public static Weather neutral() {
        return new Weather(WeatherCondition.ENSOLARADO, 20);
    }
}
```

`WeatherGenerator.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.club.MonthProfile;

import java.time.LocalDate;
import java.util.Random;
import java.util.UUID;

/**
 * Derives a fixture's weather from the home club's climate zone and the month of the match.
 * Pure and deterministic: the RNG is seeded from the fixture id, so the same fixture yields
 * identical weather in every process and after every reload. Weather is therefore never stored —
 * the engines, the eve-of-match news, and the UI all recompute the same answer.
 *
 * <p>Draw order is fixed for reproducibility: rain, heavy-rain escalation, wind, cloud, temperature.
 */
public final class WeatherGenerator {

    /** Share of rainy matches that escalate to CHUVA_FORTE. */
    private static final double HEAVY_RAIN_SHARE = 1.0 / 3.0;
    /** Share of dry, windless matches that are merely cloudy rather than sunny. */
    private static final double CLOUD_SHARE = 0.40;
    /** Rain cools the air. */
    private static final int RAIN_TEMPERATURE_DROP = 3;

    private WeatherGenerator() {
    }

    public static Weather forFixture(UUID fixtureId, ClimateZone zone, LocalDate matchDate) {
        MonthProfile profile = zone.profileFor(matchDate.getMonth());
        Random rng = new Random(
                fixtureId.getMostSignificantBits() ^ fixtureId.getLeastSignificantBits());

        boolean raining = rng.nextDouble() < profile.rainChance();
        boolean heavy = raining && rng.nextDouble() < HEAVY_RAIN_SHARE;
        boolean windy = !raining && rng.nextDouble() < profile.windChance();
        boolean cloudy = !raining && !windy && rng.nextDouble() < CLOUD_SHARE;

        WeatherCondition condition;
        if (heavy) {
            condition = WeatherCondition.CHUVA_FORTE;
        } else if (raining) {
            condition = WeatherCondition.CHUVA;
        } else if (windy) {
            condition = WeatherCondition.VENTANIA;
        } else if (cloudy) {
            condition = WeatherCondition.NUBLADO;
        } else {
            condition = WeatherCondition.ENSOLARADO;
        }

        int span = profile.maxC() - profile.minC();
        int temperature = profile.minC() + (span > 0 ? rng.nextInt(span + 1) : 0);
        if (raining) {
            temperature -= RAIN_TEMPERATURE_DROP;
        }

        return new Weather(condition, temperature);
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherGeneratorTest*'
```

Expected: 6 tests PASS. If `southernWinterIsNeverTropical` fails, the `SUL` July `maxC` in `climate.json` is above 19 — fix the data, not the test.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/WeatherCondition.java backend/src/main/java/br/com/carvoeiromanager/match/Weather.java backend/src/main/java/br/com/carvoeiromanager/match/WeatherGenerator.java backend/src/test/java/br/com/carvoeiromanager/match/WeatherGeneratorTest.java
git commit -m "feat(match): deterministic id-seeded weather generation"
```

---

### Task 4: `WeatherEffects`

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/WeatherEffects.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/WeatherEffectsTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherCondition` (Task 3), `TeamMatchInput` (existing).
- Produces: `WeatherEffects.Impact(double homeChanceMult, double awayChanceMult, double homeDecayMult, double awayDecayMult, double setPieceMult)`; `WeatherEffects.of(Weather, TeamMatchInput home, TeamMatchInput away)`.

- [ ] **Step 1: Write the failing test — neutrality contract first**

Create `backend/src/test/java/br/com/carvoeiromanager/match/WeatherEffectsTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Note on assertion style: the neutrality contract uses exact {@code isEqualTo(1.0)} — that
 * exactness is the whole point, since it is what keeps the engines byte-identical. Every other
 * assertion compares a *computed* double against a *literal* one (e.g. {@code 1 - 0.06 * 1.0}
 * versus {@code 0.94}), which differ by an ulp in IEEE-754, so those use a tolerance.
 */
class WeatherEffectsTest {

    private static final double EPSILON = 1e-12;

    private static TeamMatchInput team(GameStyle style) {
        return new TeamMatchInput(70.0, 68.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.20,
                Mentality.BALANCED, style);
    }

    private static final TeamMatchInput NEUTRAL_TEAM = team(GameStyle.EQUILIBRADO);

    @Test
    void sunnyAndMildIsExactlyANoOp() {
        for (int t = 13; t <= 28; t++) {
            WeatherEffects.Impact impact = WeatherEffects.of(
                    new Weather(WeatherCondition.ENSOLARADO, t), NEUTRAL_TEAM, NEUTRAL_TEAM);
            assertThat(impact.homeChanceMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.awayChanceMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.homeDecayMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.awayDecayMult()).as("t=%d", t).isEqualTo(1.0);
            assertThat(impact.setPieceMult()).as("t=%d", t).isEqualTo(1.0);
        }
    }

    @Test
    void neutralWeatherHelperIsANoOp() {
        WeatherEffects.Impact impact =
                WeatherEffects.of(Weather.neutral(), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeChanceMult()).isEqualTo(1.0);
        assertThat(impact.setPieceMult()).isEqualTo(1.0);
    }

    @Test
    void rainSuppressesScoringForBothSides() {
        WeatherEffects.Impact light = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);
        WeatherEffects.Impact heavy = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA_FORTE, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);

        assertThat(light.homeChanceMult()).isCloseTo(0.97, within(EPSILON));
        assertThat(light.awayChanceMult()).isCloseTo(0.97, within(EPSILON));
        assertThat(heavy.homeChanceMult()).isCloseTo(0.93, within(EPSILON));
        assertThat(heavy.awayChanceMult()).isCloseTo(0.93, within(EPSILON));
    }

    @Test
    void heavyRainPunishesPassingAndRewardsDirectPlay() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA_FORTE, 20),
                team(GameStyle.JOGO_DE_PASSES),
                team(GameStyle.JOGO_DIRETO));

        // wetness 1.0: passing x(1 - 0.06), direct x(1 + 0.04), both on top of the 0.93 wet-pitch mult
        assertThat(impact.homeChanceMult()).isCloseTo(0.93 * 0.94, within(EPSILON));
        assertThat(impact.awayChanceMult()).isCloseTo(0.93 * 1.04, within(EPSILON));
        assertThat(impact.homeChanceMult()).isLessThan(impact.awayChanceMult());
    }

    @Test
    void lightRainAppliesHalfTheStyleEffect() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.CHUVA, 20),
                team(GameStyle.JOGO_DE_PASSES),
                NEUTRAL_TEAM);

        assertThat(impact.homeChanceMult()).isCloseTo(0.97 * 0.97, within(EPSILON));
    }

    @Test
    void heatAmplifiesFatigueAndHitsHighPressingHardest() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 33),
                team(GameStyle.PRESSAO_ALTA),
                NEUTRAL_TEAM);

        // T=33 -> base bonus 0.02*5 = 0.10; the pressing side pays 1.5x that bonus
        assertThat(impact.awayDecayMult()).isCloseTo(1.10, within(EPSILON));
        assertThat(impact.homeDecayMult()).isCloseTo(1.15, within(EPSILON));
    }

    @Test
    void heatDecayIsCapped() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 45), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeDecayMult()).isCloseTo(1.20, within(EPSILON));
    }

    @Test
    void coldReducesFatigue() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.ENSOLARADO, 8), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.homeDecayMult()).isCloseTo(0.95, within(EPSILON));
        assertThat(impact.awayDecayMult()).isCloseTo(0.95, within(EPSILON));
    }

    @Test
    void windBluntsSetPiecesAndNudgesScoringDown() {
        WeatherEffects.Impact impact = WeatherEffects.of(
                new Weather(WeatherCondition.VENTANIA, 20), NEUTRAL_TEAM, NEUTRAL_TEAM);
        assertThat(impact.setPieceMult()).isCloseTo(0.85, within(EPSILON));
        assertThat(impact.homeChanceMult()).isCloseTo(0.98, within(EPSILON));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherEffectsTest*'
```

Expected: compilation failure — `WeatherEffects` does not exist.

- [ ] **Step 3: Implement**

`WeatherEffects.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;

/**
 * Pure, RNG-free environmental modifiers applied to both sides of a match, mirroring the
 * {@link MatchupModifiers} / {@link SetPieces} static-utility pattern. Where
 * {@code MatchupModifiers} answers "does my mentality beat theirs", this answers "what is the
 * pitch doing to both of us".
 *
 * <p><b>Determinism contract:</b> {@link WeatherCondition#ENSOLARADO} at a mild temperature
 * ({@value #MILD_MIN}–{@value #MILD_MAX}°C) returns exactly {@code 1.0} on every field, so a
 * neutral match is byte-identical to the pre-weather engine. Same promise as
 * {@link GameStyle#EQUILIBRADO}.
 */
public final class WeatherEffects {

    static final int MILD_MIN = 13;
    static final int MILD_MAX = 28;

    /** Wet-pitch scoring multiplier, applied to both sides. */
    private static final double LIGHT_RAIN_CHANCE = 0.97;
    private static final double HEAVY_RAIN_CHANCE = 0.93;
    private static final double WIND_CHANCE = 0.98;

    /** Per-side style response to a wet pitch, scaled by wetness. */
    private static final double PASSING_RAIN_PENALTY = 0.06;
    private static final double DIRECT_RAIN_BONUS = 0.04;

    /** Fatigue response to temperature. */
    private static final double HEAT_PER_DEGREE = 0.02;
    private static final double HEAT_DECAY_CAP = 1.20;
    private static final double PRESSING_HEAT_FACTOR = 1.5;
    private static final int COLD_THRESHOLD = 12;
    private static final double COLD_DECAY = 0.95;

    /** Wind's effect on corner and free-kick threat. */
    private static final double WIND_SET_PIECE = 0.85;

    private WeatherEffects() {
    }

    /**
     * Per-side chance and fatigue multipliers plus a shared set-piece multiplier.
     * {@code homeDecayMult}/{@code awayDecayMult} scale that side's {@code fitnessDecay};
     * {@code setPieceMult} scales both sides' corner and free-kick threat.
     */
    public record Impact(double homeChanceMult, double awayChanceMult,
                         double homeDecayMult, double awayDecayMult,
                         double setPieceMult) {
    }

    public static Impact of(Weather weather, TeamMatchInput home, TeamMatchInput away) {
        double wetPitch = switch (weather.condition()) {
            case CHUVA -> LIGHT_RAIN_CHANCE;
            case CHUVA_FORTE -> HEAVY_RAIN_CHANCE;
            case VENTANIA -> WIND_CHANCE;
            case ENSOLARADO, NUBLADO -> 1.0;
        };

        double wetness = weather.condition().wetness();
        double homeChance = wetPitch * styleResponse(home.gameStyle(), wetness);
        double awayChance = wetPitch * styleResponse(away.gameStyle(), wetness);

        double homeDecay = decayMult(weather.temperatureC(), home.gameStyle());
        double awayDecay = decayMult(weather.temperatureC(), away.gameStyle());

        double setPiece = weather.condition() == WeatherCondition.VENTANIA ? WIND_SET_PIECE : 1.0;

        return new Impact(homeChance, awayChance, homeDecay, awayDecay, setPiece);
    }

    /** A wet pitch drags on a passing game and suits a direct one. Other styles are unaffected. */
    private static double styleResponse(GameStyle style, double wetness) {
        if (wetness == 0.0) {
            return 1.0;
        }
        return switch (style) {
            case JOGO_DE_PASSES -> 1 - PASSING_RAIN_PENALTY * wetness;
            case JOGO_DIRETO -> 1 + DIRECT_RAIN_BONUS * wetness;
            default -> 1.0;
        };
    }

    /**
     * Heat above {@value #MILD_MAX}°C accelerates fatigue, and a high-pressing side pays
     * {@value #PRESSING_HEAT_FACTOR}x the penalty — the tactical bite of a hot afternoon.
     * Cold at or below {@value #COLD_THRESHOLD}°C mildly reduces it.
     */
    private static double decayMult(int temperatureC, GameStyle style) {
        if (temperatureC <= COLD_THRESHOLD) {
            return COLD_DECAY;
        }
        if (temperatureC <= MILD_MAX) {
            return 1.0;
        }
        double bonus = HEAT_PER_DEGREE * (temperatureC - MILD_MAX);
        if (style == GameStyle.PRESSAO_ALTA) {
            bonus *= PRESSING_HEAT_FACTOR;
        }
        return Math.min(HEAT_DECAY_CAP, 1.0 + bonus);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherEffectsTest*'
```

Expected: 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/WeatherEffects.java backend/src/test/java/br/com/carvoeiromanager/match/WeatherEffectsTest.java
git commit -m "feat(match): WeatherEffects — pitch, heat, and wind modifiers"
```

---

### Task 5: Wire weather into both engines

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java:29-52`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:110-140`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java:195-210`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/WeatherEngineIntegrationTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherEffects`, `WeatherGenerator` (Tasks 3–4); `ClimateZone` via `Club.getClimateZone()` (Task 2).
- Produces: `MatchSimulationService.simulate(TeamMatchInput home, TeamMatchInput away, Weather weather, long seed)` — note the new parameter sits **before** the seed.

**Critical:** every existing caller and test that calls the old 3-arg `simulate` must pass `Weather.neutral()` to stay byte-identical. Do not delete the old signature and leave callers guessing — update them all in this task.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/WeatherEngineIntegrationTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherEngineIntegrationTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    private static TeamMatchInput team(GameStyle style) {
        return new TeamMatchInput(72.0, 70.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.20,
                Mentality.BALANCED, style);
    }

    @Test
    void neutralWeatherIsByteIdenticalAcrossManySeeds() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch sunny = engine.simulate(home, away,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed);
            SimulatedMatch neutral = engine.simulate(home, away, Weather.neutral(), seed);

            assertThat(sunny.homeGoals()).isEqualTo(neutral.homeGoals());
            assertThat(sunny.awayGoals()).isEqualTo(neutral.awayGoals());
        }
    }

    @Test
    void heavyRainLowersTotalGoalsAcrossManyMatches() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        int dry = 0;
        int wet = 0;
        for (long seed = 0; seed < 3000; seed++) {
            SimulatedMatch d = engine.simulate(home, away, Weather.neutral(), seed);
            SimulatedMatch w = engine.simulate(home, away,
                    new Weather(WeatherCondition.CHUVA_FORTE, 20), seed);
            dry += d.homeGoals() + d.awayGoals();
            wet += w.homeGoals() + w.awayGoals();
        }
        assertThat(wet).isLessThan(dry);
    }

    @Test
    void aHotDayPunishesAHighPressingSide() {
        TeamMatchInput pressing = team(GameStyle.PRESSAO_ALTA);
        TeamMatchInput balanced = team(GameStyle.EQUILIBRADO);

        int mildPressingGoals = 0;
        int hotPressingGoals = 0;
        for (long seed = 0; seed < 3000; seed++) {
            mildPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed).homeGoals();
            hotPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 36), seed).homeGoals();
        }
        assertThat(hotPressingGoals).isLessThan(mildPressingGoals);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherEngineIntegrationTest*'
```

Expected: compilation failure — `simulate` has no 4-arg overload.

- [ ] **Step 3: Thread weather through `MatchSimulationService`**

Change the signature and apply the impact. In `MatchSimulationService.simulate`, replace the opening of the method:

```java
    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, Weather weather, long seed) {
        Random rng = new Random(seed);
        WeatherEffects.Impact weatherImpact = WeatherEffects.of(weather, home, away);
        double tempoFactor = (home.tempo() + away.tempo()) / 2.0;
        double homeSetPiece = SetPieces.share(
                home.cornerThreat() * weatherImpact.setPieceMult(),
                home.freeKickThreat() * weatherImpact.setPieceMult());
        double awaySetPiece = SetPieces.share(
                away.cornerThreat() * weatherImpact.setPieceMult(),
                away.freeKickThreat() * weatherImpact.setPieceMult());
        double homeCardChance = BASE_CARD_RATE * home.aggression();
        double awayCardChance = BASE_CARD_RATE * away.aggression();
```

Inside the minute loop, apply the decay multipliers to the fitness factor and the chance multipliers after `chanceMults`:

```java
            double homeFf = MatchMath.fitnessFactor(
                    home.fitnessDecay() * weatherImpact.homeDecayMult(), minute);
            double awayFf = MatchMath.fitnessFactor(
                    away.fitnessDecay() * weatherImpact.awayDecayMult(), minute);

            double homeChance = MatchMath.perMinuteScoreChance(home.attack() * homeFf,
                    away.defense() * awayFf, true) * tempoFactor;
            double awayChance = MatchMath.perMinuteScoreChance(away.attack() * awayFf,
                    home.defense() * homeFf, false) * tempoFactor;

            MatchupModifiers.ChanceMults chanceMults = MatchupModifiers.chanceMults(home, away);
            homeChance *= chanceMults.home() * weatherImpact.homeChanceMult();
            awayChance *= chanceMults.away() * weatherImpact.awayChanceMult();
```

Update the class javadoc to note that weather modifiers are applied per side and that neutral weather is a no-op.

- [ ] **Step 4: Thread weather through `LiveMatchEngine`**

Add a `Weather weather` field to `LiveMatchConfig` (follow the record/class shape already there; if it is a record, add the component and fix every construction site). In `LiveMatchEngine`, compute the impact once after the initial `strength(...)` calls:

```java
        TeamMatchInput homeInput = strength(home, home.currentProfile, cfg);
        home.dirty = false;
        TeamMatchInput awayInput = strength(away, away.currentProfile, cfg);
        away.dirty = false;

        WeatherEffects.Impact weatherImpact = WeatherEffects.of(cfg.weather(), homeInput, awayInput);
```

Then inside the loop, replace the fitness-factor and chance lines:

```java
            double homeFf = MatchMath.fitnessFactor(
                    homeInput.fitnessDecay() * weatherImpact.homeDecayMult(), minute);
            double awayFf = MatchMath.fitnessFactor(
                    awayInput.fitnessDecay() * weatherImpact.awayDecayMult(), minute);

            double homeChance = MatchMath.perMinuteScoreChance(homeInput.attack() * homeFf,
                    awayInput.defense() * awayFf, true) * tempo;
            double awayChance = MatchMath.perMinuteScoreChance(awayInput.attack() * awayFf,
                    homeInput.defense() * homeFf, false) * tempo;

            MatchupModifiers.ChanceMults chanceMults = MatchupModifiers.chanceMults(homeInput, awayInput);
            homeChance *= chanceMults.home() * weatherImpact.homeChanceMult();
            awayChance *= chanceMults.away() * weatherImpact.awayChanceMult();
```

The impact is computed **once**, not per minute, even though substitutions can rebuild `homeInput`/`awayInput`. Weather does not change during a match (spec: out of scope), and recomputing per minute would let a mid-match style change alter fatigue retroactively.

Wherever `LiveMatchEngine` consumes `cornerThreat()` / `freeKickThreat()` for set pieces, multiply both by `weatherImpact.setPieceMult()` — mirroring the `MatchSimulationService` change exactly.

- [ ] **Step 5: Resolve weather at the two call sites**

In `FixtureSimulator`, the home club is already available via `clubById`. At the precompute site (line ~208):

```java
        Weather weather = WeatherGenerator.forFixture(
                fixture.getId(), clubById.get(homeClubId).getClimateZone(), matchDate);
        SimulatedMatch match = matchSimulationService.simulate(
                homeInput, awayInput, weather, seedFor(saveGameId, fixture));
```

Do the same at the `simulateFixture` site. If a club lookup can return null in either path, fall back to `Weather.neutral()` rather than throwing — a missing club is already tolerated elsewhere in this class.

In `LiveMatchService`, resolve the weather where the `LiveMatchConfig` is built and pass it in, using the home club's zone and the fixture's `matchDate`.

- [ ] **Step 6: Fix every other caller**

```bash
cd backend && grep -rn "\.simulate(" src/main src/test --include="*.java"
```

Every remaining 3-arg call gets `Weather.neutral()` inserted before the seed. This is what keeps the existing engine tests byte-identical.

- [ ] **Step 7: Run the new test and the existing engine tests**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherEngineIntegrationTest*' --tests '*MatchSimulationServiceTest*' --tests '*LiveMatchEngineTest*' --tests '*SetPiecesTest*' --tests '*MatchupModifiersTest*'
```

Expected: all PASS. Any pre-existing engine test that now fails means a neutral-weather path is not actually neutral — fix the wiring, never re-baseline the expectation.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/test/java/br/com/carvoeiromanager/match/WeatherEngineIntegrationTest.java
git commit -m "feat(match): both engines honor weather at the modifier seam"
```

---

### Task 6: Recalibrate `MatchCalibrationTest` with live weather

**Files:**
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/MatchCalibrationTest.java`
- Modify (only if the bands break): `backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java:19`

**Interfaces:**
- Consumes: everything from Task 5.

Read `MatchCalibrationTest` in full before changing it — it is the guard for Group 3 sub-feature 1 and its bands are the contract.

- [ ] **Step 1: Add a weather-live calibration case**

Keep every existing assertion as-is (they pass `Weather.neutral()` after Task 5, which the neutrality contract guarantees is a no-op). Add one new test that draws real weather per simulated match, so the bands hold across the distribution a real season produces:

```java
    @Test
    void goalsPerMatchStayInBandWithRealWeather() {
        int goals = 0;
        int matches = 0;
        for (long seed = 0; seed < 5000; seed++) {
            Weather weather = WeatherGenerator.forFixture(
                    new UUID(seed, seed), ClimateZone.SUL, LocalDate.of(2026, 1 + (int) (seed % 10), 15));
            SimulatedMatch match = engine.simulate(averageTeam(), averageTeam(), weather, seed);
            goals += match.homeGoals() + match.awayGoals();
            matches++;
        }
        double perMatch = goals / (double) matches;
        assertThat(perMatch).isBetween(2.4, 3.0);
    }
```

Adapt `engine` / `averageTeam()` to whatever the existing test already uses — do not introduce a second way of building a calibration team.

- [ ] **Step 2: Run the full calibration test**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*MatchCalibrationTest*'
```

Expected: PASS. If `goalsPerMatchStayInBandWithRealWeather` lands below 2.4, weather's wet-pitch multipliers have shaved too much scoring — raise `MatchMath.ANCHOR` from `1.35` in steps of `0.01` until the band holds, then **re-run the whole test class** so the strong-vs-weak and home-win bands are still satisfied.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/match/MatchCalibrationTest.java backend/src/main/java/br/com/carvoeiromanager/match/MatchMath.java
git commit -m "test(match): calibration bands hold with live weather"
```

---

### Task 7: `WeatherService` — the reveal rule

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/WeatherService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/WeatherServiceTest.java`

**Interfaces:**
- Consumes: `WeatherGenerator`, `Weather` (Task 3); `Club`, `Fixture` (Task 2).
- Produces: `WeatherService.visibleWeather(Fixture fixture, Club homeClub, LocalDate today)` returning `Optional<Weather>`; `WeatherService.weatherFor(Fixture fixture, Club homeClub)` returning `Weather` (ungated — for engines and for already-played fixtures).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/WeatherServiceTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.competition.Fixture;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherServiceTest {

    private final WeatherService service = new WeatherService();

    private static Club homeClub() {
        Club club = Club.create(java.util.UUID.randomUUID(), "Criciúma Esporte Clube", "CRI");
        club.setClimateZone(ClimateZone.SUL);
        return club;
    }

    @Test
    void weatherIsHiddenMoreThanADayOut() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 18)))
                .isEmpty();
    }

    @Test
    void weatherIsVisibleOnTheEve() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 19)))
                .isPresent();
    }

    @Test
    void weatherIsVisibleOnMatchDayAndAfter() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 20)))
                .isPresent();
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 4, 30)))
                .isPresent();
    }

    @Test
    void theRevealRuleNeverChangesTheWeatherItself() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        Club club = homeClub();
        Optional<Weather> revealed = service.visibleWeather(fixture, club, LocalDate.of(2026, 3, 19));
        assertThat(revealed).contains(service.weatherFor(fixture, club));
    }

    private static Fixture fixtureOn(LocalDate date) {
        return Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), UUID.randomUUID(), date);
    }
}
```

Add `import java.util.UUID;`. `Fixture.create` assigns a random id, which is exactly what the weather seed needs — no test-only constructor required.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherServiceTest*'
```

Expected: compilation failure — `WeatherService` does not exist.

- [ ] **Step 3: Implement**

`WeatherService.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.competition.Fixture;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The single place the forecast-reveal rule lives: weather is public knowledge from the eve of
 * the match onward, and hidden before that so it cannot be scouted a month ahead.
 *
 * <p>Deliberately <b>not</b> gated on having an Auxiliar Técnico hired — a staff-less club would
 * otherwise see no forecast anywhere, since no auxiliar means no MATCH_PREVIEW news at all. The
 * auxiliar's value-add is the interpretation, not the forecast.
 *
 * <p>This is a presentation concern only. The engines call {@link #weatherFor} (or
 * {@link WeatherGenerator} directly) and are never affected by the reveal rule.
 */
@Service
public class WeatherService {

    /** Days before kickoff at which the forecast becomes public. */
    private static final long REVEAL_DAYS = 1;

    /** The fixture's weather, ungated. For the engines and for already-played fixtures. */
    public Weather weatherFor(Fixture fixture, Club homeClub) {
        return WeatherGenerator.forFixture(
                fixture.getId(), homeClub.getClimateZone(), fixture.getMatchDate());
    }

    /** The fixture's weather if the manager is allowed to see it yet, else empty. */
    public Optional<Weather> visibleWeather(Fixture fixture, Club homeClub, LocalDate today) {
        if (fixture.getMatchDate() == null || today == null) {
            return Optional.empty();
        }
        long daysOut = ChronoUnit.DAYS.between(today, fixture.getMatchDate());
        return daysOut > REVEAL_DAYS ? Optional.empty() : Optional.of(weatherFor(fixture, homeClub));
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*WeatherServiceTest*'
```

Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/WeatherService.java backend/src/test/java/br/com/carvoeiromanager/match/WeatherServiceTest.java
git commit -m "feat(match): eve-of-match forecast reveal rule"
```

---

### Task 8: `AttendanceCalculator` + `gateIncome` refactor

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/finance/AttendanceCalculator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java:132-137`
- Test: `backend/src/test/java/br/com/carvoeiromanager/finance/AttendanceCalculatorTest.java`
- Test: modify `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceCalculatorTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherCondition` (Task 3).
- Produces: `AttendanceCalculator.attendance(int stadiumCapacity, int homeReputation, int awayReputation, int recentFormPoints, int leaguePosition, int clubCount, Weather weather)` returning `int`; `FinanceCalculator.gateIncome(int attendance)` returning `BigDecimal`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/finance/AttendanceCalculatorTest.java`:

```java
package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceCalculatorTest {

    private final AttendanceCalculator calculator = new AttendanceCalculator();

    private static final Weather SUNNY = new Weather(WeatherCondition.ENSOLARADO, 22);
    private static final Weather DOWNPOUR = new Weather(WeatherCondition.CHUVA_FORTE, 18);

    @Test
    void attendanceNeverExceedsCapacity() {
        int attendance = calculator.attendance(10000, 90, 90, 15, 1, 20, SUNNY);
        assertThat(attendance).isLessThanOrEqualTo(10000);
    }

    @Test
    void attendanceIsNeverNegative() {
        int attendance = calculator.attendance(3000, 40, 40, 0, 20, 20, DOWNPOUR);
        assertThat(attendance).isGreaterThan(0);
    }

    @Test
    void goodFormDrawsABiggerCrowdThanBadForm() {
        int good = calculator.attendance(20000, 60, 60, 15, 10, 20, SUNNY);
        int bad = calculator.attendance(20000, 60, 60, 0, 10, 20, SUNNY);
        assertThat(good).isGreaterThan(bad);
    }

    @Test
    void leadingTheTableDrawsABiggerCrowdThanPropUpTheTable() {
        int top = calculator.attendance(20000, 60, 60, 8, 1, 20, SUNNY);
        int bottom = calculator.attendance(20000, 60, 60, 8, 20, 20, SUNNY);
        assertThat(top).isGreaterThan(bottom);
    }

    @Test
    void heavyRainAlwaysReducesTheCrowd() {
        int dry = calculator.attendance(20000, 60, 60, 8, 10, 20, SUNNY);
        int wet = calculator.attendance(20000, 60, 60, 8, 10, 20, DOWNPOUR);
        assertThat(wet).isLessThan(dry);
    }

    @Test
    void extremeTemperatureAddsAFurtherPenalty() {
        int mild = calculator.attendance(20000, 60, 60, 8, 10, 20,
                new Weather(WeatherCondition.ENSOLARADO, 24));
        int scorching = calculator.attendance(20000, 60, 60, 8, 10, 20,
                new Weather(WeatherCondition.ENSOLARADO, 37));
        assertThat(scorching).isLessThan(mild);
    }

    @Test
    void aSingleClubCompetitionDoesNotDivideByZero() {
        int attendance = calculator.attendance(20000, 60, 60, 8, 1, 1, SUNNY);
        assertThat(attendance).isPositive();
    }

    @Test
    void theSpreadBetweenBestAndWorstCaseMatchesTheSpec() {
        int best = calculator.attendance(20000, 60, 60, 15, 1, 20, SUNNY);
        int worst = calculator.attendance(20000, 60, 60, 0, 20, 20,
                new Weather(WeatherCondition.CHUVA_FORTE, 37));
        assertThat(worst / (double) best).isBetween(0.45, 0.55);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*AttendanceCalculatorTest*'
```

Expected: compilation failure — `AttendanceCalculator` does not exist.

- [ ] **Step 3: Implement the calculator**

`AttendanceCalculator.java`:

```java
package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic crowd model. Reputation sets the baseline draw; recent form, league
 * position, and the weather modulate it, so a home gate reflects how the season is actually
 * going rather than being a constant.
 *
 * <p>Extracted from {@code FinanceCalculator.gateIncome}, which previously computed attendance
 * as a throwaway local and returned only money. Finance no longer owns crowd behaviour.
 *
 * <p>Ranges: form 0.90–1.10, position 0.90–1.10, weather 0.74–1.00 — so the worst case
 * (bottom of the table, no form, heavy rain, temperature extreme) is ~0.60x the reputation
 * baseline and the best case ~1.21x.
 */
@Component
public class AttendanceCalculator {

    private static final double FILL_FLOOR = 0.15;
    private static final double FILL_CEILING = 1.0;

    private static final int FORM_MAX_POINTS = 15;
    private static final double FORM_MIN = 0.90;
    private static final double FORM_SPAN = 0.20;

    private static final double POSITION_MAX = 1.10;
    private static final double POSITION_SPAN = 0.20;

    private static final int HOT_THRESHOLD = 35;
    private static final int COLD_THRESHOLD = 8;
    private static final double EXTREME_TEMPERATURE_FACTOR = 0.95;

    public int attendance(int stadiumCapacity, int homeReputation, int awayReputation,
                          int recentFormPoints, int leaguePosition, int clubCount,
                          Weather weather) {
        double fill = base(homeReputation, awayReputation)
                * form(recentFormPoints)
                * position(leaguePosition, clubCount)
                * weather(weather);

        fill = Math.max(FILL_FLOOR, Math.min(FILL_CEILING, fill));
        return Math.min(stadiumCapacity, (int) Math.round(stadiumCapacity * fill));
    }

    /** Unchanged from the original gateIncome formula. */
    private double base(int homeReputation, int awayReputation) {
        return 0.40 + homeReputation / 200.0 + awayReputation / 400.0;
    }

    /** Points from the last 5 league matches, 0-15. */
    private double form(int recentFormPoints) {
        int points = Math.max(0, Math.min(FORM_MAX_POINTS, recentFormPoints));
        return FORM_MIN + FORM_SPAN * (points / (double) FORM_MAX_POINTS);
    }

    /** 1-based league position; a one-club competition is treated as neutral. */
    private double position(int leaguePosition, int clubCount) {
        if (clubCount <= 1) {
            return 1.0;
        }
        int pos = Math.max(1, Math.min(clubCount, leaguePosition));
        return POSITION_MAX - POSITION_SPAN * (pos - 1) / (double) (clubCount - 1);
    }

    private double weather(Weather weather) {
        double factor = switch (weather.condition()) {
            case ENSOLARADO -> 1.00;
            case NUBLADO -> 0.98;
            case VENTANIA -> 0.95;
            case CHUVA -> 0.90;
            case CHUVA_FORTE -> 0.78;
        };
        int t = weather.temperatureC();
        if (t >= HOT_THRESHOLD || t <= COLD_THRESHOLD) {
            factor *= EXTREME_TEMPERATURE_FACTOR;
        }
        return factor;
    }
}
```

- [ ] **Step 4: Refactor `gateIncome`**

In `FinanceCalculator.java`, replace the whole `gateIncome` method:

```java
    /**
     * Gate receipts for a realized crowd. The crowd model itself lives in
     * {@link AttendanceCalculator} — this method only turns bodies into money.
     */
    public BigDecimal gateIncome(int attendance) {
        return scaled(TICKET_PRICE.multiply(BigDecimal.valueOf(Math.max(0, attendance))));
    }
```

Update `FinanceCalculatorTest` for the new signature: any existing gate test that passed reputations now passes an attendance directly.

- [ ] **Step 5: Run both test classes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*AttendanceCalculatorTest*' --tests '*FinanceCalculatorTest*'
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/ backend/src/test/java/br/com/carvoeiromanager/finance/
git commit -m "feat(finance): context-driven AttendanceCalculator; gateIncome takes a crowd"
```

---

### Task 9: Persist attendance at match resolution

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java:145`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/AttendancePersistenceIT.java`

**Interfaces:**
- Consumes: `AttendanceCalculator` (Task 8), `WeatherService.weatherFor` (Task 7), `Fixture.setAttendance` (Task 2).
- Produces: every played fixture carries a non-null `attendance`.

Both call sites need the home club's recent form and league position. `StandingsService` already computes standings for a competition — read it and reuse it rather than writing a second standings query. Form is the home club's points from its last 5 played fixtures in the same competition (3 for a win, 1 for a draw), capped at 15; with fewer than 5 played, use what exists.

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/AttendancePersistenceIT.java`, using the same annotation trio as `ClubGeographyIT` from Task 2:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
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
class AttendancePersistenceIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;

    private UUID createSave() {
        User user = User.create("attendance_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    @Test
    void everyPlayedFixtureRecordsAPositiveAttendance() {
        UUID saveGameId = createSave();
        advanceOneRound(saveGameId);

        List<Fixture> played = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .toList();

        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(fixture ->
                assertThat(fixture.getAttendance())
                        .as("attendance of fixture %s", fixture.getId())
                        .isNotNull()
                        .isPositive());
    }

    @Test
    void unplayedFixturesHaveNoAttendance() {
        UUID saveGameId = createSave();

        List<Fixture> unplayed = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(f -> !f.isPlayed())
                .toList();

        assertThat(unplayed).isNotEmpty();
        assertThat(unplayed).allMatch(f -> f.getAttendance() == null);
    }
}
```

Before writing `advanceOneRound`, read an existing IT that advances a season (`grep -rln "seasonAdvanceService" backend/src/test`) and reuse its exact call — do not invent a new advance path.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*AttendancePersistenceIT*'
```

Expected: `everyPlayedFixtureRecordsAnAttendanceWithinCapacity` FAILS — attendance is null.

- [ ] **Step 3: Compute and persist in `FixtureSimulator`**

Inject `AttendanceCalculator` and `WeatherService` via the constructor (this class already takes several collaborators — follow its existing constructor style). Where the fixture result is persisted, before saving:

```java
        Club homeClub = clubById.get(fixture.getHomeClubId());
        Club awayClub = clubById.get(fixture.getAwayClubId());
        if (homeClub != null && awayClub != null) {
            fixture.setAttendance(attendanceCalculator.attendance(
                    homeClub.getStadiumCapacity(),
                    homeClub.getReputation(),
                    awayClub.getReputation(),
                    recentFormPoints(saveGameId, fixture.getCompetitionId(), homeClub.getId(), fixture.getMatchDate()),
                    leaguePosition(saveGameId, fixture.getCompetitionId(), homeClub.getId()),
                    clubCount(saveGameId, fixture.getCompetitionId()),
                    weatherService.weatherFor(fixture, homeClub)));
        }
```

Implement the three private helpers in terms of `StandingsService` and `FixtureRepository`. Keep them private and small; if both call sites need them, extract a small `MatchContextResolver` component rather than duplicating.

- [ ] **Step 4: Do the same in `LiveMatchService`**

At the point the live match is finalized and the fixture is saved, set attendance the same way. If you extracted `MatchContextResolver` in Step 3, use it here — the two paths must not compute attendance differently.

- [ ] **Step 5: Read the persisted attendance in `FinanceService`**

Replace line 145:

```java
                Integer attendance = matchdayFixtures.stream()
                        .filter(f -> f.getHomeClubId().equals(clubId))
                        .map(Fixture::getAttendance)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                gate = attendance != null
                        ? financeCalculator.gateIncome(attendance)
                        : BigDecimal.ZERO;
```

The `awayRep` lookup above it becomes dead — delete it.

- [ ] **Step 6: Run the test and confirm it passes**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*AttendancePersistenceIT*' --tests '*FinanceServiceIT*'
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/ backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java backend/src/test/java/br/com/carvoeiromanager/match/AttendancePersistenceIT.java
git commit -m "feat(match): persist realized attendance; finance reads it"
```

---

### Task 10: Season-solvency recalibration

**Files:**
- Test: `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceSolvencyTest.java`
- Modify (only if the test demands it): `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java`

**Interfaces:**
- Consumes: everything from Tasks 8–9.

Real capacities changed the economics: Criciúma went from a derived 38 000 seats to a real 19 225, and the smallest clubs from 28 000 to 3 000. `FinanceCalculator`'s class javadoc documents a `cap = 38 000` world that no longer exists.

- [ ] **Step 1: Write the solvency test**

Create `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceSolvencyTest.java`:

```java
package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.match.Weather;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A season's arithmetic for a mid-table club, run at both ends of the real capacity range.
 * Guards the recalibration that real stadium capacities forced: a small club must not be
 * bankrupted by simply existing.
 */
class FinanceSolvencyTest {

    private static final int MONTHS = 10;
    private static final int HOME_MATCHES = 9;
    private static final int TOTAL_MATCHES = 18;
    private static final int SQUAD_SIZE = 22;
    private static final int AVG_OVERALL = 60;
    private static final int AVG_AGE = 26;

    private final FinanceCalculator finance = new FinanceCalculator();
    private final AttendanceCalculator attendance = new AttendanceCalculator();

    @Test
    void aSmallClubSurvivesASeason() {
        assertThat(seasonNet(3000, 45)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aMidSizedClubSurvivesASeason() {
        assertThat(seasonNet(19225, 60)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aLargeClubSurvivesASeason() {
        assertThat(seasonNet(63903, 75)).isGreaterThan(BigDecimal.ZERO);
    }

    /** Net change over a season: sponsorship and prize in, wages, upkeep, and match costs out. */
    private BigDecimal seasonNet(int capacity, int reputation) {
        BigDecimal wages = finance.monthlyWage(AVG_OVERALL, AVG_AGE)
                .multiply(BigDecimal.valueOf((long) SQUAD_SIZE * MONTHS));
        BigDecimal sponsorship = finance.monthlySponsorship(reputation)
                .multiply(BigDecimal.valueOf(MONTHS));
        BigDecimal upkeep = finance.monthlyUpkeep(capacity)
                .multiply(BigDecimal.valueOf(MONTHS));
        BigDecimal matchCosts = finance.matchCost(capacity)
                .multiply(BigDecimal.valueOf(TOTAL_MATCHES));

        int crowd = attendance.attendance(capacity, reputation, reputation, 8, 10, 20, Weather.neutral());
        BigDecimal gate = finance.gateIncome(crowd).multiply(BigDecimal.valueOf(HOME_MATCHES));
        BigDecimal prize = finance.prizeMoney(10, 20);

        return sponsorship.add(gate).add(prize).subtract(wages).subtract(upkeep).subtract(matchCosts);
    }
}
```

- [ ] **Step 2: Run it and read the actual numbers**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FinanceSolvencyTest*' --info
```

Record which cases fail and by how much. **Do not guess at a fix before reading the numbers** — that is how two changes end up cancelling out.

- [ ] **Step 3: Recalibrate only what the test proves broken**

If a case is insolvent, raise `MONTHLY_SPONSORSHIP_PER_REP` (currently `100000`) in steps of `10000` until all three pass — it is the existing counterweight to payroll and scales with reputation, so it lifts small clubs proportionally less than large ones. If instead the *large* club is the one failing, `MONTHLY_UPKEEP_PER_SEAT` (currently `6`) is the lever, since upkeep scales with capacity.

Change **one** constant, re-run, repeat. If all three pass unmodified, change nothing and say so.

- [ ] **Step 4: Rewrite the calibration javadoc**

Replace the `<h3>Monthly-model calibration notes</h3>` block in `FinanceCalculator` so its worked example uses a real capacity (19 225 for Criciúma) and the final constants, and states that attendance now comes from `AttendanceCalculator` rather than being derived inline.

- [ ] **Step 5: Run the full finance suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*Finance*'
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java backend/src/test/java/br/com/carvoeiromanager/finance/FinanceSolvencyTest.java
git commit -m "test(finance): season solvency across the real capacity range"
```

---

### Task 11: Backend surfacing — DTOs and the forecast line

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureResultResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureEntryResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/club/ClubSummaryResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/club/ClubService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/MatchPreviewData.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/MatchPreviewService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/MatchPreviewWeatherTest.java`

**Interfaces:**
- Consumes: `WeatherService` (Task 7), `Weather` (Task 3), `Fixture.getAttendance()` (Task 2).
- Produces: `MatchPreviewData.weather` (String condition label, nullable), `.temperatureC` (Integer, nullable), `.weatherAdvice` (String, nullable).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/season/MatchPreviewWeatherTest.java`:

```java
package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPreviewWeatherTest {

    @Test
    void heavyRainAdviceMentionsTheWetPitch() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.CHUVA_FORTE, 18));
        assertThat(advice).containsIgnoringCase("gramado");
    }

    @Test
    void hotWeatherAdviceWarnsAboutPressing() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.ENSOLARADO, 34));
        assertThat(advice).containsIgnoringCase("press");
    }

    @Test
    void windAdviceMentionsSetPieces() {
        String advice = MatchPreviewService.weatherAdvice(
                new Weather(WeatherCondition.VENTANIA, 22));
        assertThat(advice).containsIgnoringCase("bola parada");
    }

    @Test
    void mildWeatherGivesNoAdvice() {
        assertThat(MatchPreviewService.weatherAdvice(Weather.neutral())).isNull();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*MatchPreviewWeatherTest*'
```

Expected: compilation failure — `weatherAdvice` does not exist.

- [ ] **Step 3: Add the forecast to the preview**

Add three components to `MatchPreviewData`, after `stadiumName`:

```java
        String weather,
        Integer temperatureC,
        String weatherAdvice,
```

Update the record javadoc to note all three are null when the fixture's weather is not yet revealed.

In `MatchPreviewService`, add the static advice builder:

```java
    /**
     * The auxiliar's one-line read on the forecast, in pt-BR. Null for unremarkable weather —
     * the auxiliar comments when there is something to comment on.
     */
    static String weatherAdvice(Weather weather) {
        return switch (weather.condition()) {
            case CHUVA_FORTE ->
                "Chuva forte na véspera: o gramado vai ficar pesado, o jogo de passes perde efeito.";
            case CHUVA ->
                "Previsão de chuva: gramado molhado, cuidado com a saída de bola.";
            case VENTANIA ->
                "Muito vento previsto: a bola parada e os cruzamentos vão sofrer.";
            case ENSOLARADO, NUBLADO -> {
                if (weather.temperatureC() >= 32) {
                    yield "Calor forte previsto (" + weather.temperatureC()
                            + "°C): a pressão alta cobra caro no segundo tempo.";
                }
                if (weather.temperatureC() <= 10) {
                    yield "Frio previsto (" + weather.temperatureC()
                            + "°C): ritmo tende a cair, mas a equipe cansa menos.";
                }
                yield null;
            }
        };
    }
```

Populate the three new fields where `MatchPreviewData` is constructed, resolving the weather through `WeatherService.visibleWeather` with the preview's own date. Because the preview is generated on the eve, it will always be revealed — but route through the service anyway so there is exactly one reveal rule.

- [ ] **Step 4: Add the remaining DTO fields**

- `LiveMatchState`: append `String weather`, `Integer temperatureC`, `Integer attendance`. Populate in `LiveMatchService` from `WeatherService.weatherFor` (the match is in progress, so it is always revealed) and `fixture.getAttendance()`.
- `FixtureResultResponse`: append `Integer attendance`, `String weather`, `Integer temperatureC`.
- `FixtureEntryResponse`: append `Integer attendance`, `String weather`, `Integer temperatureC` — populated in `FixtureService` through `WeatherService.visibleWeather`, so upcoming fixtures more than a day out return nulls.
- `ClubSummaryResponse`: append `String city`, `String state`, `int stadiumCapacity`, `String stadiumName`. Populate in `ClubService`.

Every new field is nullable and every existing construction site must be updated — the compiler will find them all.

- [ ] **Step 5: Run the test and the affected suites**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*MatchPreviewWeatherTest*' --tests '*MatchPreview*' --tests '*LiveMatch*' --tests '*Fixture*' --tests '*Club*'
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/ backend/src/test/java/br/com/carvoeiromanager/season/MatchPreviewWeatherTest.java
git commit -m "feat(api): expose weather, attendance, and club geography"
```

---

### Task 12: Frontend

**Files:**
- Create: `frontend/src/components/WeatherChip.tsx`
- Create: `frontend/src/components/WeatherChip.test.tsx`
- Modify: the live-match header component, the Calendário/fixtures page, the club page, and the news `MATCH_PREVIEW` renderer
- Modify: the corresponding entries in the frontend API types

Find the exact files first:

```bash
cd frontend && grep -rn "stadiumName" src --include="*.ts" --include="*.tsx"
```

**Interfaces:**
- Consumes: the DTO fields from Task 11.
- Produces: `<WeatherChip condition={...} temperatureC={...} />`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/WeatherChip.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WeatherChip } from "./WeatherChip";

describe("WeatherChip", () => {
  it("shows the condition label and the temperature", () => {
    render(<WeatherChip condition="CHUVA_FORTE" temperatureC={18} />);
    expect(screen.getByText(/Chuva forte/)).toBeInTheDocument();
    expect(screen.getByText(/18°C/)).toBeInTheDocument();
  });

  it("renders nothing when the forecast is not yet revealed", () => {
    const { container } = render(<WeatherChip condition={null} temperatureC={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("labels every condition in pt-BR", () => {
    const conditions = ["ENSOLARADO", "NUBLADO", "VENTANIA", "CHUVA", "CHUVA_FORTE"] as const;
    for (const condition of conditions) {
      const { unmount } = render(<WeatherChip condition={condition} temperatureC={22} />);
      expect(screen.getByTestId("weather-chip")).toBeInTheDocument();
      unmount();
    }
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd frontend && npx vitest run src/components/WeatherChip.test.tsx
```

Expected: FAIL — cannot resolve `./WeatherChip`.

- [ ] **Step 3: Implement the component**

Create `frontend/src/components/WeatherChip.tsx`. Match the MUI idiom of the surrounding components (read a neighbour first — the project already has chip-style components from the watchlist and squad work):

```tsx
import { Chip } from "@mui/material";
import AcUnitIcon from "@mui/icons-material/AcUnit";
import AirIcon from "@mui/icons-material/Air";
import CloudIcon from "@mui/icons-material/Cloud";
import ThunderstormIcon from "@mui/icons-material/Thunderstorm";
import WaterDropIcon from "@mui/icons-material/WaterDrop";
import WbSunnyIcon from "@mui/icons-material/WbSunny";

export type WeatherCondition =
  | "ENSOLARADO"
  | "NUBLADO"
  | "VENTANIA"
  | "CHUVA"
  | "CHUVA_FORTE";

const LABELS: Record<WeatherCondition, string> = {
  ENSOLARADO: "Ensolarado",
  NUBLADO: "Nublado",
  VENTANIA: "Ventania",
  CHUVA: "Chuva",
  CHUVA_FORTE: "Chuva forte",
};

const ICONS: Record<WeatherCondition, typeof WbSunnyIcon> = {
  ENSOLARADO: WbSunnyIcon,
  NUBLADO: CloudIcon,
  VENTANIA: AirIcon,
  CHUVA: WaterDropIcon,
  CHUVA_FORTE: ThunderstormIcon,
};

type Props = {
  condition: WeatherCondition | null;
  temperatureC: number | null;
};

/** Match-day forecast. Renders nothing before the forecast is revealed (the eve of the match). */
export const WeatherChip = ({ condition, temperatureC }: Props) => {
  if (!condition) {
    return null;
  }
  const Icon = temperatureC !== null && temperatureC <= 8 ? AcUnitIcon : ICONS[condition];
  const label =
    temperatureC !== null ? `${LABELS[condition]} · ${temperatureC}°C` : LABELS[condition];

  return <Chip data-testid="weather-chip" size="small" icon={<Icon />} label={label} />;
};
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
cd frontend && npx vitest run src/components/WeatherChip.test.tsx
```

Expected: 3 tests PASS.

- [ ] **Step 5: Wire it into the four surfaces**

- **Live match header:** `<WeatherChip>` beside the stadium name; attendance as `Público: 19.300` using `new Intl.NumberFormat("pt-BR").format(attendance)`.
- **Calendário / fixtures:** chip on upcoming rows (null-safe — it self-hides), attendance on played rows.
- **Club page:** `Estádio Heriberto Hülse · Criciúma/SC · 19.300 lugares`, each part omitted when null.
- **News `MATCH_PREVIEW` renderer:** the chip plus `weatherAdvice` as a line in the auxiliar's report, rendered only when non-null.

Add the new fields to the frontend API types alongside the existing `stadiumName` declarations.

- [ ] **Step 6: Typecheck and run the full frontend suite**

```bash
cd frontend && npx tsc -b && npx vitest run
```

Expected: clean build, all tests PASS. (`tsc --noEmit` is a no-op in this repo — do not use it.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(front): weather chip, attendance, and stadium location"
```

---

### Task 13: Full verification

- [ ] **Step 1: Full backend suite**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```

Expected: BUILD SUCCESSFUL, ~10–12 min. Note: concurrent Gradle runs in another session cause `EOFException` / `NoSuchFileException` — that is contention, not a regression. Re-run alone.

- [ ] **Step 2: Full frontend suite**

```bash
cd frontend && npx tsc -b && npx vitest run
```

Expected: clean build, all tests PASS.

- [ ] **Step 3: Migration-from-existing-save check**

Start the app against a database that predates V45 and confirm Flyway applies it cleanly, all 28 clubs get geography and real capacities, and fixtures already played keep working with a null attendance.

- [ ] **Step 4: Live end-to-end check**

Start the app, load a save, advance to the eve of a fixture, and confirm: the forecast appears in the auxiliar's preview news and on the Calendário; a fixture more than a day out shows no forecast; the live-match header shows weather and attendance; the club page shows the real capacity and city; the finances screen's gate figure tracks the displayed attendance.

- [ ] **Step 5: Report**

Summarize for the user: whether any finance constant was recalibrated in Task 10 and by how much, whether `MatchMath.ANCHOR` needed a nudge in Task 6, and the observed attendance range across a round.

---

## Notes for the implementer

- **The determinism contract is the load-bearing invariant.** If an existing engine test fails after Task 5, a neutral-weather path is not actually neutral. Fix the wiring; never re-baseline the expectation.
- **Weather is never persisted.** If you find yourself adding a `weather` column, re-read the spec — the whole design depends on it being derivable.
- **Attendance is always persisted.** It depends on standings and form at kickoff, which are gone later.
- **Two call sites, one behaviour.** `LiveMatchService` and `FixtureSimulator` must produce identical attendance for identical inputs. If the logic starts diverging, extract it.
