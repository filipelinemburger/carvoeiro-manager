# Phase 3a-2: Bench, Substitutions & Live Match — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the user's own match into a live, minute-by-minute experience with a 9-man bench, up to 5 substitutions at any minute, timed in-match injuries/red cards that pause for a decision, and partial-minutes fitness decay.

**Architecture:** A **parallel `LiveMatchEngine`** produces the user's match as a deterministic, sub-aware event timeline (goals, cards, chances, timed injuries, substitutions) — the existing instant `MatchSimulationService` is left **byte-for-byte untouched**, so every AI/other-club fixture replays exactly as before. A server-authoritative `LiveMatch` session (persisted, one per save) drives a "live broadcast": each `advance` reveals only up to the next event; the browser owns the clock over plain HTTP. On `finish`, the user's fixture outcome is applied from the final timeline, the rest of the round is instant-simulated via the existing path, and the same `AdvanceResponse` as today is returned.

**Tech Stack:** Java 25 / Spring Boot / JPA / Flyway / PostgreSQL / Testcontainers (backend); React 19 / TypeScript / Vite / MUI v9 / TanStack Query v5 / react-router v7 / Vitest + RTL (frontend).

## Global Constraints

- **JDK 25 for Gradle.** Every gradle command must run with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home`.
- **Never auto-commit.** The commit step in each task prepares the command; the human runs `git commit`. (This project rule overrides the plan template's "commit" step — write the message, do not execute it, unless the user says otherwise.)
- **Work directly on `main`** — no feature branches (solo project).
- **Determinism is bedrock.** The instant engine (`MatchSimulationService`, `FixtureSimulator` for AI fixtures) must stay byte-identical. The new `LiveMatchEngine` must be deterministic in `(seed, subSchedule)`: identical inputs ⇒ identical timeline.
- **Bench = up to 9; substitutions = up to 5**, managed club only (AI clubs do not sub in-match).
- **JSON columns are stored as `TEXT`** and (de)serialized with Jackson (`tools.jackson.databind.ObjectMapper`), following `NewsItem.payload`. No jsonb, no hibernate-types.
- **Next Flyway migration is `V16`.**
- **REST conventions:** `/api/v1/save-games/{saveGameId}/...`, `@CurrentUser UUID userId` for the authenticated user, `ApiException(code, message, HttpStatus)` for errors, records for DTOs.
- **Money is `BigDecimal`** (already true across finance).

---

## File Structure

**New backend files**
- `match/EventType.java` — *modify*: add `CHANCE`, `INJURY`, `SUBSTITUTION`.
- `match/LiveEventType` values reuse `EventType`. No new enum.
- `match/LiveMatchEngine.java` — the sub-aware, deterministic timeline engine (goals/cards/chances/timed-injuries/subs). Pure-ish: depends only on `TeamStrengthCalculator` + `FitnessCalculator`.
- `match/LiveTimeline.java`, `match/LiveTimelineEvent.java`, `match/LiveMatchConfig.java`, `match/PlayerMinutes.java` — engine value objects (records).
- `match/LiveMatch.java`, `match/LiveMatchRepository.java` — the persisted session (JPA entity + repo).
- `match/LiveMatchService.java` — kickoff / resume / advance / substitute / finish orchestration.
- `match/LiveMatchController.java` — the 5 REST endpoints.
- `match/LiveMatchState.java`, `match/LiveEventResponse.java`, `match/LivePlayerResponse.java`, `match/SubstituteRequest.java` — API DTOs.
- `db/migration/V16__live_match.sql` — the `live_matches` table.

**Modified backend files**
- `match/SetLineupRequest.java`, `match/LineupResponse.java` — add `bench`.
- `match/LineupService.java` — persist/return/validate bench.
- `match/FixtureSimulator.java` — `saveEvents` switch guard; `simulateRound(..., Set<UUID> alreadyPlayedElsewhere)` overload; expose a user-fixture outcome helper for `finish`.
- `match/MatchOutcomeApplier.java` — `applyInjuriesFromTimeline(...)`; partial-minutes decay hook.
- `match/FitnessCalculator.java` — `decayedBaseFitness(..., int minutesPlayed)` overload.
- `season/SeasonAdvanceService.java` — extract round-finalisation so `finish` reuses finances/news/season-end bookkeeping.

**New frontend files**
- `src/api/liveMatch.ts` — client for the 5 endpoints.
- `src/features/match/LiveMatchPage.tsx` (+ `.test.tsx`) — the live screen.
- `src/features/match/SubDialog.tsx` — the substitution dialog (may live inside `LiveMatchPage.tsx` if small).

**Modified frontend files**
- `src/api/types.ts` — `LiveMatchState`, `LiveEvent`, `LivePlayer`, `SubstituteRequest`, and `bench` on `SetLineupRequest`/`LineupResponse`.
- `src/features/squad/SquadPage.tsx` (+ `.test.tsx`) — bench selection.
- `src/router.tsx` — `/match` route.
- `src/components/Layout.tsx` (+ Layout test if present) — advance forks into the live match; resume guard.

---

## Phase A — Bench (independently shippable)

### Task 1: Persist a bench on the lineup (backend)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SetLineupRequest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (`setLineup` ~85-120, `getLineup` ~126-135)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceBenchTest.java` (new) or extend an existing `LineupService` test/IT

**Interfaces:**
- Consumes: existing `LineupStarter(UUID playerId, String role)`, `LineupPlayer.create(lineupId, playerId, starting, role)`, `validateAndParseRoles`, `validatePlayersOwnedByClub`, `validateNoIneligible`.
- Produces: `SetLineupRequest(String formation, List<LineupStarter> starters, List<LineupStarter> bench)`; `LineupResponse(UUID lineupId, UUID clubId, String formation, List<LineupStarter> starters, List<LineupStarter> bench)`. Bench is optional (may be null/empty), ≤ 9, distinct from starters, all owned by the club and eligible, roles valid for position.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceBenchTest.java`. Use the same setup as the existing `LineupService` tests (look at `LineupServiceTest` if present for the fixture-building helpers; otherwise this is an `@SpringBootTest` + Testcontainers IT following the project's existing lineup IT). The test must:

```java
// Given a saved lineup with 11 starters and 3 bench players,
// getLineup returns those 3 bench players; a 10th bench player is rejected.
@Test
void benchIsPersistedAndReturned() {
    // ... build a club with >= 20 players in the save (existing seed helper) ...
    SetLineupRequest req = new SetLineupRequest("4-3-3", elevenStarters, threeBench);
    lineupService.setLineup(saveGameId, clubId, req, userId);

    LineupResponse resp = lineupService.getLineup(saveGameId, clubId, userId).orElseThrow();
    assertThat(resp.bench()).hasSize(3);
    assertThat(resp.bench()).extracting(LineupStarter::playerId)
            .doesNotContainAnyElementsOf(resp.starters().stream().map(LineupStarter::playerId).toList());
}

