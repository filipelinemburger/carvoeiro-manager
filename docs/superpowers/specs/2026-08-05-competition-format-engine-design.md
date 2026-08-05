# Competition Format Engine — Group Phases, Knockouts, Extra Time & Penalties — Design Spec

**Date:** 2026-08-05
**Status:** Approved (design); ready for implementation plan.
**Builds on:** Phase 2D (multiple competitions & dated event calendar), the real Série B seed,
and the weather/stadium-attendance slice.

---

## 1. Why this exists

The long-term goal is the **Brazilian pyramid**: Criciúma rising to Série A and falling to
Série C, with the other divisions and the state championships alive around it.

That goal cannot start with content, because the engine cannot express the formats the content
needs. `FixtureScheduler` has exactly one method — `doubleRoundRobin` — and nothing in the
codebase models a group stage, a knockout, or a two-phase competition. `"CUP"` appears only in a
frontend test fixture; `Competition.type` is a free-form string that no code branches on.

| Competition | Real format | Engine supports today |
|---|---|---|
| Série A | 20 clubs, double round-robin, 38 rounds | yes |
| Série B | 20 clubs, double round-robin, 38 rounds | yes |
| Série C | single round-robin → quadrangulars → final | no |
| Série D | 64 clubs, 16 groups of 4 → knockout | no |
| State championships | group/first phase → semis → final (nearly all) | no |

Série A is the only requested competition that drops into the existing machinery unchanged.
Everything else needs a format engine first. **This slice builds that engine and proves it on a
real competition.** No new divisions are added here.

---

## 2. Scope decisions

These were settled during brainstorming and are locked for this slice.

1. **Goal is the career arc** — promotion and relegation, Criciúma rising and falling. Mechanics
   before content.
2. **Divisions, when they arrive, are fully simulated** with real clubs and real hand-authored
   squads — not abstracted tables and not generated rosters. This shapes later slices, not this one.
3. **This slice is the format engine**, not a new division. Group/first phases, knockouts, and
   everything they break.
4. **It is proven on the Campeonato Catarinense**, converted from its current 18-round double
   round-robin to a real two-phase shape. Not landed dormant, and not proven on a throwaway cup.
5. **Level knockout ties are resolved by extra time, then penalties** — the full-realism option,
   not a straight shootout and not a seeding rule.
6. **The knockout is shown as a phase-aware competition page** — the existing table for the
   round-robin phase, a grouped list of ties for the knockout phase. Not a bracket view.
7. **The phase model is data-driven** (a `competition_phase` table), not format strategy classes
   and not phases-as-sub-competitions.
8. **The Catarinense runs a generic two-phase format**, not the idiosyncratic real 2026 rules.
   See §3.

### 2.1 The Catarinense fidelity decision

Research into the real 2026 Campeonato Catarinense found a format that is unusually specific to
that season:

- 12 clubs in two groups of six.
- First phase is **cross-group only** — each club plays the six clubs in the *other* group once,
  three home and three away. Not a round-robin.
- Top four of each group reach the quarterfinals, and the quarterfinal pairs clubs from the
  **same** group (1×4, 2×3) — ties that by construction never occurred in the first phase.
- Bottom two of each group play a "quadrangular da morte"; only its winner survives and **three
  clubs are relegated**, shrinking the division to 10 for 2027.

Three of those need vocabulary beyond a general phase model: cross-group scheduling, same-group
knockout seeding, and a relegation phase running in parallel with the quarterfinals.

**The relegation half cannot be built at all**: the game has no Catarinense Série B, so the
quadrangular would decide a fate the data model cannot express.

**Decision: run a generic two-phase format over the real 12-club field.** The FCF changes the
Catarinense format most years, so the 2026 quirks would be built for one competition and then sit
unused through Série C, Série D and every other state. The general shape — single round-robin,
then a seeded knockout — is what Série C and most state championships actually use.

---

## 3. What this slice delivers

**Campeonato Catarinense, 2026 field, generic two-phase format:**

| Phase | Kind | Shape | Match-units |
|---|---|---|---|
| Primeira Fase | `ROUND_ROBIN` | 12 clubs, single round-robin | 11 |
| Quartas de final | `KNOCKOUT` | top 8 seeded 1×8…4×5, two legs | 2 |
| Semifinal | `KNOCKOUT` | two legs | 2 |
| Final | `KNOCKOUT` | two legs | 2 |

