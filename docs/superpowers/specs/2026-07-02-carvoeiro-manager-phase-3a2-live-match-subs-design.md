# Carvoeiro Manager — Phase 3a-2: Bench, Substitutions & Live Match

## Context

Phase 3 ("Deep Manager") is delivered as shippable slices. **3a-1 — Fitness & Injuries** is
done: persistent per-player fitness, age bands, injury severity tiers, fitness→strength, and
fitness-adjusted AI rotation. It explicitly deferred *bench, in-match substitutions, partial-minutes
fitness decay, and minute-timed in-match injury events* to **3a-2 (this spec)**.

Today a match is an **instant, one-shot simulation**:

- [`MatchSimulationService.simulate(home, away, seed)`](../../../backend/src/main/java/br/com/carvoeiromanager/match/MatchSimulationService.java)
  runs all 90′ in a single synchronous pass and returns the full event list. The seed is fixed per
  fixture (`saveGameId.msb ^ fixtureId.msb ^ round`); re-running is O(90) — trivially cheap.
- One **Avançar** processes one `SeasonEvent`. A `MATCH` event simulates the **entire round** — the
  user's fixture via the *same* code path as every AI fixture — then routes to a static `/news`
  summary rendered by [`AdvanceEventCard`](../../../frontend/src/features/match/AdvanceEventCard.tsx).
- There is **no bench**. [`LineupPlayer`](../../../backend/src/main/java/br/com/carvoeiromanager/match/LineupPlayer.java)
  has a `starting` boolean that is always `true`; [`setLineup`](../../../backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java)
  deletes all rows and inserts exactly 11.
- Injuries roll **once, post-match** in
  [`MatchOutcomeApplier.applyInjuries`](../../../backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java)
  using a dedicated deterministic `injuryRng`.
- Persistent fitness decay currently assumes **every starter played a full 90′**.

3a-2 turns the user's own match into an **interactive, live, minute-by-minute experience** with a
bench and substitutions, and makes fitness decay reflect **minutes actually played**.

## Goal

Make the user's match a live, watchable game they can influence: name a bench, watch the match play
out with running commentary, and substitute at any point — reacting to the scoreline, fatigue,
timed injuries, and red cards — while preserving deterministic replay of the match given its inputs.

## Scope

**In scope (3a-2):**

1. **Bench** — name up to **9** substitutes on the lineup screen; persisted alongside the XI.
2. **Substitutions** — up to **5** per match, at **any minute** (pause-anytime).
3. **Live match** — the user's fixture plays out event-by-event on a dedicated screen with a running
   clock and PT-BR commentary; the server is authoritative and **withholds the future** (the
   "live broadcast" model), revealing only up to the next event.
4. **Timed in-match injuries & red cards** — injuries become minute-stamped events that **pause** the
   match for a decision (substitute, or play on **hobbled** = reduced contribution + higher
   re-injury risk); a red card removes the player (man-down, no replacement).
5. **Partial-minutes fitness** — post-match fitness decay scales by `minutesPlayed / 90`; unused subs
   don't decay; `last_match_date` is stamped only for players who took the pitch.
6. **Transport** — plain HTTP request/response, one POST per revealed event (no WebSocket/SSE).

**Out of scope (deferred):**

- Live scores of the *other* league matches ticking alongside yours (a "vidiprinter"). The other
  fixtures are instant-simulated at `finish` and shown on the post-match summary as today.
- Manager touchline instructions / in-match tactical shifts beyond substitutions (formation/mentality
  changes mid-match) — 3b/later.
- Real-time multiplayer or server-owned match clock that ticks while the user is away.
- AI clubs making in-match substitutions in *their* (instant-simulated) matches. AI rotation between
  matches (3a-1) is unchanged; AI in-match subs are not modelled.

## Design decisions (locked with the user)

- **Live model:** live tick + **pause/substitute anytime** (most immersive).
- **Architecture:** **② server-authoritative session** — the server holds match state and reveals
  only up to the next event; the future never sits in the browser.
