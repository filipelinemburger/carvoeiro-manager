# Carvoeiro Manager — Phase 2C-2: Contracts & Free Agents — Design Spec

**Date:** 2026-06-27
**Status:** Approved (design); ready for implementation plan.
**Builds on:** Phase 2A (tactics), 2B (finances/wages), 2E (multi-season rollover), 2C-1 (transfer market & negotiation).

## 1. Goal

Close the transfer system by giving every player a **contract** (length in years + the
already-existing wage) and introducing **free agents**. Managers renew their own players
before deals expire, sign free agents for no fee, and now agree personal terms (wage +
length) when buying. Contracts tick down each season; the AI keeps its own squads intact
while the manager must actively re-sign or lose players to free agency.

This is the second half of Phase 2C (2C-1 shipped transfers/negotiation). After this slice,
2C is complete.

## 2. Locked product decisions

1. **Expiry rule — AI auto-renews, managed lapses.** At rollover, AI clubs automatically
   re-sign their own expiring players (the world stays stable). A managed-club player whose
   contract reaches zero becomes a **free agent** if the manager did not renew in time.
2. **Free-agent pool — seeded pool + your lapses.** A small pool of unsigned free agents is
   created at career start; the manager's lapsed players join it each rollover. (No AI
   releases.) A replenish-to-floor keeps the pool from dying over many seasons.
3. **Acceptance — wage-threshold accept/reject.** A player accepts a contract offer (renewal
   or free-agent signing) iff the offered wage meets a deterministic demand (pure function of
   overall/age); otherwise it is rejected and the demand is surfaced. One round-trip, no
   counter state machine.
4. **Buying negotiates personal terms.** A buy offer carries a proposed wage + length in
   addition to the fee. The fee negotiation (2C-1) is unchanged; when the fee completes, the
   player's wage demand is checked — if the offered wage is too low, the **transfer falls
   through** (no money moves, player stays).

## 3. Scope

**In scope**

- `contract_years` on every player; `club_id` becomes nullable (free agents have no club).
- `ContractValuation` (pure): wage demand, acceptance test, default length.
- Renewals of managed players (endpoint + service + UI).
- Free-agent pool listing + signing (endpoints + service + UI).
- Buy-with-personal-terms: extend the 2C-1 offer/executor with wage + length and a
  terms gate at fee completion.
- Rollover: contract tick, AI auto-renew, managed lapse → free agent, free-agent
  aging/retirement, replenish-to-floor.
- Career-creation seeding: staggered contract lengths + initial free-agent pool.
- End-of-season summary: an "expired contracts" section.

**Out of scope (deferred / excluded)**

- AI clubs signing free agents or bidding on free agents (AI auto-renews; it does not shop
  the pool).
- Contract history / multiple contracts per player (single set of fields on `Player`).
- Signing bonuses, agent fees, release clauses, loans, contract-driven morale.
- Negotiating personal terms as a multi-round counter (acceptance is a single threshold).

## 4. Architecture

Contract data lives **on `Player`** (denormalized, fields-on-entity — same pattern as `wage`
from 2B and the aging fields from 2E). No new entity. A free agent is a player with
`club_id = NULL` belonging to a save game; club-scoped queries (squad, lineup, finance,
match) already filter by `clubId`, so free agents fall out of them automatically. Only the
all-players iterators (rollover, transfer market filter) gain a null-guard.

Wage demand is **not stored** — it is a pure function computed wherever an offer is
evaluated. `Player.wage` keeps its 2B meaning ("current wage paid by the current club");
it is irrelevant while a player is a free agent and is set on signing.

### 4.1 Data model — V7 migration (additive)

```sql
-- players: free agents have no club; everyone has a contract length
ALTER TABLE players ALTER COLUMN club_id DROP NOT NULL;
ALTER TABLE players ADD COLUMN contract_years INT NOT NULL DEFAULT 0;

-- transfer_offer: personal terms attached to an OUTGOING buy offer (null for AI/incoming bids)
ALTER TABLE transfer_offer ADD COLUMN wage NUMERIC(14,2);
ALTER TABLE transfer_offer ADD COLUMN contract_years INT;
```

`club_id` keeps its existing (non-cascading) FK to `clubs(id)`; an FK permits NULL, so only
the NOT NULL constraint is dropped. `contract_years` defaults to 0 so the migration is safe
on existing rows; career creation overwrites it with staggered values.

### 4.2 Entity changes — `Player`

