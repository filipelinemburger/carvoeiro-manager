# Comissão Técnica dos Clubes — Real Staff Seeded at Career Start — Design Spec

**Date:** 2026-08-10
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the Staff System slice (`2026-07-15-staff-system-design.md`), the staff-attribute
pass (`2026-07-22-opponent-analysis-staff-attributes-design.md`), and the researched-seed
precedent set by `2026-07-17-real-free-agents-design.md` / `2026-07-20-free-agent-real-attributes-design.md`.

---

## 1. Why this exists

Every real football club already employs a coaching staff. This game does not model that: a fresh
career starts with Criciúma's four staff slots reading **"Vaga em aberto"**, and the other 30 clubs
have no staff at all — `StaffEffectsService` hands them a hardcoded baseline instead
(`StaffEffectsService.java:18`).

Two things are wrong with that. First, immersion: the manager arrives at a club that apparently
fired its entire comissão técnica the day before. Second, uniformity: every AI club is
mechanically identical in training, injury prevention, negotiation and sponsorship, so the
squad list is the only thing that distinguishes Chapecoense from Carlos Renaux.

This slice researches the real coaching staff of all 31 clubs in the seed pool, persists them at
career creation, and makes those attributes actually drive the AI clubs' effects.

## 2. Current state

- **`staff-pool.json`** holds 59 hand-authored real professionals (15 ASSISTENTE, 15
  PREPARADOR_FISICO, 14 OLHEIRO, 15 FISIOTERAPEUTA). They belong to **no club** — they are a
  free hiring pool consumed by `StaffCandidateGenerator`.
- **No staff row is created at career creation.** `SaveGameService.createCareer` seeds clubs,
  players, free agents, competitions and the calendar; `staff` is never touched.
- **AI clubs are a constant.** `StaffEffectsService.averageFor(clubId, managed, role)` returns
  `AI_BASELINE_AVERAGE = 14.0` for every non-managed club without reading the database
  (`StaffEffectsService.java:53-60`).
- **An unfilled role on the managed club is neutral**, not penalised: `averageFor` returns `null`
  and each effect method falls back to its identity value (bonus 0, multiplier 1.0).
- **Candidates exclude anyone already hired in the whole save**, not just at your club
  (`StaffService.java:52-54`, `65-67`).
- **AI clubs already pay their staff wage bill** if rows exist: `monthlyStaffWageBills` groups by
  `club_id` across the entire save with no managed-club filter (`FinanceService.java:364-368`).
- **The `staff` table has no enum constraint on `role`** — `V27__staff.sql` declares
  `role VARCHAR(20) NOT NULL` with only `UNIQUE (club_id, role)`.

## 3. Scope decisions

These four were settled during brainstorming and drive everything below.

**3.1 — AI staff counts in gameplay.** The seeded attributes feed `StaffEffectsService` for real;
the 14.0 baseline is deleted. A club with a strong physio genuinely gets fewer injuries.

**3.2 — Unresearched roles stay empty.** Only people confirmed by a source are seeded. A club with
no findable olheiro starts with that slot open and therefore a neutral effect in it — which reads
as a coherent penalty for a small club rather than as missing data.

**3.3 — Hiring becomes poaching.** Because §3.2 leaves real professionals employed and
`StaffService` excludes every hired name save-wide, the candidate list would otherwise collapse to
newgens. Instead, staff employed at *other* clubs become poachable: hiring one empties the source
club's slot.

**3.4 — `TECNICO` becomes a fifth role.** Head coach is both the most recognisable member of a
comissão técnica and the only role with guaranteed 100% research coverage. At the managed club the
slot is the player, so it is never persisted and never hireable.

**Out of scope.** AI clubs do not hire, fire, or replace staff over time — a sacked coach staying
in the job for the whole career is accepted for this slice. Staff contracts, staff morale, and a
staff transfer market are not modelled. `TECNICO` is not added to the managed club's wage bill.

