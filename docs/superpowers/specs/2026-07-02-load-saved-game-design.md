# Load Saved Game ("Carregar jogo salvo")

## Context

Carvoeiro Manager is a single-player career game. Each career is an isolated `SaveGame`
owned by a user. Today a user **cannot resume an existing career after logging in from a
fresh browser** because:

- The current career's id is stored **only in `localStorage`** (`carvoeiro_manager_save_game_id_<userId>`).
  When that entry is absent (new browser, cleared storage), the frontend has no way to find
  the career.
- The backend exposes only `POST /api/v1/save-games` (always creates a **new** save) and
  `GET /api/v1/save-games/{id}` (needs the id). There is **no "list my careers" endpoint**.
- Because "Nova Carreira" always creates a new save, repeated use silently **orphans** older
  careers — they persist in the database but are unreachable.

The careers themselves are never lost (only the pointer to them is). This feature makes them
reachable again.

## Goal

Let a logged-in user see all of their careers and resume any one of them, and delete careers
they no longer want — via a new **"Carregar jogo salvo"** option in the ⚙️ settings menu.

## Decisions (locked with the user)

- **Multiple careers per account** — the list shows all of the user's careers; they pick one to
  resume. (Matches the existing data model; rescues orphaned careers.)
- **Per-career delete** is included (users will now see old/orphaned careers and want to tidy up).
- **UI: a modal dialog** opened from the ⚙️ menu (consistent with "Nova Carreira" / "Excluir conta"),
  not a dedicated page.
- **Out of scope (YAGNI):** renaming careers, auto-resume on login, pagination.

## Backend

### Endpoints (feature package: `career`)

- `GET /api/v1/save-games` — returns the current user's careers, **newest first**, as a list of
  `SaveGameSummaryResponse`.
- `DELETE /api/v1/save-games/{id}` — deletes one career. `requireOwned` first (404
  `SAVE_GAME_NOT_FOUND` if missing or not owned), then cascade-delete; returns **204 No Content**.

### DTO

`SaveGameSummaryResponse` (record) — lightweight, cheap to build, no heavy per-save assembly:

```
UUID id
String managedClubName      // may be null if a save has no managed club; render a fallback
int seasonYear
int currentRound
String currentDate          // ISO date, nullable
String status               // IN_PROGRESS | FINISHED
Instant createdAt
```

### Service / Repository

- `SaveGameRepository.findByUserIdOrderByCreatedAtDesc(UUID userId) -> List<SaveGame>`.
- `SaveGameRepository` native cascade delete by id (mirrors the existing `deleteByUserId`
  native query so the `ON DELETE CASCADE` FKs clear all child rows): `DELETE FROM save_games WHERE id = :id`.
- `SaveGameService.listCareers(UUID userId) -> List<SaveGameSummaryResponse>` — maps each
  `SaveGame` to a summary, resolving `managedClubName` from the club (guard null `managedClubId`).
- `SaveGameService.deleteCareer(UUID saveGameId, UUID userId)` — `requireOwned(saveGameId, userId)`
  then the native delete.

## Frontend

### API client (`api/career.ts`) + types (`api/types.ts`)

- `listCareers(token) -> Promise<SaveGameSummary[]>` → `GET /save-games`.
- `deleteCareer(id, token) -> Promise<void>` → `DELETE /save-games/{id}`.
- `SaveGameSummary` type mirroring the backend DTO
  (`id, managedClubName, seasonYear, currentRound, currentDate, status, createdAt`).

### `LoadCareerDialog` (new — `features/career/LoadCareerDialog.tsx`)

- MUI `Dialog` listing careers. Each row: **Clube · Temporada · Rodada · criado em <data>**, a
  **Carregar** button, and a delete `IconButton`.
- The career whose id equals the current `localStorage` value is marked **"atual"**.
- **Carregar**: write id to `carvoeiro_manager_save_game_id_<userId>`, close the dialog,
  `navigate('/dashboard')`, and invalidate the `saveGame` query so the dashboard reloads.
