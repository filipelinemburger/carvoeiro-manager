# Rescisão de Contrato — Manager-Initiated Contract Termination — Design Spec

**Date:** 2026-09-01
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the contract slice (`ContractService`, `ContractValuation`) and the selling path
in `TransferService` / `TransferExecutor`.
**Followed by:** player loans, which are a separate spec — they split "who owns a player" from
"who he plays for" and are deliberately not designed here.

---

## 1. Why this exists

A manager who signs a bad player is stuck with him. The only ways out today are selling him — which
needs a buyer willing to bid — or waiting for the contract to lapse at season rollover. There is no
way to simply cut a player loose, so a squad slot and a wage can be dead weight for years, and a
five-year contract carries no risk at signing time because it can never bite.

Termination gives the manager that exit and, in doing so, makes contract length a real decision:
the longer the deal, the more expensive it is to undo.

## 2. Current state

- **A contract is two columns on `players`**: `wage` and `contract_years` (`V7__contracts.sql`).
  There is no contract table. A player with `club_id = NULL` is a free agent.
- **`ContractService`** owns the manager's contract operations: `renewContract` (line 50) and
  `signFreeAgent` (line 74), sharing `applyTerms` (line 105) and `requireManagedPlayer` (line 151).
- **`ContractValuation`** holds the deterministic contract economics — wage demand, valid lengths.
  No RNG anywhere in it.
- **The wage bill is computed, not read.** `FinanceService.monthlyWageBills` (line 355) derives each
  player's cost from `financeCalculator.monthlyWage(overall, age)`, ignoring the stored `wage`.
  Any severance formula must be built on the same function or it will not be in the same currency
  as the rest of the finance model.
- **A season is ~10 charged months.** The solvency estimate in `FinanceCalculator`'s javadoc
  (line 37) works in ten monthly charges per season; the calendar starts 15 January
  (`SeasonCalendarGenerator.java:97`).
- **Two departure guards already exist, both private.** `TransferService.requireSellAllowed`
  (line 383) enforces `MIN_SQUAD_SIZE = 14` and refuses to sell the last goalkeeper;
  `TransferExecutor.cleanManagedLineup` (line 111) removes a departing player from the lineup row
  and from any set-piece-taker column (no-cascade FK safety).
- **Morale is a −5..+5 integer** mutated through `Player.applyMorale(delta)` (line 327); a match
  win or loss moves it by 1 (`MatchOutcomeApplier.java:111`).
- **`Player.resetSeasonState()`** (line 387) clears per-season state and is called for every
  carried-over player at rollover, free agents included (`SeasonRolloverService.java:296`).
- **`PlayerResponse` already carries derived contract economics**: `wageDemand` is precomputed
  server-side so the squad UI's "Renovar" button can act without a second request.

## 3. Scope

In scope: the manager terminates a contract of a player at their own club.

Out of scope, deliberately:

- **AI clubs terminating contracts.** Would need a decision rule in `AiListingEngine` and is not
  required for the manager-facing feature.
- **Players requesting termination.** A new negotiation flow coupled to the morale system.
- **A news item.** The manager just performed the action; a headline reporting it back is noise.
  Press reaction to releases, if wanted, is its own slice.
- **Loans.** Separate spec.

## 4. Data model

Migration `V53__contract_termination.sql`:

```sql
ALTER TABLE players ADD COLUMN released_by_club_id UUID;
```

`released_by_club_id` records which club cut the player loose. It is read by `signFreeAgent` to
refuse re-signing a player you released yourself, and cleared in `Player.resetSeasonState()` — which
is what makes the block last exactly one season rather than the whole career.

The block is **per club, not global**: a player released by Chapecoense remains signable by the
managed club. Only the releasing club is barred.

`FinanceTransactionType` gains `TERMINATION`. Without it the fee would land in `TRANSFER_OUT` and
misreport the club's transfer activity.

No contract table. Terminating is zeroing `wage` / `contract_years` and nulling `club_id` — the same
shape the existing lapse-at-rollover path already produces.

## 5. The termination fee

Lives in `ContractValuation`, alongside the other deterministic contract economics:

```
fee = contractYears × MONTHS_PER_SEASON(10) × monthlyWage(overall, age) × TERMINATION_FACTOR(0.50)
```

`MONTHS_PER_SEASON = 10` is the same figure the finance model's own solvency estimate uses, so the
fee is denominated in the same currency as the wage bill it replaces. `TERMINATION_FACTOR = 0.50`
means the club buys out half of what it still owes.

Worked example — overall 60, two years remaining: monthly wage ≈ R$288 000, so
`2 × 10 × 288 000 × 0.50 ≈ R$2.88 mi`.

**Deliberate simplification:** the fee is not prorated by position in the season. Releasing in round
35 costs the same as releasing in round 1, even though most of that season's wages are already paid.
Prorating would be more faithful but ties the fee to the calendar and makes it hard to state in the
UI. Revisit only if the flat version reads as wrong in play.

Every club player is seeded with `contract_years` between 1 and 4 (`SaveGameService.java:204`,
pinned by `CareerContractSeedingIT`), so there is no zero-fee case. The one group carrying
`contract_years = 0` is youth prospects (`NewgenGenerator.java:64`) — which is a second reason §6
excludes them: a prospect would otherwise be terminable for free.