@Test
void benchOfTenIsRejected() {
    SetLineupRequest req = new SetLineupRequest("4-3-3", elevenStarters, tenBench);
    assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("bench");
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LineupServiceBenchTest' -p backend`
Expected: FAIL to compile — `SetLineupRequest` has no 3-arg constructor / `LineupResponse.bench()` does not exist.

- [ ] **Step 3: Add `bench` to the DTOs**

`SetLineupRequest.java` — bench is optional (do NOT annotate `@NotEmpty`):
```java
package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<LineupStarter> starters,
        List<LineupStarter> bench) {

    /** Bench is optional; normalise null to an empty list for callers. */
    public List<LineupStarter> bench() {
        return bench == null ? List.of() : bench;
    }
}
```

`LineupResponse.java`:
```java
package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.UUID;

public record LineupResponse(
        UUID lineupId,
        UUID clubId,
        String formation,
        List<LineupStarter> starters,
        List<LineupStarter> bench
) {
}
```

- [ ] **Step 4: Persist and validate the bench in `setLineup`**

In `LineupService.setLineup`, after `Map<UUID, PlayerRole> resolvedRoles = validateAndParseRoles(starters, clubPlayers);`, add bench validation and change persistence. Replace the block from `List<LineupStarter> starters = req.starters();` through the `return` with:

```java
    List<LineupStarter> starters = req.starters();
    List<LineupStarter> bench = req.bench();
    List<UUID> startingIds = starters == null ? List.of()
            : starters.stream().map(LineupStarter::playerId).toList();
    List<UUID> benchIds = bench.stream().map(LineupStarter::playerId).toList();

    validatePlayerCount(startingIds);
    validateBench(startingIds, benchIds);

    List<Player> clubPlayers = playerRepository.findByClubId(clubId);
    validatePlayersOwnedByClub(startingIds, clubPlayers);
    validatePlayersOwnedByClub(benchIds, clubPlayers);
    validateGkCount(startingIds, clubPlayers);
    validateNoIneligible(startingIds, clubPlayers);
    validateNoIneligible(benchIds, clubPlayers);
    Map<UUID, PlayerRole> starterRoles = validateAndParseRoles(starters, clubPlayers);
    Map<UUID, PlayerRole> benchRoles = validateAndParseRoles(bench, clubPlayers);

    Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).orElse(null);
    if (lineup == null) {
        lineup = Lineup.create(saveGameId, clubId, req.formation());
        lineupRepository.save(lineup);
    } else {
        lineup.setFormation(req.formation());
        lineupRepository.save(lineup);
        lineupPlayerRepository.deleteByLineupId(lineup.getId());
    }

    final UUID lineupId = lineup.getId();
    for (LineupStarter starter : starters) {
        lineupPlayerRepository.save(
                LineupPlayer.create(lineupId, starter.playerId(), true, starterRoles.get(starter.playerId())));
    }
    for (LineupStarter sub : bench) {
        lineupPlayerRepository.save(
                LineupPlayer.create(lineupId, sub.playerId(), false, benchRoles.get(sub.playerId())));
    }

    return new LineupResponse(lineupId, clubId, req.formation(), List.copyOf(starters), List.copyOf(bench));
```

Add the `validateBench` helper next to the other private validators:
```java
    private void validateBench(List<UUID> startingIds, List<UUID> benchIds) {
        if (benchIds.size() > 9) {
            throw new ApiException("INVALID_LINEUP",
                    "The bench may hold at most 9 players, but got " + benchIds.size(),
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        if (Set.copyOf(benchIds).size() != benchIds.size()) {
            throw new ApiException("INVALID_LINEUP",
                    "The bench contains duplicate players", HttpStatus.UNPROCESSABLE_CONTENT);
        }
        Set<UUID> starting = Set.copyOf(startingIds);
        List<UUID> overlap = benchIds.stream().filter(starting::contains).toList();
        if (!overlap.isEmpty()) {
            throw new ApiException("INVALID_LINEUP",
                    "These players are both starting and on the bench: " + overlap,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
```

- [ ] **Step 5: Return the bench from `getLineup`**

In `LineupService.getLineup`, replace the `.map(lineup -> { ... })` body so it reads both starting and bench rows:
```java
    public Optional<LineupResponse> getLineup(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> {
                    List<LineupPlayer> rows = lineupPlayerRepository.findByLineupId(lineup.getId());
                    List<LineupStarter> starters = rows.stream()
                            .filter(LineupPlayer::isStarting)
                            .map(lp -> new LineupStarter(lp.getPlayerId(), lp.getRole().name()))
                            .toList();
                    List<LineupStarter> bench = rows.stream()
                            .filter(lp -> !lp.isStarting())
                            .map(lp -> new LineupStarter(lp.getPlayerId(), lp.getRole().name()))
                            .toList();
                    return new LineupResponse(lineup.getId(), clubId, lineup.getFormation(), starters, bench);
                });
    }
```

Note: `startingXi`, `savedIneligibleStarters`, `lowFitnessStarters`, and `currentRoles` already filter `LineupPlayer::isStarting` (or are role-only), so bench rows do not leak into the XI. Leave them unchanged.

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LineupServiceBenchTest' -p backend`
Expected: PASS.

- [ ] **Step 7: Fix the one existing caller of `new LineupResponse(...)` / `new SetLineupRequest(...)`**

Search and update any test or code constructing these records with the old arity:
Run: `grep -rn "new LineupResponse(" backend/src ; grep -rn "new SetLineupRequest(" backend/src`
For each hit, add the new argument (`, List.of()` for an empty bench, or a real list). Re-run the full match test package:
Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*' -p backend`
Expected: PASS.

- [ ] **Step 8: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SetLineupRequest.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LineupResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceBenchTest.java
git commit -m "feat(lineup): persist an optional 9-man bench alongside the starting XI"
```

---

### Task 2: Bench selection on the squad screen (frontend)

**Files:**
- Modify: `frontend/src/api/types.ts` (`SetLineupRequest`, `LineupResponse`)
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Test: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Consumes: `getSquad`, `getLineup`, `setLineup` (unchanged signatures); `PlayerResponse`, `LineupStarter`.
- Produces: the squad screen now sends `bench: LineupStarter[]` in `setLineup` and hydrates it from `getLineup`.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/features/squad/SquadPage.test.tsx` (follow the existing mock/render setup in that file):
```tsx
it('sends the selected bench players when saving the lineup', async () => {
  // getSquad already mocked with >= 12 players in beforeEach; ensure at least one
  // eligible non-starter exists (e.g. 'p12' Reserva). Fill a valid XI via "Selecionar melhores".
  renderPage()
  await screen.findByText('Reserva')                       // a known non-starter player name
  fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

  // Add one player to the bench.
  fireEvent.click(screen.getByRole('checkbox', { name: /banco: Reserva/i }))
  fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

  await waitFor(() =>
    expect(setLineup).toHaveBeenCalledWith(
      'sg-1', 'club-1',
      expect.objectContaining({ bench: expect.arrayContaining([expect.objectContaining({ playerId: 'p12' })]) }),
      'test-token',
    ),
  )
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: FAIL — there is no "banco" checkbox and `setLineup` is called without `bench`.

- [ ] **Step 3: Add `bench` to the frontend types**

In `frontend/src/api/types.ts`:
```ts
export type SetLineupRequest = {
  formation: string
  starters: LineupStarter[]
  bench: LineupStarter[]
}

export type LineupResponse = {
  lineupId: string
  clubId: string
  formation: string
  starters: LineupStarter[]
  bench: LineupStarter[]
}
```

- [ ] **Step 4: Add bench state + UI + save wiring in `SquadPage.tsx`**

Add bench state near the other `useState`s (after `const [selected, setSelected] = ...`):
```tsx
  const [bench, setBench] = useState<string[]>([])
```

Hydrate it in the existing `useEffect` that seeds from the saved lineup — inside `if (lineup) { ... }` add:
```tsx
      setBench((lineup.bench ?? []).map(s => s.playerId))
```

Send it in the save mutation — change the `mutationFn` body:
```tsx
    mutationFn: () => {
      const toStarter = (id: string): LineupStarter => {
        const player = players.find(p => p.id === id)!
        return { playerId: id, role: roles[id] ?? DEFAULT_ROLE[player.position] }
      }
      const starters: LineupStarter[] = selected.map(toStarter)
      const benchStarters: LineupStarter[] = bench.map(toStarter)
      return setLineup(saveGameId, clubId, { formation, starters, bench: benchStarters }, token ?? '')
    },
```

Add a bench toggle handler (near `togglePlayer`):
```tsx
  // Bench: any eligible player not in the XI, up to 9. Removing from the XI also drops
  // them from the bench; selecting a benched player into the XI removes them from the bench.
  function toggleBench(player: PlayerResponse) {
    setSaveSuccess(false)
    if (bench.includes(player.id)) {
      setBench(prev => prev.filter(id => id !== player.id))
      return
    }
    if (!player.eligible || selected.includes(player.id) || bench.length >= 9) return
    setBench(prev => [...prev, player.id])
  }
```

Keep the XI and bench mutually exclusive: in `togglePlayer`, when a player is *added* to the XI, also drop them from the bench. Add `setBench(prev => prev.filter(id => id !== player.id))` right before `setSelected(prev => [...prev, player.id])` in the "Adding" branch.

Render a bench section after the starters table/cards and before the "Salvar escalação" button (inside the `<Collapsible title="Escalação">`), listing eligible non-starters with a bench checkbox:
```tsx
        <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>
          Banco de reservas — {bench.length}/9
        </Typography>
        <Stack spacing={1} sx={{ mb: 2 }}>
          {displayPlayers
            .filter(p => !selected.includes(p.id))
            .map(player => {
              const onBench = bench.includes(player.id)
              return (
                <Stack key={player.id} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Checkbox
                    size="small"
                    checked={onBench}
                    disabled={!onBench && (!player.eligible || bench.length >= 9)}
                    onChange={() => toggleBench(player)}
                    slotProps={{ input: { 'aria-label': `Banco: ${player.name}` } }}
                    sx={{ p: 0.5 }}
                  />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    {playerNameButton(player)}
                    {availabilityBadge(player)}
                  </Box>
                  <Chip size="small" label={positionLabel(player.position)} />
                  <Chip size="small" color="primary" label={`OVR ${player.overall}`} />
                  <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
                </Stack>
              )
            })}
        </Stack>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: PASS. Then typecheck: `cd frontend && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add frontend/src/api/types.ts frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(squad): pick up to 9 bench players and save them with the lineup"
```

---

## Phase B — Live match engine + session

### Task 3: New event types (keep the instant engine compiling)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/EventType.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (`saveEvents` switch ~185-190)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java` (new)

**Interfaces:**
- Produces: `EventType { GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE, INJURY, SUBSTITUTION }`. The instant path never emits the three new values.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java`:
```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventTypeTest {
    @Test
    void definesLiveOnlyEventTypes() {
        assertThat(EventType.valueOf("CHANCE")).isNotNull();
        assertThat(EventType.valueOf("INJURY")).isNotNull();
        assertThat(EventType.valueOf("SUBSTITUTION")).isNotNull();
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*EventTypeTest' -p backend`
Expected: FAIL — `No enum constant EventType.CHANCE`.

- [ ] **Step 3: Add the values**

`EventType.java`:
```java
package br.com.carvoeiromanager.match;

public enum EventType {
    GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD, CHANCE, INJURY, SUBSTITUTION
}
```

- [ ] **Step 4: Keep the instant path's exhaustive switch compiling**

`FixtureSimulator.saveEvents` has a switch expression over `event.type()` with no `default`. Adding enum constants breaks it. Update the switch so the instant path rejects live-only types (it never produces them):
```java
            Player player = switch (event.type()) {
                case GOAL -> pickWeighted(xi, Player::getFinishing, rng);
                case SET_PIECE_GOAL -> setPieceTaker(xi, profile, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
```

- [ ] **Step 5: Run tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*' -p backend`
Expected: PASS (existing simulation tests still pass; new EventTypeTest passes).

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/EventType.java \
  backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
  backend/src/test/java/br/com/carvoeiromanager/match/EventTypeTest.java
git commit -m "feat(match): add CHANCE/INJURY/SUBSTITUTION event types for the live engine"
```

---

### Task 4: `LiveMatchEngine` — deterministic, sub-aware match timeline

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveSub.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/TimedInjury.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimeline.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`

**Interfaces:**
- Consumes: `TeamStrengthCalculator.compute(List<Player>, TacticalProfile, Map<UUID,Double>)`, `FitnessCalculator.fitnessMult(int)` / `injuryChance(int,int)`, `InjurySeverity.roll(rng)` / `layoffMatches(rng)`, `Player` getters, `TacticalProfile`.
- Produces:
  - `LiveSub(int minute, boolean home, UUID outId, UUID inId)`
  - `LiveMatchConfig(long seed, List<Player> homeXi, List<Player> awayXi, List<Player> homeBench, List<Player> awayBench, TacticalProfile homeProfile, TacticalProfile awayProfile, Map<UUID,Integer> fitness, List<LiveSub> subSchedule)`
  - `LiveTimelineEvent(int minute, EventType type, boolean home, UUID playerId, UUID playerInId, String severity)`
  - `TimedInjury(UUID playerId, boolean home, int minute, InjurySeverity severity, int layoffMatches)`
  - `LiveTimeline(int homeGoals, int awayGoals, List<LiveTimelineEvent> events, Map<UUID,Integer> minutesPlayed, List<TimedInjury> injuries)` with helpers `eventsThrough(int)`, `homeGoalsAt(int)`, `awayGoalsAt(int)`, `nextEventMinute(int)`
  - `LiveMatchEngine.simulate(LiveMatchConfig) -> LiveTimeline`

- [ ] **Step 1: Write the value objects**

`LiveSub.java`:
```java
package br.com.carvoeiromanager.match;

import java.util.UUID;

/** One substitution: {@code inId} replaces {@code outId} at {@code minute} for the {@code home} side. */
public record LiveSub(int minute, boolean home, UUID outId, UUID inId) {
}
```

`LiveMatchConfig.java`:
```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable inputs for a full live-match simulation. {@code fitness} is the effective fitness at
 * kickoff for EVERY player who might appear (both XIs and both benches). {@code subSchedule} is the
 * ordered list of substitutions applied so far; the timeline is a pure function of these inputs.
 */
public record LiveMatchConfig(
        long seed,
        List<Player> homeXi,
        List<Player> awayXi,
        List<Player> homeBench,
        List<Player> awayBench,
        TacticalProfile homeProfile,
        TacticalProfile awayProfile,
        Map<UUID, Integer> fitness,
        List<LiveSub> subSchedule) {
}
```

`LiveTimelineEvent.java`:
```java
package br.com.carvoeiromanager.match;

import java.util.UUID;

/**
 * One timeline entry. {@code playerId} is the actor (scorer / booked / injured / player coming OFF).
 * {@code playerInId} is the player coming ON — only for {@code SUBSTITUTION}, else null.
 * {@code severity} is the {@link br.com.carvoeiromanager.player.InjurySeverity} name — only for
 * {@code INJURY}, else null.
 */
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity) {
}
```

`TimedInjury.java`:
```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;

import java.util.UUID;

/** An injury resolved during the match; its layoff is applied post-match at finish. */
public record TimedInjury(UUID playerId, boolean home, int minute, InjurySeverity severity, int layoffMatches) {
}
```

`LiveTimeline.java`:
```java
package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The full 90' of a live match. Callers reveal only the past via {@link #eventsThrough(int)}. */
public record LiveTimeline(
        int homeGoals,
        int awayGoals,
        List<LiveTimelineEvent> events,
        Map<UUID, Integer> minutesPlayed,
        List<TimedInjury> injuries) {

    private static boolean isGoal(EventType t) {
        return t == EventType.GOAL || t == EventType.SET_PIECE_GOAL;
    }

    public List<LiveTimelineEvent> eventsThrough(int throughMinute) {
        return events.stream().filter(e -> e.minute() <= throughMinute).toList();
    }

    public int homeGoalsAt(int minute) {
        return (int) events.stream()
                .filter(e -> e.home() && isGoal(e.type()) && e.minute() <= minute).count();
    }

    public int awayGoalsAt(int minute) {
        return (int) events.stream()
                .filter(e -> !e.home() && isGoal(e.type()) && e.minute() <= minute).count();
    }

    /** Minute of the first event strictly after {@code fromMinute}, or 90 when none remain. */
    public int nextEventMinute(int fromMinute) {
        return events.stream().map(LiveTimelineEvent::minute)
                .filter(m -> m > fromMinute).min(Integer::compareTo).orElse(90);
    }
}
```

- [ ] **Step 2: Write the failing engine test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`. Build 11 `Player`s per side plus a home bench, using the project's existing `Player` test factory (search `Player.create(` in `backend/src/test`; reuse the same helper the existing `MatchSimulationServiceTest` / `TeamStrengthCalculatorTest` use). Keep attributes fixed so assertions are stable. Provide `config(seed, subs)` building a `LiveMatchConfig` with a default `TacticalProfile`, a fitness map of every player → 100, and empty away bench.

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveMatchEngineTest {

    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator());

    // homeXi, awayXi, homeBench and config(...) built via the shared Player test factory.

    @Test
    void sameInputsProduceIdenticalTimelines() {
        LiveMatchConfig cfg = config(1234L, List.of());
        LiveTimeline a = engine.simulate(cfg);
        LiveTimeline b = engine.simulate(cfg);
        assertThat(a.events()).isEqualTo(b.events());
        assertThat(a.homeGoals()).isEqualTo(b.homeGoals());
    }

    @Test
    void aSubChangesTheFutureButNotThePast() {
        LiveTimeline base = engine.simulate(config(99L, List.of()));
        UUID out = homeXi.get(5).getId();
        UUID in = homeBench.get(0).getId();
        LiveTimeline subbed = engine.simulate(config(99L, List.of(new LiveSub(60, true, out, in))));

        assertThat(subbed.eventsThrough(59)).isEqualTo(base.eventsThrough(59));
        assertThat(subbed.events()).anyMatch(e ->
                e.type() == EventType.SUBSTITUTION && e.minute() == 60
                        && e.playerId().equals(out) && e.playerInId().equals(in));
        assertThat(subbed.minutesPlayed().get(out)).isEqualTo(60);
        assertThat(subbed.minutesPlayed().get(in)).isEqualTo(30);
    }

    @Test
    void startersWhoPlayTheWholeMatchLogNinetyMinutes() {
        LiveTimeline t = engine.simulate(config(7L, List.of()));
        assertThat(t.minutesPlayed().get(homeXi.get(0).getId())).isEqualTo(90);
    }

    @Test
    void injuriesAreTimedAndCarryASeverity() {
        LiveTimeline t = engine.simulate(config(4242L, List.of()));
        t.injuries().forEach(inj -> {
            assertThat(inj.minute()).isBetween(1, 90);
            assertThat(inj.layoffMatches()).isGreaterThan(0);
        });
        t.events().stream().filter(e -> e.type() == EventType.INJURY)
                .forEach(e -> assertThat(e.severity()).isNotNull());
    }
}
```

- [ ] **Step 3: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchEngineTest' -p backend`
Expected: FAIL — `LiveMatchEngine` does not exist.

- [ ] **Step 4: Implement `LiveMatchEngine`**

`LiveMatchEngine.java`. The per-minute goal/card formulas mirror `MatchSimulationService` exactly (kept as a deliberate separate copy so the proven instant engine is never touched). Fitness is baked into strength at kickoff and on every sub; within-match stamina still decays per minute via each side's `fitnessDecay` (mirrored). Fixed per-minute draw order: **home goal, home goal-type, away goal, away goal-type, home card, home card-type, away card, away card-type, home chance, away chance**; injuries use a dedicated `injuryRng` after the minute's play; scorer/booking attribution uses a dedicated `attrRng`.

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Deterministic, substitution-aware live-match engine. A pure function of
 * {@link LiveMatchConfig}: identical {@code (seed, subSchedule)} yields an identical timeline.
 * Kept fully separate from {@link MatchSimulationService} so the instant engine (used for all
 * AI/other-club fixtures) stays byte-for-byte unchanged.
 */
@Service
public class LiveMatchEngine {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;
    private static final double FITNESS_FLOOR = 0.75;
    private static final double SET_PIECE_SHARE = 0.18;
    private static final double BASE_CARD_RATE = 0.02;
    private static final double RED_SHARE = 0.06;
    private static final double CHANCE_RATE = 0.12;   // flavor near-miss/save rate per side per minute
    private static final double HOBBLE_MULT = 0.5;    // an injured player who plays on contributes at half

    private final TeamStrengthCalculator teamStrengthCalculator;
    private final FitnessCalculator fitnessCalculator;

    public LiveMatchEngine(TeamStrengthCalculator teamStrengthCalculator, FitnessCalculator fitnessCalculator) {
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.fitnessCalculator = fitnessCalculator;
    }

    /** Mutable on-pitch bookkeeping for one side. */
    private static final class Side {
        final boolean home;
        final Map<UUID, Player> all = new HashMap<>();     // XI + bench, by id
        final List<UUID> onPitch = new ArrayList<>();      // current 10/11 on the field
        final Map<UUID, Integer> entryMinute = new HashMap<>();
        final Map<UUID, Integer> exitMinute = new HashMap<>();
        final Map<UUID, Boolean> hobbled = new HashMap<>();
        boolean dirty = true;

        Side(boolean home) { this.home = home; }
    }

    public LiveTimeline simulate(LiveMatchConfig cfg) {
        Random rng = new Random(cfg.seed());
        Random attrRng = new Random(cfg.seed() ^ 0x5DEECE66DL);
        Random injuryRng = new Random(cfg.seed() ^ 0x1A70F1E5L);

        Side home = buildSide(true, cfg.homeXi(), cfg.homeBench());
        Side away = buildSide(false, cfg.awayXi(), cfg.awayBench());

        List<LiveTimelineEvent> events = new ArrayList<>();
        List<TimedInjury> injuries = new ArrayList<>();

        TeamMatchInput homeInput = strength(home, cfg.homeProfile(), cfg);
        TeamMatchInput awayInput = strength(away, cfg.awayProfile(), cfg);

        for (int minute = 1; minute <= MINUTES; minute++) {
            applySubs(cfg, minute, home, events);
            applySubs(cfg, minute, away, events);
            if (home.dirty) { homeInput = strength(home, cfg.homeProfile(), cfg); home.dirty = false; }
            if (away.dirty) { awayInput = strength(away, cfg.awayProfile(), cfg); away.dirty = false; }

            double tempo = (homeInput.tempo() + awayInput.tempo()) / 2.0;
            double homeFf = fitnessFactor(homeInput.fitnessDecay(), minute);
            double awayFf = fitnessFactor(awayInput.fitnessDecay(), minute);

            double homeChance = perMinuteScoreChance(homeInput.attack() * homeFf + HOME_ADVANTAGE,
                    awayInput.defense() * awayFf) * tempo;
            double awayChance = perMinuteScoreChance(awayInput.attack() * awayFf,
                    homeInput.defense() * homeFf + HOME_ADVANTAGE) * tempo;

            if (rng.nextDouble() < homeChance) {
                EventType t = rng.nextDouble() < setPiece(homeInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, home, cfg.homeProfile(), t, attrRng));
            }
            if (rng.nextDouble() < awayChance) {
                EventType t = rng.nextDouble() < setPiece(awayInput) ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(goal(minute, away, cfg.awayProfile(), t, attrRng));
            }
            maybeCard(minute, home, homeInput, rng, attrRng, events);
            maybeCard(minute, away, awayInput, rng, attrRng, events);

            if (rng.nextDouble() < CHANCE_RATE && !home.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, true,
                        pickWeighted(home, Player::getFinishing, attrRng), null, null));
            }
            if (rng.nextDouble() < CHANCE_RATE && !away.onPitch.isEmpty()) {
                events.add(new LiveTimelineEvent(minute, EventType.CHANCE, false,
                        pickWeighted(away, Player::getFinishing, attrRng), null, null));
            }

            maybeInjury(minute, home, cfg, injuryRng, events, injuries);
            maybeInjury(minute, away, cfg, injuryRng, events, injuries);
        }

        finalizeMinutes(home);
        finalizeMinutes(away);
        events.sort(Comparator.comparingInt(LiveTimelineEvent::minute));

        int hg = (int) events.stream().filter(e -> e.home() && isGoal(e.type())).count();
        int ag = (int) events.stream().filter(e -> !e.home() && isGoal(e.type())).count();

        Map<UUID, Integer> minutes = new LinkedHashMap<>();
        putMinutes(home, minutes);
        putMinutes(away, minutes);
        return new LiveTimeline(hg, ag, List.copyOf(events), Map.copyOf(minutes), List.copyOf(injuries));
    }

    // ---- side setup & minutes ----

    private Side buildSide(boolean home, List<Player> xi, List<Player> bench) {
        Side s = new Side(home);
        xi.forEach(p -> { s.all.put(p.getId(), p); s.onPitch.add(p.getId()); s.entryMinute.put(p.getId(), 0); });
        bench.forEach(p -> s.all.put(p.getId(), p));
        return s;
    }

    private void finalizeMinutes(Side s) {
        for (UUID id : s.entryMinute.keySet()) {
            s.exitMinute.putIfAbsent(id, MINUTES);
        }
    }

    private void putMinutes(Side s, Map<UUID, Integer> out) {
        for (UUID id : s.entryMinute.keySet()) {
            int played = Math.max(0, s.exitMinute.getOrDefault(id, MINUTES) - s.entryMinute.get(id));
            out.put(id, played);
        }
    }

    // ---- substitutions ----

    private void applySubs(LiveMatchConfig cfg, int minute, Side side, List<LiveTimelineEvent> events) {
        for (LiveSub sub : cfg.subSchedule()) {
            if (sub.minute() != minute || sub.home() != side.home) continue;
            if (!side.onPitch.contains(sub.outId()) || !side.all.containsKey(sub.inId())) continue;
            int idx = side.onPitch.indexOf(sub.outId());
            side.onPitch.set(idx, sub.inId());
            side.exitMinute.put(sub.outId(), minute);
            side.entryMinute.put(sub.inId(), minute);
            side.dirty = true;
            events.add(new LiveTimelineEvent(minute, EventType.SUBSTITUTION, side.home,
                    sub.outId(), sub.inId(), null));
        }
    }

    // ---- strength (recomputed on change), hobble ×0.5, red-card = fewer players ----

    private TeamMatchInput strength(Side side, TacticalProfile profile, LiveMatchConfig cfg) {
        List<Player> xi = side.onPitch.stream().map(side.all::get).toList();
        Map<UUID, Double> mult = new HashMap<>();
        for (UUID id : side.onPitch) {
            int fit = cfg.fitness().getOrDefault(id, 100);
            double m = fitnessCalculator.fitnessMult(fit);
            if (Boolean.TRUE.equals(side.hobbled.get(id))) m *= HOBBLE_MULT;
            mult.put(id, m);
        }
        return teamStrengthCalculator.compute(xi, profile, mult);
    }

    // ---- cards, goals, injuries, attribution ----

    private void maybeCard(int minute, Side side, TeamMatchInput input, Random rng, Random attrRng,
                           List<LiveTimelineEvent> events) {
        if (rng.nextDouble() < BASE_CARD_RATE * input.aggression() && !side.onPitch.isEmpty()) {
            EventType t = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
            UUID booked = pickWeighted(side, Player::getTackling, attrRng);
            events.add(new LiveTimelineEvent(minute, t, side.home, booked, null, null));
            if (t == EventType.RED_CARD) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.dirty = true;
            }
        }
    }

    private LiveTimelineEvent goal(int minute, Side side, TacticalProfile profile, EventType type, Random attrRng) {
        UUID scorer = type == EventType.SET_PIECE_GOAL ? setPieceTaker(side, profile, attrRng)
                : pickWeighted(side, Player::getFinishing, attrRng);
        return new LiveTimelineEvent(minute, type, side.home, scorer, null, null);
    }

    private void maybeInjury(int minute, Side side, LiveMatchConfig cfg, Random injuryRng,
                             List<LiveTimelineEvent> events, List<TimedInjury> injuries) {
        if (side.onPitch.isEmpty()) return;
        // Aggregate the per-player match injury chance into a per-minute team chance, preserving the
        // low-fitness/age bias when picking who gets hurt.
        double teamChance = 0.0;
        for (UUID id : side.onPitch) {
            Player p = side.all.get(id);
            teamChance += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), p.getAge());
        }
        teamChance /= MINUTES;
        if (injuryRng.nextDouble() >= teamChance) return;

        UUID victim = pickInjured(side, cfg, injuryRng);
        if (Boolean.TRUE.equals(side.hobbled.get(victim))) return; // already carrying a knock
        InjurySeverity severity = InjurySeverity.roll(injuryRng);
        int layoff = severity.layoffMatches(injuryRng);
        side.hobbled.put(victim, true);
        side.dirty = true;
        injuries.add(new TimedInjury(victim, side.home, minute, severity, layoff));
        events.add(new LiveTimelineEvent(minute, EventType.INJURY, side.home, victim, null, severity.name()));
    }

    private UUID pickInjured(Side side, LiveMatchConfig cfg, Random rng) {
        double total = 0.0;
        for (UUID id : side.onPitch) {
            total += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
        }
        double pick = rng.nextDouble() * total;
        double acc = 0.0;
        for (UUID id : side.onPitch) {
            acc += fitnessCalculator.injuryChance(cfg.fitness().getOrDefault(id, 100), side.all.get(id).getAge());
            if (pick < acc) return id;
        }
        return side.onPitch.get(side.onPitch.size() - 1);
    }

    private UUID setPieceTaker(Side side, TacticalProfile profile, Random rng) {
        UUID takerId = profile.penaltyTakerId() != null ? profile.penaltyTakerId() : profile.freekickTakerId();
        if (takerId != null && side.onPitch.contains(takerId)) return takerId;
        return pickWeighted(side, Player::getFinishing, rng);
    }

    private UUID pickWeighted(Side side, ToIntFunction<Player> attribute, Random rng) {
        List<UUID> xi = side.onPitch;
        int totalWeight = xi.stream().mapToInt(id -> attribute.applyAsInt(side.all.get(id)) + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int acc = 0;
        for (UUID id : xi) {
            acc += attribute.applyAsInt(side.all.get(id)) + 1;
            if (pick < acc) return id;
        }
        return xi.get(xi.size() - 1);
    }

    // ---- math mirrored from MatchSimulationService ----

    private static boolean isGoal(EventType t) {
        return t == EventType.GOAL || t == EventType.SET_PIECE_GOAL;
    }

    private double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    private double setPiece(TeamMatchInput team) {
        return Math.max(0.0, Math.min(0.6, SET_PIECE_SHARE * (0.5 + team.setPieceThreat())));
    }

    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
```

- [ ] **Step 5: Run the tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchEngineTest' -p backend`
Expected: PASS. If `aSubChangesTheFutureButNotThePast` shows a past-event diff, the bug is applying a sub or recomputing strength affecting minutes ≤ the sub minute; verify `applySubs` runs at the top of the minute and `strength` is recomputed only on `dirty`.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveSub.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java \
  backend/src/main/java/br/com/carvoeiromanager/match/TimedInjury.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveTimeline.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java
git commit -m "feat(match): deterministic sub-aware live match engine with timed injuries"
```

---

### Task 5: `LiveMatch` session entity + repository + migration

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatch.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchRepository.java`
- Create: `backend/src/main/resources/db/migration/V16__live_match.sql`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchRepositoryIT.java`

**Interfaces:**
- Produces: `LiveMatch` entity (JSON snapshots stored as `TEXT`); `LiveMatchRepository.findBySaveGameId(UUID)`, `deleteBySaveGameId(UUID)`. The service (Task 6) serializes `home_xi`/`away_xi`/`home_bench`/`away_bench` (JSON `List<UUID>`) and `sub_schedule` (JSON `List<LiveSub>`).

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V16__live_match.sql`:
```sql
-- One in-progress live match per save game. Deleted on finish; cascades on account/save deletion.
CREATE TABLE live_matches (
    id                UUID PRIMARY KEY,
    save_game_id      UUID NOT NULL UNIQUE REFERENCES save_games(id) ON DELETE CASCADE,
    fixture_id        UUID NOT NULL,
    competition_id    UUID NOT NULL,
    round             INT NOT NULL,
    match_date        DATE NOT NULL,
    seed              BIGINT NOT NULL,
    managed_is_home   BOOLEAN NOT NULL,
    committed_minute  INT NOT NULL DEFAULT 0,
    home_xi           TEXT NOT NULL,
    away_xi           TEXT NOT NULL,
    home_bench        TEXT NOT NULL,
    away_bench        TEXT NOT NULL,
    sub_schedule      TEXT NOT NULL
);
```

- [ ] **Step 2: Write the failing repository test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchRepositoryIT.java` — follow the project's existing repository IT style (`@DataJpaTest` or `@SpringBootTest` + Testcontainers Postgres; copy the annotations from an existing `*RepositoryIT`/`*IT` in `backend/src/test`).
```java
@Test
void persistsAndFindsBySaveGameId() {
    UUID saveGameId = /* an existing save in the test DB, or a seeded one */;
    LiveMatch lm = LiveMatch.create(saveGameId, fixtureId, competitionId, 3, LocalDate.of(2026, 3, 1),
            123L, true, "[]", "[]", "[]", "[]", "[]");
    liveMatchRepository.save(lm);

    assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isPresent();
    liveMatchRepository.deleteBySaveGameId(saveGameId);
    assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isEmpty();
}
```

- [ ] **Step 3: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchRepositoryIT' -p backend`
Expected: FAIL — `LiveMatch` / `LiveMatchRepository` do not exist.

- [ ] **Step 4: Implement the entity**

`LiveMatch.java`:
```java
package br.com.carvoeiromanager.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** Server-authoritative live-match session. JSON snapshots are stored as TEXT (see NewsItem). */
@Entity
@Table(name = "live_matches")
public class LiveMatch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false, unique = true)
    private UUID saveGameId;

    @Column(name = "fixture_id", nullable = false)
    private UUID fixtureId;

    @Column(name = "competition_id", nullable = false)
    private UUID competitionId;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "match_date", nullable = false)
    private LocalDate matchDate;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "managed_is_home", nullable = false)
    private boolean managedIsHome;

    @Column(name = "committed_minute", nullable = false)
    private int committedMinute;

    @Column(name = "home_xi", nullable = false, columnDefinition = "TEXT")
    private String homeXi;

    @Column(name = "away_xi", nullable = false, columnDefinition = "TEXT")
    private String awayXi;

    @Column(name = "home_bench", nullable = false, columnDefinition = "TEXT")
    private String homeBench;

    @Column(name = "away_bench", nullable = false, columnDefinition = "TEXT")
    private String awayBench;

    @Column(name = "sub_schedule", nullable = false, columnDefinition = "TEXT")
    private String subSchedule;

    protected LiveMatch() {
    }

    private LiveMatch(UUID id, UUID saveGameId, UUID fixtureId, UUID competitionId, int round,
                      LocalDate matchDate, long seed, boolean managedIsHome,
                      String homeXi, String awayXi, String homeBench, String awayBench, String subSchedule) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.fixtureId = fixtureId;
        this.competitionId = competitionId;
        this.round = round;
        this.matchDate = matchDate;
        this.seed = seed;
        this.managedIsHome = managedIsHome;
        this.committedMinute = 0;
        this.homeXi = homeXi;
        this.awayXi = awayXi;
        this.homeBench = homeBench;
        this.awayBench = awayBench;
        this.subSchedule = subSchedule;
    }

    public static LiveMatch create(UUID saveGameId, UUID fixtureId, UUID competitionId, int round,
                                   LocalDate matchDate, long seed, boolean managedIsHome,
                                   String homeXi, String awayXi, String homeBench, String awayBench,
                                   String subSchedule) {
        return new LiveMatch(UUID.randomUUID(), saveGameId, fixtureId, competitionId, round, matchDate,
                seed, managedIsHome, homeXi, awayXi, homeBench, awayBench, subSchedule);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getFixtureId() { return fixtureId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getRound() { return round; }
    public LocalDate getMatchDate() { return matchDate; }
    public long getSeed() { return seed; }
    public boolean isManagedIsHome() { return managedIsHome; }
    public int getCommittedMinute() { return committedMinute; }
    public String getHomeXi() { return homeXi; }
    public String getAwayXi() { return awayXi; }
    public String getHomeBench() { return homeBench; }
    public String getAwayBench() { return awayBench; }
    public String getSubSchedule() { return subSchedule; }

    public void setCommittedMinute(int committedMinute) { this.committedMinute = committedMinute; }
    public void setSubSchedule(String subSchedule) { this.subSchedule = subSchedule; }
}
```

`LiveMatchRepository.java`:
```java
package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface LiveMatchRepository extends JpaRepository<LiveMatch, UUID> {
    Optional<LiveMatch> findBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
```

- [ ] **Step 5: Run it — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchRepositoryIT' -p backend`
Expected: PASS.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatch.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchRepository.java \
  backend/src/main/resources/db/migration/V16__live_match.sql \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchRepositoryIT.java
git commit -m "feat(match): live_matches session table, entity and repository"
```

---

### Task 6: `LiveMatchService` — kickoff, resume, state mapping, commentary + API DTOs

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/SubstituteRequest.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveEventResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (this task adds `kickoff` + `getLive`; `advance`/`substitute`/`finish` land in Tasks 7/8/11)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (add `benchPlayers`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java`

**Interfaces:**
- Consumes: `SaveGameService.requireOwned`, `LineupService.startingXi` / `bestXi` / new `benchPlayers`, `TacticsService.profileFor`, `FitnessCalculator.effectiveFitness`, `LiveMatchEngine.simulate`, `CompetitionRepository`, `ClubRepository`, `FixtureRepository.findBySaveGameIdAndCompetitionIdAndRound`, `SeasonEventRepository.findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc`, `PlayerRepository`, `ObjectMapper`.
- Produces:
  - `SubstituteRequest(UUID outPlayerId, UUID inPlayerId)`
  - `LiveEventResponse(int minute, String type, boolean home, String playerId, String playerName, String playerInId, String playerInName, String severity, String text)`
  - `LivePlayerResponse(String playerId, String name, String position, int overall, int fitness, boolean hobbled, boolean onPitch, boolean used, boolean eligible)`
  - `LiveMatchState(String fixtureId, String competitionName, Integer round, String matchDate, String homeClubName, String awayClubName, boolean managedIsHome, int minute, int homeGoals, int awayGoals, String status, List<LiveEventResponse> events, List<LivePlayerResponse> onPitch, List<LivePlayerResponse> bench, int subsRemaining)`
  - `LiveMatchService.kickoff(UUID saveGameId, UUID userId) -> LiveMatchState`
  - `LiveMatchService.getLive(UUID saveGameId, UUID userId) -> Optional<LiveMatchState>`
  - `LineupService.benchPlayers(UUID saveGameId, UUID clubId) -> List<Player>`
- Status values: `IN_PROGRESS`, `PAUSED_INJURY`, `PAUSED_RED`, `FULL_TIME`. Subs cap = 5 (managed side).

- [ ] **Step 1: Add `benchPlayers` to `LineupService`**

```java
    /** The saved bench (starting=false) for a club, resolved to Players; empty when none saved. */
    public List<Player> benchPlayers(UUID saveGameId, UUID clubId) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                        .filter(lp -> !lp.isStarting())
                        .map(lp -> playerRepository.findById(lp.getPlayerId()).orElse(null))
                        .filter(p -> p != null && p.isEligible())
                        .toList())
                .orElseGet(List::of);
    }
```

- [ ] **Step 2: Write the DTO records**

`SubstituteRequest.java`:
```java
package br.com.carvoeiromanager.match;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubstituteRequest(@NotNull UUID outPlayerId, @NotNull UUID inPlayerId) {
}
```

`LiveEventResponse.java`:
```java
package br.com.carvoeiromanager.match;

public record LiveEventResponse(
        int minute,
        String type,
        boolean home,
        String playerId,
        String playerName,
        String playerInId,
        String playerInName,
        String severity,
        String text) {
}
```

`LivePlayerResponse.java`:
```java
package br.com.carvoeiromanager.match;

public record LivePlayerResponse(
        String playerId,
        String name,
        String position,
        int overall,
        int fitness,
        boolean hobbled,
        boolean onPitch,
        boolean used,
        boolean eligible) {
}
```

`LiveMatchState.java`:
```java
package br.com.carvoeiromanager.match;

import java.util.List;

public record LiveMatchState(
        String fixtureId,
        String competitionName,
        Integer round,
        String matchDate,
        String homeClubName,
        String awayClubName,
        boolean managedIsHome,
        int minute,
        int homeGoals,
        int awayGoals,
        String status,
        List<LiveEventResponse> events,
        List<LivePlayerResponse> onPitch,
        List<LivePlayerResponse> bench,
        int subsRemaining) {
}
```

- [ ] **Step 3: Write the commentary helper**

`LiveCommentary.java` — deterministic PT-BR lines; the variant is chosen from `(minute + type.ordinal())` so it is replay-stable (no RNG). Trimmable tail: extend the arrays freely later.
```java
package br.com.carvoeiromanager.match;

/** Deterministic PT-BR commentary. Variant index derives from the event so replay is stable. */
public final class LiveCommentary {

    private LiveCommentary() {
    }

    private static final String[] GOAL = {
            "GOL! %s balança as redes!", "%s marca! Que golaço!", "%s aparece na área e não perdoa!"};
    private static final String[] SET_PIECE = {
            "GOL de bola parada! %s converte!", "%s marca na cobrança!"};
    private static final String[] CHANCE = {
            "%s arrisca e a bola passa perto!", "Grande defesa! %s quase marcou.",
            "%s finaliza, mas para fora!"};
    private static final String[] YELLOW = {"Cartão amarelo para %s.", "%s entra forte e é advertido."};
    private static final String[] RED = {"Cartão vermelho! %s está expulso!", "%s recebe o vermelho e deixa o time com um a menos."};
    private static final String[] INJURY = {"%s cai lesionado no gramado.", "Preocupação: %s sente e precisa de atendimento."};

    /** {@code inName} is only used for substitutions. */
    public static String of(EventType type, String playerName, String inName, int minute) {
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> "Substituição: entra %s, sai %s.".formatted(inName, playerName);
        };
    }

    private static String pick(String[] lines, String name, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name);
    }
}
```

- [ ] **Step 4: Write the failing kickoff test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java` — `@SpringBootTest` + Testcontainers; create a career via the existing helper, save a lineup, then:
```java
@Test
void kickoffCreatesASessionAndIsIdempotent() {
    LiveMatchState first = liveMatchService.kickoff(saveGameId, userId);
    assertThat(first.minute()).isZero();
    assertThat(first.status()).isEqualTo("IN_PROGRESS");
    assertThat(first.onPitch()).hasSize(11);
    assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isPresent();

    // Idempotent: a second kickoff returns the same in-progress session (does not error/duplicate).
    LiveMatchState again = liveMatchService.kickoff(saveGameId, userId);
    assertThat(again.fixtureId()).isEqualTo(first.fixtureId());
    assertThat(liveMatchRepository.findAll()).hasSize(1);
}

@Test
void getLiveReturnsEmptyWhenNoSession() {
    assertThat(liveMatchService.getLive(saveGameId, userId)).isEmpty();
}
```

- [ ] **Step 5: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchServiceKickoffIT' -p backend`
Expected: FAIL — `LiveMatchService` does not exist.

- [ ] **Step 6: Implement `LiveMatchService` (kickoff + getLive + shared state mapping)**

`LiveMatchService.java`. `serialize`/`deserialize` use the injected Jackson `ObjectMapper` (project uses `tools.jackson.databind.ObjectMapper`). Player state is not mutated until `finish`, so re-deriving profiles/fitness from the snapshot at each call is deterministic.
```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LiveMatchService {

    static final int MAX_SUBS = 5;
    static final int MINUTES = 90;

    private final SaveGameService saveGameService;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final FitnessCalculator fitnessCalculator;
    private final LiveMatchEngine liveMatchEngine;
    private final LiveMatchRepository liveMatchRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final CompetitionRepository competitionRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final ObjectMapper objectMapper;

    public LiveMatchService(SaveGameService saveGameService, LineupService lineupService,
                            TacticsService tacticsService, FitnessCalculator fitnessCalculator,
                            LiveMatchEngine liveMatchEngine, LiveMatchRepository liveMatchRepository,
                            SeasonEventRepository seasonEventRepository, FixtureRepository fixtureRepository,
                            CompetitionRepository competitionRepository, ClubRepository clubRepository,
                            PlayerRepository playerRepository, ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.fitnessCalculator = fitnessCalculator;
        this.liveMatchEngine = liveMatchEngine;
        this.liveMatchRepository = liveMatchRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.competitionRepository = competitionRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LiveMatchState kickoff(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Optional<LiveMatch> existing = liveMatchRepository.findBySaveGameId(saveGameId);
        if (existing.isPresent()) {
            return toState(existing.get(), save);   // resume, idempotent
        }

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        if (next == null || next.getType() != SeasonEventType.MATCH) {
            throw new ApiException("NO_MATCH_TO_PLAY", "The next event is not a match", HttpStatus.CONFLICT);
        }
        UUID managedClubId = save.getManagedClubId();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, next.getCompetitionId(), next.getRound());
        Fixture fixture = fixtures.stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .findFirst()
                .orElseThrow(() -> new ApiException("MANAGED_CLUB_NOT_PLAYING",
                        "The managed club does not play in the next round", HttpStatus.CONFLICT));

        boolean managedIsHome = managedClubId.equals(fixture.getHomeClubId());
        UUID homeClubId = fixture.getHomeClubId();
        UUID awayClubId = fixture.getAwayClubId();
        LocalDate matchDate = fixture.getMatchDate();

        List<Player> homeXi = managedIsHome ? lineupService.startingXi(saveGameId, homeClubId)
                : lineupService.bestXi(homeClubId, matchDate);
        List<Player> awayXi = managedIsHome ? lineupService.bestXi(awayClubId, matchDate)
                : lineupService.startingXi(saveGameId, awayClubId);
        List<Player> homeBench = managedIsHome ? lineupService.benchPlayers(saveGameId, homeClubId) : List.of();
        List<Player> awayBench = managedIsHome ? List.of() : lineupService.benchPlayers(saveGameId, awayClubId);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();

        LiveMatch lm = LiveMatch.create(saveGameId, fixture.getId(), next.getCompetitionId(),
                next.getRound(), matchDate, seed, managedIsHome,
                ids(homeXi), ids(awayXi), ids(homeBench), ids(awayBench), "[]");
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    @Transactional(readOnly = true)
    public Optional<LiveMatchState> getLive(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        return liveMatchRepository.findBySaveGameId(saveGameId).map(lm -> toState(lm, save));
    }

    // ---- shared building blocks (reused by advance/substitute/finish) ----

    LiveMatchConfig configFor(LiveMatch lm) {
        List<Player> homeXi = resolve(deserializeIds(lm.getHomeXi()));
        List<Player> awayXi = resolve(deserializeIds(lm.getAwayXi()));
        List<Player> homeBench = resolve(deserializeIds(lm.getHomeBench()));
        List<Player> awayBench = resolve(deserializeIds(lm.getAwayBench()));
        List<LiveSub> subs = deserializeSubs(lm.getSubSchedule());

        Map<UUID, Integer> fitness = new HashMap<>();
        List<Player> everyone = new ArrayList<>();
        everyone.addAll(homeXi); everyone.addAll(awayXi); everyone.addAll(homeBench); everyone.addAll(awayBench);
        for (Player p : everyone) {
            fitness.put(p.getId(), fitnessCalculator.effectiveFitness(
                    p.getFitness(), p.getLastMatchDate(), lm.getMatchDate(), p.getAge()));
        }

        TacticalProfile homeProfile = tacticsService.profileFor(lm.getSaveGameId(),
                clubOf(homeXi), homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(lm.getSaveGameId(),
                clubOf(awayXi), awayXi);

        return new LiveMatchConfig(lm.getSeed(), homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs);
    }

    LiveMatchState toState(LiveMatch lm, SaveGame save) {
        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        int minute = lm.getCommittedMinute();

        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        Map<UUID, Player> byId = indexAll(cfg);
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute()))).toList();

        String status = statusAt(lm, timeline, minute);
        int subsUsed = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(lm.getSaveGameId()).stream()
                .collect(java.util.stream.Collectors.toMap(Club::getId, c -> c));
        String competitionName = competitionRepository.findById(lm.getCompetitionId())
                .map(c -> c.getName()).orElse(null);

        // Managed on-pitch/bench for the sub dialog.
        List<Player> managedXi = lm.isManagedIsHome() ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, minute, managedXi);
        List<LivePlayerResponse> onPitch = managed.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, true, false, managed.hobbled().contains(id))).toList();
        List<LivePlayerResponse> bench = managedBench.stream()
                .map(p -> livePlayer(p, cfg, false, managed.usedIn().contains(p.getId()), false)).toList();

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                clubs.get(fixture.getHomeClubId()).getName(), clubs.get(fixture.getAwayClubId()).getName(),
                lm.isManagedIsHome(), minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed);
    }

    /** Managed side's current on-pitch set, hobbled set, and bench-players-already-used, at {@code minute}. */
    private record OnPitch(List<UUID> onPitch, Set<UUID> hobbled, Set<UUID> usedIn) {
    }

    private OnPitch onPitchManaged(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> managedXi) {
        List<UUID> onPitch = new ArrayList<>(managedXi.stream().map(Player::getId).toList());
        Set<UUID> hobbled = new LinkedHashSet<>();
        Set<UUID> usedIn = new LinkedHashSet<>();
        for (LiveTimelineEvent e : timeline.eventsThrough(minute)) {
            if (e.home() != lm.isManagedIsHome()) continue;
            switch (e.type()) {
                case SUBSTITUTION -> {
                    onPitch.remove(e.playerId());
                    onPitch.add(e.playerInId());
                    usedIn.add(e.playerInId());
                }
                case RED_CARD -> onPitch.remove(e.playerId());
                case INJURY -> hobbled.add(e.playerId());
                default -> { }
            }
        }
        return new OnPitch(onPitch, hobbled, usedIn);
    }

    private String statusAt(LiveMatch lm, LiveTimeline timeline, int minute) {
        if (minute >= MINUTES) return "FULL_TIME";
        // A forced pause happens when the LAST revealed event is a managed-side injury or red card.
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        for (int i = revealed.size() - 1; i >= 0; i--) {
            LiveTimelineEvent e = revealed.get(i);
            if (e.minute() != minute) break;
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.INJURY) return "PAUSED_INJURY";
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.RED_CARD) return "PAUSED_RED";
        }
        return "IN_PROGRESS";
    }

    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible());
    }

    // ---- helpers ----

    private String ids(List<Player> players) {
        return serialize(players.stream().map(p -> p.getId().toString()).toList());
    }

    private List<Player> resolve(List<UUID> ids) {
        return ids.stream().map(id -> playerRepository.findById(id).orElseThrow(() ->
                new ApiException("PLAYER_NOT_FOUND", "Player " + id + " not found", HttpStatus.NOT_FOUND))).toList();
    }

    private UUID clubOf(List<Player> xi) {
        return xi.get(0).getClubId();
    }

    private Map<UUID, Player> indexAll(LiveMatchConfig cfg) {
        Map<UUID, Player> m = new HashMap<>();
        cfg.homeXi().forEach(p -> m.put(p.getId(), p));
        cfg.awayXi().forEach(p -> m.put(p.getId(), p));
        cfg.homeBench().forEach(p -> m.put(p.getId(), p));
        cfg.awayBench().forEach(p -> m.put(p.getId(), p));
        return m;
    }

    private String nameOf(Map<UUID, Player> byId, UUID id) {
        Player p = byId.get(id);
        return p == null ? "" : p.getName();
    }

    String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize live-match data", e); }
    }

    private List<UUID> deserializeIds(String json) {
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return raw.stream().map(UUID::fromString).toList();
        } catch (Exception e) { throw new RuntimeException("Failed to read live-match ids", e); }
    }

    List<LiveSub> deserializeSubs(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<LiveSub>>() { }); }
        catch (Exception e) { throw new RuntimeException("Failed to read sub schedule", e); }
    }
}
```

- [ ] **Step 7: Run the tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchServiceKickoffIT' -p backend`
Expected: PASS.

- [ ] **Step 8: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SubstituteRequest.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveEventResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java
git commit -m "feat(match): live-match kickoff, resume and state mapping with commentary"
```

---

### Task 7: `LiveMatchService.advance` — reveal the next event

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchAdvanceIT.java`

**Interfaces:**
- Consumes: `configFor`, `toState`, `LiveTimeline.nextEventMinute`, `LiveMatchRepository`.
- Produces: `LiveMatchService.advance(UUID saveGameId, UUID userId) -> LiveMatchState`. `committed_minute` is monotonic; each call jumps to the minute of the next event (or 90 = full time). Never reveals beyond the new `committed_minute`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchAdvanceIT.java` (kickoff first, then advance):
```java
@Test
void advanceRevealsEventsInOrderAndReachesFullTime() {
    liveMatchService.kickoff(saveGameId, userId);

    int prev = -1;
    LiveMatchState state = null;
    for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
        state = liveMatchService.advance(saveGameId, userId);
        assertThat(state.minute()).isGreaterThan(prev);   // committed minute is strictly monotonic
        prev = state.minute();
        // Never reveal the future: every event minute is <= the committed minute.
        assertThat(state.events()).allMatch(e -> e.minute() <= state.minute());
    }
    assertThat(state.status()).isEqualTo("FULL_TIME");
    assertThat(state.minute()).isEqualTo(90);
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchAdvanceIT' -p backend`
Expected: FAIL — `advance` is undefined.

- [ ] **Step 3: Implement `advance`**

Add to `LiveMatchService`:
```java
    @Transactional
    public LiveMatchState advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            return toState(lm, save);   // already at full time — no-op
        }
        LiveTimeline timeline = liveMatchEngine.simulate(configFor(lm));
        lm.setCommittedMinute(timeline.nextEventMinute(lm.getCommittedMinute()));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    LiveMatch requireLive(UUID saveGameId) {
        return liveMatchRepository.findBySaveGameId(saveGameId)
                .orElseThrow(() -> new ApiException("NO_LIVE_MATCH",
                        "There is no live match in progress", HttpStatus.CONFLICT));
    }
```

- [ ] **Step 4: Run the test — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchAdvanceIT' -p backend`
Expected: PASS.

- [ ] **Step 5: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchAdvanceIT.java
git commit -m "feat(match): advance reveals the next live-match event without leaking the future"
```

---

### Task 8: `LiveMatchService.substitute` — validate and record a substitution

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchSubstituteIT.java`

**Interfaces:**
- Consumes: `configFor`, `onPitchManaged`, `toState`, `deserializeSubs`, `serialize`.
- Produces: `LiveMatchService.substitute(UUID saveGameId, UUID userId, SubstituteRequest req) -> LiveMatchState`. The substitution takes effect at `committed_minute + 1` (just after the last revealed event, so no revealed event is retroactively changed). Cap 5; out must be on the managed pitch; in must be on the managed bench and unused.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchSubstituteIT.java`:
```java
@Test
void substituteBringsOnABenchPlayerAndDecrementsRemaining() {
    LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
    liveMatchService.advance(saveGameId, userId);   // reveal at least the first event

    UUID out = UUID.fromString(start.onPitch().get(6).playerId());
    UUID in = UUID.fromString(start.bench().get(0).playerId());
    LiveMatchState after = liveMatchService.substitute(saveGameId, userId, new SubstituteRequest(out, in));

    assertThat(after.subsRemaining()).isEqualTo(4);
    // The incoming player is now on the pitch once the clock reaches the sub minute.
    LiveMatchState later = after;
    for (int i = 0; i < 200 && !"FULL_TIME".equals(later.status()); i++) later = liveMatchService.advance(saveGameId, userId);
    assertThat(later.events()).anyMatch(e -> "SUBSTITUTION".equals(e.type()) && in.toString().equals(e.playerInId()));
}

@Test
void substituteRejectsAPlayerNotOnTheBench() {
    LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
    UUID out = UUID.fromString(start.onPitch().get(0).playerId());
    UUID alsoOnPitch = UUID.fromString(start.onPitch().get(1).playerId());
    assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId, new SubstituteRequest(out, alsoOnPitch)))
            .isInstanceOf(ApiException.class);
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchSubstituteIT' -p backend`
Expected: FAIL — `substitute` is undefined.