Total 17 match-units, against 18 today — season length is effectively unchanged, so the
interleave with Série B and the calendar's overall shape are not disturbed.

**Out of scope for this slice:** Série A, C and D; any other state championship; promotion and
relegation between divisions; group stages (`group_count` and cross-group scheduling); bracket
visualisation; away-goals rules; a Catarinense second division.

---

## 4. Architecture

### 4.1 The forced constraint

`fixtures.home_club_id` and `away_club_id` are `NOT NULL`, and
`SeasonCalendarGenerator` materializes **every** fixture and **every** dated `season_event` at
career creation and at rollover — that is what makes a season deterministic and testable.

A knockout cannot work that way: the quarterfinal participants are unknown until the first phase
ends. Placeholder fixtures are impossible under the `NOT NULL` constraint. **Late materialization
is forced, not chosen.**

### 4.2 Phase model

A competition owns an ordered list of phases. Each phase declares its kind and shape; the phase
row is the extension point that later slices widen for Série C, Série D and other states.

Phase status is **derived, not stored** — a phase is complete when all of its `season_event` rows
are processed. There is no second source of truth to drift out of sync.

### 4.3 Scheduling

`SeasonCalendarGenerator` walks each competition's phases in ordinal order:

- A `ROUND_ROBIN` phase **materializes** its fixtures immediately, exactly as today.
- A `KNOCKOUT` phase **reserves** its match-units: dated `season_event` rows carrying
  `(competition, phase, round)` and no fixtures at all.

A phase's length is known from its shape even when its participants are not, so every match date
in the season is still fixed at generation. The existing cross-competition interleave and the
5-day `MATCH_INTERVAL_DAYS` spacing are untouched, and differing phase lengths are handled by the
interleave logic that already copes with competitions of different lengths.

`FixtureScheduler` gains `singleRoundRobin(n)` and `knockoutPairings(seeds)` alongside
`doubleRoundRobin(n)`.

### 4.4 `PhaseResolver`

Runs from `SeasonAdvanceService` after each MATCH event is processed. When the event just
processed was the last unprocessed event of its phase, it materializes the next phase onto the
already-reserved dates.

- **Round-robin → knockout:** rank the completed phase's standings, take the top `qualifiers`,
  seed 1×8 / 2×7 / 3×6 / 4×5, create one `tie_id` per tie and two fixtures per tie, with the
  **higher seed hosting the second leg**.
- **Knockout → knockout:** carry the winners forward, each keeping its original first-phase seed,
  and re-pair best-remaining against worst-remaining (of four semifinalists, the best seed plays
  the worst and the other two meet). The higher seed again hosts the second leg.

**Seeding tie-breaks** come from the existing `StandingsCalculator` ordering — points, goal
difference, goals for, and whatever else it already applies. The resolver does not introduce a
tie-break rule of its own, so seeding agrees with the table the player sees.

**Determinism:** pairings are a pure function of standings, which are a pure function of results.
No RNG, no wall-clock.

**Idempotency:** the resolver materializes only when the target phase has no fixtures yet, so a
repeated advance or a replayed event cannot duplicate a round.

---

## 5. Data model — V47 migration (fully additive)

```sql
CREATE TABLE competition_phase (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL,
    ordinal        INT  NOT NULL,           -- 0-based order within the competition
    name           VARCHAR(40) NOT NULL,    -- "Primeira Fase", "Quartas de final", ...
    kind           VARCHAR(16) NOT NULL,    -- ROUND_ROBIN | KNOCKOUT
    legs           INT  NOT NULL,           -- 1 or 2
    rounds         INT  NOT NULL,           -- match-units this phase occupies
    qualifiers     INT                      -- ROUND_ROBIN: how many advance; null for KNOCKOUT
);
CREATE INDEX idx_competition_phase_comp ON competition_phase(competition_id, ordinal);
CREATE INDEX idx_competition_phase_save ON competition_phase(save_game_id);

ALTER TABLE fixtures ADD COLUMN phase_id      UUID;
ALTER TABLE fixtures ADD COLUMN leg           INT;   -- 1 or 2; null for league fixtures
ALTER TABLE fixtures ADD COLUMN tie_id        UUID;  -- shared by the two legs of one tie
ALTER TABLE fixtures ADD COLUMN penalty_home  INT;
ALTER TABLE fixtures ADD COLUMN penalty_away  INT;
CREATE INDEX idx_fixtures_phase ON fixtures(phase_id);
CREATE INDEX idx_fixtures_tie   ON fixtures(tie_id);

ALTER TABLE season_event ADD COLUMN phase_id UUID;
```

