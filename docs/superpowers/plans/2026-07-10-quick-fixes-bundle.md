# Quick Fixes Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship four small, independent quick fixes: short club names in match views, squad cap 30→40, a mobile squad row that never overflows, and a manager name/nationality captured at career creation and shown on the dashboard.

**Architecture:** Each item is a self-contained vertical slice (backend + frontend where applicable) with no dependency on the others — the four tasks can be reviewed and merged in any order, though they're numbered for a sensible commit sequence.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 + AssertJ (backend); React 19 / TypeScript / MUI / Vitest + React Testing Library (frontend).

**Spec:** `docs/superpowers/specs/2026-07-10-quick-fixes-bundle-design.md`

## Global Constraints

- Gradle: run from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew …`, foreground only, never two gradle invocations concurrently. Frontend: `cd frontend && npx tsc --noEmit && npx vitest run`.
- Commit locally on `main` after each task. NEVER `git push`.
- pt-BR UI copy throughout; no English strings in user-facing text.
- `FixtureResultResponse` is persisted as JSON inside `AdvanceResponse` (News feed) — its new fields must be treated as **possibly absent on old persisted payloads** (nullable on backend, optional + `?? clubName` fallback on frontend). `LiveMatchState` is never persisted (computed fresh per request) — its new fields are always populated, no fallback needed.
- Squad cap: `SquadRules.MAX_SQUAD_SIZE = 40` is the single source of truth. No test today fills a squad to trigger `SQUAD_FULL` (confirmed via repo-wide grep for `SQUAD_FULL` in `backend/src/test`) — Task 2 adds that coverage fresh rather than adjusting an existing fixture, except for one test that hardcodes the old cap as a bare literal (`30`) for an unrelated reason (see Task 2, `ContractServiceSignIT`).
- Youth-promotion's uncounted squad growth stays untouched (explicitly out of scope, confirmed with the user).
- Club selection at career creation stays hardcoded to `"CRI"` (explicitly out of scope).

---

### Task 1: Short club names in matches

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureResultResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java:175-180`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (construction at `:312-315` and `:423-427`)
- Modify: `frontend/src/api/types.ts` (`FixtureResultResponse` ~line 189, `LiveMatchState` ~line 465)
- Modify: `frontend/src/features/match/AdvanceEventCard.tsx:93-96`
- Modify: `frontend/src/features/match/LiveMatchPage.tsx:153`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java`, `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java`, `frontend/src/features/match/AdvanceEventCard.test.tsx`, `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Produces: `FixtureResultResponse(UUID fixtureId, UUID homeClubId, String homeClubName, String homeClubShortName, int homeGoals, UUID awayClubId, String awayClubName, String awayClubShortName, int awayGoals)`; `LiveMatchState(..., String homeClubName, String homeClubShortName, ..., String awayClubName, String awayClubShortName, ...)` (see full field list in Step 3).

- [ ] **Step 1: Write the failing tests**

No existing IT asserts `homeClubName`/`awayClubName` on either type today (confirmed via
repo-wide grep), so these are new test methods, not extensions of an existing assertion.
Scope: cover the `LiveMatchState` path (`toState()`, via `kickoff()`) and the live-match
`FixtureResultResponse` path (`finish()`); a dedicated IT for the instant AI-vs-AI
(`FixtureSimulator`) construction site is skipped as disproportionate for this bundle — its
`shortName()` helper is the identical one-line fallback already covered by the other two tests,
and the AI-vs-AI path has no direct UI consumer needing this field the way the live/News paths
do.

Add to `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java`
(reuses the file's existing `saveGameId`/`userId`/`managedClubId` fields set up in its
`@BeforeEach`):

```java
    @Test
    void kickoffPopulatesShortClubNames() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        assertThat(state.homeClubShortName()).isNotBlank();
        assertThat(state.awayClubShortName()).isNotBlank();
    }
```

