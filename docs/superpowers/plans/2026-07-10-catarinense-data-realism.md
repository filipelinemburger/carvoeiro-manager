# Catarinense Data Realism Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the 8 Catarinense-only clubs real, researched 2026 rosters with fully authored attributes (Part A), then correct player names across all 28 pool clubs to how each real player is actually publicly known (Part B).

**Architecture:** Pure data change to `backend/src/main/resources/seed/clubs.json` plus test updates — no schema/loader/entity changes. Part A: parallel web-research agents author full roster replacements (players + attributes) for the 8 Catarinense-only clubs, written as per-club JSON fragments under `.seed-work/clubs/`, validated, then merged. Part B: parallel web-research agents verify/correct player names across all 28 clubs, written as per-club correction-mapping files under `.seed-work/names/`, validated, then merged. A Python formatter (`seedfmt.py`) preserves the seed's exact house style throughout.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), Python 3 for seed tooling, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-10-catarinense-data-realism-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task that touches tracked files ends by committing its own tested work. `git push` is never run without an explicit user ask.
- `Position` enum values are exactly `GK, DEF, MID, ATT` (map laterais→DEF, volantes/meias→MID, pontas/centroavantes→ATT).
- All attribute and anchor values are ints in 1–99.
- Player JSON key order: `name, position, age, nationality, pace, finishing, passing, tackling, goalkeeping, stamina, attributes`. Attributes key order (22 keys, exactly): `drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge, visao, posicionamento, decisao, compostura, concentracao, determinacao, agressividade, lideranca, aceleracao, forca, resistencia, equilibrio, agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes`.
- Club JSON key order: `name, shortName, commonName, nickname, primaryColor, secondaryColor, formation, mentality, pressing, marking, players` — the seed carries tactical-identity fields (`formation`/`mentality`/`pressing`/`marking`) added by an earlier project; any tooling that rewrites the seed MUST preserve them (verified below — the naive Série B-era formatter would silently drop them).
- Seed JSON style: 2-space indent; each player is ONE line: `{ "name": ..., ... "attributes": { ... } }` with a space after `{` and before `}`; club identity/tactical fields one per line. Enforced by `.seed-work/seedfmt.py`'s round-trip check.
- Nationality in Portuguese (`"Brasil"`, `"Argentina"`, `"Venezuela"`, …); ages as of the 2026 season.
- Working dir for all commands: repo root `/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager` unless stated. Backend test commands: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` — use a long timeout (600000ms), the full suite takes several minutes.
- The 8 Catarinense-only clubs (Part A target): `FIG, CHA, BRU, MAR, BAR, CON, HER, CAM`. The 20 Série B clubs: `CRI, AVA, GOI, NOV, SPT, CEA, VIL, OPE, CRB, PPT, BOT, FOR, AME, ATH, ATG, CUI, JUV, LON, NAU, SBE`. All 28 together are the Part B target.
- Criciúma (CRI)'s roster composition and attributes stay exactly as authored today — Part A never touches CRI. Part B MAY rename CRI players (that is exactly the user's own reported bug: CRI's "Diego" needs correcting).
- `.seed-work/` is temporary tooling — never `git add`ed, deleted in the final task.

---

### Task 1: Update tests to the new expectations (red)

**Files:**
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java` (full rewrite)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java` (insert one new test after `newClubPlayersGetAuthoredAttributesThroughCareerCreation`, currently ending at line 156)

**Interfaces:**
- Produces: the executable definition of "done" for the whole plan. Some assertions stay RED until Task 4 (Part A merge); one stays RED until Task 6 (Part B merge) — this is intentional and expected, not a bug in this task.

- [ ] **Step 1: Rewrite `SeedLoaderTest` with the new invariants**

Replace the entire content of `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java` with:

```java
package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.Formation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {

    private static final Set<String> VALID_POSITIONS = Set.of("GK", "DEF", "MID", "ATT");

    /** The real 20 clubs of the 2026 Brasileirão Série B. */
    private static final Set<String> SERIE_B_2026 = Set.of(
            "CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
            "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE");

    @Test
    void loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.competitions().get(0).name()).isEqualTo("Campeonato Catarinense");
        assertThat(data.clubPool()).isNotEmpty();
        assertThat(data.clubPool().size() % 2).isZero();
        assertThat(data.clubPool()).anyMatch(c -> c.shortName().equals("CRI"));
        // Every club — Série B and Catarinense-only alike — now carries a full authored
        // squad (>= 18 players); no club is left at the old thin 14-15 placeholder size.
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(c.players()).hasSizeGreaterThanOrEqualTo(18));
    }

    @Test
    void serieBHasTheRealTwenty2026Members() {
        SeedCareer data = new SeedLoader().loadCareer();

        SeedCompetition serieB = data.competitions().stream()
                .filter(c -> c.name().equals("Série B")).findFirst().orElseThrow();
        assertThat(serieB.memberShortNames())
                .containsExactlyInAnyOrderElementsOf(SERIE_B_2026);
    }

    @Test
    void clubPoolHasTwentyEightClubsAllInSomeCompetition() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).hasSize(28);

        Set<String> members = data.competitions().stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(members).as("club %s has no competition", c.shortName())
                        .contains(c.shortName()));
        Set<String> pool = data.clubPool().stream()
                .map(SeedClub::shortName).collect(Collectors.toSet());
        assertThat(members).allSatisfy(m ->
                assertThat(pool).as("member %s missing from pool", m).contains(m));
    }

    @Test
    void everyPlayerHasValidPosition() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club ->
                assertThat(club.players()).allSatisfy(player ->
                        assertThat(VALID_POSITIONS).contains(player.position())));
    }

    @Test
    void everyClubHasAtLeastOneGoalkeeper() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club ->
                assertThat(club.players())
                        .anyMatch(p -> "GK".equals(p.position())));
    }

    @Test
    void seasonYearIs2026() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.competitions()).allSatisfy(c ->
                assertThat(c.seasonYear()).isEqualTo(2026));
    }

    @Test
    void everyPoolPlayerCarriesAuthoredAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        // After the Catarinense data-realism pass, every one of the 28 pool clubs has a
        // hand-authored roster — there are no more derived-attribute players in the seed.
        assertThat(data.clubPool()).allSatisfy(club -> assertThat(club.players())
                .allSatisfy(p -> assertThat(p.attributes())
                        .as("%s %s should be authored", club.shortName(), p.name())
                        .isNotNull()));
    }

    @Test
    void everySquadHasRealisticSizeAndGoalkeeperCover() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).allSatisfy(club -> {
            // CRI keeps its pre-existing 27-player authored squad; every other club
            // (Série B and Catarinense-only alike) targets 18-23.
            int max = club.shortName().equals("CRI") ? 27 : 23;
            assertThat(club.players()).as("%s squad size", club.shortName()).hasSizeBetween(18, max);
            long gks = club.players().stream()
                    .filter(p -> "GK".equals(p.position())).count();
            assertThat(gks).as("%s GK count", club.shortName()).isBetween(2L, 3L);
        });
    }

    @Test
    void authoredAttributesHoldTheResearchedValues() {
        SeedCareer data = new SeedLoader().loadCareer();

        SeedPlayer otero = data.clubPool().stream()
                .filter(c -> c.shortName().equals("CRI")).findFirst().orElseThrow()
                .players().stream().filter(p -> p.name().equals("Otero")).findFirst().orElseThrow();

        // Otero is a documented free-kick/long-shot playmaker — the authored block reflects that.
        assertThat(otero.attributes().bolaParada()).isEqualTo(88);
        assertThat(otero.attributes().chuteLonge()).isEqualTo(82);
        assertThat(otero.attributes().visao()).isEqualTo(80);
    }

    @Test
    void criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego() {
        SeedCareer data = new SeedLoader().loadCareer();

        // The user's own example of the known-as naming bug: Criciúma's roster had a
        // player named simply "Diego" who is publicly known by a fuller real name.
        // The known-as correction pass must have fixed this specific, named case.
        Set<String> criciumaNames = data.clubPool().stream()
                .filter(c -> c.shortName().equals("CRI")).findFirst().orElseThrow()
                .players().stream().map(SeedPlayer::name).collect(Collectors.toSet());
        assertThat(criciumaNames).doesNotContain("Diego");
    }

    @Test
    void everyClubHasAValidTacticalIdentity() {
        SeedCareer data = new SeedLoader().loadCareer();

        Set<String> validFormations = java.util.Arrays.stream(Formation.values())
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

    @Test
    void tacticalIdentitiesShowRealVarietyAcrossThePool() {
        SeedCareer data = new SeedLoader().loadCareer();

        Set<String> formations = data.clubPool().stream()
                .map(SeedClub::formation).collect(Collectors.toSet());
        Set<String> mentalities = data.clubPool().stream()
                .map(SeedClub::mentality).collect(Collectors.toSet());
        Set<String> pressings = data.clubPool().stream()
                .map(SeedClub::pressing).collect(Collectors.toSet());

        assertThat(formations).as("formation variety across the pool").hasSizeGreaterThanOrEqualTo(5);
        assertThat(mentalities).as("mentality variety across the pool")
                .containsExactlyInAnyOrder("DEFENSIVE", "BALANCED", "ATTACKING");
        assertThat(pressings).as("pressing variety across the pool")
                .containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH");
    }
}
```

This removes the old `catarinenseOnlyClubsKeepDerivedAttributes` test (its invariant is being
deliberately inverted by this plan), generalizes `everySerieBPlayerCarriesAuthoredAttributes` and
`serieBSquadsHaveRealisticSizeAndGoalkeeperCover` to cover all 28 clubs, tightens the size floor
in `loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma` from 14 to 18, and adds
`criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`.

- [ ] **Step 2: Add the Catarinense-only authored-squad test to `SaveGameServiceIT`**

In `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`, insert this new
test immediately after `newClubPlayersGetAuthoredAttributesThroughCareerCreation` (which currently
ends at line 156 with a closing `}`), before `creatingCareerSeedsClubFinancialProfilesAndPlayerWages`:

```java
    @Test
    void catarinenseOnlyClubPlayersGetAFullAuthoredSquadThroughCareerCreation() {
        // Given a persisted user
        User user = User.create("tigre_fig", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then the Catarinense-only club Figueirense (FIG) has a full-sized authored
        // squad (18-23 players), not the old thin 14-15 player placeholder roster.
        var fig = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getShortName().equals("FIG"))
                .findFirst().orElseThrow();
        var squad = playerRepository.findByClubId(fig.getId());
        assertThat(squad.size()).isBetween(18, 23);
        assertThat(squad).allSatisfy(p ->
                assertThat(p.attributes()).as("%s attributes", p.getName()).isNotNull());
    }
