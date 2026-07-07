# Real 2026 Série B Seed — Design

**Date:** 2026-07-07
**Status:** Approved

## Goal

Replace the seed's part-fictional Série B lineup with the real 20 clubs of the 2026
Brasileirão Série B, and give every Série B club a real current-season roster where each
player carries a hand-authored full 28-attribute profile (the same approach already used
for Criciúma via `SeedAttributes`).

## Background

- The seed lives in `backend/src/main/resources/seed/clubs.json`: a `clubPool`
  (25 clubs) and two competitions (Campeonato Catarinense, Série B) that reference pool
  clubs by `shortName`.
- Career creation (`SaveGameService.createCareer`) instantiates **every** pool club with
  its players, then wires competition memberships. Pool clubs in no competition still get
  created — orphans are dead weight in every save.
- Only Criciúma (the managed club, 27 players) has hand-authored `attributes` blocks;
  all other players fall back to `AttributeDeriver`.
- Real 2026 Série B lineup (source: CNN Brasil / Wikipédia): América-MG, Athletic,
  Atlético-GO, Avaí, Botafogo-SP, Ceará, CRB, Criciúma, Cuiabá, Fortaleza, Goiás,
  Juventude, Londrina, Náutico, Novorizontino, Operário-PR, Ponte Preta, São Bernardo,
  Sport, Vila Nova.

## Decisions (user-approved)

1. **All 20 Série B clubs** get real rosters with authored attribute blocks (not just the
   new clubs). Criciúma's existing 27 authored players stay as-is.
2. **Orphaned clubs are removed** from the pool: Coritiba, Paysandu, Guarani, Mirassol,
   Remo (they leave Série B and are in no other competition).
3. **Fuller squads:** ~18–23 players per club (2–3 GKs, all positions covered).

## Data changes — `clubs.json`

### Série B membership (`memberShortNames`)

New list (20): `CRI, AVA, GOI, NOV, SPT, CEA, VIL, OPE, CRB, PPT, BOT, FOR, AME, ATH,
ATG, CUI, JUV, LON, NAU, SBE`.

- Removed from Série B: FIG, CHA, BRU, MAR (remain in the Catarinense), COR, PAY, GUA,
  MIR, REM (removed from pool entirely).
- FOR (Fortaleza) already exists in the pool but was not a Série B member; it joins.
- Catarinense membership is untouched.

### Club pool

- Remove: COR, PAY, GUA, MIR, REM.
- Add 8 new clubs with real identity data (full name, commonName, nickname, colors):

| shortName | Club |
|---|---|
| AME | América-MG |
| ATH | Athletic (São João del-Rei) |
| ATG | Atlético-GO |
| CUI | Cuiabá |
| JUV | Juventude |
| LON | Londrina |
| NAU | Náutico |
| SBE | São Bernardo |

- Final pool: 28 clubs = 20 Série B + 8 Catarinense-only (BAR, CON, HER, CAM, FIG, CHA,
  BRU, MAR), with CRI and AVA in both competitions.

### Rosters

For the 19 non-Criciúma Série B clubs: replace the squad with the club's real 2026
roster (~18–23 players), researched via web. Every player gets:

- The six anchor values (`pace, finishing, passing, tackling, goalkeeping, stamina`).
- A full 22-value `attributes` block (`SeedAttributes` shape — Técnico 6, Mental 8,
  Físico 5, Goleiro 3).
- `position` (one of the app's `Position` enum values), `age`, `nationality`.

**Calibration:** the existing Criciúma squad is the reference scale. Série B starters
land ~60–75 in their strong suits; bench/young players lower. Outfielders get low
Goleiro values (~10–30); GKs get low technical/finishing values, mirroring the CRI
pattern.

Catarinense-only clubs keep their current squads with derived attributes.

**Data freshness caveat:** rosters target the 2026 season as of July 2026; for smaller
clubs some names may reflect early-2026 squads rather than post-June transfers.

## Code changes (small)

- `SaveGameService`: update the stale comment "currently Criciúma only" near the
  authored-attributes branch.
- Tests: update any assertions on pool size, club counts, or Série B membership in
  `SeedLoaderTest` and `SaveGameServiceIT`; add/extend coverage that authored attribute
  blocks load for a sample of the new clubs.
- No schema, loader, or entity changes — `SeedAttributes` and the seed pipeline already
  support everything.

## Execution approach

Parallel research agents, one per club: each web-researches the club's current 2026
squad and authors its JSON block. A Python validation script (scratchpad) checks, before
merging into the seed:

- JSON validity and `SeedAttributes` field completeness (all 22 keys, ints).
- Attribute/anchor ranges (1–99) and calibration sanity (squad averages within Série B
  band).
- Position coverage per club (2–3 GK, sensible DEF/MID/ATT spread) and squad size 18–23.
- `shortName` uniqueness and membership integrity (every `memberShortNames` entry
  resolves to a pool club; no orphans in the pool).

Then the full backend test suite runs. Work happens directly on `main` (no branches),
and the user runs `git commit` themselves.

## Testing

- `SeedLoaderTest`: seed parses; 28 pool clubs; Série B has the 20 real members; every
  Série B club's players carry non-null `attributes`.
- `SaveGameServiceIT`: career creation succeeds with the new seed; club/player counts
  match; a sampled new-club player has authored (not derived) attributes.
- Full `./mvnw test` (or project equivalent) green before completion.
