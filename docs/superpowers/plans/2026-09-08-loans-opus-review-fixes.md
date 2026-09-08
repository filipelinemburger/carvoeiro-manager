# Player loans — opus final-review fix wave

Fixes every finding from the opus whole-branch review of `24f3408..c99c22b`.
Three Important, six Minor, three test gaps. The feature is already merged to
main and pushed; each task below lands on main directly.

## About the test code in this plan

There are **no test snippets here, by design**. Four times on the previous two
plans I wrote test code from pattern-memory and it diverged from the real file
(wrong mock namespace, a `Career` helper that does not exist, `formatBRL` vs
`formatCurrency`, `Player.setPosition` which is not a method). Every task names
the test file and states what the test must *prove*. The implementer **reads
that file first** and writes the test in full, in the file's own idiom.

A test that still passes with the production guard deleted is not a test. Where
a task adds a guard, the implementer must verify the negative: remove the guard,
watch the new test fail, restore it.

## Standing constraints

- Backend: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`,
  OrbStack running for Testcontainers.
- **One Gradle invocation at a time across the whole session.** Concurrent runs
  on this repo fail with `EOFException` / `NoSuchFileException` under
  `build/test-results/`, which are not test failures.
- Run only your task's own tests. The controller runs the full suite once at the end.
- `git diff` needs `--no-ext-diff` (broken global external differ).
- Never parse `build/test-results/` without checking XML mtimes — the directory
  persists between runs and will happily report a previous run's result.

---

## Task 1 — the `bestXi` fallbacks must respect the loan bar

**Finding: Important 1.** `LineupService.bestXi` filters `eligible` through
`loanEligibility.mayFace`, but all three fallbacks read the unfiltered
`allPlayers`: the goalkeeper `.or()` (~`:447-453`), the outfield top-up when the
club has fewer than 10 eligible outfielders (~`:460-469`), and
`improvisedKeeper`'s own `.or()` (~`:498`). A player barred from facing the club
that owns him therefore still starts whenever the fallback fires — most
reachably in goal, after the borrowing club's own keeper is injured or
suspended, which is routine over a season.

**Do:** make the loan bar win in all three fallbacks.

**The trap, and it is a real one.** `improvisedKeeper` ends in
`orElseThrow(NO_GK_FOUND)`. Its javadoc says plainly why it exists: hard-failing
there *bricked the save* — advance threw 422 forever. If you filter its last
resort naively you resurrect exactly that bug. The invariant to hold is both
halves at once:

1. A barred player never takes the pitch while any alternative exists.
2. `NO_GK_FOUND` stays unreachable, and no path starts throwing where it
   previously degraded gracefully.

Read `TeamStrengthCalculator` and the engine's tolerance for a short XI before
choosing the shape. Prefer the bar; degrade below it only when the alternative
is throwing, and comment why at the line.

**Also:** the javadoc at ~`:425-426` currently documents the *opposite* for the
outfield branch — it states a barred player is deliberately eligible for the
top-up. That sentence becomes false; rewrite it. Graceful degradation is right
for injury and suspension, which are accidents of a season. The fixture-release
rule is a rule of the competition this feature exists to enforce, and it outranks
the convenience of always fielding eleven.

**Test** (`backend/src/test/java/br/com/carvoeiromanager/match/LoanFixtureEligibilityIT.java`,
or a sibling if that class does not fit): a borrowed player barred from facing
his owner, with **no eligible alternative in his position**, must not start.
Cover the goalkeeper case explicitly — it is the reachable one. Assert the match
still plays to completion rather than throwing.

---

## Task 2 — the loan departure guards, on every path and under test

**Findings: Important 2 and Minor 1, plus the sharpest test gap in the review.**

`LoanService.offerLoanOut` calls
`squadDeparture.requireDepartureAllowed(...)` (~`:76`), which enforces
`MIN_SQUAD_SIZE` and the last-goalkeeper rule. Two paths skip it:

- **`acceptCounter` (~`:168-196`)** re-checks contract, live match, ownership and
  borrower room, but never the departure guard — although it calls the same
  `execute`. Offer to lend your backup keeper, let the AI counter, terminate your
  first-choice keeper (legal: window-independent, and two keepers exist at that
  moment), then accept: the club is left with **zero goalkeepers**.
  `TransferService.respondToIncoming` gets this right, re-running
  `requireSellAllowed` on every accept branch (~`:252`, `:263`). Mirror it.
- **`offerLoanIn` (~`:91-115`)** never runs the guard against the *owning* AI
  club, so an AI club can be lent below a fieldable squad. Spec §8 says a club
  cannot lend itself below a fieldable squad and does not restrict that to the
  managed side. Partly masked today by `SellerContextFactory.isNoCover` treating
  a sole keeper as a `KEY_PLAYER` refusal — that is luck, not a guard.

**The test gap is the reason both survived:** `offerLoanOut:76` has **no test at
all**. Delete the line and the entire suite passes. Cover it now — squad floor
*and* last goalkeeper — and cover both guards this task adds.
`LoanServiceOutIT` currently covers only `SQUAD_FULL`; read it before adding.

---

## Task 3 — enforce the transfer window on loans, server-side, both directions

**Finding: Minor 4.** The backend imposes no window on loans at all. The client
disables "Pedir emprestado" when the window is shut but leaves "Emprestar"
enabled. So loans out work year-round, loans in do not, for no stated reason,
and neither is actually enforced — a direct API call bypasses both.

**Ruling (mine — the spec is silent on this).** Impose the window on the server,
in both directions. A loan is a squad movement like any transfer, the backend
already enforces the window for transfers (`TransferService:406`) and contracts
(`ContractService:166`), and the client's existing loan-in gate shows the intent
was always there. Contract *termination* stays window-independent — that was an
explicit product decision and this does not touch it.

**Do:** mirror `TransferService`'s exact pattern (`TRANSFER_WINDOW_CLOSED`) in
`offerLoanOut` and `offerLoanIn`. Check how `TransferService` treats the window
at *accept* time (~`:299`, `:312`) and match it for `acceptCounter` — an offer
that outlives the window should not execute.

Gate "Emprestar" on `windowOpen` in the frontend the same way "Pedir emprestado"
already is.

**Expect blast radius.** Existing loan ITs may build save games at dates outside
the window and will start failing. That is the fix working, not a regression —
move their dates, do not weaken the guard.

**Test:** both directions refused with `TRANSFER_WINDOW_CLOSED` outside the
window and accepted inside; the frontend button disabled.

---

## Task 4 — `ClubFinancialHealthEvaluator` must charge what clubs are actually charged

**Finding: Important 3.** `FinanceService.monthlyWageBills` (~`:365-383`) was
taught the owner/borrower split. `ClubFinancialHealthEvaluator.monthlyWageBill`
(~`:52-58`) was not — it still sums the **full** wage of everyone returned by
`findByClubId`. Its own class javadoc claims it uses "the same
`FinanceCalculator#monthlyWage` formula `FinanceService` charges every club each
in-game month". That sentence is now false for any club on either side of a loan.

