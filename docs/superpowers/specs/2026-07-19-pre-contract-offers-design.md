# Pre-Contract Offers — Design Spec

**Date:** 2026-07-19
**Cycle:** 2 of 3 (real free agents ✓ → **pre-contract offers** → watchlist)

## Goal

Let the manager sign a pre-contract with a rival club's player whose contract has
≤6 months left (Bosman-style): the player agrees terms now, sees out his final months,
and joins for free when his contract lapses at season rollover. Symmetrically, AI clubs
can poach the manager's own expiring players, with a defense window to fight back.

## Context (verified against current code)

- Contracts are an integer `contractYears` on `Player`, ticked down once per season in
  `SeasonRolloverService` (the only place contracts expire). Rollover happens mid-January.
- Today, AI players whose contracts hit 0 at rollover **silently auto-renew for 2–4
  years, unconditionally** (`SeasonRolloverService` contract-tick pass) — no AI player is
  ever actually poachable. Managed-club players whose contracts hit 0 lapse into the
  free-agent pool (`clubId = null`).
- The season calendar already has `TRANSFER` event days (`SeasonEventType.TRANSFER`),
  processed in `SeasonAdvanceService.processTransfer` — these are the game's "market
  days" and the natural hook for all periodic AI decisions in this feature.
- `ContractService.renewContract(saveGameId, userId, playerId, wage, contractYears)`
  already exists and takes wage + years. `ContractValuation.wageDemand(overall, age)` is
  the existing wage-demand formula.
- Cycle 1 removed all procedural free-agent generation; the pool only shrinks today.

## Design decision: derived expiry, not a stored date

Contracts only change in whole-year steps and only tick at rollover, so every
contract's expiry date is already a known calendar date — the rollover at the end of
its final year. No new date column. "≤6 months left" is computed:

> **eligible** ⇔ `contractYears == 1` AND `saveGame.currentDate >= July 15 of the
> current season year` AND the player has no active `PreContractAgreement` and was not
> already renewed this season.

July 15 is exactly 6 months before the mid-January rollover. The eligibility window
runs July 15 → rollover, deliberately outside transfer windows (that is the real-world
point of a pre-contract). Rejected alternatives: a stored `contract_expires_on` column
(redundant — could only ever hold the season boundary; drift risk at every contract
write site) and continuous mid-season expiry dates (breaks the season-boundary model
the whole game is built on).

## 1. AI proactive renewals (replaces the silent auto-renew)

On the **first TRANSFER day on/after July 15** each season, every AI club decides, per
final-year player (deterministic seeded RNG, same seeding discipline as rollover):

- **Release** (do nothing, player becomes poachable) only if:
  age ≥ 34, **or** overall < the club's squad-median overall − 5, **and** the squad
  would stay ≥ 19 players counting remaining contracted players.
- **Renew** everyone else: `contractYears = 2 + rng.nextInt(3)` (2–4 years), applied
  immediately.

The unconditional auto-renew in the rollover contract-tick pass is **removed**.
Unrenewed AI players who reach rollover unsigned lapse into the free-agent pool exactly
like managed players do — organic, real-player inflow that feeds cycle 1's shrinking
pool. `RolloverContractsIT.theFreeAgentPoolIsNotReplenishedAtRollover` is updated to
distinguish generated backfill (still forbidden) from organic contract-lapse inflow
(now intended).

## 2. Data model

New table `pre_contract_agreement` (JPA entity `PreContractAgreement`):

| column | type | notes |
|---|---|---|
| id | UUID | PK |
| save_game_id | UUID | scope |
| player_id | UUID | one **active** agreement per player |
| from_club_id | UUID | player's current club |
| to_club_id | UUID | destination (managed club, or AI club when poaching the manager) |
| wage | numeric | agreed wage |
| contract_years | int | agreed length, applied at execution |
| status | varchar | `AGREED` → `LOCKED` / `CANCELLED` |
| created_date | date | in-game date the deal was agreed |

Lifecycle: created `AGREED` → next TRANSFER day resolves the current club's
counter-renew → `LOCKED` (deal final) or `CANCELLED` (player renewed with his club).
Rows are deleted at rollover after execution/resolution; news items carry the history.
Flyway migration `V30` (or next free number at implementation time).

## 3. Manager's offers — negotiation with a single counter round

New "Pré-contratos" tab in Mercado listing eligible rival players (name, position, age,
overall, current club, wage-demand estimate). The manager offers `wage + contractYears`
(years 1–4, same bounds as existing contract flows). The player answers instantly,
judged against `demand = ContractValuation.wageDemand(overall, age) × 1.10`
(pre-contract premium — he holds the security of a current deal):

- **offer ≥ demand** → `AGREED`, agreement row created, news fires.
- **0.70 × demand ≤ offer < demand** → **COUNTER**: response carries his demanded wage;
  the manager may accept the counter (creates the agreement at his demand) or walk away.
  One counter round only — no haggling loop.
- **offer < 0.70 × demand** → rejected outright (re-offering later is allowed).

Constraints checked at offer time: eligibility (section "Design decision"), one active
agreement per player, senior-squad-cap room (same rule as `signFreeAgent`), and the
offer must come from the managed club (this cycle: manager → AI only; AI → AI is out of
scope). The cap is enforced at offer time only — a locked pre-contract is binding and
executes unconditionally at rollover (a small overshoot is possible if the manager
fills the squad afterwards; acceptable, matches real-world binding deals).