Every added column is nullable. Nothing is dropped, renamed or rewritten.

### 5.1 Entities & repositories

- `CompetitionPhase` entity + `CompetitionPhaseRepository`
  (`findByCompetitionIdOrderByOrdinalAsc`, `findBySaveGameId`,
  `@Transactional deleteBySaveGameId`). `PhaseKind` enum `{ ROUND_ROBIN, KNOCKOUT }`.
- `Fixture` gains `phaseId`, `leg`, `tieId`, `penaltyHome`, `penaltyAway` with getters and the
  setters the resolver and the simulator need.
- `SeasonEvent` gains `phaseId`.
- `Competition.totalRounds` becomes the sum of its phases' `rounds`, computed at generation.
  The field stays — its 7 call sites keep working.

### 5.2 A tie

A knockout tie is **two fixtures sharing a `tie_id`**. The aggregate is computed from both, never
stored. This keeps `fixtures` the single source of match truth and avoids a parallel `tie` table
that could disagree with it.

---

## 6. Match engine — extra time and penalties

### 6.1 Trigger

A fixture needs a winner **only** when its phase is `KNOCKOUT` and either:

- the phase is single-leg and the match is level at 90; or
- it is leg 2 and the tie is level on aggregate at 90.

Every league match, every first leg, and every already-decided second leg ends at 90 exactly as
today.

### 6.2 Determinism contract

**The extra-time and shootout code must not consume a single RNG draw unless it actually fires.**
A league match simulated with this code present must be byte-identical to the same seed before
the change.

This is the same discipline the weather slice used when neutral weather returned exactly `1.0`,
and it protects every calibration already tuned into the engine. It is pinned by a regression
test (§9).

### 6.3 Extra time

`LiveMatchService.MINUTES = 90` and `FixtureSimulator`'s `for (int minute = 1; minute <= 90; ...)`
become a period-aware bound: 90, then two 15-minute halves to 120.

The per-minute machinery is unchanged, so goals, cards, injuries, AI reactions and substitutions
all keep working in extra time with no new code. Additional consequences:

- Extra time carries its own fitness cost through `FitnessCalculator` — 120 minutes must visibly
  hurt the next match.
- `MAX_SUBS` rises from 5 to 6 once extra time begins, matching the real rule.

### 6.4 Shootout

Best of five, then sudden death. Takers are the players on the pitch at the end of 120, ordered
by `finishing`; conversion is `finishing` against the opposing keeper's `goalkeeping`, drawn from
the same seeded RNG. The result persists to `penalty_home` / `penalty_away` on the second-leg
fixture. Sudden death is bounded by the outfield rotation, so the sequence always terminates and
can never return a draw.

### 6.5 Scoring conventions

- `homeGoals` / `awayGoals` hold the **120-minute** score. Extra-time goals therefore count
  normally in standings, top scorers and player statistics.
- **Shootout conversions are not goals** in any statistic. They only decide who advances.
- New `EventType` values: `EXTRA_TIME_START`, `PENALTY_SHOOTOUT_SCORED`,
  `PENALTY_SHOOTOUT_MISSED`.

### 6.6 Advancement

Tie winner is decided by: aggregate over both legs → the 120-minute leg-2 result → the shootout.
**No away-goals rule** — it has been abolished in essentially every competition this models.

### 6.7 Live match

The live UI gains an extra-time period indicator and a kick-by-kick shootout sequence with a
running tally. Mastella commentary gains lines for extra time and for shootout kicks.

---

## 7. Standings, classification, archive, prize, news

### 7.1 Standings are per phase

