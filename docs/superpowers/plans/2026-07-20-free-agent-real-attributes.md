# Free-Agent Real Attributes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-research and re-author the attributes (and, where research contradicts them, age/position/nationality) of all 179 players already in `seed/free-agents.json`, so each player's ratings track their real career level on one consistent scale — without adding, removing, or renaming any player.

**Architecture:** The existing 179-entry seed file is split by nationality into 8 country fragments under `.seed-work/free-agents/`. Eight parallel research subagents rewrite their fragment's players in place against a shared calibration rubric (5 tiers, age-decline and experience rules, existing GK/anchor constraints). A consistency-review subagent then compares Tier S/A players across all 8 fragments side by side and corrects any that drifted off the shared scale. A merge step rebuilds `seed/free-agents.json` from the fragments and a stability check confirms the name set is byte-for-byte the same 179 names as before the pass. No Java code changes — this is a pure data-authoring cycle, so "testing" is schema/range validation plus a full rerun of the existing backend suite that already reads this file.

**Tech Stack:** Python 3 for seed tooling (mirrors the pattern from the real-free-agents cycle), Java 21 / Spring Boot only to rerun the existing test suite (Gradle: `./gradlew test` from `backend/`).

**Spec:** `docs/superpowers/specs/2026-07-20-free-agent-real-attributes-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference, this project only).
- Local `git commit` is allowed and expected on this project — each task that touches tracked files ends by committing its own tested work. `git push` is never run without an explicit user ask.
- The free-agent pool stays at exactly the same 179 players, identified by `name`. No task may add, remove, or rename a player. `age`, `position`, and `nationality` may be corrected when research contradicts the current value; `attributes` and the 6 anchors are expected to change for essentially every player.
- All attribute and anchor values are ints 1–99.
- `SeedPlayer` key order (unchanged from the existing file): `name, position, age, nationality, pace, finishing, passing, tackling, goalkeeping, stamina, attributes`. Attributes key order (22 keys, exactly): `drible, cruzamento, cabeceio, marcacao, bolaParada, chuteLonge, visao, posicionamento, decisao, compostura, concentracao, determinacao, agressividade, lideranca, aceleracao, forca, resistencia, equilibrio, agilidade, posicionamentoGk, saidaDeGol, jogoComOsPes`.
- Valid `nationality` values: `Brasil, Argentina, Uruguai, Colômbia, Paraguai, Chile, Venezuela, Equador, Portugal` (the last is the single existing Sérgio Oliveira entry — real fact, not a target-country label, already present in the file).
- GK entries: `finishing <= 40`. Outfielder entries: `goalkeeping <= 40`. Outfielders' `posicionamentoGk`/`saidaDeGol`/`jogoComOsPes` stay low (single digits to low teens).
- Age range: 18–42 inclusive.
- Calibration rubric (used verbatim by every research subagent — see Task 2): 5 tiers (S/A/B/C/D) on signature attributes, physicals decline with age from 30 (sharply after 34), mentals track experience/career length rather than tier, anchors must agree with the attribute block.
- Working dir for all commands: repo root `/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager` unless stated. Backend test commands: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` — use a long timeout (600000ms); `*IT` tests spin up Testcontainers Postgres and take longer than plain unit tests.
- `.seed-work/` is temporary tooling — never `git add`ed, deleted in the final task.

---

### Task 1: Seed tooling — formatter, snapshot, split, validator, merge, stability check

**Files:**
- Create: `.seed-work/fafmt.py`
- Create: `.seed-work/snapshot_names.py`
- Create: `.seed-work/split_by_country.py`
- Create: `.seed-work/validate_fa_fragment.py`
- Create: `.seed-work/merge_fa_fragments.py`
- Create: `.seed-work/check_name_set_stable.py`

