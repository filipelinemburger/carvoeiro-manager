# Staff System — Design

**Date:** 2026-07-15
**Status:** Approved

## Goal

Let the manager hire a small technical staff — one Assistant Manager, one Coach ("Preparador
Físico"), one Scout ("Olheiro"), and one Physio ("Fisioterapeuta") — each providing a small,
"thin" passive effect on an existing system, funded by a monthly wage. AI clubs get a fixed
baseline effect in every role automatically, with no roster, no hiring flow, and no wage cost.

This is the second of three gaps identified against the original Phase 3 vision in
`docs/superpowers/specs/2026-06-26-carvoeiro-manager-design.md` ("staff: assistant manager,
coaches, scouts, physios with effects"). It ships after Training
(`docs/superpowers/specs/2026-07-14-training-system-design.md`) and before Promotion/Relegation.

## Background

- **Nothing staff-related exists today.** A repo-wide search for "staff", "coach", "scout", or
  "physio" across `backend/src` and `frontend/src` returns no application-code hits.
- **Naming collision, resolved before design:** `Técnico` already means two different things in
  this codebase — `DashboardPage.tsx:79` uses it as the label for the user's own manager persona
  ("Técnico: {managerName} ({managerNationality})"), and `PlayerDetails.tsx:23` uses it as the
  "Técnico" (Technical) attribute-group name on the player board. No staff role uses the bare
  word "Técnico" in this design; the four roles are named `Auxiliar Técnico`, `Preparador
  Físico`, `Fisioterapeuta`, and `Olheiro` instead.
- **Finance already has a clean, small extension point.**
  `FinanceService.processMonth(UUID saveGameId, LocalDate monthDate)`
  (`backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java:181-219`) runs once
  per in-game month per club: sums a wage bill via the private `monthlyWageBills(...)`
  (`FinanceService.java:282-289`), computes sponsorship/upkeep via `FinanceCalculator`, nets
  `sponsorship - wages - upkeep`, and calls `club.adjustBalance(net)`
  (`FinanceService.java:204-206`, `Club.adjustBalance`, `Club.java:125`). For the managed club it
  also records ledger rows via `FinanceTransactionType.SPONSOR/WAGES/UPKEEP`
  (`FinanceService.java:209-214`). `FinanceCalculator`
  (`backend/src/main/java/br/com/carvoeiromanager/finance/FinanceCalculator.java`) is a stateless
  `@Component` holding the pure formulas — `monthlyWage` (line 76-78), `monthlySponsorship` (line
  84-86), `monthlyUpkeep` (line 92-94) — a `staffWage(...)` formula fits the same pattern.
- **Injury/fitness math has no single injection point.**
  `FitnessCalculator.injuryChance(int effectiveFitness, int age)`
  (`backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java:61-64`) is a pure
  function called from multiple independent sites: via
  `MatchOutcomeApplier.applyInjuries` (`MatchOutcomeApplier.java:159-173`, itself called from
  `FixtureSimulator.java:126-127`) and directly from `LiveMatchEngine.java:433,451,456`. A physio
  effect must be threaded as an extra parameter into `injuryChance` and passed in at every call
  site — mechanical, but touches more files than any other role's hook.
- **The free-agent hire flow is the template for staff hiring.**
  `FreeAgentsPage.tsx`'s `FreeAgentsPanel`
  (`frontend/src/features/contract/FreeAgentsPage.tsx:35-157`) fetches a candidate list
  (`useQuery(['freeAgents', ...])`, line 45), renders one `Card` per candidate with identity +
  computed demand (line 103) and an inline wage/years form (lines 110-136), and commits via
  `useMutation` wrapping `signFreeAgent(...)` (lines 47-53) fired by a "Contratar" button (lines
  137-147). Backend: `ContractController` (`GET .../free-agents`, `POST
  .../free-agents/{playerId}/sign`) delegates to `ContractService.getFreeAgents`/`signFreeAgent`
  (`backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java:51-59,61-77`).
  Candidate generation precedent: `FreeAgentGenerator.generate(UUID saveGameId, Random rng)`
  (`FreeAgentGenerator.java:35-68`), deterministic RNG-seeded rolls.
- **`TrainingService.applyTraining`'s bonus table is a small, self-contained switch**
  (`backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java:24-64`, bonus
  switch at lines 29-33) — a coach effect fits as one new parameter added into that method, with
  two call sites to update (`SeasonRolloverService.java:255` for managed-club players,
  `SeasonRolloverService.java:258` for AI-club players via the existing
  `TrainingService.heuristicFocusFor(Position)` heuristic, lines 67-74). This AI-heuristic branch
  is the direct precedent for how every staff role handles AI clubs (see below).
- **`Club.java`** (`backend/src/main/java/br/com/carvoeiromanager/club/Club.java:17-126`) has no
  existing collection field for anything roster-like — `Player.clubId` is a foreign key on
  `Player`, not an embedded list on `Club`. A `Staff` table follows the same FK pattern.
  `Club.reputation` (line 59-60) is used only by finance (gate income, sponsorship) and club
  listing/sorting — never by match-strength calculation
  (`TeamStrengthCalculator.compute`, `TeamStrengthCalculator.java:24-75`, confirmed to have no
  `reputation` term) — so there is no existing "AI clubs get baked-in coaching quality" precedent
  to reuse; the AI-baseline behavior below is modeled on Training's `heuristicFocusFor` pattern
  instead.

## Backend changes

**Data model** — new migration `V27__staff.sql` creates a `staff` table: `id, save_game_id,
club_id, role, quality, name, wage`. `role` and `quality` are `VARCHAR`, `@Enumerated(STRING)`.

New enums:
- `StaffRole { ASSISTENTE, PREPARADOR_FISICO, OLHEIRO, FISIOTERAPEUTA }`
- `StaffQuality { REGULAR, BOM, EXCELENTE }` (3 tiers, matching `TrainingIntensity`'s precedent —
  not a 1-99 numeric stat like `Player.overall`).

New `Staff` entity (mirrors `Player`'s `clubId`-FK shape) and `StaffRepository` (`findByClubId`,
`findByClubIdAndRole`). One slot per role: hiring a candidate for a role that already has a
holder deletes the old `Staff` row and inserts the new one, in one transaction.

**Candidates are not persisted.** A new `StaffCandidateGenerator.generate(UUID saveGameId, UUID
clubId, StaffRole role, LocalDate onDate, int count)` deterministically rolls `count` (3)
candidates — name, `StaffQuality`, and a wage scaled by quality — seeded from `(saveGameId,
clubId, role, onDate)` so the offer is stable within a game-day but changes as the season
advances (mirrors `FreeAgentGenerator`'s RNG-seeding style, but generates on-demand rather than
persisting a shared pool, since staff candidates are per-club and never contested by AI clubs).

**`StaffEffectsService`** is the single facade every consuming system calls through — the
"convert a hired `Staff` row into a numeric effect" logic lives in exactly one place:
- `trainingBonusFor(UUID clubId): int` — `+0 / +1 / +2` for Regular/Bom/Excelente
  `PREPARADOR_FISICO`, added on top of `TrainingService`'s existing Leve/Normal/Intensa bonus.
- `injuryRiskMultiplierFor(UUID clubId): double` — `×1.0 / ×0.85 / ×0.7` for the club's
  `FISIOTERAPEUTA`, multiplied into `FitnessCalculator.injuryChance`'s result.
- `wageDemandMultiplierFor(UUID clubId): double` — `×1.0 / ×0.95 / ×0.9` for the club's
  `OLHEIRO`, multiplied into `ContractValuation.wageDemand`'s result — scoped to free-agent
  signings only (`ContractService.signFreeAgent`'s valuation call), not the full
  transfer-negotiation engine, to keep this role's effect narrow.
- `sponsorshipMultiplierFor(UUID clubId): double` — `×1.0 / ×1.05 / ×1.1` for the club's
  `ASSISTENTE`, multiplied into `FinanceCalculator.monthlySponsorship`'s result.

Each method: if the club is the save's managed club, look up its `Staff` row for that role via
`StaffRepository.findByClubIdAndRole` and resolve `StaffQuality` to the effect value (neutral —
`+0`/`×1.0` — if no row exists for that role). If the club is any other (AI-controlled) club,
skip the repository lookup entirely and return the fixed AI baseline — equivalent to "Bom"
quality in every role — matching `TrainingService.heuristicFocusFor`'s no-roster,
always-available shape.

**Integration points** (one small edit each, all consuming `StaffEffectsService` rather than
`Staff`/`StaffRepository` directly):
- `TrainingService` gains a `StaffEffectsService` constructor dependency; `applyTraining(...)`'s
  signature is unchanged (it already receives the `Player`, so it resolves the club internally via
  `player.getClubId()`) and adds `staffEffectsService.trainingBonusFor(player.getClubId())` into
  the computed bonus before clamping.
- `FitnessCalculator.injuryChance(...)`'s callers (`MatchOutcomeApplier.applyInjuries` and the two
  `LiveMatchEngine` call sites) multiply the returned chance by
  `staffEffectsService.injuryRiskMultiplierFor(clubId)` before rolling — `FitnessCalculator`
  itself stays a pure, staff-unaware function; the multiplication happens at each call site.
- `ContractService.signFreeAgent(...)` multiplies `contractValuation.wageDemand(...)`'s result by
  `staffEffectsService.wageDemandMultiplierFor(managedClubId)` before validating/applying terms.
- `FinanceService.processMonth(...)` multiplies each club's `monthlySponsorship(...)` result by
  `staffEffectsService.sponsorshipMultiplierFor(clubId)` before netting the monthly balance
  change.

**Finance**: new `FinanceTransactionType.STAFF_WAGES`. `FinanceService.processMonth` sums each
club's hired staff wages the same way `monthlyWageBills` sums player wages (a sibling
`monthlyStaffWageBills(saveGameId)` helper), nets it out, and records one more managed-club-only
ledger row.

**API** (mirrors `SquadController`'s existing `/clubs/{clubId}` sub-resource shape):
- `GET /api/v1/save-games/{saveGameId}/clubs/{clubId}/staff` — current roster, one entry per
  role (`null` for an unfilled slot).
- `GET /api/v1/save-games/{saveGameId}/clubs/{clubId}/staff/candidates?role={role}` — 3 generated
  candidates for that role.
- `PUT /api/v1/save-games/{saveGameId}/clubs/{clubId}/staff/{role}` — hire (body: the chosen
  candidate's rolled name/quality/wage), replacing any existing holder of that role.

## Frontend changes

New **"Comissão Técnica"** page (route/navigation placement decided during planning — likely a
new top-level nav entry alongside Elenco/Clubes/Mercado, since staff is club-wide, not
squad-scoped). Four role sections, each showing the current holder (name, quality badge, wage) or
an empty-slot state, with a "Contratar" action that opens the 3 generated candidates — name,
quality badge, wage — for that role, mirroring `FreeAgentsPage.tsx`'s card-list-with-inline-hire
pattern. Hiring one candidate replaces the section's current holder and refetches the roster
query.

## Testing

- **Backend unit**: `StaffEffectsServiceTest` — pure per-role tests, `StaffQuality` tier → effect
  value, and the AI-baseline fallback (no `Staff` row, non-managed club) for all 4 methods.
- **Backend integration**: hire-then-replace-in-role persistence; each of the 4 integration
  points (training bonus, injury-risk multiplier, free-agent wage discount, sponsorship
  multiplier) picking up a hired staff member's effect end-to-end; AI clubs never persisting a
  `Staff` row while still receiving the baseline effect.
- **Frontend**: RTL coverage for the Comissão Técnica page's browse/hire/replace flow.
- Full backend suite (`./gradlew test`) and full frontend suite
  (`npx tsc --noEmit && npx vitest run`) green, per project convention.

## Out of scope

- Multiple staff per role, or any effect-stacking — exactly one slot per role, replacing on hire.
- Any new "hidden information" or fog-of-war mechanic for the Scout role — its effect is scoped
  to a wage-demand discount on free-agent signings, not real scouting/discovery mechanics.
- The Scout's discount applying to the full transfer-negotiation engine (`TransferNegotiator`,
  incoming/outgoing offers) — free-agent signings only, in this pass.
- Staff contracts, aging, retirement, or departure — a hired staff member stays indefinitely
  until the manager replaces them; no season-rollover tick analogous to player contract decay.
- Any UI or mechanic exposing the AI baseline to the player (e.g. showing "AI clubs have Bom
  Fisioterapeuta") — it's a backend-only constant, invisible in the UI, exactly like Training's
  AI heuristic.
