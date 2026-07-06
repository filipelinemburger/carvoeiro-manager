# Phase 3c-2 — Youth Academy UI + Deferred Backend Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the player-facing "Base" (youth academy) screen — list prospects with a 1–5 star potential, promote/release them — surface youth intake/departures in the season rollover summary, and close the two backend items deferred from 3c-1 (prospects wrongly counting against the senior squad cap; the now-dead `newcomers` field).

**Architecture:** Backend cleanup is two small, isolated changes to existing services (a repository-method swap and a dead-field removal). The frontend adds a new feature folder `features/base/` (screen + a reusable star component), a `api/youth.ts` client hitting the already-live save-scoped endpoints, and edits three existing files (router, nav, season summary). No new backend endpoints — the 3c-1 API is already in place.

**Tech Stack:** Backend: Java 25 / Spring Boot, JPA, Testcontainers-Postgres ITs. Frontend: React 18 + TypeScript (strict) + MUI + @tanstack/react-query + react-router-dom, Vitest + React Testing Library.

## Global Constraints

- **Backend build/test:** run gradle from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`. ITs use `@SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)`.
- **Frontend deploy guard:** `npm run build` = `tsc -b && vite build`. `tsc -b` type-checks `.test.tsx` files. **Every task that touches the frontend must end green under `tsc -b`, not just `vitest`.** A mock missing a now-required field, or a component reading a removed type field, breaks the deploy even when tests pass.
- **API convention:** youth endpoints are already nested and save-scoped: `GET /api/v1/save-games/{saveGameId}/youth-prospects`, `POST …/{prospectId}/promote`, `POST …/{prospectId}/release` (release returns 204). Frontend paths passed to `apiGet/apiPost` omit the `/api/v1` prefix (the client prepends it via `BASE`).
- **Determinism:** no engine/RNG code is touched here; do not alter rollover draw order.
- **Hidden potential:** the frontend must never receive or display the raw `potential` ceiling — only `potentialStars` (1–5). `YouthProspectResponse` already excludes raw potential; keep it that way.
- **Git:** work on `main`, one commit per task, commit only after the task's tests are green. Commit messages end with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
  Do NOT `git push` (the user pushes) and never `--amend`.
- **Cross-task coupling (read before sequencing):** Task 2 removes `newcomers` from the backend `RolloverResponse`; Task 6 removes it from the frontend type and its render. Both land before the user pushes, so the end state is consistent — but do not deploy the Task-2 backend against a pre-Task-6 frontend (the old UI reads `result.newcomers.length` and would throw on `undefined`).

## File Structure

**Backend (modify):**
- `backend/.../contract/ContractService.java` — squad-cap query excludes prospects (1 line).
- `backend/.../transfer/TransferService.java` — three squad queries exclude prospects.
- `backend/.../season/RolloverResponse.java` — drop dead `newcomers` field.
- `backend/.../season/SeasonRolloverService.java` — drop the `newcomers` list + constructor arg.
- `backend/.../contract/ContractServiceSignIT.java` — add a squad-cap-exclusion test.
- `backend/.../transfer/TransferServiceBuyingIT.java` — add a squad-cap-exclusion test.

**Frontend (create):**
- `frontend/src/api/youth.ts` — `listProspects` / `promoteProspect` / `releaseProspect`.
- `frontend/src/api/youth.test.ts` — asserts the exact save-scoped URLs.
- `frontend/src/features/base/PotentialStars.tsx` — read-only 1–5 star display.
- `frontend/src/features/base/PotentialStars.test.tsx`.
- `frontend/src/features/base/BasePage.tsx` — the screen.
- `frontend/src/features/base/BasePage.test.tsx`.

**Frontend (modify):**
- `frontend/src/api/types.ts` — add `YouthProspectResponse`; (Task 6) update `RolloverResponse`.
- `frontend/src/router.tsx` — add the `/base` route.
- `frontend/src/components/Layout.tsx` — add the "Base" nav entry.
- `frontend/src/features/season/SeasonSummaryPage.tsx` — render youth intake/departures, drop `newcomers`.
- `frontend/src/features/season/SeasonSummaryPage.test.tsx` — update the rollover mocks/assertions.

---

## Task 1: Backend — exclude prospects from the senior squad-cap / registration queries

**Why:** `ContractService.requireSquadRoom` and three `TransferService` queries load `findByClubId(managedClubId)`, which now includes academy prospects. A full academy (up to 10) therefore eats into the 30-player senior cap (blocking signings/buys prematurely), exposes prospects to AI bids, and counts toward the min-squad / last-GK sell guard. Prospects live in their own pool; they must not count until promoted. The repository already has `findByClubIdAndYouthProspectFalse` — this is a targeted swap at four call sites.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java:89`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java:232`, `:274`, `:318`
- Test: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java`

