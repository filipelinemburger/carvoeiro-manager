# Empréstimo de Jogadores — Player Loans — Design Spec

**Date:** 2026-09-02
**Status:** Approved (design); ready for implementation plan.
**Builds on:** the transfer negotiation (`TransferOffer`, `TransferNegotiator`, `SellerContext`),
the pre-contract slice as the precedent for a two-club agreement about a player, and
`SquadDeparture` — created by the contract-termination slice
(`2026-09-01-contract-termination-design.md`) and directly reused here.

---

## 1. Why this exists

A squad has more players than it has places. Today the manager's only answers are to sell a player
outright or to leave him rotting in the reserves, losing condition and morale with no path back.
Neither serves the young player who needs games, and neither helps a club that is one injury away
from having no cover.

Loans give both sides a move the game currently lacks: send a surplus player somewhere he will
play, or borrow the cover you cannot afford to buy. And because the wage share is negotiable, a
loan becomes a financial instrument too — you can buy a player's departure by subsidising him.

## 2. Current state *(as it was before this work — the starting point the design argues from)*

- **`players.club_id` means two things at once**: who owns the player, and who he plays for.
  Roughly 38 files read it — the match engine, finances, transfers, the season, statistics,
  scouting, the academy — and **almost all of them mean "who does he play for"**.
- **The wage bill is computed from the current club.** `FinanceService.monthlyWageBills` groups
  players by `club_id` and sums `financeCalculator.monthlyWage(overall, age)`, ignoring the stored
  wage.
- **The AI already models whether a player is spare.** `SellerContext(listed, keyPlayer, noCover,
  health)`, built by `SellerContextFactory`, is what `TransferNegotiator.respondToBuy` uses to
  price a bid. `keyPlayer` and `noCover` are exactly the two questions a loan needs.
- **`Club.reputation` is squad quality.** `FinanceCalculator.reputation(List<Player>)` derives it
  from the squad, so reputation and level are the same quantity.
- **Negotiation with counters already exists.** `NegotiationDecision {ACCEPT, REJECT, COUNTER}`,
  `TransferOfferStatus {PENDING, COUNTERED, ACCEPTED, REJECTED, WITHDRAWN}`,
  `offer.markCountered(...)` and `TransferService.acceptCounter(...)`.
- **`SquadDeparture`** owns every rule a departure from the managed squad implies: `seniorSquad`,
  `requireDepartureAllowed` (floor + last goalkeeper), `requireNoLiveMatch`, `cleanManagedLineup`
  and `rejectOpenOffersFor`.
- **`Player.isEligible()` is `suspensionRemaining == 0 && injuryMatches == 0`** — a predicate about
  the player alone, with no notion of an opponent.
- **`ListedReason`** already has `SURPLUS`, alongside `OWNER`, `PLAYER_REQUEST`, `UNUSED`,
  `DESPERATE` and `FINANCIAL`.
- **`SquadRules.MAX_SQUAD_SIZE = 40`**, read by `ContractService:169`, `PreContractService:411`,
  `AiTransferEngine:115` and `TransferService:406`, and pinned by `SquadRulesTest`.

## 3. Scope

In scope: the manager loans players **out** to AI clubs and **in** from AI clubs, with a negotiation
the AI can counter.

Out of scope, deliberately:

- **AI-to-AI loans.** The AI never initiates a loan; it only answers the manager's. Adding an
  engine that originates loans is a separate slice.
- **Early recall.** Every loan runs to the season rollover. A mid-season recall needs its own UI
  action, an AI reaction to losing a player it was promised, and a second lifecycle path.
- **Loan fees.** The negotiated wage share is the price; a second money term adds nothing the
  first does not already express.
- **Options to buy.** A separate feature that would couple loans to the transfer market.

## 4. When a loan is plausible

The realism rule is not a special case about club size. It is two questions, and club size falls
out of them:

**Is the player spare at his owner?** Reuse `SellerContext`: he must not be a key player, and his
position must have cover. A club does not lend someone it needs.

**Is the player useful to the borrower?** His `overall` must reach the borrower's level:
`player.overall >= borrowerClub.reputation - LEVEL_TOLERANCE`, with `LEVEL_TOLERANCE = 3`. The two
quantities are directly comparable because `FinanceCalculator.reputation` derives a club's
reputation from its squad's overalls. The tolerance exists so a borrower will take a player a
shade below its average — a squad is not made only of its mean — without opening the door to
someone plainly beneath it.

Together these make loans flow the way they do in football, without a rule that says so:

- A small club's reserve is below a big club's level → fails the second test. The big club does not
  want him.
- A small club's star passes the second test but is a key player → fails the first. The small club
  will not lend him; it would rather sell him.

So **loans upward are rare by construction**, and the narrow door — the standout who is
nonetheless spare, because his club has two of him or he sits behind an established starter — stays
narrow. The common, working cases are the real ones: lending your reserve to a smaller club, and
borrowing a bigger club's reserve.

**This makes explicability a requirement, not a polish item.** Asking a smaller club for a player
will usually fail, and a refusal with no stated reason reads as a broken feature. Every refusal
carries its reason (§6).

## 5. Data model

Migration `V54__player_loans.sql`.

**On `players`** — three columns:

```sql
ALTER TABLE players ADD COLUMN owner_club_id UUID;
ALTER TABLE players ADD COLUMN loan_wage_share INT;
ALTER TABLE players ADD COLUMN loan_may_face_owner BOOLEAN;
```

`owner_club_id IS NOT NULL` **is** the definition of "on loan". `club_id` keeps meaning *where he
plays*, so the ~38 sites that read it stay correct with no change. `loan_wage_share` is the
percentage **the owner** pays, 0–100.

**And a `loan_offer` table** for the negotiation: save, player, owner club, borrower club, proposed
wage share, proposed may-face-owner, the two counter columns, status, round, created_at.

**Why the terms live on the player and not on a long-lived agreement row.** The offer is
*transient negotiation*; the loan is a *fact about the player*. This is exactly how transfers
already work — `transfer_offer` records the haggling, and the outcome is written to the player's
`club_id`; no table says "this player was bought". The alternative, a standing `loan_agreement`
holding the terms with only ownership on the player, creates two rows that must agree about one
loan. Divergence between two sources of one truth is precisely the defect the `SquadDeparture`
extraction was created to remove. Here the player row alone answers everything: whose he is, who
pays what, and whether he may face his owner.

`loan_offer` is purged at rollover with the other season-scoped tables. The player's three columns
are cleared by the return (§7), which runs before that purge.

## 6. The negotiation

The manager proposes: the player, the **wage share the owner pays**, and whether the borrower may
field him against the owner. Symmetric in both directions — only which side the manager sits on
changes.

The AI's answer runs the tests in this order, because the order is what makes a refusal
explicable:

1. **Spare at the owner?** `SellerContext` says key player, or the position has no cover →
   **REJECT**, reason `KEY_PLAYER`.
2. **At the borrower's level?** `overall < borrower.reputation - LEVEL_TOLERANCE` → **REJECT**,
   reason `BELOW_LEVEL`.
3. **Do the terms work?** → **ACCEPT** or **COUNTER**.

The split is load-bearing. The first two are refusals about *the player*, and no improvement to the
offer will move them. The third is about *the offer*, and it is the only one worth answering with a
better proposal. A UI that blurs them sends the manager raising the wage share against a wall.

**Counters** mirror the transfer machinery one for one: `LoanOfferStatus` with the same five
values, a `LoanNegotiationResult(decision, counterWageShare, counterMayFaceOwner)`, `markCountered`,
and an `acceptCounter` endpoint.

Both terms have opposite sides, so a counter always moves them toward the responder's interest:

- **Wage share** — the borrower wants the owner to pay more; the owner wants to pay less.
- **May face owner** — the borrower wants it granted; the owner wants it withheld.

So when the manager lends, the AI borrower counters asking him to subsidise more. When the manager
borrows, the AI owner counters asking him to pay more of the wage and withholding the fixture
release.

**One round of counter**, as with transfers: the manager accepts the AI's counter or walks away.
He cannot counter the counter. A fresh offer is always possible instead, which is the same thing
with a cleaner state machine.

A countered offer means the deal is live. A rejected one means it is not.

## 7. The lifecycle

**On acceptance**, inside one transaction:

- `club_id` = borrower, `owner_club_id` = owner, `loan_wage_share` and `loan_may_face_owner` set.
- The departing side runs the full `SquadDeparture` contract: `requireNoLiveMatch`,
  `requireDepartureAllowed`, `cleanManagedLineup`, `rejectOpenOffersFor`.

That last one is not theoretical. Without it a pending bid outlives the loan, and the sell guard
then passes **trivially** because the player is no longer in the owner's squad — the club could be
credited a transfer fee for a player it had already lent away. This is the same defect contract
termination introduced and the full-suite gate caught; loans are the third exit from the squad and
inherit the obligation.

