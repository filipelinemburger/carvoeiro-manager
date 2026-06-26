# Elifoot Carvoeiro — Phase 0 + Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the full-stack skeleton (Phase 0) and ship the first playable loop (Phase 1): a user logs in, creates a Criciúma career, picks a starting XI, simulates a full Campeonato Catarinense season, and sees the league table and champion.

**Architecture:** Monorepo. Spring Boot REST backend (Java 25) with strict Controller→Service→Repository layering, PostgreSQL via JPA + Flyway. Immutable curated seed data (JSON) is copied into per-`SaveGame` mutable rows so each career is isolated. The match engine is a pure, deterministic (seeded-RNG) service with no I/O. React + TypeScript + Vite SPA consumes a typed API client.

**Tech Stack:** Java 25, Spring Boot 4.0.x (latest patch supporting Java 25), Gradle (Groovy DSL, toolchain `JavaLanguageVersion.of(25)`), PostgreSQL 16, Flyway, Spring Security + JWT, JUnit 5, Testcontainers, AssertJ. React 19, TypeScript (strict), Vite, React Router, TanStack Query, Vitest, React Testing Library.

## Global Constraints

- Java version: **25** (Gradle toolchain pinned to 25).
- Layering: Controller → Service → Repository. Controllers never touch repositories.
- DTOs are Java `record`s; request/response separated per endpoint (`CreateXRequest` / `XResponse`).
- IDs are `UUID`. Money is `BigDecimal`. Dates are `LocalDate` / `Instant` — never `java.util.Date`.
- Constructor injection only (no field `@Autowired`). Prefer `final` fields.
- REST: `/api/v1` prefix, plural kebab-case nouns, precise status codes, page-based pagination.
- Error responses use the shape `{ "error": "CODE", "message": "...", "status": 404 }`.
- Tests follow Given/When/Then; method names are descriptive sentences. Target ≥80% coverage.
- The match engine must be deterministic for a given seed (no `Math.random`, no clock reads).
- Frontend: functional components + hooks, `const` over `let`, strict TypeScript, async errors via try/catch.
- **Never run `git commit`/`git push` for the user automatically** — the commit steps below are for the implementing engineer to run themselves; do not auto-execute them on the user's behalf without their go-ahead.

## Package / File Layout

```
elifoot-carvoeiro/
  docker-compose.yml
  README.md
  backend/
    build.gradle  settings.gradle  gradlew  gradle/
    src/main/java/br/com/elifootcarvoeiro/
      ElifootCarvoeiroApplication.java
      common/            # error handling, config, security
      auth/              # user, register/login, jwt
      career/            # SaveGame
      club/              # Club
      player/            # Player + attributes
      competition/       # Competition, Fixture, Standing
      match/             # MatchSimulationService (pure engine), lineup
      seed/              # seed loader + DTOs
    src/main/resources/
      application.yml  application-test.yml
      db/migration/      # V1__auth.sql, V2__game.sql
      seed/              # criciuma.json, catarinense.json
    src/test/java/br/com/elifootcarvoeiro/...
  frontend/
    package.json  vite.config.ts  tsconfig.json  index.html
    src/
      main.tsx  App.tsx  router.tsx
      api/client.ts  api/types.ts
      auth/            # login, auth context
      features/career/ features/squad/ features/competition/ features/match/
      components/      # shared UI
    src/test/setup.ts
```

---

# PHASE 0 — Foundation & Scaffolding

### Task 0.1: Repo root + Postgres via docker-compose

**Files:**
- Create: `docker-compose.yml`
- Create: `README.md`
- Create: `.gitignore`

- [ ] **Step 1: Write `docker-compose.yml`**

```yaml
services:
  db:
    image: postgres:16
    container_name: elifoot-db
    environment:
      POSTGRES_DB: elifoot
      POSTGRES_USER: elifoot
      POSTGRES_PASSWORD: elifoot
    ports:
      - "5432:5432"
    volumes:
      - elifoot-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U elifoot"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  elifoot-pgdata:
```

- [ ] **Step 2: Write `.gitignore`**

```
# Java / Gradle
backend/.gradle/
backend/build/
backend/bin/
*.class
# Node
frontend/node_modules/
frontend/dist/
# IDE / OS
.idea/
*.iml
.DS_Store
.env
```

- [ ] **Step 3: Write a short `README.md`** describing the project, prerequisites (Java 25, Node 20+, Docker), and how to run (`docker compose up -d`, `./gradlew bootRun`, `npm run dev`).

- [ ] **Step 4: Verify Postgres starts**

Run: `docker compose up -d && docker compose ps`
Expected: `elifoot-db` healthy. Then `docker compose down`.

- [ ] **Step 5: Commit**

```bash
git init && git add . && git commit -m "chore: repo root + postgres compose"
```

---

### Task 0.2: Spring Boot skeleton (Java 25) that boots

**Files:**
- Create: `backend/build.gradle`, `backend/settings.gradle`
- Create: `backend/gradlew` + `backend/gradle/wrapper/*` (via `gradle wrapper`)
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/ElifootCarvoeiroApplication.java`
- Create: `backend/src/main/resources/application.yml`, `application-test.yml`

**Interfaces:**
- Produces: a bootable Spring app on port 8080; Spring profile `test` used by integration tests.

- [ ] **Step 1: Generate the Gradle wrapper**

Run (from `backend/`): `gradle wrapper --gradle-version 8.14` (use any Gradle ≥ the version supporting Java 25 toolchains).

- [ ] **Step 2: Write `settings.gradle`**

```groovy
rootProject.name = 'elifoot-carvoeiro'
```

- [ ] **Step 3: Write `build.gradle`**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.0'
    id 'io.spring.dependency-management' version '1.1.6'
}

group = 'br.com.elifootcarvoeiro'
version = '0.0.1'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
}

tasks.named('test') { useJUnitPlatform() }
```

> If Spring Boot `4.0.0` is not the latest patch when you build, bump to the newest `4.0.x`. Verify it supports Java 25.

