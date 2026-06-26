# Elifoot Carvoeiro — Criciúma EC Football Manager Game

## Context

The user wants to build a football-management game (Elifoot-style) themed around **Criciúma Esporte Clube** ("Carvoeiro"/"Tigre"), inspired by leaoeterno.com.br. It is a **single-player career** game: a human manages a club against AI-controlled opponents across multiple seasons, with the full classic + deep-manager feature set as the end goal.

Because a full football manager is a large effort, this plan is **phased**. Each phase ships a self-contained, playable increment and is designed so the next phase layers on top without rewrites. We start lean (pick XI → simulate → league table) and end with a deep manager (training, youth, injuries, finances, transfers, promotion/relegation).

**Decisions locked with the user:**
- Architecture: **full-stack** (separate frontend + backend + database).
- Frontend: **React + TypeScript + Vite**.
- Backend: **Java + Spring Boot + PostgreSQL** (per user's global standards: Controller→Service→Repository, records for DTOs, JPA, constructor injection, UUID ids, BigDecimal for money).
- Game data: **real** — Criciúma squad, real rivals, real Brazilian competitions — sourced from **hand-curated seed files** (JSON/SQL), no external API.
- Game mode: **single-player career** (no online multiplayer).

## Repository Structure (monorepo)

```
elifoot-carvoeiro/
  backend/            # Spring Boot (Java 25), Gradle
    src/main/java/br/com/elifootcarvoeiro/
      club/ player/ match/ competition/ career/ transfer/ ...   # by-feature packages
    src/main/resources/
      db/migration/   # Flyway migrations
      seed/           # curated real-data seed files (JSON)
  frontend/           # React + TS + Vite
    src/features/     # club, squad, match, competition, career, transfers ...
    src/api/          # typed API client
  docker-compose.yml  # postgres (+ pgadmin optional)
  docs/superpowers/specs/  # design docs
```

Organize **by feature** (per global standards), strict layering Controller→Service→Repository, domain logic in domain objects/dedicated classes.

## Core Domain Model (introduced progressively)

- **Club** — id, name, nickname, colors, stadium, reputation, finances.
- **Player** — id, name, position, age, nationality; **PlayerAttributes** (pace, finishing, passing, tackling, goalkeeping, stamina, etc., 1–99); form, fitness, morale (later phases).
- **Squad / Lineup** — formation, starting XI, bench, per-player roles.
- **Tactics** — formation, mentality, pressing, set-piece takers (Phase 2+).
- **Competition / Season / Fixture / Match** — round-robin leagues + cups; **MatchResult**, **MatchEvent** (goals, cards), **Standing** (league table).
- **Manager (User account) / SaveGame** — each career is an isolated `SaveGame` owning its own mutable copy of the world (clubs, players, standings, calendar date, finances). New saves are initialized from seed data. This keeps careers isolated and seed data immutable.
- **Transfer / TransferOffer / Budget** (Phase 2+), **Training / YouthPlayer / Injury / Staff / Sponsorship** (Phase 3+).

## The Match Engine (heart of the game)

A `MatchSimulationService` takes two lineups + tactics and produces a deterministic-but-seeded result: score, timeline of events, and per-player ratings. Built in Phase 1 as attribute-driven probability (team strength from lineup attributes → expected goals → sampled events with a seeded RNG so results are reproducible), then enriched each phase (tactics influence in P2, fatigue/injuries/morale in P3). Designed as an isolated, independently testable unit with a clear input/output contract.

---

## Phase 0 — Foundation & Scaffolding

**Goal:** runnable empty app, both ends wired, CI green.

- Monorepo + `docker-compose.yml` (PostgreSQL).
- Backend: Spring Boot skeleton (Java 25, Gradle), Flyway, base config via env vars, health endpoint, global error handler returning the standard error JSON (`{error, message, status}`), CORS.
- Frontend: Vite + React + TS (strict), router, base layout/theme in Criciúma colors (yellow/black/green), typed API client.
- Auth: minimal account model (register/login, JWT or session) so careers can belong to a user. Keep it thin.
- Testing harness: JUnit 5 + Testcontainers (Postgres) on backend; Vitest + React Testing Library on frontend. Given/When/Then style, ≥80% coverage target.

**Playable result:** log in, see an empty dashboard. (Infrastructure milestone.)

## Phase 1 — Lean Core Loop (first truly playable version)

**Goal:** pick Criciúma's starting XI, simulate a single-league season, watch results and the table.

- Seed: Criciúma squad + one real competition's clubs as a single round-robin league (e.g. **Campeonato Catarinense** participants) with basic player attributes.
- Career: create/load a SaveGame as Criciúma manager; generate the league fixture calendar.
- Squad screen: choose formation + starting XI from the real squad.
- Match engine v1: attribute-based simulation → score + goal events + player ratings (seeded RNG).
- "Advance" flow: simulate the next round (user's match uses chosen XI; AI clubs auto-pick best XI), update **league table**, results list, top scorers.
- End-of-season: champion crowned, save persists.

**Playable result:** a complete, fun season of Criciúma in the Catarinense.

## Phase 2 — Classic Elifoot

**Goal:** the full classic manager experience.

- **Multiple real competitions** in one season calendar: state championship + national division (e.g. Catarinense + **Série B**), interleaved fixture scheduling.
- **Tactics**: mentality, pressing, marking, set-piece takers — feeding the match engine.
- **Player development within a season**: form & match fitness; suspensions from cards.
- **Finances**: club **budget** (BigDecimal), matchday income, wages.
- **Transfer market**: scout/list players from other clubs, make/receive offers, buy & sell within budget, contracts.
- **Multi-season careers**: roll over seasons, age players, regenerate calendars, ongoing save.

**Playable result:** manage Criciúma across several seasons with squad-building and money.

## Phase 3 — Deep Manager

**Goal:** depth and long-term progression.

- **Training** schedules improving attributes over time.
- **Youth academy**: generate youth prospects, promote to senior squad.
- **Injuries & fatigue**: rotation matters; medical recovery.
- **Morale**: team & individual morale affecting performance.
- **Staff**: assistant manager, coaches, scouts, physios with effects.
- **Sponsorships & expanded finances**: stadium, sponsors, revenue streams.
- **Promotion/relegation across divisions**: full Brazilian pyramid (e.g. Série A/B/C) so Criciúma can rise and fall.

**Playable result:** a deep, replayable career manager.

## Cross-Cutting Concerns (apply every phase)

- **Testing:** Given/When/Then; Testcontainers Postgres for backend integration; RTL for components; ≥80% coverage. The match engine gets thorough deterministic unit tests (seeded RNG).
- **Seed-data discipline:** curated real data lives in versioned seed files; SaveGames copy from it so seeds stay immutable.
- **API conventions:** REST, plural kebab-case nouns, precise status codes, `CreateXRequest`/`XResponse` records, page-based pagination, `/v1` prefix.
- **Standards:** immutability by default, no `java.util.Date` (use `LocalDate`/`Instant`), `BigDecimal` for money, UUID ids, constructor injection, enums with behavior.

## Critical Files (created — greenfield)

- `docker-compose.yml`, `backend/build.gradle`, `backend/src/main/resources/application.yml`
- `backend/.../match/MatchSimulationService.java` (engine), `backend/.../career/SaveGameService.java`
- `backend/src/main/resources/db/migration/V1__*.sql` (schema), `backend/src/main/resources/seed/*.json` (real data)
- `frontend/src/features/squad/`, `frontend/src/features/match/`, `frontend/src/features/competition/`
- `frontend/src/api/` (typed client)
- `docs/superpowers/specs/2026-06-26-elifoot-carvoeiro-design.md` (this design, committed)

## Verification (per phase)

- **Backend:** `./gradlew test` (unit + Testcontainers integration) green; `docker-compose up` + curl key endpoints (create save, set lineup, simulate round, fetch table).
- **Frontend:** `npm run test` (Vitest/RTL) green; `npm run dev` and manually play the phase's core loop in the browser.
- **End-to-end per phase:** start backend + frontend, create a Criciúma career, run a full season for that phase's feature set, confirm the table/results/finances update correctly.
- **Phase 1 acceptance:** a user can pick an XI, simulate a full Catarinense season, and see a correct final table with a champion.

## Execution Approach

Build phase by phase. Each phase becomes its own `writing-plans` implementation plan (spec → plan → build → verify) so we keep tight review checkpoints. After this overall plan is approved, we'll write the detailed **Phase 0 + Phase 1** implementation plan first and start there.