**Counter-renew (the drama):** on the **next TRANSFER day** after `AGREED`, the
player's current club gets one seeded roll to keep him — keep probability =
`clamp(0.30 + 0.05 × (fromClubReputation − toClubReputation) + 0.02 × (age − 28), 0.05, 0.70)`.
Success → `CANCELLED`, player immediately renews with his club (2–4 years), news.
Failure → `LOCKED`, news. The roll is deterministic for a given save + date (seeded
like other calendar-day RNG).

## 4. AI poaching the manager's players (symmetric defense)

On **each TRANSFER day inside the window**, AI clubs may target the manager's
**unrenewed final-year** players: interest heuristic — player overall ≥ the AI club's
squad-median overall and age ≤ 33; at most **one** new poach agreement per TRANSFER
day league-wide (the interested club with the best reputation wins the race), keeping
the pressure believable rather than a feeding frenzy. The AI agrees terms automatically (wage = the player's demand
with the same 1.10 premium), the agreement is created `AGREED`, and the manager gets an
immediate news item.

**Defense:** until the next TRANSFER day, the manager may counter by renewing the
player via the existing `renewContract` flow. The renewal is intercepted when an
`AGREED` incoming agreement exists: the player stays if the offered renewal wage ≥
**1.05 × the agreed pre-contract wage** (and the renewal then applies normally);
otherwise the renewal is refused with a clear error and the agreement stands. Staying →
agreement `CANCELLED` + news. No action by the next TRANSFER day → `LOCKED` + news.
This mirrors section 3's counter-renew — same rule both directions, with the manager's
defense being an explicit choice instead of a roll.

## 5. Execution at rollover

In the existing contract-tick pass, when a player's contract expires
(`contractYears` reaches 0):

1. **LOCKED agreement exists** → the player moves: `clubId = to_club_id`,
   `wage = agreed wage`, `contractYears = agreed years`. No fee, no window check. News
   for both clubs' perspective (manager sees both his arrivals and his losses).
2. **AGREED agreement still pending** (no TRANSFER day remained after agreement) → it
   resolves at rollover exactly as the next TRANSFER day would have: outgoing deals run
   the current club's counter-renew roll, incoming deals simply lock (the manager's
   defense window has passed). Then rule 1 or the renewal applies.
3. **No agreement** → lapse into the free-agent pool (managed players: existing
   behavior; AI players: new behavior per section 1).

All agreement rows for the save are deleted at the end of rollover.

## 6. API & frontend

Backend — new `PreContractController` (`/save-games/{id}/pre-contracts` base):

- `GET /eligible` — eligible rival players with wage-demand estimates.
- `POST /offers` `{playerId, wage, contractYears}` — returns `AGREED` (with agreement)
  or `COUNTERED` (with demanded wage) or error.
- `POST /offers/{playerId}/accept-counter` — accept the countered demand.
- `GET /agreements` — active agreements involving the manager (outgoing signings +
  incoming threats), with status.
- `DELETE /agreements/{id}` — withdraw an outgoing `AGREED` agreement before it locks
  (allowed; the player simply stays on course to lapse).

New `ErrorCode`s: `PLAYER_NOT_PRE_CONTRACT_ELIGIBLE`, `PRE_CONTRACT_ALREADY_EXISTS`,
`PRE_CONTRACT_WINDOW_CLOSED`, `RENEWAL_BLOCKED_BY_PRE_CONTRACT` (refused defense
renewal below 1.05×). Existing renewal endpoint gains the interception described in
section 4.

Frontend — Mercado gains a **"Pré-contratos"** tab (pattern: existing market tabs):
eligible-players list with an offer dialog (wage + years, shows demand estimate,
handles the counter round), "Meus acordos" section (outgoing deals with status chips
AGREED/LOCKED, withdraw action; incoming threats highlighted with a "Renovar para
defender" button that opens the renewal flow). All copy pt-BR. News: new
`NewsItemType`s for agreed / locked / cancelled / executed (+ the threat alert is the
agreed-news for incoming direction), rendered in `AdvanceEventCard` like existing types.
TanStack Query: follow the session's caching conventions (explicit invalidation on
mutations; agreements/eligible lists invalidated on advance like other market data).

## 7. Testing

- **Unit:** AI renewal heuristic (release vs renew edge cases, squad-floor guard),
  poach-interest heuristic, negotiation math (agree/counter/reject thresholds, 1.10
  premium), counter-renew weighting determinism, defense rule (1.05× boundary).
- **IT (Testcontainers):** offer flow end-to-end (agree, counter-accept, reject,
  squad-cap block, ineligible player, window closed); TRANSFER-day resolution
  (lock + cancel paths); AI poach creates an agreement + news and the defense renewal
  cancels it; rollover executes a LOCKED move (club, wage, years, news) and resolves a
  still-AGREED deal; unrenewed AI players lapse into the free-agent pool; updated
  `RolloverContractsIT` no-generated-backfill assertion.

## Out of scope (this cycle)

- AI-to-AI pre-contract poaching (only manager↔AI directions ship now).
- Transfer-fee "buy him now instead" counter-offers from the losing club.
- Bonus/clause negotiation (goal/appearance bonuses belong to the roadmap's market
  depth group).
- Watchlist integration (cycle 3 — its alerts will naturally cover pre-contract
  eligibility later).
- Nationality display and any signing-flow changes to free agents.

## Numbers summary (tunable constants, one place in code)

| constant | value |
|---|---|
| Eligibility date | July 15 (6 months before rollover) |
| Pre-contract wage premium | 1.10 × wageDemand |
| Counter threshold | offer ≥ 0.70 × demand |
| Defense renewal rule | ≥ 1.05 × agreed pre-contract wage |
| AI renewal: release age | ≥ 34 |
| AI renewal: squad floor | 19 |
| AI renew length | 2–4 years (seeded) |
| Poach age ceiling | ≤ 33 |