- [ ] **Step 4: Write `ElifootCarvoeiroApplication.java`**

```java
package br.com.elifootcarvoeiro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ElifootCarvoeiroApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElifootCarvoeiroApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `application.yml`**

```yaml
spring:
  application:
    name: elifoot-carvoeiro
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/elifoot}
    username: ${DB_USER:elifoot}
    password: ${DB_PASSWORD:elifoot}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

app:
  jwt:
    secret: ${JWT_SECRET:change-me-dev-secret-change-me-dev-secret-0123456789}
    expiration-minutes: ${JWT_EXP_MIN:720}

server:
  port: 8080
```

- [ ] **Step 6: Write `application-test.yml`** (Testcontainers provides the datasource; keep Flyway on)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
app:
  jwt:
    secret: test-secret-test-secret-test-secret-0123456789
    expiration-minutes: 60
```

- [ ] **Step 7: Verify the app compiles** (it will not fully boot until migrations exist; that is Task 0.3)

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "chore: spring boot skeleton on java 25"
```

---

### Task 0.3: Flyway baseline migration + boot verification (Testcontainers)

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__auth.sql`
- Create: `backend/src/test/java/br/com/elifootcarvoeiro/ApplicationBootTest.java`
- Create: `backend/src/test/java/br/com/elifootcarvoeiro/support/PostgresTestContainer.java`

**Interfaces:**
- Produces: `users` table; reusable `@PostgresTest` base for integration tests.
- Consumes: nothing.

- [ ] **Step 1: Write `V1__auth.sql`**

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Write the Testcontainers base `PostgresTestContainer.java`**

```java
package br.com.elifootcarvoeiro.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16");
    }
}
```

- [ ] **Step 3: Write the failing boot test `ApplicationBootTest.java`**

```java
package br.com.elifootcarvoeiro;

import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ApplicationBootTest {
    @Test
    void contextLoadsAndMigrationsApply() {
        // Given/When: the Spring context starts with Flyway against a real Postgres
        // Then: no exception is thrown (assertion is the successful startup)
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*ApplicationBootTest'`
Expected: PASS (Flyway applies V1, Hibernate validates against `users`).

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: flyway baseline + testcontainers boot test"
```

---

### Task 0.4: Global error handling + health endpoint

**Files:**
- Create: `backend/.../common/ApiError.java` (record), `common/ApiException.java`, `common/GlobalExceptionHandler.java`, `common/HealthController.java`
- Test: `backend/.../common/HealthControllerTest.java`

**Interfaces:**
- Produces:
  - `record ApiError(String error, String message, int status)`
  - `ApiException(String code, String message, HttpStatus status)` (extends `RuntimeException`)
  - `GET /api/v1/health` → `{"status":"UP"}`

- [ ] **Step 1: Write `ApiError` record**

```java
package br.com.elifootcarvoeiro.common;

public record ApiError(String error, String message, int status) {}
```

- [ ] **Step 2: Write `ApiException`**

```java
package br.com.elifootcarvoeiro.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
```

- [ ] **Step 3: Write `GlobalExceptionHandler`**

```java
package br.com.elifootcarvoeiro.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ApiError body = new ApiError(ex.getCode(), ex.getMessage(), ex.getStatus().value());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
}
```

- [ ] **Step 4: Write the failing `HealthControllerTest`**

```java
package br.com.elifootcarvoeiro.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    @WithMockUser
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 5: Run it (fails — no controller)**

Run: `./gradlew test --tests '*HealthControllerTest'`
Expected: FAIL (404 / no bean).

- [ ] **Step 6: Write `HealthController`**

```java
package br.com.elifootcarvoeiro.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
```

- [ ] **Step 7: Run it (passes)**

Run: `./gradlew test --tests '*HealthControllerTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: global error handler + health endpoint"
```

---

### Task 0.5: Auth — register/login with JWT + security config + CORS

**Files:**
- Create: `auth/User.java` (entity), `auth/UserRepository.java`, `auth/AuthService.java`, `auth/AuthController.java`
- Create: DTOs `auth/RegisterRequest.java`, `auth/LoginRequest.java`, `auth/AuthResponse.java`
- Create: `common/security/JwtService.java`, `common/security/JwtAuthFilter.java`, `common/security/SecurityConfig.java`, `common/security/CurrentUser.java`
- Test: `auth/AuthControllerIT.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/auth/register` body `RegisterRequest(String username, String password)` → 201 `AuthResponse(String token, String username, UUID userId)`
  - `POST /api/v1/auth/login` body `LoginRequest(String username, String password)` → 200 `AuthResponse`
  - `JwtService.generateToken(UUID userId, String username)` / `parse(String token)` → `Claims`
  - `CurrentUser` argument resolver giving authenticated `UUID userId` to controllers.
- All `/api/v1/**` routes except `/auth/**` and `/health` require a Bearer token.

- [ ] **Step 1: Write `User` entity, `UserRepository`** (UUID id, username unique, password_hash). Use `@Entity` mapping to `users`. `UserRepository extends JpaRepository<User, UUID>` with `Optional<User> findByUsername(String username)`.

- [ ] **Step 2: Write `JwtService`** using jjwt: `generateToken` signs `{sub: userId, username}` with HS256 from `app.jwt.secret`, expiry from config; `parse` validates and returns claims; throw `ApiException("INVALID_TOKEN", ..., UNAUTHORIZED)` on failure.

- [ ] **Step 3: Write `SecurityConfig`** — `SecurityFilterChain` with CSRF disabled, stateless sessions, CORS enabled (allow `http://localhost:5173`), permit `/api/v1/auth/**` and `/api/v1/health`, authenticate everything else; register `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`; expose `BCryptPasswordEncoder` and `AuthenticationManager` beans.

- [ ] **Step 4: Write `AuthService`** — `register`: reject duplicate username with `ApiException("USERNAME_TAKEN", ..., CONFLICT)`, hash password with BCrypt, save, return token. `login`: load user, verify password, throw `ApiException("INVALID_CREDENTIALS", ..., UNAUTHORIZED)` on mismatch, return token.