## 4. Seed data

### 4.1 `backend/src/main/resources/seed/club-staff.json`

A new file, not an extension of `clubs.json` — that file already carries 31 inline squads and is
effectively unreviewable by hand.

```json
[
  {
    "clubShortName": "CRI",
    "staff": [
      { "name": "…", "role": "ASSISTENTE", "primaryAttribute": 13, "secondaryAttribute": 12 },
      { "name": "…", "role": "PREPARADOR_FISICO", "primaryAttribute": 14, "secondaryAttribute": 13 }
    ]
  }
]
```

Loaded by a new `SeedLoader.loadClubStaff()` into `List<SeedClubStaff>`, where
`SeedClubStaff(String clubShortName, List<SeedStaff> staff)` reuses the existing `SeedStaff`
record unchanged.

A club may appear with fewer than five entries, or be absent entirely. Absent role ⇒ open slot.

### 4.2 Research rules

- **One citable source per name**: club's official site or social account, a club press release, or
  a recognised sports outlet. No inference from squad photos, no "probably still there".
- **Data is as of the 2026 season**, matching the seed's `seasonYear` and the squad research.
- Coverage will be openly uneven — high for `TECNICO` and `ASSISTENTE`, low for `OLHEIRO`, and
  lower across the smaller Catarinense clubs (Carlos Renaux, Santa Catarina, Barra, Concórdia).
  That is the expected outcome of §3.2, not a defect to paper over.
- The spec's §11 records the per-club coverage actually achieved, so the gaps are auditable.

### 4.3 Attribute calibration

Attributes stay on the existing 1–20 scale and remain hand-authored — the JSON carries only the
numbers, the rule lives here:

1. **Base band from club reputation.** Reputation ≥ 70 → 14–17; 55–69 → 12–15; < 55 → 10–13.
2. **Adjust by track record**: Série A experience, national titles, or a long tenure at the club
   push toward the top of the band or one point above it; a first-time appointment sits at the
   bottom.
3. **`secondaryAttribute` sits at or one below `primaryAttribute`**, matching the shape of every
   entry already in `staff-pool.json`.
4. Values outside 1–20 are rejected by the seed validation test (§10) and by the existing
   `staff_primary_attribute_range` / `staff_secondary_attribute_range` CHECK constraints
   (`V35__staff_attributes.sql`).

`StaffQuality` is **not** stored in the seed — it is derived at persist time via
`StaffQuality.forAverage(...)`, exactly as `StaffCandidateGenerator` already does for pool
professionals (`StaffCandidateGenerator.java:62-63`).

### 4.4 Reconciliation with `staff-pool.json`

Anyone the research finds employed **moves** from `staff-pool.json` into `club-staff.json`. The
pool keeps its meaning: unemployed professionals available on the market. A name must never live
in both files — that would let the same person be simultaneously employed and a free agent, and
under §3.3 would let you poach a person you can already sign for free.

The seed validation test (§10) enforces this.

## 5. Domain model

### 5.1 `StaffRole` gains `TECNICO`

```java
public enum StaffRole {
    TECNICO, ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA
}
```

`TECNICO` is declared **first** so it sorts to the top of any ordinal-ordered listing, matching how
a comissão técnica is presented. Note that `StaffCandidateGenerator` seeds its RNG with
`role.ordinal()` (`StaffCandidateGenerator.java:51`) — inserting a value at position 0 shifts every
other role's ordinal and therefore changes which candidates an existing save is offered. This is
accepted: candidate lists are regenerated on demand and never persisted, so nothing already saved
breaks. The shift is called out in §9.

**No DDL is required for the enum.** `role VARCHAR(20)` fits `TECNICO` and carries no CHECK
constraint.

### 5.2 The managed club's `TECNICO` slot

