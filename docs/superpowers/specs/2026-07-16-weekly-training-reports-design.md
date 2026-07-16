# Weekly Training + Staff Training Reports — Design Spec

**Date:** 2026-07-16
**Status:** Approved (plan-mode review, 2026-07-16)

## Problem

Training (shipped 2026-07-14) applies attribute changes exactly once per season, at rollover
(`SeasonRolloverService` → `TrainingService.applyTraining`). The manager sets a focus and
intensity per player and sees nothing until the season ends. Now that the Staff System exists
(2026-07-15), the game should feel more alive: training should progress **weekly during the
season**, and the club's **Preparador Físico** should send the manager periodic reports about
how players are developing — good progress, stagnation, injury warnings.

Decisions made with the user:

1. **Move training to true weekly application** — attributes actually change incrementally
   mid-season (not qualitative hints, not a hidden meter consumed at rollover).
2. **Delivery: the news feed** — reports appear as items in the existing persisted news feed,
   alongside match/transfer events, when advancing.
3. **Staff-gated reporting** — reports require a hired PREPARADOR_FISICO; quality tier
   (REGULAR/BOM/EXCELENTE) controls report detail. Training itself is NOT staff-gated.
4. **Notable cases only** — a report mentions only players worth mentioning; quiet weeks
   produce no report at all.

## Part 1 — Weekly training application

### Hook: the finance-month pattern

`FinanceService.processMonth(saveGameId, eventDate)` is the established calendar-recurring
precedent: called at the top of `SeasonAdvanceService.advance()` (line ~126) and in
`completeUserMatch()` (line ~213), self-guarded by `SaveGame.lastFinanceMonth` (a YYYYMM int,
reset to 0 by `advanceToNextSeason()`). Weekly training mirrors it exactly:

- **`SaveGame` gains `lastTrainingWeek`** (`last_training_week` int, nullable=false, default 0),
  computed as ISO `WEEK_BASED_YEAR * 100 + WEEK_OF_WEEK_BASED_YEAR` (via
  `java.time.temporal.IsoFields`). `advanceToNextSeason()` resets it to 0, mirroring
  `lastFinanceMonth` (YYYYWW is monotonic anyway; the reset is symmetry/defense in depth).
- **`TrainingService.processWeek(UUID saveGameId, LocalDate eventDate)`** (`@Transactional`):
  compute `week` from `eventDate`; return early if `week <= save.getLastTrainingWeek()`;
  otherwise apply one weekly tick to every player in the save with a club
  (`playerRepository.findBySaveGameId`, skip `clubId == null` free agents), generate the
  managed club's report (Part 2), set `lastTrainingWeek = week`.
- **Call sites:** both `SeasonAdvanceService.advance()` and `completeUserMatch()`, immediately
  after the existing `financeService.processMonth(saveGameId, eventDate)` lines. Like finance,
  crossing multiple weeks in one advance applies **one** tick (no backfill) — matches are
  roughly weekly in the calendar, so this is the intended cadence.

### Weekly tick math: preserve the current per-season balance

Today's per-season bonus applied to the focus anchor attribute is
`intensityBonus (LEVE 1 / NORMAL 2 / INTENSA 3) + staffEffectsService.trainingBonusFor(clubId,
managed) (0/1/2, AI baseline BOM → 1)`. Weekly application spreads the same expected total
across the season using fractional accumulation (attributes are ints):

- **`Player` gains `trainingProgress`** (`training_progress` int, nullable=false, default 0),
  measured in **fortieths** of an attribute point (one unit = one week of bonus-1 training).
- **Per weekly tick, for a player with a non-null focus:**
  `progress += (intensityBonus + staffBonus)`. While `progress >= WEEKS_PER_SEASON` (constant
  `WEEKS_PER_SEASON = 40` in `TrainingService`): +1 to the focus anchor attribute (clamped
  1..99 — reuse the existing `clamp`), recompute overall via `overallCalculator.compute(...)`,
  write back with `player.evolveAttributes(...)`, `progress -= 40`. Players with
  `focus == null` are untouched (progress does not accumulate).
- This is exactly balance-preserving with pure integer math: over 40 weeks a player
  accumulates `bonus * 40` units = exactly `bonus` attribute points per season, matching
  today's one-shot values (e.g. NORMAL + no staff = 2/week → a +1 tick every 20 weeks, 2 per
  season).
- **Weekly injury roll:** today's per-season risk divided by `WEEKS_PER_SEASON`:
  LEVE 0.0, NORMAL 0.03/40 = 0.00075, INTENSA 0.08/40 = 0.002 per week. On a hit:
  `player.addInjury(1 + rng.nextInt(2))`, exactly as today. Expected injuries/season unchanged.
- **AI clubs train weekly too (parity):** focus `heuristicFocusFor(player.getPosition())`,
  intensity NORMAL, `managed=false` (staff baseline gives +1). Same tick path.
- **Determinism:** the tick uses
  `new Random(saveGameId.getMostSignificantBits() ^ week)` (week = the YYYYWW int), one RNG
  per processWeek call, consumed in stable player-iteration order.

### Rollover changes

- Remove **both** `applyTraining` call sites from `SeasonRolloverService.rollover` (the
  managed branch and the AI-heuristic branch, lines ~253-260). Aging
  (`PlayerAgingService.applyAgeCurve`) stays untouched.
- `Player.resetSeasonState()` additionally sets `trainingProgress = 0` (partial progress does
  not carry across seasons).