- **Transport:** **plain HTTP request/response**, event-driven (POST per reveal). The client owns the
  clock (pause/resume/speed are client-side); round-trips happen per *event* (~15–40/match), not per
  minute or per second. Chosen over WebSocket/SSE because the interaction is pull-based, low-volume,
  and the authoritative state already lives in the DB (so reload/reconnect is a plain read).
- **Bench / subs:** **9** on the bench, up to **5** substitutions.
- **Injuries / reds:** **pause & prompt** — the user decides (substitute, play on hobbled, or man-down
  on a red).
- **Determinism:** *within-match* — identical `(seed, subSchedule)` ⇒ identical timeline. The engine
  re-baseline vs 3a-1 is acceptable: past results are already persisted, so only future matches use
  the new engine and nothing replays differently.

---

## Section 1 — Matchday flow & data model

### The flow (how ② plugs into the existing advance)

1. **Avançar on a matchday you play.** The existing advance **gate** still applies (no-lineup /
   ineligible starters / transfer-window blockers). Instead of instant-simulating the round, the
   frontend calls **`kickoff`**, which snapshots both starting XIs, your 9-man bench, and the
   per-fixture seed into a persisted **`LiveMatch`** row, and routes to the new `/match` screen.
2. **The live screen calls `advance` repeatedly.** The server plays forward, returns *the next event
   only*, and withholds the rest. The client animates a clock between events.
3. **`substitute`** records `(minute, out, in)` at the current server-owned minute; only the future
   changes; the server recomputes forward deterministically.
4. **At 90′ → `finish`.** The server records your result from the final timeline, then
   **instant-simulates the rest of the round via the existing path**, applies all outcomes
   (goals / cards / injuries / **partial-minutes fitness**), runs finances, writes news, deletes the
   `LiveMatch`, and returns the **same `AdvanceResponse` shape** rendered by `/news` today.
5. **Reload mid-match** ⇒ `GET live-match` rehydrates the screen from the persisted state.

### New / changed persistence

**`LiveMatch` entity (new, migration `V16__live_match.sql`)** — the server-authoritative session:

| Field | Type | Meaning |
|-------|------|---------|
| `id` | UUID | PK |
| `save_game_id` | UUID | FK; **unique** — at most one live match per save |
| `fixture_id` | UUID | the user's fixture being played |
| `competition_id`, `round` | | round context for `finish` |
| `seed` | BIGINT | the fixture seed |
| `committed_minute` | INT | revealed frontier (subs cannot precede it) |
| `home_goals`, `away_goals` | INT | running score at `committed_minute` |
| `managed_is_home` | BOOLEAN | which side the user manages |
| `home_xi`, `away_xi`, `bench` | JSONB | snapshot of player ids at kickoff (match is reproducible even if the saved lineup is later edited) |
| `sub_schedule` | JSONB | ordered `[{minute, outId, inId, side}]` |
| `subs_used_home`, `subs_used_away` | INT | cap enforcement (managed side is what the user controls; away stays 0 in 3a-2) |
| `status` | VARCHAR | `IN_PROGRESS` / `FULL_TIME` |

`ON DELETE CASCADE` from `save_games`. Deleted on `finish`.

**Bench in the lineup model:**

- Stop deleting non-starters: [`setLineup`](../../../backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java)
  changes from "delete-all + insert 11" to "replace with 11 `starting=true` + up to 9 `starting=false`
  bench rows." `LineupPlayer.starting` (already present) becomes meaningful.
- `SetLineupRequest` gains `bench: List<LineupSlot>` (≤ 9, distinct from starters, all belong to the
  club); `LineupResponse` gains `bench: List<LineupStarter>`. Bench is **optional** (empty ⇒ no subs).
  Bench players should be eligible (not suspended/injured); ineligible bench selections are rejected,
  mirroring starter validation.

**Fitness:** *no new columns.* Partial-minutes scales the existing decay; `minutesPlayed` is derived
from `(who started, sub_schedule)` at `finish`.

