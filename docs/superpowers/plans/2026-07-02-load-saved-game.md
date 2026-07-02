# Load Saved Game + Username Validation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let a user list/resume/delete their careers via a "Carregar jogo salvo" ⚙️-menu dialog, and reject usernames containing spaces or special characters at registration.

**Architecture:** Two new `career` endpoints (`GET /save-games` list, `DELETE /save-games/{id}`) backed by a lightweight summary DTO + a cascade delete; a frontend `LoadCareerDialog` wired into the Layout gear menu that reads/writes the existing per-user `localStorage` save id. Username rule added as a Bean-Validation `@Pattern` on `RegisterRequest` plus a client-side check in `RegisterPage`.

**Tech Stack:** Java 25 / Spring Boot / JPA / Testcontainers; React + TS + Vite + MUI / Vitest + RTL.

## Global Constraints
- Spec: `docs/superpowers/specs/2026-07-02-load-saved-game-design.md`.
- Careers list is newest-first and scoped to the caller; ownership via `SaveGameService.requireOwned` (404 `SAVE_GAME_NOT_FOUND` for foreign/missing — never leak ids).
- Username rule: `^[A-Za-z0-9]+$` (letters+digits only; no spaces/special chars). Keep existing `@NotBlank @Size(max=50)`.
- Per-user localStorage key: `carvoeiro_manager_save_game_id_<userId>` (do not change the key format).
- pt-BR UI copy; strict TS, no `any`; DTOs are records; constructor injection.
- Backend needs JDK 25: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`.
- Commit policy: user runs commits (no auto-commit) unless they say otherwise for this run.

**Test commands:** backend `cd backend && ./gradlew test --tests "<Class>"`; frontend `cd frontend && npx vitest run <name>` + `npx tsc --noEmit`.

---

### Task 1: Backend — list + delete careers

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameSummaryResponse.java`
- Modify: `backend/.../career/SaveGameRepository.java`, `SaveGameService.java`, `SaveGameController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/career/SaveGameListDeleteIT.java` (new; mirror `SaveGameOwnershipIT`)

**Interfaces / Produces:**
- `SaveGameSummaryResponse(UUID id, String managedClubName, int seasonYear, int currentRound, String currentDate, String status, java.time.Instant createdAt)`
- `SaveGameService.listCareers(UUID userId) -> List<SaveGameSummaryResponse>`
- `SaveGameService.deleteCareer(UUID saveGameId, UUID userId) -> void`
- `GET /api/v1/save-games -> List<SaveGameSummaryResponse>`; `DELETE /api/v1/save-games/{id} -> 204`

- [ ] **Step 1 — Write the failing IT** (`SaveGameListDeleteIT`), mirroring `SaveGameOwnershipIT`'s harness (`@SpringBootTest`, `@Import(PostgresTestContainer.class)`, create a user + `saveGameService.createCareer(userId)`):

```java
// GIVEN user A with two careers and user B with one
// WHEN saveGameService.listCareers(A) → returns exactly A's two, newest createdAt first, none of B's
// WHEN saveGameService.deleteCareer(A.career1, A) → career1 no longer in listCareers(A);
//      players/competitions for that save are gone (cascade) — assert playerRepository.findBySaveGameId(id) isEmpty
// WHEN saveGameService.deleteCareer(A.career1, B) → throws ApiException SAVE_GAME_NOT_FOUND (404)
```

- [ ] **Step 2 — Run it (RED).** `... --tests "br.com.carvoeiromanager.career.SaveGameListDeleteIT"` → fails to compile (methods absent).

- [ ] **Step 3 — `SaveGameSummaryResponse`:**

```java
package br.com.carvoeiromanager.career;

import java.time.Instant;
import java.util.UUID;

public record SaveGameSummaryResponse(
        UUID id,
        String managedClubName,
        int seasonYear,
        int currentRound,
        String currentDate,
        String status,
        Instant createdAt
) {}
```

- [ ] **Step 4 — Repository** (add to `SaveGameRepository`):

