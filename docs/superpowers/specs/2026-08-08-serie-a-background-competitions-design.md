# Série A and Background Competitions — Design Spec

**Date:** 2026-08-08
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the competition format engine (2026-08-05), the real Série B seed (2026-07-07).

---

## 1. Why this exists

The roadmap's goal is the Brazilian pyramid: Criciúma rising and falling. The format engine gave
competitions a phase model; this slice gives the pyramid its **second national tier**, so that
promotion has somewhere to go.

**A sequencing error in the previous roadmap is corrected here.** The format-engine spec listed
promotion/relegation as the next slice and Série A after it. That order is impossible: promotion
needs a destination division, and the save contains only the Campeonato Catarinense and Série B.
Série A therefore comes first, as its own slice, and promotion/relegation follows against a real
two-tier pyramid.

---

## 2. The constraint that shapes this slice

Adding Série A is not primarily a data problem. It breaks the season calendar.

`SeasonCalendarGenerator` interleaves **every** competition into one dated sequence, one match-unit
every `MATCH_INTERVAL_DAYS = 5`. That is correct today because Criciúma plays in both existing
competitions, so its matches must be spaced apart.

| | match-units | season length |
|---|---|---|
| Today (Catarinense 17 + Série B 38) | 55 | 270 days — comfortably inside the year |
| With Série A interleaved (+38) | 93 | **460 days — a 15-month season** |

Interleaving assumes the managed club plays in every competition. Série A is the first one it does
not. Its rounds must not consume slots on the managed club's calendar.

---

## 3. Locked decisions

1. **Série A is its own slice**; promotion/relegation follows it.
2. **Parallel dating.** Competitions the managed club plays in keep interleaving exactly as today.
   Every other competition gets an independent date sequence across the same season window. Season
   length is unchanged at 270 days.
3. **Catch-up resolution.** Every advance, before returning, simulates any background round whose
   date has now passed. The background world is always current as of the in-game date.
4. **No special presentation.** Série A appears in the competition switcher like any other
   competition, with a table and top scorers and no highlighted row. The user infers it is not
   theirs because Criciúma is not listed.
5. **"Mine" is derived from membership**, never stored. A competition is the managed club's if the
   club has a `competition_club` row in it.

### 3.1 Why membership is derived, not flagged

A `background` column on `competitions`, or a `managed` flag in the seed, would be a second source
of truth for something `competition_club` already answers. The very next slice is promotion, which
moves the managed club between divisions — a stored flag would have to be flipped on two
competitions in the same transaction, and a missed flip yields a division the player plays in
whose rounds never stop the advance: matches silently skipping themselves. Derivation is correct
before and after promotion with no extra work.

---

## 4. Architecture

### 4.1 Partitioning

`SeasonCalendarGenerator.generate` partitions competitions into:

- **Managed competitions** — the managed club has a `competition_club` row. Interleaved and dated
  exactly as today; no behaviour change.
- **Background competitions** — everything else. Each gets its own independent date sequence.

The managed club id comes from `SaveGame.getManagedClubId()`, which the generator already has
access to via the save.

### 4.2 Background dating

A background competition spreads its rounds evenly across the **same window the managed calendar
occupies**, so its table advances at a believable pace alongside the player's season:

```
backgroundInterval = max(1, managedSeasonDays / backgroundRounds)
round r date = SEASON_START.plusDays(r * backgroundInterval)
```

With a 270-day managed season and 38 Série A rounds, that is a round every 7 days — a realistic
weekly league rhythm, finishing within the same window.

Background rounds get `season_event` rows exactly as managed ones do (same MATCH type, same
`competition_id`/`round`), because the resolution step needs dated, processed-flagged events to
know what is due. They are distinguished at read time by whether their competition is managed —
not by a column.

### 4.3 Catch-up resolution

A new `BackgroundCompetitionService.resolveDue(save, throughDate)`:

1. Find unprocessed MATCH events for background competitions whose `event_date <= throughDate`.
2. For each, in date order, simulate its round through the existing `FixtureSimulator.simulateRound`
   and mark the event processed.
3. Run matchday finance for that competition's clubs, exactly as a managed round does — background
   clubs must stay financially alive or their squads decay differently from managed-division clubs.

Called from `SeasonAdvanceService.advance` **after** the managed event is processed and its date is
known, so the background world is current as of the date the player just reached.

**It never returns anything to the advance response.** The advance stops only on the player's own
matches; background results are visible through the competition's table, not through the advance
feed.

### 4.4 What does not change

- Managed competitions' interleave, dates and 5-day spacing.
- `PhaseResolver`, `TieResolver`, `FinalClassification` — Série A is a plain league with no phases,
  so it takes the existing unphased path untouched.
- Determinism: background rounds are seeded from `(saveGameId, fixture)` like every other fixture,
  and resolved in date order, so a replayed season produces identical results.

---

## 5. Archive, prize and season end

`Season.managedClubPosition` and `championClubId` are `NOT NULL`, and a background competition has
no managed club.

