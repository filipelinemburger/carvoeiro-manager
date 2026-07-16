# Quick Wins Bundle 2 — Design Spec

**Date:** 2026-07-16
**Status:** Approved

## Problem

Five small, independent UX gaps identified in a 15-item improvement roadmap (Group 1 of
`~/.claude/plans/imperative-strolling-parasol.md`):

1. An expired auth token leaves the user stuck on a broken page instead of bouncing to login.
2. There's no one-click way to resume the career you last played.
3. The "Atuações" (ratings) tab makes you toggle between your team and the opponent instead of
   seeing both at once.
4. Players who've been substituted off still look identical to those still playing in the
   ratings list.
5. There's no view of both starting lineups before kickoff — you only see them once minutes
   start advancing.

## Part 1 — Auth hardening

**401 handling.** `frontend/src/api/client.ts`'s `handle()` function currently throws an
`ApiError(message, code, status)` for any non-OK response and nothing inspects `status`. Add a
branch: when `status === 401` **and** the request URL is not `/auth/login` or `/auth/register`,
clear both auth localStorage keys (`carvoeiro_manager_token`, `carvoeiro_manager_user`) and
hard-redirect via `window.location.href = '/login'` before throwing. The login/register
exemption matters — those endpoints legitimately return 401 for bad credentials, and that must
surface as an inline form error, not a redirect-and-wipe.

Hard `window.location` (not React Router `navigate`) is deliberate: this fires from a
non-component module with no router context, and a full reload is desirable anyway since it
resets all in-memory query state cleanly.

