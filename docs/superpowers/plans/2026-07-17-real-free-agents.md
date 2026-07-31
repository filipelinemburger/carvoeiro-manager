# Real Free Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fully-procedural free-agent pool (`FreeAgentGenerator`: 10 fictional players at career creation, floor-backfill to 6 at rollover) with a real, hand-authored South American player pool of ~180 players — no procedural generation left anywhere in the free-agent system.

**Architecture:** A new flat seed file (`seed/free-agents.json`, same `SeedPlayer` shape already used inside each club's roster) loaded once at career creation via a new `SeedLoader.loadFreeAgents()` method. `SaveGameService.createCareer` seeds the pool 1:1 from that list instead of calling a generator in a loop; `SeasonRolloverService` no longer tops the pool back up — it only shrinks (signings) or grows the way it already does today (a managed player's contract lapsing). `FreeAgentGenerator` becomes dead code once both call sites are converted and is deleted. The ~180 real players are authored the same way the 28 club rosters were: parallel web-research batches (one per country) writing validated JSON fragments, merged into the final seed file.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), Python 3 for seed tooling, JUnit 5 + AssertJ, Spring Data JPA, Testcontainers Postgres for `*IT` tests.

**Spec:** `docs/superpowers/specs/2026-07-17-real-free-agents-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task that touches tracked files ends by committing its own tested work. `git push` is never run without an explicit user ask.
- `Position` enum values are exactly `GK, DEF, MID, ATT`.
- All attribute and anchor values are ints in 1–99.
- `SeedPlayer` key order (matches `clubs.json`'s existing player style exactly): `name, position, age, nationality, pace, finishing, passing, tackling, goalkeeping, stamina, attributes`. Attributes key order (22 keys, exactly): `drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge, visao, posicionamento, decisao, compostura, concentracao, determinacao, agressividade, lideranca, aceleracao, forca, resistencia, equilibrio, agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes`.
- Nationality is one of exactly 8 Portuguese labels, one per target country: `Brasil, Argentina, Uruguai, Colômbia, Paraguai, Chile, Venezuela, Equador`.
- Free-agent target composition: ~40 Brasil (range 30–50), ~20 each of the other 7 countries (range 15–25). These are research targets, not hard requirements — never blocked on hitting an exact number.
- GK entries: `finishing <= 40`. Outfielder entries: `goalkeeping <= 40`. Outfielders' `posicionamentoGk`/`saidaDeGol`/`jogoComOsPes` stay low (single digits to low teens) — these are goalkeeper-specific attributes, not general technical skill (see the existing `Bruno Alves` DEF entry in `clubs.json`: `posicionamentoGk: 6, saidaDeGol: 5, jogoComOsPes: 8`).
- No free-agent name may collide with any player name already in `clubs.json`'s club rosters, and no two free agents may share a name.
- No procedural generation anywhere in the free-agent system after this plan — `FreeAgentGenerator` is deleted once unused.
- Working dir for all commands: repo root `/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager` unless stated. Backend test commands: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` — use a long timeout (600000ms); `*IT` tests spin up Testcontainers Postgres and take longer than plain unit tests.
- `.seed-work/` is temporary tooling — never `git add`ed, deleted in the final task.

---

### Task 1: Seed tooling — formatter, fragment validator, merge script, collision checker

**Files:**
- Create: `.seed-work/fafmt.py`
- Create: `.seed-work/validate_fa_fragment.py`
- Create: `.seed-work/merge_fa_fragments.py`
- Create: `.seed-work/check_no_collisions.py`

**Interfaces:**
- Consumes: `backend/src/main/resources/seed/clubs.json` (read-only, for the collision check).
- Produces: `fafmt.load_seed()/save_seed()/dump_seed()` used by every other script in this task and by Tasks 2 and 6; `validate_fa_fragment.py <fragment.json>` exits 0/1 with a report, used by Tasks 2 and 5; `merge_fa_fragments.py` used by Tasks 2 and 6; `check_no_collisions.py` used by Tasks 2 and 6.

**No commit for this task** — `.seed-work/` is temporary tooling, never `git add`ed (see Global Constraints). Nothing in this task touches a tracked file.

- [ ] **Step 1: Write the free-agent seed formatter**

Create `.seed-work/fafmt.py`:

```python
#!/usr/bin/env python3
"""Load/dump free-agents.json in its exact house style: a flat JSON array of
SeedPlayer objects, one line each, 2-space indent — mirrors clubs.json's
per-player line style so the two seed files read the same way.
"""
import json

SEED_PATH = "backend/src/main/resources/seed/free-agents.json"

PLAYER_KEYS = ["name", "position", "age", "nationality", "pace", "finishing",
               "passing", "tackling", "goalkeeping", "stamina", "attributes"]
ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]


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


def dump_seed(players):
    lines = ",\n".join("  " + fmt_inline_obj(p, PLAYER_KEYS) for p in players)
    return "[\n" + lines + "\n]\n"


def load_seed(path=SEED_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_seed(players, path=SEED_PATH):
    with open(path, "w", encoding="utf-8") as f:
        f.write(dump_seed(players))


if __name__ == "__main__":
    import os
    if os.path.exists(SEED_PATH):
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
            raise SystemExit("ROUND-TRIP FAILED — fix fafmt before any rewrite")
    else:
        print("no existing seed file yet — nothing to round-trip check")
```

- [ ] **Step 2: Run the formatter (no seed file exists yet, so this just proves the module imports cleanly)**

