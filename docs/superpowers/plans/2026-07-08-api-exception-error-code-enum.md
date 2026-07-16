# ApiException Error Code Enum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ApiException`'s raw `String code` with a compiler-checked `ErrorCode` enum that also owns each code's `HttpStatus`, eliminating 61 free-floating string literals across 15 services.

**Architecture:** A new `ErrorCode` enum (37 constants, each carrying its `HttpStatus`) replaces the `String code` field on `ApiException`. `ApiException`'s constructor drops its `HttpStatus` argument (derived from the code instead). `GlobalExceptionHandler` serializes the code via `.name()`, keeping the JSON wire format byte-identical. All 61 production call sites and 31 test assertions migrate to the enum.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5 + AssertJ.

## Global Constraints

- Every `ErrorCode` constant's `HttpStatus` below is the one already in use at every one of its call sites today (verified directly against source, not just the design doc) — do not change any status while migrating.
- `ApiException.getStatus()` keeps its exact signature (`public HttpStatus getStatus()`) — it now delegates to `code.status()` instead of storing its own field.
- `ApiError`'s record shape (`record ApiError(String error, String message, int status)`) does NOT change — the wire format stays `{"error": "SOME_CODE", "message": "...", "status": 404}`.
- No frontend changes — `frontend/src/api/client.ts` never reads the `error` field, confirmed by full-repo grep.
- No static factory methods on `ApiException` (e.g. `ApiException.notFound(...)`) — YAGNI.
- Constructor injection only; no `java.util.Date`.
- Work directly on `main`, no feature branches. Real local `git commit` is authorized for this project (per user's explicit mid-session instruction) — `git push` is never run.
- Run tests with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` from `backend/`.

**The full `ErrorCode` → `HttpStatus` mapping (37 constants), verified against every call site:**

| Constant | HttpStatus |
|---|---|
| CLUB_NOT_FOUND | NOT_FOUND |
| COMPETITION_NOT_FOUND | NOT_FOUND |
| INJURED_PLAYER | UNPROCESSABLE_CONTENT |
| INSUFFICIENT_FUNDS | UNPROCESSABLE_CONTENT |
| INVALID_CONTRACT | UNPROCESSABLE_CONTENT |
| INVALID_CREDENTIALS | UNAUTHORIZED |
| INVALID_LINEUP | UNPROCESSABLE_CONTENT |
| INVALID_ROLE | UNPROCESSABLE_CONTENT |
| INVALID_SUBSTITUTION | UNPROCESSABLE_CONTENT |
| INVALID_TACTICS | UNPROCESSABLE_CONTENT |
| INVALID_TOKEN | UNAUTHORIZED |
| INVALID_TRANSFER | UNPROCESSABLE_CONTENT |
| LINEUP_REQUIRED | UNPROCESSABLE_CONTENT |
| LIVE_MATCH_IN_PROGRESS | CONFLICT |
| MALFORMED_REQUEST | BAD_REQUEST |
| MANAGED_CLUB_NOT_PLAYING | CONFLICT |
| MATCH_NOT_FINISHED | CONFLICT |
| MATCH_OVER | CONFLICT |
| NO_GK_FOUND | UNPROCESSABLE_CONTENT |
| NO_LIVE_MATCH | CONFLICT |
| NO_MATCH_TO_PLAY | CONFLICT |
| NO_SUBS_REMAINING | CONFLICT |
| PLAYER_NOT_FOUND | NOT_FOUND |
| PLAYER_REJECTS_TERMS | UNPROCESSABLE_CONTENT |
| PROSPECT_NOT_FOUND | NOT_FOUND |
| SAVE_GAME_NOT_FOUND | NOT_FOUND |
| SEASON_FINISHED | CONFLICT |
| SEASON_NOT_FINISHED | UNPROCESSABLE_CONTENT |
| SEASON_NOT_FOUND | NOT_FOUND |
| SQUAD_FULL | UNPROCESSABLE_CONTENT |
| SQUAD_TOO_SMALL | UNPROCESSABLE_CONTENT |
| SUSPENDED_PLAYER | UNPROCESSABLE_CONTENT |
| TRANSFER_OFFER_NOT_FOUND | NOT_FOUND |
| TRANSFER_WINDOW_CLOSED | UNPROCESSABLE_CONTENT |
| UNAUTHENTICATED | UNAUTHORIZED |
| USERNAME_TAKEN | CONFLICT |
| VALIDATION_ERROR | BAD_REQUEST |

---

### Task 1: `ErrorCode` enum

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/common/ErrorCodeTest.java` (new)

**Interfaces:**
- Produces: `ErrorCode` enum with 37 constants (the Global Constraints table) and `public HttpStatus status()`. Task 2 constructs `ApiException` with these constants and reads `.status()`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/common/ErrorCodeTest.java`:

```java
package br.com.carvoeiromanager.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void everyConstantHasItsExpectedStatus() {
        assertThat(ErrorCode.CLUB_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.COMPETITION_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.INJURED_PLAYER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INSUFFICIENT_FUNDS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_CONTRACT.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_CREDENTIALS.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.INVALID_LINEUP.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_ROLE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_SUBSTITUTION.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_TACTICS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_TOKEN.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.INVALID_TRANSFER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.LINEUP_REQUIRED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.LIVE_MATCH_IN_PROGRESS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MALFORMED_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.MANAGED_CLUB_NOT_PLAYING.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MATCH_NOT_FINISHED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MATCH_OVER.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_GK_FOUND.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.NO_LIVE_MATCH.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_MATCH_TO_PLAY.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_SUBS_REMAINING.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PLAYER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.PLAYER_REJECTS_TERMS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.PROSPECT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SAVE_GAME_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SEASON_FINISHED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SEASON_NOT_FINISHED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SEASON_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SQUAD_FULL.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SQUAD_TOO_SMALL.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SUSPENDED_PLAYER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.TRANSFER_OFFER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.TRANSFER_WINDOW_CLOSED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.UNAUTHENTICATED.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.USERNAME_TAKEN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.VALIDATION_ERROR.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hasExactlyThirtySevenConstants() {
        assertThat(ErrorCode.values()).hasSize(37);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.common.ErrorCodeTest"` (from `backend/`)
Expected: FAIL — compile error, `ErrorCode` does not exist.

- [ ] **Step 3: Create the enum**

Create `backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.common.ErrorCodeTest"` (from `backend/`)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/ErrorCode.java \
        backend/src/test/java/br/com/carvoeiromanager/common/ErrorCodeTest.java
git commit -m "feat(common): add ErrorCode enum carrying each code's HttpStatus"
```

---

### Task 2: `ApiException` + `GlobalExceptionHandler` + all 61 production call sites

**This is one large mechanical task, not several.** `ApiException`'s constructor and every one of its 61 callers are coupled: dropping the `HttpStatus` argument from the constructor breaks every call site that still passes three arguments, so they must land in the same commit for the code to compile. Work through the steps in order; the codebase will not compile until Step 3 (rewriting `ApiException`) AND all of Steps 4-18 (migrating every call site) are done. `ApiError` needs no changes — do not touch it.

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/ApiException.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/auth/AuthService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/youth/YouthAcademyService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/security/CurrentUserArgumentResolver.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/common/security/JwtService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonHistoryService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/TopScorersService.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/competition/StandingsService.java`

**Interfaces:**
- Consumes: `ErrorCode` (Task 1) — every constant name used below matches Task 1's enum exactly.
- Produces: `new ApiException(ErrorCode code, String message)` — Task 3's test files call `ApiException`'s `getCode()`, which now returns `ErrorCode`.

**In every one of the 15 service files below:** delete the line `import org.springframework.http.HttpStatus;` (it becomes unused — each file's only `HttpStatus` usages were inside the `ApiException` calls being migrated) and add `import br.com.carvoeiromanager.common.ErrorCode;` immediately after the existing `import br.com.carvoeiromanager.common.ApiException;` line (keep `ApiException`'s import — the type is still used).

- [ ] **Step 1: Run baseline to confirm current call-site count**

Run: `grep -rc "new ApiException(" backend/src/main --include="*.java" | awk -F: '{sum+=$2} END {print sum}'`
Expected: `61`

- [ ] **Step 2: Run test to verify the old shape still works (sanity baseline)**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew compileJava` (from `backend/`)
Expected: `BUILD SUCCESSFUL` (confirms a clean starting point before the atomic rewrite)

- [ ] **Step 3: Rewrite `ApiException`**

Replace `backend/src/main/java/br/com/carvoeiromanager/common/ApiException.java` in full:

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

- [ ] **Step 4: Update `GlobalExceptionHandler`**

Replace `backend/src/main/java/br/com/carvoeiromanager/common/GlobalExceptionHandler.java` in full:

```java
package br.com.carvoeiromanager.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ApiError body = new ApiError(ex.getCode().name(), ex.getMessage(), ex.getStatus().value());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Bean-validation failures on request bodies/params. Produces the uniform error shape
     * with a concise, field-level message instead of Spring's default error JSON.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        ApiError body = new ApiError(ErrorCode.VALIDATION_ERROR.name(), message, HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Malformed / unreadable request body (e.g. invalid JSON). Produces the uniform error shape.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformed(HttpMessageNotReadableException ex) {
        ApiError body = new ApiError(ErrorCode.MALFORMED_REQUEST.name(), "Malformed request body",
                HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
```

- [ ] **Step 5: `CurrentUserArgumentResolver.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("UNAUTHENTICATED", "No authenticated user in context", HttpStatus.UNAUTHORIZED);
```
New:
```java
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "No authenticated user in context");
```

- [ ] **Step 6: `JwtService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("INVALID_TOKEN", "Invalid or expired token", HttpStatus.UNAUTHORIZED);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired token");
```

- [ ] **Step 7: `SeasonHistoryService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException(
                        "SEASON_NOT_FOUND", "Season not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.SEASON_NOT_FOUND, "Season not found"));
```

- [ ] **Step 8: `TopScorersService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException(
                        "COMPETITION_NOT_FOUND", "Competition not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));
```

- [ ] **Step 9: `StandingsService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException(
                        "COMPETITION_NOT_FOUND",
                        "Competition not found",
                        HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.COMPETITION_NOT_FOUND, "Competition not found"));
```

- [ ] **Step 10: `FinanceService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException("CLUB_NOT_FOUND", "Club not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found"));
```

- [ ] **Step 11: `SeasonRolloverService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("SEASON_NOT_FINISHED",
                    "The current season is not finished yet", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SEASON_NOT_FINISHED, "The current season is not finished yet");
```

- [ ] **Step 12: `SeasonAdvanceService.java` (2 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.SEASON_FINISHED, "The season has already finished");
```

Old:
```java
            throw new ApiException("LIVE_MATCH_IN_PROGRESS",
                    "Finish the live match before advancing", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.LIVE_MATCH_IN_PROGRESS, "Finish the live match before advancing");
```

- [ ] **Step 13: `AuthService.java` (2 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
        return new ApiException("USERNAME_TAKEN",
                "Username '" + username + "' is already taken", HttpStatus.CONFLICT);
```
New:
```java
        return new ApiException(ErrorCode.USERNAME_TAKEN, "Username '" + username + "' is already taken");
```

Old:
```java
                .orElseThrow(() -> new ApiException("INVALID_CREDENTIALS",
                        "Invalid username or password", HttpStatus.UNAUTHORIZED));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password"));
```

- [ ] **Step 14: `YouthAcademyService.java` (2 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException("PROSPECT_NOT_FOUND",
                        "Youth prospect not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.PROSPECT_NOT_FOUND, "Youth prospect not found"));
```

Old:
```java
            throw new ApiException("PROSPECT_NOT_FOUND", "Youth prospect not found", HttpStatus.NOT_FOUND);
```
New:
```java
            throw new ApiException(ErrorCode.PROSPECT_NOT_FOUND, "Youth prospect not found");
```

- [ ] **Step 15: `SaveGameService.java` (1 call site)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import. This file's existing `SeedClub`/`SeedLoader` imports and everything else stay untouched — this is the only line to change.

Old:
```java
        return new ApiException("SAVE_GAME_NOT_FOUND", "Save game not found", HttpStatus.NOT_FOUND);
```
New:
```java
        return new ApiException(ErrorCode.SAVE_GAME_NOT_FOUND, "Save game not found");
```

- [ ] **Step 16: `ContractService.java` (6 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Free agent not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Free agent not found"));
```

Old:
```java
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.TRANSFER_WINDOW_CLOSED, "The transfer window is closed");
```

Old:
```java
            throw new ApiException("SQUAD_FULL", "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
```

Old:
```java
            throw new ApiException("INVALID_CONTRACT",
                    "Contract length must be 1-5 years and wage must be positive",
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
```

Old:
```java
            throw new ApiException("PLAYER_REJECTS_TERMS",
                    "Player rejects these terms; demands at least " + demand,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects these terms; demands at least " + demand);
```

Old:
```java
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Player not found at your club", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found at your club"));
```

- [ ] **Step 17: `TransferService.java` (9 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("INVALID_CONTRACT",
                    "Contract length must be 1-5 years and wage must be positive",
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
```

Old:
```java
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Squad would be too small", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Squad would be too small");
```

Old:
```java
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Cannot sell your last goalkeeper", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Cannot sell your last goalkeeper");
```

Old:
```java
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.TRANSFER_WINDOW_CLOSED, "The transfer window is closed");
```

Old:
```java
            throw new ApiException("LIVE_MATCH_IN_PROGRESS",
                    "Finish the live match before completing transfers", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.LIVE_MATCH_IN_PROGRESS,
                    "Finish the live match before completing transfers");
```

Old:
```java
                .orElseThrow(() -> new ApiException("TRANSFER_OFFER_NOT_FOUND",
                        "Transfer offer not found", HttpStatus.NOT_FOUND));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_OFFER_NOT_FOUND, "Transfer offer not found"));
```

Old:
```java
            throw new ApiException("INSUFFICIENT_FUNDS",
                    "Not enough funds for this transfer", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Not enough funds for this transfer");
```

Old:
```java
            throw new ApiException("SQUAD_FULL",
                    "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
```

Old (this is the `invalid(String message)` helper — caller-supplied message, keep that parameter unchanged):
```java
    ApiException invalid(String message) {
        return new ApiException("INVALID_TRANSFER", message, HttpStatus.UNPROCESSABLE_CONTENT);
    }
```
New:
```java
    ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_TRANSFER, message);
    }
```

- [ ] **Step 18: `LiveMatchService.java` (14 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
            throw new ApiException("NO_MATCH_TO_PLAY", "The next event is not a match", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.NO_MATCH_TO_PLAY, "The next event is not a match");
```

Old:
```java
                .orElseThrow(() -> new ApiException("MANAGED_CLUB_NOT_PLAYING",
                        "The managed club does not play in the next round", HttpStatus.CONFLICT));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.MANAGED_CLUB_NOT_PLAYING,
                        "The managed club does not play in the next round"));
```

Old (appears twice, at two different call sites — apply to both):
```java
            throw new ApiException("MATCH_OVER", "The match has ended", HttpStatus.CONFLICT);
```
New (apply to both occurrences):
```java
            throw new ApiException(ErrorCode.MATCH_OVER, "The match has ended");
```

Old:
```java
            throw new ApiException("NO_SUBS_REMAINING", "No substitutions remaining", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.NO_SUBS_REMAINING, "No substitutions remaining");
```

Old:
```java
            throw new ApiException("INVALID_SUBSTITUTION",
                    "The outgoing player is not on the pitch", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_SUBSTITUTION, "The outgoing player is not on the pitch");
```

Old:
```java
            throw new ApiException("INVALID_SUBSTITUTION",
                    "The incoming player is not an available bench player", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_SUBSTITUTION,
                    "The incoming player is not an available bench player");
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown mentality: " + value, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown mentality: " + value);
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown pressing: " + value, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown pressing: " + value);
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown marking: " + value, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown marking: " + value);
```

Old:
```java
        throw new ApiException("INVALID_TACTICS", "Unknown formation: " + code, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
        throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown formation: " + code);
```

Old:
```java
            throw new ApiException("MATCH_NOT_FINISHED",
                    "The match has not reached full time", HttpStatus.CONFLICT);
```
New:
```java
            throw new ApiException(ErrorCode.MATCH_NOT_FINISHED, "The match has not reached full time");
```

Old:
```java
                .orElseThrow(() -> new ApiException("NO_LIVE_MATCH",
                        "There is no live match in progress", HttpStatus.CONFLICT));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.NO_LIVE_MATCH, "There is no live match in progress"));
```

Old:
```java
                throw new ApiException("PLAYER_NOT_FOUND", "Player " + id + " not found", HttpStatus.NOT_FOUND);
```
New:
```java
                throw new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player " + id + " not found");
```

- [ ] **Step 19: `LineupService.java` (18 call sites)**

Import change: delete `import org.springframework.http.HttpStatus;`, add `import br.com.carvoeiromanager.common.ErrorCode;` after the `ApiException` import.

Old:
```java
                                    .orElseThrow(() -> new ApiException(
                                            "PLAYER_NOT_FOUND",
                                            "Player " + playerId + " not found",
                                            HttpStatus.NOT_FOUND)))
