# Opponent Analysis + Staff Attributes — Design Spec

**Date:** 2026-07-22
**Status:** Approved (brainstorm decisions locked with the user)

## Summary

On the eve of every managed-club fixture (dated `matchDate − 1`), the Auxiliar Técnico
publishes a news item analyzing the next opponent: standings comparison, recent form,
best players, formation read, and tactical recommendations. To power it, every staff
role gains two CM-style 1–20 attributes that become the mechanical truth behind the
existing staff effects, and a researched pool of real Brazilian professionals feeds
staff candidate generation.

## User decisions (locked)

1. **Timing:** the analysis arrives as a news item on the news screen dated D−1
   (the eve of the match). No extra "Avançar" click — it is generated during the
   previous advance, not via a new calendar event.
2. **Attribute scope:** all 4 staff roles get attribute sheets (two attributes each),
   wired into their existing effects.
3. **Real names:** all 4 roles get real-professional research, best-effort — gaps
   filled with plausible authored names.
4. **Gate:** no Auxiliar Técnico hired → no analysis at all (hiring incentive).
   His attributes control analysis depth.
5. **Integration approach:** attributes are the mechanical truth; the quality tier
   (REGULAR/BOM/EXCELENTE) remains as the hiring/wage band. Effects become continuous
   functions of the role's attribute average, calibrated so tier midpoints reproduce
   today's values.

## Part A — Staff attributes

### Data layer

- **Migration `V34__staff_attributes.sql`:** `staff` gains `primary_attribute` and
  `secondary_attribute`, `INT NOT NULL`, range 1–20 (CHECK constraints). Backfill
  existing rows from the quality tier midpoint: `REGULAR → 10`, `BOM → 14`,
  `EXCELENTE → 17` (both columns).
- **`Staff` entity** (`backend/.../staff/Staff.java`): fields `primaryAttribute`,
  `secondaryAttribute` (int); `Staff.create(...)` extended to take both.
- Column meaning is role-dependent; labels live in code/frontend only:

| Role | Primary attribute | Secondary attribute |
|---|---|---|
| `ASSISTENTE` (Auxiliar Técnico) | Conhecimento Tático | Avaliação de Jogadores |
| `PREPARADOR_FISICO` | Preparação Física | Ciência Esportiva |
| `OLHEIRO` | Conhecimento do Mercado | Negociação |
| `FISIOTERAPEUTA` | Prevenção de Lesões | Reabilitação |

- `StaffRole` exposes the pt-BR label pair (e.g. `primaryAttributeLabel()` /
  `secondaryAttributeLabel()`); the frontend keeps its own label map.

### Tier ↔ attribute relationship

- Generated candidates: roll the tier first (unchanged logic), then roll each
  attribute uniformly within the tier band — `REGULAR 8–12`, `BOM 12–16`,
  `EXCELENTE 15–19` — deterministically from the existing candidate seed.
- Hand-authored pool professionals: attributes are authored; the tier is **derived**
  from the attribute average `a`: `a ≥ 15 → EXCELENTE`, `a ≥ 12 → BOM`, else
  `REGULAR`.
- Wages stay tier-based and unchanged: REGULAR 20k / BOM 45k / EXCELENTE 80k.

### Continuous effects (`StaffEffectsService`)

Let `a` = the role holder's attribute average (`(primary + secondary) / 2.0`).
Replace the enum-switch mappings with linear functions anchored at
`a = 10` (today's REGULAR value) and `a = 17` (today's EXCELENTE value):

- **Sponsorship multiplier** (ASSISTENTE): `1.0 + 0.10 * (a − 10) / 7`, clamped to
  `[1.0, 1.12]`.
- **Training bonus** (PREPARADOR_FISICO): `round(2 * (a − 10) / 7)`, clamped to
  `[0, 2]` (stays an int).
- **Injury-risk multiplier** (FISIOTERAPEUTA): `1.0 − 0.30 * (a − 10) / 7`, clamped
  to `[0.65, 1.0]`.
- **Wage-demand multiplier** (OLHEIRO): `1.0 − 0.10 * (a − 10) / 7`, clamped to
  `[0.88, 1.0]`.

