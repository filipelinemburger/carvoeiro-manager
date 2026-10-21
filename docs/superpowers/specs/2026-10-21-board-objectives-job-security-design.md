# Diretoria — Board Objectives and Job Security — Design Spec

**Date:** 2026-10-21
**Status:** Approved 2026-10-21 — planned, not yet built.
**Builds on:** season advance (`SeasonAdvanceService.recordMatchRound`), rollover
(`SeasonRolloverService`), the competition/phase engine (`PhaseResolver`), the news feed
(`NewsItem`), and the finance model (`Club.balance`).
**Does not depend on:** promotion/relegation, which is still unbuilt (see §3 and §13). The board
nevertheless sets targets for **every national division** (Série A, B and C), so it needs no rework
when that slice moves Criciúma between them.

---

## 1. Why this exists

Today nothing is at stake. A season ends, the summary shows where Criciúma finished, and the next
season starts the same way whether the manager won the league or finished bottom. Nobody judges
the manager, so a bad season costs nothing and a great one earns nothing beyond prize money.

This feature adds a **board** (*a diretoria*). It sets expectations before each season, reacts to
every match, warns the manager when things go badly, and can sack them. Every result then carries
weight, and so does every decision behind it: the lineup, the signings, the wage bill.

It should feel like Brazilian football: the board is impatient, the press calls the job
"ameaçado" (under threat), and a run of bad results ends with the manager sacked mid-season.
It also has to stay fair. The manager always sees exactly where they stand and gets a warning
before the end.

## 2. Current state *(what the design starts from)*

- **There is no board, objective or job-security concept anywhere.** "board" appears only in
  comments ("attribute board", "scoreboard").
- **`Club.reputation` is the squad's average overall, written once** at career creation
  (`SaveGameService.java:185`, `FinanceCalculator.reputation`) and never updated afterwards. It is
  stale by season 2, so it **cannot** be used to set expectations. This design computes a fresh
  strength value instead (§5.1).
- **The managed club plays two competitions a season, interleaved.**
  - *Campeonato Catarinense*: 12 clubs, an 11-round first phase with the top 8 qualifying, then
    two-legged quarterfinals, semifinal and final, 17 match units in all.
  - *Série B*: 20 clubs, double round robin, no phases.

  `SeasonCalendarGenerator` (around line 131) interleaves them round by round on one 5-day
  cadence, so the Catarinense runs *alongside* roughly the first 17 Série B rounds; it does not
  come before them. Série A and Série C run in the background.
- **Every competition in the seed has `type: "LEAGUE"`, and Série C has phases too.** Neither the
  type nor "has phases" can tell a state championship from a national league (§5.5).
- **`SeasonAdvanceService.recordMatchRound` is the single chokepoint for every managed match
  round.** It is shared by the instant advance and by the live-match finish, and it decides
  `seasonFinished`, setting `SaveGameStatus.FINISHED`, *before* paying the season-end prize
  (`processSeasonEnd`, around line 240). The board hooks in here.

  It also runs for rounds of a managed competition that the club is **no longer playing in**: once
  the club is out of the Catarinense, `advance` keeps popping the later knockout rounds
  (`SeasonEventRepository` membership; `LiveMatchService` refuses them with
  `MANAGED_CLUB_NOT_PLAYING`).
- **`PhaseResolver.resolveIfPhaseComplete`** runs when a whole phase completes. It detects two
  things today: the managed club failing to qualify from a round-robin phase (qualification news),
  and the winner of any final (`CHAMPION` news, deliberately not limited to the managed club). It
  does **not** detect the managed club losing a quarterfinal or semifinal tie. That detection is
  new work (§5.3).
- **`SaveGameStatus` is `IN_PROGRESS | FINISHED`.** `advance` rejects `FINISHED`, and `rollover`
  requires it. `LiveMatchService.kickoff` checks no status at all.
- **News items are serialized `AdvanceResponse`s, not free-form JSON.** `NewsFeedItemResponse`
  deserializes every payload as `AdvanceResponse`, and `NewsItem.type` is the response's `type`.
  Non-match news (`PhaseResolver.persistNews`) builds an otherwise-empty `AdvanceResponse`. A new
  news type also needs:
  - a branch in `AdvanceEventCard`;
  - entries in `newsTitle`/`newsIcon` in `features/news/newsMeta.tsx`;
  - a member in the frontend `AdvanceResponse` type union.

  Without those, the item renders as an empty card (see the javadoc on `PhaseResolver.persistNews`).
  Rollover purges the feed (`newsItemRepository.deleteBySaveGameId`). `news_items.type` is
  `VARCHAR(32)`.