```
New:
```java
                                    .orElseThrow(() -> new ApiException(
                                            ErrorCode.PLAYER_NOT_FOUND, "Player " + playerId + " not found")))
```

Old:
```java
                        .orElseThrow(() -> new ApiException(
                                "NO_GK_FOUND",
                                "Club " + clubId + " has no goalkeeper at all",
                                HttpStatus.UNPROCESSABLE_CONTENT)));
```
New:
```java
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.NO_GK_FOUND, "Club " + clubId + " has no goalkeeper at all")));
```

Old:
```java
    private ApiException clubNotFound() {
        return new ApiException("CLUB_NOT_FOUND", "Club not found", HttpStatus.NOT_FOUND);
    }
```
New:
```java
    private ApiException clubNotFound() {
        return new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found");
    }
```

Old:
```java
            throw new ApiException(
                    "INVALID_LINEUP",
                    "A lineup must have exactly 11 starting players, but got " +
                            (startingIds == null ? 0 : startingIds.size()),
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(
                    ErrorCode.INVALID_LINEUP,
                    "A lineup must have exactly 11 starting players, but got " +
                            (startingIds == null ? 0 : startingIds.size()));
```

Old:
```java
            throw new ApiException(
                    "INVALID_LINEUP",
                    "The following player IDs do not belong to this club: " + foreignIds,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(
                    ErrorCode.INVALID_LINEUP,
                    "The following player IDs do not belong to this club: " + foreignIds);
```

Old:
```java
            throw new ApiException(
                    "INVALID_LINEUP",
                    "A lineup must have exactly 1 goalkeeper, but found " + gkCount,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(
                    ErrorCode.INVALID_LINEUP,
                    "A lineup must have exactly 1 goalkeeper, but found " + gkCount);
```

Old:
```java
            throw new ApiException("SUSPENDED_PLAYER",
                    "These players are suspended and cannot be selected: " + suspended,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.SUSPENDED_PLAYER,
                    "These players are suspended and cannot be selected: " + suspended);
```

Old:
```java
            throw new ApiException("INJURED_PLAYER",
                    "These players are injured and cannot be selected: " + injured,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INJURED_PLAYER,
                    "These players are injured and cannot be selected: " + injured);
```

Old:
```java
            throw new ApiException("INVALID_LINEUP",
                    "The bench may hold at most 9 players, but got " + benchIds.size(),
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "The bench may hold at most 9 players, but got " + benchIds.size());
```

Old:
```java
            throw new ApiException("INVALID_LINEUP",
                    "The bench contains duplicate players", HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_LINEUP, "The bench contains duplicate players");
```

Old:
```java
            throw new ApiException("INVALID_LINEUP",
                    "These players are both starting and on the bench: " + overlap,
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "These players are both starting and on the bench: " + overlap);
```

Old:
```java
                throw new ApiException("INVALID_ROLE",
                        "Unknown role: " + starter.role(), HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
                throw new ApiException(ErrorCode.INVALID_ROLE, "Unknown role: " + starter.role());
```

Old:
```java
                throw new ApiException("INVALID_ROLE",
                        "Role " + role + " is not valid for position " + player.getPosition(),
                        HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
                throw new ApiException(ErrorCode.INVALID_ROLE,
                        "Role " + role + " is not valid for position " + player.getPosition());
```

Old:
```java
                .orElseThrow(() -> new ApiException("LINEUP_REQUIRED",
                        "Set a lineup before configuring tactics", HttpStatus.UNPROCESSABLE_CONTENT));
```
New:
```java
                .orElseThrow(() -> new ApiException(ErrorCode.LINEUP_REQUIRED,
                        "Set a lineup before configuring tactics"));
```

Old:
```java
            throw new ApiException("INVALID_TACTICS",
                    "Set-piece taker " + takerId + " is not in the starting XI",
                    HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS,
                    "Set-piece taker " + takerId + " is not in the starting XI");
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown mentality: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown mentality: " + v);
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown pressing: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown pressing: " + v);
```

Old:
```java
            throw new ApiException("INVALID_TACTICS", "Unknown marking: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
```
New:
```java
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown marking: " + v);
```

- [ ] **Step 20: Verify every call site migrated**

Run: `grep -rn "new ApiException(\"" backend/src/main --include="*.java"`
Expected: no output (zero matches — every remaining `new ApiException(` now takes `ErrorCode.X`, not a quoted string, as its first argument).

Run: `grep -rc "new ApiException(ErrorCode\." backend/src/main --include="*.java" | awk -F: '{sum+=$2} END {print sum}'`
Expected: `61`

- [ ] **Step 21: Compile and run the full suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew compileJava` (from `backend/`)
Expected: `BUILD SUCCESSFUL`

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`)
Expected: build reaches test execution (main compiles) but reports exactly 31 test FAILURES — every one of them a pre-existing test asserting `.isEqualTo("SOME_CODE")` (a `String`) against `getCode()` (now an `ErrorCode`), which now fails at assertion time, not compile time. This is the expected, documented "red" state — Task 3 turns it green. Any failure count other than 31, or any COMPILE failure, means a call site was missed or mistyped — go back and check `grep -rn "new ApiException(\"" backend/src/main` again.

This run also closes the spec's JSON-shape spot check: every existing test that asserts on an error response's `.getStatus().value()` or full body (a 404 from `PLAYER_NOT_FOUND`, a 409/422 from an `ApiException`, a 400 from a validation failure, a 400 from a malformed body) is exercised here too, and none of them are in the 31-failure list — confirming `GlobalExceptionHandler`'s wire format (`{"error", "message", "status"}`) is unchanged for all four response types, without needing a dedicated new test for behavior that didn't change.

- [ ] **Step 22: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/common/ApiException.java \
        backend/src/main/java/br/com/carvoeiromanager/common/GlobalExceptionHandler.java \
        backend/src/main/java/br/com/carvoeiromanager/contract/ContractService.java \
        backend/src/main/java/br/com/carvoeiromanager/career/SaveGameService.java \
        backend/src/main/java/br/com/carvoeiromanager/auth/AuthService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
        backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
        backend/src/main/java/br/com/carvoeiromanager/youth/YouthAcademyService.java \
        backend/src/main/java/br/com/carvoeiromanager/transfer/TransferService.java \
        backend/src/main/java/br/com/carvoeiromanager/common/security/CurrentUserArgumentResolver.java \
        backend/src/main/java/br/com/carvoeiromanager/common/security/JwtService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonRolloverService.java \
        backend/src/main/java/br/com/carvoeiromanager/finance/FinanceService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
        backend/src/main/java/br/com/carvoeiromanager/season/SeasonHistoryService.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/TopScorersService.java \
        backend/src/main/java/br/com/carvoeiromanager/competition/StandingsService.java
git commit -m "feat(common): ApiException takes ErrorCode instead of a raw string + status"
```

---

### Task 3: Migrate all 31 test assertions to `ErrorCode`

**Files:**
- Modify: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceRenewIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceBenchTest.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/YouthExclusionIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/TacticsApiIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/InjuryEngineIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchTacticsIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceSellingIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/transfer/TransferTermsIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceRefactorTest.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/finance/FinanceServiceIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonHistoryServiceIT.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java`

**Interfaces:**
- Consumes: `ErrorCode` (Task 1), `ApiException.getCode()` returning `ErrorCode` (Task 2).

**In every file below that does not already import `ApiException`/reference it fully-qualified, no new import is needed beyond `ErrorCode`** — add `import br.com.carvoeiromanager.common.ErrorCode;` to each of the 16 files listed above (alongside its existing imports; most already import `ApiException` from the same package, so add the `ErrorCode` import on the following line).

- [ ] **Step 1: Confirm the current red baseline**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`)
Expected: 31 failing tests (from Task 2's end state), 0 compile errors.

- [ ] **Step 2: `ContractServiceSignIT.java` (2 sites)**

Old (line 77):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PLAYER_REJECTS_TERMS"));
```
New:
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.PLAYER_REJECTS_TERMS));
```

Old (line 91):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED"));
```
New:
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.TRANSFER_WINDOW_CLOSED));
```

- [ ] **Step 3: `ContractServiceRenewIT.java` (2 sites)**

Old (line 77):
```java
                    assertThat(api.getCode()).isEqualTo("PLAYER_REJECTS_TERMS");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.PLAYER_REJECTS_TERMS);
```

Old (line 93):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_CONTRACT"));
```
New:
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_CONTRACT));
```

- [ ] **Step 4: `LineupServiceBenchTest.java` (1 site)**

Old (line 179):
```java
                    assertThat(apiEx.getCode()).isEqualTo("SUSPENDED_PLAYER");
```
New:
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.SUSPENDED_PLAYER);
```

- [ ] **Step 5: `YouthExclusionIT.java` (2 sites, both identical text — apply to both occurrences)**

Old (lines 91 and 129):
```java
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
```
New (apply to both occurrences):
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP);
```

- [ ] **Step 6: `LineupServiceIT.java` (5 sites)**

Old (line 135, 158, and 208 — identical text, apply to all three occurrences):
```java
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
```
New (apply to all three occurrences):
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP);
```

Old (line 233):
```java
                    assertThat(apiEx.getCode()).isEqualTo("SUSPENDED_PLAYER");
```
New:
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.SUSPENDED_PLAYER);
```

Old (line 262):
```java
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_ROLE");
```
New:
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_ROLE);
```

- [ ] **Step 7: `TacticsApiIT.java` (3 sites)**

Old (line 83 and 102 — identical text, apply to both occurrences):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TACTICS"));
```
New (apply to both occurrences):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
```

Old (line 92):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("LINEUP_REQUIRED"));
```
New:
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.LINEUP_REQUIRED));
```

- [ ] **Step 8: `InjuryEngineIT.java` (1 site)**

Old (line 175):
```java
                    assertThat(apiEx.getCode()).isEqualTo("INJURED_PLAYER");
```
New:
```java
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INJURED_PLAYER);
```

- [ ] **Step 9: `LiveMatchTacticsIT.java` (2 sites, both identical text — apply to both occurrences)**

Old (lines 126 and 136):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TACTICS"));
```
New (apply to both occurrences):
```java
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
```

- [ ] **Step 10: `TransferServiceSellingIT.java` (5 sites)**

Old (line 130 and 267 — identical text, apply to both occurrences):
```java
                    assertThat(api.getCode()).isEqualTo("SQUAD_TOO_SMALL");
```
New (apply to both occurrences):
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SQUAD_TOO_SMALL);
```

Old (line 145):
```java
                    assertThat(api.getCode()).isEqualTo("INVALID_TRANSFER");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INVALID_TRANSFER);