**Interfaces:**
- Consumes: `PlayerRepository.findByClubIdAndYouthProspectFalse(UUID) : List<Player>` (already exists), `Player.markAsProspect(int potential)` (already exists, sets `youth_prospect=true`).
- Produces: no new symbols; behavior change only.

- [ ] **Step 1: Write the failing squad-cap test in `ContractServiceSignIT`**

Add this method to `ContractServiceSignIT` (it uses the existing `createCareer()` / `anyFreeAgent()` helpers and imports `Position`):

```java
    @Test
    void youthProspectsDoNotCountAgainstTheSquadCapWhenSigning() {
        Career c = createCareer();
        int seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        // Fill the academy so the club, counting prospects, exceeds the 30-player senior cap...
        int toAdd = Math.max(0, 30 - seniors) + 5;
        for (int i = 0; i < toAdd; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i,
                    br.com.carvoeiromanager.player.Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            prospect.markAsProspect(80);
            playerRepository.save(prospect);
        }
        assertThat(playerRepository.findByClubId(c.clubId()).size()).isGreaterThanOrEqualTo(30);

        // ...but seniors are still under the cap, so signing a free agent must succeed.
        FreeAgentResponse fa = anyFreeAgent(c);
        PlayerContractResponse r = contractService.signFreeAgent(
                c.id(), c.userId(), fa.playerId(), fa.wageDemand(), 3);

        assertThat(r.contractYears()).isEqualTo(3);
        assertThat(playerRepository.findById(fa.playerId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }
```

- [ ] **Step 2: Write the failing squad-cap test in `TransferServiceBuyingIT`**

Add this method to `TransferServiceBuyingIT` (uses the existing `createCareer()`, `anAiPlayer()`, `okWage()` helpers):

```java
    @Test
    void youthProspectsDoNotCountAgainstTheSquadCapWhenBuying() {
        Career c = createCareer();
        int seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        int toAdd = Math.max(0, 30 - seniors) + 5;
        for (int i = 0; i < toAdd; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i,
                    br.com.carvoeiromanager.player.Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            prospect.markAsProspect(80);
            playerRepository.save(prospect);
        }
        assertThat(playerRepository.findByClubId(c.clubId()).size()).isGreaterThanOrEqualTo(30);

        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }
```

- [ ] **Step 3: Run both tests to verify they FAIL**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home \
  ./gradlew test --tests "br.com.carvoeiromanager.contract.ContractServiceSignIT" \
                 --tests "br.com.carvoeiromanager.transfer.TransferServiceBuyingIT"
```
Expected: the two new tests FAIL — the sign throws `SQUAD_FULL` and the buy throws `SQUAD_FULL` (or the makeOffer returns a non-ACCEPTED status) because prospects are counted.

- [ ] **Step 4: Apply the four query swaps**

In `ContractService.java:89`, change:
```java
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
```
to:
```java
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= MAX_SQUAD_SIZE) {
```

In `TransferService.java:232`, change:
```java
        List<Player> managedPlayers = playerRepository.findByClubId(managedClubId).stream()
```
to:
```java
        List<Player> managedPlayers = playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).stream()
```

In `TransferService.java:274`, change:
```java
        List<Player> squad = playerRepository.findByClubId(managedClubId);
