# Real 2026 Série B Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the seed's part-fictional Série B with the real 20 clubs of the 2026 Brasileirão Série B, every Série B club carrying a real ~18–23-player roster where each player has a hand-authored full 28-attribute profile.

**Architecture:** Pure data change to `backend/src/main/resources/seed/clubs.json` plus test updates. Club rosters are researched by parallel web-research agents, written as per-club JSON fragments under `.seed-work/clubs/`, validated by a Python script, then merged into the seed by a formatter that preserves the file's exact style. No loader/entity/schema changes — `SeedAttributes` already supports everything.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), Python 3 for seed tooling, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-07-real-serie-b-2026-seed-design.md`

## Global Constraints

- **NEVER run `git commit` or `git push`.** Prepare commit messages only; the user commits. (Overrides the usual commit steps of the executing skills.)
- Work directly on `main` — no branches, no worktrees (user preference).
- `Position` enum values are exactly `GK, DEF, MID, ATT` (map laterais→DEF, volantes/meias→MID, pontas/centroavantes→ATT).
- All attribute and anchor values are ints in 1–99.
- Player JSON key order: `name, position, age, nationality, pace, finishing, passing, tackling, goalkeeping, stamina, attributes`. Attributes key order: `drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge, visao, posicionamento, decisao, compostura, concentracao, determinacao, agressividade, lideranca, aceleracao, forca, resistencia, equilibrio, agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes` (22 keys, exactly).
- Seed JSON style: 2-space indent; each player is ONE line: `{ "name": ..., ... "attributes": { ... } }` with a space after `{` and before `}`.
- Nationality in Portuguese (`"Brasil"`, `"Argentina"`, `"Venezuela"`, …); ages as of the 2026 season.
- Working dir for all commands: repo root `<repo-root>` unless stated.
- Série B 2026 shortNames (the canonical 20): `CRI, AVA, GOI, NOV, SPT, CEA, VIL, OPE, CRB, PPT, BOT, FOR, AME, ATH, ATG, CUI, JUV, LON, NAU, SBE`.
- Criciúma (CRI) rosters/attributes are already authored — **never modify CRI's players**.

---

### Task 1: Update tests to the new seed expectations (red)

**Files:**
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java:71-73`

**Interfaces:**
- Produces: the executable definition of "done" for the whole plan. Tests stay RED until Task 5 merges the final seed.

- [ ] **Step 1: Rewrite `SeedLoaderTest` with the new invariants**

Replace the entire content of `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java` with:

```java
package br.com.carvoeiromanager.seed;

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
        assertThat(data.clubPool()).allSatisfy(c ->
                assertThat(c.players()).hasSizeGreaterThanOrEqualTo(14));
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
        // No orphans: every pool club belongs to at least one competition,
        // and every membership entry resolves to a pool club.
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

        assertThat(data.competitions().get(0).seasonYear()).isEqualTo(2026);
    }

    @Test
    void everySerieBPlayerCarriesAuthoredAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> assertThat(club.players())
                        .allSatisfy(p -> assertThat(p.attributes())
                                .as("%s %s should be authored", club.shortName(), p.name())
                                .isNotNull()));
    }

    @Test
    void catarinenseOnlyClubsKeepDerivedAttributes() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> !SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> assertThat(club.players())
                        .allSatisfy(p -> assertThat(p.attributes())
                                .as("%s %s should be derived", club.shortName(), p.name())
                                .isNull()));
    }

    @Test
    void serieBSquadsHaveRealisticSizeAndGoalkeeperCover() {
        SeedCareer data = new SeedLoader().loadCareer();

        assertThat(data.clubPool()).filteredOn(c -> SERIE_B_2026.contains(c.shortName()))
                .allSatisfy(club -> {
                    // CRI keeps its pre-existing 27-player authored squad; others are 18-23.
                    int max = club.shortName().equals("CRI") ? 27 : 23;
                    assertThat(club.players()).hasSizeBetween(18, max);
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
}
```

- [ ] **Step 2: Update the club count in `SaveGameServiceIT`**

In `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`, replace:

```java
        // All clubs from the pool are created (25 clubs: the Catarinense + Série B pool, incl. Criciúma)
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).hasSize(25);
```

with:

```java
        // All clubs from the pool are created (28 clubs: 20 Série B + 8 Catarinense-only, CRI/AVA in both)
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).hasSize(28);
```

- [ ] **Step 3: Run the seed tests to verify they fail for the right reason**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest'`
Expected: FAIL — `serieBHasTheRealTwenty2026Members` (COR/PAY/GUA/MIR/REM present, AME…SBE missing), `clubPoolHasTwentyEightClubsAllInSomeCompetition` (25 ≠ 28), `everySerieBPlayerCarriesAuthoredAttributes` (non-CRI attrs null), `serieBSquadsHaveRealisticSizeAndGoalkeeperCover` (squads of 14). `authoredAttributesHoldTheResearchedValues` and `everyPlayerHasValidPosition` still PASS.