- Field `int contractYears` (column `contract_years`) + `getContractYears()` /
  `setContractYears(int)`.
- `boolean isFreeAgent()` → `clubId == null`.
- `setClubId(UUID)` already exists (used by 2C-1); it must accept `null`.
- The private constructor / `create(...)` factory are unchanged (contract length is set
  explicitly by the seeder after creation, like wage is in 2B).

## 5. `ContractValuation` (pure, no RNG)

A `@Component` depending on `FinanceCalculator` (both pure), so demand stays calibrated to
the 2B wage scale and cannot drift.

```
DEMAND_PREMIUM = 1.15            // a player wants ~15% above baseline to commit
DEFAULT_CONTRACT_YEARS = 3
MIN_CONTRACT_YEARS = 1
MAX_CONTRACT_YEARS = 5

wageDemand(overall, age)        = scaled( financeCalculator.weeklyWage(overall, age) * DEMAND_PREMIUM )
acceptsWage(overall, age, offered) = offered != null && offered.compareTo(wageDemand(overall, age)) >= 0
defaultContractYears()          = DEFAULT_CONTRACT_YEARS
```

`weeklyWage(overall, age) = overall² × 20 × ageFactor` (ageFactor 0.60 ≤20 / 1.00 prime /
0.80 ≥32), so `wageDemand` is in the same currency as the player's seeded wage. Because the
2E age curve raises a young player's overall each season, their demand climbs — re-signing a
rising star costs progressively more.

All amounts are `BigDecimal` scaled to 2, consistent with `FinanceCalculator`.

## 6. REST API

All under `/api/v1/save-games/{saveGameId}/...`, ownership-checked via
`saveGameService.requireOwned(saveGameId, userId)` (404 on foreign saves, as elsewhere).

### 6.1 Renewals (managed players) — not window-gated

`POST /players/{playerId}/renew`
Request: `RenewContractRequest { BigDecimal wage, int contractYears }`
- 404 if the player is not in this save or not at the managed club.
- 422 `INVALID_CONTRACT` if `contractYears` ∉ [1,5] or `wage` ≤ 0.
- 422 `PLAYER_REJECTS_TERMS` (message includes the demand) if `!acceptsWage(...)`.
- On success: set `wage` + `contractYears`; respond `200` with the updated player contract
  view.

Renewals are allowed any time the save is active (re-signing your own player is not an
acquisition, so it is independent of the transfer window).

### 6.2 Free agents

`GET /free-agents` → `List<FreeAgentResponse>` (players with `club_id = NULL` in this save),
each `{ playerId, name, position, age, overall, wageDemand }`, sorted by overall desc.

`POST /free-agents/{playerId}/sign`
Request: `SignFreeAgentRequest { BigDecimal wage, int contractYears }`
- 404 if the player is not a free agent in this save.
- 422 `TRANSFER_WINDOW_CLOSED` if the window is closed (signing is an acquisition — gated
  like buying, via the existing `TransferWindow`).
- 422 `SQUAD_FULL` if the managed squad already has `MAX_SQUAD_SIZE` (30) — reuse
  `requireSquadRoom`.
- 422 `INVALID_CONTRACT` / `PLAYER_REJECTS_TERMS` as in renewals.
- On success: `clubId = managedClubId`, set `wage` + `contractYears`; the player leaves the
  pool. **No fee, no seller, no ledger entry** — the cost is the ongoing wage (processed by
  `FinanceService` from the next round).

### 6.3 Buying with personal terms (extends 2C-1)

`MakeOfferRequest` gains `BigDecimal wage` + `int contractYears`. `TransferController`'s
`POST /transfers/offers` passes them through to `TransferService.makeOffer`.

- `makeOffer(saveGameId, userId, playerId, fee, wage, contractYears)`:
  - existing fee guards unchanged (window, funds, squad room, not-own-player);
  - validate `contractYears` ∈ [1,5] and `wage` > 0 (422 `INVALID_CONTRACT`);
  - persist `wage` + `contractYears` on the `TransferOffer`;
  - run the existing fee negotiation (`respondToBuy`). On `ACCEPT`, completion runs the
    **terms gate** (below); on `COUNTER`/`REJECT`, behaviour is unchanged.