`StandingsService` computes over a **phase's** fixtures rather than a competition's.

- The competition detail response carries its phases (`ordinal`, `name`, `kind`, `complete`).
- The standings endpoint takes an optional `phaseId`, defaulting to the latest round-robin phase.
- A knockout phase returns **ties** instead of a table: `(tieId, seeds, both legs with scores,
  aggregate, penalties, winner)`.

### 7.2 Final classification

One pure function turns phase results into a single ordering, consumed by everything downstream:

| Position | Who |
|---|---|
| 1 | Champion |
| 2 | Runner-up |
| 3–4 | Losing semifinalists, ordered by first-phase position |
| 5–8 | Losing quarterfinalists, ordered by first-phase position |
| 9–12 | Non-qualifiers, in first-phase order |

This is how Brazilian competitions publish *classificação final*.

### 7.3 Archive

`Season` already carries `champion_club_id` separately from `managed_club_position`, so the
archive already distinguishes who won from where you finished.

- `Season.championClubId` becomes the **knockout winner**, not the table leader.
- `Season.managedClubPosition` becomes the **final classification**.
- `SeasonStanding` keeps archiving the **first-phase table**, unchanged. It is the honest league
  record and its shape needs no migration.

### 7.4 Prize money

`FinanceService` calls `financeCalculator.prizeMoney(position, memberClubs.size())`. Feed it the
final classification and it keeps working unchanged — a run to the final now pays like a top-two
league finish, which is roughly right. Paid once, when the last phase completes. **No new prize
model.**

### 7.5 News and advance preview

`AdvancePreview` and news stop saying "Rodada 5 de 17" and become phase-aware: "Primeira Fase —
Rodada 5", "Quartas de final — Volta". Phase transitions generate news: qualification secured or
missed, the quarterfinal draw, each tie's result, the champion crowned.

---

## 8. Frontend

The competition page switches on phase kind:

- **Round-robin phase** — renders today's `TablePage` untouched.
- **Knockout phase** — renders its ties grouped under *Quartas de final* / *Semifinal* / *Final*,
  each showing both legs, the aggregate, and penalties where they happened.
- A **phase selector** lets you look back at the first-phase table after the knockout starts.

Reuses existing components; no new layout primitives, and it survives a phone screen. A bracket
view can be layered on later without changing the data this page already fetches.

---

## 9. Seed data

`clubs.json` competition entries gain a `phases` array declaring each phase's name, kind, legs,
rounds and qualifiers.

### 9.1 Catarinense membership — 10 clubs to the real 12

Real 2026 field:

- **Grupo A:** Avaí, Camboriú, Concórdia, Marcílio Dias, Brusque, **Joinville**
- **Grupo B:** Chapecoense, **Carlos Renaux**, Barra, Figueirense, Criciúma, **Santa Catarina**

(The group split itself is not modelled — the first phase is a single round-robin over all 12.
The groups are recorded here only to document the field.)

Changes to the pool:

- **Add 3 clubs:** Joinville, Carlos Renaux, Santa Catarina. Full identity data (name,
  shortName, commonName, nickname, stadium, city, state, `climateZone`, `stadiumCapacity`,
  colours, default tactics) researched when authoring, matching the shape every pool club uses.
- **Remove Hercílio Luz (`HER`)** from the pool. It is not in the 2026 field, and with no
  Catarinense second division it would be an orphan — dead weight instantiated into every save.
  This follows the precedent set by the Série B seed slice, which removed orphaned clubs for the
  same reason.

Net: Catarinense members 10 → 12; club pool 28 → 30.

### 9.2 Squad fidelity

The three new clubs follow the **Catarinense-only convention** established by the Série B seed
spec: real rosters with real player names, attributes **derived** via `AttributeDeriver` rather
than hand-authored. Hand-authored 28-attribute profiles are reserved for Série B clubs and for
the national divisions when those slices arrive.

---

## 10. Compatibility

**A competition with no phase rows takes the existing single-phase league path, unchanged.**

Existing careers keep their 18-round Catarinense and keep playing to completion, including across
rollover. They are **not** backfilled into the new format. New careers get phases.