Run: `python3 .seed-work/fafmt.py`
Expected: `no existing seed file yet — nothing to round-trip check`

- [ ] **Step 3: Write the fragment validator**

Create `.seed-work/validate_fa_fragment.py`:

```python
#!/usr/bin/env python3
"""Validate one free-agent country fragment (.seed-work/free-agents/<CODE>.json)."""
import json
import os
import sys

ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]
ANCHORS = ["pace", "finishing", "passing", "tackling", "goalkeeping", "stamina"]
POSITIONS = {"GK", "DEF", "MID", "ATT"}
VALID_COUNTRIES = {"Brasil", "Argentina", "Uruguai", "Colômbia", "Paraguai",
                    "Chile", "Venezuela", "Equador"}
CLUBS_PATH = "backend/src/main/resources/seed/clubs.json"

with open(CLUBS_PATH, encoding="utf-8") as f:
    _clubs = json.load(f)
CLUB_NAMES = {p["name"] for c in _clubs["clubPool"] for p in c["players"]}


def main(path):
    with open(path, encoding="utf-8") as f:
        frag = json.load(f)
    errors = []
    country = frag.get("country")
    if country not in VALID_COUNTRIES:
        errors.append(f"unexpected country {country!r}")
    extra_keys = set(frag.keys()) - {"country", "players"}
    if extra_keys:
        errors.append(f"fragment should only carry country+players, found extra keys {sorted(extra_keys)}")

    players = frag.get("players", [])
    if not players:
        errors.append("fragment has zero players")

    names = set()
    counts = {"GK": 0, "DEF": 0, "MID": 0, "ATT": 0}
    for p in players:
        n = p.get("name", "?")
        if n in names:
            errors.append(f"duplicate player name {n!r} within fragment")
        names.add(n)
        if n in CLUB_NAMES:
            errors.append(f"{n}: collides with an existing club-roster player name")
        if p.get("nationality") != country:
            errors.append(f"{n}: nationality {p.get('nationality')!r} != fragment country {country!r}")
        pos = p.get("position")
        if pos not in POSITIONS:
            errors.append(f"{n}: invalid position {pos!r}")
            continue
        counts[pos] += 1
        if not isinstance(p.get("age"), int) or not 18 <= p["age"] <= 40:
            errors.append(f"{n}: bad age {p.get('age')!r}")
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

    # Position variety is only enforced once a fragment is reasonably sized — a
    # small bootstrap fragment (Task 2) is allowed to be lopsided.
    if len(players) >= 10:
        if counts["GK"] < 1:
            errors.append("no goalkeeper in a fragment of 10+ players")
        if counts["DEF"] < 2:
            errors.append(f"only {counts['DEF']} DEF in a fragment of 10+ players")
        if counts["MID"] < 2:
            errors.append(f"only {counts['MID']} MID in a fragment of 10+ players")

    if errors:
        print(f"INVALID {path}")
        for e in errors:
            print(f" - {e}")
        sys.exit(1)
    print(f"OK {path}: {len(players)} players {counts}")


if __name__ == "__main__":
    main(sys.argv[1])
```

- [ ] **Step 4: Write the merge script**

Create `.seed-work/merge_fa_fragments.py`:

```python
#!/usr/bin/env python3
"""Merge validated free-agent fragments from .seed-work/free-agents/ into
backend/src/main/resources/seed/free-agents.json. Fully replaces the file's
contents with the union of every fragment present at merge time."""
import glob
import json
import sys

sys.path.insert(0, ".seed-work")
import fafmt

fragments = []
per_country = {}
for path in sorted(glob.glob(".seed-work/free-agents/*.json")):
    with open(path, encoding="utf-8") as f:
        frag = json.load(f)
    per_country[frag["country"]] = len(frag["players"])
    fragments.extend(frag["players"])

if not fragments:
    sys.exit("no fragments found in .seed-work/free-agents/")

fafmt.save_seed(fragments)
print(f"merged {len(fragments)} free agents across {len(per_country)} countries: {per_country}")
```

- [ ] **Step 5: Write the collision checker**

Create `.seed-work/check_no_collisions.py`:

```python
#!/usr/bin/env python3
"""Verify no free-agent name collides with any club-roster player name, and
no free-agent name is duplicated within the free-agent pool itself."""
import json
import sys

sys.path.insert(0, ".seed-work")
import fafmt

CLUBS_PATH = "backend/src/main/resources/seed/clubs.json"

with open(CLUBS_PATH, encoding="utf-8") as f:
    clubs = json.load(f)
club_names = {p["name"] for c in clubs["clubPool"] for p in c["players"]}

free_agents = fafmt.load_seed()
fa_names = [p["name"] for p in free_agents]

errors = []
collisions = club_names & set(fa_names)
if collisions:
    errors.append(f"free agent name(s) collide with a club roster player: {sorted(collisions)}")

seen = set()
dupes = set()
for n in fa_names:
    if n in seen:
        dupes.add(n)
    seen.add(n)
if dupes:
    errors.append(f"duplicate free-agent name(s) across the pool: {sorted(dupes)}")

if errors:
    print("FAILED:")
    for e in errors:
        print(f" - {e}")
    sys.exit(1)
print(f"OK — {len(free_agents)} free agents, no collisions with {len(club_names)} "
      f"club-roster names, no internal duplicates")
```

- [ ] **Step 6: Smoke-test the validator against a hand-built fragment**

