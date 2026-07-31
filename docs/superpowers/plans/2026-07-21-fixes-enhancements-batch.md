# Fixes & Enhancements Batch — 20 items, 32 tasks

Batch of 20 fixes/enhancements. Phases: A quick wins (Tasks 1-9), B medium (10-17), C live-match depth (18-21), D gameplay depth (22-32, strictly sequential). Approved plan mirrored from ~/.claude/plans/sharded-orbiting-perlis.md.

## Global Constraints

- **Determinism doctrine** (Tasks 18, 19, 22, 25, 27, 31): never insert/remove RNG draws in existing streams; new randomness gets its own `new Random(seed ^ CONSTANT)` (injuryRng precedent in FixtureSimulator). Every engine task ships a neutral-settings byte-identical fixed-seed regression: BALANCED / EQUILIBRADO / MISTO / no-instruction / matching-positions must reproduce pre-change results exactly.
- **Record churn**: `TacticalProfile` grows in Tasks 22, 25, 27, 31 and `TeamMatchInput` in 18, 22 — each growth confined to its own task/commit; D-phase tasks never run concurrently.
- **Flyway**: latest committed migration is V32. Allocation: V33 stadium (Task 5), V34 parallel_scores (19), V35 game_style (22), V36 attack_zone (25), V37 lineup_player_instruction (27), V38 detailed_position (29). If execution order shifts, take the next free number.
- **In-flight saves**: all migrations additive with defaults/NULL. C-phase tolerates `parallel_scores NULL`; D-phase tactics schedules tolerate entries missing gameStyle/attackZone (default on parse).
- All UI text PT-BR. MUI `sx` styling, TanStack Query patterns (optimistic template: TransferMarketPage withdrawMut onMutate/onError/onSettled).
- Backend tests: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (focused `--tests 'br.com.carvoeiromanager...'` while iterating; FULL suite before committing; 600000ms timeout). Known flakes: TransferServiceSellingIT, FinanceRolloverIT — re-run isolated before judging.
- Frontend: `cd frontend && npx vitest run [pattern]` + `npx tsc -b` for type-check (`tsc --noEmit` is a no-op).
- **Commits: `git add` ONLY the files you touched, by name. NEVER `git add -A`/`git add .` — a concurrent session commits unrelated work to this same checkout on main.** Commit locally; NEVER push.
- Attribute enum names (backend/src/main/java/br/com/carvoeiromanager/player/Attribute.java): VELOCIDADE, ACELERACAO, FINALIZACAO, PASSE, DESARME, REFLEXOS, FOLEGO, CABECEIO, CRUZAMENTO, MARCACAO, DRIBLE, VISAO, FORCA, CHUTE_LONGE, RESISTENCIA, COMPOSTURA, POSICIONAMENTO, CONCENTRACAO (verify exact names in file before use).

---

### Task 1: Sort formation list ascending

**Goal:** formation dropdowns list codes ascending.
**Files:** `frontend/src/features/pitch/pitchSlots.ts`; new `frontend/src/features/pitch/pitchSlots.test.ts`.
**Approach:** `export const FORMATION_CODES = Object.keys(LAYOUTS).sort((a, b) => a.localeCompare(b, 'pt-BR', { numeric: true }))` (numeric-aware: 4-1-4-1 < 4-4-2). Do NOT reorder the LAYOUTS object literal. Consumers (SquadPage `FORMATIONS`, MatchActionsDialog) already import FORMATION_CODES — no changes there.
**Tests:** Vitest — FORMATION_CODES is sorted per that comparator; set-equal to `Object.keys(LAYOUTS)`; sync guard: import `FORMATION_SHAPE` from SquadPage (export it if not exported — export-only change) and assert its key set equals LAYOUTS keys.
**Deps:** none.

### Task 2: ConfirmDialog + "Ir para o jogo"

**Goal:** Avançar relabels to "Ir para o jogo" when the next advance is the user's match, with confirmation before kickoff.
**Files:** new `frontend/src/components/ConfirmDialog.tsx`; `frontend/src/components/Layout.tsx` (button ~280-290, handleAdvance ~124-128); tests `ConfirmDialog.test.tsx` + Layout test.
**Approach:** `ConfirmDialog({ open, title, message, confirmLabel, cancelLabel = 'Cancelar', onConfirm, onClose })` — thin MUI Dialog/DialogTitle/DialogContent/DialogActions wrapper. Layout: `preview?.managedClubPlaysNext && !liveMatchExists` → label "Ir para o jogo"; click opens ConfirmDialog (title "Ir para o jogo", message "Começar a partida contra o próximo adversário?", confirmLabel "Começar partida"); onConfirm runs the existing kickoff branch (kickoffMutation + navigate('/match')). When a live match exists the button stays "Retomar partida" (no confirm). Do NOT migrate the 4 `window.confirm` call sites.
**Tests:** RTL — ConfirmDialog fires onConfirm/onClose; Layout shows the new label when preview flag set and kickoff only fires after confirm (not on cancel).
**Deps:** none.

### Task 3: Always-visible in-game date

