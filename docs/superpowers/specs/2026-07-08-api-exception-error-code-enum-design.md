# ApiException Error Code Enum — Design

**Date:** 2026-07-08
**Status:** Approved

## Goal

Replace `ApiException`'s raw `String code` with a Java enum (`ErrorCode`) that also owns
the HTTP status for each code, so a code can never be thrown with an inconsistent status
and every valid code is a compiler-checked constant instead of a free-floating string
literal.

## Background (from the codebase)

- `ApiException` (`backend/src/main/java/br/com/carvoeiromanager/common/ApiException.java`)
  is a 17-line `RuntimeException` with a single 3-arg constructor
  `(String code, String message, HttpStatus status)`. There is no static factory — every
  call site does `new ApiException("SOME_CODE", "message", HttpStatus.X)` directly.
- 61 construction call sites exist across 15 services (`ContractService`,
  `SaveGameService`, `AuthService`, `LiveMatchService`, `LineupService`,
  `YouthAcademyService`, `TransferService`, `CurrentUserArgumentResolver`, `JwtService`,
  `SeasonRolloverService`, `FinanceService`, `SeasonAdvanceService`,
  `SeasonHistoryService`, `TopScorersService`, `StandingsService`), using 35 distinct
  code string literals.
- **Every code maps to exactly one `HttpStatus` across all its call sites** — e.g.
  `PLAYER_NOT_FOUND` is always `NOT_FOUND`, `INVALID_TACTICS` is always
  `UNPROCESSABLE_CONTENT`, `MATCH_OVER` is always `CONFLICT`. No code is ever thrown with
  two different statuses today — confirmed across all 61 sites.
- `GlobalExceptionHandler` (`@RestControllerAdvice`) catches `ApiException` and builds
  `ApiError(ex.getCode(), ex.getMessage(), ex.getStatus().value())`. Two other handlers
  build `ApiError` directly with hardcoded string codes not backed by `ApiException`:
  `"VALIDATION_ERROR"` (400, for `MethodArgumentNotValidException`) and
  `"MALFORMED_REQUEST"` (400, for `HttpMessageNotReadableException`).
- `ApiError` (`common/ApiError.java`) is `record ApiError(String error, String message, int status)`
  — the wire format is `{"error": "PLAYER_NOT_FOUND", "message": "...", "status": 404}`.
- The frontend (`frontend/src/api/client.ts`) never reads or branches on the `error`
  field — it discards the whole body except `message`. No frontend changes are needed.
- 31 backend tests assert on the exact code string via `.isEqualTo("SOME_CODE")` against
  `((ApiException) ex).getCode()`.
- Existing enum precedent: `player/InjurySeverity.java` and `player/Mentality.java` both
  use the "enum constant + constructor-supplied metadata + accessor" shape this design
  follows.

## Decisions (user-approved)

1. **`ErrorCode` owns the `HttpStatus`.** Each of the 37 constants declares its status
   once, in the enum. `ApiException` shrinks to a 2-arg constructor
   `(ErrorCode code, String message)`; `getStatus()` delegates to `code.status()`. This
   was chosen over a plain identifier-only enum (which would keep the status duplicated
   at all 61 call sites) specifically because every code's status is already 1:1 in the
   current code — the enum makes that invariant impossible to violate going forward.
2. **All 35 `ApiException` codes plus the 2 handler-only codes** (`VALIDATION_ERROR`,
   `MALFORMED_REQUEST`) become `ErrorCode` constants — 37 total — so the entire
   `ApiError.error` value space is covered by the enum, not just the `ApiException` path.
3. **Wire format is unchanged.** `ApiError.error` stays a `String`; `GlobalExceptionHandler`
   passes `ex.getCode().name()`. No frontend change, no API version bump.

## `ErrorCode` — full constant list with status