**Root route.** `frontend/src/router.tsx` has no `"/"` route; both logged-in and logged-out
users hitting `/` fall through the `*` catch-all straight to `/login`. Add a `RootRedirect`
component: `<Navigate to={token ? '/dashboard' : '/login'} replace />`, mounted at `path="/"`.
The existing `*` catch-all is simplified to reuse the same component (today it's a hardcoded
`/login`; making it token-aware is a strict improvement, not scope creep, since it's one line).

## Part 2 — Carregar última carreira

**Backend.** New nullable `last_played_at` (`Instant`) column on `save_games`, migration `V29`.
Touched (set to "now") at exactly three existing call sites — no new endpoints:
- `SaveGameService.createCareer` (`career/SaveGameService.java:102`) — a new career's first
  "play" is its creation.
- `SeasonAdvanceService.advance` (`season/SeasonAdvanceService.java:111`) — every instant
  advance.
- `LiveMatchService.kickoff` (`match/LiveMatchService.java:95`) — starting a live match counts
  as playing, even before any advance completes.

`LiveMatchService.advance` (in-match minute stepping, `match/LiveMatchService.java:244`) is
**not** touched — kickoff already marked the session as played; re-stamping every poll is
unnecessary churn for no user-visible benefit.

Since `Instant.now()` isn't available in migrations/entities the normal way at these particular
call sites already use `Instant.now()` elsewhere in the codebase (e.g. `NewsItem.createdAt`),
this is a plain `save.setLastPlayedAt(Instant.now())` — no new pattern.

**Listing order.** `SaveGameController.list` currently returns
`saveGameRepository.findByUserIdOrderByCreatedAtDesc(userId)` mapped straight to
`SaveGameSummaryResponse`. Change the ordering to prefer `lastPlayedAt`, falling back to
`createdAt` for careers never played beyond creation (which is every career, since creation
itself now stamps it — but the fallback keeps the comparator total and defends against a future
call site that constructs a `SaveGame` without going through `createCareer`). Simplest
implementation: keep the repository query as-is (`ORDER BY created_at DESC` is fine as the DB
fetch order) and sort the resulting Java list by
`Comparator.comparing((SaveGameSummaryResponse r) -> r.lastPlayedAt() != null ? r.lastPlayedAt() : r.createdAt()).reversed()`
in `SaveGameService`/`SaveGameController` before returning. `SaveGameSummaryResponse` gains a
`Instant lastPlayedAt` field (nullable in the DTO to mirror the entity, even though in practice
it's always set post-creation).

**Frontend.** `SaveGameSummary` type gains `lastPlayedAt: string | null`. On
`DashboardPage.tsx`'s existing "no active career" state (shown when localStorage has no
saveGameId for this user), add a **"Carregar última carreira"** button — visible only when
`listCareers()` returns at least one career. Clicking it takes the first item of the
already-sorted list (no new sort logic needed client-side) and does exactly what
`LoadCareerDialog`'s existing `resume()` does: write the id to `localStorage`, invalidate
`['saveGame']`, navigate to `/dashboard`. Extract that three-line resume logic into a small
shared helper (`frontend/src/features/career/resumeCareer.ts`) used by both
`LoadCareerDialog.tsx` and the new Dashboard button, rather than duplicating it — the only
DRY-worthy extraction in this bundle.

## Part 3 — Atuações two columns

`frontend/src/features/match/LiveMatchPage.tsx`'s Atuações tab body currently renders two MUI
`<Button>`s (state `ratingsSide`) that swap a single `<PlayerRatingsList ratings={...} />`
between `state.playerRatings` and `state.opponentRatings`. Replace with a `<Stack
direction={{ xs: 'column', sm: 'row' }} spacing={2}>` containing two labeled columns — "Meu
Time" then `<PlayerRatingsList ratings={state.playerRatings} .../>`, "Adversário" then
`<PlayerRatingsList ratings={state.opponentRatings} .../>` — each in a `<Box sx={{ flex: 1,
minWidth: 0 }}>` so they share width evenly on desktop and stack full-width on mobile. The
`ratingsSide` state and its two buttons are deleted entirely. `PlayerRatingsList` itself is
unchanged apart from Part 4's new prop.

## Part 4 — Subbed-off styling

`PlayerRatingsList` (`frontend/src/features/match/PlayerRatingsList.tsx`) gains a new optional
prop `onPitchIds?: Set<string>`. When provided, any row whose `playerId` is **not** in the set
renders with `opacity: 0.5` and a small trailing "↩" glyph after the name (title-attributed
"Substituído" for accessibility) — using data already present, no new backend field. This same
rule also dims red-carded players, which is correct: anyone with a rating who isn't currently on
the pitch (subbed off or sent off) reads as "no longer playing," and that's the right signal
either way.

`LiveMatchPage.tsx` passes `onPitchIds={new Set(state.onPitch.map(p => p.playerId))}` to the
"Meu Time" list and `new Set(state.opponentOnPitch.map(p => p.playerId))` to "Adversário". The
persisted news-feed usage (`AdvanceEventCard.tsx`'s `MatchTimeline`, showing a completed match's
final Atuações) passes no `onPitchIds` — after full time there's no live on-pitch/off-pitch
distinction worth showing, and that call site has no `onPitch` list to draw from anyway (it only
has `playerRatings` from the `AdvanceResponse`), so omitting the prop there is not a gap, it's
the correct absence.

## Part 5 — Pre-match team sheet

`LiveMatchPage.tsx`'s default "Lances" tab currently renders the live event timeline, which is
empty at minute 0 (nothing has happened yet) — today the user sees a blank/placeholder state
until the first advance. Add a new presentational component
`frontend/src/features/match/PreMatchLineups.tsx`: `{ home, away }` each `{ clubName,
formation, players: LivePlayer[] }`. It renders two side-by-side columns (same responsive
`Stack` pattern as Part 3), each a header (club name + formation label, reusing the existing
`formationLabel`/`tacticsLabels` helpers already used elsewhere) over a list of starting-XI rows
(position badge, player name, overall) — no ratings, no minutes, since nothing has been played.

In `LiveMatchPage.tsx`, when `state.minute === 0`, the Lances tab body renders
`<PreMatchLineups .../>` instead of the (empty) event timeline, built from `state.onPitch` +
`state.currentTactics` for the home side and `state.opponentOnPitch` + `state.opponentTactics`
for the away side (both already present in `LiveMatchState` since the Opponent Visibility
feature). Once `state.minute > 0`, the tab reverts to the normal event timeline automatically —
no new state, purely a render branch on the existing `state.minute`. Other tabs (Atuações,
Campo) are unaffected and remain reachable at minute 0 exactly as today.

## Testing

- **Backend:** unit test on the new list-ordering comparator (unplayed-vs-played mixed list
  sorts correctly, ties fall back to `createdAt`); IT coverage that `advance()` and `kickoff()`
  bump `lastPlayedAt` (Testcontainers, assert the timestamp changes and is `>=` a captured
  "before" instant).
- **Frontend:** `client.ts` 401 test (redirect fires for a generic endpoint, does NOT fire for
  `/auth/login`/`/auth/register`, both auth keys cleared); `RootRedirect` test (both branches);
  Dashboard "Carregar última carreira" button test (hidden with zero careers, resumes the
  first/most-recent on click); `PlayerRatingsList` dimmed-row test (`onPitchIds` provided vs.
  omitted); `LiveMatchPage` Atuações two-column render test (both lists visible simultaneously,
  no toggle buttons); `LiveMatchPage` minute-0 team-sheet test (renders `PreMatchLineups` at
  `minute: 0`, reverts to the timeline once `minute > 0`).
- Full suites: backend `./gradlew test` (heap already 3g), frontend `npx tsc --noEmit && npx
  vitest run`.

## Out of scope

- Any change to `LiveMatchService.advance` (in-match polling) touching `lastPlayedAt`.
- A dedicated "Escalações" nav tab or route — the team sheet is a render-state of the existing
  Lances tab only, at minute 0.
- Any backend change for subbed-off styling — it's derivable from data already in
  `LiveMatchState`.
- Sorting/filtering the career list beyond "most recently played first."