The borrower is evaluated on a wage it does not pay (over-stated); the lender is
evaluated without a wage it does pay (under-stated). The classification feeds
`AiListingEngine`'s FINANCIAL trigger, `AiTransferEngine`'s buyer filter, and
`SellerContext.health`, which prices every bid. A club nudged across the
DESPERATE line fire-sells its two best players.

**Do:** route both callers through one helper so they cannot drift again. The
split logic lives in `FinanceService` today — extract it rather than
reimplementing it, and make the duplication structurally impossible. Fix the
javadoc.

**Honest bound, for the commit message:** one wage against a 20-45 man bill is a
few percent. This shifts clubs already at the boundary; it does not invent
disasters.

**Test:** a club on each side of a loan classifies from the split bill, not the
full one.

---

## Task 5 — a lent player leaves the market with him

**Finding: Minor 2.** Neither `Player.loanTo` nor `LoanService.execute` clears a
transfer listing, so a listed player who is then lent stays on the market —
displayed with `clubName` of the **borrowing** club (`TransferService.unpagedMarket`
~`:112-121`). "Fazer proposta" stays enabled, always 422s, and
`PLAYER_ALREADY_ON_LOAN` exists in `ErrorCode.java:33` but **not** in
`frontend/src/api/errorMessages.ts` — I confirmed zero occurrences under
`frontend/src/` — so the manager gets the generic "Erro ao enviar proposta." and
no reason. Money is safe; this is presentation.

**Do, all three:**
1. `LoanService.execute` clears the listing when the player moves. He is not the
   owner's to sell while lent, and certainly not the borrower's.
2. Add `PLAYER_ALREADY_ON_LOAN` to `errorMessages.ts` in the file's own pt-BR idiom.
3. Gate "Fazer proposta" on `onLoan` in `TransferMarketPage.tsx`.
   `MarketPlayerResponse` already carries the flag.

Clearing the listing does not collide with rollover: `resetSeasonState()` wipes
`transferListed` during the contract tick, and the SURPLUS listing runs after it.

**Test:** backend — lending a listed player clears the listing. Frontend — the
button is disabled for an on-loan player and the error message renders.

---

## Task 6 — a countered loan offer must survive the dialog closing