This mirrors how the weather slice treated pre-V45 saves, and it means V47 cannot break a save in
flight. Phase rows are created at career creation from the seed and carried forward across
rollover; only fixtures and `season_event` rows are regenerated.

---

## 11. Testing

**Scheduler**
- `singleRoundRobin` plays each pair exactly once with balanced home/away allocation.
- `knockoutPairings` seeds 1×8 / 2×7 / 3×6 / 4×5 correctly for 8 qualifiers.

**PhaseResolver**
- Qualifier selection from a completed phase's standings.
- Seeding and second-leg home advantage for the higher seed.
- **Idempotency** — running twice creates no duplicate fixtures.
- Determinism — identical standings produce identical pairings.

**Calendar**
- Knockout match-units are reserved with no fixtures attached.
- Match dates are stable across regeneration.
- Total season length is 17 match-units for the Catarinense.

**Match engine**
- **The determinism regression test:** a league match simulated with the extra-time and shootout
  code present is byte-identical to the same seed before the change.
- Extra time fires only on a level knockout tie and never on a level league match.
- The shootout is seeded, terminates under sudden death, and cannot return a draw.
- Aggregate resolution counts extra-time goals and does not apply away goals.
- Shootout conversions do not appear in goal statistics.

**Integration (Testcontainers)**
- A full Catarinense season end to end: 11 first-phase rounds, then quarterfinals, semifinals and
  final materializing on their reserved dates; champion crowned; `Season` archived with the
  correct champion and final classification; prize paid exactly once.
- V47 applied against a pre-V47 database with existing careers — old saves keep their league-only
  Catarinense and remain playable.

**Frontend**
- Phase switching, tie list rendering, aggregate and penalty display.

**Live verification (not optional)**
- A live click-through of a knockout tie including extra time and a shootout.

`LiveMatchEngine` is the riskiest surface in this slice. The weather slice is the precedent: a
wire-format mismatch there passed 574 frontend tests and the entire backend suite while throwing
on first render. Test suites do not cover this seam.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Extra-time/shootout code perturbs seeded league results | Byte-identical determinism regression test; no RNG draws unless triggered |
| `PhaseResolver` double-materializes a round | Materialize only when the target phase has no fixtures |
| Live match UI breaks on the new period/shootout states | Mandatory live click-through, not just test suites |
| V47 breaks existing careers | Fully additive migration; null `phase_id` falls back to the league path; IT against a pre-V47 database |
| Season length drift disturbs the Série B interleave | 17 vs 18 match-units; calendar test pins total length |

---

## 13. What comes after this slice

Each is its own spec → plan → implementation cycle:

1. **Promotion and relegation** — division structure, clubs moving between tiers at rollover,
   board expectations tied to tier. The core of the career arc.
2. **Série A** — 20 real clubs with hand-authored squads. Drops into the existing round-robin
   machinery; needs (1) to be meaningful.
3. **Série C** — 20 real clubs, using this slice's phase engine for its two-phase format.
4. **Other state championships** — each a phase-configured competition; needs a much wider club
   pool.
5. **Série D** — 64 clubs and 16 groups; needs `group_count` and group scheduling added to the
   phase model.
6. **State second divisions** — would finally make the Catarinense relegation quadrangular
   expressible.

---

## 14. Sources

- [Campeonato Catarinense de Futebol de 2026 — Série A (Wikipédia)](https://pt.wikipedia.org/wiki/Campeonato_Catarinense_de_Futebol_de_2026_-_S%C3%A9rie_A)
- [Guia do Campeonato Catarinense 2026 — os 12 clubes (ND Mais)](https://ndmais.com.br/futebol/guia-do-campeonato-catarinense-2026-conheca-os-12-clubes-estadios-e-destaques-dos-times/)
- [Regulamento geral do Catarinense 2026 (FCF, PDF)](https://avai.com.br/wp-content/uploads/2025/11/20251120-Regulamento-geral-Catarinense-2026.pdf)
- [Catarinense 2026 começa com novo formato (Visor Notícias)](https://visornoticias.com.br/campeonato-catarinense-2026-comeca)
- [Quartas de final e quadrangular do rebaixamento (Notisul)](https://www.notisul.com.br/chuva-de-gols-6-rodada-catarinense-2026/)