Run:
```bash
mkdir -p .seed-work/free-agents
python3 - <<'EOF'
import json
frag = {"country": "Brasil", "players": [
    {"name": "Smoke Test Player", "position": "GK", "age": 99, "nationality": "Brasil",
     "pace": 1, "finishing": 1, "passing": 1, "tackling": 1, "goalkeeping": 1, "stamina": 1,
     "attributes": {}}
]}
json.dump(frag, open("/tmp/smoke-fa.json", "w"), ensure_ascii=False)
EOF
python3 .seed-work/validate_fa_fragment.py /tmp/smoke-fa.json
```
Expected: `INVALID /tmp/smoke-fa.json` reporting `bad age 99` and `attributes missing [...]` (all 22 keys) — proves the validator parses real structure and enforces both rules. Then `rm /tmp/smoke-fa.json`.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 2: `SeedLoader.loadFreeAgents()` and a small real bootstrap seed

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`
- Create: `backend/src/main/resources/seed/free-agents.json` (via the Task 1 tooling)

**Interfaces:**
- Consumes: `.seed-work/validate_fa_fragment.py`, `.seed-work/merge_fa_fragments.py` (Task 1).
- Produces: `SeedLoader.loadFreeAgents(): List<SeedPlayer>`, consumed by Tasks 3 and by the new tests below. `backend/src/main/resources/seed/free-agents.json` first exists after this task (a small real bootstrap — 6 players across 3 countries — fully superseded by Task 6's full merge, but genuinely real data, not placeholders).

- [ ] **Step 1: Add the failing loader tests**

In `backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java`, add these two tests at the end of the class, immediately before the closing `}`:

```java
    @Test
    void loadsFreeAgentsFromTheSeedFile() {
        var freeAgents = new SeedLoader().loadFreeAgents();

        assertThat(freeAgents).isNotEmpty();
        assertThat(freeAgents).allSatisfy(p -> {
            assertThat(VALID_POSITIONS).contains(p.position());
            assertThat(p.attributes()).as("%s should be authored", p.name()).isNotNull();
            assertThat(p.nationality()).isNotBlank();
        });
    }

    @Test
    void freeAgentNamesDoNotCollideWithAnyClubRosterPlayer() {
        SeedLoader loader = new SeedLoader();
        Set<String> clubNames = loader.loadCareer().clubPool().stream()
                .flatMap(c -> c.players().stream())
                .map(SeedPlayer::name)
                .collect(Collectors.toSet());
        Set<String> freeAgentNames = loader.loadFreeAgents().stream()
                .map(SeedPlayer::name).collect(Collectors.toSet());

        assertThat(clubNames).as("free agent names must not collide with any club roster player")
                .doesNotContainAnyElementsOf(freeAgentNames);
    }
```

- [ ] **Step 2: Run the seed tests to verify they fail for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest'`

Expected: compile/test FAILURE — `loadFreeAgents()` does not exist on `SeedLoader` yet.

- [ ] **Step 3: Implement `SeedLoader.loadFreeAgents()`**

Replace the full contents of `backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java` with:

```java
package br.com.carvoeiromanager.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class SeedLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public SeedCareer loadCareer() {
        try (InputStream in = new ClassPathResource("seed/clubs.json").getInputStream()) {
            return mapper.readValue(in, SeedCareer.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load career seed", e);
        }
    }

    public NewgenNames loadNewgenNames() {
        try (InputStream in = new ClassPathResource("seed/newgen-names.json").getInputStream()) {
            return mapper.readValue(in, NewgenNames.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load newgen names seed", e);
        }
    }

    /** The real, hand-authored South American free-agent pool. Career creation seeds the
     *  free-agent pool 1:1 from this list — there is no procedural generation. */
    public List<SeedPlayer> loadFreeAgents() {
        try (InputStream in = new ClassPathResource("seed/free-agents.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedPlayer>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load free-agent seed", e);
        }
    }
}
```

- [ ] **Step 4: Author the bootstrap fragments**

Create `.seed-work/free-agents/BRA.json`:

```json
{
  "country": "Brasil",
  "players": [
    { "name": "Jean", "position": "GK", "age": 30, "nationality": "Brasil", "pace": 40, "finishing": 8, "passing": 45, "tackling": 20, "goalkeeping": 74, "stamina": 60, "attributes": { "drible": 15, "cruzamento": 10, "cabeceio": 20, "marcacao": 25, "bolaParada": 10, "chuteLonge": 12, "visao": 55, "posicionamento": 68, "decisao": 62, "compostura": 65, "concentracao": 68, "determinacao": 60, "agressividade": 40, "lideranca": 55, "aceleracao": 42, "forca": 60, "resistencia": 58, "equilibrio": 55, "agilidade": 62, "posicionamentoGk": 70, "saidaDeGol": 68, "jogoComOsPes": 58 } },
    { "name": "Bruno Uvini", "position": "DEF", "age": 34, "nationality": "Brasil", "pace": 45, "finishing": 15, "passing": 50, "tackling": 68, "goalkeeping": 5, "stamina": 62, "attributes": { "drible": 30, "cruzamento": 25, "cabeceio": 70, "marcacao": 70, "bolaParada": 20, "chuteLonge": 25, "visao": 50, "posicionamento": 68, "decisao": 62, "compostura": 66, "concentracao": 65, "determinacao": 62, "agressividade": 60, "lideranca": 58, "aceleracao": 42, "forca": 68, "resistencia": 58, "equilibrio": 60, "agilidade": 48, "posicionamentoGk": 6, "saidaDeGol": 5, "jogoComOsPes": 10 } },
    { "name": "Luiz Gustavo", "position": "MID", "age": 38, "nationality": "Brasil", "pace": 40, "finishing": 30, "passing": 68, "tackling": 65, "goalkeeping": 5, "stamina": 58, "attributes": { "drible": 45, "cruzamento": 35, "cabeceio": 45, "marcacao": 66, "bolaParada": 30, "chuteLonge": 40, "visao": 68, "posicionamento": 70, "decisao": 74, "compostura": 72, "concentracao": 70, "determinacao": 68, "agressividade": 55, "lideranca": 75, "aceleracao": 36, "forca": 62, "resistencia": 55, "equilibrio": 60, "agilidade": 48, "posicionamentoGk": 5, "saidaDeGol": 4, "jogoComOsPes": 12 } },
    { "name": "Romarinho", "position": "ATT", "age": 35, "nationality": "Brasil", "pace": 60, "finishing": 62, "passing": 48, "tackling": 20, "goalkeeping": 5, "stamina": 55, "attributes": { "drible": 62, "cruzamento": 55, "cabeceio": 40, "marcacao": 20, "bolaParada": 45, "chuteLonge": 50, "visao": 55, "posicionamento": 58, "decisao": 58, "compostura": 56, "concentracao": 55, "determinacao": 58, "agressividade": 45, "lideranca": 42, "aceleracao": 58, "forca": 48, "resistencia": 50, "equilibrio": 55, "agilidade": 60, "posicionamentoGk": 4, "saidaDeGol": 3, "jogoComOsPes": 8 } }
  ]
}
```

Create `.seed-work/free-agents/ARG.json`:

```json
{
  "country": "Argentina",
  "players": [
    { "name": "Darío Benedetto", "position": "ATT", "age": 35, "nationality": "Argentina", "pace": 48, "finishing": 68, "passing": 45, "tackling": 18, "goalkeeping": 5, "stamina": 52, "attributes": { "drible": 42, "cruzamento": 30, "cabeceio": 65, "marcacao": 18, "bolaParada": 40, "chuteLonge": 48, "visao": 52, "posicionamento": 66, "decisao": 60, "compostura": 62, "concentracao": 58, "determinacao": 65, "agressividade": 62, "lideranca": 55, "aceleracao": 42, "forca": 68, "resistencia": 48, "equilibrio": 55, "agilidade": 48, "posicionamentoGk": 4, "saidaDeGol": 3, "jogoComOsPes": 6 } }
  ]
}
```

Create `.seed-work/free-agents/URU.json`:

```json
{
  "country": "Uruguai",
  "players": [
    { "name": "Sebastián Sosa Sánchez", "position": "ATT", "age": 32, "nationality": "Uruguai", "pace": 62, "finishing": 60, "passing": 44, "tackling": 22, "goalkeeping": 5, "stamina": 58, "attributes": { "drible": 55, "cruzamento": 45, "cabeceio": 42, "marcacao": 22, "bolaParada": 30, "chuteLonge": 42, "visao": 50, "posicionamento": 58, "decisao": 55, "compostura": 54, "concentracao": 52, "determinacao": 58, "agressividade": 50, "lideranca": 40, "aceleracao": 60, "forca": 55, "resistencia": 52, "equilibrio": 56, "agilidade": 58, "posicionamentoGk": 5, "saidaDeGol": 4, "jogoComOsPes": 9 } }
  ]
}
```

These six are real, currently unattached players as of mid-2026 (Jean — released by Cerro Porteño in May 2026; Bruno Uvini — released by Vitória in June 2026; Luiz Gustavo — contract with São Paulo not renewed; Romarinho — rescinded with Vitória in October; Darío Benedetto — left Newell's Old Boys; Sebastián Sosa Sánchez — contract terminated by Everton de Viña del Mar in July 2026), calibrated as faded/experienced free-agent profiles (lower pace/physical for the older players, higher decisão/compostura/liderança) rather than peak-level ratings. This bootstrap set is intentionally small — Task 6 replaces it wholesale with the full ~180-player pool from Task 5's research.

- [ ] **Step 5: Validate and merge the bootstrap**

Run:
```bash
python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/BRA.json
python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/ARG.json
python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/URU.json
python3 .seed-work/merge_fa_fragments.py
python3 .seed-work/check_no_collisions.py
```
Expected: three `OK ...` lines, `merged 6 free agents across 3 countries: {'Brasil': 4, 'Argentina': 1, 'Uruguai': 1}`, then `OK — 6 free agents, no collisions with ... club-roster names, no internal duplicates`.

- [ ] **Step 6: Run the seed tests again to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.SeedLoaderTest'`

Expected: BUILD SUCCESSFUL, all tests pass including the two new ones.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/seed/SeedLoader.java backend/src/test/java/br/com/carvoeiromanager/seed/SeedLoaderTest.java backend/src/main/resources/seed/free-agents.json
git commit -m "$(cat <<'EOF'
feat(seed): add SeedLoader.loadFreeAgents() and a real bootstrap free-agent seed

Six real, currently-unattached South American players (Brasil/Argentina/
Uruguai) bootstrap the new seed file. Task 6 replaces this with the full
~180-player researched pool — nothing here is a placeholder or generated.
EOF
)"
```

---

### Task 3: `SaveGameService` wiring — seed the real pool at career creation

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentListIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`

