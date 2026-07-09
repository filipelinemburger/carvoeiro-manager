# Batch Substitutions + Transfer Error Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One atomic POST applies all queued live-match substitutions, and transfer rejections show their specific pt-BR reason instead of a generic error.

**Architecture:** Part 1: `POST /live-match/substitutions` takes the whole sub list, validates it against the evolving pitch state before persisting anything, and appends all pairs at one minute — replacing the per-sub endpoint (the dialog is its only consumer). Part 2: the API client stops discarding the backend's `error` code (`ApiError`), and a reusable `errorMessages` module maps known `ErrorCode`s to pt-BR on the transfer surfaces.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 + AssertJ (backend); React 19 / TypeScript / Vitest + React Testing Library (frontend).

**Spec:** `docs/superpowers/specs/2026-07-09-batch-subs-and-transfer-error-messages-design.md`

## Global Constraints

- Gradle: run from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew …`, foreground only, never two gradle invocations concurrently. Frontend: `cd frontend && npx tsc --noEmit && npx vitest run`.
- Commit locally on `main` after each task. NEVER `git push`.
- Batch atomicity: any invalid pair → 422 and ZERO subs persisted (schedule and `subsRemaining` unchanged). All pairs land at the same minute `min(90, committedMinute + 1)`, in list order. The old single-sub endpoint is DELETED, not kept.
- Existing `ErrorCode`s only (`MATCH_OVER`, `NO_SUBS_REMAINING`, `INVALID_SUBSTITUTION`) — no new backend codes, no migration, wire format of `LiveMatchState` unchanged.
- pt-BR strings, verbatim (spec §Part 2 table): TRANSFER_WINDOW_CLOSED `Janela de transferências fechada.`; SQUAD_FULL `Elenco cheio — venda antes de contratar.`; INSUFFICIENT_FUNDS `Saldo insuficiente para esta proposta.`; LIVE_MATCH_IN_PROGRESS `Finalize a partida ao vivo antes de negociar.`; SQUAD_TOO_SMALL `O elenco ficaria pequeno demais.`; INVALID_TRANSFER `Proposta inválida.`; INVALID_CONTRACT `Termos de contrato inválidos.`; TRANSFER_OFFER_NOT_FOUND `Proposta não encontrada.` Unknown codes → the caller's fallback.
- Part 2 wiring is limited to the transfer surfaces (`ClubViewPage`, `TransferMarketPage`, the squad listing toggle) — no app-wide sweep.

---

### Task 1: Backend — atomic batch substitutions endpoint

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/SubstituteBatchRequest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchController.java:49-56` (mapping `/substitute` → `/substitutions`, request type)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java:141-175` (`substitute` reworked for the batch)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchSubstituteIT.java` (reworked), `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchControllerIT.java` (empty-list validation, if the file exercises request bodies — follow its conventions)

**Interfaces:**
- Consumes: existing `record SubstituteRequest(@NotNull UUID outPlayerId, @NotNull UUID inPlayerId)` (unchanged, becomes the batch item); `OnPitch`/`onPitchManaged`, `requireLive`, `configFor`, `serialize`, `MAX_SUBS = 5` — all existing private members of `LiveMatchService`.
- Produces (Task 2 relies on): `POST /api/v1/save-games/{saveGameId}/live-match/substitutions` with body `{ "subs": [ { "outPlayerId": …, "inPlayerId": … }, … ] }` → `LiveMatchState`; service signature `LiveMatchState substitute(UUID saveGameId, UUID userId, SubstituteBatchRequest req)`.

- [ ] **Step 1: Write the failing tests**