No `staff` row is ever written for the managed club's `TECNICO`. `StaffService.getRoster` fills the
slot from `SaveGame.getManagerName()`, and `StaffRosterResponse` gains a `tecnico` field carrying a
`StaffResponse` with the manager's name, no wage, and no attributes.

This keeps the special case in one method instead of spreading a "is this row really the player"
check through hiring, wages, poaching and effects.

### 5.3 Attribute labels

`TECNICO`'s two attributes are **Trabalho Tático** and **Motivação**, following the existing
role-dependent labelling in `StaffPage.tsx:27-32`.

## 6. Career creation

`SaveGameService.createCareer` gains a staff pass after clubs are persisted and before contract
staggering — it needs `clubIdByShortName`, which the club loop already builds.

For each `SeedClubStaff` entry, for each `SeedStaff` in it:

- Skip when `clubShortName` is the managed club (`CRI`) **and** role is `TECNICO` (§5.2).
- Derive `StaffQuality.forAverage((primary + secondary) / 2.0)`.
- Wage comes from the existing tier table. That table currently lives as a private method inside
  `StaffCandidateGenerator` (`wageFor`, lines 115-121); it moves to `StaffQuality` as
  `monthlyWage()` so career creation and candidate generation share one definition instead of
  duplicating the three constants.
- Persist via `entityManager.persist(...)`, not `staffRepository.save(...)` — the same
  assigned-UUID/merge concern documented at `SaveGameService.java:66-68`.

An unknown `clubShortName` in the seed is a **hard failure at career creation**, thrown before any
row is written, mirroring how `SeedPhaseValidator` fails fast on a bad phase config
(`SaveGameService.java:222-226`). A silently-dropped club would mean a career quietly missing a
comissão técnica.

## 7. Effects

### 7.1 `StaffEffectsService` reads real rows for every club

`AI_BASELINE_AVERAGE` is deleted and the `boolean managed` parameter is removed from all four
public methods. `averageFor(clubId, role)` becomes a plain repository lookup returning `null` for
an unfilled slot, which every effect method already handles as its identity value.

Call sites updated (mechanical, 7 of them): `ContractService.java:63,111`,
`MatchOutcomeApplier.java:173`, `LiveMatchEngine.java:599`, `FinanceService.java:226`,
`TrainingService.java:87-88`.

### 7.2 `TrainingService` resolves per club

`TrainingService.java:87-88` currently resolves two constants for the whole training week — the
managed club's bonus and a single AI bonus derived from the baseline. The AI half is now per club,
so the week resolves a `Map<UUID, Integer>` of club → training bonus once, before the player loop,
and reads from it inside the loop. One query per club per week, not one per player.

### 7.3 Injury, wage-demand and sponsorship

No structural change. `LiveMatchEngine.resolveInjuryRiskMultiplier` already resolves once per side
per match (`LiveMatchEngine.java:588-600`), so removing the constant-return fast path costs two
queries per match, not one per minute. `ContractService` and `FinanceService` already call per
club.

### 7.4 The coach effect

New method:

```java
/** Head-coach strength multiplier for a club: [0.96, 1.04], 1.0 when the slot is empty. */
public double coachStrengthMultiplierFor(UUID clubId)
```

It maps the `TECNICO` attribute average through the existing `lerp` anchored at (10, 0.96) and
(17, 1.04), clamped to that range. The managed club has no `TECNICO` row (§5.2), so it always
receives exactly 1.0 — your own decisions are already the input the engine reads.

`TeamMatchInput` gains:

```java
/** Scales attack and defense by the head coach's influence; 1.0 returns an identical record. */
public TeamMatchInput withCoachMultiplier(double m)
```

Applied immediately after the two places team strength is built —
`FixtureSimulator.java:228-229` and `LiveMatchEngine.java:380`. `TeamStrengthCalculator` stays a
pure, repository-free domain service; the multiplier is composed onto its output rather than
threaded through it.

**Determinism contract:** at `m = 1.0` the returned record is field-identical, so every existing
seeded-result test stays green (§10).