- [ ] **Step 3: Implement `substitute`**

Add to `LiveMatchService`:
```java
    @Transactional
    public LiveMatchState substitute(UUID saveGameId, UUID userId, SubstituteRequest req) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            throw new ApiException("MATCH_OVER", "The match has ended", HttpStatus.CONFLICT);
        }

        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        List<Player> managedXi = lm.isManagedIsHome() ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, lm.getCommittedMinute(), managedXi);

        int used = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();
        if (used >= MAX_SUBS) {
            throw new ApiException("NO_SUBS_REMAINING", "No substitutions remaining", HttpStatus.CONFLICT);
        }
        if (!managed.onPitch().contains(req.outPlayerId())) {
            throw new ApiException("INVALID_SUBSTITUTION",
                    "The outgoing player is not on the pitch", HttpStatus.UNPROCESSABLE_CONTENT);
        }
        boolean onBench = managedBench.stream().anyMatch(p -> p.getId().equals(req.inPlayerId()));
        if (!onBench || managed.usedIn().contains(req.inPlayerId())) {
            throw new ApiException("INVALID_SUBSTITUTION",
                    "The incoming player is not an available bench player", HttpStatus.UNPROCESSABLE_CONTENT);
        }

        int minute = Math.min(MINUTES, lm.getCommittedMinute() + 1);
        List<LiveSub> subs = new ArrayList<>(cfg.subSchedule());
        subs.add(new LiveSub(minute, lm.isManagedIsHome(), req.outPlayerId(), req.inPlayerId()));
        lm.setSubSchedule(serialize(subs));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }
```