```
to:
```java
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(managedClubId);
```

In `TransferService.java:318`, change:
```java
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
```
to:
```java
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= MAX_SQUAD_SIZE) {
```

- [ ] **Step 5: Run the two ITs to verify they PASS, plus the surrounding suites for regressions**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home \
  ./gradlew test --tests "br.com.carvoeiromanager.contract.*" \
                 --tests "br.com.carvoeiromanager.transfer.*"
```
Expected: BUILD SUCCESSFUL, including the two new tests and all existing contract/transfer ITs.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java \
        backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java \
        backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java \
        backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java
git commit -m "$(cat <<'EOF'
fix(youth): exclude prospects from senior squad-cap + transfer queries

Prospects live in their own pool and must not count against the 30-player
senior cap, be bid on by AI clubs, or count toward the min-squad/last-GK
sell guard. Swap findByClubId -> findByClubIdAndYouthProspectFalse at the
four ContractService/TransferService call sites; +2 guarding ITs.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Backend — remove the dead `newcomers` field from the rollover response

**Why:** `SeasonRolloverService` declares `newcomers` but never populates it — the managed newgen-on-retirement that used to fill it was removed in 3c-1 (managed retirees are now replaced from the academy), and the AI newgen path does not record it. The field is always `[]`. `youthIntake` is its real replacement. `grep` confirms `newcomers` appears only in `RolloverResponse.java` and `SeasonRolloverService.java` — no backend test references it.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java:188`, `:291`

**Interfaces:**
- Produces: `RolloverResponse` record loses its `newcomers` component. New shape:
  `(int seasonYear, int currentRound, List<SquadChangeResponse> retirements, List<SquadChangeResponse> expiredContracts, List<SquadChangeResponse> youthIntake, List<SquadChangeResponse> youthDepartures)`.

- [ ] **Step 1: Remove the field from the record**

Edit `RolloverResponse.java` to:
```java
package br.com.carvoeiromanager.season;

import java.util.List;

public record RolloverResponse(
        int seasonYear,
        int currentRound,
        List<SquadChangeResponse> retirements,
        List<SquadChangeResponse> expiredContracts,
        List<SquadChangeResponse> youthIntake,
        List<SquadChangeResponse> youthDepartures
) {}
```

- [ ] **Step 2: Remove the list declaration in `SeasonRolloverService`**

Delete line 188:
```java
        List<SquadChangeResponse> newcomers = new ArrayList<>();
```

- [ ] **Step 3: Drop `newcomers` from the constructor call**

At the end of the method (around line 290-292), change:
```java
        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(newcomers), List.copyOf(expiredContracts),
                List.copyOf(youthIntake), List.copyOf(youthDepartures));
```
to:
```java
        return new RolloverResponse(saveGame.getSeasonYear(), saveGame.getCurrentRound(),
                List.copyOf(retirements), List.copyOf(expiredContracts),
                List.copyOf(youthIntake), List.copyOf(youthDepartures));
```

- [ ] **Step 4: Compile + run the rollover suites**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home \
  ./gradlew test --tests "br.com.carvoeiromanager.season.*" \
                 --tests "br.com.carvoeiromanager.youth.*"
```
Expected: BUILD SUCCESSFUL. If the compiler reports any other reference to `.newcomers()` or the old 7-arg constructor, fix that call site (there should be none per grep).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/season/RolloverResponse.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java
git commit -m "$(cat <<'EOF'
refactor(season): drop always-empty newcomers from RolloverResponse

The managed newgen-on-retirement that populated newcomers was removed in
3c-1 (retirees now refill from the academy); youthIntake replaces it. The
field was dead (always []). Remove it from the record + service.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Frontend — `YouthProspectResponse` type + youth API client

**Files:**
- Modify: `frontend/src/api/types.ts` (add a type; do NOT touch `RolloverResponse` here — that is Task 6)
- Create: `frontend/src/api/youth.ts`
- Create: `frontend/src/api/youth.test.ts`

**Interfaces:**
- Consumes: `apiGet<T>(path, token)`, `apiPost<T>(path, body, token)` from `./client`.
- Produces:
  - `type YouthProspectResponse = { id, name, position: 'GK'|'DEF'|'MID'|'ATT', age, overall, pace, finishing, passing, tackling, goalkeeping, stamina, potentialStars }` (all numbers except id/name/position).
  - `listProspects(saveGameId: string, token: string): Promise<YouthProspectResponse[]>`
  - `promoteProspect(saveGameId: string, prospectId: string, token: string): Promise<YouthProspectResponse>`
  - `releaseProspect(saveGameId: string, prospectId: string, token: string): Promise<void>`