```

- [ ] **Step 3: Run the seed and career tests to verify they fail for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest' --tests 'br.com.carvoeiromanager.career.SaveGameServiceIT'`

Expected: FAIL — `loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma` (the 8 Catarinense-only
clubs have 14-15 players, not >= 18), `everyPoolPlayerCarriesAuthoredAttributes` (those 8 clubs'
players have null `attributes`), `everySquadHasRealisticSizeAndGoalkeeperCover` (same 8 clubs, size
14-15 not in 18..23), `criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego` (CRI still has a
player literally named "Diego"), `catarinenseOnlyClubPlayersGetAFullAuthoredSquadThroughCareerCreation`
(FIG persists 15 players, not between 18-23). Every other test in both files still PASSES.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java
git commit -m "$(cat <<'EOF'
test(seed): expect authored Catarinense-only rosters and corrected known-as names

Some assertions intentionally stay red until the Part A data merge (Task 4)
and one until the Part B name-correction merge (Task 6) — this is the plan's
executable definition of done, landing in two waves.
EOF
)"
```

---

### Task 2: Seed tooling — formatter, roster validator/merge, name-correction validator/merge

**Files:**
- Create: `.seed-work/seedfmt.py`
- Create: `.seed-work/validate_roster_fragment.py`
- Create: `.seed-work/merge_roster_fragments.py`
- Create: `.seed-work/validate_name_corrections.py`
- Create: `.seed-work/merge_name_corrections.py`
- Create: `.seed-work/verify_part_a_diff.py`
- Create: `.seed-work/verify_name_only_changes.py`

**Interfaces:**
- Consumes: `backend/src/main/resources/seed/clubs.json` (current format, including the
  `formation`/`mentality`/`pressing`/`marking` tactical fields added by an earlier project).
- Produces: `seedfmt.load_seed()/save_seed()/dump_seed()` used by every other script in this
  task and by Tasks 3-7; `validate_roster_fragment.py <fragment.json>` and
  `validate_name_corrections.py <correction.json>` exit 0/1 with a report, used by Task 3/5
  agents; `merge_roster_fragments.py` and `merge_name_corrections.py` used by Tasks 4/6;
  `verify_part_a_diff.py` and `verify_name_only_changes.py` used by Tasks 4/6 to prove the
  diff touched only what it should.

**No commit for this task** — `.seed-work/` is temporary tooling, never `git add`ed (see Global
Constraints). Nothing in this task touches a tracked file.

- [ ] **Step 1: Write the house-style formatter**

Create `.seed-work/seedfmt.py`:

```python
#!/usr/bin/env python3
"""Load/dump clubs.json in its exact house style.

Style: 2-space indent; club identity + tactical-identity fields one per line;
each player a single line `{ "k": v, ... }` with spaces inside braces;
memberShortNames single-line when <= 10 entries, else wrapped 6 per line.
Key order is canonical.
"""
import json