- [ ] **Step 4: Run the tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchSubstituteIT' -p backend`
Expected: PASS.

- [ ] **Step 5: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchSubstituteIT.java
git commit -m "feat(match): validate and record live substitutions (cap 5, bench-only)"
```

---

### Task 9: Partial-minutes fitness + apply injuries from the timeline

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/FitnessCalculatorTest.java` (extend), `MatchOutcomeApplierTimelineTest.java` (new)

**Interfaces:**
- Produces:
  - `FitnessCalculator.decayedBaseFitness(int effectiveAtKickoff, int stamina, int age, Position position, int minutesPlayed)` — decay scaled by `minutesPlayed/90`. The existing 4-arg overload delegates with `minutesPlayed = 90` (behaviour unchanged).
  - `MatchOutcomeApplier.applyInjuriesFromTimeline(List<TimedInjury> injuries, Map<UUID,Player> byId, boolean managedIsHome, Summary acc)` — applies pre-decided timeline injuries (no roll); managed-side injuries surface in the summary.

- [ ] **Step 1: Write the failing tests**

Add to `FitnessCalculatorTest.java`:
```java
@Test
void partialMinutesScaleTheDecay() {
    // Full 90' equals the existing 4-arg decay.
    int full = calc.decayedBaseFitness(100, 70, 27, Position.MID);
    assertThat(calc.decayedBaseFitness(100, 70, 27, Position.MID, 90)).isEqualTo(full);
    // A 30' cameo decays ~1/3 as much; an unused sub (0') does not decay.
    assertThat(calc.decayedBaseFitness(100, 70, 27, Position.MID, 0)).isEqualTo(100);
    int cameo = calc.decayedBaseFitness(100, 70, 27, Position.MID, 30);
    assertThat(100 - cameo).isLessThan(100 - full);
}
```