Add to `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java` (reuses the
file's existing `@BeforeEach` fixture, mirroring `finishRecordsTheResultSimulatesTheRoundAndClearsTheSession`):

```java
    @Test
    void finishPopulatesShortClubNamesOnTheResult() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState s = null;
        for (int i = 0; i < 200 && (s == null || !"FULL_TIME".equals(s.status())); i++) {
            s = liveMatchService.advance(saveGameId, userId);
        }

        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);

        FixtureResultResponse userResult = resp.results().stream()
                .filter(r -> r.homeClubId().equals(managedClubId) || r.awayClubId().equals(managedClubId))
                .findFirst().orElseThrow();
        assertThat(userResult.homeClubShortName()).isNotBlank();
        assertThat(userResult.awayClubShortName()).isNotBlank();
    }
```

Frontend — `AdvanceEventCard.test.tsx` already has a `MATCH_RESULT` fixture (its `results`
entries carry no `homeClubShortName`/`awayClubShortName` — that fixture, left as-is, becomes the
regression test for the "old persisted payload, fields absent" fallback case for free, since its
existing `'renders fixture nodes with scores'` test (asserting `Criciúma`/`Figueirense`/`Avaí`
render) must keep passing unchanged once the fallback is wired in Step 3). Add ONE new test
inside the `describe('MATCH type', ...)` block for the "fields present" case:

```typescript
    it('prefers the short club name when present', () => {
      renderCard({
        ...MATCH_RESULT,
        results: [
          {
            fixtureId: 'f1', homeClubId: 'c1',
            homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma', homeGoals: 2,
            awayClubId: 'c2',
            awayClubName: 'Figueirense Futebol Clube', awayClubShortName: 'Figueirense', awayGoals: 1,
          },
        ],
      })
      expect(screen.getByText(/Criciúma\b/)).toBeInTheDocument()
      expect(screen.queryByText(/Criciúma Esporte Clube/)).not.toBeInTheDocument()
      expect(screen.queryByText(/Figueirense Futebol Clube/)).not.toBeInTheDocument()
    })
```

`LiveMatchPage.test.tsx` — **important:** its shared `STATE` fixture (top of file) does not
carry `homeClubShortName`/`awayClubShortName`. Since `LiveMatchState`'s new fields are always
backend-populated (no fallback needed, per the Global Constraints), the header will render them
directly with no `??` — so `STATE` itself must gain the two fields, or the existing
`'shows the score and the commentary feed'` test (which asserts `/Criciúma/` renders) breaks
once the header switches from `homeClubName` to `homeClubShortName`. Add both to `STATE`:

```typescript
const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma',
  awayClubName: 'Rival Futebol Clube', awayClubShortName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
  playerRatings: [],
}
```
(deliberately gives `homeClubName`/`awayClubName` a longer, distinct value from the short name
so the existing `'shows the score and the commentary feed'` test's
`expect(screen.getByText(/Criciúma/)).toBeInTheDocument()` assertion still passes — it matches
against the substring `Criciúma`, which the short name `'Criciúma'` satisfies — while doubling as
proof the FULL name never renders.) Add one new test:

```typescript
  it('renders the short club name, not the full legal name, in the header', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/Criciúma\b/)).toBeInTheDocument()
    expect(screen.queryByText(/Criciúma Esporte Clube/)).not.toBeInTheDocument()
  })
```

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: compilation FAILURE (no `homeClubShortName()` accessor yet). Then:
```bash
cd frontend && npx vitest run src/features/match/AdvanceEventCard.test.tsx src/features/match/LiveMatchPage.test.tsx
```
Expected: FAIL (property doesn't exist on the mock type / short name never rendered).

- [ ] **Step 3: Implement**

3a. `FixtureResultResponse.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.UUID;

public record FixtureResultResponse(
        UUID fixtureId,
        UUID homeClubId,
        String homeClubName,
        String homeClubShortName,
        int homeGoals,
        UUID awayClubId,
        String awayClubName,
        String awayClubShortName,
        int awayGoals
) {
}
```

3b. `FixtureSimulator.java` — add a private helper and update the one construction site:

```java
    private static String shortName(Club club) {
        if (club == null) return "";
        return club.getCommonName() != null ? club.getCommonName() : club.getName();
    }
```

```java
        FixtureResultResponse response = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "", shortName(homeClub),
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "", shortName(awayClub),
                match.awayGoals());
```

3c. `LiveMatchState.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.List;

public record LiveMatchState(
        String fixtureId,
        String competitionName,
        Integer round,
        String matchDate,
        String homeClubName,
        String homeClubShortName,
        String awayClubName,
        String awayClubShortName,
        boolean managedIsHome,
        int minute,
        int homeGoals,
        int awayGoals,
        String status,
        List<LiveEventResponse> events,
        List<LivePlayerResponse> onPitch,
        List<LivePlayerResponse> bench,
        int subsRemaining,
        LiveTactics currentTactics,
        List<PlayerRating> playerRatings) {
}
```

3d. `LiveMatchService.java` — add a private helper (near `isCardOrGoal`):

```java
    private String shortName(Club club) {
        return club.getCommonName() != null ? club.getCommonName() : club.getName();
    }
```

Update the `finish()` construction (`:312-315`):

```java
        FixtureResultResponse userResult = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, clubs.get(homeClubId).getName(), shortName(clubs.get(homeClubId)), timeline.homeGoals(),
                awayClubId, clubs.get(awayClubId).getName(), shortName(clubs.get(awayClubId)), timeline.awayGoals());
```

Update the `toState()` construction (`:423-427`):

```java
        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                clubs.get(fixture.getHomeClubId()).getName(), shortName(clubs.get(fixture.getHomeClubId())),
                clubs.get(fixture.getAwayClubId()).getName(), shortName(clubs.get(fixture.getAwayClubId())),
                lm.isManagedIsHome(), minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed, currentTactics, playerRatings);
```

3e. `frontend/src/api/types.ts` — `FixtureResultResponse` (fields possibly absent on old
persisted news payloads, hence optional):

```typescript
export type FixtureResultResponse = {
  fixtureId: string
  homeClubId: string
  homeClubName: string
  homeClubShortName?: string
  homeGoals: number
  awayClubId: string
  awayClubName: string
  awayClubShortName?: string
  awayGoals: number
}
```

`LiveMatchState` (always backend-populated, required):

```typescript
export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  homeClubName: string
  homeClubShortName: string
  awayClubName: string
  awayClubShortName: string
  managedIsHome: boolean
  minute: number
  homeGoals: number
  awayGoals: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  currentTactics: LiveTactics
  /** Managed players' 0–10 grades; populated only at full time, empty otherwise. */
  playerRatings: PlayerRating[]
}
```

3f. `AdvanceEventCard.tsx:93-96`:

```tsx
                {r.homeClubShortName ?? r.homeClubName} <strong>{r.homeGoals}</strong>
                {' — '}
                <strong>{r.awayGoals}</strong> {r.awayClubShortName ?? r.awayClubName}
```

3g. `LiveMatchPage.tsx:153`:

```tsx
          {state.homeClubShortName} <strong>{state.homeGoals}</strong> — <strong>{state.awayGoals}</strong> {state.awayClubShortName}
```

- [ ] **Step 4: Run the tests, then the affected packages**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*" --tests "br.com.carvoeiromanager.season.*"
```
Expected: BUILD SUCCESSFUL. Then:
```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck, all tests green.

- [ ] **Step 5: Commit**

```bash
git add -A backend frontend
git commit -m "feat(match): show short club names in live match and news/fixture views"
```

---

### Task 2: Squad cap 30 → 40

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java:25,87`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java:39,417`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/AiTransferEngine.java:33,114`
- Test: `backend/src/test/java/br/com/carvoeiromanager/common/SquadRulesTest.java` (new),
  `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java` (has the
  only existing test that hardcodes the old cap as a literal — fix in Step 1/3),
  `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java` (no
  `SQUAD_FULL` coverage exists there today — new test added in Step 1)

**Interfaces:**
- Produces: `public final class SquadRules { public static final int MAX_SQUAD_SIZE = 40; }`

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/br/com/carvoeiromanager/common/SquadRulesTest.java`:

```java
package br.com.carvoeiromanager.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SquadRulesTest {

    @Test
    void maxSquadSizeIsForty() {
        assertThat(SquadRules.MAX_SQUAD_SIZE).isEqualTo(40);
    }
}
```

`backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java` has no test
that actually fills a squad to trigger `SQUAD_FULL` — but
`youthProspectsDoNotCountAgainstTheSquadCapWhenSigning` (its last test) hardcodes the OLD cap as
a literal `30` (`int toAdd = Math.max(0, 30 - seniors) + 5;`, with a comment saying "exceeds the
30-player senior cap"). The test's actual assertion (youth prospects don't count toward the
senior cap) stays correct at any cap size, but the magic number should reference the new shared
constant instead of drifting out of sync with it. Change:

```java
        int toAdd = Math.max(0, 30 - seniors) + 5;
```
to:
```java
        int toAdd = Math.max(0, SquadRules.MAX_SQUAD_SIZE - seniors) + 5;
```
and the line right above it (`// Fill the academy so the club, counting prospects, exceeds the
30-player senior cap...`) to reference `SquadRules.MAX_SQUAD_SIZE` instead of "30-player". Also
update the boundary assertion two lines below from
`.isGreaterThanOrEqualTo(30)` to `.isGreaterThanOrEqualTo(SquadRules.MAX_SQUAD_SIZE)`. Add
`import br.com.carvoeiromanager.common.SquadRules;` to the file.

Add a new test to `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java`
(reuses the file's `createCareer()`/`anAiPlayer()`/`okWage()` helpers already shown above):

```java
    @Test
    void makingAnOfferWhenTheSquadIsAtTheCapIsRejected() {
        Career c = createCareer();
        int current = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        for (int i = 0; i < SquadRules.MAX_SQUAD_SIZE - current; i++) {
            Player filler = Player.create(c.id(), c.clubId(), "Reserva " + i,
                    br.com.carvoeiromanager.player.Position.MID, 22, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            playerRepository.save(filler);
        }
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size())
                .isEqualTo(SquadRules.MAX_SQUAD_SIZE);

        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        assertThatThrownBy(() ->
                transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.SQUAD_FULL));
    }
```
Add `import br.com.carvoeiromanager.common.SquadRules;` to the file (it already imports
`ApiException`, `ErrorCode`, `Player`, `BigDecimal`).

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.common.SquadRulesTest"
```
Expected: compilation FAILURE — `SquadRules` does not exist. Then the new 35-player tests fail
against the old cap once `SquadRules` compiles (run after Step 3 begins, or confirm via a
temporary local run with the old constant — either way, the 35-player test must fail before the
cap is raised).

- [ ] **Step 3: Implement**

3a. `backend/src/main/java/br/com/carvoeiromanager/common/SquadRules.java`:

```java
package br.com.carvoeiromanager.common;

/** Squad-size limits shared by every place that gates adding a player to the managed club. */
public final class SquadRules {

    public static final int MAX_SQUAD_SIZE = 40;

    private SquadRules() {
    }
}
```

3b. `ContractService.java` — remove the local constant (line 25) and its import needs no
change; update the reference at line 87:

```java
    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }
```
Add `import br.com.carvoeiromanager.common.SquadRules;` to the file's import block.

3c. `TransferService.java` — remove `static final int MAX_SQUAD_SIZE = 30;` (line 39, keep
`MIN_SQUAD_SIZE = 14` untouched); update the reference at line 417:

```java
    void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }
```
Add `import br.com.carvoeiromanager.common.SquadRules;`.

3d. `AiTransferEngine.java` — add `import br.com.carvoeiromanager.common.SquadRules;`, update
the javadoc reference at line 33 (`{@link TransferService#MAX_SQUAD_SIZE}` →
`{@link SquadRules#MAX_SQUAD_SIZE}`) and the usage at line 114:

```java
                .filter(club -> squadByClub.get(club.getId()).size() < SquadRules.MAX_SQUAD_SIZE)
```

- [ ] **Step 4: Run the tests, then the affected packages**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.common.*" --tests "br.com.carvoeiromanager.contract.*" --tests "br.com.carvoeiromanager.transfer.*"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(squad): raise squad cap 30 -> 40, consolidate the duplicated constant"
```

---

### Task 3: Mobile squad row — name-first, tap-to-expand

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx:521-542` (mobile row) and its imports
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: existing `playerNameButton`, `availabilityBadge`, `listedBadge`, `positionLabel`,
  `FitnessBar` (unchanged signatures).

- [ ] **Step 1: Write the failing test**

No test in `SquadPage.test.tsx` mocks `useIsMobile` today (its default in jsdom is already
`false`, so all existing tests exercise the desktop table branch and stay unaffected). Add the
mock near the file's other top-level `vi.mock` calls, plus an `afterEach` that restores the
`false` default so this one test's override can never leak into any other test regardless of
run order or failure:

```typescript
vi.mock('../../lib/useIsMobile', () => ({ useIsMobile: vi.fn(() => false) }))
```
Add the import: `import { useIsMobile } from '../../lib/useIsMobile'`. Add, right after the
existing `beforeEach` block:
```typescript
  afterEach(() => {
    vi.mocked(useIsMobile).mockReturnValue(false)
  })
```
(add `afterEach` to the existing `import { describe, it, expect, vi, beforeEach } from 'vitest'`
line.)

Add the test itself — reuses the file's existing `squad` fixture (already resolved by
`mockGetSquad` in `beforeEach`) and `renderWithProviders()` helper; `GK` (`'Goleiro Teste'`,
`overall: 72`) is the fixture's first player and renders first:

```typescript
  it('mobile: shows only the player name until expanded, then reveals position/OVR/fitness', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true)
    renderWithProviders()

    await screen.findByText('Goleiro Teste')
    expect(screen.queryByText('OVR 72')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /ver detalhes de goleiro teste/i }))

    expect(await screen.findByText('OVR 72')).toBeInTheDocument()
    expect(screen.getByText('GK')).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run to verify failure**

```bash
cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx
```
Expected: FAIL (chevron/expand control doesn't exist yet; position/OVR/fitness are always
visible, not gated behind expansion).

- [ ] **Step 3: Implement**

3a. Add imports to `SquadPage.tsx`:

```typescript
import {
  Box, Stack, Typography, Button, Chip, Alert, Snackbar, FormControl, NativeSelect, Link,
  Table, TableHead, TableBody, TableRow, TableCell, TableContainer, Paper, Card, CardContent,
  Tabs, Tab, Collapse, IconButton,
} from '@mui/material'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
```

3b. Add a small per-row expansion component right before the `SquadPage` mobile render block
(near the other per-player helper functions, e.g. after `listingButton`):

```typescript
  // Mobile row: name-only summary line (never overflows) + a tap-to-expand detail line with
  // position/OVR/fitness/badges — avoids the name spilling over the fixed-width chips on
  // narrow screens.
  function MobileSquadRow({ player }: { player: PlayerResponse }) {
    const [open, setOpen] = useState(false)
    return (
      <Card variant="outlined">
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            {statusButton(player)}
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', minWidth: 0 }}>
                <Typography
                  component="div"
                  sx={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
                >
                  {playerNameButton(player)}
                </Typography>
              </Box>
            </Box>
            <IconButton
              size="small"
              onClick={() => setOpen(o => !o)}
              aria-label={`${open ? 'Ocultar' : 'Ver'} detalhes de ${player.name}`}
            >
              {open ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
            </IconButton>
          </Stack>
          <Collapse in={open} timeout="auto" unmountOnExit>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1, flexWrap: 'wrap' }}>
              {availabilityBadge(player)}
              {listedBadge(player)}
              <Chip size="small" label={positionLabel(player.position)} />
              <Chip size="small" color="primary" label={`OVR ${player.overall}`} />
              <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
            </Stack>
          </Collapse>
        </CardContent>
      </Card>
    )
  }