```

Old (line 176):
```java
                    assertThat(api.getCode()).isEqualTo("LIVE_MATCH_IN_PROGRESS");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.LIVE_MATCH_IN_PROGRESS);
```

Old (line 238):
```java
                    assertThat(api.getCode()).isEqualTo("SQUAD_TOO_SMALL");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SQUAD_TOO_SMALL);
```

- [ ] **Step 11: `TransferServiceBuyingIT.java` (2 sites)**

Old (line 117):
```java
                    assertThat(api.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
```

Old (line 135):
```java
                    assertThat(api.getCode()).isEqualTo("TRANSFER_WINDOW_CLOSED");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.TRANSFER_WINDOW_CLOSED);
```

- [ ] **Step 12: `TransferTermsIT.java` (1 site, spans two lines)**

Old (lines 97-98):
```java
                .satisfies(ex -> assertThat(((br.com.carvoeiromanager.common.ApiException) ex).getCode())
                        .isEqualTo("INVALID_CONTRACT"));
```
New:
```java
                .satisfies(ex -> assertThat(((br.com.carvoeiromanager.common.ApiException) ex).getCode())
                        .isEqualTo(br.com.carvoeiromanager.common.ErrorCode.INVALID_CONTRACT));
```

(This file references `ApiException` fully-qualified inline rather than via import — match that existing style and fully-qualify `ErrorCode` too rather than adding an import, so the file's own convention stays consistent.)

- [ ] **Step 13: `SeasonAdvanceServiceIT.java` (1 site)**

Old (line 196):
```java
                    assertThat(api.getCode()).isEqualTo("SEASON_FINISHED");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_FINISHED);