**Interfaces:**
- Consumes: `SeedLoader.loadFreeAgents()` (Task 2).
- Produces: career creation seeds the free-agent pool from real seed data. `FreeAgentGenerator` is still used by `SeasonRolloverService` at this point (Task 4 removes that) — do not delete the class yet.

- [ ] **Step 1: Update the failing test for the real pool count**

In `backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentListIT.java`, add the import and change the assertion:

Add to the imports (after `import br.com.carvoeiromanager.common.ApiException;`):
```java
import br.com.carvoeiromanager.seed.SeedLoader;
```

Replace:
```java
    @Test
    void careerCreationSeedsAFreeAgentPool() {
        Career c = createCareer();
        var pool = contractService.getFreeAgents(c.id(), c.userId());
        assertThat(pool).hasSize(10);
        assertThat(pool).allMatch(fa -> fa.wageDemand().signum() > 0);
    }
```
with:
```java
    @Test
    void careerCreationSeedsAFreeAgentPool() {
        Career c = createCareer();
        var pool = contractService.getFreeAgents(c.id(), c.userId());
        assertThat(pool).hasSize(new SeedLoader().loadFreeAgents().size());
        assertThat(pool).allMatch(fa -> fa.wageDemand().signum() > 0);
    }
```

- [ ] **Step 2: Add the new career-creation sanity test**

In `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`, add this test immediately after `creatingCareerSeedsClubsPlayersAndFullFixtureList` (which currently ends at line 107 with a closing `}`), before `criciumaPlayersGetAuthoredAttributesThroughCareerCreation`:

```java
    @Test
    void creatingCareerSeedsTheRealFreeAgentPool() {
        // Given a persisted user
        User user = User.create("tigre_fa_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());

        // Then the free-agent pool is seeded 1:1 from the real seed file — no
        // procedural generation, no wage, no contract.
        var freeAgentSeeds = new SeedLoader().loadFreeAgents();
        var freeAgents = playerRepository.findBySaveGameIdAndClubIdIsNull(response.id());
        assertThat(freeAgents).hasSize(freeAgentSeeds.size());
        assertThat(freeAgents).allSatisfy(p ->
                assertThat(p.getWage()).isEqualByComparingTo(java.math.BigDecimal.ZERO));
        assertThat(freeAgents).allSatisfy(p -> assertThat(p.getContractYears()).isZero());

        // More than one South American nationality is represented — this is a
        // real multi-country pool, not the old Brasil-only procedural generator.
        var nationalities = freeAgents.stream()
                .map(br.com.carvoeiromanager.player.Player::getNationality)
                .collect(Collectors.toSet());
        assertThat(nationalities)
                .as("free-agent pool should span more than one nationality")
                .hasSizeGreaterThan(1);
    }
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.FreeAgentListIT' --tests 'br.com.carvoeiromanager.career.SaveGameServiceIT'`

Expected: `careerCreationSeedsAFreeAgentPool` FAILS (pool still has 10 generated agents, not the seeded count of 6), `creatingCareerSeedsTheRealFreeAgentPool` FAILS (all 10 free agents still carry `nationality = "Brasil"` — only one nationality). Every other test in both files still PASSES.

- [ ] **Step 4: Wire `SaveGameService.createCareer` to the real seed**

In `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`, remove the now-unused import:

```java
import br.com.carvoeiromanager.contract.FreeAgentGenerator;
```

Remove the field declaration:
```java
    private final FreeAgentGenerator freeAgentGenerator;
```

In the constructor signature, remove the parameter:
```java
                           FreeAgentGenerator freeAgentGenerator,
```

In the constructor body, remove the assignment:
```java
        this.freeAgentGenerator = freeAgentGenerator;
```

Replace the free-agent seeding block:
```java
        // Seed an initial free-agent pool (clubless) so there is always a market to shop.
        Random faRng = new Random(saveGame.getId().getMostSignificantBits() ^ 0xFA11AC0DL);
        for (int i = 0; i < 10; i++) {
            entityManager.persist(freeAgentGenerator.generate(saveGame.getId(), faRng));
        }
```
with:
```java
        // Seed the real free-agent pool (clubless) so there is always a market to shop —
        // hand-authored South American players, not procedurally generated.
        for (SeedPlayer seedPlayer : seedLoader.loadFreeAgents()) {
            Position position = Position.valueOf(seedPlayer.position());
            int overall = overallCalculator.compute(position,
                    seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                    seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina());
            Attributes attributes = seedPlayer.attributes() != null
                    ? seedPlayer.attributes().toAttributes(
                            seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                            seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina())
                    : AttributeDeriver.derive(position,
                            seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                            seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(), overall);
            Player freeAgent = Player.create(saveGame.getId(), null, seedPlayer.name(), position,
                    seedPlayer.age(), seedPlayer.nationality(), attributes, overall);
            entityManager.persist(freeAgent);
        }
```