---

## Section 2 — Interactive match engine

### Sub-aware, event-driven simulation

`simulate` becomes sub-aware: conceptually
`simulate(homeInput, awayInput, seed, subSchedule) → SimulatedMatch`, where a sub swaps a player's
strength contribution from their entry/exit minute onward. The RNG stream is seeded exactly as today
and advanced deterministically per minute, so **a fixed schedule replays byte-identically**.
"Advance to next event" = run the deterministic sim with the current schedule and read off the first
event with `minute > committedMinute` (O(90), cheap; may be memoised per schedule and invalidated on
a new sub).

### Timed injuries (moved out of post-match)

The injury roll moves **into the sim**: the dedicated `injuryRng` is drawn per minute so an injury
lands **at a minute**. When an injury hits the **managed** club, `advance` returns it with
`status = PAUSED_INJURY` and the client blocks auto-advance until the user acts. The injured player is
marked **hobbled from that minute** server-side (reduced contribution + raised re-injury chance) —
this applies whether or not the user subs. Severity/layoff are drawn via `InjurySeverity.roll` as
today and the **layoff is applied at `finish`** (unchanged recovery mechanics). Injuries to the AI
opponent are shown but do **not** force a pause.

### Red cards

A red card (existing timed `RED_CARD` event) to the managed club sets `status = PAUSED_RED` so the
user can reshape; the sent-off player is removed for the remainder (man-down, no replacement, team
strength drops). Suspensions are applied at `finish` as today.

### Flavor events (liveliness)

Add non-scoring `CHANCE` (near miss / good save) plus `SUBSTITUTION` and `INJURY` to
[`EventType`](../../../backend/src/main/java/br/com/carvoeiromanager/match/EventType.java). Flavor
events never change the score — purely commentary — but they consume RNG draws (part of the
re-baseline).

### Partial-minutes fitness

At `finish`, each player's post-match base decay from
[`FitnessCalculator.decayedBaseFitness`](../../../backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java)
scales by `minutesPlayed / 90` (full 90 = today's behaviour; subbed off at 60′ = ⅔; a 30′ cameo = ⅓;
**unused sub = no decay + normal recovery**). `last_match_date` is stamped **only** for players who
took the pitch.

### Round refactor

[`simulateRound`](../../../backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java)
skips the user's fixture (already played live) and instant-sims the rest at `finish`. The
[`SeasonAdvanceService.processMatch`](../../../backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java)
bookkeeping (mark `SeasonEvent` processed, `currentRound + 1`, matchday finances, news,
`seasonFinished`) is refactored so `finish` can invoke it with the user's fixture pre-simulated.

---

## Section 3 — API (new `LiveMatchController`, `/api/v1/save-games/{saveGameId}` prefix)

| Verb + path | Purpose | Returns |
|---|---|---|
| `POST …/live-match/kickoff` | Enter the match. Snapshots XIs + bench + seed into `LiveMatch` (idempotent — returns the existing row if in progress, enabling resume). | `LiveMatchState` |
| `GET …/live-match` | Resume-on-reload: current in-progress match, or `204`. | `LiveMatchState` / 204 |
| `POST …/live-match/advance` | Reveal up to & incl. the next event after `committed_minute`; advances the frontier (mutates state ⇒ POST). Never returns the future. | `LiveMatchState` |
| `POST …/live-match/substitute` | Body `{outPlayerId, inPlayerId}`; minute is server-owned. Validates: out on pitch, in on bench & eligible & unused, subs remaining. | `LiveMatchState` |
| `POST …/live-match/finish` | Only at 90′. Records the result from the final timeline, instant-sims the rest of the round, applies outcomes/finances/news, deletes `LiveMatch`. | `AdvanceResponse` |