## 8. Poaching

### 8.1 Candidates

`StaffCandidate` gains two nullable fields:

```java
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage,
                             int primaryAttribute, int secondaryAttribute,
                             UUID sourceStaffId, String currentClubName) {}
```

`null` in both means an unemployed professional or a newgen. `StaffCandidateResponse` mirrors
`currentClubName` for the UI; `sourceStaffId` stays server-side.

`StaffCandidateGenerator.generate(...)` takes an additional input describing who is employed
elsewhere. The generator must not gain repository access, and it needs the club *name* for display,
so `StaffService` resolves both and passes a flat view:

```java
/** A staff member currently employed at another club — a poaching target. */
public record EmployedStaff(UUID staffId, String name, String clubName,
                            int primaryAttribute, int secondaryAttribute) {}
```

`StaffService` builds the list from `staffRepository.findBySaveGameId(saveGameId)` filtered to the
requested role, excluding the hiring club and excluding `TECNICO`, joined against club names it
already loads. Candidates are assembled in order until `CANDIDATE_COUNT` is reached:

1. unemployed pool professionals for the role (today's behaviour),
2. staff employed at other clubs in the save,
3. generated newgens.

Determinism is preserved: the employed list is sorted by staff id before shuffling with the
existing `(saveGameId, clubId, role, onDate)` seed, so the same day yields the same three names.

`excludedNames` narrows from "every name hired in the save" to "every name hired **at the hiring
club**" — otherwise §3.3's whole point is defeated by the existing exclusion.

### 8.2 Hiring

`StaffService.hire` gains one step: when the chosen candidate carries a `sourceStaffId`, delete
that row before inserting the new one. Ordering matters and the existing flush comment
(`StaffService.java:76-81`) applies here too — both deletes are issued and flushed before the
insert.

The source club is left with an **open slot**, which under §7.1 means a neutral effect there. AI
clubs do not re-hire (§3, out of scope).

### 8.3 `TECNICO` is not hireable

`StaffService.getCandidates` and `hire` reject `TECNICO` with `ErrorCode.VALIDATION_ERROR`. There
is no scenario where the player hires a head coach — they are the head coach. The frontend never
renders the button, and the backend rejects a hand-crafted request.

## 9. Compatibility with existing saves

`V50__club_staff.sql` backfills careers already in progress, following the club-name matching
precedent of `V43__research_backfill.sql` and `V45__weather_and_stadiums.sql`. Generated from
`club-staff.json` by a script under `backend/scripts/`, as V43 was — never hand-edited.

One statement per seeded (club, role, name), carrying three guards:

```sql
INSERT INTO staff (id, save_game_id, club_id, role, quality, name, wage,
                   primary_attribute, secondary_attribute)
SELECT gen_random_uuid(), c.save_game_id, c.id, 'ASSISTENTE', 'BOM', 'Fulano de Tal', 45000.00, 13, 12
FROM clubs c
WHERE c.name = 'Avaí Futebol Clube'
  -- (a) never overwrite a slot that is already filled
  AND NOT EXISTS (SELECT 1 FROM staff s
                   WHERE s.club_id = c.id AND s.role = 'ASSISTENTE')
  -- (b) never duplicate a person the user already hired somewhere in this save
  AND NOT EXISTS (SELECT 1 FROM staff s
                   WHERE s.save_game_id = c.save_game_id AND s.name = 'Fulano de Tal');
```

Guard (a) is what keeps a player's own hires intact — without it the insert would violate
`UNIQUE (club_id, role)`. Guard (b) covers the §3.3 interaction: in an older save the user may
already have hired, from the pool, someone this research places at another club; inserting them
again would put the same person in two dugouts at once.

Guard (c) applies **only to `TECNICO` statements**, which additionally carry:

```sql
  AND c.id IS DISTINCT FROM (SELECT g.managed_club_id FROM save_games g
                              WHERE g.id = c.save_game_id)
```

so the managed club never receives a head-coach row, matching §5.2. It is expressed as a predicate
rather than resolved by the generator script because `managed_club_id` (`V2__game.sql:4`) is
per-save runtime state, unknown at generation time — the same save file may be one user's Criciúma
career and, later, someone else's.

**What still differs after the backfill:** an existing save whose managed club already hired a
physio from the pool keeps that person, while a fresh career gets the researched one — guard (a)
prefers the user's choice. Existing saves are therefore not byte-identical to new ones, the same
trade V43 already accepted (`V43__research_backfill.sql` header).

Skipping the migration entirely is **not** an option: §7.1 deletes the 14.0 baseline, so an
un-backfilled save would silently drop every AI club to neutral effects mid-career.

## 10. Testing

**Seed validation** (a test over the JSON, not over a running career):

- no name appears in both `club-staff.json` and `staff-pool.json` (§4.4);
- no name appears twice within `club-staff.json`;
- every `clubShortName` resolves against `clubs.json`'s pool;
- at most one entry per (club, role);
- every attribute is within 1–20.

**Career creation:**

- a club with researched staff gets exactly its seeded rows, with `quality` derived from the
  average and the tier wage applied;
- a club with a partial entry gets only the researched roles, and `getRoster` reports the rest as
  open;
- Criciúma has **no** `TECNICO` row, and `getRoster` still returns a `tecnico` carrying the
  manager's name;
- an unknown `clubShortName` in the seed fails career creation before any row is written.

**Effects:**

- an AI club with a seeded physio gets its real multiplier, not 14.0's;
- an AI club with an open slot gets the identity value;
- `TrainingService` gives two AI clubs with different preparadores different bonuses in the same
  week.

**Coach multiplier:**

- `withCoachMultiplier(1.0)` returns a record equal to the original — the determinism contract;
- an existing seeded-simulation test is asserted unchanged, proving no accidental drift for clubs
  without a `TECNICO`;
- the multiplier is clamped at both ends.

**Poaching:**

- a candidate employed elsewhere is offered with `currentClubName` set;
- hiring them deletes the source club's row and leaves that role open there;
- a name already hired **at the hiring club** is still excluded;
- `getCandidates` and `hire` reject `TECNICO`.

**Migration:** applied against a save with a hand-hired physio, that physio survives and the
remaining roles fill; the managed club gains no `TECNICO`.

**Frontend:** `StaffPage` renders the `TECNICO` card with the manager's name and no action button;
a candidate with `currentClubName` renders the club chip, one without renders "Livre".

## 11. Research coverage

Filled in during implementation, one row per club, so the gaps left by §3.2 are auditable rather
than invisible:

| Clube | TECNICO | ASSISTENTE | PREP. FÍSICO | OLHEIRO | FISIOTERAPEUTA |
|---|---|---|---|---|---|
| _(a preencher)_ | | | | | |

## 12. Risks

- **Research coverage is the real deliverable and it is uneven.** The smaller Catarinense clubs may
  end up with only a head coach. §3.2 makes that a deliberate outcome, but if coverage turns out
  worse than expected across the board, the balance conclusion in §7.1 (AI asymmetry) is weaker
  than intended and should be revisited before the effect change ships.
- **Staff data goes stale fast.** Coaching staff turn over constantly in Brazilian football; this
  seed is a 2026 snapshot and will drift. Sources are recorded so a refresh pass is mechanical.
- **The `StaffRole` ordinal shift** (§5.1) changes candidate lists for existing saves. Harmless —
  candidates are never persisted — but surprising if not expected.
- **AI clubs never re-hire**, so a poached slot stays empty for the rest of the career. Acceptable
  for one poach per save; if poaching turns out to be a repeatable exploit against a single rival,
  a simple AI backfill at season rollover is the follow-up.

## 13. Sources

Recorded per name during implementation, alongside the coverage table in §11.