**Interfaces:**
- Consumes: `backend/src/main/resources/seed/free-agents.json` (read-only until Task 5's merge).
- Produces: `fafmt.load_seed()/save_seed()/dump_seed()` used by every other script in this task and by Tasks 2, 4, and 5; `.seed-work/original-names.json` (the 179-name snapshot) consumed by `check_name_set_stable.py` (Task 5); `.seed-work/free-agents/<CODE>.json` fragments (8 files) consumed by Tasks 3 and 4; `validate_fa_fragment.py <fragment.json>` used by Tasks 3 and 4; `merge_fa_fragments.py` and `check_name_set_stable.py` used by Task 5.

**No commit for this task** — `.seed-work/` is temporary tooling, never `git add`ed (see Global Constraints). Nothing in this task touches a tracked file.

- [ ] **Step 1: Write the seed formatter**

Create `.seed-work/fafmt.py`:

```python
#!/usr/bin/env python3
"""Load/dump free-agents.json in its exact house style: a flat JSON array of
SeedPlayer objects, one line each, 2-space indent."""
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
```

- [ ] **Step 2: Run the round-trip check against the real, already-existing seed file**

Run: `python3 .seed-work/fafmt.py`
Expected: `ROUND-TRIP OK` — the existing 179-player file already uses this exact style, so this proves the formatter is byte-exact before anything gets rewritten.

- [ ] **Step 3: Write the name snapshot script and run it**

Create `.seed-work/snapshot_names.py`:

```python
#!/usr/bin/env python3
"""Snapshot the current free-agent name set before any rewriting starts, so
Task 5 can prove the pass didn't add/remove/rename anyone."""
import json
import sys

sys.path.insert(0, ".seed-work")
import fafmt

players = fafmt.load_seed()
names = sorted(p["name"] for p in players)
with open(".seed-work/original-names.json", "w", encoding="utf-8") as f:
    json.dump(names, f, ensure_ascii=False, indent=2)
print(f"snapshotted {len(names)} names to .seed-work/original-names.json")
```

Run: `python3 .seed-work/snapshot_names.py`
Expected: `snapshotted 179 names to .seed-work/original-names.json`

- [ ] **Step 4: Write the country-split script and run it**

Create `.seed-work/split_by_country.py`:

```python
#!/usr/bin/env python3
"""Split the current free-agents.json into one fragment per research batch,
grouped by nationality. The single Portugal entry (Sérgio Oliveira) is folded
into the BRA batch per the design spec — same researcher, same batch file."""
import json
import os
import sys

sys.path.insert(0, ".seed-work")
import fafmt

NATIONALITY_TO_CODE = {
    "Brasil": "BRA", "Portugal": "BRA",
    "Argentina": "ARG", "Uruguai": "URU", "Colômbia": "COL",
    "Paraguai": "PAR", "Chile": "CHI", "Venezuela": "VEN", "Equador": "ECU",
}

players = fafmt.load_seed()
by_code = {}
for p in players:
    code = NATIONALITY_TO_CODE.get(p["nationality"])
    if code is None:
        sys.exit(f"unmapped nationality {p['nationality']!r} on player {p['name']!r}")
    by_code.setdefault(code, []).append(p)

os.makedirs(".seed-work/free-agents", exist_ok=True)
for code, group in sorted(by_code.items()):
    path = f".seed-work/free-agents/{code}.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"code": code, "players": group}, f, ensure_ascii=False, indent=2)
    print(f"{code}: {len(group)} players -> {path}")
```

Run: `python3 .seed-work/split_by_country.py`
Expected: 8 lines, one per code (`BRA` includes the Portugal entry so its count is 40, one more than the 39 Brasil-nationality players), summing to 179.

- [ ] **Step 5: Write the fragment validator**

Create `.seed-work/validate_fa_fragment.py`:

```python
#!/usr/bin/env python3
"""Validate one free-agent country fragment (.seed-work/free-agents/<CODE>.json)
after a research subagent has rewritten its players' attributes."""
import json
import sys

ATTR_KEYS = ["drible", "cruzamento", "cabeceio", "marcacao", "bolaParada",
             "chuteLonge", "visao", "posicionamento", "decisao", "compostura",
             "concentracao", "determinacao", "agressividade", "lideranca",
             "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
             "posicionamentoGk", "saidaDeGol", "jogoComOsPes"]
ANCHORS = ["pace", "finishing", "passing", "tackling", "goalkeeping", "stamina"]
POSITIONS = {"GK", "DEF", "MID", "ATT"}
VALID_NATIONALITIES = {"Brasil", "Argentina", "Uruguai", "Colômbia", "Paraguai",
                        "Chile", "Venezuela", "Equador", "Portugal"}
VALID_CODES = {"BRA", "ARG", "URU", "COL", "PAR", "CHI", "VEN", "ECU"}


def main(path):
    with open(path, encoding="utf-8") as f:
        frag = json.load(f)
    errors = []
    code = frag.get("code")
    if code not in VALID_CODES:
        errors.append(f"unexpected code {code!r}")
    extra_keys = set(frag.keys()) - {"code", "players"}
    if extra_keys:
        errors.append(f"fragment should only carry code+players, found extra keys {sorted(extra_keys)}")

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
        if p.get("nationality") not in VALID_NATIONALITIES:
            errors.append(f"{n}: invalid nationality {p.get('nationality')!r}")
        pos = p.get("position")
        if pos not in POSITIONS:
            errors.append(f"{n}: invalid position {pos!r}")
            continue
        counts[pos] += 1
        if not isinstance(p.get("age"), int) or not 18 <= p["age"] <= 42:
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
        if pos != "GK":
            for gk_attr in ("posicionamentoGk", "saidaDeGol", "jogoComOsPes"):
                v = attrs.get(gk_attr)
                if isinstance(v, int) and v > 20:
                    errors.append(f"{n}: outfielder {gk_attr}={v} > 20 (should be single digits to low teens)")

    if errors:
        print(f"INVALID {path}")
        for e in errors:
            print(f" - {e}")
        sys.exit(1)
    print(f"OK {path}: {len(players)} players {counts}")


if __name__ == "__main__":
    main(sys.argv[1])
```

- [ ] **Step 6: Write the merge script**

Create `.seed-work/merge_fa_fragments.py`:

```python
#!/usr/bin/env python3
"""Merge the 8 rewritten free-agent fragments from .seed-work/free-agents/
back into backend/src/main/resources/seed/free-agents.json, replacing its
contents entirely."""
import json
import sys

sys.path.insert(0, ".seed-work")
import fafmt

CODES = ["BRA", "ARG", "URU", "COL", "PAR", "CHI", "VEN", "ECU"]

players = []
per_code = {}
for code in CODES:
    path = f".seed-work/free-agents/{code}.json"
    with open(path, encoding="utf-8") as f:
        frag = json.load(f)
    per_code[code] = len(frag["players"])
    players.extend(frag["players"])

fafmt.save_seed(players)
print(f"merged {len(players)} free agents across {len(per_code)} fragments: {per_code}")
```

- [ ] **Step 7: Write the name-set stability check**

Create `.seed-work/check_name_set_stable.py`:

```python
#!/usr/bin/env python3
"""Verify the merged free-agents.json has exactly the same 179 names as the
pre-pass snapshot — no additions, removals, or renames."""
import json
import sys

sys.path.insert(0, ".seed-work")
import fafmt

with open(".seed-work/original-names.json", encoding="utf-8") as f:
    original = set(json.load(f))

current = {p["name"] for p in fafmt.load_seed()}

missing = original - current
added = current - original

if missing or added:
    print("FAILED:")
    if missing:
        print(f" - names removed/renamed away: {sorted(missing)}")
    if added:
        print(f" - names added/renamed to: {sorted(added)}")
    sys.exit(1)
print(f"OK — {len(current)} names identical to the pre-pass snapshot")
```

- [ ] **Step 8: Smoke-test the validator against a hand-built bad fragment**

Run:
```bash
python3 - <<'EOF'
import json
frag = {"code": "BRA", "players": [
    {"name": "Smoke Test Player", "position": "GK", "age": 99, "nationality": "Brasil",
     "pace": 1, "finishing": 1, "passing": 1, "tackling": 1, "goalkeeping": 1, "stamina": 1,
     "attributes": {}}
]}
json.dump(frag, open("/tmp/smoke-fa.json", "w"), ensure_ascii=False)
EOF
python3 .seed-work/validate_fa_fragment.py /tmp/smoke-fa.json
rm /tmp/smoke-fa.json
```
Expected: `INVALID /tmp/smoke-fa.json` reporting `bad age 99` and `attributes missing [...]` (all 22 keys) — proves the validator parses real structure and enforces both rules.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 2: Write the calibration rubric

**Files:**
- Create: `.seed-work/rubric.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `.seed-work/rubric.md`, read verbatim by every research subagent in Task 3 and by the consistency-review subagent in Task 4.

**No commit for this task** — scratch tooling content, never `git add`ed.

- [ ] **Step 1: Write the rubric**

Create `.seed-work/rubric.md`:

```markdown
# Free-Agent Attribute Calibration Rubric

Applies to every player in the free-agent pool. Tiers describe *signature*
attributes — the technical/mental attributes central to the player's real
role (e.g. `finishing`/`chuteLonge` for a striker, `bolaParada`/`visao` for a
passing midfielder, `marcacao`/`cabeceio` for a stopper centre-back,
`goalkeeping`/`posicionamentoGk` for a keeper).

## Tiers (signature attributes)

- **S — ex-elite** (played regularly at a major European league or a top
  South American club at genuine quality, has real caps/silverware to show
  for it — e.g. Coutinho, Cavani, Falcao, Valencia, Isla, Luiz Gustavo):
  signature attributes 80–92. These MAY exceed the best current club-roster
  values in this game's data (today's ceiling: `bolaParada` 88, `visao` 83,
  anchor tops 80–84) — rate from the real career, don't cap to match the
  league.