Rework `LiveMatchSubstituteIT.java` — update its two existing tests to wrap their single pair in a batch (`new SubstituteBatchRequest(List.of(new SubstituteRequest(out, in)))`), keeping their assertions identical, then add (follow the file's existing fixture helpers for creating the career/kickoff and resolving XI/bench ids — read the file first; it is ~100 lines):

```java
    @Test
    void aBatchAppliesAllSubsAtTheSameMinuteInOrder() {
        // two valid pairs in one call: subsRemaining drops by 2, both SUBSTITUTION events
        // appear at the same minute, in the order submitted.
        LiveMatchState after = liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(
                        new SubstituteRequest(out1, in1),
                        new SubstituteRequest(out2, in2))));
        assertThat(after.subsRemaining()).isEqualTo(3);
        // assert both events revealed at the same minute and ordered in1-before-in2
    }

    @Test
    void aChainBatchIsValid() {
        // A-off/B-on then B-off/C-on in ONE batch: valid because validation evolves the
        // pitch state pair-by-pair. Final state: A and B off, C on.
    }

    @Test
    void anInvalidPairRejectsTheWholeBatchAtomically() {
        // [valid pair, pair whose incoming player is already on the pitch]:
        // throws INVALID_SUBSTITUTION AND the first pair was NOT applied —
        // re-read state: subsRemaining still 5, no SUBSTITUTION events.
    }

    @Test
    void theSameIncomingPlayerTwiceIsRejected() {
        // [out1 -> inX, out2 -> inX]: INVALID_SUBSTITUTION (inX used by the earlier pair).
    }

    @Test
    void aBatchBeyondRemainingSubsIsRejected() {
        // batch of MAX_SUBS + 1 pairs (duplicate pairs are fine — the size check fires
        // before pair validation): NO_SUBS_REMAINING.
    }
```
Write the commented scenarios as real assertions using the file's helpers. For the atomicity
test assert via a fresh `getLive(...)`/state read that `subsRemaining() == 5` and no
SUBSTITUTION event exists. Exception assertions follow the file's existing
`assertThatThrownBy(...)` style, matching the `ErrorCode` (e.g.
`.isInstanceOf(ApiException.class)` + extracting `getCode()`), consistent with how other ITs
in the package assert codes.

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LiveMatchSubstituteIT"
```
Expected: compilation FAILURE — `SubstituteBatchRequest` does not exist.

- [ ] **Step 3: Implement**

3a. `SubstituteBatchRequest.java`:

```java
package br.com.carvoeiromanager.match;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** All substitutions from one dialog apply — validated and persisted atomically. */
public record SubstituteBatchRequest(@NotEmpty List<@Valid SubstituteRequest> subs) {
}
```

3b. `LiveMatchController.java` — replace the `/substitute` mapping:

```java
    @PostMapping("/substitutions")
    public ResponseEntity<LiveMatchState> substitute(
            @PathVariable UUID saveGameId,
            @Valid @RequestBody SubstituteBatchRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.substitute(saveGameId, userId, request));
    }
```

3c. `LiveMatchService.substitute` — full replacement (imports: `java.util.Set`, `java.util.LinkedHashSet`, `java.util.stream.Collectors` as needed):

```java
    @Transactional
    public LiveMatchState substitute(UUID saveGameId, UUID userId, SubstituteBatchRequest req) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            throw new ApiException(ErrorCode.MATCH_OVER, "The match has ended");
        }

        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        List<Player> managedXi = lm.isManagedIsHome() ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, lm.getCommittedMinute(), managedXi);

        int used = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();
        if (used + req.subs().size() > MAX_SUBS) {
            throw new ApiException(ErrorCode.NO_SUBS_REMAINING, "No substitutions remaining");
        }

        // Validate the WHOLE batch against the evolving pitch state before persisting anything —
        // any failure rejects the batch atomically (zero subs applied).
        List<UUID> onPitch = new ArrayList<>(managed.onPitch());
        Set<UUID> usedIn = new LinkedHashSet<>(managed.usedIn());
        Set<UUID> benchIds = managedBench.stream().map(Player::getId).collect(Collectors.toSet());
        for (SubstituteRequest sub : req.subs()) {
            if (!onPitch.contains(sub.outPlayerId())) {
                throw new ApiException(ErrorCode.INVALID_SUBSTITUTION, "The outgoing player is not on the pitch");
            }
            if (!benchIds.contains(sub.inPlayerId()) || usedIn.contains(sub.inPlayerId())) {
                throw new ApiException(ErrorCode.INVALID_SUBSTITUTION,
                        "The incoming player is not an available bench player");
            }
            onPitch.remove(sub.outPlayerId());
            onPitch.add(sub.inPlayerId());
            usedIn.add(sub.inPlayerId());
        }

        int minute = Math.min(MINUTES, lm.getCommittedMinute() + 1);
        List<LiveSub> subs = new ArrayList<>(cfg.subSchedule());
        for (SubstituteRequest sub : req.subs()) {
            subs.add(new LiveSub(minute, lm.isManagedIsHome(), sub.outPlayerId(), sub.inPlayerId()));
        }
        lm.setSubSchedule(serialize(subs));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }
```

3d. Update every other caller: grep `liveMatchService.substitute(` across `src/` — any other
test/IT call site wraps its pair in `new SubstituteBatchRequest(List.of(...))` with no
behavioral change. If `LiveMatchControllerIT` posts to `/substitute`, update the path to
`/substitutions` and the body to the batch shape, and add one request with `{"subs": []}`
expecting a 4xx validation response (follow the file's existing MockMvc/assert conventions).

- [ ] **Step 4: Run the match package**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(match): atomic batch substitutions — one POST per dialog apply"
```

---

### Task 2: Frontend — single batch call from the live-match dialog

**Files:**
- Modify: `frontend/src/api/types.ts` (add `SubstituteBatchRequest`)
- Modify: `frontend/src/api/liveMatch.ts:17-21` (`substituteLiveMatch` → batch)
- Modify: `frontend/src/features/match/LiveMatchPage.tsx:69-83` (`handleApply` loop → one call)
- Test: `frontend/src/features/match/LiveMatchPage.test.tsx` (extend/adjust — read it first and follow its mock conventions)

**Interfaces:**
- Consumes (Task 1): `POST /live-match/substitutions` with `{ subs: [{ outPlayerId, inPlayerId }, …] }` → `LiveMatchState`.
- Produces: `substituteLiveMatch(saveGameId: string, req: SubstituteBatchRequest, token: string): Promise<LiveMatchState>`.

- [ ] **Step 1: Write/adjust the failing test**

In `LiveMatchPage.test.tsx` (adapt to its existing mocks for `../../api/liveMatch`): a test
that queues TWO substitutions through the actions dialog and applies them, asserting
`substituteLiveMatch` was called EXACTLY ONCE with
`{ subs: [{ outPlayerId: out1, inPlayerId: in1 }, { outPlayerId: out2, inPlayerId: in2 }] }`
(and not per-pair). If driving the dialog through the UI is impractical in the existing test
setup, assert at the `handleApply` boundary the same way existing tests exercise
apply-behavior — but the single-call + both-pairs assertion is mandatory. Update any existing
test that mocks the old single-pair `substituteLiveMatch` signature.

- [ ] **Step 2: Run to verify failure**

```bash
cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx
```
Expected: FAIL (old per-pair calls / signature mismatch).

- [ ] **Step 3: Implement**

3a. `types.ts` — next to the existing `SubstituteRequest` type:

```typescript
export type SubstituteBatchRequest = { subs: SubstituteRequest[] }
```

3b. `liveMatch.ts`:

```typescript
export function substituteLiveMatch(
  saveGameId: string, req: SubstituteBatchRequest, token: string,
): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/substitutions`, req, token)
}
```
(import type `SubstituteBatchRequest`.)

3c. `LiveMatchPage.tsx` `handleApply` — replace the `for` loop:

```typescript
      if (subs.length > 0) {
        latest = await substituteLiveMatch(
          saveGameId,
          { subs: subs.map(pair => ({ outPlayerId: pair.out.playerId, inPlayerId: pair.in.playerId })) },
          token ?? '',
        )
      }