```

- [ ] **Step 14: `SeasonAdvanceServiceRefactorTest.java` (1 site)**

Old (line 84):
```java
                    assertThat(api.getCode()).isEqualTo("LIVE_MATCH_IN_PROGRESS");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.LIVE_MATCH_IN_PROGRESS);
```

- [ ] **Step 15: `FinanceServiceIT.java` (1 site)**

Old (line 235):
```java
                    assertThat(api.getCode()).isEqualTo("CLUB_NOT_FOUND");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.CLUB_NOT_FOUND);
```

- [ ] **Step 16: `SeasonHistoryServiceIT.java` (1 site)**

Old (line 103):
```java
                    assertThat(api.getCode()).isEqualTo("SEASON_NOT_FOUND");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_NOT_FOUND);
```

- [ ] **Step 17: `SeasonRolloverServiceIT.java` (1 site)**

Old (line 185):
```java
                    assertThat(api.getCode()).isEqualTo("SEASON_NOT_FINISHED");
```
New:
```java
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_NOT_FINISHED);
```

- [ ] **Step 18: Verify no old-style assertions remain**

Run: `grep -rn "getCode()).isEqualTo(\"" backend/src/test --include="*.java"`
Expected: no output (zero matches).

- [ ] **Step 19: Run the full backend suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` (from `backend/`)
Expected: `BUILD SUCCESSFUL`, all tests green, 0 failures.

- [ ] **Step 20: Commit**

```bash
git add backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceSignIT.java \
        backend/src/test/java/br/com/carvoeiromanager/contract/ContractServiceRenewIT.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceBenchTest.java \
        backend/src/test/java/br/com/carvoeiromanager/match/YouthExclusionIT.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java \
        backend/src/test/java/br/com/carvoeiromanager/match/TacticsApiIT.java \
        backend/src/test/java/br/com/carvoeiromanager/match/InjuryEngineIT.java \
        backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchTacticsIT.java \
        backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceSellingIT.java \
        backend/src/test/java/br/com/carvoeiromanager/transfer/TransferServiceBuyingIT.java \
        backend/src/test/java/br/com/carvoeiromanager/transfer/TransferTermsIT.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceIT.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceRefactorTest.java \
        backend/src/test/java/br/com/carvoeiromanager/finance/FinanceServiceIT.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonHistoryServiceIT.java \
        backend/src/test/java/br/com/carvoeiromanager/season/SeasonRolloverServiceIT.java
git commit -m "test: migrate ApiException code assertions from String to ErrorCode"
```