- [ ] **Step 1: Add the `YouthProspectResponse` type**

Append to `frontend/src/api/types.ts` (near the other response types, e.g. after the `SeasonSummary`/`SeasonDetail` block):
```typescript
export type YouthProspectResponse = {
  id: string
  name: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  age: number
  overall: number
  pace: number
  finishing: number
  passing: number
  tackling: number
  goalkeeping: number
  stamina: number
  potentialStars: number
}
```

- [ ] **Step 2: Write the failing API-client test**

Create `frontend/src/api/youth.test.ts`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import { apiGet, apiPost } from './client'
import { listProspects, promoteProspect, releaseProspect } from './youth'

const mockGet = vi.mocked(apiGet)
const mockPost = vi.mocked(apiPost)

describe('youth api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists prospects under the save-scoped path', () => {
    listProspects('sg-1', 'tok')
    expect(mockGet).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects', 'tok')
  })

  it('promotes via POST to the nested path', () => {
    promoteProspect('sg-1', 'p-1', 'tok')
    expect(mockPost).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects/p-1/promote', {}, 'tok')
  })

  it('releases via POST to the nested path', () => {
    releaseProspect('sg-1', 'p-1', 'tok')
    expect(mockPost).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects/p-1/release', {}, 'tok')
  })
})
```

- [ ] **Step 3: Run it to verify it FAILS**

```bash
cd frontend && npx vitest run src/api/youth.test.ts
```
Expected: FAIL — `Cannot find module './youth'` (not created yet).

- [ ] **Step 4: Create the API client**

Create `frontend/src/api/youth.ts`:
```typescript
import { apiGet, apiPost } from './client'
import type { YouthProspectResponse } from './types'

export function listProspects(saveGameId: string, token: string): Promise<YouthProspectResponse[]> {
  return apiGet<YouthProspectResponse[]>(`/save-games/${saveGameId}/youth-prospects`, token)
}

export function promoteProspect(
  saveGameId: string,
  prospectId: string,
  token: string,
): Promise<YouthProspectResponse> {
  return apiPost<YouthProspectResponse>(
    `/save-games/${saveGameId}/youth-prospects/${prospectId}/promote`,
    {},
    token,
  )
}