- `acceptCounter(...)`: unchanged guards, then completion runs the terms gate.
- **Terms gate (at fee completion, both paths):** before `transferExecutor.execute`, check
  `contractValuation.acceptsWage(player.overall, player.age, offer.wage)`. If it fails, the
  deal **falls through**: `offer.markRejected()`, no `execute`, no money moves, player stays;
  the response carries the demand (UI surfaces it). The market list already exposes
  `wageDemand`, so a lowball is a deliberate choice.
- `TransferExecutor.execute(...)` additionally sets the bought player's `wage = offer.wage`
  and `contractYears = offer.contractYears` (alongside the existing club move + balance
  adjustments + managed-lineup cleanup from 2C-1).

Incoming AI bids (selling) are unchanged: `wage`/`contract_years` stay null on INCOMING
offers; when you sell, the player keeps their contract fields and moves to the AI buyer
(whose own terms are unmodeled).

`TransferOfferResponse` gains `wage` + `contractYears` (nullable) so the UI can show the
personal terms on an outgoing offer.

## 7. Rollover (extends `SeasonRolloverService`)

Within the existing transactional rollover, after the age curve / retirement partition and
**before** fixture regen, run a contract pass over players (id-sorted, the existing seeded
`rng` for this save+year):

For each carried-over player (not retiring):
1. `contractYears = contractYears - 1`.
2. If `contractYears <= 0`:
   - **AI player** (`clubId != managedClubId` and `clubId != null`): auto-renew →
     `contractYears = 2 + rng.nextInt(3)` (2–4), stays at club.
   - **Managed player** (`clubId == managedClubId`): lapse → `clubId = null`,
     `contractYears = 0`; record for the end-of-season summary as an expired contract.

Free-agent handling (players already `clubId == null` at rollover start): they age via the
existing curve and are subject to the existing retirement probability — a retiring free
agent is deleted and **not** replaced (no club to replace into). Their `contractYears` stays
0. (Free agents must be skipped by the managed/AI `clubId.equals(...)` branches — null-guard.)

Newgens that replace retirees (2E) get a fresh seeded term `2 + rng.nextInt(3)`.

**Replenish-to-floor:** after lapses and retirements, if the free-agent pool size <
`FREE_AGENT_FLOOR` (6), generate fresh free agents (newgen-style, `clubId = null`,
`contractYears = 0`) up to the floor, using the same seeded `rng`. These are generated
talent, not AI releases.

FK ordering is unchanged from 2E: events + fixtures purged and flushed, managed lineup
cleared and flushed, **before** any player delete; the contract pass mutates/derives but the
only deletes are retirements (already FK-safe) plus newly-generated free agents (inserts).
Lapsing sets `club_id = null` (an update), which is FK-safe.

`RolloverResponse` gains an `expiredContracts` list (player name/position) for the summary.

## 8. Career-creation seeding (extends `SaveGameService`)

- After seeding each player's wage (2B), set a **staggered** `contractYears` so deals do not
  all expire together: deterministic per save, e.g. a `Random(saveGameId.msb)` drawn in
  id-order giving `1 + rng.nextInt(4)` (1–4).
- Create an initial **free-agent pool** of `INITIAL_FREE_AGENTS` (10): `clubId = null`,
  `contractYears = 0`, varied positions/ages/quality, names from the existing newgen name
  pool, generated via a seeded `Random(saveGameId.msb ^ FREE_AGENT_SEED)` so creation is
  reproducible. (Reuse `NewgenGenerator` where it fits; otherwise a small dedicated
  `FreeAgentGenerator` sharing the name pool and `OverallCalculator`.)

## 9. Frontend

- **Squad page** — show each player's **contract years left** and **wage demand**; an inline
  **Renew** form (wage + length 1–5) calling `POST .../players/{id}/renew`; players with ≤1
  year highlighted as a warning. Hardening: the 2C-1 note about `players.find(...)!` becomes
  relevant now that rosters mutate via signings — use `p?.name ?? id` style guards.
- **Free Agents page** (`/free-agents`) — table of the pool (name/pos/age/overall/wage
  demand) with a **Sign** form (wage + length); a transfer-window-closed banner reusing
  `isTransferWindowOpen`; a dashboard **"Livres"** link.
- **Transfer market make-offer form** — add **wage** + **contract length** inputs beside the
  fee; show the player's wage demand so offers are informed; render the personal terms on
  outgoing offers.
- **End-of-season summary** — an **"Expired contracts"** section listing lapsed players from
  `RolloverResponse.expiredContracts`.