New `MatchOutcomeApplierTimelineTest.java`:
```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeApplierTimelineTest {

    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());

    @Test
    void appliesTimelineInjuriesAndSurfacesManagedOnes() {
        Player p = /* managed-side player via the shared factory */;
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        applier.applyInjuriesFromTimeline(
                List.of(new TimedInjury(p.getId(), true, 55, InjurySeverity.MINOR, 3)),
                Map.of(p.getId(), p), true, acc);

        assertThat(p.getInjuryMatches()).isEqualTo(3);
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.MINOR);
        assertThat(acc.newInjuries()).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo("MINOR"));
    }
}
```

- [ ] **Step 2: Run them — verify they fail**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FitnessCalculatorTest' --tests '*MatchOutcomeApplierTimelineTest' -p backend`
Expected: FAIL — new methods undefined.

- [ ] **Step 3: Add the partial-minutes overload**

In `FitnessCalculator.java`, change the existing method to delegate and add the 5-arg version:
```java
    /** New base fitness after playing a full match from the given kickoff fitness. */
    public int decayedBaseFitness(int effectiveAtKickoff, int stamina, int age, Position position) {
        return decayedBaseFitness(effectiveAtKickoff, stamina, age, position, 90);
    }

    /** New base fitness after playing {@code minutesPlayed} of a match; decay scales linearly with minutes. */
    public int decayedBaseFitness(int effectiveAtKickoff, int stamina, int age, Position position, int minutesPlayed) {
        double fullDecay = DECAY_BASE * clamp(70.0 / stamina, 0.7, 1.4)
                * AgeBand.of(age).decayFactor() * positionDecayFactor(position);
        double decay = fullDecay * (Math.max(0, Math.min(90, minutesPlayed)) / 90.0);
        return (int) Math.max(0, Math.round(effectiveAtKickoff - decay));
    }
```