The constructor call site that wires this bean (Spring's component scan) needs no change — only the parameter list shrinks. `SeasonRolloverService` still declares and injects its own separate `FreeAgentGenerator` field; that bean stays registered until Task 4.

- [ ] **Step 5: Run the tests again to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.contract.FreeAgentListIT' --tests 'br.com.carvoeiromanager.career.SaveGameServiceIT' --tests 'br.com.carvoeiromanager.contract.ContractServiceSignIT' --tests 'br.com.carvoeiromanager.contract.ContractServiceStaffIT'`

Expected: BUILD SUCCESSFUL, all tests pass (the last two suites are included because they sign free agents from a freshly created career and must keep working against the smaller 6-player bootstrap pool).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentListIT.java backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java
git commit -m "$(cat <<'EOF'
feat(career): seed the free-agent pool from real data at career creation

createCareer now loads seed/free-agents.json instead of running
FreeAgentGenerator in a loop. SeasonRolloverService still uses the
generator for its rollover backfill — that call site goes next.
EOF
)"
```

---

### Task 4: `SeasonRolloverService` wiring — remove the generated backfill, delete `FreeAgentGenerator`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java`
- Delete: `backend/src/main/java/br/com/carvoeiromanager/contract/FreeAgentGenerator.java`
- Delete: `backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentGeneratorTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SeasonRolloverService` no longer references `FreeAgentGenerator` in any way; the class is fully deleted (last remaining reference removed).

- [ ] **Step 1: Rewrite the floor-backfill test to assert no backfill happens**

In `backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java`, replace:

```java
    @Test
    void theFreeAgentPoolIsReplenishedToTheFloor() {
        Career c = playToSeasonEnd();
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(allPlayers);

        rolloverService.rollover(c.id(), c.userId());

        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size())
                .isGreaterThanOrEqualTo(6);
    }
```
with:
```java
    @Test
    void theFreeAgentPoolIsNotReplenishedAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5)); // nobody's contract expires this rollover
        playerRepository.saveAll(allPlayers);
        int poolBefore = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size();

        rolloverService.rollover(c.id(), c.userId());

        // No contracts expired, so the pool can only shrink (a free agent retiring)
        // or stay the same — it must never be topped back up by generation.
        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size())
                .isLessThanOrEqualTo(poolBefore);
    }
```

- [ ] **Step 2: Run the rollover tests to verify the new test fails for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.RolloverContractsIT'`

Expected: `theFreeAgentPoolIsNotReplenishedAtRollover` FAILS — the pool is still artificially topped back up to the floor of 6, which is greater than `poolBefore` (6, since the bootstrap seed has exactly 6 free agents and none of them retired). The other two tests in the file still PASS.

- [ ] **Step 3: Remove the floor-backfill from `SeasonRolloverService`**

In `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`, remove the import:

```java
import br.com.carvoeiromanager.contract.FreeAgentGenerator;
```

Replace the field block:
```java
    private final PlayerAgingService playerAgingService;
    private static final int FREE_AGENT_FLOOR = 6;

    private final NewgenGenerator newgenGenerator;
    private final FreeAgentGenerator freeAgentGenerator;
    private final FinanceTransactionRepository financeTransactionRepository;
```
with:
```java
    private final PlayerAgingService playerAgingService;

    private final NewgenGenerator newgenGenerator;
    private final FinanceTransactionRepository financeTransactionRepository;
```

In the constructor signature, replace:
```java
                                 NewgenGenerator newgenGenerator,
                                 FreeAgentGenerator freeAgentGenerator,
                                 FinanceTransactionRepository financeTransactionRepository,
```
with:
```java
                                 NewgenGenerator newgenGenerator,
                                 FinanceTransactionRepository financeTransactionRepository,
```

In the constructor body, replace:
```java
        this.newgenGenerator = newgenGenerator;
        this.freeAgentGenerator = freeAgentGenerator;
        this.financeTransactionRepository = financeTransactionRepository;
```
with:
```java
        this.newgenGenerator = newgenGenerator;
        this.financeTransactionRepository = financeTransactionRepository;
```

Remove the backfill block entirely:
```java
        // Replenish the free-agent pool to the floor so the market never dies.
        int poolSize = playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).size();
        for (int i = poolSize; i < FREE_AGENT_FLOOR; i++) {
            playerRepository.save(freeAgentGenerator.generate(saveGameId, rng));
        }

```
(leave the surrounding blank lines and the following "Top up the managed club's youth academy..." comment/block untouched).

- [ ] **Step 4: Delete `FreeAgentGenerator` and its test**

```bash
rm backend/src/main/java/br/com/carvoeiromanager/contract/FreeAgentGenerator.java
rm backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentGeneratorTest.java
```

- [ ] **Step 5: Fix the stale doc-comment cross-reference in `StaffCandidateGenerator`**

In `backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java`, replace:

```java
/**
 * Rolls staff candidates on demand — never persisted, unlike {@code FreeAgentGenerator}'s shared
 * pool. Deterministic for a given (saveGameId, clubId, role, onDate): the offer is stable within
 * a game-day and changes only as the season advances, so re-browsing the same role on the same
 * day always shows the same three candidates.
 */
```
with:
```java
/**
 * Rolls staff candidates on demand — never persisted, unlike the free-agent pool (seeded once
 * from real data and only ever shrinks). Deterministic for a given (saveGameId, clubId, role,
 * onDate): the offer is stable within a game-day and changes only as the season advances, so
 * re-browsing the same role on the same day always shows the same three candidates.
 */
```

- [ ] **Step 6: Run the rollover and contract test suites to verify everything is green**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.RolloverContractsIT' --tests 'br.com.carvoeiromanager.contract.*' --tests 'br.com.carvoeiromanager.staff.*'`

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java backend/src/main/java/br/com/carvoeiromanager/staff/StaffCandidateGenerator.java backend/src/test/java/br/com/carvoeiromanager/season/RolloverContractsIT.java backend/src/main/java/br/com/carvoeiromanager/contract/FreeAgentGenerator.java backend/src/test/java/br/com/carvoeiromanager/contract/FreeAgentGeneratorTest.java
git commit -m "$(cat <<'EOF'
feat(season): stop replenishing the free-agent pool with generated players

