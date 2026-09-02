# Rescisão de Contrato — Manager-Initiated Contract Termination — Design Spec

**Date:** 2026-09-01
**Status:** Implemented and shipped (branch `f960b3a..afda449`).
**Revised 2026-09-02**, after implementation, to describe the system that actually shipped. Three
things the build found that the design had not: a live-match guard, outstanding transfer offers,
and a season-archive crash. Each is marked *(found in build)* below.
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

## 2. Current state *(as it was before this work — kept as the starting point the design argues from)*

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
managed club. Only the releasing club is barred. The refusal raises its own
`ErrorCode.PLAYER_RECENTLY_RELEASED` with its own pt-BR message *(found in build)* — the first
attempt reused `INVALID_TRANSFER`, which the frontend maps to a generic "Proposta inválida.", so
the manager was told nothing. §9 rejects exactly that click-then-fail pattern for the terminate
button; it must not reappear one page over.

`FinanceTransactionType` gains `TERMINATION`. Without it the fee would land in `TRANSFER_OUT` and
misreport the club's transfer activity. The enum value only pays off once the Finances page can
render it: `TYPE_LABELS` gains `TERMINATION: 'Rescisão de contrato'` *(found in build)*, otherwise
the row falls back to the raw enum string in a pt-BR-only screen.

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

**Response:** `TerminationResponse(playerId, playerName, fee, newBalance)`, so the UI can report what
was paid without a follow-up request.

**Service:** `ContractService.terminateContract(saveGameId, userId, playerId)`, sibling to
`renewContract`.

### Validation order

Cheapest and most explanatory first:

1. Player belongs to the managed club and is not a youth prospect (`youth_prospect = false`) →
   `PLAYER_NOT_FOUND`
2. No live match is in progress → `LIVE_MATCH_IN_PROGRESS` *(found in build)*
3. Departure allowed: squad would not drop below `MIN_SQUAD_SIZE`, and the player is not the last
   goalkeeper → `SQUAD_TOO_SMALL`
4. Club balance covers the fee → `INSUFFICIENT_FUNDS`