- [ ] **Step 4: Prepare (do not run) the commit message for the user**

Suggested message: `test(seed): expect the real 2026 Série B lineup with authored squads`

---

### Task 2: Seed tooling — formatter, structural apply, fragment validator, merge

**Files:**
- Create: `.seed-work/seedfmt.py`
- Create: `.seed-work/apply_structure.py`
- Create: `.seed-work/validate_fragment.py`
- Create: `.seed-work/merge_fragments.py`

**Interfaces:**
- Consumes: `backend/src/main/resources/seed/clubs.json` (current format).
- Produces: `seedfmt.dump_seed(data) -> str` (house-style JSON text); `validate_fragment.py <fragment.json>` exits 0/1 with report; `apply_structure.py` rewrites the seed's memberships and drops orphans; `merge_fragments.py` merges `.seed-work/clubs/*.json` into the seed. Task 4 agents call `validate_fragment.py`; Tasks 3 and 5 call the others.

- [ ] **Step 1: Write the house-style formatter**

Create `.seed-work/seedfmt.py`:

```python
#!/usr/bin/env python3
"""Load/dump clubs.json in its exact house style.

Style: 2-space indent; club identity fields one per line; each player a single
line `{ "k": v, ... }` with spaces inside braces; memberShortNames single-line
when <= 10 entries, else wrapped 6 per line. Key order is canonical.
"""
import json

SEED_PATH = "backend/src/main/resources/seed/clubs.json"

CLUB_KEYS = ["name", "shortName", "commonName", "nickname",
             "primaryColor", "secondaryColor", "players"]
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
        print("\n".join(diff[:40]))
        raise SystemExit("ROUND-TRIP FAILED — fix seedfmt before any rewrite")
```