SEED_PATH = "backend/src/main/resources/seed/clubs.json"

CLUB_KEYS = ["name", "shortName", "commonName", "nickname",
             "primaryColor", "secondaryColor", "formation", "mentality",
             "pressing", "marking", "players"]
PLAYER_KEYS = ["name", "position", "age", "nationality", "pace", "finishing",
               "passing", "tackling", "goalkeeping", "stamina", "attributes"]
ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]
COMP_KEYS = ["name", "type", "seasonYear", "memberShortNames"]


def js(v):
    return json.dumps(v, ensure_ascii=False)


def fmt_inline_obj(obj, key_order):
    parts = []
    for k in key_order:
        if k not in obj:
            continue
        v = obj[k]
        if k == "attributes" and isinstance(v, dict):
            parts.append(f'"{k}": {fmt_inline_obj(v, ATTR_KEYS)}')
        else:
            parts.append(f'"{k}": {js(v)}')
    return "{ " + ", ".join(parts) + " }"


def fmt_club(club):
    lines = ["    {"]
    for k in CLUB_KEYS[:-1]:
        if k in club:
            lines.append(f'      "{k}": {js(club[k])},')
    lines.append('      "players": [')
    players = club["players"]
    for i, p in enumerate(players):
        comma = "," if i < len(players) - 1 else ""
        lines.append("        " + fmt_inline_obj(p, PLAYER_KEYS) + comma)
    lines.append("      ]")
    lines.append("    }")
    return "\n".join(lines)


def fmt_members(members):
    if len(members) <= 10:
        return "[" + ", ".join(js(m) for m in members) + "]"
    rows = [members[i:i + 6] for i in range(0, len(members), 6)]
    body = ",\n".join("        " + ", ".join(js(m) for m in row) for row in rows)
    return "[\n" + body + "\n      ]"


def fmt_competition(comp):
    lines = ["    {"]
    lines.append(f'      "name": {js(comp["name"])},')
    lines.append(f'      "type": {js(comp["type"])},')
    lines.append(f'      "seasonYear": {js(comp["seasonYear"])},')
    lines.append(f'      "memberShortNames": {fmt_members(comp["memberShortNames"])}')
    lines.append("    }")
    return "\n".join(lines)


def dump_seed(data):
    clubs = ",\n".join(fmt_club(c) for c in data["clubPool"])
    comps = ",\n".join(fmt_competition(c) for c in data["competitions"])
    return ('{\n  "clubPool": [\n' + clubs + '\n  ],\n'
            + '  "competitions": [\n' + comps + '\n  ]\n}\n')


def load_seed(path=SEED_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_seed(data, path=SEED_PATH):
    with open(path, "w", encoding="utf-8") as f:
        f.write(dump_seed(data))


if __name__ == "__main__":
    # Round-trip check: formatting the current seed must reproduce it byte-for-byte.
    with open(SEED_PATH, encoding="utf-8") as f:
        original = f.read()
    regenerated = dump_seed(json.loads(original))
    if regenerated == original:
        print("ROUND-TRIP OK")
    else:
        import difflib
        diff = list(difflib.unified_diff(
            original.splitlines(), regenerated.splitlines(), lineterm=""))
        print("\n".join(diff[:60]))
        raise SystemExit("ROUND-TRIP FAILED — fix seedfmt before any rewrite")
```

- [ ] **Step 2: Run the round-trip check (this is the formatter's test)**

Run: `python3 .seed-work/seedfmt.py`
Expected: `ROUND-TRIP OK`. This has already been independently verified during plan-writing — the
seed's `formation`/`mentality`/`pressing`/`marking` fields (added after the earlier Série B
project, which is why they're not in that project's archived tooling) round-trip correctly with
the `CLUB_KEYS` list above. If this fails, the diff shows where `dump_seed` diverges from the
file's real style — fix `seedfmt.py` (never the seed) until it passes.

- [ ] **Step 3: Write the roster fragment validator**

Create `.seed-work/validate_roster_fragment.py`:

```python
#!/usr/bin/env python3
"""Validate one Catarinense-only club roster fragment (.seed-work/clubs/<SHORT>.json)."""
import json
import sys

ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]
ANCHORS = ["pace", "finishing", "passing", "tackling", "goalkeeping", "stamina"]
TARGET_CLUBS = {"FIG", "CHA", "BRU", "MAR", "BAR", "CON", "HER", "CAM"}
POSITIONS = {"GK", "DEF", "MID", "ATT"}


