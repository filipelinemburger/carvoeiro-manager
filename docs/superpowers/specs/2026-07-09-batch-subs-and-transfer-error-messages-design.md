# Batch Substitutions + Specific Transfer Error Messages — Design

**Date:** 2026-07-09
**Status:** Approved (brainstormed with user)

## Context

Two independent papercuts observed on the deployed app:

1. **Substitutions fan out into N requests.** The live-match dialog queues up to 5
   substitutions plus an optional tactics change, but `LiveMatchPage.handleApply` submits the
   subs in a sequential loop — one `POST /live-match/substitute` per pair — then a separate
   tactics POST. Every call re-resolves all players and re-simulates the full 90 minutes
   server-side, so a 3-sub apply costs 4 round trips and 4 re-simulations where 2 would do.
2. **Transfer rejections render as a mystery error.** `POST /transfers/offers` returns 422
   with a precise `ErrorCode` (`TRANSFER_WINDOW_CLOSED`, `SQUAD_FULL`, `INSUFFICIENT_FUNDS`,
   …) and an English message, but `frontend/src/api/client.ts` discards the `error` code and
   the offer screens render a static "Erro ao enviar proposta." — a legitimate business
   rejection is indistinguishable from a crash.

**User decisions:** batch the substitutions only (tactics stays its own call — max 2 requests
per apply); error-message mapping ships as a reusable module wired into the transfer screens
now, other screens adopt it later.

## Part 1 — Batch substitutions

### Backend

- **Endpoint:** `POST /api/v1/save-games/{saveGameId}/live-match/substitutions` replaces
  `POST …/live-match/substitute` (the dialog is the old endpoint's only consumer; it is
  DELETED, not kept alongside). Response: `LiveMatchState` (same as today).
- **Request:** new `record SubstituteBatchRequest(@NotEmpty List<@Valid SubstituteRequest> subs)`;
  the existing `record SubstituteRequest(@NotNull UUID outPlayerId, @NotNull UUID inPlayerId)`
  becomes the batch item. An empty/absent list fails bean validation (existing global handling).
- **Service:** `LiveMatchService.substitute(UUID saveGameId, UUID userId, SubstituteBatchRequest req)`
  validates the WHOLE batch before persisting anything (atomic — any failure is a 422 and zero
  subs are applied):
  1. Existing guards unchanged: owned save, live match exists, `committedMinute < 90`
     (`MATCH_OVER`).
  2. `usedSubs + req.subs().size() > MAX_SUBS` (5) → `NO_SUBS_REMAINING`.
  3. Sequential validation against the EVOLVING pitch state: starting from the current
     on-pitch set and used-bench set (`onPitchManaged`, as today), for each pair in order —
     the outgoing player must be in the evolving on-pitch set (`INVALID_SUBSTITUTION`,
     "The outgoing player is not on the pitch"), the incoming player must be a bench member
     not yet used, including by earlier pairs in this batch (`INVALID_SUBSTITUTION`,
     "The incoming player is not an available bench player"); then the set is updated
     (out removed, in added). This catches out-twice, in-twice, and "sub the player who just
     came on back off" chains exactly as N sequential calls would have.
  4. All pairs append to the schedule at the SAME minute `min(90, committedMinute + 1)`, in
     list order (the engine's `applySubs` applies same-minute entries in schedule order, so
     sequential semantics are preserved). One serialize + save + `toState` at the end.
- Existing `ErrorCode`s are reused; no new codes, no migration.

### Frontend

- `api/liveMatch.ts`: `substituteLiveMatch(saveGameId, { subs: [{ outPlayerId, inPlayerId }, …] }, token)`
  — one call, new path `/live-match/substitutions`.
- `LiveMatchPage.handleApply`: replace the loop with a single call when `subs.length > 0`;
  tactics call unchanged. Error handling and post-apply resume behavior unchanged.

## Part 2 — Specific transfer error messages

### API client (`frontend/src/api/client.ts`)

- New `export class ApiError extends Error { readonly code: string | null; readonly status: number }`.
- `handle()` throws `ApiError` instead of `Error`, preserving the backend body's `error` field
  as `code` (null when the body has none) and the HTTP status. Existing `catch (err) { err
  instanceof Error }` consumers keep working (subclass).

### Shared module (`frontend/src/api/errorMessages.ts`, new)

`export function errorMessage(err: unknown, fallback: string): string` — returns the pt-BR
text for a known `ApiError.code`, else `fallback`. Mapped codes and exact strings:

| ErrorCode | pt-BR |
|---|---|
| `TRANSFER_WINDOW_CLOSED` | `Janela de transferências fechada.` |
| `SQUAD_FULL` | `Elenco cheio — venda antes de contratar.` |
| `INSUFFICIENT_FUNDS` | `Saldo insuficiente para esta proposta.` |
| `LIVE_MATCH_IN_PROGRESS` | `Finalize a partida ao vivo antes de negociar.` |
| `SQUAD_TOO_SMALL` | `O elenco ficaria pequeno demais.` |
| `INVALID_TRANSFER` | `Proposta inválida.` |
| `INVALID_CONTRACT` | `Termos de contrato inválidos.` |
| `TRANSFER_OFFER_NOT_FOUND` | `Proposta não encontrada.` |

Unknown/absent codes → the caller's fallback (each surface keeps its current generic text as
fallback).

### Wiring (transfer surfaces only, this change)

- `features/club/ClubViewPage.tsx`: offer mutation alert →
  `errorMessage(offerMut.error, 'Erro ao enviar proposta.')`.
- `features/transfer/TransferMarketPage.tsx`: every mutation error display on the page
  (make offer, accept counter, withdraw, respond to incoming) routes through `errorMessage`
  with its current generic text as fallback.
- Squad listing toggle (`PlayerDetails` `listingAction`, wired from `SquadPage`): the
  list/unlist mutation adopts `errorMessage` with fallback `Erro ao atualizar a lista de
  transferências.`; if that flow currently renders no error at all, add a small `Alert` for it.
- No backend changes in Part 2 — codes and messages already exist.

## Out of scope (explicit)

- Combining tactics + subs into one endpoint (user chose subs-only batching).
- Adopting `errorMessage` outside the transfer surfaces (module is reusable; later).
- Localizing backend `message` strings — the frontend maps codes; backend text stays English.
- Any change to offer/negotiation behavior.

## Testing

- **Backend (`LiveMatchSubstituteIT` reworked to the batch endpoint):** two subs in one call
  → both applied at the same minute in order, `subsRemaining` drops by 2; chain batch
  (A-off/B-on then B-off/C-on) valid; atomicity — batch of [valid, invalid] → 422 and NO sub
  persisted (schedule unchanged, `subsRemaining` unchanged); `usedSubs + batch > 5` →
  `NO_SUBS_REMAINING`; out-not-on-pitch / in-not-on-bench / same-in-twice →
  `INVALID_SUBSTITUTION`; empty list rejected; match-over guard; injury-pause resolved by a
  batch apply (existing scenario re-based).
- **Frontend:** `LiveMatchPage` test — applying 2 queued subs issues exactly ONE
  `substituteLiveMatch` call with both pairs (mock assertion) and closes the dialog;
  `errorMessages` unit test — each mapped code, unknown code → fallback, non-ApiError →
  fallback; `ClubViewPage`/`TransferMarketPage` tests — a mocked 422 with
  `TRANSFER_WINDOW_CLOSED` renders "Janela de transferências fechada." instead of the generic
  text.