- The old one-shot `applyTraining(player, focus, intensity, rng, clubId, managed)` method is
  **removed** and replaced by the weekly model; `TrainingServiceTest` is rewritten for the new
  semantics. `heuristicFocusFor` stays (now consumed inside `processWeek`).

### Behavior notes

- Switching focus mid-season keeps accumulated `trainingProgress` (it is a generic meter, not
  per-attribute). This is intentional and simple; the next tick applies to the new focus.
- A newly created save has `lastTrainingWeek = 0`, so the first advance triggers the first
  tick — same as finance's first month.

## Part 2 — Weekly staff report

Generated inside the same `processWeek`, after the ticks, **for the managed club only**, and
**only if a PREPARADOR_FISICO row exists** for the managed club (any quality —
`staffRepository.findByClubIdAndRole`). No staff → training still ran, but no report.

### Entry types (notable cases only), collected during the managed club's ticks

| Case | Condition | Example message (pt-BR) |
|---|---|---|
| Evolution | focus attribute ticked +1 this week | REGULAR: "Fulano evoluiu nos treinos." / BOM+: "Fulano evoluiu em Passe." / EXCELENTE: "Fulano evoluiu em Passe (78 → 79)." |
| Ceiling | focus attribute already at 99 (tick wasted) | "Fulano não tem mais o que evoluir em Velocidade — considere trocar o foco." |
| Training injury | injury roll hit this week | "Fulano se lesionou durante os treinos (fora por N partida(s))." |
| Risky regime | intensity INTENSA and age ≥ 30 | "Treino intenso para Fulano (32 anos) traz alto risco de lesão." |
| Near-evolution (EXCELENTE only) | progress ≥ 20 (half a point) after this tick, no tick this week | "Fulano está perto de evoluir Finalização." |

### Quality controls detail and volume

| Quality | Max entries | Detail level |
|---|---|---|
| REGULAR | 3 | vague — no attribute names, no numbers |
| BOM | 6 | names the attribute |
| EXCELENTE | 10 | attribute + values / near-evolution lines |

Entry priority when truncating: evolutions first, then injuries, then ceiling, then risky
regime, then near-evolution. **Zero entries → no news item persisted that week** (most weeks
are quiet by design; this prevents feed spam).

### Delivery: the persisted news pipeline

- New news type string **`TRAINING_REPORT`** (15 chars — fits `news_item.type` varchar(16)).
- `AdvanceResponse` gains a new final field `TrainingReportResponse trainingReport` (null for
  MATCH/TRANSFER/FINISHED and for all pre-existing persisted payloads — same
  nullable-on-deserialize precedent as the `transfers` field; callers null-guard).
- New records in the `season` package:
  `TrainingReportResponse(String staffName, String quality, List<TrainingReportEntry> entries)`
  and `TrainingReportEntry(String playerName, String message)`.
- `TrainingService.processWeek` returns the report (or null); `SeasonAdvanceService` persists
  it as its own news item via the existing `persistNewsItem(saveGameId, eventDate, response)`
  with an `AdvanceResponse` of `type = "TRAINING_REPORT"`, `date` set, all other fields
  null/zero/empty, and `trainingReport` populated. It is a separate feed item from the MATCH/
  TRANSFER item of the same advance. (To avoid a `season → player → season` package cycle:
  `TrainingService.processWeek` returns a small player-package result object — e.g. staff name,
  quality, entry list of (playerName, message) — and `SeasonAdvanceService` maps it to the
  `season`-package DTOs. The plan pins the exact shape.)

## Part 3 — Frontend

- `frontend/src/api/types.ts`: `AdvanceResponse` gains `trainingReport?: TrainingReport | null`;
  new `TrainingReport { staffName, quality, entries }` and `TrainingReportEntry { playerName,
  message }` types.
- `frontend/src/features/match/AdvanceEventCard.tsx`: new render branch for
  `type === 'TRAINING_REPORT'`: header "Relatório de Treino" with the pt-BR date
  (existing `formatDatePtBR`) and a caption/chip with the Preparador Físico's name + quality
  label; body is the existing `TimelineNode` pattern, one 🏋️ node listing the entry messages.
- The News page (`NewsPage.tsx`) renders the new items automatically — no changes there.
- No changes to SquadPage's Treino tab or StaffPage (YAGNI).

## Testing

- **Unit (`TrainingServiceTest`, rewritten):** same-week guard no-ops; new week ticks and
  advances `lastTrainingWeek`; progress accumulation and carry across 100; clamp at 99 (tick
  consumed, ceiling entry emitted); staff bonus changes weekly rate; AI parity (heuristic
  focus + BOM baseline, no report for AI clubs); null-focus untouched; free agents skipped;
  injury roll (forced-seed); report gating (no PREPARADOR_FISICO → null report); entry
  generation per quality tier including caps and truncation priority; zero entries → null.
- **IT (Testcontainers):** advancing across a week boundary persists a `TRAINING_REPORT` news
  item retrievable via `GET /v1/save-games/{id}/news` when a Preparador Físico is hired;
  rollover no longer changes attributes via training (aging-only deltas); pre-existing news
  payloads without `trainingReport` still deserialize (null).
- **Frontend:** `AdvanceEventCard` test for the `TRAINING_REPORT` branch (renders header,
  staff attribution, entry messages); full `vitest` + `tsc --noEmit`.
- Full backend suite green before the final whole-branch review.

## Out of scope

- Backfilling reports for weeks skipped by large date jumps (mirrors finance's behavior).
- Progress display in the SquadPage Treino tab.
- Reports about aging/decline (aging still happens only at rollover).
- Per-attribute progress meters (single generic meter per player).