- [ ] **Step 5: Write `AuthController`** delegating to `AuthService`; register returns `201 Created`.

- [ ] **Step 6: Write the failing integration test `AuthControllerIT`**

```java
package br.com.elifootcarvoeiro.auth;

import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AuthControllerIT {
    @Autowired MockMvc mockMvc;

    @Test
    void registeringThenReturnsTokenAndUsername() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"tigre\",\"password\":\"carvoeiro123\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("tigre"));
    }

    @Test
    void duplicateUsernameIsRejectedWithConflict() throws Exception {
        String body = "{\"username\":\"dup\",\"password\":\"carvoeiro123\"}";
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));
    }
}
```

- [ ] **Step 7: Run tests, iterate until green**

Run: `./gradlew test --tests '*AuthControllerIT'`
Expected: PASS both.

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: jwt auth (register/login) + security + cors"
```

---

### Task 0.6: Frontend scaffold (Vite + React + TS strict) with Criciúma theme + login

**Files:**
- Create: `frontend/` via Vite, then `src/main.tsx`, `App.tsx`, `router.tsx`, `api/client.ts`, `api/types.ts`, `auth/AuthContext.tsx`, `auth/LoginPage.tsx`, `features/career/DashboardPage.tsx`, `components/Layout.tsx`, `src/theme.css`
- Test: `src/auth/LoginPage.test.tsx`, `src/test/setup.ts`

**Interfaces:**
- Consumes backend `POST /api/v1/auth/login` & `/register`.
- Produces: `api/client.ts` exporting `apiPost<T>(path, body, token?)` / `apiGet<T>(path, token)`; `AuthContext` exposing `{ token, user, login, register, logout }` persisted to `localStorage`.

- [ ] **Step 1: Scaffold** — Run (from repo root): `npm create vite@latest frontend -- --template react-ts`, then `cd frontend && npm install && npm install react-router-dom @tanstack/react-query && npm install -D vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom`.

- [ ] **Step 2: Enable strict TS** — ensure `tsconfig.json` has `"strict": true`, `"noUnusedLocals": true`, `"noUnusedParameters": true`.

- [ ] **Step 3: Configure Vitest** — add to `vite.config.ts`:

```ts
/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
  test: { environment: 'jsdom', globals: true, setupFiles: './src/test/setup.ts' },
})
```

And `src/test/setup.ts`:

```ts
import '@testing-library/jest-dom'
```

- [ ] **Step 4: Write `api/client.ts`**

```ts
const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1'

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(err.message ?? 'Request failed')
  }
  return res.status === 204 ? (undefined as T) : (res.json() as Promise<T>)
}

export async function apiGet<T>(path: string, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return handle<T>(res)
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  return handle<T>(res)
}
```

- [ ] **Step 5: Write `auth/AuthContext.tsx`** — React context storing `token`/`username`/`userId` in state + `localStorage`; `login`/`register` call `apiPost('/auth/login'|'/auth/register', ...)`, store result; `logout` clears. Expose a `useAuth()` hook.

- [ ] **Step 6: Write `theme.css`** — CSS variables for Criciúma colors:

```css
:root {
  --cec-yellow: #f7d417;
  --cec-black: #111111;
  --cec-green: #1f7a3f;
  --cec-white: #ffffff;
  --bg: #f4f4f5;
}
body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); }
.cec-header { background: var(--cec-black); color: var(--cec-yellow); padding: 0.75rem 1rem; }
.cec-btn { background: var(--cec-yellow); color: var(--cec-black); border: 0; padding: 0.5rem 1rem; border-radius: 6px; cursor: pointer; font-weight: 600; }
```

- [ ] **Step 7: Write the failing `LoginPage.test.tsx`**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { LoginPage } from './LoginPage'
import { AuthProvider } from './AuthContext'
import { MemoryRouter } from 'react-router-dom'

describe('LoginPage', () => {
  it('renders username and password fields and a submit button', () => {
    render(
      <MemoryRouter>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(screen.getByLabelText(/usu[aá]rio/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/senha/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /entrar/i })).toBeInTheDocument()
  })
})
```

- [ ] **Step 8: Run it (fails)**

Run (from `frontend/`): `npm run test -- --run LoginPage`
Expected: FAIL (no `LoginPage`).

- [ ] **Step 9: Write `LoginPage.tsx`** — controlled form (`usuário`/`senha` labels, `Entrar` button), calls `useAuth().login`, on success navigates to `/dashboard`, shows error text on failure (try/catch). Add a link to register.

- [ ] **Step 10: Write `router.tsx` + `App.tsx`** — routes: `/login` → `LoginPage`, `/dashboard` → `DashboardPage` (protected: redirect to `/login` if no token). Wrap app in `AuthProvider` + `QueryClientProvider`. `DashboardPage` for now shows "Bem-vindo, {username}" and a logout button (empty dashboard = Phase 0 milestone).

- [ ] **Step 11: Run frontend tests**

Run: `npm run test -- --run`
Expected: PASS.

- [ ] **Step 12: Manual smoke** — Start `docker compose up -d`, `./gradlew bootRun`, `npm run dev`; register a user in the browser, land on the empty dashboard.

- [ ] **Step 13: Commit**

```bash
git add frontend && git commit -m "feat: react+vite scaffold, auth context, login, dashboard"
```

**Phase 0 done:** log in → empty dashboard, both ends wired, tests green.

---

# PHASE 1 — Lean Core Loop (first playable)

> Domain model intro: per-`SaveGame` mutable tables (`clubs`, `players`, `competitions`, `fixtures`, `match_events`, `lineups`, `lineup_players`) populated by copying immutable seed JSON. Standings are computed from `fixtures` on demand.