- **Morale** is a per-player −5..+5 integer. It is unrelated to this feature and stays unchanged.
- **Latest migration:** `V56__live_match_slot_schedule.sql`.

## 3. Scope

In scope:

1. Three **season objectives** set at the start of every season: league, state championship and
   finances. The league objective has a target table for **each division** the managed club can
   play in: Série A, Série B and the phased Série C (§5.2).
2. A **board confidence** value from 0 to 100 that moves after every managed match and at key
   moments.
3. An **ultimatum** warning, **mid-season sacking**, and a **season-end review** that can also
   sack.
4. A **sacked** end state for the career.
5. A per-career **"sacking enabled"** switch, chosen at career creation.
6. UI: a dashboard *Diretoria* card, news items, a board section on the season summary, and a
   sacked screen.

Out of scope, deliberately:

- **Promotion and relegation.** Still unbuilt: today Criciúma is always in Série B, so the Série A
  and Série C targets are reachable only in tests until that slice lands. Objectives are phrased as
  **table positions or stages** ("Terminar no G-4", "Chegar às semifinais"), never as "subir" (go
  up) or "cair" (go down), so they stay true before and after it.
- **Job offers from other clubs.** The game is built around managing Criciúma. Staff, academy and
  lineup are seeded only for the managed club, so switching clubs is a project of its own. A
  sacking ends the career.
- **A manager contract** (length, wage, renewal). Job security is carried entirely by confidence.
- **Board-controlled transfer budgets**, **fan mood** as a separate meter, **press conferences**
  and **derby weighting**. These are possible follow-ups (§13), not part of this design.
- **AI managers being sacked.** Only the player's own job is modelled.

## 4. Data model

Migration `V57__board.sql`:

```sql
CREATE TABLE board_states (
    save_game_id        UUID PRIMARY KEY REFERENCES save_games(id) ON DELETE CASCADE,
    confidence          INT     NOT NULL,           -- 0..100
    ultimatum_active    BOOLEAN NOT NULL DEFAULT FALSE,
    matches_since_ultimatum INT NOT NULL DEFAULT 0,
    sacking_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    grace_season_year   INT                          -- no sacking during this season; NULL = none
);

CREATE TABLE board_objectives (
    id              UUID PRIMARY KEY,
    save_game_id    UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    season_year     INT  NOT NULL,
    kind            VARCHAR(20) NOT NULL,            -- LEAGUE | STATE | FINANCE
    competition_id  UUID,                            -- NULL for FINANCE
    target_position INT,                             -- LEAGUE: worst acceptable final position
    target_stage    VARCHAR(20),                     -- QUARTERFINAL|SEMIFINAL|FINAL|CHAMPION (STATE, phased LEAGUE)
    tier            INT,                             -- LEAGUE: the division the target was set for
    outcome         VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | MET | FAILED
    UNIQUE (save_game_id, season_year, kind)
);

CREATE TABLE board_confidence_changes (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    event_date    DATE NOT NULL,
    seq           INT  NOT NULL,                     -- ordering within a save, like news_items.seq
    reason        VARCHAR(40) NOT NULL,              -- BoardChangeReason
    delta         INT  NOT NULL,
    confidence_after INT NOT NULL,
    payload       TEXT                               -- JSON: opponent, score, position… for the label
);
```

The same migration adds a competition scope (§5.5):

```sql
ALTER TABLE competitions ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'NATIONAL'; -- NATIONAL | STATE
ALTER TABLE competitions ADD COLUMN tier  INT;                                    -- 1 = A, 2 = B, 3 = C; NULL for STATE
-- backfill existing saves
UPDATE competitions SET scope = 'STATE' WHERE name = 'Campeonato Catarinense';
UPDATE competitions SET tier = 1 WHERE name = 'Série A';
UPDATE competitions SET tier = 2 WHERE name = 'Série B';
UPDATE competitions SET tier = 3 WHERE name = 'Série C';
```