- Types/client helpers: `FreeAgent`, `RenewContractRequest`, `SignFreeAgentRequest`,
  extended `MakeOfferRequest`/`TransferOffer`; client funcs `getFreeAgents`, `signFreeAgent`,
  `renewContract`; money via the existing `formatBRL`.

## 10. Constants (locked initial calibration)

| Constant | Value | Where |
|---|---|---|
| `DEMAND_PREMIUM` | 1.15 | `ContractValuation` |
| `DEFAULT_CONTRACT_YEARS` | 3 | `ContractValuation` |
| `MIN_CONTRACT_YEARS` / `MAX_CONTRACT_YEARS` | 1 / 5 | offer + renewal + signing validation |
| AI auto-renew / newgen term | `2 + rng.nextInt(3)` (2–4) | rollover |
| `FREE_AGENT_FLOOR` | 6 | rollover replenish |
| `INITIAL_FREE_AGENTS` | 10 | career creation |
| `FREE_AGENT_SEED` | `0xFA11AC0DL` (fixed long XORed into the save seed) | creation FA generation |
| `MIN_SQUAD_SIZE` / `MAX_SQUAD_SIZE` | 14 / 30 | reused from 2C-1 |

## 11. Error handling

Standard error JSON `{ error, message, status }`. Codes: `PLAYER_REJECTS_TERMS` (422, carries
the demand in the message), `INVALID_CONTRACT` (422), reused `TRANSFER_WINDOW_CLOSED`,
`SQUAD_FULL`, `PLAYER_NOT_FOUND`/404 for missing or non-managed/non-free-agent players. A buy
that falls through on terms is **not** an error response — it is a successful call returning a
`REJECTED` offer with the demand, so the UI can re-offer.

## 12. Testing

**Backend (JUnit5 + AssertJ; Testcontainers ITs `@SpringBootTest @ActiveProfiles("test")
@Import(PostgresTestContainer.class)`):**
- `ContractValuationTest` (unit): demand = `weeklyWage × 1.15` at sample (overall, age);
  acceptance boundary (offered just below/at/above demand); age-factor effect; scale 2.
- Renewal IT: accept sets wage+years; lowball → `PLAYER_REJECTS_TERMS`, unchanged;
  re-signing a player whose overall grew costs more; non-managed player → 404.
- Free-agent sign IT: success moves player to club + sets terms + leaves pool; window
  closed → 422; squad full → 422; lowball → 422; non-free-agent → 404.
- Buy-with-terms IT: lowball wage at fee completion → offer `REJECTED`, balances unchanged,
  player still at seller; adequate wage → executes and sets bought player's `wage` +
  `contractYears`; via both `makeOffer` ACCEPT and `acceptCounter`.
- Rollover IT: `contract_years` decrements; AI player at 0 auto-renews (still at club, term
  2–4); managed player at 0 → `club_id` null, appears in `expiredContracts`; a free agent
  ages and can retire (removed, no replacement); pool replenished to floor when below 6;
  determinism — same save+year → identical contract/FA outcomes.
- Seeding IT: every seeded player has `contract_years` ∈ [1,4]; exactly 10 free agents
  created with `club_id` null.
- V7 migration applies on the Testcontainers boot.

**Frontend (Vitest + RTL):** free-agents page render + sign form; squad renew form +
expiring highlight; market make-offer wage/length inputs; summary expired-contracts section.

## 13. Determinism

The match engine is untouched. `ContractValuation` is pure (no RNG). All randomness uses
save-derived seeds, matching 2E/2C-1: career-creation contract stagger
(`Random(saveGameId.msb)`) and FA pool (`saveGameId.msb ^ FREE_AGENT_SEED`); rollover
auto-renew terms, FA aging, and replenishment use the existing per-save+year rollover `rng`.
No call introduces wall-clock or unseeded randomness.

## 14. Forward compatibility

- Contracts and the free-agent pool generalize cleanly to 2D (multiple competitions): they
  are save-scoped, not competition-scoped.
- The nullable `club_id` + `isFreeAgent()` is the foundation for future loans / releases if
  ever wanted (not in scope).

## 15. Acceptance

A user can: see contract years + wage demand on the squad; renew a player (and be refused a
lowball); lose a player to free agency by ignoring an expiry, then re-sign them or another
free agent from the pool during the window; buy a player by agreeing both a fee and personal
terms, and watch a lowball wage sink the deal. Across seasons the AI keeps its squads, the
free-agent pool stays stocked, and all suites stay green with determinism intact.
