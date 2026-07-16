# AI Club Tactical Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every AI-controlled club a real, researched tactical identity (formation, mentality, pressing, marking) that the match engine already knows how to use, instead of every AI club sharing one hardcoded default.

**Architecture:** Extend the existing seed → career-creation → persisted-entity pipeline (the same one that already carries club colors from `clubs.json` into the `Club` entity) with 4 new identity fields, then make `TacticsService`'s AI-club fallback read them from the `Club` row instead of a hardcoded constant. No engine, formula, or public-API changes — `TeamStrengthCalculator` already fully consumes `TacticalProfile`.

**Tech Stack:** Java 21, Spring Boot, Gradle, Flyway migrations, Postgres (Testcontainers for integration tests), JUnit 5 + AssertJ.

## Global Constraints

- All 28 pool clubs get an authored identity, including the managed club (CRI) and Avaí (in both competitions) — this is also the fallback shown before the human manager sets their own tactics.
- Formation values must be one of the 16 real `Formation` enum codes (`br.com.carvoeiromanager.player.Formation`): `4-4-2, 4-3-3, 3-5-2, 5-3-2, 4-5-1, 4-2-3-1, 4-1-4-1, 4-3-2-1, 3-4-3, 4-4-1-1, 5-4-1, 4-2-4, 3-4-2-1, 4-1-2-1-2, 5-2-3, 4-3-1-2`.
- Mentality/Pressing/Marking values must match the existing enum names exactly: `Mentality` = `DEFENSIVE | BALANCED | ATTACKING`; `Pressing` = `LOW | MEDIUM | HIGH`; `Marking` = `ZONAL | MAN_TIGHT`.
- Set-piece takers (penalty/freekick/corner) stay computed at match time from the XI's best finisher/passer — unchanged, not authored.
- `TacticsService.profileFor(UUID saveGameId, UUID clubId, List<Player> xi)`'s public signature must not change — existing callers `FixtureSimulator.java:148-149` and `LiveMatchService.java:352-354` are untouched.
- No dynamic in-match AI tactical adjustment (protecting a lead, chasing a game) — out of scope.
- Work directly on `main`, no feature branches.
- Never run `git commit` or `git push` — prepare commit messages only; the user runs them.
- Constructor injection only (no field `@Autowired`); `Optional` for possibly-absent lookups; no `java.util.Date`.
- Run tests with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` from `backend/`.

---

### Task 1: `clubs` schema + `Club` entity — tactical identity columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V21__club_tactical_identity.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/club/Club.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/club/ClubTest.java` (new)

**Interfaces:**
- Produces: `Club.getDefaultFormation()/setDefaultFormation(String)`, `getDefaultMentality()/setDefaultMentality(String)`, `getDefaultPressing()/setDefaultPressing(String)`, `getDefaultMarking()/setDefaultMarking(String)` — all plain `String` (not `@Enumerated`), mirroring how `Lineup.formation` is already a plain string. Every later task that reads a club's identity uses these four getters.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/club/ClubTest.java`:

```java
package br.com.carvoeiromanager.club;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClubTest {

    @Test
    void tacticalIdentityFieldsDefaultToNullAndAreSettable() {
        Club club = Club.create(java.util.UUID.randomUUID(), "Test FC", "TST");

        assertThat(club.getDefaultFormation()).isNull();
        assertThat(club.getDefaultMentality()).isNull();
        assertThat(club.getDefaultPressing()).isNull();
        assertThat(club.getDefaultMarking()).isNull();

        club.setDefaultFormation("4-3-3");
        club.setDefaultMentality("ATTACKING");
        club.setDefaultPressing("HIGH");
        club.setDefaultMarking("MAN_TIGHT");

        assertThat(club.getDefaultFormation()).isEqualTo("4-3-3");
        assertThat(club.getDefaultMentality()).isEqualTo("ATTACKING");
        assertThat(club.getDefaultPressing()).isEqualTo("HIGH");
        assertThat(club.getDefaultMarking()).isEqualTo("MAN_TIGHT");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.club.ClubTest"` (from `backend/`)
Expected: FAIL — `Club` has no method `getDefaultFormation()` (compile error).

- [ ] **Step 3: Add the migration**

Create `backend/src/main/resources/db/migration/V21__club_tactical_identity.sql`:

```sql
ALTER TABLE clubs
    ADD COLUMN default_formation VARCHAR(10),
    ADD COLUMN default_mentality VARCHAR(12),
    ADD COLUMN default_pressing VARCHAR(8),
    ADD COLUMN default_marking VARCHAR(10);
```

- [ ] **Step 4: Add the entity fields**

In `backend/src/main/java/br/com/carvoeiromanager/club/Club.java`, add 4 new `@Column` fields right after `secondaryColor` (after line 42, before the `balance` field), and their getters/setters alongside the existing ones:

```java
    @Column(name = "default_formation", length = 10)
    private String defaultFormation;

    @Column(name = "default_mentality", length = 12)
    private String defaultMentality;

    @Column(name = "default_pressing", length = 8)
    private String defaultPressing;

    @Column(name = "default_marking", length = 10)
    private String defaultMarking;
```

Add getters next to the existing `getPrimaryColor()`/`getSecondaryColor()` (after line 85):

```java
    public String getDefaultFormation() { return defaultFormation; }
    public String getDefaultMentality() { return defaultMentality; }
    public String getDefaultPressing() { return defaultPressing; }
    public String getDefaultMarking() { return defaultMarking; }
```

Add setters next to the existing `setPrimaryColor()`/`setSecondaryColor()` (after line 90):

```java
    public void setDefaultFormation(String defaultFormation) { this.defaultFormation = defaultFormation; }
    public void setDefaultMentality(String defaultMentality) { this.defaultMentality = defaultMentality; }
    public void setDefaultPressing(String defaultPressing) { this.defaultPressing = defaultPressing; }
    public void setDefaultMarking(String defaultMarking) { this.defaultMarking = defaultMarking; }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.club.ClubTest"` (from `backend/`)
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V21__club_tactical_identity.sql \
        backend/src/main/java/br/com/carvoeiromanager/club/Club.java \
        backend/src/test/java/br/com/carvoeiromanager/club/ClubTest.java
git commit -m "feat(club): add tactical identity columns to clubs table"
```

---

### Task 2: `SeedClub` extension + neutral placeholder backfill

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedClub.java`
- Modify: `backend/src/main/resources/seed/clubs.json`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`

**Interfaces:**
- Consumes: none from Task 1 (independent record/JSON change).
- Produces: `SeedClub.formation()/mentality()/pressing()/marking()` — all `String`, read by `SaveGameService` in Task 5. Every one of the 28 `clubPool` entries in `clubs.json` carries these 4 fields after this task (temporarily identical neutral values — `"4-4-2"/"BALANCED"/"MEDIUM"/"ZONAL"` — for every club; Task 4 overwrites them with real per-club research).

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java` (needs a new import: `import br.com.carvoeiromanager.player.Formation;` at the top, alongside the existing imports):

```java
    @Test
    void everyClubHasAValidTacticalIdentity() {
        SeedCareer data = new SeedLoader().loadCareer();

        java.util.Set<String> validFormations = java.util.Arrays.stream(Formation.values())
                .map(Formation::code)
                .collect(Collectors.toSet());

        assertThat(data.clubPool()).allSatisfy(club -> {
            assertThat(validFormations).as("%s formation", club.shortName()).contains(club.formation());
            assertThat(club.mentality()).as("%s mentality", club.shortName())
                    .isIn("DEFENSIVE", "BALANCED", "ATTACKING");
            assertThat(club.pressing()).as("%s pressing", club.shortName())
                    .isIn("LOW", "MEDIUM", "HIGH");
            assertThat(club.marking()).as("%s marking", club.shortName())
                    .isIn("ZONAL", "MAN_TIGHT");
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.seed.SeedLoaderTest"` (from `backend/`)
Expected: FAIL — compile error, `SeedClub` has no method `formation()`.

- [ ] **Step 3: Extend `SeedClub`**

Replace `backend/src/main/java/br/com/carvoeiromanager/seed/SeedClub.java` in full:

```java
package br.com.carvoeiromanager.seed;

import java.util.List;

public record SeedClub(
        String name,
        String shortName,
        String commonName,
        String nickname,
        String primaryColor,
        String secondaryColor,
        String formation,
        String mentality,
        String pressing,
        String marking,
        List<SeedPlayer> players
) {}
```