```java
package br.com.carvoeiromanager.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    CLUB_NOT_FOUND(HttpStatus.NOT_FOUND),
    COMPETITION_NOT_FOUND(HttpStatus.NOT_FOUND),
    INJURED_PLAYER(HttpStatus.UNPROCESSABLE_CONTENT),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_CONTRACT(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_LINEUP(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_ROLE(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_SUBSTITUTION(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_TACTICS(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED),
    INVALID_TRANSFER(HttpStatus.UNPROCESSABLE_CONTENT),
    LINEUP_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    LIVE_MATCH_IN_PROGRESS(HttpStatus.CONFLICT),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    MANAGED_CLUB_NOT_PLAYING(HttpStatus.CONFLICT),
    MATCH_NOT_FINISHED(HttpStatus.CONFLICT),
    MATCH_OVER(HttpStatus.CONFLICT),
    NO_GK_FOUND(HttpStatus.UNPROCESSABLE_CONTENT),
    NO_LIVE_MATCH(HttpStatus.CONFLICT),
    NO_MATCH_TO_PLAY(HttpStatus.CONFLICT),
    NO_SUBS_REMAINING(HttpStatus.CONFLICT),
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PLAYER_REJECTS_TERMS(HttpStatus.UNPROCESSABLE_CONTENT),
    PROSPECT_NOT_FOUND(HttpStatus.NOT_FOUND),
    SAVE_GAME_NOT_FOUND(HttpStatus.NOT_FOUND),
    SEASON_FINISHED(HttpStatus.CONFLICT),
    SEASON_NOT_FINISHED(HttpStatus.UNPROCESSABLE_CONTENT),
    SEASON_NOT_FOUND(HttpStatus.NOT_FOUND),
    SQUAD_FULL(HttpStatus.UNPROCESSABLE_CONTENT),
    SQUAD_TOO_SMALL(HttpStatus.UNPROCESSABLE_CONTENT),
    SUSPENDED_PLAYER(HttpStatus.UNPROCESSABLE_CONTENT),
    TRANSFER_OFFER_NOT_FOUND(HttpStatus.NOT_FOUND),
    TRANSFER_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_CONTENT),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    USERNAME_TAKEN(HttpStatus.CONFLICT),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
```

## `ApiException` — new shape

```java
package br.com.carvoeiromanager.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() { return code; }
    public HttpStatus getStatus() { return code.status(); }
}
```

`getStatus()` keeps its exact method signature/return type (`HttpStatus`) — callers of
`ex.getStatus()` (including `GlobalExceptionHandler`) need no changes.

## `GlobalExceptionHandler` changes

```java
@ExceptionHandler(ApiException.class)
public ResponseEntity<ApiError> handleApi(ApiException ex) {
    ApiError body = new ApiError(ex.getCode().name(), ex.getMessage(), ex.getStatus().value());
    return ResponseEntity.status(ex.getStatus()).body(body);
}
```

The two framework-exception handlers switch their string literals to
`ErrorCode.VALIDATION_ERROR.name()` and `ErrorCode.MALFORMED_REQUEST.name()` — same
`HttpStatus.BAD_REQUEST.value()` as today, now sourced from the enum instead of a
hand-typed duplicate.

## Call-site migration

All 61 sites change from `new ApiException("CODE", "message", HttpStatus.X)` to
`new ApiException(ErrorCode.CODE, "message")` — mechanical, one-line-per-site, dropping
the third argument. `TransferService`'s `invalid(String message)` helper keeps its
caller-supplied message, only its internal `new ApiException(...)` call changes shape.

## Test migration

All 31 assertions of the shape `.isEqualTo("SOME_CODE")` against a code obtained from
`getCode()` change to `.isEqualTo(ErrorCode.SOME_CODE)`. No test needs new coverage —
this is a mechanical type change, not new behavior. `ErrorCode` itself gets no dedicated
unit test beyond what's implicit in every existing `ApiException`-throwing test already
asserting on `getStatus()`/`getCode()` pairs.

## Out of scope

- No change to the JSON wire format, `ApiError`'s field names/types, or any frontend
  code — the frontend never reads the code field today (confirmed by full-repo grep).
- No static factory methods on `ApiException` (e.g. `ApiException.notFound(...)`) — YAGNI;
  the 2-arg constructor is already a one-line call at every site.
- No database/migration changes — `ErrorCode` values never touch persistence.
- No renaming of any existing code string — this is a type change, not a taxonomy change.

## Testing

- Full backend suite green after the mechanical migration (`./gradlew test`).
- Spot check: `GlobalExceptionHandler`'s three `@ExceptionHandler` methods each still
  produce the exact same JSON shape/status they do today for a representative case (an
  `ApiException`-backed 404, an `ApiException`-backed 422/409, a validation 400, a
  malformed-body 400).