- [ ] **Step 4: Add `applyInjuriesFromTimeline`**

In `MatchOutcomeApplier.java`:
```java
    /**
     * Applies injuries already decided by the live timeline (no roll). Managed-side injuries are
     * surfaced in the summary. Mirrors {@link #applyInjuries} but consumes pre-drawn {@link TimedInjury}s.
     */
    public void applyInjuriesFromTimeline(List<TimedInjury> injuries, Map<UUID, Player> byId,
                                          boolean managedIsHome, Summary acc) {
        for (TimedInjury inj : injuries) {
            Player p = byId.get(inj.playerId());
            if (p == null) {
                continue;
            }
            p.applyInjury(inj.severity(), inj.layoffMatches());
            if (inj.home() == managedIsHome) {
                acc.newInjuries().add(new InjuryResponse(p.getId(), p.getName(),
                        inj.layoffMatches(), inj.severity().name()));
            }
        }
    }
```
Add the imports `java.util.List`, `java.util.Map` (already present) — `TimedInjury` is same package.

- [ ] **Step 5: Run the tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*FitnessCalculatorTest' --tests '*MatchOutcomeApplierTimelineTest' -p backend`
Expected: PASS.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FitnessCalculator.java \
  backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java \
  backend/src/test/java/br/com/carvoeiromanager/match/FitnessCalculatorTest.java \
  backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTimelineTest.java
git commit -m "feat(match): partial-minutes fitness decay and timeline-driven injuries"
```

---

### Task 10: Round-finalisation refactor for the live finish path

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (`simulateRound` overload)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java` (extract `recordMatchRound`; add `completeUserMatch`; guard `advance` while a live match is in progress)
- Modify: constructor of `SeasonAdvanceService` to inject `LiveMatchRepository`, `MatchOutcomeApplier` (only if needed), and reuse existing `fixtureSimulator`
- Test: `backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceRefactorTest.java` (new) — assert the existing advance still works and `advance` is blocked mid-live-match

**Interfaces:**
- Consumes: `RoundOutcome(List<FixtureResultResponse> results, List<BookingResponse> bookings, List<SuspensionResponse> newSuspensions, List<InjuryResponse> newInjuries)` — **verify this exact record shape in `RoundOutcome.java` before coding.** `FixtureSimulator.simulateRound(save, competitionId, fixtures)`, `financeService.processMatchday/processMonth/processSeasonEnd`, `noMoreMatchEvents`, `persistNewsItem`.
- Produces:
  - `FixtureSimulator.simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures, Set<UUID> alreadyPlayedElsewhere)` — the 3-arg version delegates with `Set.of()`; `alreadyPlayedElsewhere` players are excluded from the non-player regression.
  - `SeasonAdvanceService.recordMatchRound(SaveGame save, SeasonEvent event, RoundOutcome outcome, List<Fixture> fixtures) -> AdvanceResponse` (package-visible or public).
  - `SeasonAdvanceService.completeUserMatch(SaveGame save, FixtureResultResponse userResult, MatchOutcomeApplier.Summary userSummary, UUID userFixtureId, Set<UUID> userPlayedIds) -> AdvanceResponse`.

- [ ] **Step 1: Write the failing test**

`SeasonAdvanceServiceRefactorTest.java` (`@SpringBootTest` + Testcontainers, existing career helper):
```java
@Test
void advanceIsBlockedWhileALiveMatchIsInProgress() {
    liveMatchService.kickoff(saveGameId, userId);   // starts a live match
    assertThatThrownBy(() -> seasonAdvanceService.advance(saveGameId, userId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("live match");
}
```
(The "existing advance still works for non-playing rounds" path is already covered by the current advance ITs — running the full `season` package in Step 4 guards the refactor.)

- [ ] **Step 2: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*SeasonAdvanceServiceRefactorTest' -p backend`
Expected: FAIL — no guard yet (advance proceeds).

- [ ] **Step 3: Add the `simulateRound` overload**

In `FixtureSimulator.java`, keep the current method as a delegating overload and add the `alreadyPlayedElsewhere` parameter to the real one:
```java
    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures) {
        return simulateRound(save, competitionId, fixtures, java.util.Set.of());
    }

    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures,
                                      Set<UUID> alreadyPlayedElsewhere) {
        // ... unchanged body up to the nonPlayers computation ...
        List<Player> nonPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !playedIds.contains(p.getId()) && !alreadyPlayedElsewhere.contains(p.getId()))
                .toList();
        // ... unchanged tail ...
    }
```
(Only the `nonPlayers` filter line changes; everything else in the method body is identical. `Set` is already imported.)

- [ ] **Step 4: Extract `recordMatchRound`, add `completeUserMatch`, guard `advance`**

In `SeasonAdvanceService.java`:

Inject `LiveMatchRepository liveMatchRepository` and `MatchOutcomeApplier` is not needed here. Add to the constructor + field.

Guard `advance` (right after the `FINISHED` check):
```java
        if (liveMatchRepository.findBySaveGameId(saveGameId).isPresent()) {
            throw new ApiException("LIVE_MATCH_IN_PROGRESS",
                    "Finish the live match before advancing", HttpStatus.CONFLICT);
        }
```

Replace the tail of `processMatch` (from `save.setCurrentRound(round + 1);` onward) with a call to a new extracted method, and add both methods:
```java
    private AdvanceResponse processMatch(SaveGame save, SeasonEvent event) {
        final UUID saveGameId = save.getId();
        final UUID competitionId = event.getCompetitionId();
        final int round = event.getRound();
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, competitionId, round);
        RoundOutcome outcome = fixtureSimulator.simulateRound(save, competitionId, fixtures);
        return recordMatchRound(save, event, outcome, fixtures);
    }

    /** Finalise a MATCH round whose fixtures are already simulated into {@code outcome}: bump the
     *  round, charge matchday finances, award the season-end prize on the final matchday, and build
     *  the response. Shared by the instant advance and the live-match finish. */
    AdvanceResponse recordMatchRound(SaveGame save, SeasonEvent event, RoundOutcome outcome, List<Fixture> fixtures) {
        final UUID saveGameId = save.getId();
        final UUID competitionId = event.getCompetitionId();
        final int round = event.getRound();
        final LocalDate eventDate = event.getEventDate();
        String competitionName = competitionRepository.findById(competitionId).map(c -> c.getName()).orElse(null);

        save.setCurrentRound(round + 1);
        FinanceRoundResponse matchdayFinance =
                financeService.processMatchday(saveGameId, competitionId, fixtures, eventDate);
        boolean seasonFinished = noMoreMatchEvents(saveGameId, event.getId());
        BigDecimal prize = BigDecimal.ZERO;
        if (seasonFinished) {
            save.setStatus(SaveGameStatus.FINISHED);
            prize = financeService.processSeasonEnd(saveGameId, competitionId, eventDate);
        }
        FinanceRoundResponse finance = new FinanceRoundResponse(
                matchdayFinance.gate(), matchdayFinance.matchCost(),
                matchdayFinance.sponsorship(), matchdayFinance.wages(),
                matchdayFinance.upkeep(), prize,
                matchdayFinance.net(), matchdayFinance.balance().add(prize));
        return new AdvanceResponse("MATCH", eventDate.toString(), competitionName, round,
                List.copyOf(outcome.results()), List.copyOf(outcome.bookings()),
                List.copyOf(outcome.newSuspensions()), List.copyOf(outcome.newInjuries()),
                finance, 0, seasonFinished);
    }

    /** Called by the live-match finish: the user's fixture is already simulated (result + summary +
     *  played ids); this simulates the rest of the round, merges, finalises, marks the event
     *  processed and persists the news item. */
    @Transactional
    public AdvanceResponse completeUserMatch(SaveGame save, FixtureResultResponse userResult,
                                             MatchOutcomeApplier.Summary userSummary, UUID userFixtureId,
                                             Set<UUID> userPlayedIds) {
        final UUID saveGameId = save.getId();
        SeasonEvent event = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        final LocalDate eventDate = event.getEventDate();
        save.setCurrentDate(eventDate);
        financeService.processMonth(saveGameId, eventDate);

        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, event.getCompetitionId(), event.getRound());
        List<Fixture> others = fixtures.stream().filter(f -> !f.getId().equals(userFixtureId)).toList();
        RoundOutcome othersOutcome = fixtureSimulator.simulateRound(save, event.getCompetitionId(), others, userPlayedIds);

        List<FixtureResultResponse> results = new java.util.ArrayList<>();
        results.add(userResult);
        results.addAll(othersOutcome.results());
        RoundOutcome merged = new RoundOutcome(
                results,
                concat(userSummary.bookings(), othersOutcome.bookings()),
                concat(userSummary.newSuspensions(), othersOutcome.newSuspensions()),
                concat(userSummary.newInjuries(), othersOutcome.newInjuries()));

        AdvanceResponse response = recordMatchRound(save, event, merged, fixtures);
        event.markProcessed();
        seasonEventRepository.save(event);
        saveGameRepository.save(save);
        persistNewsItem(saveGameId, eventDate, response);
        return response;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
```
Add imports as needed: `br.com.carvoeiromanager.match.FixtureResultResponse`, `br.com.carvoeiromanager.match.MatchOutcomeApplier`, `br.com.carvoeiromanager.match.LiveMatchRepository`, `br.com.carvoeiromanager.common.ApiException`, `java.util.Set`. (`ApiException` and `HttpStatus` are already used in this file.)

- [ ] **Step 5: Run tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.season.*' -p backend`
Expected: PASS — existing advance ITs still green (proves the `processMatch`/`recordMatchRound` extraction is behaviour-preserving) and the new guard test passes.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java \
  backend/src/main/java/br/com/carvoeiromanager/season/SeasonAdvanceService.java \
  backend/src/test/java/br/com/carvoeiromanager/season/SeasonAdvanceServiceRefactorTest.java
git commit -m "refactor(season): reusable round finalisation + block advance during a live match"
```

---

### Task 11: `LiveMatchService.finish` — apply the outcome and finalise the round

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (constructor deps + `finish`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java`

**Interfaces:**
- Consumes: `configFor`, `indexAll`, `LiveMatchEngine.simulate`, `Fixture.recordResult`, `MatchEventRepository.save` + `MatchEvent.create`, `MatchOutcomeApplier.applyFixture` / `applyInjuriesFromTimeline` + `Summary` + `TeamOutcome`, `FitnessCalculator.decayedBaseFitness(...,minutes)`, `SeasonAdvanceService.completeUserMatch`, `LiveMatchRepository.deleteBySaveGameId`, `ClubRepository`, `PlayerRepository.saveAll`.
- Produces: `LiveMatchService.finish(UUID saveGameId, UUID userId) -> AdvanceResponse`. Only valid at full time (`committed_minute >= 90`).

- [ ] **Step 1: Add the new constructor dependencies**

Add fields + constructor params to `LiveMatchService`: `MatchEventRepository matchEventRepository`, `MatchOutcomeApplier matchOutcomeApplier`, `br.com.carvoeiromanager.season.SeasonAdvanceService seasonAdvanceService`. (Wiring `SeasonAdvanceService` here is safe — it does not depend on `LiveMatchService`, so there is no cycle.)

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java`:
```java
@Test
void finishRecordsTheResultSimulatesTheRoundAndClearsTheSession() {
    LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
    String starterId = start.onPitch().get(0).playerId();

    LiveMatchState s = start;
    for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) s = liveMatchService.advance(saveGameId, userId);

    AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);

    assertThat(resp.type()).isEqualTo("MATCH");
    assertThat(resp.results()).isNotEmpty();
    assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isEmpty();
    // A player who started lost fitness (kicked off at 100).
    assertThat(playerRepository.findById(UUID.fromString(starterId)).orElseThrow().getFitness()).isLessThan(100);
}