### Task 1.1: Game schema migration (V2)

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__game.sql`
- Test: extend `ApplicationBootTest` (already validates schema on boot — no new test needed; the Hibernate `validate` + entity mappings in later tasks enforce correctness).

- [ ] **Step 1: Write `V2__game.sql`**

```sql
CREATE TABLE save_games (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id),
    managed_club_id  UUID,
    season_year      INT NOT NULL,
    current_round    INT NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clubs (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    short_name    VARCHAR(20) NOT NULL,
    nickname      VARCHAR(40),
    primary_color VARCHAR(9),
    secondary_color VARCHAR(9)
);

CREATE TABLE players (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    position      VARCHAR(3) NOT NULL,   -- GK, DEF, MID, ATT
    age           INT NOT NULL,
    nationality   VARCHAR(40) NOT NULL,
    pace          INT NOT NULL,
    finishing     INT NOT NULL,
    passing       INT NOT NULL,
    tackling      INT NOT NULL,
    goalkeeping   INT NOT NULL,
    stamina       INT NOT NULL,
    overall       INT NOT NULL
);

CREATE TABLE competitions (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    type          VARCHAR(20) NOT NULL,  -- LEAGUE
    season_year   INT NOT NULL,
    total_rounds  INT NOT NULL
);

CREATE TABLE fixtures (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    competition_id UUID NOT NULL REFERENCES competitions(id) ON DELETE CASCADE,
    round          INT NOT NULL,
    home_club_id   UUID NOT NULL REFERENCES clubs(id),
    away_club_id   UUID NOT NULL REFERENCES clubs(id),
    home_goals     INT,
    away_goals     INT,
    played         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE match_events (
    id          UUID PRIMARY KEY,
    fixture_id  UUID NOT NULL REFERENCES fixtures(id) ON DELETE CASCADE,
    minute      INT NOT NULL,
    type        VARCHAR(20) NOT NULL,   -- GOAL
    player_id   UUID NOT NULL REFERENCES players(id),
    club_id     UUID NOT NULL REFERENCES clubs(id)
);

CREATE TABLE lineups (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    formation     VARCHAR(10) NOT NULL,
    UNIQUE (save_game_id, club_id)
);

CREATE TABLE lineup_players (
    lineup_id  UUID NOT NULL REFERENCES lineups(id) ON DELETE CASCADE,
    player_id  UUID NOT NULL REFERENCES players(id),
    starting   BOOLEAN NOT NULL,
    PRIMARY KEY (lineup_id, player_id)
);

CREATE INDEX idx_fixtures_round ON fixtures(save_game_id, competition_id, round);
CREATE INDEX idx_players_club ON players(club_id);
```

- [ ] **Step 2: Verify migration applies** (boot test still green)

Run: `./gradlew test --tests '*ApplicationBootTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend && git commit -m "feat: V2 game schema (clubs, players, competitions, fixtures...)"
```

---

### Task 1.2: Seed data files + loader

**Files:**
- Create: `backend/src/main/resources/seed/catarinense.json` (clubs + squads, Criciúma included)
- Create: `seed/SeedClub.java`, `seed/SeedPlayer.java` (record DTOs), `seed/SeedData.java` (record), `seed/SeedLoader.java`
- Test: `seed/SeedLoaderTest.java`

**Interfaces:**
- Produces:
  - `record SeedPlayer(String name, String position, int age, String nationality, int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina)`
  - `record SeedClub(String name, String shortName, String nickname, String primaryColor, String secondaryColor, List<SeedPlayer> players)`
  - `record SeedData(String competitionName, int seasonYear, List<SeedClub> clubs)`
  - `SeedLoader.loadCatarinense()` → `SeedData` (reads `seed/catarinense.json` via Jackson).

- [ ] **Step 1: Write `catarinense.json`** — a `SeedData` JSON with `competitionName: "Campeonato Catarinense"`, `seasonYear: 2026`, and the real Catarinense first-division clubs (e.g. Criciúma, Avaí, Figueirense, Chapecoense, Brusque, Marcílio Dias, Barra, Concórdia, Hercílio Luz, Camboriú — use the real participants you can confirm; aim for an even count, 10 or 12). Each club has a realistic short squad (≥14 players spanning GK/DEF/MID/ATT) with plausible attribute integers 1–99. Criciúma uses real current squad names; rival squads may use real names where known. Keep colors as hex.

```json
{
  "competitionName": "Campeonato Catarinense",
  "seasonYear": 2026,
  "clubs": [
    {
      "name": "Criciúma Esporte Clube",
      "shortName": "CRI",
      "nickname": "Tigre",
      "primaryColor": "#f7d417",
      "secondaryColor": "#111111",
      "players": [
        { "name": "Gustavo", "position": "GK", "age": 28, "nationality": "Brasil", "pace": 45, "finishing": 20, "passing": 60, "tackling": 30, "goalkeeping": 78, "stamina": 70 }
      ]
    }
  ]
}
```

> The snippet shows the shape — fill all clubs with full squads. Even number of clubs is required for clean round-robin scheduling.

- [ ] **Step 2: Write the record DTOs** `SeedPlayer`, `SeedClub`, `SeedData` exactly matching the JSON (Jackson maps by record component names).

- [ ] **Step 3: Write the failing `SeedLoaderTest`**

```java
package br.com.elifootcarvoeiro.seed;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderTest {
    @Test
    void loadsCatarinenseWithEvenNumberOfClubsIncludingCriciuma() {
        SeedData data = new SeedLoader().loadCatarinense();

        assertThat(data.competitionName()).isEqualTo("Campeonato Catarinense");
        assertThat(data.clubs()).isNotEmpty();
        assertThat(data.clubs().size() % 2).isZero();
        assertThat(data.clubs()).anyMatch(c -> c.shortName().equals("CRI"));
        assertThat(data.clubs()).allSatisfy(c ->
            assertThat(c.players()).hasSizeGreaterThanOrEqualTo(14));
    }
}
```

- [ ] **Step 4: Run it (fails — no loader)**

Run: `./gradlew test --tests '*SeedLoaderTest'`
Expected: FAIL.

- [ ] **Step 5: Write `SeedLoader`**

```java
package br.com.elifootcarvoeiro.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;

@Component
public class SeedLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public SeedData loadCatarinense() {
        try (InputStream in = new ClassPathResource("seed/catarinense.json").getInputStream()) {
            return mapper.readValue(in, SeedData.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load catarinense seed", e);
        }
    }
}
```

- [ ] **Step 6: Run it (passes)**

Run: `./gradlew test --tests '*SeedLoaderTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend && git commit -m "feat: catarinense seed data + loader"
```

---

### Task 1.3: Round-robin fixture scheduler (pure)

**Files:**
- Create: `competition/FixtureScheduler.java`, `competition/ScheduledFixture.java` (record)
- Test: `competition/FixtureSchedulerTest.java`

**Interfaces:**
- Produces:
  - `record ScheduledFixture(int round, int homeIndex, int awayIndex)`
  - `FixtureScheduler.doubleRoundRobin(int teamCount)` → `List<ScheduledFixture>` using the circle method; double round-robin (home & away). For N teams: `2*(N-1)` rounds, `N/2` matches per round. Indices are 0..N-1 positions into the club list.

- [ ] **Step 1: Write the failing `FixtureSchedulerTest`**

```java
package br.com.elifootcarvoeiro.competition;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class FixtureSchedulerTest {
    @Test
    void doubleRoundRobinForFourTeamsHasSixRoundsAndTwelveMatches() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(4);

        assertThat(fixtures).hasSize(12);                 // 4 teams: 2*(4-1)*(4/2)
        assertThat(fixtures.stream().map(ScheduledFixture::round).distinct().count()).isEqualTo(6L);
    }

    @Test
    void everyOrderedPairMeetsExactlyOnce() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(4);
        Set<String> pairs = new HashSet<>();
        for (ScheduledFixture f : fixtures) {
            assertThat(f.homeIndex()).isNotEqualTo(f.awayIndex());
            assertThat(pairs.add(f.homeIndex() + "-" + f.awayIndex())).isTrue(); // no duplicate ordered pair
        }
        assertThat(pairs).hasSize(12); // 4*3 ordered pairs
    }

    @Test
    void eachRoundHasEachTeamExactlyOnce() {
        List<ScheduledFixture> fixtures = new FixtureScheduler().doubleRoundRobin(6);
        Map<Integer, List<ScheduledFixture>> byRound = new HashMap<>();
        fixtures.forEach(f -> byRound.computeIfAbsent(f.round(), r -> new ArrayList<>()).add(f));
        for (var entry : byRound.entrySet()) {
            Set<Integer> teams = new HashSet<>();
            entry.getValue().forEach(f -> { teams.add(f.homeIndex()); teams.add(f.awayIndex()); });
            assertThat(teams).hasSize(6);
        }
    }
}
```

- [ ] **Step 2: Run it (fails)**

Run: `./gradlew test --tests '*FixtureSchedulerTest'`
Expected: FAIL.

- [ ] **Step 3: Write `ScheduledFixture` + `FixtureScheduler`** (circle method, then mirror for the second leg swapping home/away)

```java
package br.com.elifootcarvoeiro.competition;