```java
import java.util.List;
// ...
List<SaveGame> findByUserIdOrderByCreatedAtDesc(UUID userId);

@Modifying
@Query(value = "DELETE FROM save_games WHERE id = :id", nativeQuery = true)
void deleteByIdNative(@Param("id") UUID id);
```

- [ ] **Step 5 — Service** (add to `SaveGameService`; `clubRepository` already injected):

```java
@Transactional(readOnly = true)
public List<SaveGameSummaryResponse> listCareers(UUID userId) {
    return saveGameRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(this::toSummary)
            .toList();
}

private SaveGameSummaryResponse toSummary(SaveGame s) {
    String clubName = s.getManagedClubId() == null ? null
            : clubRepository.findById(s.getManagedClubId()).map(Club::getName).orElse(null);
    String currentDate = s.getCurrentDate() != null ? s.getCurrentDate().toString() : null;
    return new SaveGameSummaryResponse(s.getId(), clubName, s.getSeasonYear(),
            s.getCurrentRound(), currentDate, s.getStatus().name(), s.getCreatedAt());
}

@Transactional
public void deleteCareer(UUID saveGameId, UUID userId) {
    requireOwned(saveGameId, userId);           // 404 if missing/foreign
    saveGameRepository.deleteByIdNative(saveGameId);
}
```

- [ ] **Step 6 — Controller** (add to `SaveGameController`, base `/api/v1/save-games`):

```java
@GetMapping
public ResponseEntity<List<SaveGameSummaryResponse>> list(@CurrentUser UUID userId) {
    return ResponseEntity.ok(saveGameService.listCareers(userId));
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser UUID userId) {
    saveGameService.deleteCareer(id, userId);
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 7 — Run IT (GREEN)**, then the career package: `... --tests "br.com.carvoeiromanager.career.*"`.
- [ ] **Step 8 — Commit** (hand to maintainer): `feat(career): list and delete saved careers`.

---

### Task 2: Backend — username format validation

**Files:** Modify `backend/.../auth/RegisterRequest.java`; Test `backend/src/test/java/br/com/carvoeiromanager/auth/RegisterValidationIT.java` (new; mirror `AuthControllerIT` — MockMvc/HTTP POST `/api/v1/auth/register`).

- [ ] **Step 1 — Failing IT:** POST register with username `"bad name!"` → 400 and no user created; username `"Gooduser1"` → 200/201 and a token returned.
- [ ] **Step 2 — Run (RED).**
- [ ] **Step 3 — Add the constraint** to `RegisterRequest` (keep the others):

```java
import jakarta.validation.constraints.Pattern;
// ...
@NotBlank
@Size(max = 50)
@Pattern(regexp = "^[A-Za-z0-9]+$",
        message = "Username must contain only letters and numbers (no spaces or special characters)")
String username,
```

- [ ] **Step 4 — Run IT (GREEN)** + `auth.*` package.
- [ ] **Step 5 — Commit:** `feat(auth): reject usernames with spaces or special characters`.

---

### Task 3: Frontend — API + LoadCareerDialog + gear-menu wiring

**Files:**
- Modify: `frontend/src/api/types.ts`, `frontend/src/api/career.ts`, `frontend/src/components/Layout.tsx`
- Create: `frontend/src/features/career/LoadCareerDialog.tsx`, `LoadCareerDialog.test.tsx`
- Create: `frontend/src/features/career/saveGameKey.ts` (extract the shared per-user key helper)

**Interfaces / Consumes:** `GET /save-games`, `DELETE /save-games/{id}` (Task 1).

- [ ] **Step 1 — Shared key helper** `saveGameKey.ts`:

```ts
export const saveGameKeyFor = (userId: string | undefined) =>
  `carvoeiro_manager_save_game_id_${userId ?? 'anon'}`