- **A — solid top-flight career / capped international without S-tier
  peak:** signature attributes 68–80.
- **B — top-flight squad player, or a strong second-division regular:**
  signature attributes 52–68.
- **C — journeyman, spent most of the career in lower divisions:**
  signature attributes 38–55.
- **D — unproven youngster, little to no senior first-team career yet:**
  overall attribute spread 28–45. Physicals can sit above the tier band;
  mentals stay low regardless.

Non-signature attributes for a player scale down from the tier band — a
Tier-S striker doesn't also get Tier-S tackling.

## Cross-cutting rules

1. **Physicals decline with age, independent of tier.** `pace`,
   `aceleracao`, `stamina`, `resistencia`, `agilidade` fall off starting
   around age 30, and sharply after 34. A 39-year-old Tier-S striker can
   keep `chuteLonge`/finishing near his tier ceiling while `pace` and
   `aceleracao` sit in the 30s–40s. Don't let a veteran's physicals sit
   above what a real player that age could do.
2. **Mentals track experience, not tier.** `decisao`, `compostura`,
   `concentracao`, `lideranca` scale with career length/seniority more than
   with technical level — a long-serving Tier-C or Tier-B defender can still
   post a high `lideranca` if the research supports it (captaincy, veteran
   squad role).
3. **Anchors must agree with the attribute block.** The 6 top-level anchors
   (`pace`, `finishing`, `passing`, `tackling`, `goalkeeping`, `stamina`)
   are not independent of the 22-key block — e.g. a high `passing` anchor
   should come with high `visao`/`chuteLonge` for a passing-profile player;
   a high `pace` anchor should come with high `aceleracao`/`agilidade`.