**Finding: Minor 3.** `SquadPage.tsx` and `TransferMarketPage.tsx` both drop
`loanResult` on close, and there is no endpoint to list or withdraw loan offers —
`LoanController` has three POSTs and nothing else. `LoanOffer.markWithdrawn()`
and `LoanOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn` are both dead
code, waiting for exactly this. A refresh loses the `offerId` permanently.
Recoverable today only because `LoanNegotiator` is RNG-free, so re-offering the
identical terms is certain to be accepted — if the manager memorised them.

**Do:** a `GET` listing the save's open loan offers (`COUNTERED` at minimum), and
a withdraw endpoint that uses the already-present `markWithdrawn()`. Surface
pending counters in the UI so the manager can accept or withdraw one he did not
act on immediately. Keep it proportionate — transfers have a full inbox; loans
need the counter to be reachable, not a second inbox.

**Test:** a countered offer is retrievable after the dialog closes and can still
be accepted; a withdrawn one cannot.

---

## Task 7 — a loanee who retires is reported once, not twice

**Finding: Minor 5.** `SeasonRolloverService` adds the returning loanee to
`loanReturns` at ~`:254`, and the aging pass at ~`:301-313` can then add the same
player to `retirements`. The season summary lists him under both headings.
Cosmetic, but wrong on screen.

**Do:** the ordering is correct and must not change — returns have to precede the
aging pass. Reconcile at the end instead: a player who retired is not also a
returning loanee in the summary. Decide which heading wins (retirement is the
more informative one) and document the choice at the line.

**Test:** `LoanRolloverIT` — a lent player old enough to retire on return appears
exactly once in the summary.

---

## Task 8 — delete the loan-blind `LineupService` overloads

**Finding: Minor 6.** `startingXi(save, club)`, `bestXi(club, date)`,
`benchPlayers(save, club)`, `bestBench(club, xi)` and
`savedIneligibleStarters(save, club)` all pass `opponentClubId = null`, which
**disables the loan rule**, and no production call site uses any of them — all
five live callers pass the opponent. They survive for tests only. The next
caller who reaches for the shorter signature silently opts out of the rule.

**Do:** delete them and update every test to pass the opponent explicitly (`null`
where the test genuinely means "no opponent" — then it is a visible choice, not a
default). If a signature turns out to have a production caller after all, say so
and keep it rather than forcing the deletion.

**Test:** none new. The suite must compile and stay green.

---

## Closing the wave

After the last task: one clean full backend suite (`rm -rf build/test-results/test`
first, `echo "GRADLE_EXIT=$?"` into the log, trust only XML newer than the run's
start), plus `npx vitest run` and `npx tsc -b` on the frontend. Then a scoped
re-review of the whole wave's diff.

---

## Task 9 — a short XI must cost strength, not gain it

**Found by Task 1's review, not by the opus review.** `TeamStrengthCalculator`
computes `attack = attackSum / xi.size()` and
`outfieldDefense = outfieldDefenseSum / outfieldCount` — pure averages, with no
squad-size term anywhere downstream (formation, mentality and style multipliers
are all size-independent). So a club that loses a **below-average** player and
fields ten comes out with *higher* attack and outfield defense than it had with
eleven. Ten men are stronger than eleven.

This is **pre-existing** — `LiveMatchEngine`'s own comment reads
`red-card = fewer players` and recomputes strength through the same averages, so
a red card can currently strengthen the team that received it. It is in this wave
because Task 1's loan-bar fix turns it from a rare accident into a routine,
rule-driven event on both the AI and managed sides.

**Do:** make a short XI cost strength proportionally to the missing players. A
side of ten must never out-rate the same side of eleven that includes its worst
player.

**Scope discipline.** This touches match balance for *every* cause of a short XI,
red cards included — which is the point; a red card strengthening your team is a
bug by any reading. Do not attempt a wider rebalance: no change to the formation,
mentality or style multipliers, and no change to what counts as a red card. If
you find the fix cannot be confined this way, stop and report rather than
widening it.

**Test:** the same XI minus its weakest outfielder rates strictly lower, not
higher. Cover the red-card path too, since it shares the calculation.

---

## Task 10 — the opponent scouting panel must respect the loan bar

**Found by Task 1's review.** `MatchPreviewService` (~`:158`) builds the
opponent's "best players" panel from `findByClubIdAndYouthProspectFalse(opponentId)`
with no `mayFace` filter, and there is no other filter in that path. Lend a star
to the club you play next and the pre-match preview headlines **your own player**
as their danger man, for a fixture he is barred from.

**Do:** apply the same loan bar the XI selection uses. The panel previews a
specific fixture, so the opponent is already in hand.

**Test:** a lent player barred from the fixture does not appear in the preview of
the opponent's best players; one whose loan negotiated the release still does.