## 6. The operation

**Endpoint:** `POST /save-games/{saveGameId}/players/{playerId}/terminate`, no body — mirroring the
`renew` mapping in `ContractController` (line 31).

**Response:** `TerminationResponse(playerName, fee, newBalance)`, so the UI can report what was paid
without a follow-up request.

**Service:** `ContractService.terminateContract(saveGameId, userId, playerId)`, sibling to
`renewContract`.

### Validation order

Cheapest and most explanatory first:

1. Player belongs to the managed club and is not a youth prospect (`youth_prospect = false`) →
   `PLAYER_NOT_FOUND`
2. Departure allowed: squad would not drop below `MIN_SQUAD_SIZE`, and the player is not the last
   goalkeeper → `SQUAD_TOO_SMALL`
3. Club balance covers the fee → `INSUFFICIENT_FUNDS`

**No transfer-window check.** Termination is not a transfer, and mid-season — with a bloated squad —
is exactly when it is useful. This is a decision, not an oversight; a test defends it.

### Effects (one transaction)

- Debit the fee from the club balance; record a `TERMINATION` ledger row described
  `"Rescisão: <name>"`.
- `clubId = null`, `wage = 0`, `contractYears = 0`, `unlist()` — the player enters the free-agent
  pool and appears in `getFreeAgents`.
- `releasedByClubId = managedClubId`.
- Remove the player from the managed lineup and from any set-piece-taker column.
- `applyMorale(-1)` on every remaining senior player at the club (`youth_prospect = false`; the
  released player himself is excluded). On a −5..+5 scale that is a perceptible
  blow, comparable to losing a match, without being catastrophic.

## 7. Refactor: one concept for leaving the managed squad

Both halves of "a player leaves the managed squad" are currently private and duplicated in intent:
`TransferService.requireSellAllowed` (the guard) and `TransferExecutor.cleanManagedLineup` (the
cleanup). Termination needs both, verbatim.

Extract a `SquadDeparture` component exposing:

- `requireDepartureAllowed(managedClubId, player)` — min squad size, last goalkeeper
- `cleanManagedLineup(saveGameId, managedClubId, playerId)` — lineup row + set-piece takers

`TransferService`, `TransferExecutor` and `ContractService` all depend on it. Selling and
terminating then share one definition of the rule instead of two copies that can drift apart.

This is the only structural change; nothing else about the transfer path moves.

## 8. Interface

The termination action lives in the **Contrato** tab of `PlayerDetails`, in the existing
`contractAction` slot next to "Renovar" (`SquadPage.tsx:964`) — where the manager already is when
thinking about that player's contract.

Unlike "Renovar", which fires immediately, termination **must confirm**: it spends money and cannot
be undone.

- **Confirmation dialog** stating the fee, the resulting balance, and that the player cannot be
  re-signed by this club until next season. Confirm button in `color="error"`.
- **Disabled with a visible reason** when termination is impossible — squad at the minimum, player
  is the last goalkeeper, or the balance will not cover the fee. Failing only after the click, via
  an error toast, would be worse than the current UI. These three reasons are derived client-side
  from data the squad page already holds (the squad list and the club balance); only the fee itself
  comes from the server. The backend still enforces all three — the client-side check is for
  explanation, never for authority.

`PlayerResponse` gains `terminationFee: BigDecimal`, precomputed server-side exactly as `wageDemand`
already is. No preview endpoint, no extra request when the dialog opens.

On success, invalidate the squad, finances and free-agent queries: the player leaves the list, the
balance drops, and he shows up on the free-agent market in the same navigation.

## 9. Testing

**`ContractValuationTest`** (extend) — the fee is a pure function, so it is pinned with direct
assertions: years remaining × season payroll × 0.50, and the one-year floor. Changing the discount
factor breaks this test first, and it says why.

**`ContractServiceTerminateIT`** (new), one case per path:

- success: player becomes a free agent, `wage` / `contractYears` zeroed, balance debited,
  `TERMINATION` row present
- squad at 14 → `SQUAD_TOO_SMALL`, nothing mutated
- last goalkeeper → `SQUAD_TOO_SMALL`, nothing mutated
- balance below the fee → `INSUFFICIENT_FUNDS`, nothing mutated
- another club's player, or a youth prospect → `PLAYER_NOT_FOUND`
- **outside the transfer window: succeeds** — defends the deliberate rule from §6
- the released player is gone from the lineup and from every set-piece-taker column
- remaining senior players lose 1 morale; the released player is not counted

**`ContractServiceSignIT`** (extend) — re-signing a player this club released is refused; a player
released by *another* club signs normally. The pair is what proves the block is per-club.

**`RolloverContractsIT`** (extend) — `released_by_club_id` is cleared at rollover, so the block
lasts one season rather than the career.

**Frontend** (`SquadPage.test.tsx`, `PlayerDetails.test.tsx`) — the dialog shows fee and resulting
balance; confirming calls the endpoint; cancelling calls nothing; the button renders disabled with
its reason when the squad is at the minimum or funds are short.