def main(path):
    with open(path, encoding="utf-8") as f:
        club = json.load(f)
    errors = []
    sn = club.get("shortName")
    if sn not in TARGET_CLUBS:
        errors.append(f"unexpected shortName {sn!r}")
    extra_keys = set(club.keys()) - {"shortName", "players"}
    if extra_keys:
        errors.append(f"fragment should only carry shortName+players, found extra keys {sorted(extra_keys)}")

    players = club.get("players", [])
    if not 18 <= len(players) <= 23:
        errors.append(f"squad size {len(players)} not in 18..23")

    names = set()
    counts = {"GK": 0, "DEF": 0, "MID": 0, "ATT": 0}
    for p in players:
        n = p.get("name", "?")
        if n in names:
            errors.append(f"duplicate player name {n!r}")
        names.add(n)
        pos = p.get("position")
        if pos not in POSITIONS:
            errors.append(f"{n}: invalid position {pos!r}")
            continue
        counts[pos] += 1
        if not isinstance(p.get("age"), int) or not 16 <= p["age"] <= 42:
            errors.append(f"{n}: bad age {p.get('age')!r}")
        if not isinstance(p.get("nationality"), str) or not p["nationality"]:
            errors.append(f"{n}: missing nationality")
        for a in ANCHORS:
            v = p.get(a)
            if not isinstance(v, int) or not 1 <= v <= 99:
                errors.append(f"{n}: anchor {a}={v!r}")
        attrs = p.get("attributes")
        if not isinstance(attrs, dict):
            errors.append(f"{n}: missing attributes block")
            continue
        missing = [k for k in ATTR_KEYS if k not in attrs]
        extra = [k for k in attrs if k not in ATTR_KEYS]
        if missing:
            errors.append(f"{n}: attributes missing {missing}")
        if extra:
            errors.append(f"{n}: unknown attribute keys {extra}")
        for k, v in attrs.items():
            if k in ATTR_KEYS and (not isinstance(v, int) or not 1 <= v <= 99):
                errors.append(f"{n}: attribute {k}={v!r}")
        if pos == "GK" and isinstance(p.get("finishing"), int) and p["finishing"] > 40:
            errors.append(f"{n}: GK finishing {p['finishing']} > 40")
        if pos != "GK" and isinstance(p.get("goalkeeping"), int) and p["goalkeeping"] > 40:
            errors.append(f"{n}: outfielder goalkeeping {p['goalkeeping']} > 40")

    if not 2 <= counts["GK"] <= 3:
        errors.append(f"GK count {counts['GK']} not in 2..3")
    if counts["DEF"] < 5:
        errors.append(f"only {counts['DEF']} DEF (need >= 5)")
    if counts["MID"] < 5:
        errors.append(f"only {counts['MID']} MID (need >= 5)")
    if counts["ATT"] < 3:
        errors.append(f"only {counts['ATT']} ATT (need >= 3)")

    if errors:
        print(f"INVALID {path}")
        for e in errors:
            print(f" - {e}")
        sys.exit(1)
    print(f"OK {path}: {len(players)} players {counts}")


if __name__ == "__main__":
    main(sys.argv[1])
```

- [ ] **Step 4: Write the roster merge script**

Create `.seed-work/merge_roster_fragments.py`:

```python
#!/usr/bin/env python3
"""Merge validated Catarinense-only roster fragments from .seed-work/clubs/ into the seed.

Every target club already exists in the pool; only its `players` array is replaced.
Identity fields (name, commonName, nickname, colors) and tactical identity
(formation, mentality, pressing, marking) are kept exactly as they are.
"""
import glob
import json
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

EXPECTED = {"FIG", "CHA", "BRU", "MAR", "BAR", "CON", "HER", "CAM"}

fragments = {}
for path in sorted(glob.glob(".seed-work/clubs/*.json")):
    with open(path, encoding="utf-8") as f:
        club = json.load(f)
    fragments[club["shortName"]] = club

missing = EXPECTED - fragments.keys()
unexpected = fragments.keys() - EXPECTED
if missing:
    sys.exit(f"missing fragments for: {sorted(missing)}")
if unexpected:
    sys.exit(f"unexpected fragments: {sorted(unexpected)}")

data = seedfmt.load_seed()
pool_by_sn = {c["shortName"]: c for c in data["clubPool"]}

for sn, frag in fragments.items():
    if sn not in pool_by_sn:
        sys.exit(f"{sn} not found in the current pool")
    pool_by_sn[sn]["players"] = frag["players"]

seedfmt.save_seed(data)
sizes = {sn: len(pool_by_sn[sn]["players"]) for sn in EXPECTED}
print(f"merged {len(fragments)} Catarinense-only rosters; squad sizes: {sizes}")
```

- [ ] **Step 5: Write the Part A diff-scope verifier**

Create `.seed-work/verify_part_a_diff.py`:

```python
#!/usr/bin/env python3
"""Verify the Part A merge changed ONLY the `players` array of the 8 target
clubs — nothing else in the seed (identity fields, tactical fields, or any
other club)."""
import json
import subprocess
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

TARGET_CLUBS = {"FIG", "CHA", "BRU", "MAR", "BAR", "CON", "HER", "CAM"}

before_raw = subprocess.run(
    ["git", "show", f"HEAD:{seedfmt.SEED_PATH}"],
    capture_output=True, text=True, check=True,
).stdout
before = json.loads(before_raw)
after = seedfmt.load_seed()

before_by_sn = {c["shortName"]: c for c in before["clubPool"]}
after_by_sn = {c["shortName"]: c for c in after["clubPool"]}

errors = []
if before_by_sn.keys() != after_by_sn.keys():
    errors.append(f"club set changed: {before_by_sn.keys() ^ after_by_sn.keys()}")