- [ ] **Step 4: Backfill `clubs.json` with neutral placeholders**

Run this from `backend/src/main/resources/seed/`:

```bash
python3 -c "
import re

with open('clubs.json') as f:
    lines = f.readlines()

out = []
for line in lines:
    out.append(line)
    if re.match(r'^      \"secondaryColor\": \".*\",\$', line):
        out.append('      \"formation\": \"4-4-2\",\n')
        out.append('      \"mentality\": \"BALANCED\",\n')
        out.append('      \"pressing\": \"MEDIUM\",\n')
        out.append('      \"marking\": \"ZONAL\",\n')

with open('clubs.json', 'w') as f:
    f.writelines(out)
"
```

Verify exactly 28 insertions landed and the file still parses:

```bash
python3 -c "
import json
d = json.load(open('clubs.json'))
assert len(d['clubPool']) == 28
for c in d['clubPool']:
    assert c['formation'] == '4-4-2'
    assert c['mentality'] == 'BALANCED'
    assert c['pressing'] == 'MEDIUM'
    assert c['marking'] == 'ZONAL'
print('OK: 28 clubs backfilled')
"
```

Expected output: `OK: 28 clubs backfilled`

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.seed.SeedLoaderTest"` (from `backend/`)
Expected: PASS (all tests in the class, including the new one)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/seed/SeedClub.java \
        backend/src/main/resources/seed/clubs.json \
        backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java
git commit -m "feat(seed): add tactical identity fields to SeedClub (neutral placeholder values)"
```

---

### Task 3: Research real tactical identities for all 28 clubs (parallel research)

**Execution note:** unlike other tasks, this one is NOT delegated to a single implementer subagent. The controller (you, running subagent-driven-development) dispatches the 5 batches below directly as parallel `Agent` calls, then collects their output before Task 4. This mirrors how the Série B roster research was executed (parallel research agents per batch, merged afterward).

**Files:**
- Create (scratchpad, one per batch): `<scratchpad>/tactics-batch-a.json` ... `<scratchpad>/tactics-batch-e.json`

**Interfaces:**
- Produces: 5 JSON files, each a flat object mapping `shortName -> {"formation": ..., "mentality": ..., "pressing": ..., "marking": ..., "source": "<one-line rationale>"}`. Task 4 reads all 5 and merges them into `clubs.json`.

**Batches** (shortName — full club name):

- Batch A: `CRI` — Criciúma Esporte Clube; `AVA` — Avaí Futebol Clube; `GOI` — Goiás Esporte Clube; `NOV` — Grêmio Novorizontino; `SPT` — Sport Club do Recife; `CEA` — Ceará Sporting Club
- Batch B: `VIL` — Vila Nova Futebol Clube; `OPE` — Operário Ferroviário Esporte Clube; `CRB` — Clube de Regatas Brasil; `PPT` — Ponte Preta Futebol Clube; `BOT` — Botafogo Futebol Clube (SP); `FOR` — Fortaleza Esporte Clube
- Batch C: `AME` — América Futebol Clube (MG); `ATH` — Athletic Club (São João del-Rei); `ATG` — Atlético Clube Goianiense; `CUI` — Cuiabá Esporte Clube; `JUV` — Esporte Clube Juventude; `LON` — Londrina Esporte Clube
- Batch D: `NAU` — Clube Náutico Capibaribe; `SBE` — São Bernardo Futebol Clube; `FIG` — Figueirense Futebol Clube; `CHA` — Associação Chapecoense de Futebol
- Batch E: `BRU` — Brusque Futebol Clube; `MAR` — Clube Náutico Marcílio Dias; `BAR` — Associação Desportiva Barra; `CON` — Associação Esportiva Concórdia; `HER` — Hercílio Luz Futebol Clube; `CAM` — Camboriú Futebol Clube

- [ ] **Step 1: Dispatch the 5 batches in parallel**

For each batch, dispatch an `Agent` call (`subagent_type: general-purpose`, model: `sonnet`) with this prompt template, substituting `{BATCH_LETTER}` and `{CLUB_LIST}` (the shortName — full name list above) and `{OUTPUT_PATH}` (a scratchpad path, e.g. `<scratchpad>/tactics-batch-a.json`):

```
Research the real 2026-season tactical identity of these Brazilian football
clubs: {CLUB_LIST}.