- **Excluir**: confirm, call `deleteCareer`, refetch the list; if the deleted id was the active
  one, remove the `localStorage` entry.
- **Empty state**: "Nenhuma carreira salva."
- Loading/error states consistent with the app's existing dialogs (pt-BR copy).

### `Layout.tsx` ⚙️ menu

- Add a **"Carregar jogo salvo"** `MenuItem` between "Nova Carreira" and "Sair" that opens the
  dialog (dialog state lives in `Layout` or the dialog component).

### Shared helper

- Reuse the existing per-user key helper `saveGameKeyFor(userId)` (currently duplicated in
  `DashboardPage` and `Layout`); the dialog uses the same key. Optionally extract it to a small
  shared module so all three sites agree — a light, in-scope cleanup.

## Edge cases

- **No careers** → dialog shows the empty state; no crash.
- **Deleting the active career** → clear `localStorage`; dashboard falls back to the "create a
  career" prompt.
- **Loading** always rewrites the per-user key, so switching accounts cannot cross career ids.
- **Foreign/missing id on delete or load** → backend 404 (`SAVE_GAME_NOT_FOUND`); the dialog
  surfaces an error and refetches.

## Testing

- **Backend (JUnit + Testcontainers, Given/When/Then):**
  - `GET /save-games` returns only the caller's careers, ordered newest-first; a second user's
    careers are excluded.
  - `DELETE /save-games/{id}` removes the career and its child rows (cascade), returns 204;
    deleting a foreign/nonexistent id returns 404.
- **Frontend (Vitest + RTL):** `LoadCareerDialog` renders the list; **Carregar** sets the correct
  per-user localStorage key and navigates to `/dashboard`; **Excluir** confirms, calls the API,
  and clears the active-career key when the active one is deleted; empty state renders.

## Critical files

- `backend/.../career/SaveGameController.java` (add GET list + DELETE)
- `backend/.../career/SaveGameService.java` (listCareers, deleteCareer)
- `backend/.../career/SaveGameRepository.java` (findByUserIdOrderByCreatedAtDesc, native delete-by-id)
- `backend/.../career/SaveGameSummaryResponse.java` (new)
- `frontend/src/api/career.ts`, `frontend/src/api/types.ts`
- `frontend/src/features/career/LoadCareerDialog.tsx` (new) + test
- `frontend/src/components/Layout.tsx` (menu item + dialog wiring)

## Verification

- `./gradlew test` green (new list/delete ITs); `npm run test` green (dialog test).
- Manual: log in on a fresh browser (empty localStorage) → ⚙️ → "Carregar jogo salvo" → the
  existing career appears → **Carregar** resumes it on the dashboard; **Excluir** removes a career.

---

## Related adjustment: username format validation (registration)

Separate small change bundled with this work at the user's request.

**Rule:** a username may contain **only letters and digits** (`A–Z a–z 0–9`) — no spaces and no
special characters (underscore/dot/hyphen excluded). Existing `@NotBlank @Size(max = 50)`
constraints stay.

- **Backend:** add `@Pattern(regexp = "^[A-Za-z0-9]+$", message = "Username must contain only
  letters and numbers (no spaces or special characters)")` to `RegisterRequest.username`. The
  controller already validates with `@Valid`, so invalid input returns a 400 through the existing
  error handler — no service change needed.
- **Frontend (`RegisterPage`):** validate `^[A-Za-z0-9]+$` before submit; on failure show a pt-BR
  error (e.g. "O nome de usuário deve conter apenas letras e números, sem espaços ou caracteres
  especiais.") and do not call `register`.

**Testing:** backend — a register request with a spaced/special-char username returns 400 and does
not create a user; a valid alphanumeric username still succeeds. Frontend — `RegisterPage` blocks
submit and shows the error for an invalid username.