The seed JSON gains `"scope": "STATE"` on the Catarinense entry and `"tier": 1|2|3` on each Série,
and `NATIONAL` is the default scope when the key is absent. `SeedLoader` rejects a `NATIONAL`
competition without a tier, so a future division cannot silently get no board targets. Career
creation copies both fields onto `Competition`. Competitions survive rollover (only `seasonYear`
changes), so the fields persist without a copy. The backfill is the only place that matches by
name.

`SaveGameStatus` gains **`SACKED`**.

Notes on the data model:

- **`board_objectives` keeps every season's rows.** Past objectives and their outcomes are the
  manager's record, and the sacked screen (§9.4) lists them. Rows are never purged at rollover.
- **`board_confidence_changes` is season-scoped.** It is purged at rollover alongside the news
  feed, because it explains this season's number, not the career's.
- **The state lives in its own table, not as columns on `save_games`.** `SaveGame` already carries
  a lot of season bookkeeping, and the board is a feature package of its own (`board/`).

## 5. Objectives

Objectives are set by `BoardService.setSeasonObjectives(save)`, called:

- at career creation, after clubs and squads are seeded; and
- at the end of `SeasonRolloverService.rollover`, after squads have aged, contracts have ticked
  and the new calendar exists. This is after the news purge, so the objectives news item survives.

Each call writes three `board_objectives` rows and one `BOARD_OBJECTIVES` news item, dated the
season's first calendar day.

### 5.1 Club strength

`ClubStrength.of(players)` is the **average overall of the club's 16 best players by overall**,
counting the players currently *playing* for the club (`club_id`). That means loanees count for the
borrower, not the owner. Youth prospects and free agents are excluded. It is a pure function. The
same helper is used for objective targets (§5.2) and for match expectations (§6.1), so the two
agree on who is strong.

Strength is recomputed every time it is needed. It is never stored on `Club` and never mixed up
with the stale `reputation`.

### 5.2 League objective (every division)

Rank the national league's members by `ClubStrength`, strongest first; ties are broken by club
name. That gives the managed club an **expected rank** `r`. The target depends on the division
(`Competition.tier`) and on `r`. All three divisions have 20 clubs today; the bands below are
written for 20, and `LeagueTargets` scales them proportionally (`ceil(band · n / 20)`) if a division
ever has a different size.

**Série A (tier 1)** — round robin, Z-4 relegated in reality:

| Expected rank `r` | `target_position` | Objective text (pt-BR) |
|---|---|---|
| 1–3   | 4  | Terminar no G-4 |
| 4–8   | 6  | Terminar no G-6 |
| 9–14  | 12 | Terminar entre os 12 primeiros |
| 15–20 | 16 | Terminar fora do Z-4 |

**Série B (tier 2)** — round robin, G-4 promoted and Z-4 relegated in reality:

| Expected rank `r` | `target_position` | Objective text (pt-BR) |
|---|---|---|
| 1–3   | 4  | Terminar no G-4 |
| 4–7   | 8  | Terminar entre os 8 primeiros |
| 8–13  | 10 | Terminar na primeira metade da tabela |
| 14–20 | 16 | Terminar fora do Z-4 |

**Série C (tier 3)** — phased: a 19-round first phase, top 8 into two-legged quarterfinals,
semifinal and final (the four semifinalists are the clubs promoted in reality). The target is a
**stage**, like the state objective, except for the weakest band, which is a first-phase position:

| Expected rank `r` | Target | Objective text (pt-BR) |
|---|---|---|
| 1–2   | `target_stage` FINAL        | Chegar à final da Série C |
| 3–5   | `target_stage` SEMIFINAL    | Chegar às semifinais da Série C |
| 6–10  | `target_stage` QUARTERFINAL | Classificar para as quartas de final |
| 11–20 | `target_position` 14 (first-phase table) | Terminar a primeira fase entre os 14 primeiros |

The board asks for a little *less* than the club's rank, because the table is noisy. A side ranked
2nd that finishes 4th has met expectations.

The objective row stores the `tier` it was set for, so the review copy and the sacked screen's
career record read correctly even after a future promotion moves the club.

### 5.3 State objective (Catarinense)

Rank the 12 members the same way:

| Expected rank | `target_stage` | Text |
|---|---|---|
| 1–2  | CHAMPION     | Ser campeão catarinense |
| 3–4  | FINAL        | Chegar à final do Catarinense |
| 5–6  | SEMIFINAL    | Chegar às semifinais do Catarinense |
| 7–12 | QUARTERFINAL | Classificar para as quartas de final |