Sanity anchors: `a=10` → exactly today's REGULAR values; `a=14` → within a point of
today's BOM values; `a=17` → exactly today's EXCELENTE values; `a=18–20` slightly
exceeds today's ceiling (the reward for elite pros), bounded by the clamps.

- **AI clubs:** unchanged model — no staff rows; the baseline for non-managed clubs
  becomes `a = 14` (equivalent to today's flat BOM baseline).
- **No auxiliar analysis effect here** — analysis depth reads the ASSISTENTE's two
  attributes individually (Part C), not through `StaffEffectsService` averages.

### DTOs / API

`StaffResponse` and `StaffCandidateResponse` gain `primaryAttribute` and
`secondaryAttribute` (int). No new endpoints.

## Part B — Real-professionals pool

- **New seed file `backend/src/main/resources/seed/staff-pool.json`:** array of
  `{ "name": string, "role": StaffRole, "primaryAttribute": int, "secondaryAttribute": int }`.
  Target **12–15 entries per role**. Loaded via `SeedLoader` (new
  `loadStaffPool()` following the `free-agents.json` pattern).
- **Research (parallel batch, established pattern):** real Brazilian professionals
  plausible at Série B / Catarinense level — auxiliares técnicos and preparadores
  físicos from real club staffs; best-effort real olheiros and fisioterapeutas.
  Where research runs dry, fill with plausible authored Brazilian names (still
  hand-authored attributes). Attribute authoring guidance: top-flight background
  15–19; journeyman 10–14.
- **`StaffCandidateGenerator`:** per `(saveGameId, clubId, role, onDate)` seed
  (unchanged determinism), draw the 3 candidates from pool members of that role
  **not already hired in this save** (checked against the save's `staff` rows);
  when fewer than 3 unhired pool members remain, fill the remainder with generated
  newgen names carrying tier-band-rolled attributes. Pool members keep their
  authored attributes and derived tier; wage by tier as today.
- **Seed validation test** (like the free-agents one): role coverage (all 4 roles
  ≥ 12 entries), attribute bounds 1–20, unique names.

## Part C — Match preview news (the D−1 analysis)

### Generation — `MatchPreviewService` (season package)

`maybePersistPreview(saveGameId)`:

1. Find the next unprocessed `MATCH` `SeasonEvent`; resolve the managed club's
   fixture in that round (reuse `AdvancePreviewService`'s lookup:
   `findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc` →
   `fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound` → home/away =
   managedClubId). No upcoming managed fixture → no-op.
2. **No `ASSISTENTE` hired for the managed club → no-op.**
3. Idempotency: load the latest news item of type `MATCH_PREVIEW`
   (`findFirstBySaveGameIdAndTypeOrderBySeqDesc`); if its payload's `fixtureId`
   equals the resolved fixture's id, skip. (Previews are generated in fixture
   order, so one-back is sufficient.)
4. Persist a news item via the `persistNewsItem` pattern (`seq = count + 1`):
   `type = "MATCH_PREVIEW"`, **`eventDate = matchDate.minusDays(1)`**, payload =
   `AdvanceResponse` with the new `matchPreview` field set and
   `date = matchDate.minusDays(1)`.

### Call sites

- End of `SeasonAdvanceService.advance(...)` — after processing any event (MATCH,
  TRANSFER, or FINISHED-check), so the next match's analysis lands right after each
  advance.
- After season rollover (round 1 of the new season).
- After `StaffService.hire(...)` when the hired role is `ASSISTENTE` — hiring
  immediately delivers the pending analysis; this also covers career start (new
  careers have no staff, so no preview until the first auxiliar is hired).

### Payload — `MatchPreviewData` (new record, nullable field on `AdvanceResponse`)

- Fixture context: `fixtureId`, `round`, `matchDate`, `home` (boolean),
  `stadiumName` (where the match happens).
- Opponent identity: `opponentClubId`, `opponentName`, `opponentShortName`.
- Byline: `assistantName` (the auxiliar's name).
- Standings snapshot (from `StandingsService`, positions implicit by sort order):
  opponent row and managed row — each `{position, points, played, won, drawn,
  lost, goalsFor, goalsAgainst}`.
- Form: opponent's last up-to-5 **played** fixtures (any competition round order),
  newest first, as `List<String>` of `"V"`/`"E"`/`"D"` from the opponent's POV —
  derived from `FixtureRepository` played fixtures.
- Player intel (see depth gates): `bestPlayers` `[{name, position, overall}]`,
  `topScorer` `{name, goals}` (nullable — opponent's best in the competition
  top-scorers list, absent if none scored yet).
- Tactical read (see depth gates): `formation`, `mentality`, `pressing`, `marking`
  (from `Club.getDefaultFormation()` etc., the AI tactical identity).
- `insights`: `List<String>` — pt-BR tactical recommendation sentences, composed
  deterministically (seeded by fixtureId) in a professional assistant's voice
  (NOT the Mastella commentary voice). Rule-based from the opponent's tactical
  identity vs. ours + strength comparison, e.g. "Eles pressionam alto — explore
  as costas da defesa com bolas longas."

All new payload fields are nullable / `@JsonSetter(nulls = AS_EMPTY)`-guarded per
the `AdvanceResponse` back-compat convention.

### Depth gates (auxiliar's individual attributes)

- **Always included** (any auxiliar hired): fixture context, byline, standings
  snapshot, form.
- **Avaliação de Jogadores (secondary):** `< 12` → `bestPlayers` has only the single
  top player by overall; `12–15` → top 3 by overall; `≥ 16` → top 3 **plus**
  `topScorer` with goal count.
- **Conhecimento Tático (primary):** `< 12` → no tactical read, no insights;
  `12–15` → formation/mentality/pressing/marking read, no insights; `≥ 16` →
  tactical read **plus** 2–3 `insights` sentences.

## Part D — Frontend

- **`types.ts`:** add `'MATCH_PREVIEW'` to the `AdvanceResponse.type` union; add
  `MatchPreviewData` type + nullable `matchPreview` field.
- **`newsMeta.tsx`:** title `Análise do adversário — {opponentName}`; icon
  `QueryStats` (distinct from existing types).
- **`AdvanceEventCard.tsx`:** new `result.type === 'MATCH_PREVIEW'` header + body
  block: byline ("por {assistantName}, Auxiliar Técnico"), opponent header with
  home/away + stadium, two-row standings mini-table (them vs. us), colored V/E/D
  form chips (green/grey/red), best players with the existing `ovrBadgeSx` badges,
  formation/mentality line, insights as highlighted quote paragraphs. Sections
  render only when their payload fields are present (depth gating is server-side).
- **`StaffPage.tsx`:** hired staff and candidates show their two attributes as
  labeled 1–20 badges (color tiers like OVR: e.g. ≥16 gold, ≥12 green, else grey).
  Frontend keeps its own role → label-pair map.

## Testing

- **Backend units:** `StaffEffectsService` anchor values (`a=10/14/17`) + clamps
  per effect; `StaffCandidateGenerator` pool draw, no-rehire-in-save, determinism,
  and generated fallback; `MatchPreviewService` — no assistant → no news,
  idempotency (advance twice → one preview), depth gating at attribute boundaries
  (11/12/15/16), form derivation (fewer than 5 played, V/E/D orientation), D−1
  `eventDate`, byline name.
- **Seed:** `staff-pool.json` validation test (role coverage ≥ 12 each, bounds,
  unique names).
- **Migration/IT:** backfill covered by an integration test asserting midpoint
  attributes on a pre-existing staff row path (or via the standard IT suite
  exercising hire + effects).
- **Frontend:** `newsMeta.test.ts` new case; `AdvanceEventCard.test.tsx` new block
  (full payload + minimal payload without gated sections); `StaffPage.test.tsx`
  attribute badges.
- Full suites before merge: `./gradlew test` (backend), `npx tsc -b` + `npx vitest
  run` (frontend).

## Out of scope

- AI clubs hiring/holding real staff rows (baseline stays virtual).
- Accuracy noise (a weak auxiliar is shallow, never wrong).
- New calendar event types / day-granular advance.
- Analysis for non-managed fixtures.
- Changes to staff wages, hiring flow UX, or the one-holder-per-role constraint.