- [ ] **Step 2: Run the round-trip check (this is the formatter's test)**

Run: `python3 .seed-work/seedfmt.py`
Expected: `ROUND-TRIP OK`. If it fails, the diff shows where `dump_seed` diverges from the file's real style — fix `seedfmt.py` (never the seed) until it passes.

- [ ] **Step 3: Write the structural-change script**

Create `.seed-work/apply_structure.py`:

```python
#!/usr/bin/env python3
"""Set the real 2026 Série B membership and drop clubs left in no competition."""
import sys
sys.path.insert(0, ".seed-work")
import seedfmt

SERIE_B_2026 = ["CRI", "AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
                "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE"]
REMOVE_FROM_POOL = {"COR", "PAY", "GUA", "MIR", "REM"}

data = seedfmt.load_seed()

serie_b = next(c for c in data["competitions"] if c["name"] == "Série B")
serie_b["memberShortNames"] = SERIE_B_2026

before = len(data["clubPool"])
data["clubPool"] = [c for c in data["clubPool"]
                    if c["shortName"] not in REMOVE_FROM_POOL]
removed = before - len(data["clubPool"])

seedfmt.save_seed(data)
print(f"Série B members: {len(serie_b['memberShortNames'])}; "
      f"removed {removed} orphan clubs; pool now {len(data['clubPool'])}")
```

- [ ] **Step 4: Write the fragment validator**

Create `.seed-work/validate_fragment.py`:

```python
#!/usr/bin/env python3
"""Validate one club roster fragment (.seed-work/clubs/<SHORT>.json)."""
import json
import re
import sys

ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]
ANCHORS = ["pace", "finishing", "passing", "tackling", "goalkeeping", "stamina"]
NEW_CLUBS = {"AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE"}
EXISTING_CLUBS = {"AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT", "BOT", "FOR"}
POSITIONS = {"GK", "DEF", "MID", "ATT"}
IDENTITY = ["name", "shortName", "commonName", "nickname", "primaryColor", "secondaryColor"]


def main(path):
    with open(path, encoding="utf-8") as f:
        club = json.load(f)
    errors = []
    sn = club.get("shortName")
    if sn not in NEW_CLUBS | EXISTING_CLUBS:
        errors.append(f"unexpected shortName {sn!r}")
    if sn in NEW_CLUBS:
        for field in IDENTITY:
            v = club.get(field)
            if not isinstance(v, str) or not v:
                errors.append(f"new club missing identity field {field!r}")
        for field in ("primaryColor", "secondaryColor"):
            v = club.get(field)
            if isinstance(v, str) and not re.fullmatch(r"#[0-9a-fA-F]{6}", v):
                errors.append(f"{field} {v!r} is not #rrggbb")

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
        # Sanity: keep the CRI calibration pattern.
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

- [ ] **Step 5: Write the merge script**

Create `.seed-work/merge_fragments.py`:

```python
#!/usr/bin/env python3
"""Merge validated club fragments from .seed-work/clubs/ into the seed.

Existing clubs: only their players are replaced (curated identity is kept).
New clubs: appended to the pool in fixed order with identity from the fragment.
"""
import glob
import json
import os
import sys

sys.path.insert(0, ".seed-work")
import seedfmt

NEW_CLUB_ORDER = ["AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE"]
EXPECTED = {"AVA", "GOI", "NOV", "SPT", "CEA", "VIL", "OPE", "CRB", "PPT",
            "BOT", "FOR", "AME", "ATH", "ATG", "CUI", "JUV", "LON", "NAU", "SBE"}
IDENTITY = ["name", "shortName", "commonName", "nickname", "primaryColor", "secondaryColor"]

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
    if sn in pool_by_sn:
        pool_by_sn[sn]["players"] = frag["players"]
    # new clubs are appended below in fixed order

for sn in NEW_CLUB_ORDER:
    if sn not in pool_by_sn:
        frag = fragments[sn]
        club = {k: frag[k] for k in IDENTITY}
        club["players"] = frag["players"]
        data["clubPool"].append(club)

seedfmt.save_seed(data)
sizes = {c["shortName"]: len(c["players"]) for c in data["clubPool"]}
print(f"pool {len(data['clubPool'])} clubs; squad sizes: {sizes}")
```

- [ ] **Step 6: Smoke-test validator and merge preconditions**

Run:
```bash
mkdir -p .seed-work/clubs
python3 - <<'EOF'
import json, sys
sys.path.insert(0, ".seed-work")
import seedfmt
data = seedfmt.load_seed()
cri = next(c for c in data["clubPool"] if c["shortName"] == "CRI")
# CRI is a valid authored club except shortName — rename to AVA to exercise the validator.
cri = dict(cri); cri["shortName"] = "AVA"
json.dump(cri, open("/tmp/smoke-ava.json", "w"), ensure_ascii=False)
EOF
python3 .seed-work/validate_fragment.py /tmp/smoke-ava.json
```
Expected: `INVALID /tmp/smoke-ava.json` with exactly one error — `squad size 27 not in 18..23` (CRI has 27 players; everything else about the authored squad is valid). This proves the validator parses real data and enforces the size rule. Then `rm /tmp/smoke-ava.json`.

Note: `.seed-work/` is temporary tooling, deleted in Task 6 — it is never committed.

---

### Task 3: Apply the structural seed changes

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (memberships + orphan removal, via script)

**Interfaces:**
- Consumes: `.seed-work/apply_structure.py`, `.seed-work/seedfmt.py` (Task 2).
- Produces: seed with Série B = the canonical 20 shortNames and pool of 20 clubs (25 − 5 orphans; the 8 new clubs arrive in Task 5).

- [ ] **Step 1: Run the structural script**

Run: `python3 .seed-work/apply_structure.py`
Expected: `Série B members: 20; removed 5 orphan clubs; pool now 20`

- [ ] **Step 2: Verify the diff is surgical**

Run: `git diff --stat backend/src/main/resources/seed/clubs.json && git diff backend/src/main/resources/seed/clubs.json | head -60`
Expected: only removed club blocks (COR, PAY, GUA, MIR, REM) and the Série B `memberShortNames` change; CRI and all other club lines untouched (round-trip fidelity from Task 2 guarantees this).

- [ ] **Step 3: Confirm test status moved for the right reason**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest'`
Expected: `serieBHasTheRealTwenty2026Members` now PASSES (membership list is right even though 8 clubs lack pool entries — membership is data, not a join). `clubPoolHasTwentyEightClubsAllInSomeCompetition` still FAILS (20 ≠ 28, and 8 members don't resolve to pool clubs). `everySerieBPlayerCarriesAuthoredAttributes` still FAILS.

- [ ] **Step 4: Prepare (do not run) the commit message for the user**

Suggested: `feat(seed): real 2026 Série B membership; drop clubs with no competition` — but recommend the user commits Tasks 3–5 together since the seed is inconsistent until the merge (tests red).

---

### Task 4: Research and author the 19 club roster fragments (parallelizable)

**Files:**
- Create: `.seed-work/clubs/<SHORT>.json` — one per club, 19 files.

**Interfaces:**
- Consumes: `.seed-work/validate_fragment.py` (Task 2).
- Produces: validated fragments consumed by `merge_fragments.py` (Task 5). Fragment shape: full club object `{name, shortName, commonName, nickname, primaryColor, secondaryColor, players: [...]}` for NEW clubs; for EXISTING clubs only `{shortName, players}` is required (identity is kept from the seed).

**This task runs once per club in the table below. All 19 runs are independent — dispatch them as parallel research subagents (batches of 4–6). Each subagent gets the full prompt template with its club's row substituted in.**

**Club table:**

| # | shortName | Club | Status | Identity hint (VERIFY via research) |
|---|-----------|------|--------|--------------------------------------|
| 1 | AVA | Avaí FC | existing | keep seed identity |
| 2 | GOI | Goiás EC | existing | keep seed identity |
| 3 | NOV | Grêmio Novorizontino | existing | keep seed identity |
| 4 | SPT | Sport Club do Recife | existing | keep seed identity |
| 5 | CEA | Ceará SC | existing | keep seed identity |
| 6 | VIL | Vila Nova FC | existing | keep seed identity |
| 7 | OPE | Operário Ferroviário EC | existing | keep seed identity |
| 8 | CRB | Clube de Regatas Brasil | existing | keep seed identity |
| 9 | PPT | AA Ponte Preta | existing | keep seed identity |
| 10 | BOT | Botafogo FC (Ribeirão Preto) | existing | keep seed identity |
| 11 | FOR | Fortaleza EC | existing | keep seed identity |
| 12 | AME | América Futebol Clube (MG) | NEW | commonName "América-MG", nickname "Coelho", green/white |
| 13 | ATH | Athletic Club (São João del-Rei) | NEW | commonName "Athletic", nickname "Esquadrão de Aço", red/white |
| 14 | ATG | Atlético Clube Goianiense | NEW | commonName "Atlético-GO", nickname "Dragão", red/black |
| 15 | CUI | Cuiabá Esporte Clube | NEW | commonName "Cuiabá", nickname "Dourado", gold/green |
| 16 | JUV | Esporte Clube Juventude | NEW | commonName "Juventude", nickname "Papo", green/white |
| 17 | LON | Londrina Esporte Clube | NEW | commonName "Londrina", nickname "Tubarão", blue/white |
| 18 | NAU | Clube Náutico Capibaribe | NEW | commonName "Náutico", nickname "Timbu", red/white |
| 19 | SBE | São Bernardo Futebol Clube | NEW | commonName "São Bernardo", verify nickname (Tigre?) and colors (yellow/black?) |

**Subagent prompt template (substitute `{SHORT}`, `{CLUB}`, `{STATUS}`, `{IDENTITY_HINT}`):**

````text
You are authoring the real 2026 roster for {CLUB} ({SHORT}) in a football-manager
game seed. Work from repo root <repo-root>.

1. RESEARCH (WebSearch): find the club's current squad for the 2026 Brasileirão
   Série B season (best sources: Transfermarkt "{CLUB} plantel 2026", Wikipedia
   pt "Elenco atual", ogol.com.br, ge.globo.com). Prefer July-2026 data; early-2026
   rosters are acceptable if that's the freshest available. Pick 20-23 real players:
   2-3 goalkeepers, >=5 defenders (zagueiros + laterais), >=5 midfielders
   (volantes + meias), >=3 forwards (pontas + centroavantes). Prefer regular
   starters plus notable reserves. Record each player's commonly-used short name
   (e.g. "Otero", "Marcelo Hermes"), age in 2026, and nationality in Portuguese
   ("Brasil", "Argentina", ...).
   {STATUS} == NEW only: also verify full official name, commonName, nickname and
   the two main kit colors as hex. Hint (verify, don't trust): {IDENTITY_HINT}.

2. AUTHOR the JSON fragment at .seed-work/clubs/{SHORT}.json:
   - NEW club: { "name", "shortName": "{SHORT}", "commonName", "nickname",
     "primaryColor": "#rrggbb", "secondaryColor": "#rrggbb", "players": [...] }
   - EXISTING club: { "shortName": "{SHORT}", "players": [...] }
   Each player object, keys in THIS order:
   name, position (GK|DEF|MID|ATT), age, nationality,
   pace, finishing, passing, tackling, goalkeeping, stamina,
   attributes: { drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge,
     visao, posicionamento, decisao, compostura, concentracao, determinacao,
     agressividade, lideranca, aceleracao, forca, resistencia, equilibrio,
     agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes }
   All values ints 1-99. Map positions: laterais -> DEF, volantes/meias -> MID,
   pontas/centroavantes -> ATT.

3. CALIBRATE attributes to each player's real profile and reputation, on the
   scale used by the existing Criciúma squad (the managed club, slightly strong
   for the division). Reference points from the seed:
   - GK starter (Airton, 35): pace 44, finishing 16, passing 58, tackling 30,
     goalkeeping 80, stamina 70; attributes: drible 28, cruzamento 18, cabeceio 22,
     marcacao 26, bolaParada 20, chuteLonge 30, visao 50, posicionamento 24,
     decisao 62, compostura 66, concentracao 72, determinacao 68, agressividade 40,
     lideranca 68, aceleracao 44, forca 72, resistencia 70, equilibrio 64,
     agilidade 66, posicionamentoGk 80, saidaDeGol 74, jogoComOsPes 62
   - Star MID (Otero, 34, set-piece playmaker): pace 62, finishing 64, passing 80,
     tackling 50, goalkeeping 8, stamina 65; bolaParada 88, chuteLonge 82, visao 80,
     drible 78, cruzamento 72 — peaks near 80-88 are for genuinely documented
     specialties only.
   - Solid DEF starter (Marcelo Hermes, 31, left-back): pace 70, passing 66,
     tackling 72; cruzamento 74, marcacao 70, resistencia 76 — typical starter
     band is 60-75 in strong suits.
   Bands: regular starters 60-75 in their strengths; rotation players 48-62;
   young prospects 35-55 with higher determinacao. GKs: high goalkeeping +
   posicionamentoGk/saidaDeGol, low outfield technicals (finishing <= 40).
   Outfielders: goalkeeping <= 40 (usually 5-15). Older players (32+): lower
   pace/aceleracao/resistencia, higher decisao/compostura/lideranca.

4. VALIDATE: run `python3 .seed-work/validate_fragment.py .seed-work/clubs/{SHORT}.json`
   and fix issues until it prints OK.

5. RETURN: one line per outcome — squad size, position counts, data source used
   and its date, plus any players you were unsure about.
````

- [ ] **Step 1: Author fragments for existing clubs batch 1 — AVA, GOI, NOV, SPT (4 parallel subagents)**
- [ ] **Step 2: Author fragments for existing clubs batch 2 — CEA, VIL, OPE, CRB (4 parallel subagents)**
- [ ] **Step 3: Author fragments for existing clubs batch 3 — PPT, BOT, FOR (3 parallel subagents)**
- [ ] **Step 4: Author fragments for new clubs batch 1 — AME, ATH, ATG, CUI (4 parallel subagents)**
- [ ] **Step 5: Author fragments for new clubs batch 2 — JUV, LON, NAU, SBE (4 parallel subagents)**
- [ ] **Step 6: Re-validate all fragments in one sweep**

Run: `for f in .seed-work/clubs/*.json; do python3 .seed-work/validate_fragment.py "$f" || exit 1; done && ls .seed-work/clubs/ | wc -l`
Expected: 19 `OK` lines and count `19`.

- [ ] **Step 7: Spot-check research quality**

For 3 random clubs, verify 3 named players each actually play there in 2026 (quick WebSearch). Any wrong player → send the fragment back to a fix-up subagent with the correction.

---

### Task 5: Merge fragments into the seed and go green

**Files:**
- Modify: `backend/src/main/resources/seed/clubs.json` (via `merge_fragments.py`)

**Interfaces:**
- Consumes: 19 validated fragments (Task 4), `merge_fragments.py` + `seedfmt.py` (Task 2).
- Produces: the final seed — 28 clubs, all Série B squads authored. SeedLoaderTest green.

- [ ] **Step 1: Merge**

Run: `python3 .seed-work/merge_fragments.py`
Expected: `pool 28 clubs; squad sizes: {...}` with CRI=27, Catarinense-only clubs at their old sizes (14–15), and all other Série B clubs 18–23.

- [ ] **Step 2: Verify the diff protects curated data**

Run: `git diff backend/src/main/resources/seed/clubs.json | grep -c '^[-+]' && git diff backend/src/main/resources/seed/clubs.json | grep '^[-+]' | grep -E '"(CRI|BAR|CON|HER|CAM|FIG|CHA|BRU|MAR)"' | head`
Expected: large +/- count (roster replacement) but NO changed lines carrying the shortNames of CRI or the Catarinense-only clubs (second command prints nothing).

- [ ] **Step 3: Run the seed tests**

Run: `cd backend && ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest'`
Expected: ALL PASS (including `clubPoolHasTwentyEightClubsAllInSomeCompetition`, `everySerieBPlayerCarriesAuthoredAttributes`, `catarinenseOnlyClubsKeepDerivedAttributes`, `serieBSquadsHaveRealisticSizeAndGoalkeeperCover`).

- [ ] **Step 4: Prepare (do not run) the commit message for the user**

Suggested: `feat(seed): real 2026 Série B — 20 real clubs with authored 28-attribute rosters`

---

### Task 6: Code comment, full suite, cleanup

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java:124-125`
- Delete: `.seed-work/` (tooling, not committed)

**Interfaces:**
- Consumes: final seed (Task 5).
- Produces: green full test suite; clean working tree apart from the intended changes.

- [ ] **Step 1: Fix the stale comment**

In `SaveGameService.java`, replace:

```java
                // Prefer hand-authored ("real") attributes when the seed carries them (currently
                // Criciúma only); otherwise derive the 22 non-anchor attributes from the six anchors.
```

with:

```java
                // Prefer hand-authored ("real") attributes when the seed carries them (all Série B
                // clubs); otherwise derive the 22 non-anchor attributes from the six anchors.
```

- [ ] **Step 2: Run the full backend suite**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including `SaveGameServiceIT` with the 28-club assertion from Task 1).

- [ ] **Step 3: Clean up tooling**

Run: `rm -rf .seed-work`
Expected: `git status` shows only: modified `clubs.json`, `SaveGameService.java`, `SeedLoaderTest.java`, `SaveGameServiceIT.java`, new spec + plan docs (plus the pre-existing uncommitted SeedAttributes work).

- [ ] **Step 4: Prepare the final commit message for the user (do not run git commit)**

Suggested single commit for the whole change set:

```
feat(seed): real 2026 Série B with authored rosters for all 20 clubs

- Série B membership updated to the real 2026 lineup (adds América-MG,
  Athletic, Atlético-GO, Cuiabá, Juventude, Londrina, Náutico, São Bernardo;
  Fortaleza joins; Figueirense/Chapecoense/Brusque/Marcílio Dias stay
  Catarinense-only)
- Coritiba, Paysandu, Guarani, Mirassol and Remo removed from the pool
  (no competition -> dead weight in every save)
- Every Série B club now carries a real ~20-player 2026 roster with
  hand-authored 28-attribute profiles (SeedAttributes), calibrated to the
  Criciúma reference scale
```

---

## Self-Review Notes

- **Spec coverage:** membership (Task 3), orphan removal (Task 3), 8 new clubs + real rosters + authored attributes (Tasks 4–5), squad size 18–23 (validator + tests), code comment (Task 6), test updates (Task 1), full suite (Task 6). Validation script requirements from the spec map to `validate_fragment.py` checks one-for-one, with membership integrity covered by `clubPoolHasTwentyEightClubsAllInSomeCompetition`.
- **Known judgment call:** `apply_structure.py` (Task 3) runs before fragments exist, so the seed is intentionally inconsistent (red tests) between Tasks 3 and 5 — flagged in Task 3/Step 4 so the user commits 3–5 together.
- **Type consistency:** shortName sets are identical across tests, validator, apply, and merge scripts (verified by eye against the canonical list in Global Constraints).