**Goal:** current in-game date always visible; match date shown in live header.
**Files:** new `frontend/src/utils/date.ts`; `frontend/src/features/match/AdvanceEventCard.tsx` (remove local formatDatePtBR ~23-29, import from utils; update any other importers of it, e.g. NewsPage); `frontend/src/components/Layout.tsx`; `frontend/src/features/match/LiveMatchPage.tsx` (header ~169-179).
**Approach:** move `formatDatePtBR` verbatim to utils/date.ts. Layout: render `formatDatePtBR(saveGame.currentDate)` as a caption Typography near the Avançar button (Layout already has save-game data; if it only has the preview query, add the existing ['saveGame'] query usage — check what Layout already queries first). LiveMatchPage header: add a line with `formatDatePtBR(state.matchDate)` (`LiveMatchState.matchDate` exists in api/types.ts:629, currently never rendered; null-safe).
**Tests:** unit for the moved formatter; RTL — Layout shows the date; LiveMatchPage header shows the match date.
**Deps:** none.

### Task 4: Optimistic contract renewal

**Goal:** clicking Renovar updates the CONTRATO tab instantly; rolls back only on failure.
**Files:** `frontend/src/features/squad/SquadPage.tsx` (renewMut ~273-277, renewButton ~547-558, detailsPlayer state ~177); test file for SquadPage.
**Approach:** (a) Replace `detailsPlayer: PlayerResponse | null` state with `detailsPlayerId: string | null`; derive `const detailsPlayer = squadQuery.data?.players.find(p => p.id === detailsPlayerId) ?? null` so the open modal tracks the query cache (fixes the frozen-snapshot problem). SquadPage-scoped ONLY — do not touch other PlayerDetails callers. (b) renewMut: `onMutate` — cancelQueries(['squad', saveGameId, clubId]), snapshot previous, setQueryData patching that player's `contractYears` to 3 (the modal renders `${player.contractYears} ano(s)`; the request already sends contractYears: 3); `onError` — restore snapshot; `onSettled` — invalidate ['squad']. Copy the withdrawMut pattern (TransferMarketPage.tsx:378-394).
**Tests:** cache patched synchronously on mutate; rollback on error; RTL — open modal, renew, contract years update without reopening.
**Deps:** none.

### Task 5: Stadium name backend (V33)

**Goal:** clubs have real stadium names, existing saves backfilled.
**Files:** new `backend/src/main/resources/db/migration/V33__stadium_name.sql`; `backend/src/main/java/br/com/carvoeiromanager/club/Club.java`; `backend/src/main/resources/seed/clubs.json`; `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java` (seed parse ~134/180).
**Approach:** migration: `ALTER TABLE clubs ADD COLUMN stadium_name VARCHAR(80);` then `UPDATE clubs SET stadium_name = CASE name WHEN 'Criciúma' THEN 'Estádio Heriberto Hülse' ... END` covering EVERY club name present in seed/clubs.json with its real stadium (research real Série A/B stadiums; CASE keys must match seed `name` values byte-for-byte including accents; ELSE `'Estádio ' || name`). Column stays nullable; `Club` gains `stadiumName` field + getter with no fallback logic (fallback lives in response mapping: `stadiumName != null ? stadiumName : "Estádio " + name`). clubs.json: add `"stadium"` per club; SaveGameService copies it when creating clubs for a new save.
**Tests:** Testcontainers IT — post-migration existing rows non-null; new save: Criciúma club has "Estádio Heriberto Hülse"; seed-coverage assertion (every seeded club name got a non-fallback stadium).
**Deps:** none.

### Task 6: Stadium + date surfaces

**Goal:** stadium visible in live-match header and round results.
**Files:** `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`; `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (toState; home club resolved ~472-479); `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureResultResponse.java`; `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (result-response building); `frontend/src/api/types.ts`; `frontend/src/features/match/LiveMatchPage.tsx`; `frontend/src/features/match/AdvanceEventCard.tsx`.
**Approach:** `LiveMatchState` + `stadiumName` (home club's stadium with Task-5 fallback). Header line becomes "{stadium} · {date}". `FixtureResultResponse` + nullable `stadium` (home side's); AdvanceEventCard MatchTimeline rows get a small caption with it. Update all LiveMatchState/FixtureResultResponse construction sites + tests (record growth).
**Tests:** toState unit (stadium + fallback); RTL header + result caption.
**Deps:** Tasks 5, 3.

### Task 7: Reusable WatchStar

**Goal:** single component owns watch-star wiring; MERCADO refactored to it with zero behavior change.
**Files:** new `frontend/src/features/players/WatchStar.tsx`; `frontend/src/features/transfer/TransferMarketPage.tsx` (star wiring ~793-808, watch mutations ~311-326); tests.
**Approach:** `WatchStar({ saveGameId, playerId })` — internal `useQuery(['watchlist', saveGameId], getWatchlist)`, add/remove mutations (api/watchlist.ts) with optimistic toggle (withdrawMut pattern), Star/StarBorder IconButton, Tooltips "Observar jogador"/"Remover da observação". Component renders null while watchlist loads. TransferMarketPage swaps its inline wiring for `<WatchStar/>` passed as `watchAction` — identical UX. Keep the shared ['watchlist', saveGameId] query key so all surfaces share one cache entry.
**Tests:** RTL — star toggles call add/remove with optimistic flip + rollback; TransferMarketPage existing tests still pass.
**Deps:** none.