4. **Hard constraints (unchanged from the existing file):**
   - All attribute and anchor values are integers 1–99.
   - GK entries: `finishing <= 40`.
   - Outfielder entries: `goalkeeping <= 40`, and
     `posicionamentoGk`/`saidaDeGol`/`jogoComOsPes` stay low (single digits
     to low teens) — these are goalkeeper-specific attributes, not general
     technical skill.
   - Age stays in 18–42.
   - `SeedPlayer` key order and the 22-key attribute order must be preserved
     exactly (the fragment already has every player in this order — keep it
     when rewriting).
5. **Never rename, add, or remove a player.** The `name` field is locked —
   every player already in your fragment must still be there, spelled
   exactly the same way, when you're done. If research turns up a different
   real name for the same person, keep the existing spelling; this pass
   only changes ratings (plus age/position/nationality when clearly wrong).
6. **`age`/`position`/`nationality` may be corrected** when research
   directly contradicts the current value — e.g. the file says DEF but the
   player has spent their whole career at right-back-turned-winger, or the
   age is a year off from public sources. Don't change these speculatively;
   only correct them when you have a source.
```

- [ ] **Step 2: Verify the rubric file is complete**

Run: `grep -c '^##' .seed-work/rubric.md`
Expected: `2` (the two `##` section headers: "Tiers (signature attributes)" and "Cross-cutting rules").

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 3: Research and rewrite all 8 country batches (parallelizable)