The live-match guard was missing from the original three. Selling already had it
(`TransferService.requireSellAllowed`'s neighbour `requireNoLiveMatch`), for the reason it applies
here identically: releasing a starter mid-match deletes his `lineup_players` row while `LiveMatch`
still holds him in its serialised XI, and `MatchOutcomeApplier` would later apply the match's
effects to a player who is by then a free agent. §7's "one concept" was the place this omission
showed up — it had unified two of the three rules a departure implies, not all three.

**No transfer-window check.** Termination is not a transfer, and mid-season — with a bloated squad —
is exactly when it is useful. This is a decision, not an oversight; a test defends it.

### Effects (one transaction)

- Debit the fee from the club balance; record a `TERMINATION` ledger row described
  `"Rescisão: <name>"`.
- `clubId = null`, `wage = 0`, `contractYears = 0`, `unlist()` — the player enters the free-agent
  pool and appears in `getFreeAgents`.
- `releasedByClubId = managedClubId`.
- Remove the player from the managed lineup and from any set-piece-taker column.
- Reject every `PENDING` or `COUNTERED` transfer offer for him *(found in build)*. Without this a
  live bid outlives the contract: `respondToIncoming` re-loads the player and its squad guard now
  passes **trivially**, because he is no longer in the squad — so the club could be credited a
  transfer fee and a `"Venda: <name>"` ledger row for a player it had already paid a buyout to
  release. Rejecting rather than deleting matches the fiction: the bidder's interest dies with the
  contract.
- `applyMorale(-1)` on every remaining senior player at the club (`youth_prospect = false`; the
  released player himself is excluded). On a −5..+5 scale that is a perceptible
  blow, comparable to losing a match, without being catastrophic.

## 7. Refactor: one concept for leaving the managed squad

"A player leaves the managed squad" was three private rules living apart in the transfer package:
`TransferService.requireSellAllowed` (the departure guard), `TransferService.requireNoLiveMatch`,
and `TransferExecutor.cleanManagedLineup` (the cleanup). Termination needs all three, verbatim.

`SquadDeparture` (in `br.com.carvoeiromanager.common`) now owns them, and one more the build added:

- `seniorSquad(clubId)` — the professional squad, the roster every departure rule counts against
- `requireDepartureAllowed(seniorSquad, leaving)` — min squad size, last goalkeeper
- `requireNoLiveMatch(saveGameId)` — *(found in build)*
- `cleanManagedLineup(saveGameId, managedClubId, playerId)` — lineup row + set-piece takers
- `rejectOpenOffersFor(saveGameId, playerId)` — *(found in build)*

`requireDepartureAllowed` takes the squad list rather than the club id, so a caller that needs that
list for something else — termination needs it for the morale hit — does not query it twice. The
list must be `seniorSquad(managedClubId)` for the same club.

`TransferService`, `TransferExecutor` and `ContractService` all depend on it. Selling and
terminating share one definition of every rule instead of copies that can drift apart.

**What this section originally got wrong.** It named two rules and called them "both halves". There
were three, and the build found a fourth obligation (outstanding offers) that no existing path had
ever needed, because before termination nothing removed a managed player from the squad while a
live bid existed. A component that unifies *most* of what leaving implies is more dangerous than
one that unifies none: the rules it omits look handled.

## 8. A released player and the season archive *(found in build)*

`season_top_scorer.club_id` is `NOT NULL`, and `SeasonRolloverService` writes the scorer's
**current** club. Rollover archives every competition in step 1 and only nulls out lapsed contracts
in step 3 — so a contract *expiring* never presents a club-less scorer to the archive. Termination
is the first path that can, and it did: releasing a top scorer and then rolling the season threw a
constraint violation and the save could not advance.

The archive therefore falls back to `released_by_club_id` when a scorer has no current club. That
column exists to record who released him, so it names the club he actually scored those goals for —
truer than any placeholder, and it needs no schema change. A club-less scorer with no release mark
(not reachable today) is omitted rather than crashing, and omission does not consume a rank.

The fallback lives in `SeasonRolloverService.archive`, deliberately **not** in `TopScorersService`:
that service also feeds live in-season screens, where a released player must read as club-less
rather than still under his former club's name.

## 9. Interface

The termination action lives in the **Contrato** tab of `PlayerDetails`, in the existing
`contractAction` slot next to "Renovar" (`SquadPage.tsx:964`) — where the manager already is when
thinking about that player's contract.

Unlike "Renovar", which fires immediately, termination **must confirm**: it spends money and cannot
be undone.

- **Confirmation dialog** stating the fee, the resulting balance, and that the player cannot be
  re-signed by this club until next season. Confirm button in `color="error"`.
- **Disabled with a visible reason** when termination is impossible — squad at the minimum, player
  is the last goalkeeper, or the balance will not cover the fee. Failing only after the click, via
  an error toast, would be worse than the current UI. The squad-size and goalkeeper reasons come
  from the squad list `SquadPage` already holds; the fee comes from the server on each player. The
  **balance does not** — `SquadPage` currently loads only `squad` and `lineup`, so it must also read
  the shared `['finances', saveGameId, clubId]` query that `FinancesPage` already populates and
  `TransferMarketPage` already invalidates. That same query is what lets the dialog state the
  resulting balance. The backend still enforces every rule — the client-side check explains, it
  never authorises.
- **Confirm stays disabled until the balance is known** *(found in build)*. The finances query is
  in flight on mount, and while it is pending — or if it errors — `balance` is `undefined`. The
  first build rendered the fee and the warning but silently dropped the balance line and left the
  button clickable, which is the confirmation contract this dialog exists to uphold. The balance
  slot now always renders, showing a pending placeholder instead of vanishing.

`PlayerResponse` gains `terminationFee: BigDecimal`, precomputed server-side exactly as `wageDemand`
already is. No preview endpoint, no extra request when the dialog opens.

On success, invalidate the squad, finances, free-agent, lineup and market queries: the player
leaves the list, the balance drops, he shows up on the free-agent market, and — since `releaseFrom`
calls `unlist()` — any transfer-market listing for him disappears with him.

A **success Snackbar** reports what the termination cost, using the response's `playerName`, `fee`
and `newBalance` *(found in build)*. §6 justifies the response's existence as "so the UI can report
what was paid"; the first build left it entirely unconsumed, so the only feedback for the one
action that spends money irreversibly was the balance quietly changing on another page.

## 10. Testing

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