// The backend release endpoint is POST and returns 204 No Content.
export function releaseProspect(saveGameId: string, prospectId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/youth-prospects/${prospectId}/release`, {}, token)
}
```

- [ ] **Step 5: Run the test to verify it PASSES + type-check**

```bash
cd frontend && npx vitest run src/api/youth.test.ts && npx tsc -b
```
Expected: 3 passing; `tsc -b` clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/youth.ts frontend/src/api/youth.test.ts
git commit -m "$(cat <<'EOF'
feat(youth): frontend YouthProspectResponse type + youth API client

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Frontend — `PotentialStars` component

**Why:** A small, deterministic, testable 1–5 star display used by the Base screen. Uses filled/outline star icons (present in `@mui/icons-material`) and exposes an `aria-label` so tests assert on the star count without pixel-peeping.

**Files:**
- Create: `frontend/src/features/base/PotentialStars.tsx`
- Create: `frontend/src/features/base/PotentialStars.test.tsx`

**Interfaces:**
- Produces: `PotentialStars({ stars }: { stars: number })` — renders 5 icons, first `stars` filled; clamps to `[0,5]`; `aria-label={`Potencial ${clamped} de 5`}`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/base/PotentialStars.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { PotentialStars } from './PotentialStars'

describe('PotentialStars', () => {
  it('labels the rendered star count', () => {
    render(<PotentialStars stars={4} />)
    expect(screen.getByLabelText('Potencial 4 de 5')).toBeInTheDocument()
  })

  it('clamps values above 5', () => {
    render(<PotentialStars stars={7} />)
    expect(screen.getByLabelText('Potencial 5 de 5')).toBeInTheDocument()
  })

  it('clamps negative values to 0', () => {
    render(<PotentialStars stars={-2} />)
    expect(screen.getByLabelText('Potencial 0 de 5')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it FAILS**

```bash
cd frontend && npx vitest run src/features/base/PotentialStars.test.tsx
```
Expected: FAIL — module `./PotentialStars` not found.

- [ ] **Step 3: Create the component**

Create `frontend/src/features/base/PotentialStars.tsx`:
```typescript
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import { Box } from '@mui/material'

/** Read-only 1–5 star potential band. Never renders the raw potential ceiling. */
export function PotentialStars({ stars }: { stars: number }) {
  const clamped = Math.max(0, Math.min(5, stars))
  return (
    <Box
      component="span"
      aria-label={`Potencial ${clamped} de 5`}
      title={`Potencial ${clamped}/5`}
      sx={{ display: 'inline-flex', color: 'warning.main', verticalAlign: 'middle' }}
    >
      {Array.from({ length: 5 }, (_, i) =>
        i < clamped ? (
          <StarIcon key={i} fontSize="small" />
        ) : (
          <StarBorderIcon key={i} fontSize="small" />
        ),
      )}
    </Box>
  )
}
```

- [ ] **Step 4: Run the test to verify it PASSES + type-check**

```bash
cd frontend && npx vitest run src/features/base/PotentialStars.test.tsx && npx tsc -b
```
Expected: 3 passing; `tsc -b` clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/base/PotentialStars.tsx frontend/src/features/base/PotentialStars.test.tsx
git commit -m "$(cat <<'EOF'
feat(youth): PotentialStars component (1-5 star potential band)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Frontend — `BasePage` screen + route + nav entry

**Why:** The player-facing academy. Lists prospects (desktop table / mobile cards), shows OVR + star potential, and offers Promover / Liberar with a confirm. Reachable via a `/base` route and a "Base" nav entry.

**Files:**
- Create: `frontend/src/features/base/BasePage.tsx`
- Create: `frontend/src/features/base/BasePage.test.tsx`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/components/Layout.tsx:200`

**Interfaces:**
- Consumes: `listProspects/promoteProspect/releaseProspect` (Task 3), `PotentialStars` (Task 4), `useAuth`, `useSearchParams`, `useIsMobile`, `YouthProspectResponse`.
- Produces: `export function BasePage()`.

- [ ] **Step 1: Write the failing screen test**

Create `frontend/src/features/base/BasePage.test.tsx`:
```typescript
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { BasePage } from './BasePage'
import { AuthProvider } from '../../auth/AuthContext'
import type { YouthProspectResponse } from '../../api/types'

vi.mock('../../api/youth', () => ({
  listProspects: vi.fn(),
  promoteProspect: vi.fn(),
  releaseProspect: vi.fn(),
}))

import { listProspects, promoteProspect, releaseProspect } from '../../api/youth'

const mockList = vi.mocked(listProspects)
const mockPromote = vi.mocked(promoteProspect)
const mockRelease = vi.mocked(releaseProspect)

const prospect = (over: Partial<YouthProspectResponse> = {}): YouthProspectResponse => ({
  id: 'p-1',
  name: 'Joia da Base',
  position: 'ATT',
  age: 16,
  overall: 58,
  pace: 60,
  finishing: 55,
  passing: 50,
  tackling: 30,
  goalkeeping: 10,
  stamina: 65,
  potentialStars: 4,
  ...over,
})

function renderWithProviders(saveGameId = 'sg-1', clubId = 'club-1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={[`/base?saveGameId=${saveGameId}&clubId=${clubId}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <BasePage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('BasePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    mockList.mockResolvedValue([prospect()])
    mockPromote.mockResolvedValue(prospect())
    mockRelease.mockResolvedValue(undefined)
  })

  it('renders prospects with their potential stars', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    expect(screen.getByLabelText('Potencial 4 de 5')).toBeInTheDocument()
  })

  it('shows the empty state when the academy is empty', async () => {
    mockList.mockResolvedValue([])
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText(/Sua base ainda não tem jovens/)).toBeInTheDocument(),
    )
  })

  it('promotes a prospect after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Promover' }))
    await waitFor(() => expect(mockPromote).toHaveBeenCalledWith('sg-1', 'p-1', 'test-token'))
    confirmSpy.mockRestore()
  })

  it('does not release when confirmation is declined', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Liberar' }))
    expect(mockRelease).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
```

- [ ] **Step 2: Run it to verify it FAILS**

```bash
cd frontend && npx vitest run src/features/base/BasePage.test.tsx
```
Expected: FAIL — module `./BasePage` not found.

- [ ] **Step 3: Create the screen**

Create `frontend/src/features/base/BasePage.tsx`:
```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { listProspects, promoteProspect, releaseProspect } from '../../api/youth'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { PotentialStars } from './PotentialStars'
import type { YouthProspectResponse } from '../../api/types'

const POSITION_LABEL: Record<YouthProspectResponse['position'], string> = {
  GK: 'GOL',
  DEF: 'ZAG',
  MID: 'MEI',
  ATT: 'ATA',
}

export function BasePage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['youth', saveGameId],
    queryFn: () => listProspects(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['youth', saveGameId] })
    queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
  }

  const promote = useMutation({
    mutationFn: (prospectId: string) => promoteProspect(saveGameId, prospectId, token ?? ''),
    onSuccess: invalidate,
  })
  const release = useMutation({
    mutationFn: (prospectId: string) => releaseProspect(saveGameId, prospectId, token ?? ''),
    onSuccess: invalidate,
  })
  const busy = promote.isPending || release.isPending

  const onPromote = (p: YouthProspectResponse) => {
    if (window.confirm(`Promover ${p.name} ao elenco principal? Ele assinará um contrato de 3 anos.`)) {
      promote.mutate(p.id)
    }
  }
  const onRelease = (p: YouthProspectResponse) => {
    if (window.confirm(`Liberar ${p.name} da base? Esta ação não pode ser desfeita.`)) {
      release.mutate(p.id)
    }
  }

  if (isLoading) return <Typography sx={{ p: 3 }}>Carregando a base…</Typography>
  if (error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar a base.</Typography>

  const prospects = data ?? []

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Base</Typography>

      {(promote.isError || release.isError) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {(promote.error as Error)?.message ?? (release.error as Error)?.message ?? 'Ação falhou.'}
        </Alert>
      )}

      {prospects.length === 0 ? (
        <Alert severity="info">Sua base ainda não tem jovens — eles chegam a cada temporada.</Alert>
      ) : isMobile ? (
        <Stack spacing={1.5}>
          {prospects.map((p) => (
            <Card key={p.id} variant="outlined">
              <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 600 }}>{p.name}</Typography>
                  </Box>
                  <Chip size="small" label={POSITION_LABEL[p.position]} />
                  <Chip size="small" color="primary" label={`OVR ${p.overall}`} />
                </Stack>
                <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary">{p.age} anos</Typography>
                    <PotentialStars stars={p.potentialStars} />
                  </Stack>
                  <Stack direction="row" spacing={1}>
                    <Button size="small" variant="contained" disabled={busy} onClick={() => onPromote(p)}>
                      Promover
                    </Button>
                    <Button size="small" variant="outlined" color="error" disabled={busy} onClick={() => onRelease(p)}>
                      Liberar
                    </Button>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Nome</TableCell>
                <TableCell align="center">Pos</TableCell>
                <TableCell align="center">Idade</TableCell>
                <TableCell align="center">OVR</TableCell>
                <TableCell align="center">Potencial</TableCell>
                <TableCell align="right">Ações</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {prospects.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>{p.name}</TableCell>
                  <TableCell align="center">{POSITION_LABEL[p.position]}</TableCell>
                  <TableCell align="center">{p.age}</TableCell>
                  <TableCell align="center">{p.overall}</TableCell>
                  <TableCell align="center"><PotentialStars stars={p.potentialStars} /></TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                      <Button size="small" variant="contained" disabled={busy} onClick={() => onPromote(p)}>
                        Promover
                      </Button>
                      <Button size="small" variant="outlined" color="error" disabled={busy} onClick={() => onRelease(p)}>
                        Liberar
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}
```

- [ ] **Step 4: Run the screen test to verify it PASSES**

```bash
cd frontend && npx vitest run src/features/base/BasePage.test.tsx
```
Expected: 4 passing.

- [ ] **Step 5: Wire the `/base` route**

In `frontend/src/router.tsx`, add the import after the `SquadPage` import (line 5):
```typescript
import { BasePage } from './features/base/BasePage'
```
And add the route block after the `/squad` route (after line 44, before the `/table` route):
```typescript
      <Route
        path="/base"
        element={
          <ProtectedRoute>
            <BasePage />
          </ProtectedRoute>
        }
      />
```

- [ ] **Step 6: Add the "Base" nav entry**

In `frontend/src/components/Layout.tsx`, inside the `saveLinks` array (after the `/squad` "Escalação" entry, line 195), add:
```typescript
        { to: `/base?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Base' },
```

- [ ] **Step 7: Full frontend type-check + run the new tests**

```bash
cd frontend && npx tsc -b && npx vitest run src/features/base/
```
Expected: `tsc -b` clean; all `features/base` tests pass.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/base/BasePage.tsx frontend/src/features/base/BasePage.test.tsx \
        frontend/src/router.tsx frontend/src/components/Layout.tsx
git commit -m "$(cat <<'EOF'
feat(youth): Base screen (list prospects, promote/release) + route + nav

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Frontend — surface youth intake/departures in the season summary (and drop `newcomers`)

**Why:** The rollover summary should report the academy's new arrivals and age-20 leavers, matching the backend `RolloverResponse` (which already returns `youthIntake`/`youthDepartures` and, after Task 2, no longer returns `newcomers`). This task updates the frontend type, the render, and the test together so `tsc -b` stays green in the same commit.

**Files:**
- Modify: `frontend/src/api/types.ts` (`RolloverResponse`)
- Modify: `frontend/src/features/season/SeasonSummaryPage.tsx`
- Modify: `frontend/src/features/season/SeasonSummaryPage.test.tsx`

**Interfaces:**
- Consumes: existing `SquadChange` type and `SquadChangeList` component.
- Produces: `RolloverResponse` gains `youthIntake: SquadChange[]` / `youthDepartures: SquadChange[]`, loses `newcomers`.

- [ ] **Step 1: Update the `RolloverResponse` type**

In `frontend/src/api/types.ts`, replace the `RolloverResponse` type (currently lines ~226-232):
```typescript
export type RolloverResponse = {
  seasonYear: number
  currentRound: number
  retirements: SquadChange[]
  newcomers: SquadChange[]
  expiredContracts: SquadChange[]
}
```
with:
```typescript
export type RolloverResponse = {
  seasonYear: number
  currentRound: number
  retirements: SquadChange[]
  expiredContracts: SquadChange[]
  youthIntake: SquadChange[]
  youthDepartures: SquadChange[]
}
```

- [ ] **Step 2: Update the season-summary test mocks + assertions**

In `frontend/src/features/season/SeasonSummaryPage.test.tsx`, in the `'rolls over and shows squad changes'` test, replace the mock (lines 51-57):
```typescript
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [{ playerName: 'Veterano', position: 'DEF', age: 38 }],
      newcomers: [{ playerName: 'Jovem Silva', position: 'DEF', age: 17 }],
      expiredContracts: [],
    })
```
with:
```typescript
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [{ playerName: 'Veterano', position: 'DEF', age: 38 }],
      expiredContracts: [],
      youthIntake: [{ playerName: 'Jovem Silva', position: 'DEF', age: 17 }],
      youthDepartures: [],
    })
```
and add an assertion after the existing `Jovem Silva` check (after line 65):
```typescript
    expect(screen.getByText(/Reforços da Base/i)).toBeInTheDocument()
```

In the `'lists players who lapsed into free agency'` test, replace the mock (lines 70-76):
```typescript
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [],
      newcomers: [],
      expiredContracts: [{ playerName: 'Zé Tigre', position: 'MID', age: 31 }],
    })
```
with:
```typescript
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [],
      expiredContracts: [{ playerName: 'Zé Tigre', position: 'MID', age: 31 }],
      youthIntake: [],
      youthDepartures: [],
    })
```

- [ ] **Step 3: Run the test to verify it FAILS**

```bash
cd frontend && npx vitest run src/features/season/SeasonSummaryPage.test.tsx
```
Expected: the `'rolls over…'` test FAILS — `Reforços da Base` is not rendered yet (the page still reads `newcomers`).

- [ ] **Step 4: Update the render in `SeasonSummaryPage.tsx`**

Remove the `newcomers` block (lines 153-155):
```typescript
          {result.newcomers.length > 0 && (
            <SquadChangeList title="Novatos" changes={result.newcomers} />
          )}
```
Then, immediately after the `expiredContracts` block closes (after line 167, before the `<Box>` with the "Voltar ao painel" button), add:
```typescript
          {result.youthIntake.length > 0 && (
            <SquadChangeList title="Reforços da Base" changes={result.youthIntake} />
          )}
          {result.youthDepartures.length > 0 && (
            <SquadChangeList title="Saídas da Base" changes={result.youthDepartures} />
          )}
```

- [ ] **Step 5: Run the test + full type-check**

```bash
cd frontend && npx vitest run src/features/season/SeasonSummaryPage.test.tsx && npx tsc -b
```
Expected: both season-summary tests pass; `tsc -b` clean (no lingering `newcomers` reference).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/season/SeasonSummaryPage.tsx \
        frontend/src/features/season/SeasonSummaryPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(youth): show youth intake/departures in the season summary

Replace the dead 'Novatos' (newcomers) block with 'Reforços da Base' and
'Saídas da Base', matching the backend RolloverResponse.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: End-to-end verification

**Why:** Confirm the whole feature is green across both stacks and manually smoke the screen.

**Files:** none (verification only).

- [ ] **Step 1: Backend — run the touched suites**

```bash
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home \
  ./gradlew test --tests "br.com.carvoeiromanager.contract.*" \
                 --tests "br.com.carvoeiromanager.transfer.*" \
                 --tests "br.com.carvoeiromanager.season.*" \
                 --tests "br.com.carvoeiromanager.youth.*" \
                 --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL. (If `RolloverContractsIT` flakes, re-run once — it has a known intermittent failure unrelated to this work.)

- [ ] **Step 2: Frontend — full deploy guard + test suite**

```bash
cd frontend && npx tsc -b && npx vitest run
```
Expected: `tsc -b` clean; all tests pass.

- [ ] **Step 3: Manual smoke (optional but recommended)**

Start backend + frontend, load a career, and verify:
- The "Base" nav entry appears once a save is loaded and opens the academy screen.
- Prospects render with OVR + star potential; empty state shows the Portuguese copy when the academy is empty.
- Promover moves a prospect into the squad (it leaves the Base list, appears on the Squad screen); Liberar removes it after confirm.
- Rolling a season shows "Reforços da Base" / "Saídas da Base" when applicable, and no "Novatos" block.

- [ ] **Step 4: Report completion**

Summarize: all 6 implementation tasks committed on `main`, both suites green, screen smoked. Hand off to the user to `git push`.

---

## Self-Review (completed during authoring)

- **Spec coverage:** Base screen (Task 5), 1–5 stars (Task 4), Promote/Release with confirm (Task 5), empty state (Task 5), youth API client (Task 3), `YouthProspectResponse` type never exposing raw potential (Task 3), rollover summary youth intake/departures (Task 6), nav entry + route (Task 5). Deferred backend items: squad-cap exclusion (Task 1), dead `newcomers` removal (Task 2). ✓
- **No placeholders:** every code step contains full source; every command has an expected result. ✓
- **Type consistency:** `YouthProspectResponse` shape is identical in Task 3 (type), Task 5 (mock factory), and the backend record. `RolloverResponse` after Task 6 matches the backend record after Task 2 (`retirements, expiredContracts, youthIntake, youthDepartures`). Youth API paths in Task 3's test match the client in Task 3 and the backend `YouthController` mappings. ✓
- **`tsc -b` guard:** Task 3 (additive), 4, 5, and 6 each end with a `tsc -b` step; Task 6 changes the type and both its consumers in one commit so no intermediate frontend commit references a removed field. ✓