```
(the tactics call and everything after it stay unchanged; update the comment above
`handleApply` to say the subs go in one batched request followed by the optional tactics
call.)

- [ ] **Step 4: Verify**

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck; all tests green.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(match): live-match dialog submits substitutions as one batch request"
```

---

### Task 3: Frontend — ApiError + pt-BR transfer error messages

**Files:**
- Modify: `frontend/src/api/client.ts` (`ApiError`, `handle`)
- Create: `frontend/src/api/errorMessages.ts`
- Modify: `frontend/src/features/club/ClubViewPage.tsx:131` (offer alert)
- Modify: `frontend/src/features/transfer/TransferMarketPage.tsx` (~line 312 offer alert; add an offers-tab action alert for accept/withdraw/respond)
- Modify: `frontend/src/features/squad/SquadPage.tsx` (~lines 225-232: list/unlist mutations gain an error display)
- Test: create `frontend/src/api/errorMessages.test.ts`; extend `ClubViewPage.test.tsx` and `TransferMarketPage.test.tsx`

**Interfaces:**
- Consumes: backend error body shape `{ "error": "TRANSFER_WINDOW_CLOSED", "message": "...", "status": 422 }` (already produced by `GlobalExceptionHandler`).
- Produces: `class ApiError extends Error { readonly code: string | null; readonly status: number }` exported from `client.ts`; `errorMessage(err: unknown, fallback: string): string` exported from `errorMessages.ts`.

- [ ] **Step 1: Write the failing tests**

1. `frontend/src/api/errorMessages.test.ts`:

```typescript
import { describe, expect, it } from 'vitest'
import { ApiError } from './client'
import { errorMessage } from './errorMessages'

describe('errorMessage', () => {
  it('maps every known transfer error code to its pt-BR text', () => {
    const cases: Array<[string, string]> = [
      ['TRANSFER_WINDOW_CLOSED', 'Janela de transferências fechada.'],
      ['SQUAD_FULL', 'Elenco cheio — venda antes de contratar.'],
      ['INSUFFICIENT_FUNDS', 'Saldo insuficiente para esta proposta.'],
      ['LIVE_MATCH_IN_PROGRESS', 'Finalize a partida ao vivo antes de negociar.'],
      ['SQUAD_TOO_SMALL', 'O elenco ficaria pequeno demais.'],
      ['INVALID_TRANSFER', 'Proposta inválida.'],
      ['INVALID_CONTRACT', 'Termos de contrato inválidos.'],
      ['TRANSFER_OFFER_NOT_FOUND', 'Proposta não encontrada.'],
    ]
    for (const [code, text] of cases) {
      expect(errorMessage(new ApiError('x', code, 422), 'fallback')).toBe(text)
    }
  })

  it('falls back for unknown codes, plain Errors and non-errors', () => {
    expect(errorMessage(new ApiError('x', 'SOMETHING_ELSE', 500), 'fb')).toBe('fb')
    expect(errorMessage(new ApiError('x', null, 500), 'fb')).toBe('fb')
    expect(errorMessage(new Error('x'), 'fb')).toBe('fb')
    expect(errorMessage(undefined, 'fb')).toBe('fb')
  })
})
```

2. `ClubViewPage.test.tsx` — add a test (follow the file's existing mock setup for
`../../api/career`): `makeOffer` mock rejects with
`new ApiError('The transfer window is closed', 'TRANSFER_WINDOW_CLOSED', 422)`; submit an
offer through the dialog; assert the page shows `Janela de transferências fechada.` and NOT
`Erro ao enviar proposta.`.