For each club, find its current (2026) head coach and the formation/style they
actually use. Convert what you find into exactly these 4 fields:

- formation: one of these 16 codes ONLY (pick the closest real match):
  4-4-2, 4-3-3, 3-5-2, 5-3-2, 4-5-1, 4-2-3-1, 4-1-4-1, 4-3-2-1, 3-4-3, 4-4-1-1,
  5-4-1, 4-2-4, 3-4-2-1, 4-1-2-1-2, 5-2-3, 4-3-1-2
- mentality: DEFENSIVE, BALANCED, or ATTACKING (how the team approaches games —
  a possession/high-line attacking side is ATTACKING, a low-block counter side
  is DEFENSIVE, most sit at BALANCED)
- pressing: LOW, MEDIUM, or HIGH (how aggressively they press without the ball)
- marking: ZONAL or MAN_TIGHT (their defensive marking scheme — most Brazilian
  sides are ZONAL; only mark MAN_TIGHT if you find clear evidence of an
  aggressive man-marking scheme)

For smaller clubs (state-league / Catarinense-only sides) where a specific 2026
system isn't confidently findable, infer a reasonable style from the club's
division level and general reputation rather than leaving anything blank — note
this in "source" as inferred, not confirmed.

Write your findings as a single JSON object to {OUTPUT_PATH}, keyed by
shortName, e.g.:

{
  "CRI": {"formation": "4-3-3", "mentality": "ATTACKING", "pressing": "HIGH", "marking": "ZONAL", "source": "2026 coach X plays high-press 4-3-3 per <source>"},
  "AVA": {"formation": "4-4-2", "mentality": "BALANCED", "pressing": "MEDIUM", "marking": "ZONAL", "source": "..."}
}

Every one of the {N} clubs in your batch must appear as a key. Use exactly one
of the allowed values per field — no other strings. Report back the file path
you wrote and a one-line summary per club.
```

- [ ] **Step 2: Validate each batch file**

For each of the 5 output files, confirm it parses and every club in that batch's list appears with all 4 required fields holding an allowed value:

```bash
python3 -c "
import json
allowed_formations = {'4-4-2','4-3-3','3-5-2','5-3-2','4-5-1','4-2-3-1','4-1-4-1',
    '4-3-2-1','3-4-3','4-4-1-1','5-4-1','4-2-4','3-4-2-1','4-1-2-1-2','5-2-3','4-3-1-2'}
allowed_mentality = {'DEFENSIVE','BALANCED','ATTACKING'}
allowed_pressing = {'LOW','MEDIUM','HIGH'}
allowed_marking = {'ZONAL','MAN_TIGHT'}

import sys
path = sys.argv[1]
d = json.load(open(path))
for short, v in d.items():
    assert v['formation'] in allowed_formations, f'{short}: bad formation {v[\"formation\"]}'
    assert v['mentality'] in allowed_mentality, f'{short}: bad mentality {v[\"mentality\"]}'
    assert v['pressing'] in allowed_pressing, f'{short}: bad pressing {v[\"pressing\"]}'
    assert v['marking'] in allowed_marking, f'{short}: bad marking {v[\"marking\"]}'
print(f'{path}: OK, {len(d)} clubs')
" "<path-to-batch-file>"
```

Expected: `OK, <N> clubs` for each of the 5 files, with the club count matching that batch's list size (6, 6, 6, 4, 6). If a research agent produced an invalid value or missed a club, re-dispatch just that batch with the same prompt before continuing.

This task has no code to commit — its output feeds directly into Task 4.

---

### Task 4: Merge researched identities into `clubs.json`, seed tests green

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`