### Task 8: Watch star + clickable names everywhere

**Goal:** every non-own-player surface opens PlayerDetails with a watch star.
**Files:** `frontend/src/features/club/ClubViewPage.tsx`; `frontend/src/features/contract/FreeAgentsPage.tsx`; `frontend/src/features/transfer/PreContractsPanel.tsx`; `frontend/src/features/transfer/WatchlistPanel.tsx`; tests per surface.
**Approach:** ClubViewPage + FreeAgentsPage already render PlayerDetails — pass `watchAction={<WatchStar saveGameId={...} playerId={player.id}/>}` (and `isOwnPlayer={false}`). PreContractsPanel (~192-199, plain TableCell text) and WatchlistPanel (~41-88, rows not clickable): make names buttons → `selectedPlayerId` state → `useQuery(['player', saveGameId, id], () => getPlayer(...), { enabled: !!id })` (api/career.ts getPlayer) → read-only PlayerDetails + watchAction. WatchlistPanel keeps its Remover button per row.
**Tests:** RTL per surface — click name opens modal (mocked getPlayer); star present and toggles.
**Deps:** Task 7.

### Task 9: Clickable player names in live match

**Goal:** lineups and ratings names in the live match open read-only PlayerDetails.
**Files:** `frontend/src/features/match/LiveMatchPage.tsx`; `frontend/src/features/match/PreMatchLineups.tsx`; `frontend/src/features/match/PlayerRatingsList.tsx`; tests.
**Approach:** LiveMatchPage owns `selectedPlayerId` + getPlayer query + PlayerDetails (read-only: no lineupControl/contractAction/listingAction; `watchAction` via WatchStar only for opponent-side players; `isOwnPlayer` true for managed side). Thread `onPlayerClick(playerId)` into PreMatchLineups and PlayerRatingsList (names become ButtonBase/Link-styled). Opening the modal must not break the tick loop (clock keeps running — acceptable; user can Pausar).
**Tests:** RTL — clicking a ratings-row name opens the modal with mocked player data.
**Deps:** Task 7.

### Task 10: Opponent-goal commentary

**Goal:** opponent goals get sad PT-BR lines naming the opponent; Criciúma goals unchanged.
**Files:** `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`; `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (toState ~424-433); tests LiveCommentaryTest, LiveMatchServiceTest.
**Approach:** new pools OPP_GOAL, OPP_GOAL_ASSISTED, OPP_PENALTY_GOAL, OPP_FREE_KICK_GOAL, OPP_CORNER_GOAL (mirror whichever goal-family pools exist — check the exact pool set in the file) with 4+ sad lines each; some lines take scorer `%s` only, some scorer + opponent club `%s` (e.g. "Gol deles... a zaga bate cabeça e o %s aproveita.", "%s marca para o %s e o estádio fica em silêncio."). New overload `of(..., boolean opponentGoal, String opponentClubName)`; existing signature delegates with `(false, null)` so non-goal call sites don't churn; same deterministic pick() indexing. Caller: toState computes `boolean opponentEvent = event.home() != lm.isManagedIsHome()` for goal-family events and passes the resolved opponent club name (~472-479). Comeback/hype signature lines remain managed-goals-only (verify gating).
**Tests:** opponent pools deterministic; %s-arity check across ALL pools (count format specifiers vs args — regression guard); LiveMatchServiceTest — opponent goal → sad line with club name; managed goal → byte-identical to current output for a fixed seed.
**Deps:** none.

### Task 11: Looser negotiation for listed players

**Goal:** transfer-listed players are cheaper and easier to buy.
**Files:** `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferNegotiator.java` + its test.
**Approach:** named constants; listed path: `ASK_FACTOR_LISTED` 0.85 → **0.75**; accept ratio **0.88** (unlisted stays 0.95); counter-min ratio **0.60** (unlisted stays 0.70); `COUNTER_MARKUP` 1.10 unchanged; clamp [0.65, 1.50] unchanged. `respondToBuy` selects ratios by `context.listed()`.
**Tests:** boundary table — listed at 0.88×effective → ACCEPT; at 0.60×effective → COUNTER at 1.10×effective; below → REJECT; unlisted boundaries unchanged. Check TransferServiceSellingIT/related ITs still pass (they may embed old constants).
**Deps:** none.

### Task 12: +6 trainable attributes

**Goal:** CABECEIO, CRUZAMENTO, MARCACAO, DRIBLE, VISAO, FORCA become training focuses (12 total).
**Files:** `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (TRAINABLE_ATTRIBUTES ~158-160); `backend/src/main/java/br/com/carvoeiromanager/player/TrainingService.java` (anchorValue ~225, applyAnchor ~237); possibly `player/Player.java`/`Attributes` (need a set-single-attribute path); `frontend/src/features/squad/SquadPage.tsx` (TRAINABLE_ATTRIBUTE_LABEL ~95-102); tests.
**Approach:** generalize `anchorValue(p, focus)` → `p.attribute(focus)` (works for all attributes — the 6 legacy getters are Attributes-map views anyway). `applyAnchor`: legacy 6 keep the existing `evolveAttributes` path (recomputes overall); new 6 set the attribute directly via an Attributes copy-with (add `Attributes.with(Attribute, int)` if absent) leaving overall untouched (overall is anchor-based by design — do not change OverallCalculator). Widen TRAINABLE_ATTRIBUTES. AI `heuristicFocusFor` unchanged. Frontend labels: Cabeceio, Cruzamento, Marcação, Drible, Visão de jogo, Força. Training-report copy must not promise overall gains for the new 6.
**Tests:** TrainingService — CABECEIO focus raises the attribute over enough weeks, overall unchanged; legacy focus still moves overall; setTraining accepts new values, rejects non-whitelisted; RTL — 12 options + Nenhum.
**Deps:** none.