**Background competitions are not archived.** `SeasonRolloverService` skips any competition the
managed club is not in. The season-history page is a record of the player's career; a table the
player had no part in does not belong in it. This avoids a sentinel value that every reader would
then have to interpret.

**Prize money is unaffected.** `FinanceService.processSeasonEnd` already returns `ZERO` when the
managed club is not in the competition; that path simply now gets exercised.

**Season completion.** `noMoreMatchEvents` currently asks whether any unprocessed MATCH event
remains. With background events in the same table it must ask only about **managed** competitions —
otherwise the season would refuse to finish until Série A's last round, which is a different date.
This is the one existing method whose semantics this slice changes, and it is load-bearing: getting
it wrong either ends the season early or never.

---

## 6. Seed data

The real 2026 Série A field (source: CNN Brasil, Olympics.com, Metrópoles):

Atlético-MG, Bahia, Botafogo, Bragantino, Corinthians, Cruzeiro, Flamengo, Fluminense, Grêmio,
Internacional, Mirassol, Palmeiras, Santos, São Paulo, Vasco, Vitória — plus the four promoted from
the 2025 Série B: Athletico-PR, **Chapecoense**, Coritiba, Remo.

### 6.1 Overlaps to handle

- **Chapecoense (`CHA`) is already in the pool** as a Catarinense club. It is not added again; it
  joins Série A's `memberShortNames` as an existing club. It legitimately plays both — the
  managed/background split is per competition, not per club, so this needs no special handling.
- **`BOT` is already Botafogo-SP** (Série B). Botafogo (RJ) is a different club and needs its own
  short name — `BFR`.
- **Coritiba, Remo and Mirassol were removed from the pool** during the Série B slice as orphans.
  They return here as Série A members.

Net: **19 new clubs**, pool 31 → 50. Squads follow the established convention — real rosters with a
full authored `attributes` block per player, calibrated *above* the Série B clubs, since this is the
country's top division.

### 6.2 Seed shape

Série A is added to `clubs.json` `competitions` with `type: "LEAGUE"`, 20 members and **no
`phases` key** — a plain double round-robin, which the format engine already handles unchanged.

---

## 7. Frontend

Nothing new is built. Série A appears in the existing competition switcher because it is a
competition in the save; its table and top scorers use the existing endpoints; no row highlights
because the managed club is not a member.

The one check worth making explicitly is that the competition page renders a competition containing
no managed club without error — every "your club" affordance must degrade to absent, not to a
crash.

---

## 8. Testing

**Calendar**
- A save with one managed and one background competition: managed match-units keep their 5-day
  spacing and the season is still 270 days; background rounds are dated independently across the
  same window.
- Adding a background competition does not change any managed fixture's date — pinned by comparing
  generated dates against a save without it.

**Resolution**
- After an advance, every background round dated on or before the new current date is processed and
  its fixtures played; none dated after it are.
- Background results never appear in the advance response.
- Determinism: two saves generated from the same seed produce identical background results.

**Season end**
- The season finishes when the managed competitions' events are exhausted, regardless of whether
  background events remain.
- A season whose background competition has unplayed rounds still rolls over cleanly.

**Archive and finance**
- No `Season` row is written for a background competition.
- `processSeasonEnd` on a background competition pays the managed club nothing and throws nothing.

**Seed**
- Série A has 20 members, no phases, and 380 fixtures.
- `CHA` belongs to both the Catarinense and Série A; `BOT` and `BFR` are distinct clubs.
- Pool is 50 clubs, all with authored attributes.

**Integration**
- A full season with three competitions completes, Série A's table is full and plausible at the
  end, and the managed club's season is unchanged in length.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| `noMoreMatchEvents` counting background events ends the season never or early | Scope it to managed competitions; explicit tests both ways |
| 380 extra fixtures per season slow the IT suite noticeably | Measure before and after; the suite is already ~20 min and this is the largest single addition to it |
| Career creation instantiates ~440 more players | Measured at creation; the existing bulk-persist path already handles 625 |
| Background dating drifts out of the season window | Interval derived from the managed season's length, not a constant |

---

## 10. What comes after

1. **Promotion and relegation** — Série A ↔ Série B movement at rollover, now that both exist.
2. **Série C**, using the format engine's phase model for its two-phase format.
3. **Other state championships**; then **Série D**, which needs group support in the phase model.

---

## 11. Sources

- [Campeonato Brasileiro: clubes da Série A em 2026 (CNN Brasil)](https://www.cnnbrasil.com.br/esportes/brasileirao/campeonato-brasileiro-veja-os-clubes-que-participarao-da-serie-a-em-2026/)
- [Brasileirão Série A 2026: times confirmados (Olympics.com)](https://www.olympics.com/pt/noticias/brasileirao-serie-a-2026-times-confirmados)
- [Os 156 clubes das séries A, B, C e D em 2026 (Metrópoles)](https://www.metropoles.com/esportes/confira-os-156-clubes-das-series-a-b-c-e-d-do-brasileirao-em-2026)