```
Then replace the duplicated local `saveGameKeyFor` in `DashboardPage.tsx` and `Layout.tsx` with an import from this module (no behavior change).

- [ ] **Step 2 — Types** (`types.ts`):

```ts
export type SaveGameSummary = {
  id: string
  managedClubName: string | null
  seasonYear: number
  currentRound: number
  currentDate: string | null
  status: 'IN_PROGRESS' | 'FINISHED'
  createdAt: string
}
```

- [ ] **Step 3 — API client** (`career.ts`; import `apiDelete` from `./client`):

```ts
export function listCareers(token: string): Promise<SaveGameSummary[]> {
  return apiGet<SaveGameSummary[]>('/save-games', token)
}
export function deleteCareer(id: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${id}`, token)
}
```

- [ ] **Step 4 — Failing RTL test** `LoadCareerDialog.test.tsx` (reuse the app's query-client + mock-fetch harness, mock `listCareers`/`deleteCareer`, `useNavigate`, a user in localStorage):
  - renders a career row (club + season + round + created), marks the active one "atual";
  - clicking **Carregar** sets `carvoeiro_manager_save_game_id_<userId>` to that id and navigates to `/dashboard`;
  - clicking delete → confirm → calls `deleteCareer` and, when the active career is deleted, removes the localStorage key;
  - empty list shows "Nenhuma carreira salva."

- [ ] **Step 5 — `LoadCareerDialog.tsx`:** MUI `Dialog` (`open`, `onClose` props). `useQuery(['careers'], () => listCareers(token))`. Each career: `Typography` line `${managedClubName ?? 'Sem clube'} · Temporada ${seasonYear} · Rodada ${currentRound} · criado em ${new Date(createdAt).toLocaleDateString('pt-BR')}`, a **Carregar** `Button`, and a delete `IconButton` (`DeleteOutline`). Carregar: `localStorage.setItem(saveGameKeyFor(user?.userId), c.id)`, `onClose()`, `navigate('/dashboard')`, `queryClient.invalidateQueries({queryKey:['saveGame']})`. Delete: `window.confirm(...)` → `deleteCareer` mutation → on success refetch `['careers']`; if `c.id === localStorage.getItem(key)` remove the key. Mark active row with a "atual" `Chip`. Empty state text. pt-BR throughout.

- [ ] **Step 6 — Wire into `Layout.tsx` gear menu:** add dialog open-state; insert between "Nova Carreira" and "Sair":

```tsx
<MenuItem onClick={() => { setLoadOpen(true); setGearAnchor(null) }}>Carregar jogo salvo</MenuItem>
```
and render `<LoadCareerDialog open={loadOpen} onClose={() => setLoadOpen(false)} />`.

- [ ] **Step 7 — Run** `npx vitest run LoadCareerDialog` (GREEN) + `npx tsc --noEmit` + full `npx vitest run` (no regressions from the key-helper extraction).
- [ ] **Step 8 — Commit:** `feat(career): Carregar jogo salvo dialog to list, resume and delete careers`.

---

### Task 4: Frontend — RegisterPage username validation

**Files:** Modify `frontend/src/auth/RegisterPage.tsx`; Test `frontend/src/auth/RegisterPage.test.tsx` (create if absent).

- [ ] **Step 1 — Failing test:** typing `"bad name!"` + a valid password and submitting shows the pt-BR error and does NOT call `register`; a valid `"Gooduser1"` submits (calls `register`).
- [ ] **Step 2 — Run (RED).**
- [ ] **Step 3 — Implement:** in `handleSubmit`, before `await register(...)`:

```ts
if (!/^[A-Za-z0-9]+$/.test(username)) {
  setError('O nome de usuário deve conter apenas letras e números, sem espaços ou caracteres especiais.')
  return
}
```

- [ ] **Step 4 — Run test (GREEN)** + `npx tsc --noEmit`.
- [ ] **Step 5 — Commit:** `feat(auth): block invalid usernames on the register form`.

---

## Self-Review
- **Spec coverage:** list endpoint (T1), delete endpoint + cascade (T1), summary DTO (T1), dialog + resume/delete + active marker + empty state (T3), shared key helper (T3), username rule backend (T2) + frontend (T4). All spec sections mapped.
- **Types:** `SaveGameSummaryResponse`/`SaveGameSummary` fields align backend↔frontend; `listCareers`/`deleteCareer` names consistent across tasks; localStorage key format unchanged.
- **No placeholders:** each step has concrete code/commands.