3. `TransferMarketPage.test.tsx` — same pattern for the market offer flow: mocked rejection
with code `INSUFFICIENT_FUNDS` renders `Saldo insuficiente para esta proposta.`.

- [ ] **Step 2: Run to verify failure**

```bash
cd frontend && npx vitest run src/api/errorMessages.test.ts src/features/club/ClubViewPage.test.tsx src/features/transfer/TransferMarketPage.test.tsx
```
Expected: FAIL (`ApiError`/`errorMessages` don't exist; pages show generic text).

- [ ] **Step 3: Implement**

3a. `client.ts` — add the class and rework `handle`:

```typescript
export class ApiError extends Error {
  readonly code: string | null
  readonly status: number

  constructor(message: string, code: string | null, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string; message?: string }
    throw new ApiError(body.message ?? res.statusText ?? 'Request failed', body.error ?? null, res.status)
  }
  return res.status === 204 ? (undefined as T) : (res.json() as Promise<T>)
}
```

3b. `errorMessages.ts`:

```typescript
import { ApiError } from './client'

/** pt-BR texts for backend ErrorCodes surfaced in the transfer flows. */
const MESSAGES: Record<string, string> = {
  TRANSFER_WINDOW_CLOSED: 'Janela de transferências fechada.',
  SQUAD_FULL: 'Elenco cheio — venda antes de contratar.',
  INSUFFICIENT_FUNDS: 'Saldo insuficiente para esta proposta.',
  LIVE_MATCH_IN_PROGRESS: 'Finalize a partida ao vivo antes de negociar.',
  SQUAD_TOO_SMALL: 'O elenco ficaria pequeno demais.',
  INVALID_TRANSFER: 'Proposta inválida.',
  INVALID_CONTRACT: 'Termos de contrato inválidos.',
  TRANSFER_OFFER_NOT_FOUND: 'Proposta não encontrada.',
}

/** The specific pt-BR reason for a known ApiError code, else the caller's fallback. */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code !== null && err.code in MESSAGES) {
    return MESSAGES[err.code]
  }
  return fallback
}
```

3c. Wiring:
- `ClubViewPage.tsx:131`:

```tsx
      {offerMut.isError && <Alert severity="error" sx={{ mt: 2 }}>{errorMessage(offerMut.error, 'Erro ao enviar proposta.')}</Alert>}
```
- `TransferMarketPage.tsx` (~312):

```tsx
      {offerMut.isError && <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(offerMut.error, 'Erro ao enviar proposta.')}</Alert>}
```
and, in the offers tab content, one action alert (place it where the tab's content starts,
matching the page's existing Alert styling):

```tsx
      {(acceptMut.isError || withdrawMut.isError || respondMut.isError) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {errorMessage(acceptMut.error ?? withdrawMut.error ?? respondMut.error, 'Erro ao processar a proposta.')}
        </Alert>
      )}
```
- `SquadPage.tsx`: the `listPlayer`/`unlistPlayer` mutations (~lines 225-232) currently
  surface no error. Add an error display for them using the page's existing feedback pattern
  (it uses a `Snackbar`+`Alert` for lineup saves — either extend that or add a sibling), with
  text `errorMessage(<listing mutation>.error, 'Erro ao atualizar a lista de transferências.')`.

- [ ] **Step 4: Verify**

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```
Expected: clean typecheck; all tests green (including the untouched suites — `ApiError` is an
`Error` subclass so existing `err instanceof Error` handling keeps working).

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(transfer): surface specific pt-BR rejection reasons — ApiError + errorMessages"
```

---

## Verification (end-to-end sanity, after all tasks)

In a live match, queue 2–3 substitutions and apply: the network tab shows ONE
`POST …/live-match/substitutions` (plus the tactics POST only if tactics changed), and the
subs all appear in the feed. On the transfer side, make an offer outside the window (or with
insufficient funds): the alert now names the reason — e.g. "Janela de transferências
fechada." — instead of "Erro ao enviar proposta.".