**Files:**
- Modify: `.seed-work/free-agents/<CODE>.json` — all 8 fragments, in place (players already exist in each; only their `age`/`position`/`nationality`/anchors/`attributes` are rewritten).

**Interfaces:**
- Consumes: `.seed-work/rubric.md` (Task 2), `.seed-work/validate_fa_fragment.py` (Task 1), the 8 fragments split in Task 1.
- Produces: validated, re-researched fragments consumed by Task 4 (consistency review) and Task 5 (merge).

**This task runs once per country/code below. All 8 runs are independent — dispatch them as parallel research subagents (2 batches of 4). Each subagent gets the full prompt template with its code's row substituted in.**

**Batch table:**

| # | code | nationalities in this fragment | player count |
|---|------|----------------------------------|---------------|
| 1 | BRA  | Brasil (+ the single Portugal entry, Sérgio Oliveira) | 40 |
| 2 | ARG  | Argentina                        | 18 |
| 3 | URU  | Uruguai                           | 18 |
| 4 | COL  | Colômbia                          | 24 |
| 5 | PAR  | Paraguai                          | 20 |
| 6 | CHI  | Chile                             | 20 |
| 7 | VEN  | Venezuela                         | 17 |
| 8 | ECU  | Equador                           | 22 |

(Exact counts per code — confirm against `.seed-work/free-agents/<CODE>.json`'s actual length; the table above reflects the current file's real distribution.)

**Subagent prompt template (substitute `{CODE}`):**

````text
You are re-researching and re-rating real football players already listed in
this game's free-agent pool. Work from repo root
/Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager.

1. READ `.seed-work/rubric.md` in full — this is the calibration rubric you
   must apply to every player. Read it before touching any data.

2. READ `.seed-work/free-agents/{CODE}.json` — this is your batch. Every
   player in it is a REAL person, already correctly identified by name. Your
   job is not to find new players; it's to research each existing name and
   re-rate them accurately.

3. FOR EACH PLAYER in the fragment, in order:
   a. WebSearch the player's real career: which clubs, which divisions,
      peak level reached, playing style, age, natural position, any
      leadership role (captaincy), and — if findable — why they're
      currently a free agent (released, contract expired, rescinded).
   b. Assign a tier per the rubric (S/A/B/C/D) based on real career level.
   c. Rewrite the player's `age`, `position`, and `nationality` ONLY if
      your research clearly contradicts the current value (cite what you
      found in your final report). Otherwise leave them as-is.
   d. Rewrite the 6 anchors and the full 22-key `attributes` block to match
      the tier and the rubric's age-decline / experience rules. Keep the
      exact key order already present in the file.
   e. Do NOT change the `name` field. Do NOT add or remove players.