The same stage rules resolve a Série C `target_stage` LEAGUE objective (§5.2); everything below
applies to it unchanged, except that the deltas come from the LEAGUE rows of §6.2.

The state objective is resolved **as soon as its outcome is known**, at phase completion inside
`PhaseResolver.resolveIfPhaseComplete`. All ties in a knockout phase play the same rounds, so phase
completion falls on the date of the managed club's own second leg. No per-tie check is needed.

A stage counts as *reached* by winning the phase before it: QUARTERFINAL means qualifying from the
first phase, SEMIFINAL means winning the quarterfinal, FINAL means winning the semifinal, and
CHAMPION means winning the final. When a phase completes:

- the managed club **reached** `target_stage` → the objective is MET now, even if the run goes on;
- the managed club was **knocked out** (or failed to qualify) before reaching it → FAILED now;
- the managed club **won the final** → an extra `TITLE_WON` confidence change (§6.2), whether or
  not the objective was already resolved.

Detecting that the managed club lost a knockout tie is new. Today `PhaseResolver` only reports
round-robin non-qualification and the final's winner. The check uses the same `TieResolver` the
phase already runs. The manager does not wait until December to hear the board's opinion of a
quarterfinal exit.

### 5.4 Finance objective

"Terminar a temporada com saldo positivo": `Club.balance > 0` when the season finishes, checked
**after** the season-end prize is paid. The prize is part of the season's money, and a mid-table
prize can flip a small deficit. There is deliberately one simple rule. The point is that reckless
wage bills carry a cost, not to model budgets.

### 5.5 Finding the competitions

The competitions are found by **scope**, never by name or by whether they have phases. The
managed club's `NATIONAL` competition is the LEAGUE, and its `STATE` competition is the STATE
championship. Both "unphased = league" and the seed's `type` fail: every competition is
`type: "LEAGUE"`, and Série C is phased.

If a season lacks one of them, its objective is simply not created; the board must not assume all
three exist. The division's targets come from `tier` (§5.2), never from the competition's name.

When the national league is phased (Série C), a `target_position` is read against its **first
phase's** final table and resolves when that phase completes; a `target_stage` resolves through the
phase hook exactly like the state objective. An unphased league (A, B) resolves at season end.

## 6. Confidence

Confidence is an integer from 0 to 100. A new career starts at **60**. Higher is safer.

All arithmetic lives in a pure, deterministic `BoardConfidenceCalculator`: no RNG, no
repositories, constants in one place. `BoardService` gathers the inputs and persists the outcome.
The constants below are the starting tuning. The builder may adjust them after playtesting, but
the *shape* is the design.

### 6.1 After every managed match

A *managed match* is a round in which the managed club **has a fixture**. A round of a managed
competition that the club is no longer in (§2) is skipped entirely: no confidence change, and it
does not count toward `matches_since_ultimatum`.

The board compares the points the club actually won with the points it was **expected** to win:

```
diff     = ClubStrength(own) − ClubStrength(opponent)          // computed at the match
expected = clamp(1.35 + 0.08·diff + (home ? +0.30 : −0.30), 0.30, 2.70)
points   = 3 / 1 / 0   (for a knockout leg: the leg's own result)
delta    = round((points − expected) · 3)
```

Then:

- **Heavy defeat:** if the club lost by 4 or more goals, an extra −3.
- **State championship weighting:** Catarinense matches count at half weight
  (`delta = round(delta / 2)`), because the board cares most about the league.
- **League trajectory** (national league only, from its 7th match on, once the table means
  something): compare the current position in the league's round-robin table (Série C: the first
  phase's, while it is running) with the target. For a `target_position`, use it directly. For a
  `target_stage`, use the qualifying line, 8th, as the reference. At or above the reference: +1. Five
  or more places below: −1. Once the round-robin phase is over, the rule stops.
- **Negative balance:** −1 per managed match while `Club.balance < 0`.
- The total change from one match is clamped to **−8..+8**. Confidence is clamped to **0..100**.

Each part with a non-zero value is written as its own `board_confidence_changes` row
(`MATCH_RESULT`, `HEAVY_DEFEAT`, `TABLE_POSITION`, `NEGATIVE_BALANCE`), so the card can say *why*
confidence moved. If the clamp trims the total, the trim comes off the last row written.