### Task 13: Fixtures endpoint

**Goal:** `GET /api/v1/save-games/{id}/fixtures?competitionId=` returns the managed club's season fixtures.
**Files:** new `backend/src/main/java/br/com/carvoeiromanager/competition/FixtureEntryResponse.java`; `competition/FixtureRepository.java` (+ finder ordered by round); service + controller (colocate with CompetitionController/StandingsService); tests.
**Approach:** record `FixtureEntryResponse(UUID fixtureId, int round, LocalDate matchDate, boolean home, UUID opponentClubId, String opponentName, String opponentShortName, String stadiumName, boolean played, Integer goalsFor, Integer goalsAgainst)`. Service: fixtures where managed club is home or away, ordered by round; goalsFor/Against oriented to the managed club; stadium = home side's stadium (Task-5 fallback); unplayed → null goals.
**Tests:** IT — ordering, home/away orientation of goals, stadium present, unplayed nulls.
**Deps:** Task 5.

### Task 14: Calendário page + nav

**Goal:** new Calendário tab listing the whole season.
**Files:** new `frontend/src/features/competition/CalendarPage.tsx`; `frontend/src/api/career.ts` (+getFixtures); `frontend/src/api/types.ts`; `frontend/src/components/Layout.tsx` (saveLinks ~204-218); `frontend/src/router.tsx`; tests.
**Approach:** nav entry `{ to: '/calendar', label: 'Calendário' }` (place after Classificação); route in router.tsx. Table: Rodada, Data (formatDatePtBR), Adversário (shortName + " (C)"/" (F)"), Estádio, Resultado ("2–1" colored success/error/neutral by managed result, "—" unplayed). Competition selector mirroring TablePage's picker (saveGame.competitions, default via preview.defaultStandingsCompetitionId if that's the TablePage pattern — copy it). Highlight first unplayed round row. Mobile-friendly (follow TablePage's desktop/mobile split if simple; otherwise horizontal-scroll container).
**Tests:** RTL — rows, C/F markers, result coloring, highlight.
**Deps:** Tasks 13, 3.

### Task 15: Táticas de jogo side-by-side

**Goal:** instructions and field image side by side on desktop.
**Files:** `frontend/src/features/squad/SquadPage.tsx` (tab 1 panel ~696-712); test.
**Approach:** wrap in `<Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, gap: 2, alignItems: 'flex-start' }}>` — TacticsPanel in fixed-width (~380px, flexShrink 0) left column (drop the Collapsible on md+, keep panel always visible; simplest: remove Collapsible entirely and render TacticsPanel directly), Pitch in `flex: 1` right column.
**Tests:** RTL — tab 1 renders both TacticsPanel and Pitch.
**Deps:** none (must land before Tasks 24/26 so their selects join the final layout).

### Task 16: Advance-to endpoint

**Goal:** server-side skip to half-time or full-time.
**Files:** `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchController.java`; `match/LiveMatchService.java`; new request record; tests.
**Approach:** `POST /save-games/{id}/live-match/advance-to` body `{"target": "HALF_TIME"|"FULL_TIME"}` (enum `AdvanceTarget`). Service `advanceTo`: load once; loop the same single-step logic `advance` uses (`committedMinute = timeline.nextEventMinute(...)` — reuse/extract the shared step so scheduled subs/tactics apply identically); after each step compute `statusAt`; break on FULL_TIME always, on HALF_TIME only when targeted; PAUSED_INJURY/PAUSED_RED never break (auto-continue through). If target already passed → return current state unchanged (idempotent). Single save + single toState at end. Validation: 404/409 consistent with existing live-match endpoints when no match in progress.
**Tests:** from 0' → HT target gives status HALF_TIME minute 45; FT target gives FULL_TIME; determinism — minute-by-minute advance loop ≡ one advance-to (identical final score + events) for the same seed; advance-to proceeds across a PAUSED_INJURY minute; idempotency (HT target at minute 60 → unchanged state).
**Deps:** none.

### Task 17: Advance-to buttons

**Goal:** skip buttons in the live match UI.
**Files:** `frontend/src/api/liveMatch.ts` (+advanceTo); `frontend/src/features/match/LiveMatchPage.tsx` (clock ~126-134, controls ~196-216); tests.
**Approach:** buttons next to Pausar: "Avançar até o intervalo" (visible while minute < 45 && status IN_PROGRESS-family) and "Avançar até o fim" (visible until FULL_TIME). Click: stop the pending setTimeout (set `skipping` flag that gates the tick effect), disable controls, `await advanceTo(target)`, write returned state into the query cache (same setState the tick uses), clear flag. At HALF_TIME the existing "Iniciar 2º tempo" flow takes over; at FULL_TIME the existing finish flow.
**Tests:** RTL — click calls advanceTo once, no further advance POSTs fire, state updates.
**Deps:** Task 16.

### Task 18: Mentality × overall differential

**Goal:** attacking mentality pays off when your attack outguns their defense (and costs when it doesn't); symmetric for defensive.
**Files:** new `backend/src/main/java/br/com/carvoeiromanager/match/MatchupModifiers.java`; `match/TeamMatchInput.java` (+`Mentality mentality`); `match/TeamStrengthCalculator.java` (populate it); `match/MatchSimulationService.java`; `match/LiveMatchEngine.java` (per-minute chance call sites); tests.
**Approach:** `MatchupModifiers.chanceMults(TeamMatchInput home, TeamMatchInput away)` → `record ChanceMults(double home, double away)`. Per side S vs opponent O: S ATTACKING → S's mult ×= `1 + 0.15 * Math.tanh((S.attack − O.defense) / 50)`; S DEFENSIVE → O's mult ×= `1 − 0.15 * Math.tanh((S.defense − O.attack) / 50)`; BALANCED contributes nothing. Clamp each final mult to [0.80, 1.20]. Both engines multiply their per-side scoring chance by it before drawing — zero extra RNG draws, applied at the chance level (not team strength) so it composes with existing multipliers. TeamMatchInput record growth: update all construction sites + tests in this task.
**Tests:** formula table (attacking+stronger > 1, attacking+weaker < 1, balanced = 1, defensive reduces opponent, clamps); 200-fixed-seed sweep — ATTACKING strong-vs-weak mean goals strictly greater than BALANCED baseline; BALANCED-vs-BALANCED byte-identical regression (mults exactly 1.0).
**Deps:** none; must land before Task 22.

### Task 19: Parallel round pre-simulation (V34)

**Goal:** other fixtures of the round are pre-simulated at kickoff and replayed (not re-simulated) at finish.
**Files:** new `V34__live_match_parallel_scores.sql` (`ALTER TABLE live_matches ADD COLUMN parallel_scores TEXT NULL`); `match/LiveMatch.java`; `match/FixtureSimulator.java`; `match/LiveMatchService.java` (kickoff + finish); `season/SeasonAdvanceService.java` (completeUserMatch ~229-262); tests.
**Approach:** extract pure `simulatePure(Fixture, ...)` from simulateFixture (resolveXi → profileFor → compute → `matchSimulationService.simulate(inputs, seed)`; per-fixture seed formula unchanged; verify resolveXi is repo-read-only). Kickoff: for the round's other fixtures, simulatePure each and serialize `[{fixtureId, homeShort, awayShort, homeGoals, awayGoals, events:[{m,t,h}]}]` as JSON into `lm.parallelScores` (Jackson, subSchedule precedent). Finish: deserialize → `Map<UUID, SimulatedMatch>` → new `simulateRound(..., precomputed)` overload; inside simulateFixture use the precomputed SimulatedMatch instead of calling simulate — ALL downstream attribution/persistence identical (attribution RNG independently seeded per fixture, unchanged). `parallel_scores NULL` → empty map → exact legacy path.
**Tests:** kickoff persists JSON covering every other fixture of the round; finish persists Fixture results equal to stored scores; identity proof — precomputed path persists identical events/appearances as the recompute path for the same seed; NULL column → legacy behavior.
**Deps:** after Task 18 (same files).

### Task 20: parallelScores + competitionId in LiveMatchState

**Goal:** live state exposes other games' scores as of the current minute.
**Files:** `match/LiveMatchState.java`; `match/LiveMatchService.java` (toState); `frontend/src/api/types.ts` (types only, no UI); tests.
**Approach:** +`competitionId`, +`parallelScores: List<ParallelScore>` where `ParallelScore(fixtureId, homeShort, awayShort, homeGoals, awayGoals)` = stored events filtered to goal-family with `minute <= lm.getCommittedMinute()` (reuse/extract the existing goal-counting predicate — grep how SimulatedEvent goals are tallied; share it). Null column → empty list. Record growth: update construction sites + tests.
**Tests:** scores tick up as committedMinute grows; at FT equal stored finals; legacy null → empty.
**Deps:** Task 19.

### Task 21: Paralelas + Classificação tabs

**Goal:** two new live-match tabs.
**Files:** `frontend/src/features/match/LiveMatchPage.tsx` (tabs ~224-228); possibly extract a shared standings table from `frontend/src/features/competition/TablePage.tsx`; `frontend/src/api/liveMatch.ts`/types; tests.
**Approach:** tabs after Campo: **Paralelas** — rows "{homeShort} {hg} x {ag} {awayShort}" from state.parallelScores, brief highlight animation when a score changes (previous snapshot via useRef), empty state "Sem outros jogos nesta rodada". **Classificação** — getStandings(saveGameId, state.competitionId) with staleTime Infinity, caption "Classificação antes da rodada"; reuse TablePage's table markup (extract presentational component if trivially separable, else slim local copy).
**Tests:** RTL — parallel rows render from state; standings query fired with state.competitionId; empty state.
**Deps:** Task 20.

### Task 22: GameStyle core (V35)

**Goal:** estilos de jogo with squad-fit effectiveness, wired into both engines.
**Files:** new `backend/src/main/java/br/com/carvoeiromanager/player/GameStyle.java`; new `V35__game_style.sql`; `match/Lineup.java`; `club/Club.java`; `match/TacticalProfile.java`; `match/TacticsService.java` (profileFor); `match/TeamStrengthCalculator.java`; `match/TeamMatchInput.java`; `match/MatchupModifiers.java`; tests.
**Approach:** enum {EQUILIBRADO, JOGO_DE_PASSES, CONTRA_ATAQUE, JOGO_DIRETO, PRESSAO_ALTA, RETRANCA} with fields (baseAttack, baseDefense, decayMult, fitScale, weighted fit attributes, PT-BR label):
- EQUILIBRADO: 1.00/1.00/1.00, fit none (0)
- JOGO_DE_PASSES: attack 1.03+0.05·fit, defense 0.99 — PASSE .5, VISAO .3, COMPOSTURA .2
- CONTRA_ATAQUE: attack 0.99+0.05·fit, defense 1.02 — VELOCIDADE .4, ACELERACAO .3, FINALIZACAO .3
- JOGO_DIRETO: attack 1.02+0.05·fit, defense 1.00 — FORCA .35, CABECEIO .35, CHUTE_LONGE .30
- PRESSAO_ALTA: attack 1.02, defense 1.02+0.05·fit, decay ×1.15 — RESISTENCIA .4, MARCACAO .3, DESARME .3
- RETRANCA: attack 0.94, defense 1.05+0.05·fit — MARCACAO .35, POSICIONAMENTO .35, CONCENTRACAO .30
Fit = `clamp((Σ_{XI outfield} weightedAttr / count − 58.0) / 20.0, −1, 1)` on RAW attributes (before form/morale mults). Applied in TeamStrengthCalculator after existing mentality/marking mults; fitness decay × decayMult where pressing decay applies. Migration: `lineups ADD game_style VARCHAR(16) NOT NULL DEFAULT 'EQUILIBRADO'`; `clubs ADD default_game_style VARCHAR(16)` backfilled CASE default_mentality (ATTACKING→'JOGO_DE_PASSES', DEFENSIVE→'CONTRA_ATAQUE', ELSE 'EQUILIBRADO'). TacticalProfile +gameStyle; TeamMatchInput +gameStyle; TacticsService: lineup value for managed, club default else EQUILIBRADO for AI. MatchupModifiers: self CONTRA_ATAQUE && opponent mentality ATTACKING → self mult ×1.04 pre-clamp. ALL record construction sites updated here — the one big churn commit; nothing else concurrent.
**Tests:** enum math incl. negative-fit malus; fit formula (avg 58→0, 78→+1, 38→−1); EQUILIBRADO byte-identical fixed-seed regression; migration backfill IT; TacticsService default resolution.
**Deps:** Task 18.

### Task 23: GameStyle persistence API + seeds

**Goal:** style settable via squad tactics + live tactics; seeded AI styles.
**Files:** `match/LineupService.java` (setTactics path) + tactics DTOs; `match/LiveMatchService.java` + live tactics request; `backend/src/main/resources/seed/clubs.json`; `career/SaveGameService.java`; tests.
**Approach:** squad PUT tactics + live-match tactics request/response gain `gameStyle` (invalid → 400 "Estilo de jogo inválido"); live change recorded in tacticsSchedule like mentality (parse tolerates entries missing gameStyle → keep current). clubs.json optional `"style"` per club (assign real-ish: retranca/contra-ataque for small defensive clubs, jogo de passes for technical big sides; default from mentality mapping where unsure); SaveGameService parses with the Task-22 mentality-derived fallback.
**Tests:** set+get roundtrip; mid-match style change applies from that minute (schedule test); seed IT.
**Deps:** Task 22.

### Task 24: GameStyle frontend

**Goal:** "Estilo de jogo" select in both tactics surfaces.
**Files:** `frontend/src/api/types.ts`; `frontend/src/features/squad/TacticsPanel.tsx`; `frontend/src/features/match/MatchActionsDialog.tsx`; tests.
**Approach:** options Equilibrado / Jogo de passes / Contra-ataque / Jogo direto / Pressão alta / Retranca; mirror the Mentalidade select wiring (labels map like MENTALITY_LABEL, e.g. in tacticsLabels.ts).
**Tests:** RTL both surfaces — renders, change included in mutation payload.
**Deps:** Tasks 23, 15.

### Task 25: AttackZone backend (V36)

**Goal:** "onde forçar o jogo" affects who scores/assists and how.
**Files:** new `player/AttackZone.java` {LATERAIS, CENTRO, MISTO}; new `V36__attack_zone.sql`; `match/Lineup.java`; `club/Club.java`; `match/TacticalProfile.java`; `match/TacticsService.java`; `match/TeamStrengthCalculator.java` (synergy); `match/LiveMatchEngine.java` + `match/FixtureSimulator.java` (scorer/assister weights); tactics DTOs (squad + live); tests.
**Approach:** migration: `lineups ADD attack_zone VARCHAR(10) NOT NULL DEFAULT 'MISTO'`; `clubs ADD default_attack_zone VARCHAR(10)` (null→MISTO, no backfill). Weights only — identical RNG draw counts. Assister weight: LATERAIS → base + 2×CRUZAMENTO, ×1.5 if role WINGER/FULL_BACK; CENTRO → base + PASSE + VISAO, ×1.5 if role PLAYMAKER/BOX_TO_BOX; MISTO → EXACTLY the current formula. Scorer weight adds: LATERAIS +0.5×CABECEIO; CENTRO +0.5×CHUTE_LONGE; MISTO unchanged. Synergy in TeamStrengthCalculator: (JOGO_DIRETO+LATERAIS) or (JOGO_DE_PASSES+CENTRO) → attack ×1.02. TacticalProfile +attackZone (isolated churn commit); DTOs gain attackZone (schedule parse tolerant).
**Tests:** weight-function units per zone; MISTO byte-identical fixed-seed round regression; synergy unit; DTO roundtrip.
**Deps:** Tasks 22, 23.

### Task 26: AttackZone frontend

**Goal:** "Onde forçar o jogo" select in both tactics surfaces.
**Files:** TacticsPanel.tsx, MatchActionsDialog.tsx, api/types.ts, labels; tests.
**Approach:** options Pelos lados / Pelo centro / Misto; mirrors Task 24 wiring.
**Deps:** Tasks 25, 24.

### Task 27: PlayerInstruction backend (V37)

**Goal:** per-player instructions that shift engine weights.
**Files:** new `player/PlayerInstruction.java`; new `V37__lineup_player_instruction.sql` (`ALTER TABLE lineup_players ADD COLUMN instruction VARCHAR(20) NULL`); lineup-players entity/mapping; `match/LineupService.java` (validate beside role parsing); `match/TacticalProfile.java` (+`Map<UUID, PlayerInstruction> instructions`, empty for AI); `match/TeamStrengthCalculator.java`; `match/LiveMatchEngine.java` + `match/FixtureSimulator.java`; tests.
**Approach:** enum {INFILTRAR_NA_AREA, CHUTAR_DE_LONGE, CRUZAR_MAIS, SEGURAR_POSICAO, MARCACAO_DURA, DRIBLAR_MAIS} + PT-BR labels. Effects (weights/multipliers only, zero new draws): INFILTRAR_NA_AREA scorer weight ×1.5 · CHUTAR_DE_LONGE scorer ×1.25 and MID positionFactor 6→8 for that player · CRUZAR_MAIS assister ×1.5 · SEGURAR_POSICAO attackContribution ×0.85, defenseContribution ×1.10 · MARCACAO_DURA defenseContribution ×1.08, card/foul-committed attribution weight ×1.5 · DRIBLAR_MAIS fouled/penalty-won attribution weight ×1.5. Validation: instruction on GK → 400 "Goleiros não recebem instruções pessoais". AI clubs: empty map, no behavior change.
**Tests:** per-instruction weight/mult units; empty-map byte-identical fixed-seed regression; validation + persist/reload roundtrip.
**Deps:** Task 25 (sequential engine edits).

### Task 28: PlayerInstruction API + frontend

**Goal:** set instructions from the squad UI.
**Files:** lineup DTOs (the per-player role carrier gains `instruction`); SquadController path; `frontend/src/features/squad/PlayerDetails.tsx`; `frontend/src/features/squad/SquadPage.tsx` (save-lineup payload); api/types.ts; tests.
**Approach:** PlayerDetails gains select "Instrução pessoal" (6 options + "Nenhuma"), visible only when `lineupControl` present and player is not GK; persisted through the existing lineup/role update mutation extended with instruction.
**Tests:** RTL — select shows for own outfield player, hidden for GK; payload carries instruction; backend controller roundtrip.
**Deps:** Task 27.

### Task 29: DetailedPosition enum + migration + generation (V38)

**Goal:** every player has a detailed position; new players generated with one.
**Files:** new `player/DetailedPosition.java`; new `V38__detailed_position.sql`; `player/Player.java`; PlayerResponse mapping (`match/LineupService.toPlayerResponse`); `player/NewgenGenerator.java`; `season/SeasonRolloverService.java` (youth intake ~321, retirement newgen ~263); `career/SaveGameService.java` (seed load); tests.
**Approach:** enum {GOLEIRO, ZAGUEIRO, LATERAL_ESQUERDO, LATERAL_DIREITO, VOLANTE, MEIA_ARMADOR, PONTA, FALSO_9, ATACANTE} with `Position coarse()`, PT-BR label + short label (GOL, ZAG, LE, LD, VOL, MEI, PON, F9, ATA), and `static DetailedPosition defaultFor(Position, PlayerRole, Random)`: GK→GOLEIRO; STOPPER/BALL_PLAYING_DEF→ZAGUEIRO; FULL_BACK→LE/LD 50/50; HOLDING_MID→VOLANTE; PLAYMAKER→MEIA_ARMADOR; BOX_TO_BOX→VOL/MEI 50/50; WINGER→PONTA; POACHER/TARGET_MAN→ATACANTE, with ~10% FALSO_9 for ATT when PASSE≥60. Migration: nullable `players ADD detailed_position VARCHAR(20)` → SQL backfill mirroring defaultFor deterministically (hash-parity `(hashtext(id::text) % 2)` for LE/LD and VOL/MEI splits; FALSO_9 never backfilled) → `SET NOT NULL`. Generation call sites use defaultFor with their existing seeded Randoms; seed JSONs unchanged (derive at load in SaveGameService). PlayerResponse + `detailedPosition` + label.
**Tests:** migration IT — no nulls, laterais split both sides, coarse-consistency (`detailed.coarse() == position` for all rows); defaultFor table; newgen/rollover coarse-consistency.
**Deps:** Task 27 complete (no parallel churn).

### Task 30: Formation slots + slot-aware bestXi

**Goal:** formations know their detailed slots; auto-pick fills them.
**Files:** new `backend/src/main/java/br/com/carvoeiromanager/match/FormationSlots.java`; `match/LineupService.java` (bestXi ~320-367, bestBench); tests.
**Approach:** `FormationSlots.of(String code)` → ordered 11 `Slot(Set<DetailedPosition> accepted)` for all 16 Formation codes, mirroring frontend LAYOUTS shapes (e.g. 4-3-3: GOLEIRO, LD, ZAG, ZAG, LE, VOL, MEI, MEI, PON, {ATACANTE, FALSO_9}, PON). bestXi greedy: GK slot first, then slots by candidate scarcity; per slot pick tier 1 = exact detailed match (wins regardless of overall), tier 2 = same coarse band, tier 3 = any; within tier order by overall desc then id (deterministic). Keep the exactly-1-GK invariant and existing eligibility filters.
**Tests:** per-formation slot coarse-band counts equal the formation code digits (backend sync guard for all 16); tiering assertions (exact-match LATERAL beats higher-overall ZAGUEIRO for the LD slot); bench behavior regression.
**Deps:** Task 29.

### Task 31: Out-of-position penalty + AI auto-assign

**Goal:** playing out of detailed position costs effectiveness; AI unaffected via auto-assign.
**Files:** `match/TeamStrengthCalculator.java`; `match/TacticsService.java` (profileFor builds assignments); `match/TacticalProfile.java` (+`Map<UUID, DetailedPosition> slotAssignments`); tests.
**Approach:** per-player contribution multiplier: 1.00 when player.detailedPosition matches the assigned slot (slot accepts it); 0.92 wrong detailed but same coarse band; 0.80 cross-band. Applied to both attack and defense contributions. Player absent from assignments → 1.00 (legacy safety). Managed club: assignments = lineup starter order ↔ FormationSlots.of(lineup.formation). AI clubs: greedy best-fit auto-assign (same tiering as bestXi) → ≈all exact, strength-neutral, deterministic. TacticalProfile churn isolated here.
**Tests:** forced mismatch drops that player's contribution exactly 8%/20%; AI assignment all-1.0 for well-formed squad; fixed-seed round — results change only for squads with mismatches (construct both).
**Deps:** Task 30.

### Task 32: DetailedPosition frontend

**Goal:** detailed positions visible everywhere; pitch warns on out-of-slot.
**Files:** `frontend/src/api/types.ts`; new `frontend/src/features/players/positionLabels.ts`; `frontend/src/features/squad/PlayerDetails.tsx`; `frontend/src/features/squad/SquadPage.tsx`; transfer/free-agent/youth tables where position renders (TransferMarketPage, FreeAgentsPage/Panel, WatchlistPanel, ClubViewPage, BasePage); `frontend/src/features/pitch/pitchSlots.ts` (each LAYOUTS slot gains `detailed` tag matching backend FormationSlots); `frontend/src/features/pitch/PlayerToken.tsx`; tests.
**Approach:** DETAILED_POSITION_LABEL + short map (GOL/ZAG/LE/LD/VOL/MEI/PON/F9/ATA); render detailed label wherever position shows, fallback to coarse POSITION_LABEL for legacy payloads. PlayerToken shows the slot short label and warn-tints when `player.detailedPosition` ≠ slot tag (visual cue for the Task-31 penalty).
**Tests:** RTL labels in PlayerDetails/SquadPage; pitchSlots test — every slot's `detailed` tag consistent with its band and per-formation counts equal code digits (frontend sync guard).
**Deps:** Tasks 29, 30, 1.