4. VALIDATE: run
   `python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/{CODE}.json`
   and fix issues until it prints OK.

5. RETURN: a tier breakdown (how many S/A/B/C/D), 3 example players with the
   tier you assigned and the source that justified it, and any player you
   were unsure about (thin public data, conflicting sources, etc.) and how
   you handled it.
````

- [ ] **Step 1: Research and rewrite batch 1 — BRA, ARG, URU, COL (4 parallel subagents)**
- [ ] **Step 2: Research and rewrite batch 2 — PAR, CHI, VEN, ECU (4 parallel subagents)**
- [ ] **Step 3: Re-validate all fragments in one sweep**

Run: `for f in .seed-work/free-agents/*.json; do python3 .seed-work/validate_fa_fragment.py "$f" || exit 1; done`
Expected: 8 `OK` lines, one per fragment.

- [ ] **Step 4: Confirm no fragment lost or gained a player**

Run:
```bash
python3 - <<'EOF'
import json, glob
for path in sorted(glob.glob(".seed-work/free-agents/*.json")):
    frag = json.load(open(path, encoding="utf-8"))
    print(path, len(frag["players"]))
EOF
```
Expected: the same 8 counts as the split step in Task 1 (e.g. `BRA.json 40`, `ARG.json 18`, ...) — no fragment's player count changed.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 4: Cross-batch consistency review

**Files:**
- Modify: `.seed-work/free-agents/<CODE>.json` — any fragment with a flagged outlier, in place.

**Interfaces:**
- Consumes: 8 validated fragments (Task 3), `.seed-work/rubric.md` (Task 2), `validate_fa_fragment.py` (Task 1).
- Produces: fragments where every Tier-S/Tier-A player sits on one consistent cross-country scale, ready for Task 5's merge.

- [ ] **Step 1: Dispatch the consistency-review subagent**

Subagent prompt:

````text
Work from repo root /Users/filipelinemburger/Documents/filipe/projects/carvoeiro-manager.

1. READ `.seed-work/rubric.md`.
2. READ all 8 fragments in `.seed-work/free-agents/*.json`.
3. For every player whose signature attributes land in the Tier-S (80-92) or
   Tier-A (68-80) band, list them together in one table, sorted by their
   average signature-attribute value, with: name, code, position, age,
   tier claimed, and their key signature attribute values.
4. Scan that combined list for inconsistencies — e.g. two players with
   similar real career levels rated more than ~10 points apart on their
   signature attributes, or a player whose assigned tier doesn't match the
   values actually written (claims Tier A but values sit in Tier B range).
5. For every inconsistency found, edit the offending player's entry directly
   in its `.seed-work/free-agents/<CODE>.json` fragment to bring it onto the
   shared scale — do not touch players that are already consistent.
6. After edits, run
   `python3 .seed-work/validate_fa_fragment.py .seed-work/free-agents/<CODE>.json`
   for every fragment you touched, and fix any validation errors.
7. RETURN: the combined Tier-S/A table you built, and a list of every
   correction you made (player, code, what changed, why).
````

- [ ] **Step 2: Re-validate every fragment after the consistency pass**

Run: `for f in .seed-work/free-agents/*.json; do python3 .seed-work/validate_fa_fragment.py "$f" || exit 1; done`
Expected: 8 `OK` lines.

- [ ] **Step 3: Re-confirm no fragment lost or gained a player**

Run the same per-fragment count script from Task 3 Step 4.
Expected: identical counts to Task 3 — the consistency pass only edits values, never adds/removes entries.

**No commit for this task** — `.seed-work/` is scratch tooling output, never `git add`ed.

---

### Task 5: Merge into `free-agents.json`, verify stability, run the backend suite

**Files:**
- Modify: `backend/src/main/resources/seed/free-agents.json` (via `merge_fa_fragments.py`)