**A new guard, specific to loans: the contract must outlast the loan.** A loan runs to the
rollover, and the contract ticks at the rollover. So a player may be loaned only with **at least
2 years remaining**: he returns with 1 year still to run, and the loan closed inside the contract.
At 1 year the contract would end at the very moment the loan does, which the design forbids — a
loan must begin and end within one contract, never straddling its end. The fiction agrees: you do
not lend a player who is already leaving.

This is a property of the player, so it binds **both directions**: the manager cannot lend a player
with one year left, and cannot borrow one either. It also means no loan can be interrupted by its
player becoming a free agent mid-loan — the case is designed out rather than handled.

**Monthly**, `FinanceService.monthlyWageBills` stops being a plain group-by. For a loaned player it
charges `loan_wage_share`% to `owner_club_id` and the remainder to `club_id`. This is the one place
the split ownership reaches a hot path, and it runs for every club, every processed month.

**At rollover**, the return happens in the player pass, and **two orderings matter**:

- **After the season archive** (step 1). His goals were scored for the club he played at, and that
  is the club the archived top-scorer row must name. Getting this backwards is how the termination
  slice broke the archive.
- **Before the contract tick.** The contract is with the owner, so he must be home before it ticks.

The return sets `club_id = owner_club_id` and clears all three loan columns.

## 8. Squad limits and the return

`SquadRules.MAX_SQUAD_SIZE` rises from 40 to **45**. This reaches beyond loans: it also governs
signing a free agent (`ContractService:169`), pre-contracts (`PreContractService:411`), AI buying
(`AiTransferEngine:115`) and managed buying (`TransferService:406`). `SquadRulesTest` pins the value
and must be updated with it.

A loaned-out player counts for the club he plays for, not the one that owns him — the same
principle as the wage and the squad list. Lending frees a place; borrowing fills one, so accepting
a loan **in** is refused when the borrower is already at the cap, exactly as buying is; and lending
**out** runs the existing `requireDepartureAllowed` floor, so a club cannot lend itself below a
fieldable squad.

**Returns can still overflow**, because the cap is checked when *adding* a player and the return
adds several at once. After the returns, any club above 45 lists its lowest-`overall` senior
players as `ListedReason.SURPLUS` until it is back at the cap. For the managed club this appears in
the rollover summary, beside retirements and expired contracts — where the manager already looks
for what changed.

## 9. Eligibility against the owner

`Player.isEligible()` is `suspensionRemaining == 0 && injuryMatches == 0` — a predicate about the
player alone. **The fixture-release rule cannot live there**, because it depends on the opponent.
It needs a new opponent-aware check where a lineup is built and validated.

It must apply to the **AI's automatic lineup selection** as well, not only the manager's. If only
the manager's side enforces it, the AI will field an ineligible borrowed player against the club
that owns him — visibly, in a match the manager is watching minute by minute.

## 10. Interface

**Loaning out** lives beside the existing squad actions, in `PlayerDetails`' Contrato tab next to
"Renovar" and "Rescindir": pick a club, set the wage share you will pay, choose whether to release
him for the fixture against you.

**Loaning in** belongs on the transfer market, where the manager is already browsing other clubs'
players — a loan is an alternative to a bid on the same player, not a separate destination.

Both refusals show their reason in pt-BR, distinguishing the two kinds: "é peça-chave do elenco
dele" and "não está no nível do seu elenco" end the conversation, while "não gostaram dos termos"
invites a better offer. A countered offer shows the AI's terms and offers to accept them.

A borrowed player is marked as such wherever the squad is listed, with his owner named — the
manager must never be surprised at the rollover by a player leaving.

## 11. Testing

**Unit** — the plausibility rule and the counter logic are pure decisions and belong in fast tests:
the two rejection reasons, the accept/counter boundary, and that a counter always moves both terms
toward the responder.

**Integration** — one per path:

- lending out: player moves, owner's lineup cleaned, open bids rejected, squad counts shift
- borrowing in: the mirror
- a player with 1 year left cannot be loaned; with 2 he can
- the wage bill splits by `loan_wage_share` across both clubs for a processed month
- rollover returns him to his owner, clears the loan columns, and does so **after** the archive —
  asserted by checking a loaned top scorer is archived under the club he played for
- rollover returns that push a club past 45 list the lowest-`overall` seniors as `SURPLUS`
- a borrowed player without fixture release is not selectable against his owner — **and the AI
  does not field him either**
- every refusal reason reaches the API distinctly

**Frontend** — the two negotiation surfaces, the three distinct refusal messages, the counter
accept flow, and the borrowed-player marking in the squad list.