Some sanity checks on the formula (10 points of strength apart for "much"): an away win against a
much stronger side scores +8. A home loss to a much weaker side scores −7. A home draw between
equals scores −2. An away draw between equals scores 0.

### 6.2 When an objective resolves

| Event | Delta |
|---|---|
| STATE objective met | +6 |
| STATE objective failed | −8 |
| Title won in a phased managed competition — Catarinense, or Série C (`TITLE_WON`, in addition to any row above) | +4 |
| LEAGUE met, finishing 4+ places better than the target | +20 |
| LEAGUE met (a stage target is met the moment it is reached, so it never earns the +20) | +12 |
| LEAGUE failed by 1–3 places (or 1 stage short) | −12 |
| LEAGUE failed by 4+ places (or 2+ stages short, incl. not qualifying for a stage target) | −25 |
| LEAGUE: finished in the Z-4 (17th–20th) of **Série A or B** | −35 (instead of the two rows above) |

Stages are ordered QUARTERFINAL < SEMIFINAL < FINAL < CHAMPION, with failing to qualify one stage
below QUARTERFINAL. The Z-4 row exists only for the divisions with a relegation zone; Série C has
none (there is no Série D).
| FINANCE met | +4 |
| FINANCE failed | −10 |

FINANCE and an unphased LEAGUE resolve when the season finishes (§7.2). STATE and a Série C LEAGUE
resolve when their outcome is known (§5.3, §5.5).

### 6.3 Between seasons

At rollover, after the season-end review, confidence moves **halfway back toward 60** (rounding
toward 60). A new season is a fresh start, but a great or a terrible year still carries over in
part. The change is logged as a `NEW_SEASON` row, the first in the new season's purged log, and
the ultimatum is cleared.

### 6.4 Mood label

The frontend maps confidence to a label: **≥ 80** *Muito satisfeita* · **60–79** *Satisfeita* ·
**45–59** *Neutra* · **30–44** *Preocupada* · **15–29** *Insatisfeita* · **< 15** *Cargo por um
fio*. The labels live only in the frontend. The API returns the number.

## 7. Warnings and sacking

### 7.1 During the season

After every managed match, `BoardService` checks the following in order:

1. **Ultimatum.** If confidence is ≤ **30** and no ultimatum is active: set `ultimatum_active`,
   reset `matches_since_ultimatum` to 0, and post a `BOARD_ULTIMATUM` news item ("A diretoria
   exige reação imediata"). It is issued only once per dip.
2. **Recovery.** If an ultimatum is active and confidence rises to ≥ **45**: clear it and post
   `BOARD_ULTIMATUM_LIFTED`.
3. **Mid-season sacking.** Every one of these must hold:
   - confidence ≤ **15**;
   - an ultimatum is active and at least **3** managed matches have been played since it was issued;
   - the club has played at least **6 national-league matches** this season (whichever division
     it is in). A new manager gets time to settle in. The Catarinense runs alongside the league
     (§2), so its matches still move confidence (at half weight) and count toward the 3 matches
     after an ultimatum. On their own, though, they can never get a manager sacked before the
     league is six rounds old;
   - `sacking_enabled` is true and the season is not the `grace_season_year`.

   When they all hold, the manager is sacked (§7.3).

The ultimatum guarantees that the manager always gets a warning, and three more matches to respond,
before the end.

### 7.2 At season end

When `recordMatchRound` determines `seasonFinished`, `BoardService.reviewSeason(save)` runs
**after** `processSeasonEnd` has paid the prize (§5.4) and decides the final status. The existing
`save.setStatus(FINISHED)` moves below the prize call, and the board sets either `FINISHED` or
`SACKED`. The review does the following:

1. Resolve FINANCE and an unphased LEAGUE (§6.2). STATE and a phased LEAGUE are normally resolved
   already; if either is still `PENDING`, resolve it now with the stage or position reached.
2. Post a `BOARD_REVIEW` news item listing each objective, its outcome and the resulting confidence.
3. If confidence is **< 25** after the review, `sacking_enabled` is true and the season is not the
   grace season, the manager is sacked (§7.3). Otherwise the status becomes `FINISHED` as it does
   today.

A season-end sacking is therefore possible even without a prior ultimatum. Finishing in the Z-4
against a mid-table target (−35) will usually trigger it. That is intended: the end of a season is
when real boards make this decision.

### 7.3 Being sacked

- `SaveGameStatus` becomes **`SACKED`**, and a `BOARD_SACKED` news item is posted.
- **The world freezes.** Calendar events stay unprocessed and no rollover happens. The career is
  kept (`DELETE /save-games/{id}` still works) and can be opened to look at, but not to play on.
- **Guards.** `advance`, `LiveMatchService.kickoff` and `rollover` reject a `SACKED` save with
  the new `ErrorCode.MANAGER_SACKED` (409 Conflict), using the standard error body. Every other
  mutation (transfers, lineup, training…) is left alone: it cannot move time forward, so it cannot
  break anything, and guarding fifty endpoints would be noise. *(Builder may tighten this if a
  mutation turns out to have side effects that matter.)*
- If the sacking happens at the end of a **live** match, the live match has already finished by
  then (the hook runs inside `completeUserMatch`), so no live match is left dangling.

### 7.4 With sacking disabled

When `sacking_enabled` is false, everything runs the same: objectives, confidence, ultimatums and
reviews. At the point where the manager *would* be sacked, a `BOARD_SACKING_AVERTED` news item is
posted instead ("A diretoria queria sua saída, mas decidiu mantê-lo no cargo"). Confidence is then
raised to **30**, so the board's displeasure stays visible without repeating every match.

### 7.5 Existing careers

When the feature ships, careers already in progress have no `board_states` row.
`BoardService.ensureState(save)` creates one lazily the first time the board is touched, whether
from a match hook, the GET endpoint or a rollover:

- confidence 60, sacking enabled;
- `grace_season_year = current seasonYear`. The season in progress can warn and review but
  **cannot sack**, because the manager made this season's decisions without knowing a board
  existed;
- objectives for the current season, computed from today's squads (§5.2–5.4). If the Catarinense
  is already over, its STATE objective is created already resolved from the stage reached, with
  **no** confidence change. There is no retroactive punishment.

New careers get no grace season. The round-7 rule and the ultimatum are their protection.

## 8. Where it hooks in

| Hook | Call |
|---|---|
| `SaveGameService` career creation, after seeding | `boardService.initialize(save, sackingEnabled)` → state + objectives |
| `SeasonAdvanceService.recordMatchRound`, after `phaseResolver.resolveIfPhaseComplete` | `boardService.afterManagedMatch(save, round fixtures, competition)`; a no-op when the managed club has no fixture in the round |
| same method, where `seasonFinished` is true, after `processSeasonEnd` | `boardService.reviewSeason(save)` decides `FINISHED` vs `SACKED` |
| inside `afterManagedMatch`, when the round completed a phase (`PhaseResolver.isPhaseComplete`, today's private `phaseComplete` made public) | resolve whichever stage objective points at that competition (STATE, or a Série C LEAGUE) and record `TITLE_WON` for a STATE final (§5.3). `PhaseResolver` does **not** call the board: it lives in `competition/` and also runs for background competitions, and a call back into `board/` would make the two packages depend on each other |
| `SeasonRolloverService.rollover`, at the end | `boardService.startNewSeason(save)` → regression, log purge, new objectives |
| `advance`, `kickoff`, `rollover` entry | reject `SACKED` (`MANAGER_SACKED`) |

`afterManagedMatch` runs for both the instant advance and the live-match finish, because both go
through `recordMatchRound`. It must never be called a second time for the same round.

If `afterManagedMatch` sacks the manager on the season's last round, the season-end block still
pays the prize but **skips** `reviewSeason`, so the `SACKED` status is never overwritten with
`FINISHED`.

The sacking decision is made inside the same transaction as the match round. If the round rolls
back, so does the sacking.

## 9. Interface

### 9.1 API

`GET /api/v1/save-games/{saveGameId}/board` → `BoardResponse`

```jsonc
{
  "confidence": 42,
  "ultimatumActive": false,
  "sackingEnabled": true,
  "graceSeason": false,
  "objectives": [
    { "kind": "LEAGUE", "competitionName": "Série B", "targetPosition": 8, "targetStage": null,
      "currentPosition": 11, "progress": "AT_RISK", "outcome": "PENDING" },
    { "kind": "STATE", "competitionName": "Campeonato Catarinense", "targetPosition": null,
      "targetStage": "CHAMPION", "currentPosition": null, "progress": null, "outcome": "MET" },
    { "kind": "FINANCE", "competitionName": null, "targetPosition": null, "targetStage": null,
      "currentPosition": null, "progress": "ON_TRACK", "outcome": "PENDING" }
  ],
  "recentChanges": [   // newest first, at most 8
    { "date": "2026-06-14", "reason": "MATCH_RESULT", "delta": -3,
      "payload": { "opponent": "Avaí", "score": "0–2", "home": true } }
  ]
}
```

- `progress` applies only to a `PENDING` objective. For LEAGUE it is `ON_TRACK` (position ≤
  target), `AT_RISK` (1–3 places worse) or `OFF_TRACK` (4 or more worse), measured against the
  reference of §6.1 for a stage target. It is `null` before the league's first round and once a
  stage target's round-robin phase is over.
- Each objective also carries `tier` (LEAGUE only), so the frontend picks the right text table. For FINANCE it is `ON_TRACK` when the balance is positive and `OFF_TRACK`
  otherwise. STATE has no live progress; it resolves quickly.
- `AdvanceResponse` gains one nullable field, `board` (`BoardEventResponse`):
  `{ confidence, delta, ultimatumIssued, ultimatumLifted, sacked, sackingAverted, objectives }`,
  where `objectives` uses the same shape as above and is filled only for objectives and review
  items.

  It serves two purposes:
  - on a MATCH response, the advance card can show "Diretoria −3", and the frontend learns about a
    sacking without a second request;
  - it is **the payload of every `BOARD_*` news item** (§9.3). News payloads are serialized
    `AdvanceResponse`s (§2), so board news is written the way `PhaseResolver.persistNews` writes
    phase news: an otherwise-empty `AdvanceResponse` with `type = BOARD_…` and `board` filled.
    No pt-BR text goes into `competitionName` or any other string field (§10).
- `CreateSaveGameRequest` gains `boolean sackingEnabled`, defaulting to true when absent.
- `SaveGameStatus` on the wire gains `"SACKED"`. The frontend union types in `api/types.ts` must be
  widened at both sites (lines 47 and 65).

Request and response DTOs are records, following the project convention.

### 9.2 Dashboard card — *Diretoria*

A new card on `DashboardPage`, next to the existing career card:

- the confidence as a horizontal bar (0–100), with the mood label and number. The bar turns
  warning/error colour below 45/30;
- an ultimatum banner when `ultimatumActive` is true ("Ultimato da diretoria");
- the three objectives, each with its text, live progress ("atualmente 11º") and a status chip
  (no caminho / em risco / fora da meta / cumprido / não cumprido);
- the last few changes, each as "−3 · Derrota para o Avaí (0–2)";
- a small note when `graceSeason` is true ("Temporada de adaptação: a diretoria avalia, mas não
  demite") and when sacking is disabled.

### 9.3 News items

New types: `BOARD_OBJECTIVES`, `BOARD_ULTIMATUM`, `BOARD_ULTIMATUM_LIFTED`, `BOARD_REVIEW`,
`BOARD_SACKED` and `BOARD_SACKING_AVERTED`, all within the `VARCHAR(32)` limit. Each one needs a
title and icon in `newsMeta.tsx`, a branch in `AdvanceEventCard` that renders from
`payload.board`, and a member in the frontend `AdvanceResponse` type union. Routine
per-match confidence changes are **not** news; they show on the card and the advance card. This
follows the termination spec's rule that the feed should not report back what the manager can
already see.

### 9.4 Sacked screen

When `saveGame.status === 'SACKED'`, the `Layout` sends every route to a full-page *"Você foi
demitido"* screen showing:

- the board's verdict text and the final confidence;
- a career record: seasons managed, matches W/D/L, titles won, and each season's objectives with
  their outcomes. It is built from `board_objectives`, the season archive, and the current
  (unarchived) season's live tables;
- **two actions:** "Nova carreira" (opens `NewCareerDialog`) and "Ver tabela final"
  (a read-only link to `/table`; navigation to read-only pages stays allowed).

`LoadCareerDialog` shows a "Demitido" chip on sacked careers.

### 9.5 Season summary

`SeasonSummaryPage` gains a *Diretoria* section: each objective with its outcome and the
confidence before and after the review.

### 9.6 New career dialog

`NewCareerDialog` gains a checkbox, checked by default: **"A diretoria pode me demitir"**, with a
one-line hint that unchecking it keeps objectives and warnings but never ends the career.

## 10. Copy

All player-facing text is in pt-BR and lives in the frontend. The backend sends enums and numbers:
`kind`, `targetStage`, `reason`, `progress`, `outcome`. That follows the existing pattern
(`staffLabels.ts`, `roleLabels.ts`). Competition names come from data and are used without articles
("Série B: meta cumprida"), for the same gender-agreement reason `PhaseResolver.emitChampionNews`
documents.

## 11. Testing

Follow the project's Given/When/Then style and descriptive test names.

- **`BoardConfidenceCalculator` (unit, exhaustive):** expected points across strength diffs and
  home/away; the clamps; heavy defeat; Catarinense half weight; the trajectory rule before and
  after round 7; each objective-resolution delta, including the Z-4 override; regression toward 60
  (both directions, with rounding).
- **Objective targets (unit):** each rank band for LEAGUE in **each tier** (A, B, C) and for
  STATE; proportional scaling for a non-20 field; tie-breaks; a missing competition creates no
  objective; a `NATIONAL` seed competition without a tier is rejected; `ClubStrength` counts loanees for the borrower and excludes
  youth.
- **Sacking rules (unit):** ultimatum issued once per dip; lifted at 45; no sacking before
  6 national-league matches, without an ultimatum, within 3 matches of it, in the grace season, or with
  sacking disabled (averted path → 30).
- **Integration (real Postgres, as the project's ITs do):**
  - a full season through `advance`: objectives exist, confidence moves, `BOARD_REVIEW` is posted
    and the status ends `FINISHED`;
  - a forced bad run gets an ultimatum, then a sacking: the status is `SACKED`, and `advance`,
    `kickoff` and `rollover` return 409 `MANAGER_SACKED`;
  - a live-match finish goes through the board hook exactly once;
  - after a rollover, confidence has regressed, the change log is purged, new objectives are set,
    and the previous season's objectives are still present;
  - an existing save with no board row gets grace, and is never sacked that season;
  - a Catarinense quarterfinal exit resolves STATE as FAILED on the second leg's date; reaching
    the target stage resolves it as MET while the run continues; winning the final adds
    `TITLE_WON`;
  - after the club is out of the Catarinense, the remaining Catarinense rounds popped by `advance`
    change neither confidence nor `matches_since_ultimatum`;
  - FINANCE is judged on the balance *after* the season-end prize: a small deficit that the prize
    covers counts as MET;
  - the V57 backfill marks an existing save's Catarinense as `STATE` and sets each Série's tier, and
    the LEAGUE objective resolves against Série B, not Série C;
  - with the managed club's membership moved into Série A and into Série C by the test (the only
    way to reach them before promotion exists), `setSeasonObjectives` writes a LEAGUE objective
    with the right tier and target. Resolution in those divisions (a Série A Z-4 finish taking the
    −35 row, a Série C quarterfinal exit failing a SEMIFINAL target by one stage) is covered by the
    pure calculator's unit tests, because a season cannot be played in them until promotion
    exists.
- **Frontend (React Testing Library):** the dashboard card in each mood band, with an ultimatum and
  with grace; the sacked screen redirect; the advance card's board delta; the new-career checkbox;
  the news labels for every new type.

## 12. Left to the builder

- The exact tuning constants in §6, adjusted after playtesting. Keep them together in
  `BoardConfidenceCalculator`.
- The visual details of the dashboard card and the sacked screen, following the existing MUI
  patterns.
- How the career record on the sacked screen is queried (one endpoint or several).
- Whether `ClubStrength` sits in `board/` or in `club/`.

## 13. Follow-ups this design leaves room for

1. **Promotion and relegation.** The targets already follow the club into its new division,
   because competitions are found by scope and targets by tier (§5.2, §5.5). That slice only adds
   review copy ("Acesso conquistado!", "Rebaixado") and may tune the bands once real movement is
   playable.
2. **Board-set transfer budget**, scaled by confidence.
3. **Clássicos.** Double weight for matches against Avaí, Figueirense, Chapecoense and Joinville.
4. **Fan mood** as a second voice that feeds the board.
5. **Job offers after a sacking**, once managing another club is possible.