**Interfaces:**
- Consumes: 8 finalized fragments (Task 4), `merge_fa_fragments.py` + `check_name_set_stable.py` (Task 1).
- Produces: the final seed file — every existing free-agent test now runs against the re-rated pool, still 179 players, same names.

- [ ] **Step 1: Merge**

Run: `python3 .seed-work/merge_fa_fragments.py`
Expected: `merged 179 free agents across 8 fragments: {...}` with the per-code counts summing to 179.

- [ ] **Step 2: Verify the name set is unchanged**

Run: `python3 .seed-work/check_name_set_stable.py`
Expected: `OK — 179 names identical to the pre-pass snapshot`.

- [ ] **Step 3: Run the seed, career, contract, and rollover test suites**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.seed.*' --tests 'br.com.carvoeiromanager.career.*' --tests 'br.com.carvoeiromanager.contract.*' --tests 'br.com.carvoeiromanager.season.*'`

Expected: BUILD SUCCESSFUL, all tests pass. None of these tests hardcode a free-agent count — `FreeAgentListIT` and `SaveGameServiceIT` both assert against `new SeedLoader().loadFreeAgents().size()`, so they pass unchanged against the re-rated data as long as the count stays 179 (confirmed by Step 2).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/seed/free-agents.json
git commit -m "$(cat <<'EOF'
feat(seed): re-author free-agent attributes to match real career level

Re-researched all 179 free agents against a shared 5-tier calibration
rubric (physicals decline with age, mentals track experience, ex-elite
names can exceed today's club-roster ceilings) so ratings reflect real
career quality instead of the original cycle's time-pressured pass.
Same 179 players, no adds/removes/renames — attributes only, plus a
few age/position/nationality corrections where research contradicted
the existing value.
EOF
)"
```

---

### Task 6: Full backend suite and cleanup

**Files:**
- Delete: `.seed-work/` (tooling, not committed)

**Interfaces:**
- Consumes: final seed (Task 5).
- Produces: green full test suite; clean working tree apart from the intended change.

- [ ] **Step 1: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Clean up tooling**

Run: `rm -rf .seed-work`
Expected: `git status` shows a clean working tree apart from any pre-existing untracked docs from earlier session work — the only tracked-file change this plan makes (`free-agents.json`) was already committed in Task 5.

---

## Self-Review Notes

- **Spec coverage:** the calibration rubric (Task 2) implements the design's tier table and
  cross-cutting rules verbatim; the country-batched research process (Task 3) matches the design's
  "eight research batches" section, including the BRA/Portugal folding; the consistency review
  (Task 4) implements the design's cross-batch consistency pass; the name-set stability check
  (Task 1 Step 7, run in Task 5 Step 2) implements the design's testing requirement; no Java files
  are touched anywhere, matching the design's explicit "no Java/backend code changes" scope; the
  out-of-scope items (adding/removing free agents, nationality display, `ContractValuation`
  changes, composition rebalancing) are untouched by every task above.
- **Placeholder scan:** every script and the rubric are complete, runnable content — no TBDs. The
  batch table's exact per-code counts are flagged as "confirm against the actual split output"
  rather than asserted as a hard requirement, since they're a derived fact from the existing file,
  not a design decision — this mirrors how the original cycle's plan treated target counts as
  approximate rather than hardcoded.
- **Type/name consistency:** `fafmt.load_seed()/save_seed()/dump_seed()` signatures are identical
  everywhere they're imported (Tasks 1 script definitions, used by snapshot/split/merge/stability
  scripts); fragment shape `{ "code", "players" }` is identical across the split script, validator,
  merge script, and every subagent prompt in Tasks 3–4.
- **Known judgment call:** unlike the original real-free-agents plan (which authored new content
  from scratch), this plan never creates a "bootstrap" intermediate — Tasks 1–2 operate on the
  real, already-populated file from the start, since there's no red/green Java test cycle to
  unblock here (no Java code changes at all). The "test" that gates progress is schema validation
  (Task 1/3/4) plus the name-set stability check and a full existing-suite rerun (Task 5–6).