for sn, b_club in before_by_sn.items():
    a_club = after_by_sn.get(sn)
    if a_club is None:
        continue
    if sn in TARGET_CLUBS:
        for key in b_club:
            if key == "players":
                continue
            if b_club[key] != a_club.get(key):
                errors.append(f"{sn}: non-player field {key!r} changed (should be untouched)")
        if b_club["players"] == a_club["players"]:
            errors.append(f"{sn}: players array is unchanged (expected a roster replacement)")
    else:
        if b_club != a_club:
            errors.append(f"{sn}: club changed but is NOT a Part A target — unexpected")

if errors:
    print("FAILED:")
    for e in errors:
        print(f" - {e}")
    sys.exit(1)
print(f"OK — only {sorted(TARGET_CLUBS)}'s player rosters changed; every other club untouched")
```

- [ ] **Step 6: Write the name-correction validator**

Create `.seed-work/validate_name_corrections.py`:

```python
#!/usr/bin/env python3
"""Validate one name-correction file (.seed-work/names/<SHORT>.json)."""
import json
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

ALL_CLUBS = {"CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
             "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE",
             "FIG", "CHA", "BRU", "MAR", "BAR", "CON", "HER", "CAM"}


def main(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    errors = []
    sn = data.get("shortName")
    if sn not in ALL_CLUBS:
        errors.append(f"unexpected shortName {sn!r}")
        print(f"INVALID {path}")
        for e in errors:
            print(f" - {e}")
        sys.exit(1)

    seed = seedfmt.load_seed()
    club = next((c for c in seed["clubPool"] if c["shortName"] == sn), None)
    if club is None:
        sys.exit(f"club {sn} not found in the current seed")
    real_names = {p["name"] for p in club["players"]}

    corrections = data.get("corrections", [])
    seen_old = set()
    new_names_used = set()
    for c in corrections:
        old, new = c.get("old_name"), c.get("new_name")
        if old in seen_old:
            errors.append(f"duplicate correction for old_name {old!r}")
        seen_old.add(old)
        if old not in real_names:
            errors.append(f"old_name {old!r} is not a current {sn} player — did the roster change?")
        if not isinstance(new, str) or not new.strip():
            errors.append(f"{old!r}: new_name is blank")
        elif new == old:
            errors.append(f"{old!r}: new_name is identical to old_name (not a correction)")
        if new in new_names_used:
            errors.append(f"new_name {new!r} used twice in this file")
        new_names_used.add(new)

    if errors:
        print(f"INVALID {path}")
        for e in errors:
            print(f" - {e}")
        sys.exit(1)
    print(f"OK {path}: {len(corrections)} correction(s) for {sn}")


if __name__ == "__main__":
    main(sys.argv[1])
```

- [ ] **Step 7: Write the name-correction merge script**

Create `.seed-work/merge_name_corrections.py`:

```python
#!/usr/bin/env python3
"""Apply validated name corrections from .seed-work/names/*.json onto the seed."""
import glob
import json
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

ALL_CLUBS = {"CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
             "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE",
             "FIG", "CHA", "BRU", "MAR", "BAR", "CON", "HER", "CAM"}

files = sorted(glob.glob(".seed-work/names/*.json"))
by_club = {}
for path in files:
    with open(path, encoding="utf-8") as f:
        payload = json.load(f)
    sn = payload["shortName"]
    if sn in by_club:
        sys.exit(f"duplicate correction file for {sn}: {path} and {by_club[sn][0]}")
    by_club[sn] = (path, payload.get("corrections", []))

missing = ALL_CLUBS - by_club.keys()
if missing:
    sys.exit(f"missing correction files for: {sorted(missing)}")

data = seedfmt.load_seed()
total = 0
per_club = {}
for club in data["clubPool"]:
    sn = club["shortName"]
    _, corrections = by_club[sn]
    by_old_name = {c["old_name"]: c["new_name"] for c in corrections}
    if not by_old_name:
        continue
    changed = 0
    for p in club["players"]:
        if p["name"] in by_old_name:
            p["name"] = by_old_name.pop(p["name"])
            changed += 1
    if by_old_name:
        sys.exit(f"{sn}: correction(s) for {sorted(by_old_name)} did not match any current player")
    total += changed
    per_club[sn] = changed

seedfmt.save_seed(data)
print(f"applied {total} name correction(s) across {len(per_club)} club(s): {per_club}")
```

- [ ] **Step 8: Write the name-only diff-scope verifier**

Create `.seed-work/verify_name_only_changes.py`:

```python
#!/usr/bin/env python3
"""Verify the name-correction merge changed ONLY player `name` fields —
nothing else (attributes, position, age, nationality, squad composition,
club identity, tactical identity)."""
import json
import subprocess
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

before_raw = subprocess.run(
    ["git", "show", f"HEAD:{seedfmt.SEED_PATH}"],
    capture_output=True, text=True, check=True,
).stdout
before = json.loads(before_raw)
after = seedfmt.load_seed()

before_by_sn = {c["shortName"]: c for c in before["clubPool"]}
after_by_sn = {c["shortName"]: c for c in after["clubPool"]}

errors = []
if before_by_sn.keys() != after_by_sn.keys():
    errors.append(f"club set changed: {before_by_sn.keys() ^ after_by_sn.keys()}")

for sn, b_club in before_by_sn.items():
    a_club = after_by_sn.get(sn)
    if a_club is None:
        continue
    for key in b_club:
        if key == "players":
            continue
        if b_club[key] != a_club.get(key):
            errors.append(f"{sn}: non-player field {key!r} changed")
    if len(b_club["players"]) != len(a_club["players"]):
        errors.append(f"{sn}: player count changed ({len(b_club['players'])} -> {len(a_club['players'])})")
        continue
    for bp, ap in zip(b_club["players"], a_club["players"]):
        for key in bp:
            if key == "name":
                continue
            if bp[key] != ap.get(key):
                errors.append(f"{sn}/{bp['name']}: field {key!r} changed (expected only 'name' to change)")

if errors:
    print("FAILED — non-name changes detected:")
    for e in errors[:40]:
        print(f" - {e}")
    sys.exit(1)
print("OK — every club's player list is unchanged except `name` fields")
```

- [ ] **Step 9: Smoke-test the roster validator against real data**

Run:
```bash
mkdir -p .seed-work/clubs .seed-work/names
python3 - <<'EOF'
import json, sys
sys.path.insert(0, ".seed-work")
import seedfmt
data = seedfmt.load_seed()
fig = next(c for c in data["clubPool"] if c["shortName"] == "FIG")
frag = {"shortName": "FIG", "players": fig["players"]}
json.dump(frag, open("/tmp/smoke-fig.json", "w"), ensure_ascii=False)
EOF
python3 .seed-work/validate_roster_fragment.py /tmp/smoke-fig.json
```
Expected: `INVALID /tmp/smoke-fig.json` with exactly one error — `squad size 15 not in 18..23` (FIG's
current placeholder squad has no `attributes` blocks yet, but it does happen to have valid
positions/anchors — the only violation right now is size). This proves the validator parses real
data and enforces the size rule. Then `rm /tmp/smoke-fig.json`.

---

### Task 3: Research and author roster fragments for the 8 Catarinense-only clubs (parallelizable)

**Files:**
- Create: `.seed-work/clubs/<SHORT>.json` — one per club, 8 files.

**Interfaces:**
- Consumes: `.seed-work/validate_roster_fragment.py` (Task 2).
- Produces: validated fragments consumed by `merge_roster_fragments.py` (Task 4). Fragment
  shape: `{ "shortName": "<SHORT>", "players": [...] }`.

**This task runs once per club in the table below. All 8 runs are independent — dispatch them as
parallel research subagents (2 batches of 4). Each subagent gets the full prompt template with its
club's row substituted in.**

**Club table:**

| # | shortName | Club (commonName) |
|---|-----------|--------------------|
| 1 | FIG | Figueirense Futebol Clube (Figueirense) |
| 2 | CHA | Associação Chapecoense de Futebol (Chapecoense) |
| 3 | BRU | Brusque Futebol Clube (Brusque) |
| 4 | MAR | Clube Náutico Marcílio Dias (Marcílio Dias) |
| 5 | BAR | Associação Desportiva Barra (Barra) |
| 6 | CON | Associação Esportiva Concórdia (Concórdia) |
| 7 | HER | Hercílio Luz Futebol Clube (Hercílio Luz) |
| 8 | CAM | Camboriú Futebol Clube (Camboriú) |

**Subagent prompt template (substitute `{SHORT}`, `{CLUB}`):**

````text
You are authoring the real current roster for {CLUB} ({SHORT}) in a football-manager
game seed. Work from repo root /Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager.

1. RESEARCH (WebSearch): find {CLUB}'s current real squad. This club plays ONLY the
   Campeonato Catarinense (Santa Catarina's state championship) in this game's pool — it
   is NOT one of the 20 clubs in the real 2026 Série B. First establish {CLUB}'s actual
   current competitive level (Série C, Série D, or state-league-only — search
   "{CLUB} 2026 divisão" / "{CLUB} temporada 2026"), since that determines how strong the
   authored ratings should be (see CALIBRATE below). Then find the current squad (best
   sources: Transfermarkt "{CLUB} plantel", Wikipedia pt "Elenco atual", ogol.com.br,
   ffc.org.br for Catarinense clubs). Pick 18-23 real players: 2-3 goalkeepers, >=5
   defenders (zagueiros + laterais), >=5 midfielders (volantes + meias), >=3 forwards
   (pontas + centroavantes). Record each player's actual real public "known as" name
   (the name they're genuinely referred to by, not a guessed shortening), age in 2026,
   and nationality in Portuguese ("Brasil", "Argentina", ...).

2. AUTHOR the JSON fragment at .seed-work/clubs/{SHORT}.json:
   { "shortName": "{SHORT}", "players": [...] }
   Each player object, keys in THIS order:
   name, position (GK|DEF|MID|ATT), age, nationality,
   pace, finishing, passing, tackling, goalkeeping, stamina,
   attributes: { drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge,
     visao, posicionamento, decisao, compostura, concentracao, determinacao,
     agressividade, lideranca, aceleracao, forca, resistencia, equilibrio,
     agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes }
   All values ints 1-99. Map positions: laterais -> DEF, volantes/meias -> MID,
   pontas/centroavantes -> ATT.

3. CALIBRATE attributes to each player's real profile, ONE TIER BELOW the Série B
   calibration used elsewhere in this seed (this club plays only the state championship,
   not a national Série B campaign — use the division you researched in step 1 to judge
   where in the band a given squad sits: a club recently in Série C sits toward the top,
   a purely regional club toward the bottom):
   - Regular starters: 50-65 in their strengths.
   - Rotation/bench players: 38-52.
   - Young prospects: 28-45, with relatively higher determinacao.
   - GKs: goalkeeping 55-78 with posicionamentoGk/saidaDeGol correspondingly high; low
     outfield technicals (finishing <= 40).
   - Outfielders: goalkeeping <= 40 (usually 5-15).
   - Older players (32+): lower pace/aceleracao/resistencia, higher
     decisao/compostura/lideranca.
   Reference point from the existing seed (Criciúma, Série B tier — do NOT match this
   level, stay noticeably below it): GK starter Airton has goalkeeping 80, finishing 16;
   star MID Otero has passing 80, visao 80, bolaParada 88 (an elite specialty — only for
   a genuinely documented set-piece taker); solid DEF starter Marcelo Hermes has tackling
   72, cruzamento 74. Your club's equivalents should land clearly under these numbers.

4. VALIDATE: run `python3 .seed-work/validate_roster_fragment.py .seed-work/clubs/{SHORT}.json`
   and fix issues until it prints OK.

5. RETURN: one line per outcome — squad size, position counts, the division you found
   for calibration, data source used and its date, plus any players you were unsure about.
````

- [ ] **Step 1: Author fragments for batch 1 — FIG, CHA, BRU, MAR (4 parallel subagents)**
- [ ] **Step 2: Author fragments for batch 2 — BAR, CON, HER, CAM (4 parallel subagents)**
- [ ] **Step 3: Re-validate all fragments in one sweep**

Run: `for f in .seed-work/clubs/*.json; do python3 .seed-work/validate_roster_fragment.py "$f" || exit 1; done && ls .seed-work/clubs/ | wc -l`
Expected: 8 `OK` lines and count `8`.

- [ ] **Step 4: Spot-check research quality**

For 3 random clubs among the 8, verify 3 named players each actually play there right now (quick
WebSearch). Any wrong player → send that fragment back to a fix-up subagent with the correction,
then re-run Step 3.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 4: Merge Part A rosters into the seed and go green for Part A's scope

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (via `merge_roster_fragments.py`)

**Interfaces:**
- Consumes: 8 validated fragments (Task 3), `merge_roster_fragments.py` + `seedfmt.py` +
  `verify_part_a_diff.py` (Task 2).
- Produces: seed where all 28 clubs carry authored attributes and 18-23-player squads.
  `SeedLoaderTest` fully green EXCEPT `criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`
  (expected — that needs Part B, Task 6). `SaveGameServiceIT`'s
  `catarinenseOnlyClubPlayersGetAFullAuthoredSquadThroughCareerCreation` green.

- [ ] **Step 1: Merge**

Run: `python3 .seed-work/merge_roster_fragments.py`
Expected: `merged 8 Catarinense-only rosters; squad sizes: {...}` with every size between 18 and 23.

- [ ] **Step 2: Verify the diff touched only the 8 target clubs' rosters**

Run: `python3 .seed-work/verify_part_a_diff.py`
Expected: `OK — only ['BAR', 'BRU', 'CAM', 'CHA', 'CON', 'FIG', 'HER', 'MAR']'s player rosters
changed; every other club untouched`.

- [ ] **Step 3: Run the seed and career tests**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest' --tests 'br.com.carvoeiromanager.career.SaveGameServiceIT'`
Expected: every test PASSES **except** `criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`,
which still fails — CRI is untouched by Part A, and that is exactly the invariant Task 6 closes.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/seed/clubs.json backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java
git commit -m "$(cat <<'EOF'
feat(seed): real researched rosters for the 8 Catarinense-only clubs

Figueirense, Chapecoense, Brusque, Marcílio Dias, Barra, Concórdia,
Hercílio Luz and Camboriú now carry real, researched 18-23-player squads
with full hand-authored SeedAttributes, calibrated one tier below the
existing Série B rosters (these clubs play only the state championship).

One test still intentionally red: the known-as name-correction pass
(Part B) lands in the next commit.
EOF
)"
```

---

### Task 5: Research and author name corrections across all 28 clubs (parallelizable)

**Files:**
- Create: `.seed-work/names/<SHORT>.json` — one per club, 28 files.

**Interfaces:**
- Consumes: `.seed-work/validate_name_corrections.py` (Task 2), the post-Task-4 seed (all 28
  clubs now have their real current rosters).
- Produces: validated correction files consumed by `merge_name_corrections.py` (Task 6).
  File shape: `{ "shortName": "<SHORT>", "corrections": [{ "old_name": "...", "new_name": "..." }] }`
  — an empty `corrections` array is valid and expected for clubs needing no fix.

**This task runs once per batch of 4 clubs in the table below (7 batches, 28 clubs). All batches
are independent — dispatch them as parallel research subagents. Each subagent gets the full
prompt template with its batch's club list substituted in.**

**Batches:**

| Batch | Clubs |
|---|---|
| 1 | CRI, AVA, GOI, NOV |
| 2 | SPT, CEA, VIL, OPE |
| 3 | CRB, PPT, BOT, FOR |
| 4 | AME, ATH, ATG, CUI |
| 5 | JUV, LON, NAU, SBE |
| 6 | FIG, CHA, BRU, MAR |
| 7 | BAR, CON, HER, CAM |

**Subagent prompt template (substitute `{CLUB_LIST}`, a comma-separated list of that batch's 4
shortNames):**

````text
You are correcting player names in a football-manager game seed to match how each real
player is actually publicly known. Work from repo root
/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager.

Your assigned clubs (shortNames): {CLUB_LIST}.

For EACH club:
1. READ its current player list from `backend/src/main/resources/seed/clubs.json`
   (search for `"shortName": "<SHORT>"`).
2. For EACH player, RESEARCH (WebSearch) their actual real public "known as" name —
   the name they are actually referred to by in the media/on their kit, not necessarily
   their full legal name and not necessarily a shortened nickname; whichever the real
   player is genuinely known as. Example of the bug this fixes: a real Criciúma player
   publicly known as a fuller name currently shows in the seed as just a bare first
   name — correct toward whatever that specific real player's actual known-as name is.
   Some corrections will be to a fuller name, some to a different short form — there is
   no single uniform rule, follow the real-world fact per player.
3. If a player's current seed name already matches how they're really known, DO NOT
   include them — only list actual corrections.
4. WRITE one file per club at `.seed-work/names/<SHORT>.json`:
   { "shortName": "<SHORT>", "corrections": [
       { "old_name": "<exact current seed name>", "new_name": "<real known-as name>" }
   ] }
   Use an empty `"corrections": []` array if no player on that club needs correcting.
5. VALIDATE each file: run
   `python3 .seed-work/validate_name_corrections.py .seed-work/names/<SHORT>.json`
   and fix issues until it prints OK. `old_name` must be copied EXACTLY (character for
   character) from the current seed — the validator will reject anything that doesn't
   match a current player.
6. RETURN: one line per club — how many corrections, and for each correction the
   old name -> new name plus the source you used to confirm it.
````

- [ ] **Step 1: Batch 1 — CRI, AVA, GOI, NOV (1 subagent, all 4 clubs)**
- [ ] **Step 2: Batch 2 — SPT, CEA, VIL, OPE (1 subagent, all 4 clubs)**
- [ ] **Step 3: Batch 3 — CRB, PPT, BOT, FOR (1 subagent, all 4 clubs)**
- [ ] **Step 4: Batch 4 — AME, ATH, ATG, CUI (1 subagent, all 4 clubs)**
- [ ] **Step 5: Batch 5 — JUV, LON, NAU, SBE (1 subagent, all 4 clubs)**
- [ ] **Step 6: Batch 6 — FIG, CHA, BRU, MAR (1 subagent, all 4 clubs)**
- [ ] **Step 7: Batch 7 — BAR, CON, HER, CAM (1 subagent, all 4 clubs)**

Dispatch all 7 batches in parallel — they're fully independent (each reads a disjoint set of
clubs and writes disjoint output files).

- [ ] **Step 8: Re-validate all correction files in one sweep**

Run: `for f in .seed-work/names/*.json; do python3 .seed-work/validate_name_corrections.py "$f" || exit 1; done && ls .seed-work/names/ | wc -l`
Expected: 28 `OK` lines and count `28`.

- [ ] **Step 9: Spot-check research quality, and specifically confirm the Diego correction**

Run: `python3 -c "import json; d=json.load(open('.seed-work/names/CRI.json')); print(d['corrections'])"`
Expected: the list includes an entry with `"old_name": "Diego"` — this is the plan's named
acceptance criterion. If it's missing, dispatch a fix-up subagent for CRI specifically before
proceeding (this is the exact bug the user reported). Then spot-check 3 more random corrections
across other clubs with a quick WebSearch to confirm they're real.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 6: Merge Part B name corrections into the seed and go fully green

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (via `merge_name_corrections.py`)

**Interfaces:**
- Consumes: 28 validated correction files (Task 5), `merge_name_corrections.py` +
  `verify_name_only_changes.py` (Task 2).
- Produces: the final seed. `SeedLoaderTest` fully green, including
  `criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`.

- [ ] **Step 1: Merge**

Run: `python3 .seed-work/merge_name_corrections.py`
Expected: `applied N name correction(s) across M club(s): {...}` (N and M depend on the research
findings from Task 5 — any count >= 0 per club is valid, but CRI must show at least 1, covering
the "Diego" correction confirmed in Task 5 Step 9).

- [ ] **Step 2: Verify the diff changed only `name` fields**

Run: `python3 .seed-work/verify_name_only_changes.py`
Expected: `OK — every club's player list is unchanged except \`name\` fields`.

- [ ] **Step 3: Run the full seed and career test suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.*' --tests 'br.com.carvoeiromanager.career.*'`
Expected: BUILD SUCCESSFUL, ALL tests pass, including
`criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/seed/clubs.json
git commit -m "$(cat <<'EOF'
feat(seed): correct player names to their real known-as public names

Across all 28 pool clubs, player names are now corrected to how each
real player is actually publicly known (e.g. Criciúma's "Diego" ->
his real known-as name) rather than a placeholder or guessed shortening.
EOF
)"
```

---

### Task 7: Code comment, full suite, cleanup

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java:142-143`
- Delete: `.seed-work/` (tooling, not committed)

**Interfaces:**
- Consumes: final seed (Task 6).
- Produces: green full test suite; clean working tree apart from the intended changes.

- [ ] **Step 1: Fix the stale comment**

In `SaveGameService.java`, replace:

```java
                // Prefer hand-authored ("real") attributes when the seed carries them (all Série B
                // clubs); otherwise derive the 22 non-anchor attributes from the six anchors.
```

with:

```java
                // Prefer hand-authored ("real") attributes when the seed carries them (every club
                // in the pool, as of the Catarinense data-realism pass); otherwise derive the 22
                // non-anchor attributes from the six anchors. The derive path still runs for
                // free agents and newgens generated at runtime.
```

- [ ] **Step 2: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Clean up tooling**

Run: `rm -rf .seed-work`
Expected: `git status` shows a clean working tree apart from the pre-existing untracked docs from
earlier session work — `SaveGameService.java` is staged/committed by this task; `clubs.json`,
`SeedLoaderTest.java`, and `SaveGameServiceIT.java` were already committed in Tasks 4/6.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java
git commit -m "$(cat <<'EOF'
docs(seed): update the stale attribute-derivation comment

Every pool club is now hand-authored after the Catarinense data-realism
pass — the derive path is runtime-only (free agents, newgens) from here on.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** Part A roster authoring for all 8 Catarinense-only clubs with calibration
  guidance (Tasks 3-4), squad size 18-23 (validator + tests), Part B name correction across all 28
  clubs including the 8 Catarinense-only ones and the named Diego example (Tasks 5-6), sequencing
  Part B after Part A so the 8 clubs are checked against their new real names (Task 5 explicitly
  reads the post-Task-4 seed), code comment update (Task 7), full suite (Task 7). The design's
  "Required test changes" section maps one-for-one onto Task 1's rewritten `SeedLoaderTest`.
- **Known judgment call:** Task 4's commit intentionally leaves one test red
  (`criciumaPlayerNamesNoLongerIncludeTheBarePlaceholderDiego`) — flagged explicitly in that
  task's commit message and in Step 3's expected output, so nobody mid-execution mistakes it for
  a regression. It's closed by Task 6 in the same continuous run.
- **Verified, not assumed:** the `seedfmt.py` round-trip was independently run against the real
  seed file during plan-writing (not just copied from the archived Série B tooling) — this caught
  that the old formatter's `CLUB_KEYS` predates the `formation`/`mentality`/`pressing`/`marking`
  fields added by a later project and would have silently dropped them on every rewrite.
- **Type/name consistency:** shortName sets (`TARGET_CLUBS`, `ALL_CLUBS`, `SERIE_B_2026`) are
  identical across the tests, validators, and merge/verify scripts — checked by eye against the
  canonical lists in Global Constraints.