**Interfaces:**
- Consumes: the 5 validated batch JSON files from Task 3 (paths supplied by the controller when dispatching this task).
- Produces: `clubs.json` where all 28 clubs carry their real researched identity (Task 2's placeholders are gone).

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`:

```java
    @Test
    void tacticalIdentitiesShowRealVarietyAcrossThePool() {
        SeedCareer data = new SeedLoader().loadCareer();

        java.util.Set<String> formations = data.clubPool().stream()
                .map(SeedClub::formation).collect(Collectors.toSet());
        java.util.Set<String> mentalities = data.clubPool().stream()
                .map(SeedClub::mentality).collect(Collectors.toSet());
        java.util.Set<String> pressings = data.clubPool().stream()
                .map(SeedClub::pressing).collect(Collectors.toSet());

        assertThat(formations).as("formation variety across the pool").hasSizeGreaterThanOrEqualTo(5);
        assertThat(mentalities).as("mentality variety across the pool")
                .containsExactlyInAnyOrder("DEFENSIVE", "BALANCED", "ATTACKING");
        assertThat(pressings).as("pressing variety across the pool")
                .containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.seed.SeedLoaderTest"` (from `backend/`)
Expected: FAIL — every club still has the Task 2 placeholder values (`"4-4-2"/"BALANCED"/"MEDIUM"/"ZONAL"`), so `mentalities` and `pressings` each have size 1, not 3.

- [ ] **Step 3: Combine the 5 batch files into one lookup**

```bash
python3 -c "
import json

combined = {}
for path in ['<batch-a-path>', '<batch-b-path>', '<batch-c-path>', '<batch-d-path>', '<batch-e-path>']:
    combined.update(json.load(open(path)))

assert len(combined) == 28, f'expected 28 clubs, got {len(combined)}'
json.dump(combined, open('/tmp/tactics-combined.json', 'w'))
print('combined OK:', len(combined), 'clubs')
"
```

(Substitute the 5 real batch file paths from Task 3.) Expected: `combined OK: 28 clubs`.

- [ ] **Step 4: Merge into `clubs.json`**

Run from `backend/src/main/resources/seed/`:

```bash
python3 -c "
import json, re

identities = json.load(open('/tmp/tactics-combined.json'))

with open('clubs.json') as f:
    lines = f.readlines()

out = []
current_short = None
for line in lines:
    m = re.match(r'^      \"shortName\": \"(\w+)\",\$', line)
    if m:
        current_short = m.group(1)
    m2 = re.match(r'^      \"(formation|mentality|pressing|marking)\": \".*\",\$', line)
    if m2 and current_short in identities:
        key = m2.group(1)
        value = identities[current_short][key]
        out.append(f'      \"{key}\": \"{value}\",\n')
        continue
    out.append(line)

with open('clubs.json', 'w') as f:
    f.writelines(out)
print('merge OK')
"
```

Verify the file still parses, still has 28 clubs, and the line count is unchanged from Task 2 (values replaced in place, no lines added/removed):

```bash
python3 -c "
import json
d = json.load(open('clubs.json'))
assert len(d['clubPool']) == 28
for c in d['clubPool']:
    assert c['formation'] and c['mentality'] and c['pressing'] and c['marking']
print('OK: all 28 clubs carry a tactical identity')
"
git diff --stat backend/src/main/resources/seed/clubs.json
```

Expected: `OK: all 28 clubs carry a tactical identity`; the `git diff --stat` line count should show 0 insertions/0 deletions beyond what Task 2 already committed (only the values on existing lines changed since Task 2, so a diff against Task 2's commit shows ~112 modified lines, not added/removed ones — if `git diff --stat` against HEAD shows insertions or deletions, the line-based merge missed the `secondaryColor`-adjacent lines Task 2 inserted; check for a club whose `shortName` line uses different spacing).

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.seed.SeedLoaderTest"` (from `backend/`)
Expected: PASS (all tests in the class)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/seed/clubs.json \
        backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java
git commit -m "feat(seed): author real 2026 tactical identities for all 28 clubs"
```

---

### Task 5: `SaveGameService` wiring — persist club identity at career creation

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java:104-108`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`

**Interfaces:**
- Consumes: `Club.setDefaultFormation/setDefaultMentality/setDefaultPressing/setDefaultMarking` (Task 1), `SeedClub.formation()/mentality()/pressing()/marking()` (Tasks 2 & 4).
- Produces: every `Club` row created by `createCareer()` carries its seed club's tactical identity — read by `TacticsService` in Task 6.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`. First add these imports at the top, alongside the existing ones:

```java
import br.com.carvoeiromanager.seed.SeedCareer;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedLoader;

import java.util.stream.Collectors;
```

Then add the test:

```java
    @Test
    void clubsCarryTheirSeedTacticalIdentity() {
        User user = User.create("tacid_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId());

        var clubs = clubRepository.findBySaveGameId(response.id());
        assertThat(clubs).allSatisfy(club -> {
            assertThat(club.getDefaultFormation()).as("%s formation", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultMentality()).as("%s mentality", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultPressing()).as("%s pressing", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultMarking()).as("%s marking", club.getShortName()).isNotBlank();
        });

        // Spot-check one Série B club and one Catarinense-only club against the
        // exact seed values they were loaded from.
        SeedCareer seed = new SeedLoader().loadCareer();
        var seedByShortName = seed.clubPool().stream()
                .collect(Collectors.toMap(SeedClub::shortName, sc -> sc));

        var nau = clubs.stream().filter(c -> c.getShortName().equals("NAU")).findFirst().orElseThrow();
        SeedClub seedNau = seedByShortName.get("NAU");
        assertThat(nau.getDefaultFormation()).isEqualTo(seedNau.formation());
        assertThat(nau.getDefaultMentality()).isEqualTo(seedNau.mentality());
        assertThat(nau.getDefaultPressing()).isEqualTo(seedNau.pressing());
        assertThat(nau.getDefaultMarking()).isEqualTo(seedNau.marking());

        var bar = clubs.stream().filter(c -> c.getShortName().equals("BAR")).findFirst().orElseThrow();
        SeedClub seedBar = seedByShortName.get("BAR");
        assertThat(bar.getDefaultFormation()).isEqualTo(seedBar.formation());
        assertThat(bar.getDefaultMentality()).isEqualTo(seedBar.mentality());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.career.SaveGameServiceIT"` (from `backend/`)
Expected: FAIL — `club.getDefaultFormation()` returns `null` for every club (not yet wired).

- [ ] **Step 3: Wire the assignment in `SaveGameService`**

In `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`, right after the existing color assignment (currently lines 107-108):

```java
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
```

add:

```java
            club.setDefaultFormation(seedClub.formation());
            club.setDefaultMentality(seedClub.mentality());
            club.setDefaultPressing(seedClub.pressing());
            club.setDefaultMarking(seedClub.marking());
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.career.SaveGameServiceIT"` (from `backend/`)
Expected: PASS (all tests in the class)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
        backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java
git commit -m "feat(career): persist each club's seed tactical identity at career creation"
```

---

### Task 6: `TacticsService` reads per-club identity; full suite green

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java`

**Interfaces:**
- Consumes: `Club.getDefaultFormation/getDefaultMentality/getDefaultPressing/getDefaultMarking` (Task 1, populated by Task 5), `ClubRepository.findById(UUID)` (existing, `JpaRepository`).
- Produces: `TacticsService.profileFor(UUID saveGameId, UUID clubId, List<Player> xi)` — unchanged public signature, now returns a per-club profile for AI clubs instead of one shared default. `TacticsService.defaultProfile(UUID clubId, List<Player> xi)` — signature changed (was `defaultProfile(List<Player> xi)`); the only caller is `profileFor` itself, updated in this task.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java`. First add these imports at the top, alongside the existing ones:

```java
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
```

And add this field alongside the other `@Autowired` fields:

```java
    @Autowired ClubRepository clubRepository;
```

Then add the test:

```java
    @Test
    void aiClubsUseTheirOwnSeedTacticalIdentityNotAGlobalDefault() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID managedClub = career.save().managedClubId();

        var aiClubs = clubRepository.findBySaveGameId(save).stream()
                .filter(c -> !c.getId().equals(managedClub))
                .toList();

        // Find two AI clubs whose seed identities actually differ, so the
        // assertion is a real signal, not two clubs that happen to match.
        Club clubA = aiClubs.get(0);
        Club clubB = aiClubs.stream()
                .filter(c -> !c.getDefaultFormation().equals(clubA.getDefaultFormation())
                        || !c.getDefaultMentality().equals(clubA.getDefaultMentality())
                        || !c.getDefaultPressing().equals(clubA.getDefaultPressing())
                        || !c.getDefaultMarking().equals(clubA.getDefaultMarking()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected at least two AI clubs with distinct identities"));

        List<Player> xiA = playerRepository.findByClubId(clubA.getId());
        List<Player> xiB = playerRepository.findByClubId(clubB.getId());

        TacticalProfile profileA = tacticsService.profileFor(save, clubA.getId(), xiA);
        TacticalProfile profileB = tacticsService.profileFor(save, clubB.getId(), xiB);

        assertThat(profileA.formation()).isEqualTo(clubA.getDefaultFormation());
        assertThat(profileA.mentality().name()).isEqualTo(clubA.getDefaultMentality());
        assertThat(profileA.pressing().name()).isEqualTo(clubA.getDefaultPressing());
        assertThat(profileA.marking().name()).isEqualTo(clubA.getDefaultMarking());

        assertThat(profileB.formation()).isEqualTo(clubB.getDefaultFormation());
        assertThat(profileA).isNotEqualTo(profileB);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.TacticsServiceIT"` (from `backend/`)
Expected: FAIL — `profileA.formation()` is `"4-4-2"` (the old hardcoded default) regardless of `clubA`'s seed identity.

- [ ] **Step 3: Make `TacticsService` club-aware**

Replace `backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java` in full:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Pressing;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds the {@link TacticalProfile} for a club's match: the managed club's
 * persisted lineup tactics when present, otherwise that club's own seed-authored
 * identity (formation/mentality/pressing/marking, persisted on {@link Club} at
 * career creation for every club including AI clubs). Falls back to a neutral
 * default only when the club or an identity field cannot be found (e.g. a stale
 * or synthetic clubId).
 */
@Service
public class TacticsService {

    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final ClubRepository clubRepository;

    public TacticsService(LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository,
                          ClubRepository clubRepository) {
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.clubRepository = clubRepository;
    }

    public TacticalProfile profileFor(UUID saveGameId, UUID clubId, List<Player> xi) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> fromLineup(lineup, xi))
                .orElseGet(() -> defaultProfile(clubId, xi));
    }

    public TacticalProfile defaultProfile(UUID clubId, List<Player> xi) {
        Club club = clubRepository.findById(clubId).orElse(null);

        Mentality mentality = parseOrDefault(
                club != null ? club.getDefaultMentality() : null, Mentality::valueOf, Mentality.BALANCED);
        String formation = club != null && club.getDefaultFormation() != null
                ? club.getDefaultFormation() : "4-4-2";
        Pressing pressing = parseOrDefault(
                club != null ? club.getDefaultPressing() : null, Pressing::valueOf, Pressing.MEDIUM);
        Marking marking = parseOrDefault(
                club != null ? club.getDefaultMarking() : null, Marking::valueOf, Marking.ZONAL);

        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        }
        UUID bestFinisher = xi.stream().max(Comparator.comparingInt(Player::getFinishing))
                .map(Player::getId).orElse(null);
        UUID bestPasser = xi.stream().max(Comparator.comparingInt(Player::getPassing))
                .map(Player::getId).orElse(null);
        return new TacticalProfile(mentality, formation, pressing, marking,
                roles, bestFinisher, bestPasser, bestFinisher);
    }

    private <T extends Enum<T>> T parseOrDefault(String raw, Function<String, T> parser, T fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private TacticalProfile fromLineup(Lineup lineup, List<Player> xi) {
        Map<UUID, PlayerRole> persisted = new HashMap<>();
        lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .forEach(lp -> persisted.put(lp.getPlayerId(), lp.getRole()));
        // Fill any XI member missing a persisted role with its band default.
        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), persisted.getOrDefault(p.getId(), PlayerRole.defaultFor(p.getPosition())));
        }
        return new TacticalProfile(
                lineup.getMentality(), lineup.getFormation(), lineup.getPressing(), lineup.getMarking(),
                roles, lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.TacticsServiceIT"` (from `backend/`)
Expected: PASS (all tests in the class, including the pre-existing `defaultProfileWhenNoLineupRow` — it calls `profileFor` with a random, non-existent `clubId`, so `clubRepository.findById(...)` returns empty and it still hits the `BALANCED/4-4-2/MEDIUM/ZONAL` fallback unchanged).

- [ ] **Step 5: Run the full backend suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`)
Expected: BUILD SUCCESSFUL, all tests green (no regressions in `FixtureSimulator`, `LiveMatchEngine`, `MatchSimulationService`, or any other consumer of `TacticsService`).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java \
        backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java
git commit -m "feat(match): AI clubs use their own seed tactical identity, not a shared default"
```
