# Catarinense Data Realism Pass — Design

**Date:** 2026-07-10
**Status:** Approved

## Goal

Give the 8 Catarinense-only clubs real, current rosters with authored attributes (matching
the quality bar already set for Criciúma and the 20 Série B clubs), and correct player names
across the whole 28-club pool to how each real player is actually publicly known.

This is Group 2 of the 10-item enhancement roadmap
(`~/.claude/plans/imperative-strolling-parasol.md`), covering the user's items 1 and 2:
"Update Catarinense player rosters to real players with real attributes, considering
division/current situation/history" and "Update player names to their real known-as public
names across all clubs."

## Background

- The seed lives in `backend/src/main/resources/seed/clubs.json`: a `clubPool` (28 clubs)
  and two competitions (Campeonato Catarinense, Série B).
- 20 clubs (Criciúma, Avaí, and the 18 other real 2026 Série B clubs, 449 players total)
  already carry hand-authored `SeedAttributes` blocks, from the earlier Série B seed project
  (`docs/superpowers/specs/2026-07-07-real-serie-b-2026-seed-design.md`).
- The remaining 8 clubs are Catarinense-only: FIG (Figueirense), CHA (Chapecoense), BRU
  (Brusque), MAR (Marcílio Dias), BAR (Barra), CON (Concórdia), HER (Hercílio Luz), CAM
  (Camboriú). Each has 14-15 players today with plausible-but-unverified names and **no**
  `attributes` block — they fall back to `AttributeDeriver` at career creation
  (`SaveGameService.java`).
- The `formation`/`mentality`/`pressing`/`marking` tactical-identity fields on every club
  (added by the AI-tactics project) already cover all 28 clubs and are untouched by this work.
- The user's own example of the naming problem: Criciúma's roster has a player named simply
  `"Diego"` (`clubs.json`), who publicly goes by a fuller real name (e.g. "Diego Gonçalves")
  — the correction direction is toward whatever a given player is actually known as, not a
  uniform full-name-always or nickname-always rule.

## Part A — Real rosters for the 8 Catarinense-only clubs

Each of the 8 clubs' roster is replaced with a real, researched current squad:

- **Squad size:** ~18-23 players, 2-3 GKs, full DEF/MID/ATT position coverage — matching the
  Série B batch's target size, for consistency and enough bench depth for rotation/injuries
  under the recently-raised 40-player squad cap.
- **Per player:** the six anchor values (`pace, finishing, passing, tackling, goalkeeping,
  stamina`), the full 22-value `SeedAttributes` block (Técnico 6, Mental 8, Físico 5, Goleiro
  3 — same shape as every other authored club), `position`, `age`, `nationality`.
- **Calibration:** these clubs sit a level below the Série B batch — they play only the state
  championship, not a national Série B campaign. Research each club's actual current division
  (Série C/D or state-only) to inform the calibration, and land authored ratings a tier below
  the Série B band (Série B starters ~60-75 in their strong suits; these clubs' starters
  should sit lower, with the exact gap judged per club from its real division).
- **Data freshness caveat:** same as the Série B project — rosters target the current 2026
  season as of research time; for smaller, lower-visibility clubs some names may reflect an
  earlier-2026 squad rather than the latest transfer window.

## Part B — "Known as" name correction, all 28 clubs (~563 players)

A second, separate pass over every pool club's player `name` field, correcting each to the
real player's actual public "known as" name. Name-only — no attribute, position, age, or
nationality changes in this part.

Runs across the full 28-club pool, **including** the 8 clubs from Part A: Part A's own
research could land on an imperfect public name while focused on attributes/roster
construction, so Part B acts as a second, dedicated verification/correction pass over names
specifically, everywhere.

**Sequencing:** Part A merges into `clubs.json` first. Part B's research and corrections run
against the **post-Part-A** roster, so the 8 clubs get checked against their newly-authored
real names, not the old placeholder ones.

## Execution approach

Same parallel-research-batch pattern as the Série B project (proven in this codebase):

- **Part A:** one research agent per club (8 agents), each researching and authoring one
  club's full roster fragment — same JSON shape as the Série B batch fragments.
- **Part B:** batched agents, a handful of clubs each (roughly 4-5 clubs/agent, ~6-7 agents
  for the 28 clubs), each verifying/correcting names only. Output is a simple mapping per
  club of `{old_name: new_name}`, listing only players whose name actually needs correcting
  (not a full re-emit of every player).
- A scratchpad Python validator checks every Part A fragment before merge: JSON validity,
  `SeedAttributes` field completeness (all 22 keys, ints), attribute/anchor ranges (1-99),
  calibration sanity (squad averages sit below the Série B band), position coverage (2-3 GK,
  sensible DEF/MID/ATT spread), and squad size (18-23).
- A merge script applies Part A fragments (full roster replacement per club) and then Part B's
  name corrections (targeted field updates) into `clubs.json`.
- Work happens directly on `main`, subagent-driven, local commits only, no push without
  asking — per project convention. No scripts from the earlier Série B pass survive (they
  lived in the scratchpad, which is ephemeral) — this pass writes its own tooling fresh.

## Required test changes

Found during exploration — these are not guesses, the current test file was read directly.
`SeedLoaderTest.java`:

- `catarinenseOnlyClubsKeepDerivedAttributes` (currently asserts the 8 clubs have **null**
  attributes) must be replaced with the opposite assertion — every pool club's players now
  carry authored attributes, none derived.
- `everySerieBPlayerCarriesAuthoredAttributes` (currently filtered to the 20 Série B
  shortNames) should be generalized to assert **every** pool club's players carry authored
  attributes, since after this work there are no more derived-attribute players left in the
  seed pool at all. (`AttributeDeriver` itself stays — it's still used at runtime for free
  agents and newgens, just no longer needed for any seeded club.)
- `serieBSquadsHaveRealisticSizeAndGoalkeeperCover` (currently Série-B-scoped) needs a
  parallel size/GK-coverage assertion for the 8 Catarinense clubs, now that they're 18-23
  players with 2-3 GKs too. Could be generalized to cover all 28 clubs in one test rather than
  keeping two near-duplicate size assertions.
- `loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma`'s `hasSizeGreaterThanOrEqualTo(14)`
  floor still holds (18-23 > 14) — no change needed.
- Three integration tests reference these 8 clubs by shortName or hardcoded club name
  (`FixtureRepositoryIT`, `SaveGameServiceIT`, `AiListingEngineIT`) but assert on club-level
  data, not specific player names — expected to be unaffected by Part A/B, but must be
  re-confirmed once the real diffs exist rather than assumed safe.

## Out of scope

- Tactical identity (`formation`/`mentality`/`pressing`/`marking`) for any club — already
  researched and correct from the AI-tactics project.
- Club identity fields (nickname, colors, full/common name) — already correct.
- Any attribute changes as part of Part B — name field only.
- Any code/schema changes — `SeedAttributes` and the seed pipeline already support everything
  needed; this is a pure data-authoring pass, same as the Série B project before it.

## Testing

- `SeedLoaderTest`: updated per "Required test changes" above; seed parses; pool still has 28
  clubs; every club's players carry non-null authored `attributes`; all 28 clubs (not just
  Série B) have realistic size (18-23) and GK coverage (2-3).
- `SaveGameServiceIT`: career creation still succeeds with the updated seed; club/player counts
  match; a sampled Catarinense-only-club player has authored (not derived) attributes.
- Full backend suite (`JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home
  ./gradlew test`) green before completion.
