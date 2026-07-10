# Quick Fixes Bundle — Design

**Date:** 2026-07-10
**Status:** Approved (brainstormed with user)

## Context

Group 1 of the ten-item enhancement roadmap (`~/.claude/plans/imperative-strolling-parasol.md`):
four small, independent, low-risk items bundled together because none needs research and each
is a self-contained fix in code already mapped during the roadmap's exploration phase.

## 1. Short club names in matches

`Club.commonName`/`shortName` already exist and are already used correctly in club browsing,
standings, and the transfer market (`x.commonName ?? x.name` fallback pattern, e.g.
`ClubViewPage.tsx:109`, `TablePage.tsx:58`). The gap is the live-match header and the
fixture-result/news feed, which only ever render the full legal `Club.name`.

- **Backend:** `FixtureResultResponse` and `LiveMatchState` each gain a `shortName` field per
  club side (`homeClubShortName`/`awayClubShortName`), populated as
  `club.getCommonName() != null ? club.getCommonName() : club.getName()` — the identical
  fallback rule already used elsewhere. No migration; purely a response-mapping addition at
  the existing construction sites (`FixtureSimulator`, `LiveMatchService.toState`,
  `SeasonAdvanceService` wherever `FixtureResultResponse` is built).
- **Frontend:** `LiveMatchPage.tsx:153` and `AdvanceEventCard.tsx:93,95` render
  `state.homeClubShortName ?? state.homeClubName` (and the away equivalent) instead of the
  raw full name.

## 2. Squad cap 30 → 40

`ContractService.MAX_SQUAD_SIZE` (`contract/ContractService.java:25`) and
`TransferService.MAX_SQUAD_SIZE` (`transfer/TransferService.java:39`) are two independently
declared constants, both currently `30`, both gating `ErrorCode.SQUAD_FULL`.

- New `common/SquadRules.java`: a small utility class holding
  `public static final int MAX_SQUAD_SIZE = 40;` (lives in `common` since `contract` and
  `transfer` are sibling packages with no natural owner between them).
- `ContractService`/`TransferService` reference `SquadRules.MAX_SQUAD_SIZE` instead of their
  own constants; the two local declarations are removed.
- Every IT that fills a squad to exactly 30 players to trigger `SQUAD_FULL` is bumped to 40.
- **Explicitly out of scope:** `YouthAcademyService.promote()`'s uncounted squad growth —
  confirmed with the user, left as pre-existing behavior.

## 3. Mobile squad row: name-first, tap-to-expand

Today's mobile card row (`SquadPage.tsx:521-542`) packs status button, name (+ badges),
position chip, OVR chip, and `FitnessBar` into one unwrapped `Stack direction="row"`; the name
button has no `noWrap`, so long names + badges visually spill over the fixed-width chips and
bar on narrow screens.

Redesign into two lines, modeled directly on `TablePage.tsx:222-271`'s `MobileStandingRow`
(chevron `IconButton` + MUI `Collapse in={open} unmountOnExit` + a second detail row):

- **Primary row (always visible):** status button, player name with `noWrap` + text overflow
  ellipsis (can never overflow, by construction), a chevron `IconButton` on the trailing edge.
  Tapping the name still opens `PlayerDetails` (unchanged behavior); the chevron is the sole
  expand/collapse trigger, so the two gestures never conflict.
- **Secondary row (collapsed by default, one local `useState<boolean>` per row):** position
  chip, OVR chip, `FitnessBar`, availability badge, "Listado" badge — everything that
  previously crowded the single row.

Desktop/table layout (`isMobile === false`) is unchanged.

## 4. Manager name + nationality at career creation

Today `POST /save-games` takes an empty body; `SaveGameService.createCareer` hardcodes the
managed club to `"CRI"`; there is no "manager" concept anywhere in the domain.

**Backend:**
- Migration `V23__manager_identity.sql`: `ALTER TABLE save_games ADD COLUMN manager_name
  VARCHAR(80) NOT NULL DEFAULT '', ADD COLUMN manager_nationality VARCHAR(40) NOT NULL DEFAULT
  '';` — additive, same style as `V13`/`V21`/`V22`; defaults keep any pre-existing save rows
  valid with no backfill needed.
- `SaveGame` entity: `managerName`/`managerNationality` fields + getters.
- `CreateSaveGameRequest` becomes `record CreateSaveGameRequest(@NotBlank String managerName,
  @NotBlank String managerNationality)` (was an empty record).
- `SaveGameService.createCareer(UUID userId, CreateSaveGameRequest req)` persists both onto
  the new `SaveGame`. Club choice stays hardcoded to `"CRI"` — explicitly out of scope, per
  the user's original ask (name + nationality only).
- `SaveGameResponse` gains `managerName`/`managerNationality` so the dashboard can read them
  back.

**Frontend:**
- New `features/career/NewCareerDialog.tsx`: a MUI `Dialog` with two fields — manager name
  (`TextField`, required) and nationality (MUI `Autocomplete`, required — the app's first use
  of `Autocomplete`, backed by a small static curated list of country names in Portuguese:
  Brasil, Argentina, Uruguai, Paraguai, Chile, Colômbia, Portugal, Espanha, Itália, França,
  Alemanha, Inglaterra — matching the existing free-string nationality convention already
  used for players, e.g. seed data's `"nationality": "Brasil"`). Submit is disabled until both
  fields are non-empty.
- `Layout.tsx:288-290`'s "Nova Carreira" menu item opens this dialog instead of firing the
  `createCareer` mutation directly; the mutation now runs on dialog submit, passing
  `{ managerName, managerNationality }`.
- `api/career.ts`'s `createCareer(token)` becomes
  `createCareer(req: CreateSaveGameRequest, token)`.

**Display:** `DashboardPage.tsx`'s existing save-game summary `Card` (Temporada/Competição/
Clube/Rodada/Saldo `StatRow`s) gains one more: `<StatRow label="Técnico:" value={
  \`${saveGame.managerName} (${saveGame.managerNationality})\` } />` — reuses the exact
existing `StatRow` pattern, no new component.

## Out of scope (explicit)

- Club selection at career creation (stays hardcoded to Criciúma).
- A squad-size guard on youth promotion.
- Any manager-profile surface beyond the one new dashboard stat row.
- Country list beyond the curated set above (not a full ISO-3166 country database).

## Testing

- **Backend:** response-mapping tests asserting `shortName` fields fall back to full name
  when `commonName` is null; `SquadRules.MAX_SQUAD_SIZE` referenced by both services (existing
  30-player IT fixtures bumped to 40); `V23` migration + a `SaveGameService`/controller IT
  asserting persisted manager fields round-trip and blank name/nationality is rejected
  (400/422 via existing bean-validation → `GlobalExceptionHandler` path).
- **Frontend:** `LiveMatchPage`/`AdvanceEventCard` tests asserting short-name-with-fallback
  rendering; a `SquadPage` mobile test asserting the collapsed row shows only the name (no
  overflow) and the chevron reveals position/OVR/fitness/badges; `NewCareerDialog` test
  (renders, disabled submit until both fields set, submits with both values, calls
  `createCareer` with the right payload); `DashboardPage` test asserting the new "Técnico:"
  stat row renders from `saveGame.managerName`/`managerNationality`.