public record ScheduledFixture(int round, int homeIndex, int awayIndex) {}
```

```java
package br.com.elifootcarvoeiro.competition;

import java.util.ArrayList;
import java.util.List;

public class FixtureScheduler {

    public List<ScheduledFixture> doubleRoundRobin(int teamCount) {
        if (teamCount < 2 || teamCount % 2 != 0) {
            throw new IllegalArgumentException("teamCount must be even and >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = teamCount;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        int roundsPerLeg = n - 1;
        for (int round = 0; round < roundsPerLeg; round++) {
            for (int i = 0; i < n / 2; i++) {
                int home = arr[i];
                int away = arr[n - 1 - i];
                // alternate home/away by round for fairness
                if (round % 2 == 1) { int tmp = home; home = away; away = tmp; }
                result.add(new ScheduledFixture(round, home, away));               // first leg
                result.add(new ScheduledFixture(round + roundsPerLeg, away, home)); // second leg (reversed)
            }
            rotate(arr);
        }
        return result;
    }

    // Fix index 0, rotate the rest clockwise (standard circle method)
    private void rotate(int[] arr) {
        int last = arr[arr.length - 1];
        for (int i = arr.length - 1; i > 1; i--) arr[i] = arr[i - 1];
        arr[1] = last;
    }
}
```

- [ ] **Step 4: Run it (passes)**

Run: `./gradlew test --tests '*FixtureSchedulerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: double round-robin fixture scheduler"
```

---

### Task 1.4: Match engine v1 (pure, seeded RNG)

**Files:**
- Create: `match/MatchSimulationService.java`, `match/LineupStrength.java` (record), `match/SimulatedMatch.java` (record), `match/SimulatedGoal.java` (record)
- Test: `match/MatchSimulationServiceTest.java`

**Interfaces:**
- Produces:
  - `record LineupStrength(double attack, double defense)` — derived team strengths (0..100-ish).
  - `record SimulatedGoal(int minute, boolean home)`
  - `record SimulatedMatch(int homeGoals, int awayGoals, List<SimulatedGoal> goals)`
  - `MatchSimulationService.simulate(LineupStrength home, LineupStrength away, long seed)` → `SimulatedMatch`. Deterministic for a given seed. Uses `java.util.Random(seed)`. Goal-count model: expected goals per side from `attack` vs opponent `defense` with a home advantage, sampled via independent per-minute scoring chance. No clock/`Math.random`.

- [ ] **Step 1: Write the records** `LineupStrength`, `SimulatedGoal`, `SimulatedMatch`.

- [ ] **Step 2: Write the failing `MatchSimulationServiceTest`**

```java
package br.com.elifootcarvoeiro.match;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {
    private final MatchSimulationService engine = new MatchSimulationService();

    @Test
    void sameSeedAndInputsProduceIdenticalResult() {
        LineupStrength a = new LineupStrength(70, 65);
        LineupStrength b = new LineupStrength(60, 60);

        SimulatedMatch first = engine.simulate(a, b, 42L);
        SimulatedMatch second = engine.simulate(a, b, 42L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void goalListSizeMatchesScoreline() {
        SimulatedMatch m = engine.simulate(new LineupStrength(75, 70), new LineupStrength(55, 50), 7L);

        long homeGoals = m.goals().stream().filter(SimulatedGoal::home).count();
        long awayGoals = m.goals().stream().filter(g -> !g.home()).count();
        assertThat(homeGoals).isEqualTo(m.homeGoals());
        assertThat(awayGoals).isEqualTo(m.awayGoals());
    }

    @Test
    void goalMinutesAreWithinRegulationTime() {
        SimulatedMatch m = engine.simulate(new LineupStrength(80, 75), new LineupStrength(50, 45), 99L);
        assertThat(m.goals()).allSatisfy(g -> assertThat(g.minute()).isBetween(1, 90));
    }

    @Test
    void strongerTeamWinsMoreOftenOverManySeeds() {
        LineupStrength strong = new LineupStrength(85, 80);
        LineupStrength weak = new LineupStrength(45, 40);
        int strongWins = 0;
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(strong, weak, seed);
            if (m.homeGoals() > m.awayGoals()) strongWins++;
        }
        assertThat(strongWins).isGreaterThan(120); // clearly favoured
    }
}
```

- [ ] **Step 3: Run it (fails)**

Run: `./gradlew test --tests '*MatchSimulationServiceTest'`
Expected: FAIL.

- [ ] **Step 4: Write `MatchSimulationService`**

```java
package br.com.elifootcarvoeiro.match;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class MatchSimulationService {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;

    public SimulatedMatch simulate(LineupStrength home, LineupStrength away, long seed) {
        Random rng = new Random(seed);

        double homeChance = perMinuteScoreChance(home.attack() + HOME_ADVANTAGE, away.defense());
        double awayChance = perMinuteScoreChance(away.attack(), home.defense() + HOME_ADVANTAGE);

        List<SimulatedGoal> goals = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;
        for (int minute = 1; minute <= MINUTES; minute++) {
            if (rng.nextDouble() < homeChance) { homeGoals++; goals.add(new SimulatedGoal(minute, true)); }
            if (rng.nextDouble() < awayChance) { awayGoals++; goals.add(new SimulatedGoal(minute, false)); }
        }
        return new SimulatedMatch(homeGoals, awayGoals, List.copyOf(goals));
    }

    // Maps attack-vs-defense differential to a small per-minute scoring probability.
    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;          // roughly -1..+1
        double expectedGoals = Math.max(0.2, 1.4 + diff); // avg goals per team per match
        return expectedGoals / MINUTES;
    }
}
```

- [ ] **Step 5: Run it (passes; tune constants if the "stronger team" test is flaky)**

Run: `./gradlew test --tests '*MatchSimulationServiceTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: deterministic match simulation engine v1"
```

---

### Task 1.5: JPA entities + repositories for the game model

**Files:**
- Create entities: `career/SaveGame.java`, `club/Club.java`, `player/Player.java`, `player/Position.java` (enum), `competition/Competition.java`, `competition/Fixture.java`, `match/MatchEvent.java`, `match/Lineup.java`, `match/LineupPlayer.java`
- Create repositories: `SaveGameRepository`, `ClubRepository`, `PlayerRepository`, `CompetitionRepository`, `FixtureRepository`, `MatchEventRepository`, `LineupRepository`
- Test: `competition/FixtureRepositoryIT.java`

**Interfaces:**
- Produces (key repo methods later tasks rely on):
  - `FixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(UUID saveGameId, UUID competitionId, int round)` → `List<Fixture>`
  - `FixtureRepository.findBySaveGameIdAndCompetitionId(UUID, UUID)` → `List<Fixture>`
  - `PlayerRepository.findByClubId(UUID clubId)` → `List<Player>`
  - `ClubRepository.findBySaveGameId(UUID)` → `List<Club>`
  - `enum Position { GK, DEF, MID, ATT }`
- Entities use `UUID` ids (assigned in service via `UUID.randomUUID()`), `@Enumerated(EnumType.STRING)` for `Position`/status.

- [ ] **Step 1: Write `Position` enum and all entities** mapping exactly to the V2 tables. Mutable JPA entities are allowed per standards. Use `Instant` for `createdAt`. `Fixture.homeGoals/awayGoals` are nullable `Integer` until played.

- [ ] **Step 2: Write the repositories** (interfaces extending `JpaRepository`) with the query methods listed above.

- [ ] **Step 3: Write the failing `FixtureRepositoryIT`** (Testcontainers) that persists a save+clubs+competition+fixture and reads it back by round. Use `@DataJpaTest` with `@Import(PostgresTestContainer.class)`, `@AutoConfigureTestDatabase(replace = NONE)`, `@ActiveProfiles("test")`.

```java
package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.support.PostgresTestContainer;
// ... entity imports
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureRepositoryIT {
    @Autowired FixtureRepository fixtures;
    // @Autowired the other repos needed to set up the graph

    @Test
    void findsFixturesByRound() {
        // Given a persisted save, two clubs, a competition, and a round-1 fixture
        // When findBySaveGameIdAndCompetitionIdAndRound is called
        // Then exactly that fixture is returned
        // (build the graph with the repositories, then assert)
    }
}
```

- [ ] **Step 4: Run it (fails, then implement entities/repos until green)**

Run: `./gradlew test --tests '*FixtureRepositoryIT'`
Expected: PASS once mappings are correct.

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: jpa entities + repositories for game model"
```

---

### Task 1.6: SaveGameService — create a career from seed

**Files:**
- Create: `career/SaveGameService.java`, `career/SaveGameStatus.java` (enum), `career/CreateSaveGameRequest.java`, `career/SaveGameResponse.java`, `career/SaveGameController.java`
- Test: `career/SaveGameServiceIT.java`

**Interfaces:**
- Consumes: `SeedLoader`, `FixtureScheduler`, all repositories.
- Produces:
  - `enum SaveGameStatus { IN_PROGRESS, FINISHED }`
  - `SaveGameService.createCareer(UUID userId)` → `SaveGameResponse(UUID id, int seasonYear, UUID managedClubId, String managedClubName, int currentRound, int totalRounds)`. Steps: load seed → create `SaveGame` (managed club = Criciúma) → insert clubs+players (copy from seed) → insert one LEAGUE `Competition` → generate fixtures via `FixtureScheduler.doubleRoundRobin(clubCount)` mapping indices to created club ids → persist all fixtures (`played=false`).
  - `POST /api/v1/save-games` → 201 `SaveGameResponse` (uses `CurrentUser` userId).
  - `GET /api/v1/save-games/{id}` → 200 `SaveGameResponse`.

- [ ] **Step 1: Write enum + DTO records.**

- [ ] **Step 2: Write the failing `SaveGameServiceIT`** (Testcontainers, `@SpringBootTest`)

```java
package br.com.elifootcarvoeiro.career;
// imports: PostgresTestContainer, repositories, a helper to create a user
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest @ActiveProfiles("test") @Import(PostgresTestContainer.class)
class SaveGameServiceIT {

    @Test
    void creatingCareerSeedsClubsPlayersAndFullFixtureList() {
        // Given a persisted user
        // When saveGameService.createCareer(userId) is called
        // Then a SaveGame exists managed by Criciúma,
        //   clubs == seed club count, every club has players,
        //   fixtures count == clubCount*(clubCount-1) (double round robin),
        //   and all fixtures are unplayed.
    }
}
```

- [ ] **Step 3: Run it (fails).** `./gradlew test --tests '*SaveGameServiceIT'`

- [ ] **Step 4: Write `SaveGameService`** — `@Service`, `@Transactional` on `createCareer`. Compute `overall` per player as a position-weighted average of attributes (e.g. GK weights goalkeeping heavily; outfield weight by position). Set `Competition.totalRounds = 2*(clubCount-1)`. Map `ScheduledFixture.homeIndex/awayIndex` to the ordered list of created club ids.

- [ ] **Step 5: Write `SaveGameController`** delegating to the service, using the `CurrentUser` resolver from Task 0.5.

- [ ] **Step 6: Run it (passes).** `./gradlew test --tests '*SaveGameServiceIT'`

- [ ] **Step 7: Commit**

```bash
git add backend && git commit -m "feat: create career from seed (clubs, players, fixtures)"
```

---

### Task 1.7: Squad + lineup endpoints

**Files:**
- Create: `match/LineupService.java`, DTOs `match/PlayerResponse.java`, `match/SquadResponse.java`, `match/SetLineupRequest.java`, `match/LineupResponse.java`, `match/SquadController.java`
- Test: `match/LineupServiceIT.java`

**Interfaces:**
- Produces:
  - `GET /api/v1/save-games/{id}/clubs/{clubId}/players` → `SquadResponse(List<PlayerResponse>)` where `PlayerResponse(UUID id, String name, String position, int age, int overall, ...attributes)`.
  - `PUT /api/v1/save-games/{id}/clubs/{clubId}/lineup` body `SetLineupRequest(String formation, List<UUID> startingPlayerIds)` → 200 `LineupResponse`. Validates: exactly 11 starters, exactly 1 GK, all players belong to the club; else `ApiException("INVALID_LINEUP", ..., UNPROCESSABLE_ENTITY)`. Upserts the `lineups`/`lineup_players` rows.
  - `LineupService.bestXi(UUID clubId)` → `Lineup`-equivalent selection (1 GK + top 10 by overall respecting at least a valid spread) — used by AI clubs and as the user's default.

- [ ] **Step 1: Write DTO records.**

- [ ] **Step 2: Write the failing `LineupServiceIT`** asserting: (a) setting a valid 11-with-1-GK lineup persists and reads back; (b) a lineup with 0 GKs is rejected with `INVALID_LINEUP`; (c) `bestXi` returns 11 with exactly 1 GK.

- [ ] **Step 3: Run it (fails).**

- [ ] **Step 4: Write `LineupService`** (validation + upsert + `bestXi`) and `SquadController`.

- [ ] **Step 5: Run it (passes).**

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: squad + lineup selection endpoints"
```

---

### Task 1.8: Advance-round service (simulate a round) + standings

**Files:**
- Create: `competition/StandingsCalculator.java`, `competition/Standing.java` (record), `match/RoundSimulationService.java`, `match/SimulateRoundResponse.java`, DTOs `competition/StandingRow.java`, `match/FixtureResultResponse.java`
- Create controller endpoints in `competition/CompetitionController.java` and `match/MatchController.java`
- Test: `competition/StandingsCalculatorTest.java`, `match/RoundSimulationServiceIT.java`

**Interfaces:**
- Produces:
  - `record StandingRow(UUID clubId, String clubName, int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst, int goalDifference, int points)`
  - `StandingsCalculator.compute(List<Club> clubs, List<Fixture> playedFixtures)` → `List<StandingRow>` sorted by points, then goal difference, then goals for, then name. Win=3, draw=1.
  - `RoundSimulationService.simulateNextRound(UUID saveGameId)` → `SimulateRoundResponse(int round, List<FixtureResultResponse> results, boolean seasonFinished)`. For each fixture in `save.currentRound + 1`: build `LineupStrength` for both clubs (user's club uses persisted lineup if present else `bestXi`; AI uses `bestXi`), call `MatchSimulationService.simulate(..., seed)` with a deterministic seed derived from `(saveGameId, fixtureId)` (e.g. `fixtureId.getMostSignificantBits() ^ round`), write goals to fixture + `match_events` (map goal minutes to a scoring player via simple attribute-weighted pick), mark played, then increment `save.currentRound`; set status FINISHED when last round simulated.
  - `GET /api/v1/save-games/{id}/competitions/{competitionId}/standings` → `List<StandingRow>`.
  - `POST /api/v1/save-games/{id}/advance` → `SimulateRoundResponse`.

- [ ] **Step 1: Write `StandingRow` + the failing `StandingsCalculatorTest`**

```java
package br.com.elifootcarvoeiro.competition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
// build 3 clubs and a few played fixtures with known scores
class StandingsCalculatorTest {
    @Test
    void ranksByPointsThenGoalDifference() {
        // Given clubs A,B,C and fixtures: A 2-0 B, A 1-1 C, B 3-1 C
        // When compute(...) is called
        // Then order is A (4 pts), then by GD between B and C; points/GD/GF computed correctly
        // assert points: A=4, B=3, C=1 ; A first
    }
}
```

- [ ] **Step 2: Run it (fails).** `./gradlew test --tests '*StandingsCalculatorTest'`

- [ ] **Step 3: Write `StandingsCalculator`** (pure; comparator points→GD→GF→name).

- [ ] **Step 4: Run it (passes).**

- [ ] **Step 5: Write the failing `RoundSimulationServiceIT`** (Testcontainers): create a career, advance once → first-round fixtures all `played`, `currentRound==1`, results count == clubCount/2; advancing through all rounds sets status FINISHED and standings sum of `played` is consistent.

- [ ] **Step 6: Run it (fails), implement `RoundSimulationService` + controllers, until green.**

Run: `./gradlew test --tests '*RoundSimulationServiceIT'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend && git commit -m "feat: advance-round simulation + standings"
```

---

### Task 1.9: Frontend — career flow (create, squad, advance, table)

**Files:**
- Create: `frontend/src/api/types.ts` (TS mirrors of response records), `api/career.ts` (typed calls)
- Create: `features/career/DashboardPage.tsx` (extend), `features/squad/SquadPage.tsx`, `features/competition/TablePage.tsx`, `features/match/AdvancePanel.tsx`
- Test: `features/squad/SquadPage.test.tsx`, `features/competition/TablePage.test.tsx`

**Interfaces:**
- Consumes backend: `POST /save-games`, `GET /save-games/{id}`, `GET .../players`, `PUT .../lineup`, `POST .../advance`, `GET .../standings`.
- Produces: typed `api/career.ts` functions (`createCareer`, `getSquad`, `setLineup`, `advanceRound`, `getStandings`) all taking the auth token from `useAuth()`.

- [ ] **Step 1: Write `api/types.ts`** mirroring `SaveGameResponse`, `PlayerResponse`, `StandingRow`, `SimulateRoundResponse`, `FixtureResultResponse` as TS `type`s.

- [ ] **Step 2: Write `api/career.ts`** thin typed wrappers over `apiGet`/`apiPost`/`apiPut` (add `apiPut` to `client.ts`).

- [ ] **Step 3: Write the failing `SquadPage.test.tsx`** — mock `getSquad` to return a small squad; assert player names render and selecting 11 enables the "Salvar escalação" button; selecting without a GK shows a validation message. (Use RTL + mocked module.)

- [ ] **Step 4: Run it (fails).** `npm run test -- --run SquadPage`

- [ ] **Step 5: Write `SquadPage.tsx`** — fetch squad via TanStack Query, formation select, checkbox/click selection capped at 11 with a client-side "1 GK + 11 total" hint, calls `setLineup`.

- [ ] **Step 6: Write `TablePage.test.tsx`** — mock `getStandings`, assert rows render sorted with points column.

- [ ] **Step 7: Write `TablePage.tsx`** (standings table, Criciúma row highlighted) and `AdvancePanel.tsx` ("Avançar rodada" button → `advanceRound`, shows round results, invalidates standings query). Wire `DashboardPage` to create/continue a career and link to Squad / Table.

- [ ] **Step 8: Run all frontend tests.** `npm run test -- --run` → PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend && git commit -m "feat: career UI - create, squad, advance, league table"
```

---

### Task 1.10: End-to-end manual verification (Phase 1 acceptance)

- [ ] **Step 1:** `docker compose up -d` (Postgres).
- [ ] **Step 2:** `cd backend && ./gradlew bootRun`.
- [ ] **Step 3:** `cd frontend && npm run dev`.
- [ ] **Step 4:** In the browser: register/login → create a Criciúma career → open Squad, pick an XI, save → from the dashboard click "Avançar rodada" repeatedly to the end of the season.
- [ ] **Step 5:** Confirm: results appear each round, the league table updates and sorts correctly, Criciúma's row is highlighted, and at the end a champion is shown / status is FINISHED.
- [ ] **Step 6:** Run full test suites: `./gradlew test` and `npm run test -- --run` — both green.

**Phase 1 acceptance:** a user picks an XI, simulates a full Catarinense season, and sees a correct final table with a champion.

---

## Self-Review Notes

- **Spec coverage:** Phase 0 (compose/Postgres ✓, Spring skeleton+Flyway ✓, error handler+health ✓, auth ✓, React scaffold+theme+login ✓, testing harness ✓). Phase 1 (seed ✓, career from seed ✓, squad/lineup ✓, match engine ✓, advance+standings ✓, end-of-season ✓, UI ✓). Fixture scheduling, deterministic seeding, and standings ordering each have dedicated pure-unit tasks.
- **Determinism:** match engine uses only `Random(seed)`; round seeds derived from fixture id — no clock/`Math.random`.
- **Type consistency:** `LineupStrength(attack, defense)`, `SimulatedMatch(homeGoals, awayGoals, goals)`, `StandingRow(...points)`, `SaveGameResponse(...totalRounds)` referenced consistently across tasks 1.4/1.6/1.8/1.9.
- **Deferred to later phases (not gaps):** tactics, finances, transfers, multi-season, training/youth/injuries/morale/staff, promotion/relegation.

## Notes / Risks for the implementer

- Confirm the exact latest **Spring Boot 4.0.x** patch that supports Java 25 before locking `build.gradle`.
- The Catarinense seed must have an **even** club count for the scheduler; verify real participant list and pad/trim to even.
- If the "stronger team wins" statistical test is flaky, widen the seed count or tune `BASE`/`HOME_ADVANTAGE` — keep the engine deterministic.