**`LiveMatchState`** carries: fixture / competition / round / date; home & away club names +
`managedIsHome`; `minute`; `homeGoals` / `awayGoals`; `status`
(`IN_PROGRESS` / `PAUSED_INJURY` / `PAUSED_RED` / `FULL_TIME`); the **full revealed-so-far** event
list (`LiveEvent[]`, idempotent, no future); the managed team's **on-pitch XI** and **bench** with
`fitness` / `hobbled` / `used` / `eligible` flags; and `subsRemaining`. Returning the whole revealed
timeline each call keeps reload/resume trivial.

**`LiveEvent`**: `minute`, `type`
(`GOAL` / `SET_PIECE_GOAL` / `YELLOW_CARD` / `RED_CARD` / `CHANCE` / `INJURY` / `SUBSTITUTION`),
`side` (home/away), `playerId`, `playerName`, `text` (commentary); for `SUBSTITUTION` also
`inPlayerName` / `outPlayerName`; for `INJURY` also `severity`.

**Flow rules:**

- The existing `advance` endpoint is **unchanged** and still serves TRANSFER events and match rounds
  where the managed club does not play.
- While a `LiveMatch` is `IN_PROGRESS`, a plain `advance` is **blocked** (the UI forces resume).
- **Play-on-hobbled needs no endpoint** — the injury event marks the player hobbled server-side;
  "Continuar" is just the next `advance`.

---

## Section 4 — Frontend

### Bench on the lineup screen

A bench zone to name up to 9 from the remaining squad (with `FitnessBar`s), saved via the extended
`SetLineupRequest`. Bench optional (empty ⇒ subs unavailable). Reuses the eligibility rules already
shown for starters.

### New live screen — `LiveMatchPage` (`/match?saveGameId=…`)

- **Header:** big clock (minute) + `HOME x–y AWAY` + an "AO VIVO" indicator; competition / round / date.
- **Commentary feed:** revealed events animate in with icons (⚽ 🟨 🟥 🚑 🔁) + PT-BR text as the clock
  reaches them.
- **Controls:** **Play/Pause** (toggles the client auto-advance timer), **speed** (1× / 2× / instant),
  **Substituir**. The client owns the clock: it ticks at the chosen speed and fires `advance` when it
  reaches the last revealed event, interpolating minutes between events. A **forced pause**
  (`PAUSED_INJURY` / `PAUSED_RED`) disables auto-advance and shows a decision banner
  (**Substituir** / **Continuar**).
- **Sub dialog:** on-pitch XI (fitness + hobbled markers) vs bench (available / used / ineligible),
  subs remaining; pick out + in → `substitute` → a 🔁 event appears in the feed.
- **Full-time:** `status = FULL_TIME` ⇒ "Fim de jogo" + final score; **Ver resumo** calls `finish`
  and routes to `/news` with the returned `AdvanceResponse` — the post-match experience is identical
  to today.
- **Resume:** on load / route entry, `GET live-match`; if present, hydrate from the revealed events +
  state and route to `/match`.

### Advance wiring in [`Layout.tsx`](../../../frontend/src/components/Layout.tsx)

The Avançar mutation **forks** on `managedClubPlaysNext` (already in `AdvancePreviewResponse`):
kickoff + navigate to `/match`, else the current instant `advanceRound`. The advance gate still guards
entry, and an in-progress `LiveMatch` forces resume rather than a fresh advance.

---

## Section 5 — Commentary, determinism, testing, edge cases

### Commentary

Deterministic templated PT-BR lines per event type; the variant is chosen by an index derived from
the sim RNG so it is **replay-stable**. Start minimal (~3–5 lines each for goal / chance-save / card /
injury / substitution / kickoff-HT-FT). This is the deliberately **trimmable tail** of the slice.

### Determinism

Within-match: identical `(seed, subSchedule)` ⇒ identical timeline (so resume + re-sim-on-sub are
exact, and `finish` reproduces the played match). The engine re-baseline vs 3a-1 is intentional and
safe — historical fixtures + `MatchEvent` rows are already persisted; only future matches use the new
engine.