@Test
void finishBeforeFullTimeIsRejected() {
    liveMatchService.kickoff(saveGameId, userId);
    assertThatThrownBy(() -> liveMatchService.finish(saveGameId, userId))
            .isInstanceOf(ApiException.class);
}
```

- [ ] **Step 3: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchFinishIT' -p backend`
Expected: FAIL — `finish` undefined.

- [ ] **Step 4: Implement `finish`**

Add to `LiveMatchService` (imports: `br.com.carvoeiromanager.club.Club`, `java.util.stream.Collectors` already present or add):
```java
    @Transactional
    public AdvanceResponse finish(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() < MINUTES) {
            throw new ApiException("MATCH_NOT_FINISHED",
                    "The match has not reached full time", HttpStatus.CONFLICT);
        }

        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        Map<UUID, Player> byId = indexAll(cfg);

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        UUID homeClubId = fixture.getHomeClubId();
        UUID awayClubId = fixture.getAwayClubId();
        fixture.recordResult(timeline.homeGoals(), timeline.awayGoals());
        fixtureRepository.save(fixture);

        // Persist real match events (goals + cards), mirroring the instant path's match_events rows.
        for (LiveTimelineEvent e : timeline.events()) {
            if (isCardOrGoal(e.type())) {
                UUID clubId = e.home() ? homeClubId : awayClubId;
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), e.minute(), e.type().name(), e.playerId(), clubId));
            }
        }

        List<Player> homePlayed = playedPlayers(cfg.homeXi(), cfg.homeBench(), timeline);
        List<Player> awayPlayed = playedPlayers(cfg.awayXi(), cfg.awayBench(), timeline);

        List<AttributedEvent> attributed = timeline.events().stream()
                .filter(e -> isCardOrGoal(e.type()))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();

        boolean homeManaged = lm.isManagedIsHome();
        boolean awayManaged = !lm.isManagedIsHome();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        matchOutcomeApplier.applyFixture(attributed,
                new MatchOutcomeApplier.TeamOutcome(homePlayed, timeline.homeGoals(), timeline.awayGoals(), homeManaged),
                new MatchOutcomeApplier.TeamOutcome(awayPlayed, timeline.awayGoals(), timeline.homeGoals(), awayManaged),
                summary);
        matchOutcomeApplier.applyInjuriesFromTimeline(timeline.injuries(), byId, lm.isManagedIsHome(), summary);

        List<Player> allPlayed = new ArrayList<>();
        allPlayed.addAll(homePlayed);
        allPlayed.addAll(awayPlayed);
        for (Player p : allPlayed) {
            int eff = cfg.fitness().getOrDefault(p.getId(), 100);
            int minutes = timeline.minutesPlayed().getOrDefault(p.getId(), 0);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge(), p.getPosition(), minutes);
            p.recordAppearance(newBase, lm.getMatchDate());
        }
        playerRepository.saveAll(allPlayed);

        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        FixtureResultResponse userResult = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, clubs.get(homeClubId).getName(), timeline.homeGoals(),
                awayClubId, clubs.get(awayClubId).getName(), timeline.awayGoals());
        Set<UUID> userPlayedIds = allPlayed.stream().map(Player::getId).collect(Collectors.toSet());

        AdvanceResponse response = seasonAdvanceService.completeUserMatch(
                save, userResult, summary, fixture.getId(), userPlayedIds);
        liveMatchRepository.deleteBySaveGameId(saveGameId);
        return response;
    }

    private boolean isCardOrGoal(EventType t) {
        return t == EventType.GOAL || t == EventType.SET_PIECE_GOAL
                || t == EventType.YELLOW_CARD || t == EventType.RED_CARD;
    }

    /** Starters (who all played) plus any bench player who actually came on (minutes > 0). */
    private List<Player> playedPlayers(List<Player> xi, List<Player> bench, LiveTimeline timeline) {
        List<Player> out = new ArrayList<>(xi);
        for (Player p : bench) {
            if (timeline.minutesPlayed().getOrDefault(p.getId(), 0) > 0) {
                out.add(p);
            }
        }
        return out;
    }
```

- [ ] **Step 5: Run the tests — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchFinishIT' -p backend`
Expected: PASS.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchFinishIT.java
git commit -m "feat(match): finish applies the live outcome, simulates the round and clears the session"
```

---

### Task 12: `LiveMatchController` — the five endpoints

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchController.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchControllerIT.java`

**Interfaces:**
- Consumes: `LiveMatchService`, `AdvanceResponse` (season pkg), `@CurrentUser`.
- Produces the REST surface:
  - `POST /api/v1/save-games/{saveGameId}/live-match/kickoff` → `200 LiveMatchState`
  - `GET  /api/v1/save-games/{saveGameId}/live-match` → `200 LiveMatchState` or `204`
  - `POST /api/v1/save-games/{saveGameId}/live-match/advance` → `200 LiveMatchState`
  - `POST /api/v1/save-games/{saveGameId}/live-match/substitute` (body `SubstituteRequest`) → `200 LiveMatchState`
  - `POST /api/v1/save-games/{saveGameId}/live-match/finish` → `200 AdvanceResponse`

- [ ] **Step 1: Write the failing controller test**

`backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchControllerIT.java` — MockMvc + JWT (copy auth setup from an existing `*ControllerIT` such as `SquadControllerIT` / `AdvanceControllerIT`):
```java
@Test
void fullLiveMatchFlowOverHttp() throws Exception {
    // kickoff
    mockMvc.perform(post("/api/v1/save-games/{s}/live-match/kickoff", saveGameId).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    // resume
    mockMvc.perform(get("/api/v1/save-games/{s}/live-match", saveGameId).header("Authorization", bearer))
            .andExpect(status().isOk());
    // advance to full time
    String status = "IN_PROGRESS";
    for (int i = 0; i < 200 && !"FULL_TIME".equals(status); i++) {
        status = JsonPath.read(mockMvc.perform(post("/api/v1/save-games/{s}/live-match/advance", saveGameId)
                .header("Authorization", bearer)).andReturn().getResponse().getContentAsString(), "$.status");
    }
    // finish returns an AdvanceResponse
    mockMvc.perform(post("/api/v1/save-games/{s}/live-match/finish", saveGameId).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("MATCH"));
    // session gone -> 204
    mockMvc.perform(get("/api/v1/save-games/{s}/live-match", saveGameId).header("Authorization", bearer))
            .andExpect(status().isNoContent());
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchControllerIT' -p backend`
Expected: FAIL — no such endpoints (404).

- [ ] **Step 3: Implement the controller**

`LiveMatchController.java`:
```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.common.security.CurrentUser;
import br.com.carvoeiromanager.season.AdvanceResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/live-match")
public class LiveMatchController {

    private final LiveMatchService liveMatchService;

    public LiveMatchController(LiveMatchService liveMatchService) {
        this.liveMatchService = liveMatchService;
    }

    @PostMapping("/kickoff")
    public ResponseEntity<LiveMatchState> kickoff(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.kickoff(saveGameId, userId));
    }

    @GetMapping
    public ResponseEntity<LiveMatchState> live(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return liveMatchService.getLive(saveGameId, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/advance")
    public ResponseEntity<LiveMatchState> advance(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.advance(saveGameId, userId));
    }

    @PostMapping("/substitute")
    public ResponseEntity<LiveMatchState> substitute(@PathVariable UUID saveGameId,
            @Valid @RequestBody SubstituteRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.substitute(saveGameId, userId, request));
    }

    @PostMapping("/finish")
    public ResponseEntity<AdvanceResponse> finish(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.finish(saveGameId, userId));
    }
}
```

- [ ] **Step 4: Run the test — verify green**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '*LiveMatchControllerIT' -p backend`
Expected: PASS.

- [ ] **Step 5: Run the whole backend suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test -p backend`
Expected: PASS (aside from the known pre-existing `SeedLoaderTest` failure tracked separately — confirm no NEW failures).

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchController.java \
  backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchControllerIT.java
git commit -m "feat(match): REST endpoints for the live match (kickoff/advance/substitute/finish)"
```

---

## Phase C — Frontend live match

### Task 13: Live-match API client + types

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/liveMatch.ts`
- Test: none (thin client; covered by the page tests in Tasks 14–15)

**Interfaces:**
- Produces: `LiveEvent`, `LivePlayer`, `LiveMatchStatus`, `LiveMatchState`, `SubstituteRequest` types; `kickoffLiveMatch`, `getLiveMatch`, `advanceLiveMatch`, `substituteLiveMatch`, `finishLiveMatch`.

- [ ] **Step 1: Add the types**

Append to `frontend/src/api/types.ts`:
```ts
export type LiveMatchStatus = 'IN_PROGRESS' | 'PAUSED_INJURY' | 'PAUSED_RED' | 'FULL_TIME'

export type LiveEvent = {
  minute: number
  type: 'GOAL' | 'SET_PIECE_GOAL' | 'YELLOW_CARD' | 'RED_CARD' | 'CHANCE' | 'INJURY' | 'SUBSTITUTION'
  home: boolean
  playerId: string
  playerName: string
  playerInId: string | null
  playerInName: string | null
  severity: string | null
  text: string
}

export type LivePlayer = {
  playerId: string
  name: string
  position: string
  overall: number
  fitness: number
  hobbled: boolean
  onPitch: boolean
  used: boolean
  eligible: boolean
}

export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  homeClubName: string
  awayClubName: string
  managedIsHome: boolean
  minute: number
  homeGoals: number
  awayGoals: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
}

export type SubstituteRequest = { outPlayerId: string; inPlayerId: string }
```

- [ ] **Step 2: Add the client**

`frontend/src/api/liveMatch.ts`:
```ts
import { apiGet, apiPost } from './client'
import type { LiveMatchState, SubstituteRequest, AdvanceResponse } from './types'

export function kickoffLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/kickoff`, {}, token)
}

// Resume-on-reload: null when no live match is in progress (endpoint replies 204 -> undefined).
export function getLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState | null> {
  return apiGet<LiveMatchState | undefined>(`/save-games/${saveGameId}/live-match`, token).then(r => r ?? null)
}

export function advanceLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/advance`, {}, token)
}

export function substituteLiveMatch(
  saveGameId: string, req: SubstituteRequest, token: string,
): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/substitute`, req, token)
}

export function finishLiveMatch(saveGameId: string, token: string): Promise<AdvanceResponse> {
  return apiPost<AdvanceResponse>(`/save-games/${saveGameId}/live-match/finish`, {}, token)
}
```

- [ ] **Step 3: Typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 4: Prepare the commit (do not run)**

```bash
git add frontend/src/api/types.ts frontend/src/api/liveMatch.ts
git commit -m "feat(live-match): frontend types and API client"
```

---

### Task 14: `LiveMatchPage` — clock, commentary feed, auto-advance, full-time → news

**Files:**
- Create: `frontend/src/features/match/LiveMatchPage.tsx`
- Create: `frontend/src/features/match/LiveMatchPage.test.tsx`
- Modify: `frontend/src/router.tsx` (add the `/match` route)

**Interfaces:**
- Consumes: `getLiveMatch`, `advanceLiveMatch`, `finishLiveMatch`; `useAuth`, TanStack Query, react-router `useSearchParams` / `useNavigate`.
- Produces: the `/match` screen. Auto-advances event-to-event while `status === 'IN_PROGRESS'`; pauses on `PAUSED_INJURY` / `PAUSED_RED`; at `FULL_TIME` shows **Ver resumo** which calls `finish` and routes to `/news`. (Substitutions are added in Task 15.)

- [ ] **Step 1: Write the failing test**

`frontend/src/features/match/LiveMatchPage.test.tsx`:
```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LiveMatchPage } from './LiveMatchPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
}))
import { getLiveMatch, advanceLiveMatch, finishLiveMatch } from '../../api/liveMatch'

const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma', awayClubName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/match?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}><LiveMatchPage /></QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('LiveMatchPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows the score and the commentary feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/GOL! Camisa 9/)).toBeInTheDocument()
    expect(screen.getByText(/Criciúma/)).toBeInTheDocument()
  })

  it('at full time, Ver resumo finishes and navigates to the news', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    vi.mocked(finishLiveMatch).mockResolvedValue({ type: 'MATCH' } as never)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /ver resumo/i }))
    await waitFor(() => expect(finishLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })
})
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: FAIL — `LiveMatchPage` does not exist.

- [ ] **Step 3: Implement the page**

