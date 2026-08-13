# Série C 2026 — Design Spec

**Date:** 2026-08-13
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the competition format engine (2026-08-05), Série A and background competitions (2026-08-08).
**Research input:** [`docs/research/2026-08-13-serie-c-2026-field-and-format.md`](../../research/2026-08-13-serie-c-2026-field-and-format.md)

---

## 1. Why this exists

The roadmap's goal is the Brazilian pyramid: Criciúma rising and falling. Série A gave the pyramid a
tier above Série B. This slice gives it a tier **below**, so that relegation has somewhere to go.

It is deliberately a seed slice, not a mechanics slice. Nothing here moves a club between divisions.

### 1.1 Why Série C comes before promotion/relegation

The format-engine spec listed promotion/relegation before Série A, and the Série A spec corrected
that: promotion needs a destination division. The same argument applies downwards. Building
promotion/relegation against a two-tier world would settle the ladder's shape now and force it to be
revisited the moment a third tier lands — and relegation out of Série B would have nowhere to send
anyone.

Bundling the two would also make the mechanic hostage to the seed: ~370 hand-placed players have to
be right before a single membership swap could be verified. Two specs, Série C first.

---

## 2. Locked decisions

Settled during brainstorming; not open in this slice.

1. **Three tiers, with Série C as the new floor.** No relegation out of Série C — Série D does not
   exist. The real regulation relegates 2 clubs (Art. 42); here there is nowhere to send them.
2. **Generic two-phase format, no group support.** Single round-robin then a seeded knockout —
   the shape the format engine already runs for the Catarinense. Real *quadrangulares* are not built.
3. **Real 2026 field**, verified against the CBF's own regulation, not recalled.
4. **Real rosters, derived attributes.** Player names, positions, ages and nationalities are real;
   the numbers are generated from a per-club strength target and a positional profile.
5. **Série C runs in the background**, exactly like Série A. No special presentation.
6. **A new `NORTE` climate zone**, rather than stretching `NORDESTE_LITORAL` any further.

### 2.1 Where decision 4 departs from precedent, and why

Format-engine locked decision #2 reads: *"Divisions, when they arrive, are fully simulated with real
clubs and real hand-authored squads — not abstracted tables and not generated rosters."* Série A
honoured that literally: 23 real players per club, each hand-tuned.

This slice keeps the clubs and the rosters real and mechanises only the numbers. The reason is
source quality, not effort alone: third-tier roster data is thinner and staler than Série A's, so
hand-tuning 370 players would mean hand-tuning a large fraction of them from judgement while
presenting the result as researched. Derived attributes make the provenance honest — the names are
sourced, the numbers are calibrated — and they guarantee the tier lands on its strength band by
construction instead of by 370 individual guesses.

What is lost is per-player texture at a tier the player currently cannot reach. When
promotion/relegation lands and Série C becomes reachable, its squads can be hand-tuned in place
without touching any of this slice's structure.

---

## 3. The competition

### 3.1 Shape

20 clubs. Four phases, expressed entirely as seed data:

```json
[{"name": "Primeira Fase",    "kind": "ROUND_ROBIN", "legs": 1, "rounds": 19, "qualifiers": 8},
 {"name": "Quartas de final", "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null},
 {"name": "Semifinal",        "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null},
 {"name": "Final",            "kind": "KNOCKOUT",    "legs": 2, "rounds": 2,  "qualifiers": null}]
```

This is byte-for-byte the Catarinense's phase block with `rounds` changed from 11 to 19. **No
production code changes for the format.** `PhaseResolver`, `FinalClassification`, extra time,
penalties, per-phase standings and the phase-aware competition page all apply unchanged.

| | value |
|---|---|
| Match-units | 25 (19 + 2 + 2 + 2) |
| Fixtures per season | 204 (190 first phase + 8 QF + 4 SF + 2 final) |
| Real competition, for comparison | 27 matchdays, 216 matches |

### 3.2 The deliberate divergence from the real format

Recorded here so a later reader does not file it as a bug.

The real 2ª Fase is **two four-club quadrangulares** (REC Art. 17): the top 8 split into Grupo B
(1st, 4th, 5th, 8th) and Grupo C (2nd, 3rd, 6th, 7th), double round-robin, 6 rounds. The champion is
then decided by a two-legged final between the group winners (Art. 21). Group stages are explicitly
out of scope for the format engine — the format-engine spec names `group_count` and cross-group
scheduling as not built.

A seeded knockout over the same 8 clubs preserves what actually matters:

- **Promotion count is identical.** The regulation promotes both group winners *and* both runners-up
  — 4 clubs, decided in the quadrangulares, not in the final (Art. 5º). Under a knockout, the 4
  semifinalists are the best 4 of the qualifying 8. Same count, same meaning. This is what makes the
  approximation safe for the promotion/relegation slice that follows.