The pool now only shrinks (signings) or grows the way it already did
(a managed player's contract lapsing) — no more procedural top-up at
rollover. FreeAgentGenerator has no remaining callers; deleted.
EOF
)"
```

---

### Task 5: Research and author the full ~180-player pool (parallelizable)

**Files:**
- Create: `.seed-work/free-agents/<CODE>.json` — one per country, 8 files (overwrites the 3 bootstrap files from Task 2 plus 5 new ones).

**Interfaces:**
- Consumes: `.seed-work/validate_fa_fragment.py` (Task 1).
- Produces: validated fragments consumed by `merge_fa_fragments.py` (Task 6). Fragment shape: `{ "country": "<label>", "players": [...] }`.

**This task runs once per country in the table below. All 8 runs are independent — dispatch them as parallel research subagents (2 batches of 4). Each subagent gets the full prompt template with its country's row substituted in.**

**Country table:**

| # | code | country (nationality label) | target size |
|---|------|------------------------------|-------------|
| 1 | BRA  | Brasil                       | ~40 (30-50) |
| 2 | ARG  | Argentina                    | ~20 (15-25) |
| 3 | URU  | Uruguai                      | ~20 (15-25) |
| 4 | COL  | Colômbia                     | ~20 (15-25) |
| 5 | PAR  | Paraguai                     | ~20 (15-25) |
| 6 | CHI  | Chile                        | ~20 (15-25) |
| 7 | VEN  | Venezuela                    | ~20 (15-25) |
| 8 | ECU  | Equador                      | ~20 (15-25) |

**Subagent prompt template (substitute `{CODE}`, `{COUNTRY}`, `{TARGET}`):**

````text
You are authoring real, currently-unattached (free agent) football players from
{COUNTRY} for a football-manager game's free-agent pool. Work from repo root
/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager.

1. RESEARCH (WebSearch): find real {COUNTRY} players who are genuinely without a
   club right now — rescinded contracts, released, out of contract and unsigned.
   Search things like "jugadores libres {COUNTRY} 2026", "agentes libres futbol
   {COUNTRY}", "<player> sin club 2026", Transfermarkt's free-agent listing
   filtered by nationality (transfermarkt.us/statistik/vertragslosespieler), and
   sports-news roundups of released players. Aim for {TARGET} players spanning a
   believable mix: some faded ex-top-flight veterans, some squad/rotation players
   let go by a mid-table club, some unproven young players who didn't get a deal.
   Real free agents by definition were NOT good enough to keep a club interested —
   most should sit at Série B backup/rotation quality or below; a handful of
   faded former-quality veterans (like Task 2's bootstrap examples: Luiz Gustavo,
   Darío Benedetto) can carry a couple of standout attributes (leadership, set
   pieces, finishing) despite an otherwise declined profile. Do not invent
   players — every entry must be independently verifiable as real and,
   as best you can confirm, currently unattached.

2. AVOID DUPLICATES: none of your players may share a name with any player
   already in `backend/src/main/resources/seed/clubs.json`'s club rosters, or
   with a real player another country batch might plausibly also pick (stick to
   players who are actually {COUNTRY} nationals — nationality is the dedup key
   across batches).

3. AUTHOR the JSON fragment at `.seed-work/free-agents/{CODE}.json`:
   { "country": "{COUNTRY}", "players": [...] }
   Each player object, keys in THIS order:
   name, position (GK|DEF|MID|ATT), age, nationality ("{COUNTRY}"),
   pace, finishing, passing, tackling, goalkeeping, stamina,
   attributes: { drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge,
     visao, posicionamento, decisao, compostura, concentracao, determinacao,
     agressividade, lideranca, aceleracao, forca, resistencia, equilibrio,
     agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes }
   All values ints 1-99. Map positions: defenders (incl. full-backs) -> DEF,
   defensive/central/attacking midfielders -> MID, wingers/strikers -> ATT.

4. CALIBRATE attributes to each player's real profile, mostly at or below
   Série B backup/rotation quality (this is a free-agent pool — nobody here was
   good enough to stay employed):
   - Rotation/fringe players: 38-55 in their strengths.
   - Faded ex-top-flight veterans: one or two standout attributes (55-75, e.g.
     lideranca, decisao, bolaParada, finishing) but LOW pace/aceleracao/
     resistencia for anyone 33+.
   - Unproven young players (under 23): 30-48, with relatively higher
     determinacao and lower compostura/decisao.
   - GKs: goalkeeping 45-72; low outfield technicals (finishing <= 40).
   - Outfielders: goalkeeping <= 40 (usually 3-15). Outfielders'
     posicionamentoGk/saidaDeGol/jogoComOsPes stay low (single digits to low
     teens) regardless of technical quality — these are goalkeeper-specific
     attributes, not general skill.

5. VALIDATE: run
   `python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/{CODE}.json`
   and fix issues until it prints OK.

6. RETURN: one line per outcome — player count, position counts, 3 sample
   players with the source you used to confirm each is real and currently
   unattached, and any player you were unsure about.
````

- [ ] **Step 1: Author fragments for batch 1 — BRA, ARG, URU, COL (4 parallel subagents)**
- [ ] **Step 2: Author fragments for batch 2 — PAR, CHI, VEN, ECU (4 parallel subagents)**
- [ ] **Step 3: Re-validate all fragments in one sweep**

Run: `for f in .seed-work/free-agents/*.json; do python3 .seed-work/validate_fa_fragment.py "$f" || exit 1; done && ls .seed-work/free-agents/ | wc -l`
Expected: 8 `OK` lines and count `8`.

- [ ] **Step 4: Spot-check research quality**

For 3 random countries among the 8, verify 2 named players each are genuinely real and plausibly still unattached (quick WebSearch). Any invented or clearly-wrong player → send that fragment back to a fix-up subagent with the correction, then re-run Step 3.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 6: Merge the full pool into `free-agents.json` and go fully green

**Files:**
- Modify: `backend/src/main/resources/seed/free-agents.json` (via `merge_fa_fragments.py`)

**Interfaces:**
- Consumes: 8 validated fragments (Task 5), `merge_fa_fragments.py` + `check_no_collisions.py` (Task 1).
- Produces: the final seed — every existing free-agent test now runs against the real ~180-player pool instead of the 6-player bootstrap.

- [ ] **Step 1: Merge**

Run: `python3 .seed-work/merge_fa_fragments.py`
Expected: `merged N free agents across 8 countries: {...}` — N in the 150-210 range (targets, not hard requirements — see Global Constraints).

- [ ] **Step 2: Verify no collisions, internal or against club rosters**

Run: `python3 .seed-work/check_no_collisions.py`
Expected: `OK — N free agents, no collisions with ... club-roster names, no internal duplicates`.

- [ ] **Step 3: Run the full seed, career, contract, and rollover test suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.*' --tests 'br.com.carvoeiromanager.career.*' --tests 'br.com.carvoeiromanager.contract.*' --tests 'br.com.carvoeiromanager.season.*'`

Expected: BUILD SUCCESSFUL, all tests pass — `careerCreationSeedsAFreeAgentPool` and `creatingCareerSeedsTheRealFreeAgentPool` now assert against the real ~180-player count, `freeAgentNamesDoNotCollideWithAnyClubRosterPlayer` and `theFreeAgentPoolIsNotReplenishedAtRollover` pass against real data.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/seed/free-agents.json
git commit -m "$(cat <<'EOF'
feat(seed): research and author the full real free-agent pool

~180 real, currently-unattached South American players (Brasil plus
Argentina, Uruguai, Colômbia, Paraguai, Chile, Venezuela, Equador),
hand-authored the same way the 28 club rosters were. Fully replaces
the Task 2 bootstrap — the free-agent pool is now entirely real data.
EOF
)"
```

---

### Task 7: Full backend suite and cleanup

**Files:**
- Delete: `.seed-work/` (tooling, not committed)

**Interfaces:**
- Consumes: final seed (Task 6).
- Produces: green full test suite; clean working tree apart from the intended changes.

- [ ] **Step 1: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Clean up tooling**

Run: `rm -rf .seed-work`
Expected: `git status` shows a clean working tree apart from any pre-existing untracked docs from earlier session work — every file this plan touched was already committed in Tasks 2, 3, 4, and 6.

---

## Self-Review Notes

- **Spec coverage:** every section of `2026-07-17-real-free-agents-design.md` maps onto a task —
  seed file shape and pool composition (Tasks 1-2, 5-6), `SeedLoader` addition (Task 2),
  `SaveGameService` wiring (Task 3), `SeasonRolloverService` no-backfill + `FreeAgentGenerator`
  deletion (Task 4), testing (new tests woven into Tasks 2-4 and re-verified in Task 6),
  out-of-scope items (nationality display, pre-contract offers, watchlist) are untouched by
  every task above.
- **Two-stage data landing, mirroring the Catarinense precedent:** Task 2's 6-player bootstrap
  (real players, not placeholders) exists only so Tasks 3-4's wiring tests have real data to run
  against without being blocked on the full research pass; Task 6 fully supersedes it. Every test
  written against the bootstrap (`careerCreationSeedsAFreeAgentPool`,
  `creatingCareerSeedsTheRealFreeAgentPool`, `theFreeAgentPoolIsNotReplenishedAtRollover`) asserts
  against `seedLoader.loadFreeAgents().size()` or a relative (`<=`) comparison rather than a
  hardcoded literal, so it stays valid — and meaningfully re-verified — after Task 6 lands the
  full ~180-player pool. No test needs a second red/green wave.
- **Verified, not guessed:** the six bootstrap players in Task 2 were independently confirmed via
  WebSearch during plan-writing (each is a real player reported as contract-rescinded/released as
  of mid-2026), not invented — the plan does not ask an implementer to trust unverified names for
  the parts that ship inside a task's own commit.
- **Type/name consistency:** `SeedPlayer`/`SeedAttributes` (used unchanged, no new Java type),
  `loadFreeAgents(): List<SeedPlayer>` signature is identical everywhere it's referenced (Tasks
  2-6), fragment shape `{ "country", "players" }` is identical across the validator, merge script,
  Task 2's bootstrap, and Task 5's research prompt.
- **Known judgment call:** Task 3 deliberately leaves `FreeAgentGenerator` in place (still used by
  `SeasonRolloverService`) — flagged explicitly in that task's Interfaces and commit message, so
  nobody mid-execution mistakes the partial removal for a missed cleanup. Task 4 completes it in
  the same continuous run.