### Backend tests (JUnit + Testcontainers, Given/When/Then, ≥80%)

- `simulate` is deterministic; a sub at minute M leaves minutes ≤ M identical and changes > M.
- `advance` reveals events in order, never returns the future, and `committed_minute` is monotonic;
  a substitute at a minute < `committed_minute` is rejected.
- `substitute` validation: cap of 5, out must be on the pitch, in must be on the bench + eligible +
  unused; `subsRemaining` decrements.
- Timed injuries land at a minute, set `PAUSED_INJURY` for the managed club, hobble reduces
  contribution, and the layoff is applied at `finish`.
- Red card removes the player for the remainder and sets `PAUSED_RED`.
- Partial-minutes fitness: full-90 == prior decay; subbed at 60′ ⇒ ⅔ decay; unused sub ⇒ no decay +
  normal recovery; `last_match_date` set only for players who played.
- `finish`: user fixture result recorded from the timeline; the rest of the round instant-sims;
  finances / news / `SeasonEvent` bookkeeping match the old `processMatch`; `LiveMatch` deleted;
  `seasonFinished` computed.
- Resume: `GET live-match` rebuilds state; `kickoff` is idempotent.
- Bench persistence: `setLineup` keeps bench rows; `getLineup` returns them.

### Frontend tests (Vitest / RTL)

- Feed renders revealed events; the clock fires `advance` per event.
- Pause stops auto-advance; an injury forces a pause until the user decides.
- Sub flow decrements subs and emits a 🔁 event.
- Full-time → `finish` → routes to `/news`.
- Resume from `GET live-match`.
- Bench selection on the lineup screen.

### Edge cases

- **Out of subs** ⇒ Substituir disabled; an injury ⇒ hobbled for the rest (no man-down by choice).
- **Red card** ⇒ man-down, no replacement.
- **Reload at FT before finish** ⇒ resume shows FT; `finish` still available.
- **Navigate away mid-match** ⇒ `LiveMatch` persists; next Avançar / login resumes it and blocks a
  fresh advance.
- **Empty bench** ⇒ no subs available; match still fully playable.

## Critical files

- `backend/.../match/LiveMatch.java`, `LiveMatchRepository.java`, `LiveMatchService.java`,
  `LiveMatchController.java` (new); `LiveMatchState` / `LiveEvent` / `SubstituteRequest` DTOs (new)
- `backend/.../db/migration/V16__live_match.sql` (new)
- `backend/.../match/MatchSimulationService.java` (sub-aware, timed injuries, flavor events)
- `backend/.../match/EventType.java` (`CHANCE`, `INJURY`, `SUBSTITUTION`)
- `backend/.../match/FitnessCalculator.java` (partial-minutes decay)
- `backend/.../match/MatchOutcomeApplier.java` (injuries move into the sim; apply at finish)
- `backend/.../match/FixtureSimulator.java` (skip user fixture in `simulateRound`; finish path)
- `backend/.../match/LineupService.java`, `Lineup*`/`SetLineupRequest`/`LineupResponse` (bench)
- `backend/.../season/SeasonAdvanceService.java` (refactor `processMatch` for `finish`)
- `frontend/src/features/match/LiveMatchPage.tsx` (+ test), sub dialog, commentary feed (new)
- `frontend/src/features/lineup/…` bench selection (+ test)
- `frontend/src/components/Layout.tsx` (advance fork + resume guard)
- `frontend/src/api/liveMatch.ts`, `frontend/src/api/types.ts` (client + types)

## Verification

- `./gradlew test` green (unit + Testcontainers).
- `docker-compose up`, create a Criciúma career, reach a matchday: Avançar enters the live screen;
  the clock ticks with commentary; pausing works; a substitution appears in the feed and decrements
  the count; a timed injury pauses for a decision; at full-time **Ver resumo** shows the same news as
  before; fitness drops reflect minutes played; a mid-match reload resumes.
- `npm run test` green; `npm run dev` and verify the live screen, bench selection, and resume.