- **Seeding logic is preserved.** 1v8 / 2v7 / 3v6 / 4v5 ranks by first-phase position, as the real
  group allocation does.
- **The phase-reset rule is moot.** Art. 12's "points reset to zero at each phase" is the trap a
  group second phase would spring; a knockout has no table.

What is genuinely lost: each qualifier plays 6 second-phase matches in reality and 2–6 here, and the
champion can differ from the club with the best second-phase campaign.

### 3.3 Calendar

Série C is a background competition: the managed club is not a member, so
`SeasonCalendarGenerator` gives it an independent date sequence across the same season window, per
the background dating built for Série A. **The managed season stays 270 days.** No calendar
mechanism changes.

---

## 4. Seed data

### 4.1 Clubs — 50 → 67

Three are already in the pool and need only a `competition_club` row: **Brusque** `BRU`,
**Figueirense** `FIG`, **Barra** `BAR`. Seventeen are new. The full field, with legal names,
nicknames, stadiums, cities, states and capacities, is in the research note §1 and §4 and is
transcribed into the seed as-is.

### 4.2 shortName collisions

Six new clubs collide with existing codes. This bit the project once already (Camboriú vs
Atlético-MG both wanting `CAM`), so the codes are settled here rather than during authoring:

| New club | Obvious | Collides with | Use |
|---|---|---|---|
| Confiança | `CON` | Concórdia | `CFZ` |
| Maringá | `MAR` | Marcílio Dias | `MGA` |
| Maranhão | `MAR` | Marcílio Dias | `MRN` |
| Botafogo-PB | `BOT` | Botafogo-SP | `BPB` |
| Santa Cruz | `SAN`/`SCA` | Santos, Santa Catarina | `SCR` |
| Inter de Limeira | `INT` | Internacional | `ILI` |

The remaining eleven take their obvious codes, all verified free: `FER`, `AMA`, `VRE`, `PAY`, `CAX`,
`GUA`, `FLO`, `YPI`, `ITU`, `ANA`, `ITB`.

### 4.3 Corrections to existing data

The research turned up two errors in the seeded `BAR` entry, both inherited from the Catarinense
seed. Fixed in this slice because this is the slice that makes Barra a national-tier club:

- `stadiumCapacity` 3000 → **5500**. Arena Barra FC opened in January 2025 at 5,500.
- `name` "Associação Desportiva Barra" → **"Barra Futebol Clube"**, the name the CBF regulation uses.

`city` stays "Balneário Camboriú" — sources genuinely differ on the ground's municipality, and the
seeded value is defensible. Noted so nobody "fixes" it in the wrong direction.

---

## 5. Squads

### 5.1 Volume and depth

~21–22 players per new club, ~370 total. The pool goes 1,133 → ~1,503 players.

### 5.2 Calibration

The ladder's existing rhythm, measured as the mean over clubs of each club's top-11 player attribute
average:

| Tier | Clubs | Mean | Range |
|---|---|---|---|
| Série A | 20 | 58.0 | 53.9 – 62.6 |
| Série B | 20 | 48.4 | 44.3 – 55.3 |
| Série C | 20 | **~41 (target)** | ~36 – 45 |

The three already-seeded clubs are **not re-authored** — their squads are real and hand-tuned, and
they land at Brusque 45.1, Figueirense 43.8, Barra 42.8. To put the tier mean on 41 with those three
fixed, the seventeen new clubs need a mean of about **40.5**.

Per-club strength targets are set from 2025 finishing position, which the regulation's Anexo B gives
exhaustively:

- **Relegated from Série B 2025** (Ferroviária, Amazonas, Volta Redonda, Paysandu) — top of the band.
- **Retained, 5th–16th in Série C 2025** — the middle.
- **Promoted from Série D 2025** (Barra, Santa Cruz, Inter de Limeira, Maranhão) — bottom.

**Known exception:** Barra was promoted from Série D yet sits at 42.8, above several retained clubs.
Its squad is real and hand-authored; re-authoring it downwards to satisfy a rule of thumb would be
bending real data to fit a heuristic. Accepted as-is.

### 5.2a The bands overlap at the edges, deliberately

Série C's strongest club is stronger than Série B's weakest: Brusque 45.1 against Londrina 44.3.
This is correct and should not be calibrated away — a strong third-tier side really is better than a
struggling second-tier one, and that overlap is what makes promotion and relegation plausible rather
than a cliff.

The consequence for testing is that "every Série C club is weaker than every Série B club" is a
**false** claim and must not be asserted. Separation is a property of the tier *means*, not of every
pair of clubs. See §8.1.

### 5.3 Determinism

Attribute derivation runs at authoring time and is committed as literal JSON, exactly like every
other seeded club. Nothing is generated at runtime, so career creation stays deterministic and no
new RNG enters the engine.

---

## 6. Climate

