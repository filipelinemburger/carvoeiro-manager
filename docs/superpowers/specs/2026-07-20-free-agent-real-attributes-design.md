# Free-Agent Real Attributes — Design

## Context

`backend/src/main/resources/seed/free-agents.json` holds 179 real, currently-unattached South
American players (authored in the [Real Free Agents](2026-07-17-real-free-agents-design.md)
cycle). The names, nationalities, and rough positions are real, but the attribute values were
authored under time pressure and don't reliably reflect each player's actual career level —
some journeymen are rated too high, some recognizable ex-elite names are capped well below what
their real careers justify. This cycle re-researches and re-authors attributes only, so the
free-agent pool's ratings track real-world characteristics the same way the club rosters do.

## Scope

- **In scope:** for all 179 free agents — the six anchor stats (`pace`, `finishing`, `passing`,
  `tackling`, `goalkeeping`, `stamina`) and the 22-key `attributes` block. `age` and `position`
  may also be corrected when research contradicts the current entry (attributes are derived from
  both). `nationality` may be corrected if research shows it's wrong, but no country-mix
  rebalancing is done — the target composition from the previous cycle stands.
- **Out of scope:** adding or removing players. The pool stays fixed at the same 179 names, even
  for a player research shows has since signed with a club — that reconciliation is a future
  concern, not this cycle's. No Java/backend code changes; no test-count changes (still 179). No
  change to `ContractValuation`, the signing flow, or nationality display in the UI/API.

## Calibration rubric

The rubric that makes 8 independently-researched batches land on one consistent scale. It's
written once, given to every research agent verbatim, and referenced during the consistency
review.

**Tiers** (apply to *signature* technical/mental attributes — the ones central to the player's
role and real quality, e.g. `finishing`/`chuteLonge` for a striker, `bolaParada`/`visao` for a
passing midfielder):

- **S — ex-elite** (e.g. Coutinho, Cavani, Falcao, Valencia, Isla, Luiz Gustavo): signature
  attributes 80–92. These may exceed today's best club-roster values (current ceiling: Otero
  `bolaParada` 88, Fellipe Mateus `visao` 83, anchor tops 80–84) — realism-first, not capped to
  match the league.
- **A — solid top-flight career / capped internationals:** signature 68–80.
- **B — top-flight squad player / strong second-division regular:** signature 52–68.
- **C — journeyman / lower-division:** signature 38–55.
- **D — unproven youngster:** overall 28–45; physicals may be high, mentals stay low.

**Cross-cutting rules:**

- **Physicals decline with age**, independent of tier: `pace`, `aceleracao`, `stamina`,
  `resistencia`, `agilidade` fall off from age 30, sharply after 34. A 39-year-old Tier-S striker
  can keep `chuteLonge`/finishing near his tier ceiling while `pace`/`aceleracao` sit in the
  30s–40s.
- **Mentals track experience, not tier:** `decisao`, `compostura`, `concentracao`, `lideranca`
  scale with age/career length more than with technical level — a veteran Tier-C defender can
  still have high `lideranca`.
- **Existing hard constraints carry over unchanged:** all attribute/anchor values are ints 1–99;
  GK entries have `finishing ≤ 40`; outfielders have `goalkeeping ≤ 40` and keep
  `posicionamentoGk`/`saidaDeGol`/`jogoComOsPes` in single digits to low teens; `SeedPlayer` key
  order and the 22-key attribute order are preserved exactly (per the previous cycle's spec).
- **Anchors agree with the block:** each of the 6 anchors must be consistent with its related
  attribute-block entries (e.g. `pace` anchor tracks `aceleracao`/`agilidade`; `passing` tracks
  `visao`/`chuteLonge` for a passing profile).

## Process

Eight research batches, one per country in the pool (Brasil 39 — folding in the single Portugal
entry, Sérgio Oliveira — Colômbia 24, Equador 22, Chile 20, Paraguai 20, Argentina 18, Uruguai
18, Venezuela 17). Each batch is handed the rubric and its slice of the current
`free-agents.json`, researches each player's real career (trajectory, peak level, last
club/division, playing style, verified age/position), assigns a tier, and rewrites that player's
anchors + attribute block in place. Where public data is thin for an obscure player, attributes
are derived from verifiable career facts (divisions played, clubs, appearances, goals) rather
than invented specifics — same approach as the original club-roster and free-agent research.

After all batches complete:

1. **Script validation**, extending the previous cycle's `.seed-work` validator: schema, exact
   key order, 1–99 ranges, GK/outfielder constraints — plus a new check that the file still
   contains exactly the same 179 names as before the pass (no adds, removes, or renames).
2. **Cross-batch consistency review:** one pass that lists every Tier-S and Tier-A player across
   all 8 countries side by side with their signature values, to catch a batch that drifted off
   the shared scale, and corrects any outliers before finalizing.

`.seed-work/` tooling is scratch — never committed, cleaned up at the end, same as last cycle.

## Balance mechanism

No explicit power cap is needed. `ContractValuation.wageDemand(overall, age)` already prices
wage demands from overall rating, so a realistically-rated Coutinho commands a wage to match —
signing a star free agent becomes a budget decision the user has to earn, not a free upgrade.
This cycle doesn't touch `ContractValuation` or the signing flow.

## Testing

- Existing suite must stay green with no count changes: `SeedLoaderTest`'s free-agent
  assertions, `FreeAgentListIT`, and `SaveGameServiceIT` all currently assume 179 free agents at
  career creation — that number doesn't change.
- Add a name-set stability check to the seed-validation test (or the `.seed-work` validator) if
  one doesn't already exist: the set of free-agent names after this cycle must equal the set
  before it.

## Out of scope for this cycle

- Adding, removing, or replacing free agents (including players who've since signed elsewhere).
- Nationality display anywhere in the API or UI.
- Any change to `ContractValuation`, the signing flow, or transfer-window gating.
- Rebalancing the per-country composition targets.