`frontend/src/features/match/LiveMatchPage.tsx`:
```tsx
import { useEffect, useRef, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Typography, Button, Chip, Alert, LinearProgress } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getLiveMatch, advanceLiveMatch, finishLiveMatch } from '../../api/liveMatch'
import type { LiveEvent, LiveMatchState } from '../../api/types'

const EVENT_ICON: Record<LiveEvent['type'], string> = {
  GOAL: '⚽', SET_PIECE_GOAL: '⚽', YELLOW_CARD: '🟨', RED_CARD: '🟥',
  CHANCE: '🎯', INJURY: '🚑', SUBSTITUTION: '🔁',
}

const ADVANCE_MS = 1500   // event-to-event pacing (client-owned clock)

export function LiveMatchPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const [playing, setPlaying] = useState(true)

  const { data: state } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
  })

  const setState = (s: LiveMatchState) => queryClient.setQueryData(['liveMatch', saveGameId], s)

  const advanceMut = useMutation({
    mutationFn: () => advanceLiveMatch(saveGameId, token ?? ''),
    onSuccess: setState,
  })

  const finishMut = useMutation({
    mutationFn: () => finishLiveMatch(saveGameId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })

  // Client-owned clock: while playing and in open play, keep asking for the next event.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    if (!state) return
    const openPlay = state.status === 'IN_PROGRESS' && state.minute < 90
    if (playing && openPlay && !advanceMut.isPending) {
      timer.current = setTimeout(() => advanceMut.mutate(), ADVANCE_MS)
      return () => { if (timer.current) clearTimeout(timer.current) }
    }
  }, [state, playing, advanceMut.isPending])

  if (!saveGameId) return <Typography>Partida inválida.</Typography>
  if (!state) return <Typography>Nenhuma partida em andamento.</Typography>

  const paused = state.status === 'PAUSED_INJURY' || state.status === 'PAUSED_RED'
  const fullTime = state.status === 'FULL_TIME'
  const feed = [...state.events].reverse()

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
        <Chip color="error" label="AO VIVO" size="small" />
        <Typography sx={{ fontWeight: 700 }}>{state.minute}'</Typography>
        <Typography sx={{ flex: 1, textAlign: 'center', fontFamily: 'monospace', fontSize: '1.1rem' }}>
          {state.homeClubName} <strong>{state.homeGoals}</strong> — <strong>{state.awayGoals}</strong> {state.awayClubName}
        </Typography>
      </Stack>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {state.competitionName}{state.round !== null ? ` · Rodada ${state.round + 1}` : ''}
      </Typography>
      {!fullTime && <LinearProgress variant="determinate" value={(state.minute / 90) * 100} sx={{ my: 1 }} />}

      {state.status === 'PAUSED_INJURY' && (
        <Alert severity="warning" sx={{ my: 1 }}>Lesão! Faça uma substituição ou continue.</Alert>
      )}
      {state.status === 'PAUSED_RED' && (
        <Alert severity="error" sx={{ my: 1 }}>Cartão vermelho! Seu time está com um a menos.</Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ my: 1 }}>
        {!fullTime && (
          <Button variant="outlined" onClick={() => setPlaying(p => !p)}>
            {playing ? 'Pausar' : 'Continuar'}
          </Button>
        )}
        {!fullTime && paused && (
          <Button variant="outlined" onClick={() => advanceMut.mutate()}>Continuar assim mesmo</Button>
        )}
        {fullTime && (
          <Button variant="contained" disabled={finishMut.isPending} onClick={() => finishMut.mutate()}>
            {finishMut.isPending ? 'Encerrando…' : 'Ver resumo'}
          </Button>
        )}
      </Stack>

      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {feed.map((e, i) => (
          <Stack key={`${e.minute}-${i}`} direction="row" spacing={1}>
            <Typography sx={{ minWidth: 32, color: 'text.secondary' }}>{e.minute}'</Typography>
            <Typography component="span">{EVENT_ICON[e.type]} {e.text}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
```

- [ ] **Step 4: Add the `/match` route**

In `frontend/src/router.tsx`, import and add the route (protected, like the others):
```tsx
import { LiveMatchPage } from './features/match/LiveMatchPage'
// ...
      <Route path="/match" element={<ProtectedRoute><LiveMatchPage /></ProtectedRoute>} />
```

- [ ] **Step 5: Run the test — verify green**

Run: `cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: PASS. Then `cd frontend && npx tsc --noEmit` → clean.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add frontend/src/features/match/LiveMatchPage.tsx frontend/src/features/match/LiveMatchPage.test.tsx frontend/src/router.tsx
git commit -m "feat(live-match): live screen with clock, commentary feed and auto-advance"
```

---

### Task 15: Substitution dialog + wiring

**Files:**
- Create: `frontend/src/features/match/SubDialog.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Consumes: `substituteLiveMatch`; `LivePlayer` from the state.
- Produces: a **Substituir** button on the live screen that opens `SubDialog`; confirming calls `substituteLiveMatch` and updates the cached state. Disabled at full time or when `subsRemaining === 0`.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/features/match/LiveMatchPage.test.tsx` (extend the mock to include on-pitch/bench players):
```tsx
it('opens the sub dialog and substitutes a bench player', async () => {
  vi.mocked(getLiveMatch).mockResolvedValue({
    ...STATE, status: 'PAUSED_INJURY',
    onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: true, onPitch: true, used: false, eligible: true }],
    bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
    subsRemaining: 5,
  } as never)
  vi.mocked(substituteLiveMatch).mockResolvedValue({ ...STATE, subsRemaining: 4 } as never)

  renderPage()
  fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))
  fireEvent.click(await screen.findByText('Titular'))
  fireEvent.click(screen.getByText('Reserva'))
  fireEvent.click(screen.getByRole('button', { name: /confirmar/i }))

  await waitFor(() =>
    expect(substituteLiveMatch).toHaveBeenCalledWith('sg-1', { outPlayerId: 'p1', inPlayerId: 'b1' }, 'test-token'))
})
```
Add `substituteLiveMatch` to the imports pulled from the mocked module at the top of the file.

- [ ] **Step 2: Run it — verify it fails**

Run: `cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: FAIL — no "Substituir" button.

- [ ] **Step 3: Create `SubDialog`**

`frontend/src/features/match/SubDialog.tsx`:
```tsx
import { useState } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, List, ListItemButton, ListItemText, Typography, Box,
} from '@mui/material'
import type { LivePlayer } from '../../api/types'

interface SubDialogProps {
  open: boolean
  onClose: () => void
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  pending: boolean
  onConfirm: (outPlayerId: string, inPlayerId: string) => void
}

export function SubDialog({ open, onClose, onPitch, bench, subsRemaining, pending, onConfirm }: SubDialogProps) {
  const [out, setOut] = useState<string | null>(null)
  const [inp, setInp] = useState<string | null>(null)
  const available = bench.filter(b => !b.used && b.eligible)

  return (
    <Dialog open={open} onClose={onClose} fullWidth>
      <DialogTitle>Substituição — restam {subsRemaining}</DialogTitle>
      <DialogContent>
        <Typography variant="subtitle2" sx={{ mt: 1 }}>Quem sai</Typography>
        <List dense>
          {onPitch.map(p => (
            <ListItemButton key={p.playerId} selected={out === p.playerId} onClick={() => setOut(p.playerId)}>
              <ListItemText primary={`${p.name}${p.hobbled ? ' 🚑' : ''}`} secondary={`${p.position} · Condição ${p.fitness}%`} />
            </ListItemButton>
          ))}
        </List>
        <Typography variant="subtitle2" sx={{ mt: 1 }}>Quem entra</Typography>
        <List dense>
          {available.map(p => (
            <ListItemButton key={p.playerId} selected={inp === p.playerId} onClick={() => setInp(p.playerId)}>
              <ListItemText primary={p.name} secondary={`${p.position} · Condição ${p.fitness}%`} />
            </ListItemButton>
          ))}
          {available.length === 0 && (
            <Box sx={{ px: 2, py: 1 }}><Typography variant="body2">Sem reservas disponíveis.</Typography></Box>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button variant="contained" disabled={!out || !inp || pending || subsRemaining <= 0}
          onClick={() => { if (out && inp) onConfirm(out, inp) }}>
          Confirmar
        </Button>
      </DialogActions>
    </Dialog>
  )
}
```

- [ ] **Step 4: Wire it into `LiveMatchPage`**

Add the import and mutation, a dialog-open state, the button, and render the dialog. In `LiveMatchPage.tsx`:
```tsx
import { getLiveMatch, advanceLiveMatch, finishLiveMatch, substituteLiveMatch } from '../../api/liveMatch'
import { SubDialog } from './SubDialog'
```
Add near the other state:
```tsx
  const [subOpen, setSubOpen] = useState(false)
```
Add the mutation next to `advanceMut`:
```tsx
  const subMut = useMutation({
    mutationFn: (v: { outPlayerId: string; inPlayerId: string }) => substituteLiveMatch(saveGameId, v, token ?? ''),
    onSuccess: (s) => { setState(s); setSubOpen(false) },
  })
```
Add a **Substituir** button inside the controls `Stack` (before the full-time button):
```tsx
        {!fullTime && (
          <Button variant="outlined" color="secondary" disabled={state.subsRemaining <= 0}
            onClick={() => setSubOpen(true)}>
            Substituir ({state.subsRemaining})
          </Button>
        )}
```
Render the dialog before the closing `</Box>`:
```tsx
      <SubDialog
        open={subOpen}
        onClose={() => setSubOpen(false)}
        onPitch={state.onPitch}
        bench={state.bench}
        subsRemaining={state.subsRemaining}
        pending={subMut.isPending}
        onConfirm={(outPlayerId, inPlayerId) => subMut.mutate({ outPlayerId, inPlayerId })}
      />
```
When the sub dialog is open, pause the clock so it doesn't advance under the user: guard the auto-advance effect's condition with `&& !subOpen`.

- [ ] **Step 5: Run the tests — verify green**

Run: `cd frontend && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: PASS. Then `cd frontend && npx tsc --noEmit` → clean.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add frontend/src/features/match/SubDialog.tsx frontend/src/features/match/LiveMatchPage.tsx frontend/src/features/match/LiveMatchPage.test.tsx
git commit -m "feat(live-match): substitution dialog wired to the live screen"
```

---

### Task 16: Advance fork + resume guard in the top bar

**Files:**
- Modify: `frontend/src/components/Layout.tsx`
- Create: `frontend/src/components/Layout.liveMatch.test.tsx`

**Interfaces:**
- Consumes: `getLiveMatch`, `kickoffLiveMatch`; existing `preview.managedClubPlaysNext`.
- Produces: the **Avançar** button forks — resume an in-progress live match, else kick off the live match when the managed club plays, else the current instant advance.

- [ ] **Step 1: Write the failing test**

`frontend/src/components/Layout.liveMatch.test.tsx` — mock `../api/career`, `../api/liveMatch`, and `../api/account`; provide a save game and a preview with `managedClubPlaysNext: true`, no ineligible/window blockers, and a saved lineup so the advance gate is open.
```tsx
it('kicks off the live match and navigates to /match when the managed club plays', async () => {
  vi.mocked(getLiveMatch).mockResolvedValue(null)
  vi.mocked(kickoffLiveMatch).mockResolvedValue({ fixtureId: 'f1' } as never)
  renderLayout()   // helper wiring MemoryRouter + AuthProvider + QueryClient with the mocks above
  fireEvent.click(await screen.findByRole('button', { name: /avançar/i }))
  await waitFor(() => expect(kickoffLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
})

it('shows Retomar partida and navigates to /match when a live match is in progress', async () => {
  vi.mocked(getLiveMatch).mockResolvedValue({ fixtureId: 'f1', status: 'IN_PROGRESS' } as never)
  renderLayout()
  expect(await screen.findByRole('button', { name: /retomar partida/i })).toBeInTheDocument()
})
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd frontend && npx vitest run src/components/Layout.liveMatch.test.tsx`
Expected: FAIL — the button says "Avançar" and calls `advanceRound`.

- [ ] **Step 3: Implement the fork**

In `Layout.tsx`:

Add imports:
```tsx
import { getLiveMatch, kickoffLiveMatch } from '../api/liveMatch'
import type { LiveMatchState } from '../api/types'
```
Add the live-match query (after the `preview` query):
```tsx
  const { data: liveMatch } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 5_000,
  })
```
Add the kickoff mutation (next to `advanceMutation`):
```tsx
  const kickoffMutation = useMutation<LiveMatchState>({
    mutationFn: () => kickoffLiveMatch(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['liveMatch', saveGameId] })
      void navigate(`/match?saveGameId=${saveGameId}`)
    },
  })
```
Add the advance handler and use it on the button:
```tsx
  const handleAdvance = () => {
    if (liveMatch) { void navigate(`/match?saveGameId=${saveGameId}`); return }
    if (preview?.managedClubPlaysNext) { kickoffMutation.mutate(); return }
    advanceMutation.mutate()
  }
```
Change the Avançar button:
```tsx
          <Button
            color="primary"
            disabled={advanceDisabled || kickoffMutation.isPending}
            title={advanceBlockReason ?? undefined}
            onClick={handleAdvance}
          >
            {liveMatch ? 'Retomar partida'
              : advanceMutation.isPending || kickoffMutation.isPending ? 'Avançando…' : 'Avançar'}
          </Button>
```
Note: `advanceDisabled` already includes `advanceMutation.isPending`; the gate blockers (no-lineup / ineligible / window) still apply before kickoff, which is correct — you must fix the lineup before starting the match.

- [ ] **Step 4: Run the test — verify green**

Run: `cd frontend && npx vitest run src/components/Layout.liveMatch.test.tsx`
Expected: PASS.

- [ ] **Step 5: Full frontend suite + typecheck**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`
Expected: all tests PASS; typecheck clean.

- [ ] **Step 6: Prepare the commit (do not run)**

```bash
git add frontend/src/components/Layout.tsx frontend/src/components/Layout.liveMatch.test.tsx
git commit -m "feat(live-match): Avançar forks into the live match and resumes it on reload"
```

---

## Final verification (after all tasks)

- [ ] **Backend:** `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test -p backend` — green except the known pre-existing `SeedLoaderTest` failure (confirm no NEW failures).
- [ ] **Frontend:** `cd frontend && npx vitest run && npx tsc --noEmit` — all green, typecheck clean.
- [ ] **Manual (docker-compose up):** create a Criciúma career, save an XI + bench, reach a matchday. **Avançar** enters `/match`; the clock ticks with commentary; **Pausar** stops it; a **Substituição** decrements the counter and shows a 🔁 in the feed; a timed injury pauses with the decision banner; at full time **Ver resumo** shows the same news card as before; a player who played has reduced fitness (partial-minutes) while an unused sub does not; a mid-match reload resumes the game; the other league results appear on the summary.