Série C brings **AM** (Manaus) and **MA** (São Luís) into the world, plus **PB** and **SE**.
`ClimateZone` has four constants and no honest value for the first two.

- **New constant `NORTE`** — "Norte equatorial": hot, humid, heavy rain December–May, low wind.
  Its 12-month profile joins the others in `seed/climate.json`, which is already data-driven.
- **AM and MA map to `NORTE`.**
- **PA is re-zoned from `NORDESTE_LITORAL` to `NORTE`.** Remo (Belém) is currently stretched onto the
  coastal-northeastern profile because nothing better existed. This fixes it.
- **PB and SE map to `NORDESTE_LITORAL`**, which is correct for João Pessoa and Aracaju.

Re-zoning Remo changes match-day weather for one existing Série A club, and therefore its weather-
dependent match outcomes. That is a deliberate correction, not a regression, but it must be called
out in the plan so a shifted assertion is not mistaken for a bug.

---

## 7. Frontend

Nothing. Série C appears in the competition switcher like any other competition, with a table for
the round-robin phase and a grouped list of ties for the knockout phases — all existing behaviour
from the format engine and background competition slices. The player infers it is not theirs because
Criciúma is not in it.

---

## 8. Testing

### 8.1 New coverage

- **Field and format:** the seed loads 20 Série C clubs with the four-phase block; 19 first-phase
  rounds; 8 qualifiers; 204 fixtures generated.
- **A full background Série C season:** every fixture played, a 20-row first-phase table with each
  club on 19 played, zero-sum goals, and a final classification whose top four are exactly the
  clubs that reached the semifinals — the four the promotion slice will later promote.
- **shortName uniqueness across the whole pool** — a test that would have caught the `CAM` collision.
- **Calibration:** the tier's mean top-11 lands in a band around 41, and Série C's mean sits a stated
  margin below Série B's. Per §5.2a the assertion is on means; a club-by-club domination test would
  be false today and must not be written.
- **Climate:** `NORTE` has a complete 12-month profile, and every seeded club's state resolves to a
  zone — no club falls through to a default.

### 8.2 Existing tests that must move

The five full-seed opt-outs (`@TestPropertySource(properties = "carvoeiro.seed.competitions=")`)
are the tests that pay for this slice:

| Test | Change |
|---|---|
| `ClubGeographyIT` | 50 → 67 clubs |
| `SaveGameServiceIT` | 50 → 67 clubs, 3 → 4 competitions |
| `CareerCreationCalendarIT` | 3 → 4 competitions |
| `TwoCompetitionCareerIT` | 3 → 4 competitions |
| `ThreeCompetitionSeasonIT` | now four competitions — extend and rename |

`MatchCalibrationTest` is pinned to CRI/SCA by name rather than pool extremes, so a weaker tier
cannot break it the way Série A did. It should stay green untouched; if it does not, that is a real
finding.

### 8.3 Runtime

Série C is **not** in the test profile's retained competitions
(`carvoeiro.seed.competitions=Campeonato Catarinense,Série B`), so the ~100 test careers do not pay
for it at all. Only the five opt-outs above build a 67-club world. The suite baseline is ~1h; this
slice should not move it materially, and that expectation is worth checking rather than assuming.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Stadium capacities are secondary-sourced; the CBF publishes none, and the two Wikipedias disagree on ten | Research note §5.1 tabulates both figures and states which was taken; capacity drives attendance only |
| Amazonas and Paysandu are seeded at their regular grounds (10,000 / 16,200), understating real draw | Deliberate and recorded; big fixtures moving to Arena da Amazônia / Mangueirão is not modelled |
| Re-zoning Remo to `NORTE` shifts an existing club's weather and match outcomes | Called out in the plan; expect and verify the shift rather than treat it as a regression |
| ~370 more players at career creation | The bulk-persist path already handles 1,133; measure creation time before and after |
| Derived attributes produce flat, samey squads | Positional profiles plus per-club targets; the calibration test asserts spread, not just the mean |
| Floresta shares Estádio Presidente Vargas with Ceará | The pool already shares the Maracanã between Flamengo and Fluminense; no constraint to relax |

---

## 10. What comes after

1. **Promotion and relegation** — the next spec. Movement at rollover across all three tiers:
   4 up / 4 down between A and B, 4 up from C to B, and 4 down from B to C. No relegation out of
   Série C, which is the floor.
2. **Other state championships**, then **Série D**, which needs real group support in the phase model.

---

## 11. Sources

All field and format claims trace to the research note, which cites the CBF regulation by article:

- [Série C 2026 field and format research](../../research/2026-08-13-serie-c-2026-field-and-format.md)
- CBF, *Regulamento Específico da Competição — Brasileiro Série C 2026* (signed 2026-02-23):
  <https://stcbfsiteprdimgbrs.blob.core.windows.net/img-site/cdn/REC_Brasileiro_Serie_C_2026_f894bdfed3.pdf>