```

3c. Replace the mobile render block (`:521-542`):

```tsx
        {isMobile ? (
          <Stack spacing={1.5} sx={{ mb: 2 }}>
            {displayPlayers.map(player => <MobileSquadRow key={player.id} player={player} />)}
          </Stack>
        ) : (
```
(the rest of the ternary — the desktop `TableContainer` branch — is unchanged.)

- [ ] **Step 4: Run the tests, then the frontend suite**

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck; all tests green, including the file's existing (desktop-branch)
`SquadPage` tests, which must be unaffected since `useIsMobile` still defaults to `false`
elsewhere in the file's tests.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(squad): mobile row shows the name first, position/OVR/fitness on tap"
```

---

### Task 4: Manager name + nationality at career creation

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__manager_identity.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGame.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/CreateSaveGameRequest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameController.java:26-30`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
  (`createCareer` and `getById`)
- Modify: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameOwnershipIT.java`,
  `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameListDeleteIT.java`,
  `backend/src/test/java/br/com/carvoeiromanager/auth/AccountDeletionIT.java`,
  `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchControllerIT.java`,
  `backend/src/test/java/br/com/carvoeiromanager/youth/YouthControllerIT.java` (each has a
  MockMvc `post("/api/v1/save-games")` call with no body — now needs one, see Step 3)
- Create: `frontend/src/features/career/NewCareerDialog.tsx`
- Create: `frontend/src/features/career/NewCareerDialog.test.tsx`
- Modify: `frontend/src/api/types.ts` (`SaveGameResponse`, new `CreateSaveGameRequest`)
- Modify: `frontend/src/api/career.ts:32-34` (`createCareer`)
- Modify: `frontend/src/components/Layout.tsx` (Nova Carreira wiring)
- Modify: `frontend/src/features/career/DashboardPage.tsx`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`,
  `frontend/src/features/career/DashboardPage.test.tsx`

**Interfaces:**
- Produces: `CreateSaveGameRequest(String managerName, String managerNationality)` (backend, both
  `@NotBlank`); `SaveGameResponse(..., String managerName, String managerNationality)`; frontend
  `createCareer(req: CreateSaveGameRequest, token: string): Promise<SaveGameResponse>`; frontend
  `type CreateSaveGameRequest = { managerName: string; managerNationality: string }`.

**IMPORTANT — blast radius already checked:** `saveGameService.createCareer(userId)` (the
current 1-arg method) is called from **~55 test files** at the service layer (e.g.
`new Career(user.getId(), saveGameService.createCareer(user.getId()))` in most `*IT.java`
career-fixture helpers). Changing that method's signature outright would force a 55-file
mechanical edit unrelated to this feature. Follow the exact precedent already established in
this codebase for this situation (`TransferService` kept a 2-arg `getMarket(saveGameId, userId)`
convenience overload during the transfer-market work rather than forcing every caller onto the
new filtered/paged signature): **keep `createCareer(UUID userId)` as a convenience overload**
that delegates to a default identity; add `createCareer(UUID userId, CreateSaveGameRequest req)`
as the real method the controller calls. This means Task 4 touches **zero** of those 55 files.

Separately (a real, smaller blast radius, confirmed via `grep -rl 'post("/api/v1/save-games")'
backend/src/test/java`): exactly **5 files** call the HTTP endpoint directly via MockMvc with no
request body at all (`SaveGameOwnershipIT`, `SaveGameListDeleteIT`, `AccountDeletionIT`,
`LiveMatchControllerIT`, `YouthControllerIT`) — once the controller requires
`@Valid @RequestBody CreateSaveGameRequest`, these 5 calls need a JSON body added or they'll
start failing with 400. Fixed in Step 3f below.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameServiceIT.java`, using the
file's existing fixture conventions (a `User` created via `User.create(...)` +
`userRepository.save(...)`, matching its other tests):

```java
    @Test
    void createCareerPersistsTheManagerIdentity() {
        User user = User.create("tigre_manager", "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId(),
                new CreateSaveGameRequest("Filipe Silva", "Brasil"));

        assertThat(response.managerName()).isEqualTo("Filipe Silva");
        assertThat(response.managerNationality()).isEqualTo("Brasil");
    }

    @Test
    void theOneArgOverloadStillWorksForCallersThatDontCareAboutManagerIdentity() {
        User user = User.create("tigre_default", "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId());

        assertThat(response.managerName()).isNotBlank();
        assertThat(response.managerNationality()).isNotBlank();
    }
```

Add to `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameOwnershipIT.java` (mirrors
its existing `andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))` lineup-validation test
exactly — see that test a few lines above for the established pattern this follows):

```java
    @Test
    void blankManagerNameIsRejectedWithAUniformValidationError() throws Exception {
        String token = register("validation_" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"managerName\":\"\",\"managerNationality\":\"Brasil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400));
    }
```

Frontend — `DashboardPage.test.tsx`, extend the `'renders the career when the stored save game
loads'` test's mock payload with `managerName: 'Filipe Silva', managerNationality: 'Brasil'` and
add an assertion:

```typescript
    await waitFor(() => {
      expect(screen.getByText(/Filipe Silva \(Brasil\)/)).toBeInTheDocument()
    })
```

`NewCareerDialog.test.tsx` (new file, model its harness on `LoadCareerDialog.test.tsx`):

```typescript
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NewCareerDialog } from './NewCareerDialog'
import { AuthProvider } from '../../auth/AuthContext'
import { createCareer } from '../../api/career'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('../../api/career', () => ({
  createCareer: vi.fn(),
}))

function renderDialog() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <NewCareerDialog open onClose={() => {}} />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NewCareerDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'asd', userId: 'U1' }))
  })

  it('disables the submit button until both fields are filled', () => {
    renderDialog()
    expect(screen.getByRole('button', { name: /iniciar carreira/i })).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    expect(screen.getByRole('button', { name: /iniciar carreira/i })).toBeDisabled()
  })

  it('submits the manager name and nationality and navigates to the new squad', async () => {
    ;(createCareer as Mock).mockResolvedValue({
      id: 'save-9', managedClubId: 'club-1', managerName: 'Filipe Silva', managerNationality: 'Brasil',
    })
    renderDialog()

    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    // Nationality: MUI Autocomplete — open it and pick 'Brasil' from the option list.
    fireEvent.mouseDown(screen.getByLabelText(/nacionalidade/i))
    fireEvent.click(await screen.findByText('Brasil'))

    fireEvent.click(screen.getByRole('button', { name: /iniciar carreira/i }))

    await waitFor(() => {
      expect(createCareer).toHaveBeenCalledWith(
        { managerName: 'Filipe Silva', managerNationality: 'Brasil' }, 'test-token',
      )
    })
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/squad?saveGameId=save-9&clubId=club-1')
    })
  })
})
```

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.career.*"
```
Expected: compilation FAILURE (`CreateSaveGameRequest` has no matching constructor, no
`managerName()`/`managerNationality()` accessors). Then:
```bash
cd frontend && npx vitest run src/features/career/
```
Expected: FAIL — `NewCareerDialog` module doesn't exist; `DashboardPage` doesn't render the new
line.

- [ ] **Step 3: Implement**

3a. `V23__manager_identity.sql`:

```sql
ALTER TABLE save_games ADD COLUMN manager_name VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE save_games ADD COLUMN manager_nationality VARCHAR(40) NOT NULL DEFAULT '';
```

3b. `SaveGame.java` — add the two fields, thread them through `create(...)`, add getters:

```java
    @Column(name = "manager_name", nullable = false, length = 80)
    private String managerName;

    @Column(name = "manager_nationality", nullable = false, length = 40)
    private String managerNationality;
```
Update the private constructor and `create` factory:

```java
    private SaveGame(UUID id, UUID userId, UUID managedClubId, int seasonYear,
                     int currentRound, SaveGameStatus status, Instant createdAt,
                     String managerName, String managerNationality) {
        this.id = id;
        this.userId = userId;
        this.managedClubId = managedClubId;
        this.seasonYear = seasonYear;
        this.currentRound = currentRound;
        this.status = status;
        this.createdAt = createdAt;
        this.managerName = managerName;
        this.managerNationality = managerNationality;
    }

    public static SaveGame create(UUID userId, int seasonYear, String managerName, String managerNationality) {
        return new SaveGame(UUID.randomUUID(), userId, null, seasonYear, 0, SaveGameStatus.IN_PROGRESS,
                Instant.now(), managerName, managerNationality);
    }
```
Add getters next to the others:
```java
    public String getManagerName() { return managerName; }
    public String getManagerNationality() { return managerNationality; }
```

3c. `CreateSaveGameRequest.java`:

```java
package br.com.carvoeiromanager.career;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/v1/save-games. */
public record CreateSaveGameRequest(
        @NotBlank String managerName,
        @NotBlank String managerNationality
) {}
```

3d. `SaveGameController.java`:

```java
    @PostMapping
    public ResponseEntity<SaveGameResponse> createCareer(
            @Valid @RequestBody CreateSaveGameRequest request, @CurrentUser UUID userId) {
        SaveGameResponse response = saveGameService.createCareer(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
```
Add imports `jakarta.validation.Valid` and `org.springframework.web.bind.annotation.RequestBody`.

3e. `SaveGameResponse.java` — append the two fields:

```java
public record SaveGameResponse(
        UUID id,
        int seasonYear,
        UUID competitionId,
        String competitionName,
        UUID managedClubId,
        String managedClubName,
        int currentRound,
        int totalRounds,
        String currentDate,
        List<CompetitionSummary> competitions,
        String status,
        boolean transferWindowOpen,
        String managerName,
        String managerNationality
) {}
```

3f. `SaveGameService.java` — keep the existing 1-arg method as a convenience overload
delegating to a new 2-arg method (see the blast-radius note above the Interfaces block for why);
rename the current method body to take `CreateSaveGameRequest` and add the overload right above
it:

```java
    /** Convenience overload for internal/test callers that don't need a specific manager identity. */
    @Transactional
    public SaveGameResponse createCareer(UUID userId) {
        return createCareer(userId, new CreateSaveGameRequest("Técnico", "Brasil"));
    }

    /**
     * Creates a new single-player career by copying the immutable seed into per-SaveGame rows:
     * clubs, players, competitions with membership rows, and the full dated calendar generated
     * by SeasonCalendarGenerator.
     */
    @Transactional
    public SaveGameResponse createCareer(UUID userId, CreateSaveGameRequest req) {
```
And the `SaveGame.create` call inside that method body:
```java
        SaveGame saveGame = SaveGame.create(userId, seasonYear, req.managerName(), req.managerNationality());
```
```java
        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                primaryCompetition.getId(),
                primaryCompetition.getName(),
                managedClubId,
                managedClubName,
                saveGame.getCurrentRound(),
                primaryCompetition.getTotalRounds(),
                seasonStart.toString(),
                competitionSummaries,
                saveGame.getStatus().name(),
                transferWindow.isOpen(seasonStart),
                saveGame.getManagerName(),
                saveGame.getManagerNationality());
```
`getById`'s construction site gains the same two trailing arguments:
```java
        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                competitionId,
                competitionName,
                saveGame.getManagedClubId(),
                managedClubName,
                saveGame.getCurrentRound(),
                totalRounds,
                currentDate,
                competitionSummaries,
                saveGame.getStatus().name(),
                saveGame.getCurrentDate() != null && transferWindow.isOpen(saveGame.getCurrentDate()),
                saveGame.getManagerName(),
                saveGame.getManagerNationality());
```

3g. Frontend `frontend/src/api/types.ts` — extend `SaveGameResponse` and add the request type:

```typescript
export type SaveGameResponse = {
  id: string
  seasonYear: number
  competitionId: string
  competitionName: string
  managedClubId: string
  managedClubName: string
  currentRound: number
  totalRounds: number
  currentDate?: string | null
  competitions?: CompetitionSummary[]
  status?: 'IN_PROGRESS' | 'FINISHED'
  transferWindowOpen?: boolean
  managerName?: string
  managerNationality?: string
}

export type CreateSaveGameRequest = {
  managerName: string
  managerNationality: string
}
```
(kept optional on the response type since `SaveGameSummaryResponse`/other lighter DTOs sharing
this shape may omit them — check `SaveGameSummaryResponse` usage sites; if `SaveGameResponse` is
never partially constructed elsewhere, required fields are fine too — match whatever the file's
existing optional-field convention does for similarly-new fields like `transferWindowOpen`.)

3h. `frontend/src/api/career.ts:32-34`:

```typescript
export function createCareer(req: CreateSaveGameRequest, token: string): Promise<SaveGameResponse> {
  return apiPost<SaveGameResponse>('/save-games', req, token)
}
```
Add `CreateSaveGameRequest` to the file's type import list from `./types`.

3i. `frontend/src/features/career/NewCareerDialog.tsx` (new):

```tsx
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Autocomplete, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField,
} from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { createCareer } from '../../api/career'
import { saveGameKeyFor } from './saveGameKey'
import type { SaveGameResponse } from '../../api/types'

type Props = { open: boolean; onClose: () => void }

const COUNTRIES = [
  'Brasil', 'Argentina', 'Uruguai', 'Paraguai', 'Chile', 'Colômbia',
  'Portugal', 'Espanha', 'Itália', 'França', 'Alemanha', 'Inglaterra',
]

/** Collects the manager's name and nationality before starting a fresh career. */
export function NewCareerDialog({ open, onClose }: Props) {
  const { user, token } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [managerName, setManagerName] = useState('')
  const [managerNationality, setManagerNationality] = useState<string | null>(null)

  const mutation = useMutation<SaveGameResponse>({
    mutationFn: () => createCareer({ managerName, managerNationality: managerNationality ?? '' }, token ?? ''),
    onSuccess: (data) => {
      localStorage.setItem(saveGameKeyFor(user?.userId), data.id)
      void queryClient.invalidateQueries()
      onClose()
      void navigate(`/squad?saveGameId=${data.id}&clubId=${data.managedClubId}`)
    },
  })

  const canSubmit = managerName.trim().length > 0 && Boolean(managerNationality)

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Nova carreira</DialogTitle>
      <DialogContent dividers>
        <TextField
          label="Nome do técnico"
          fullWidth
          margin="normal"
          value={managerName}
          onChange={e => setManagerName(e.target.value)}
        />
        <Autocomplete
          options={COUNTRIES}
          value={managerNationality}
          onChange={(_, value) => setManagerNationality(value)}
          renderInput={(params) => <TextField {...params} label="Nacionalidade" margin="normal" />}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button
          variant="contained"
          disabled={!canSubmit || mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? 'Criando…' : 'Iniciar carreira'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
```

3j. `Layout.tsx` — remove the `newCareerMutation` block (lines ~127-134) and the `createCareer`
import, add dialog state, simplify `handleNewCareer`, wire the menu item and render the dialog:

```typescript
import { NewCareerDialog } from '../features/career/NewCareerDialog'
```
```typescript
  const [newCareerOpen, setNewCareerOpen] = useState(false)
```
```typescript
  // "Nova Carreira" lives in the gear menu: start a fresh career at any time. The current
  // save isn't deleted, but the active pointer moves to the new one — confirm to avoid slips.
  const handleNewCareer = () => {
    setGearAnchor(null)
    const hasActive = Boolean(saveGameId)
    if (hasActive && !window.confirm('Começar uma nova carreira? A carreira atual continuará salva, mas você voltará ao início.')) {
      return
    }
    setNewCareerOpen(true)
  }
```
Menu item (remove the `disabled`/pending-label logic, now owned by the dialog):
```tsx
            <MenuItem onClick={handleNewCareer}>Nova Carreira</MenuItem>
```
Render next to `LoadCareerDialog`:
```tsx
      <NewCareerDialog open={newCareerOpen} onClose={() => setNewCareerOpen(false)} />
```
Remove the now-unused `createCareer` import from `'../api/career'` at the top of the file (check
the rest of the import list stays valid — `advanceRound, getAdvancePreview, getLineup,
getSaveGame` remain used).

3k. `DashboardPage.tsx` — add one `StatRow` to the existing summary `Card`:

```tsx
              <StatRow label="Técnico:" value={`${saveGame.managerName} (${saveGame.managerNationality})`} />
```
Place it as the first row (before "Temporada:") or last (after "Saldo:") — either is fine;
place it first since it's identity, not a season stat.

3l. Fix the 5 MockMvc `post("/api/v1/save-games")` calls that currently send no body (listed in
the Files section above) — each needs `.contentType("application/json")` and a valid
`.content(...)` added, otherwise the new `@Valid @RequestBody` on the controller rejects them
with 400. Apply this exact change to each of the 5 call sites (adjust only the `post(...)` chain
shown; everything else in each file — headers, assertions, surrounding code — is unchanged):

```java
        // before:
        // mockMvc.perform(post("/api/v1/save-games")
        //                 .header("Authorization", bearer(token)))
        // after:
        mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"managerName\":\"Técnico\",\"managerNationality\":\"Brasil\"}"))
```

(`LiveMatchControllerIT.java` and `YouthControllerIT.java` use a local variable named `bearer`
rather than a `bearer(token)` helper method — check each file's exact header line before editing
and keep whatever authorization expression it already uses; only add the two new chained calls.)

- [ ] **Step 4: Run the tests, then the affected packages**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.career.*"
```
Expected: BUILD SUCCESSFUL. Then the full backend suite (this task touches a shared response
DTO and a controller signature — verify nothing else broke):
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL. Then:
```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck; all tests green, including `Layout.test.tsx` (which mocks
`createCareer` wholesale via `vi.mock('../api/career', ...)` — since that mock covers the
module regardless of which component under the `Layout` tree imports it, no changes should be
needed there, but re-run to confirm).

- [ ] **Step 5: Commit**

```bash
git add -A backend frontend
git commit -m "feat(career): capture manager name and nationality when starting a career"
```

---

## Verification (end-to-end sanity, after all four tasks)

Start a fresh career via "Nova Carreira": the dialog should require both a manager name and a
nationality before "Iniciar carreira" enables; after creating it, "Início" shows "Técnico: <name>
(<nationality>)". Play/advance a match: the live-match header and the News feed show short club
names ("Criciúma" not "Criciúma Esporte Clube"). On a narrow viewport, the squad list's mobile
cards show only the player name until tapped, then reveal position/OVR/fitness with no visual
overflow. Sign enough free agents/buy enough players to exceed 30 (up to 40) without hitting
`SQUAD_FULL` prematurely, and confirm it still triggers at 40.
