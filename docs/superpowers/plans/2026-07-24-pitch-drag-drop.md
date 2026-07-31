# Pitch Drag-and-Drop Slot Assignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drag a player token on the tactics pitch to another spot to really assign him to that formation slot — persisted with the lineup and honored by the match engine's out-of-position grading.

**Architecture:** A nullable `slot_index` on `lineup_players` is the persistence; the slot index into the shared ordered slot list (backend `FormationSlots.of(formation)` ≡ frontend `pitchSlots(formation)`, index 0 = GK) is the contract. `TacticsService` pins persisted assignments and greedily fills leftovers (subs, legacy lineups) via the existing `SlotAssigner`. The frontend keeps a `slotByPlayerId` map in `SquadPage`, mirrors the backend greedy assigner for defaults, and adds hand-rolled pointer-event drag to `Pitch` (opt-in via an `onSwap` prop).

**Tech Stack:** Spring Boot 4 + Flyway + PostgreSQL (Testcontainers ITs), React + TypeScript + MUI + TanStack Query, Vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-07-24-pitch-drag-drop-design.md`

## Global Constraints

- The slot-order mirror is load-bearing: backend `FormationSlots.of(f)` and frontend `pitchSlots(f)` list the same 11 slots in the same order (GK first, then DEF left→right, MID deepest row first, ATT). Never reorder either side.
- Slot 0 is the GK slot in every formation.
- Error code for all new lineup rejections: existing `ErrorCode.INVALID_LINEUP` (422). No new enum values.
- All user-facing UI copy is pt-BR.
- Frontend type-check: `cd frontend && npx tsc -b` (NOT `tsc --noEmit` — it is a no-op in this repo).
- `git diff` requires `--no-ext-diff` in this environment.
- Backend tests: `cd backend && ./gradlew test`. Concurrent sessions may cause Gradle `EOFException`/`NoSuchFileException` — that is build contention, not a code bug; retry.
- Work directly on `main` (solo workflow, no feature branches).
- New/changed code comments in English, matching each file's existing density.

---

### Task 1: Backend schema + DTO plumbing (`slot_index` column, `LineupStarter.slotIndex`)

**Files:**
- Create: `backend/src/main/resources/db/migration/V40__lineup_slot_index.sql`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupPlayer.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupStarter.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `LineupPlayer.getSlotIndex()/setSlotIndex(Integer)`; `LineupStarter` record with 4th component `Integer slotIndex` plus convenience constructors `(UUID, String)` and `(UUID, String, String)` that leave it null. Task 2 and 3 build on these exact signatures.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V40__lineup_slot_index.sql`:

```sql
-- Explicit formation-slot assignment for a starter: index into FormationSlots.of(formation)
-- (0 = GK slot). NULL for bench rows and legacy lineups saved before drag-and-drop existed —
-- the engine then falls back to greedy assignment.
ALTER TABLE lineup_players ADD COLUMN slot_index INT;
```

- [ ] **Step 2: Map the column on the entity**

In `LineupPlayer.java`, after the `instruction` field, add:

```java
    /**
     * Index of the formation slot this starter occupies (0 = GK slot; order of
     * {@code FormationSlots.of}). Null for bench rows and legacy lineups — the engine then
     * falls back to greedy assignment.
     */
    @Column(name = "slot_index")
    private Integer slotIndex;
```

And with the other accessors:

```java
    public Integer getSlotIndex() { return slotIndex; }
    public void setSlotIndex(Integer slotIndex) { this.slotIndex = slotIndex; }
```

- [ ] **Step 3: Extend the `LineupStarter` record**

Replace the record declaration in `LineupStarter.java` (keep the existing class javadoc, extend it with one line about `slotIndex`):

```java
public record LineupStarter(@NotNull UUID playerId, @NotBlank String role, String instruction,
                            Integer slotIndex) {

    /**
     * Convenience constructor for a starter with no personal instruction and no explicit slot.
     * Keeps the many {@code (playerId, role)} call sites (tests) compiling unchanged.
     */
    public LineupStarter(UUID playerId, String role) {
        this(playerId, role, null, null);
    }

    /** Starter with an instruction but no explicit slot (pre-drag-and-drop call sites). */
    public LineupStarter(UUID playerId, String role, String instruction) {
        this(playerId, role, instruction, null);
    }
}
```

- [ ] **Step 4: Fix any 3-arg positional construction sites**

Run: `grep -rn "new LineupStarter(" backend/src/main/java backend/src/test/java`
Any call site passing exactly `(playerId, role, instruction)` still compiles via the new
3-arg constructor; nothing should need changes. If a call site destructures or pattern-matches
the record, add the fourth component.

- [ ] **Step 5: Run the backend build + lineup tests**

Run: `cd backend && ./gradlew test --tests '*Lineup*' --tests 'TacticsApiIT'`
Expected: BUILD SUCCESSFUL — pure plumbing, no behavior change. (Retry on Gradle contention errors.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V40__lineup_slot_index.sql backend/src/main/java/br/com/carvoeiromanager/match/LineupPlayer.java backend/src/main/java/br/com/carvoeiromanager/match/LineupStarter.java
git commit -m "feat(lineup): slot_index column + LineupStarter.slotIndex plumbing"
```

---

### Task 2: Backend `setLineup` slot validation + persistence + `getLineup` echo

**Files:**
- Create: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceSlotIndexIT.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java`

**Interfaces:**
- Consumes: Task 1's `LineupStarter.slotIndex()`, `LineupPlayer.setSlotIndex/getSlotIndex`.
- Produces: `setLineup` accepts+persists slot indexes (all-or-nothing, permutation 0–10, GK at slot 0, bench must be null); `getLineup` and the `setLineup` response echo `slotIndex` per starter. Task 7's save payload relies on exactly these rules.

- [ ] **Step 1: Write the failing IT**

Create `LineupServiceSlotIndexIT.java` (mirror the structure of `LineupServiceIT` — Testcontainers, real career):

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.assertj.core.api.ThrowableAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ITs for the explicit slot assignment on setLineup/getLineup: all-or-nothing slotIndexes,
 * permutation of 0..10, goalkeeper pinned to slot 0, bench never carries one, and the
 * save→get roundtrip echoes what was sent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupServiceSlotIndexIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private record Career(UUID userId, UUID saveGameId, UUID clubId) {}

    /** The one rejection contract every invalid-slot request shares: ApiException + INVALID_LINEUP. */
    private void assertRejected(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        apiEx -> assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP));
    }

    private Career createCareer() {
        User user = User.create("slotidx_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save.id(), save.managedClubId());
    }

    /**
     * A valid XI where the GK takes slot 0 and the outfielders take 1..10 in bestXi order.
     * Some may be "out of position" for their index — legal by design.
     */
    private List<LineupStarter> startersWithSlots(UUID clubId) {
        List<Player> xi = lineupService.bestXi(clubId);
        List<LineupStarter> starters = new ArrayList<>();
        int next = 1;
        for (Player p : xi) {
            int slot = p.getPosition() == Position.GK ? 0 : next++;
            starters.add(new LineupStarter(p.getId(),
                    PlayerRole.defaultFor(p.getPosition()).name(), null, slot));
        }
        return starters;
    }

    private List<LineupStarter> startersWithoutSlots(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());
    }

    @Test
    void savesSlotIndexesAndGetLineupEchoesThemBack() {
        Career career = createCareer();
        List<LineupStarter> starters = startersWithSlots(career.clubId());

        LineupResponse saved = lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId());
        assertThat(saved.starters()).extracting(LineupStarter::slotIndex)
                .containsExactlyInAnyOrderElementsOf(
                        starters.stream().map(LineupStarter::slotIndex).toList());

        LineupResponse loaded = lineupService.getLineup(career.saveGameId(), career.clubId(),
                career.userId()).orElseThrow();
        for (LineupStarter sent : starters) {
            LineupStarter got = loaded.starters().stream()
                    .filter(s -> s.playerId().equals(sent.playerId())).findFirst().orElseThrow();
            assertThat(got.slotIndex()).isEqualTo(sent.slotIndex());
        }
    }

    @Test
    void rejectsMixedNullAndNonNullSlotIndexes() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        LineupStarter last = starters.remove(starters.size() - 1);
        starters.add(new LineupStarter(last.playerId(), last.role(), null, null));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsDuplicateSlotIndexes() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        // Break the permutation: give the last outfielder the same index as the previous one.
        LineupStarter a = starters.get(9);
        LineupStarter b = starters.remove(10);
        starters.add(new LineupStarter(b.playerId(), b.role(), null, a.slotIndex()));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsOutOfRangeSlotIndex() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        LineupStarter last = starters.remove(starters.size() - 1);
        starters.add(new LineupStarter(last.playerId(), last.role(), null, 11));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsNonGoalkeeperAtSlotZero() {
        Career career = createCareer();
        // Swap slot 0 (GK) with slot 1 (an outfielder).
        List<LineupStarter> starters = startersWithSlots(career.clubId()).stream()
                .map(s -> s.slotIndex() == 0 ? new LineupStarter(s.playerId(), s.role(), null, 1)
                        : s.slotIndex() == 1 ? new LineupStarter(s.playerId(), s.role(), null, 0)
                        : s)
                .collect(Collectors.toList());

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsBenchEntryWithSlotIndex() {
        Career career = createCareer();
        List<LineupStarter> starters = startersWithSlots(career.clubId());
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(career.clubId());
        Player benchPlayer = squad.stream()
                .filter(p -> starters.stream().noneMatch(s -> s.playerId().equals(p.getId())))
                .filter(Player::isEligible)
                .findFirst().orElseThrow();
        List<LineupStarter> bench = List.of(new LineupStarter(benchPlayer.getId(),
                PlayerRole.defaultFor(benchPlayer.getPosition()).name(), null, 5));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, bench), career.userId()));
    }

    @Test
    void legacyRequestWithoutSlotIndexesStillSavesAndReadsBackNulls() {
        Career career = createCareer();
        LineupResponse saved = lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", startersWithoutSlots(career.clubId()), null),
                career.userId());
        assertThat(saved.starters()).hasSize(11);

        LineupResponse loaded = lineupService.getLineup(career.saveGameId(), career.clubId(),
                career.userId()).orElseThrow();
        assertThat(loaded.starters()).extracting(LineupStarter::slotIndex).containsOnlyNulls();
    }
}
```

Note: `Player.isEligible()` exists (Player.java:264) and `ApiException.getCode()` is the
accessor — the `assertRejected` helper matches the assertion style `LineupServiceIT` uses.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd backend && ./gradlew test --tests 'LineupServiceSlotIndexIT'`
Expected: FAIL — `savesSlotIndexesAndGetLineupEchoesThemBack` gets nulls back (slotIndex not persisted), rejection tests fail because no exception is thrown. `legacyRequest…` may already pass.

- [ ] **Step 3: Implement in `LineupService`**

3a. In `setLineup`, after the `validateAndParseInstructions` calls and before any persistence, add:

```java
        validateSlotIndexes(starters, bench, clubPlayers);
```

3b. In the starter persist loop, set the index:

```java
        for (LineupStarter starter : starters) {
            LineupPlayer lp = LineupPlayer.create(lineupId, starter.playerId(), true, starterRoles.get(starter.playerId()));
            lp.setInstruction(starterInstructions.get(starter.playerId()));
            lp.setSlotIndex(starter.slotIndex());
            lineupPlayerRepository.save(lp);
        }
```

3c. In `toLineupStarter` (the mapper `getLineup` uses), include `lp.getSlotIndex()` as the 4th
component of the returned `LineupStarter`.

3d. Add the validator (near the other `validate*` methods):

```java
    /**
     * Validates the optional explicit slot assignment: either every starter carries a slotIndex
     * (the 11 values must then be a permutation of 0..10, and the player at slot 0 — the GK slot
     * in every formation — must be a goalkeeper) or none does (legacy greedy assignment).
     * Bench entries never carry one. Runs before any delete/insert so a bad request rejects the
     * whole call without partial mutation.
     */
    private void validateSlotIndexes(List<LineupStarter> starters, List<LineupStarter> bench,
                                     List<Player> clubPlayers) {
        if (bench != null && bench.stream().anyMatch(s -> s.slotIndex() != null)) {
            throw new ApiException(ErrorCode.INVALID_LINEUP, "Bench players cannot carry a slotIndex");
        }
        long withSlot = starters.stream().filter(s -> s.slotIndex() != null).count();
        if (withSlot == 0) {
            return; // legacy request — the engine falls back to greedy assignment
        }
        if (withSlot != starters.size()) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "Either every starter carries a slotIndex or none does");
        }
        Set<Integer> indexes = starters.stream().map(LineupStarter::slotIndex).collect(Collectors.toSet());
        if (indexes.size() != starters.size()
                || indexes.stream().anyMatch(i -> i < 0 || i >= starters.size())) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "Starter slotIndexes must be a permutation of 0.." + (starters.size() - 1));
        }
        UUID slotZeroPlayerId = starters.stream().filter(s -> s.slotIndex() == 0)
                .map(LineupStarter::playerId).findFirst().orElseThrow();
        Player slotZeroPlayer = clubPlayers.stream()
                .filter(p -> p.getId().equals(slotZeroPlayerId)).findFirst().orElse(null);
        if (slotZeroPlayer == null || slotZeroPlayer.getPosition() != Position.GK) {
            throw new ApiException(ErrorCode.INVALID_LINEUP, "Slot 0 is the goalkeeper slot");
        }
    }
```

Add missing imports (`Position`, `Set`, `Collectors`) if not already present. `starters` is
guaranteed non-null/size-11 here because `validatePlayerCount` ran first — place the new call
AFTER it (the instruction in 3a already does).

- [ ] **Step 4: Run the IT again**

Run: `cd backend && ./gradlew test --tests 'LineupServiceSlotIndexIT'`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Run the full lineup/tactics suites for regressions**

Run: `cd backend && ./gradlew test --tests '*Lineup*' --tests 'Tactics*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceSlotIndexIT.java
git commit -m "feat(lineup): validate + persist explicit slot assignment"
```

---

### Task 3: Engine honors persisted slots (`SlotAssigner` pinned overload + `TacticsService`)

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerTest.java` (append)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java` (append)

**Interfaces:**
- Consumes: Task 1's `LineupPlayer.getSlotIndex()`.
- Produces: `SlotAssigner.assignToSlots(List<Player> xi, String formation, Map<UUID, Integer> pinnedSlotIndexes)` — pinned players get exactly their slot; leftovers are greedily assigned to remaining slots. The 2-arg `assignToSlots` delegates with `Map.of()` (behavior unchanged).

- [ ] **Step 1: Write the failing unit tests (append to `SlotAssignerTest`)**

Read `SlotAssignerTest.java` first and reuse its existing `detailed(...)`/XI helpers. Append:

```java
    @Test
    void pinnedAssignmentsAreHonoredVerbatimEvenOutOfPosition() {
        List<Player> xi = wellFormed442();
        // Pin a natural ZAGUEIRO into the last slot (a striker slot in 4-4-2): greedy would
        // never do this while his exact slot is free, so honoring it proves the override.
        Player zagueiro = xi.stream()
                .filter(p -> p.getDetailedPosition() == DetailedPosition.ZAGUEIRO)
                .findFirst().orElseThrow();
        Map<UUID, Integer> pinned = Map.of(zagueiro.getId(), 10);

        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2", pinned);

        assertThat(assignments).hasSize(11);
        assertThat(assignments.get(zagueiro.getId()))
                .isEqualTo(FormationSlots.of("4-4-2").get(10));
        assertThat(assignments.get(zagueiro.getId()).accepted())
                .contains(DetailedPosition.ATACANTE);
    }

    @Test
    void leftoverPlayerInheritsTheSingleVacatedSlot() {
        List<Player> xi = wellFormed442();
        // Pin 10 of the 11 to slots 0..9; the unpinned one must get slot 10.
        Map<UUID, Integer> pinned = new java.util.HashMap<>();
        for (int i = 0; i < 10; i++) {
            pinned.put(xi.get(i).getId(), i);
        }
        Player leftover = xi.get(10);

        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2", pinned);

        assertThat(assignments).hasSize(11);
        assertThat(assignments.get(leftover.getId()))
                .isEqualTo(FormationSlots.of("4-4-2").get(10));
    }

    @Test
    void emptyPinnedMapMatchesTheLegacyGreedyAssignment() {
        List<Player> xi = wellFormed442();
        assertThat(SlotAssigner.assignToSlots(xi, "4-4-2", Map.of()))
                .isEqualTo(SlotAssigner.assignToSlots(xi, "4-4-2"));
    }
```

`wellFormed442()` is an existing helper in this test class building 11 players whose detailed
positions exactly fit 4-4-2, in slot order — if its players are NOT in slot order, adapt
`leftoverPlayerInheritsTheSingleVacatedSlot` to pin by matching detailed position to
`FormationSlots.of("4-4-2").get(i).accepted()` instead of by list order; the assertion stays
"the unpinned player gets the one unpinned slot".

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'SlotAssignerTest'`
Expected: FAIL to compile — `assignToSlots(List, String, Map)` does not exist.

- [ ] **Step 3: Implement the pinned overload in `SlotAssigner`**

Change the existing 2-arg `assignToSlots` body to delegate, and add the 3-arg version:

```java
    static Map<UUID, FormationSlots.Slot> assignToSlots(List<Player> xi, String formation) {
        return assignToSlots(xi, formation, Map.of());
    }

    /**
     * Like {@link #assignToSlots(List, String)} but honoring explicit pinned assignments first:
     * every XI member present in {@code pinnedSlotIndexes} (with a valid, unclaimed index) gets
     * exactly that slot; everyone else is greedily assigned to the remaining slots. A substitute
     * who came on mid-match is the common leftover — he inherits the vacated slot.
     */
    static Map<UUID, FormationSlots.Slot> assignToSlots(List<Player> xi, String formation,
                                                        Map<UUID, Integer> pinnedSlotIndexes) {
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, FormationSlots.Slot> assignments = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        List<Player> leftovers = new ArrayList<>();
        for (Player p : xi) {
            Integer idx = pinnedSlotIndexes.get(p.getId());
            if (idx != null && idx >= 0 && idx < slots.size() && taken.add(idx)) {
                assignments.put(p.getId(), slots.get(idx));
            } else {
                leftovers.add(p);
            }
        }
        List<FormationSlots.Slot> freeSlots = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!taken.contains(i)) {
                freeSlots.add(slots.get(i));
            }
        }
        Comparator<Player> byRating =
                Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);
        Player[] picked = assign(freeSlots, leftovers, byRating);
        for (int i = 0; i < picked.length; i++) {
            if (picked[i] != null) {
                assignments.put(picked[i].getId(), freeSlots.get(i));
            }
        }
        return assignments;
    }
```

Add `HashSet` to the imports. Update the class javadoc's "pure function of (slots, pool)"
sentence to mention the pinned variant.

- [ ] **Step 4: Run the unit tests**

Run: `cd backend && ./gradlew test --tests 'SlotAssignerTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing IT (append to `TacticsServiceIT`)**

```java
    @Test
    void honorsPersistedSlotIndexesInTheTacticalProfile() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player gk = squad.stream().filter(p -> p.getPosition() == br.com.carvoeiromanager.player.Position.GK)
                .findFirst().orElseThrow();
        List<Player> outfield = squad.stream().filter(p -> p != gk).limit(10).toList();
        List<Player> xi = new ArrayList<>();
        xi.add(gk);
        xi.addAll(outfield);

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineupRepository.save(lineup);
        // Persist GK -> slot 0, then the outfielders in REVERSE order into slots 1..10, so the
        // arrangement cannot coincide with what greedy would pick.
        LineupPlayer gkRow = LineupPlayer.create(lineup.getId(), gk.getId(), true, PlayerRole.GOALKEEPER);
        gkRow.setSlotIndex(0);
        lineupPlayerRepository.save(gkRow);
        for (int i = 0; i < outfield.size(); i++) {
            Player p = outfield.get(outfield.size() - 1 - i);
            LineupPlayer row = LineupPlayer.create(lineup.getId(), p.getId(), true,
                    PlayerRole.defaultFor(p.getPosition()));
            row.setSlotIndex(i + 1);
            lineupPlayerRepository.save(row);
        }

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);

        List<FormationSlots.Slot> slots = FormationSlots.of("4-3-3");
        assertThat(profile.slotAssignments().get(gk.getId())).isEqualTo(slots.get(0));
        for (int i = 0; i < outfield.size(); i++) {
            Player p = outfield.get(outfield.size() - 1 - i);
            assertThat(profile.slotAssignments().get(p.getId()))
                    .as("player pinned to slot %d", i + 1)
                    .isEqualTo(slots.get(i + 1));
        }
    }

    @Test
    void substituteWithoutPersistedSlotInheritsTheVacatedSlot() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player gk = squad.stream().filter(p -> p.getPosition() == br.com.carvoeiromanager.player.Position.GK)
                .findFirst().orElseThrow();
        List<Player> outfield = squad.stream().filter(p -> p != gk).limit(11).toList();
        // Persist a full XI (gk + first 10 outfielders) with slots 0..10 …
        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineupRepository.save(lineup);
        LineupPlayer gkRow = LineupPlayer.create(lineup.getId(), gk.getId(), true, PlayerRole.GOALKEEPER);
        gkRow.setSlotIndex(0);
        lineupPlayerRepository.save(gkRow);
        for (int i = 0; i < 10; i++) {
            LineupPlayer row = LineupPlayer.create(lineup.getId(), outfield.get(i).getId(), true,
                    PlayerRole.defaultFor(outfield.get(i).getPosition()));
            row.setSlotIndex(i + 1);
            lineupPlayerRepository.save(row);
        }
        // …but the LIVE XI has the 11th outfielder on for the player who held slot 5.
        Player replaced = outfield.get(4); // slot 5
        Player sub = outfield.get(10);     // no lineup row at all
        List<Player> liveXi = new ArrayList<>();
        liveXi.add(gk);
        for (int i = 0; i < 10; i++) {
            liveXi.add(outfield.get(i) == replaced ? sub : outfield.get(i));
        }

        TacticalProfile profile = tacticsService.profileFor(save, club, liveXi);

        assertThat(profile.slotAssignments().get(sub.getId()))
                .isEqualTo(FormationSlots.of("4-3-3").get(5));
        assertThat(profile.slotAssignments()).doesNotContainKey(replaced.getId());
    }
```

- [ ] **Step 6: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'TacticsServiceIT'`
Expected: the two new tests FAIL (greedy ignores the persisted indexes); existing tests PASS.

- [ ] **Step 7: Wire the pinned map in `TacticsService.fromLineup`**

In `fromLineup`, collect the pinned indexes in the existing starter loop and pass them through:

```java
    private TacticalProfile fromLineup(Lineup lineup, List<Player> xi) {
        Map<UUID, PlayerRole> persisted = new HashMap<>();
        Map<UUID, PlayerInstruction> instructions = new HashMap<>();
        Map<UUID, Integer> pinnedSlots = new HashMap<>();
        lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .forEach(lp -> {
                    persisted.put(lp.getPlayerId(), lp.getRole());
                    if (lp.getInstruction() != null) {
                        instructions.put(lp.getPlayerId(), lp.getInstruction());
                    }
                    if (lp.getSlotIndex() != null) {
                        pinnedSlots.put(lp.getPlayerId(), lp.getSlotIndex());
                    }
                });
```

and replace the assignment line:

```java
        // Persisted (drag-and-drop) slots are honored verbatim; XI members without one — subs
        // who came on, legacy lineups — fill the remaining slots greedily.
        Map<UUID, FormationSlots.Slot> slotAssignments =
                SlotAssigner.assignToSlots(xi, lineup.getFormation(), pinnedSlots);
```

Keep the surrounding comments coherent (the old "Grade each starter…" comment is replaced by
the one above).

- [ ] **Step 8: Run the ITs**

Run: `cd backend && ./gradlew test --tests 'TacticsServiceIT' --tests 'SlotAssignerTest'`
Expected: PASS.

- [ ] **Step 9: Run the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL (simulation determinism tests must stay green — legacy lineups
take the `Map.of()` path, which is byte-identical).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerTest.java backend/src/test/java/br/com/carvoeiromanager/match/TacticsServiceIT.java
git commit -m "feat(match): engine honors persisted slot assignments"
```

---

### Task 4: Frontend types + client-side greedy assigner (`slotAssign.ts`)

**Files:**
- Modify: `frontend/src/api/types.ts` (the `LineupStarter` type)
- Create: `frontend/src/features/pitch/slotAssign.ts`
- Test: `frontend/src/features/pitch/slotAssign.test.ts`

**Interfaces:**
- Consumes: `pitchSlots(formation)` from `frontend/src/features/pitch/pitchSlots.ts`.
- Produces: `assignSlots(players: AssignablePlayer[], formation: string): Record<string, number>` and `type AssignablePlayer = { id: string; position: 'GK' | 'DEF' | 'MID' | 'ATT'; detailedPosition?: DetailedPosition | null; overall: number }`. `LineupStarter.slotIndex?: number | null`. Task 7 imports both.

- [ ] **Step 1: Extend the TS type**

In `frontend/src/api/types.ts`, add to `LineupStarter`:

```ts
export type LineupStarter = {
  playerId: string
  role: PlayerRole
  /** Optional per-player instruction; null (or omitted) means none. */
  instruction: PlayerInstruction | null
  /**
   * Explicit formation slot (index into pitchSlots(formation); 0 = GK). Null/omitted on bench
   * entries and legacy lineups — the engine then assigns greedily. Optional here only to keep
   * older test fixtures compiling.
   */
  slotIndex?: number | null
}
```

- [ ] **Step 2: Write the failing unit tests**

Create `frontend/src/features/pitch/slotAssign.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { assignSlots, type AssignablePlayer } from './slotAssign'
import { pitchSlots } from './pitchSlots'
import type { DetailedPosition } from '../../api/types'

function p(id: string, position: AssignablePlayer['position'],
           detailedPosition: DetailedPosition | null, overall: number): AssignablePlayer {
  return { id, position, detailedPosition, overall }
}

// A well-formed 4-4-2 XI: one exact candidate per slot, in slot order.
const wellFormed442: AssignablePlayer[] = [
  p('gk', 'GK', 'GOLEIRO', 75),
  p('ld', 'DEF', 'LATERAL_DIREITO', 74),
  p('z1', 'DEF', 'ZAGUEIRO', 73),
  p('z2', 'DEF', 'ZAGUEIRO', 72),
  p('le', 'DEF', 'LATERAL_ESQUERDO', 71),
  p('vol', 'MID', 'VOLANTE', 70),
  p('m1', 'MID', 'MEIA_ARMADOR', 69),
  p('m2', 'MID', 'MEIA_ARMADOR', 68),
  p('m3', 'MID', 'MEIA_ARMADOR', 67),
  p('a1', 'ATT', 'ATACANTE', 66),
  p('a2', 'ATT', 'ATACANTE', 65),
]

describe('assignSlots', () => {
  it('assigns a well-formed XI so every player sits in a slot accepting his detailed position', () => {
    const result = assignSlots(wellFormed442, '4-4-2')
    const slots = pitchSlots('4-4-2')
    expect(Object.keys(result)).toHaveLength(11)
    expect(new Set(Object.values(result)).size).toBe(11)
    for (const player of wellFormed442) {
      expect(slots[result[player.id]].accepted).toContain(player.detailedPosition)
    }
    expect(result['gk']).toBe(0)
  })

  it('lets an exact detailed match win its slot over a higher-rated same-band player', () => {
    const players = [
      ...wellFormed442.filter(x => x.id !== 'ld'),
      p('lowLd', 'DEF', 'LATERAL_DIREITO', 55),
    ]
    const result = assignSlots(players, '4-4-2')
    // Slot 1 is the right-back slot in 4-4-2: the only LATERAL_DIREITO takes it despite
    // every other defender out-rating him.
    expect(result['lowLd']).toBe(1)
  })

  it('fills every slot from tier-3 when nobody fits (all-zagueiro squad)', () => {
    const players = Array.from({ length: 11 }, (_, i) =>
      p(`z${i}`, 'DEF', 'ZAGUEIRO', 80 - i))
    const result = assignSlots(players, '4-4-2')
    expect(Object.keys(result)).toHaveLength(11)
    expect(new Set(Object.values(result)).size).toBe(11)
  })

  it('is deterministic across repeated calls', () => {
    const shuffled = [...wellFormed442].reverse()
    expect(assignSlots(shuffled, '4-3-3')).toEqual(assignSlots(shuffled, '4-3-3'))
  })

  it('assigns a partial squad (fewer than 11) to distinct slots without throwing', () => {
    const five = wellFormed442.slice(0, 5)
    const result = assignSlots(five, '4-4-2')
    expect(Object.keys(result)).toHaveLength(5)
    expect(new Set(Object.values(result)).size).toBe(5)
  })
})
```

- [ ] **Step 3: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/pitch/slotAssign.test.ts`
Expected: FAIL — module `./slotAssign` not found.

- [ ] **Step 4: Implement `slotAssign.ts`**

```ts
import { pitchSlots } from './pitchSlots'
import type { DetailedPosition } from '../../api/types'

export type AssignablePlayer = {
  id: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  detailedPosition?: DetailedPosition | null
  overall: number
}

/**
 * Client-side mirror of the backend SlotAssigner's greedy tiered best-fit, used for default
 * arrangements (auto-pick, formation change, legacy lineups without persisted slots). Slots are
 * processed scarcest-first (fewest exact-detailed candidates in the initial pool, ties by slot
 * index); each slot takes the best remaining candidate by tier — 1 = the slot accepts the
 * player's detailed position, 2 = same coarse line, 3 = anyone — ties by overall desc then id.
 * Deterministic for a given (players, formation). Exact tie-break parity with the backend's
 * UUID ordering is not required (arrangements are persisted from whichever side computed them);
 * determinism is.
 */
export function assignSlots(players: AssignablePlayer[], formation: string): Record<string, number> {
  const slots = pitchSlots(formation)
  const tier1Count = slots.map(
    slot => players.filter(p => p.detailedPosition && slot.accepted.includes(p.detailedPosition)).length,
  )
  const order = slots.map((_, i) => i).sort((a, b) => tier1Count[a] - tier1Count[b] || a - b)
  const byRating = (a: AssignablePlayer, b: AssignablePlayer) =>
    b.overall - a.overall || b.id.localeCompare(a.id)
  const available = [...players]
  const out: Record<string, number> = {}
  for (const slotIndex of order) {
    if (available.length === 0) break
    const slot = slots[slotIndex]
    const tier1 = available.filter(p => p.detailedPosition && slot.accepted.includes(p.detailedPosition))
    const tier2 = available.filter(p => p.position === slot.line)
    const pool = tier1.length > 0 ? tier1 : tier2.length > 0 ? tier2 : available
    const choice = [...pool].sort(byRating)[0]
    out[choice.id] = slotIndex
    available.splice(available.indexOf(choice), 1)
  }
  return out
}
```

- [ ] **Step 5: Run tests + type-check**

Run: `cd frontend && npx vitest run src/features/pitch/slotAssign.test.ts && npx tsc -b`
Expected: PASS, no type errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/pitch/slotAssign.ts frontend/src/features/pitch/slotAssign.test.ts
git commit -m "feat(pitch): LineupStarter.slotIndex type + client-side greedy slot assigner"
```

---

### Task 5: Pitch renders by explicit `slotIndex`

**Files:**
- Modify: `frontend/src/features/pitch/Pitch.tsx`
- Test: `frontend/src/features/pitch/Pitch.test.tsx` (append)

**Interfaces:**
- Consumes: nothing new (self-contained rendering change).
- Produces: `PlacedPlayer.slotIndex?: number`; when **every** player of a team carries one, placement uses it (unassigned indexes render placeholder tokens); otherwise the legacy line-zip runs unchanged. Every token wrapper now carries `data-slot-index={i}` (i = index into `pitchSlots(formation)`), which Task 6's drag and Task 7's tests rely on.

- [ ] **Step 1: Write the failing tests (append to `Pitch.test.tsx`)**

Read the existing test file first and reuse its fixture helpers/style. Append (adapting the
`TeamOnPitch` construction to whatever helper exists — the essential fixture is shown inline):

```tsx
describe('explicit slot placement', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }
  const placed = (id: string, surname: string, position: 'GK' | 'DEF' | 'MID' | 'ATT',
                  slotIndex: number): PlacedPlayer =>
    ({ id, surname, position, detailedPosition: null, fitness: 100, slotIndex })

  it('places each player at his explicit slot, not by list order', () => {
    // Two ATT players given in one order but slotIndexes in the other: explicit wins.
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [
        placed('gk', 'Golem', 'GK', 0),
        placed('a-right', 'Direita', 'ATT', 10),
        placed('a-left', 'Esquerda', 'ATT', 8),
      ],
    }
    const { container } = render(<Pitch home={home} />)
    expect(container.querySelector('[data-slot-index="8"]')).toHaveTextContent('Esquerda')
    expect(container.querySelector('[data-slot-index="10"]')).toHaveTextContent('Direita')
    expect(container.querySelector('[data-slot-index="0"]')).toHaveTextContent('Golem')
  })

  it('renders unassigned slots as empty placeholders', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [placed('gk', 'Golem', 'GK', 0)],
    }
    const { container } = render(<Pitch home={home} />)
    // 11 slots total: 1 occupied + 10 placeholders.
    expect(container.querySelectorAll('[data-slot-index]')).toHaveLength(11)
    expect(container.querySelectorAll('[data-testid="player-token"][data-empty="true"]')).toHaveLength(10)
  })

  it('falls back to line-zip placement when any player lacks a slotIndex', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [
        { id: 'gk', surname: 'Golem', position: 'GK', detailedPosition: null, fitness: 100 },
        { id: 'd1', surname: 'Zaga', position: 'DEF', detailedPosition: null, fitness: 100 },
      ],
    }
    const { container } = render(<Pitch home={home} />)
    // Legacy zip: the first DEF fills the first DEF slot (index 1 in 4-3-3).
    expect(container.querySelector('[data-slot-index="1"]')).toHaveTextContent('Zaga')
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: new tests FAIL (`data-slot-index` absent, explicit placement not implemented);
existing tests PASS.

- [ ] **Step 3: Implement in `Pitch.tsx`**

3a. Add `slotIndex` to `PlacedPlayer`:

```tsx
export type PlacedPlayer = {
  id: string
  surname: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  /** Fine-grained position; when set and not accepted by the slot, the token gets a warn tint. */
  detailedPosition?: DetailedPosition | null
  /**
   * Explicit formation slot (index into pitchSlots(formation)). When EVERY player of a team
   * carries one, placement uses it; otherwise the legacy line-zip below runs.
   */
  slotIndex?: number
  fitness: number
  badges?: TokenBadges
}
```

3b. Add the explicit-placement path next to `assign` (keep `assign` untouched):

```tsx
// Places players by their explicit slotIndex; slots nobody claims stay on the pitch as empty
// placeholders. Used when every player carries a slotIndex; otherwise `assign` line-zips.
function placeExplicit(team: TeamOnPitch): Positioned[] {
  const slots = pitchSlots(team.formation)
  const bySlot = new Map(team.players.map(p => [p.slotIndex as number, p]))
  return slots.map((slot, i) => ({ slot, number: i + 1, player: bySlot.get(i) ?? null }))
}

function place(team: TeamOnPitch): Positioned[] {
  return team.players.every(p => p.slotIndex != null) ? placeExplicit(team) : assign(team)
}
```

3c. In `TeamLayer`, iterate `place(team)` instead of `assign(team)`, and add the slot index to
the wrapper Box. Both placement paths emit one entry per slot in slot order, so the map index
IS the slot index:

```tsx
      {place(team).map(({ slot, number, player }, i) => {
        …
          <Box
            key={player?.id ?? `slot-${number}`}
            data-slot-index={i}
            sx={{ position: 'absolute', left: `${xPct}%`, top: `${yPct}%`, transform: 'translate(-50%, -50%)' }}
          >
```

- [ ] **Step 4: Run the Pitch tests + the suites that render pitches**

Run: `cd frontend && npx vitest run src/features/pitch/ src/features/match/ src/features/squad/SquadPage.test.tsx && npx tsc -b`
Expected: ALL PASS (legacy zip path untouched for teams without slotIndex).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pitch/Pitch.tsx frontend/src/features/pitch/Pitch.test.tsx
git commit -m "feat(pitch): explicit per-slot placement via PlacedPlayer.slotIndex"
```

---

### Task 6: Pitch drag-and-drop interaction

**Files:**
- Modify: `frontend/src/features/pitch/Pitch.tsx`
- Test: `frontend/src/features/pitch/Pitch.test.tsx` (append)

**Interfaces:**
- Consumes: Task 5's `place()` positioning and `data-slot-index` wrappers.
- Produces: `Pitch` prop `onSwap?: (fromSlot: number, toSlot: number) => void`. Its presence makes home-team outfield tokens draggable; drop resolution (occupied = swap vs empty = move) is the PARENT's job — Pitch only reports the two slot indexes. Task 7 passes `onSwap` and implements the state change.

- [ ] **Step 1: Write the failing tests (append to `Pitch.test.tsx`)**

jsdom has no layout, so mock the pitch container's rect; slot centers are then
`(slot.x * 400, slot.y * 600)`. For 4-3-3: slot 1 (right-back, x 0.16 / y 0.79) centers at
(64, 474); slot 9 (central striker, x 0.5 / y 0.32) at (200, 192); slot 0 (GK, x 0.5 / y 0.94)
at (200, 564).

```tsx
import { fireEvent } from '@testing-library/react'
// (merge into the existing @testing-library/react import)

describe('drag and drop', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  function fullXi(): PlacedPlayer[] {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT', slotIndex: number): PlacedPlayer =>
      ({ id, surname: id, position, detailedPosition: null, fitness: 100, slotIndex })
    return [
      mk('gk', 'GK', 0),
      mk('d1', 'DEF', 1), mk('d2', 'DEF', 2), mk('d3', 'DEF', 3), mk('d4', 'DEF', 4),
      mk('m1', 'MID', 5), mk('m2', 'MID', 6), mk('m3', 'MID', 7),
      mk('a1', 'ATT', 8), mk('a2', 'ATT', 9), mk('a3', 'ATT', 10),
    ]
  }

  function renderDraggable(players = fullXi()) {
    const onSwap = vi.fn()
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players }} onSwap={onSwap} />,
    )
    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const token = (slot: number) => container.querySelector(`[data-slot-index="${slot}"]`)!
    return { onSwap, container, token }
  }

  it('dropping a token on another slot reports both slot indexes', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
  })

  it('dropping onto an unoccupied slot also reports it (move)', () => {
    const players = fullXi().filter(p => p.slotIndex !== 9) // striker slot empty
    const { onSwap, token } = renderDraggable(players)
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
  })

  it('a drop far from any slot snaps back (no callback)', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 399, clientY: 10 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('the GK token is not draggable', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(0), { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(token(0), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('the GK slot is never a drop target', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 564 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('highlights the drop candidate while dragging over it', () => {
    const { token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(token(9)).toHaveAttribute('data-drop-candidate', 'true')
  })

  it('without onSwap the pitch stays inert', () => {
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players: fullXi() }} />,
    )
    const token = container.querySelector('[data-slot-index="1"]')!
    fireEvent.pointerDown(token, { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token, { pointerId: 1, clientX: 200, clientY: 192 })
    // No crash, no drag state — nothing to assert beyond absence of the candidate attr.
    expect(container.querySelector('[data-drop-candidate="true"]')).toBeNull()
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: new drag tests FAIL; everything else PASS.

- [ ] **Step 3: Implement drag in `Pitch.tsx`**

3a. New imports and constant:

```tsx
import { useRef, useState } from 'react'
```

```tsx
// A drop lands on the nearest droppable slot whose center is within this fraction of the
// pitch's rendered width; farther away, the token snaps back.
const DROP_RADIUS_FRACTION = 0.12
```

3b. `Pitch` owns the container ref and passes drag wiring to the home layer only:

```tsx
export function Pitch({ home, away, onSwap }: {
  home: TeamOnPitch
  away?: TeamOnPitch
  /**
   * Presence makes the home team's outfield tokens draggable. Called with the origin and
   * target slot indexes on a successful drop; the parent decides swap-vs-move.
   */
  onSwap?: (fromSlot: number, toSlot: number) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  return (
    <Box
      ref={containerRef}
      data-testid="pitch"
      sx={{ position: 'relative', width: '100%', maxWidth: 440, mx: 'auto', aspectRatio: '2 / 3', borderRadius: 1, overflow: 'hidden', backgroundColor: '#2b8449' }}
    >
      {/* …existing svg unchanged… */}
      <TeamLayer team={home} mirror={false} onSwap={onSwap} containerRef={containerRef} />
      {away && <TeamLayer team={away} mirror />}
    </Box>
  )
}
```

3c. `TeamLayer` drag state + hit-test + handlers (full replacement of the component):

```tsx
type Drag = { fromSlot: number; startX: number; startY: number; x: number; y: number }

function TeamLayer({ team, mirror, onSwap, containerRef }: {
  team: TeamOnPitch
  mirror: boolean
  onSwap?: (fromSlot: number, toSlot: number) => void
  containerRef?: React.RefObject<HTMLDivElement | null>
}) {
  const colors = tokenColors(team.kit)
  const [drag, setDrag] = useState<Drag | null>(null)
  const positioned = place(team)

  // Nearest droppable slot center within DROP_RADIUS_FRACTION of the pitch width, in screen
  // pixels; the origin slot and slot 0 (GK) are never candidates. Null = snap back.
  function dropTarget(clientX: number, clientY: number, fromSlot: number): number | null {
    const rect = containerRef?.current?.getBoundingClientRect()
    if (!rect || rect.width === 0) return null
    const radius = rect.width * DROP_RADIUS_FRACTION
    let best: { slot: number; d: number } | null = null
    positioned.forEach(({ slot }, i) => {
      if (i === fromSlot || i === 0) return
      const cx = rect.left + (mirror ? 1 - slot.x : slot.x) * rect.width
      const cy = rect.top + (mirror ? 1 - slot.y : slot.y) * rect.height
      const d = Math.hypot(clientX - cx, clientY - cy)
      if (d <= radius && (best === null || d < best.d)) best = { slot: i, d }
    })
    return best === null ? null : best.slot
  }

  const dropCandidate = drag ? dropTarget(drag.x, drag.y, drag.fromSlot) : null

  return (
    <>
      {positioned.map(({ slot, number, player }, i) => {
        const yPct = (mirror ? 1 - slot.y : slot.y) * 100
        const xPct = (mirror ? 1 - slot.x : slot.x) * 100
        const posLabel = DETAILED_POSITION_SHORT[slot.accepted[0]]
        const outOfPosition = !!player?.detailedPosition && !slot.accepted.includes(player.detailedPosition)
        const draggable = Boolean(onSwap) && !mirror && i !== 0 && player !== null
        const dragging = drag?.fromSlot === i
        return (
          <Box
            key={player?.id ?? `slot-${number}`}
            data-slot-index={i}
            data-drop-candidate={dropCandidate === i ? 'true' : undefined}
            onPointerDown={draggable ? e => {
              e.currentTarget.setPointerCapture?.(e.pointerId)
              setDrag({ fromSlot: i, startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY })
            } : undefined}
            onPointerMove={draggable ? e => {
              setDrag(d => (d && d.fromSlot === i ? { ...d, x: e.clientX, y: e.clientY } : d))
            } : undefined}
            onPointerUp={draggable ? e => {
              if (!drag || drag.fromSlot !== i) return
              const target = dropTarget(e.clientX, e.clientY, i)
              setDrag(null)
              if (target !== null) onSwap?.(i, target)
            } : undefined}
            onPointerCancel={draggable ? () => setDrag(null) : undefined}
            sx={{
              position: 'absolute',
              left: `${xPct}%`,
              top: `${yPct}%`,
              transform: dragging && drag
                ? `translate(-50%, -50%) translate(${drag.x - drag.startX}px, ${drag.y - drag.startY}px)`
                : 'translate(-50%, -50%)',
              zIndex: dragging ? 10 : undefined,
              cursor: draggable ? 'grab' : undefined,
              touchAction: draggable ? 'none' : undefined,
              ...(dropCandidate === i && { borderRadius: 1, boxShadow: '0 0 0 3px #f7d417' }),
            }}
          >
            {player ? (
              <PlayerToken
                number={number}
                surname={team.showNames ? player.surname : undefined}
                fill={colors.fill}
                text={colors.text}
                fitness={player.fitness}
                badges={player.badges}
                posLabel={posLabel}
                outOfPosition={outOfPosition}
              />
            ) : (
              <PlayerToken number={number} empty fill={colors.fill} text={colors.text} fitness={0} posLabel={posLabel} />
            )}
          </Box>
        )
      })}
    </>
  )
}
```

Note `setPointerCapture?.(…)` — jsdom doesn't implement pointer capture; the optional call
keeps tests green while real browsers keep receiving move/up events during fast drags.

- [ ] **Step 4: Run the Pitch tests**

Run: `cd frontend && npx vitest run src/features/pitch/Pitch.test.tsx`
Expected: PASS (all, including Task 5's and pre-existing).

- [ ] **Step 5: Run the neighboring suites + type-check**

Run: `cd frontend && npx vitest run src/features/match/ src/features/squad/ && npx tsc -b`
Expected: PASS — `onSwap` is optional, existing consumers unaffected.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/pitch/Pitch.tsx frontend/src/features/pitch/Pitch.test.tsx
git commit -m "feat(pitch): pointer-event drag-and-drop between formation slots"
```

---

### Task 7: SquadPage integration — slot state, relaxed validation, save from the tactics tab

**Files:**
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Test: `frontend/src/features/squad/SquadPage.test.tsx` (append + update one test)

**Interfaces:**
- Consumes: `assignSlots`/`AssignablePlayer` (Task 4), `Pitch onSwap` + `PlacedPlayer.slotIndex` (Tasks 5–6), `pitchSlots` (existing), `LineupStarter.slotIndex` (Task 4), backend contract (Task 2: permutation of 0–10, GK at 0, bench null).
- Produces: the user-facing feature. Save payload starters each carry `slotIndex`; bench entries carry `slotIndex: null`.

- [ ] **Step 1: Write the failing tests (append to `SquadPage.test.tsx`)**

```tsx
  it('sends a slotIndex permutation (GK at 0) for the starters and null for the bench', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(pickBtn('goleiro teste')) // bands full → bench
    fireEvent.click(screen.getByRole('button', { name: /salvar escala/i }))

    await waitFor(() => expect(mockSetLineup).toHaveBeenCalled())
    const req = mockSetLineup.mock.calls[0][2]
    const slotIndexes = req.starters.map((s: { slotIndex?: number | null }) => s.slotIndex)
    expect([...slotIndexes].sort((a, b) => (a ?? -1) - (b ?? -1)))
      .toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    const gkStarter = req.starters.find((s: { playerId: string }) => s.playerId === GK2.id)
    expect(gkStarter.slotIndex).toBe(0)
    expect(req.bench.every((s: { slotIndex?: number | null }) => s.slotIndex == null)).toBe(true)
  })

  it('rehydrates explicit slot assignments from the saved lineup onto the pitch', async () => {
    // Deliberately cross-line: MID1 is saved into an ATT slot (index 9 in 4-3-3).
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-3', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null, slotIndex: 0 },
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null, slotIndex: 1 },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null, slotIndex: 4 },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 5 },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 6 },
        { playerId: ATT1.id, role: 'POACHER', instruction: null, slotIndex: 7 },
        { playerId: ATT2.id, role: 'POACHER', instruction: null, slotIndex: 8 },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 9 },
        { playerId: ATT3.id, role: 'POACHER', instruction: null, slotIndex: 10 },
      ],
      bench: [],
    })
    const { container } = renderWithProviders()
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-3-3'))
    // The pitch (mounted under the hidden Táticas panel) placed MID1 at slot 9.
    expect(container.querySelector('[data-slot-index="9"]')).toHaveTextContent('Meio Um')
    // Cross-line arrangements are legal: saving stays enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('dragging on the tactics pitch swaps the two players in the save payload', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const tokenAt = (slot: number) => pitch.querySelector(`[data-slot-index="${slot}"]`)!
    // Token text is "«shirt number»«surname»"; every fixture name is unique, so the suffix
    // identifies the player.
    const idOfToken = (text: string | null) =>
      squad.players.find(p => text?.endsWith(p.name))!.id
    // The auto-picked 4-3-3 puts DEF1 (the only LATERAL_ESQUERDO) in slot 4 — the LE slot.
    expect(tokenAt(4).textContent).toContain('Defensor Um')
    const toText = tokenAt(9).textContent
    const toName = squad.players.find(p => toText?.endsWith(p.name))!.name

    // Drag slot 4 (left-back, center 336/474 in 4-3-3) onto slot 9 (striker, 200/192).
    fireEvent.pointerDown(tokenAt(4), { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerMove(tokenAt(4), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(tokenAt(4), { pointerId: 1, clientX: 200, clientY: 192 })

    // The two tokens swapped on the pitch (shirt numbers follow the slot, so match by name)…
    await waitFor(() => expect(tokenAt(9).textContent).toContain('Defensor Um'))
    expect(tokenAt(4).textContent).toContain(toName)
    // …and the lateral in the striker slot is flagged out of position.
    expect(tokenAt(9).querySelector('[data-out-of-position="true"]')).not.toBeNull()

    // Saving from the pitch's own button sends the swapped permutation.
    const saveButtons = screen.getAllByRole('button', { name: /salvar escala/i })
    fireEvent.click(saveButtons[saveButtons.length - 1])
    await waitFor(() => expect(mockSetLineup).toHaveBeenCalled())
    const req = mockSetLineup.mock.calls[0][2]
    const slotOf = (playerId: string) =>
      req.starters.find((s: { playerId: string }) => s.playerId === playerId)?.slotIndex
    const slotIndexes = req.starters.map((s: { slotIndex?: number | null }) => s.slotIndex)
    expect([...slotIndexes].sort((a: number, b: number) => a - b))
      .toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    // The dragged pair really exchanged indexes.
    expect(slotOf(DEF1.id)).toBe(9)
    expect(slotOf(idOfToken(toText))).toBe(4)
  })

  it('re-derives slot assignments when the formation changes and keeps saving enabled', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    const xi = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of xi) {
      fireEvent.click(pickBtn(player.name))
    }
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // Under slot-based validation a 4-DEF XI still fits 3-5-2: the 4th defender lands in a
    // midfield slot (out of position, penalised — but saveable).
    fireEvent.change(screen.getByLabelText(/formação/i), { target: { value: '3-5-2' } })
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
```

Notes for the implementer:
- The old test `blocks saving when the line-up no longer fits a changed formation` (~line 402)
  is REPLACED by the last test above — delete the old one.
- All other existing tests must keep passing UNCHANGED — the second save button lives in the
  hidden Táticas panel, and `getByRole` ignores hidden elements, so Plantel-tab tests are
  unaffected.

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: the 4 new tests FAIL; after deleting the old formation test, the rest PASS.

- [ ] **Step 3: Implement in `SquadPage.tsx`**

3a. Imports:

```tsx
import { FORMATION_CODES, pitchSlots } from '../pitch/pitchSlots'
import { assignSlots, type AssignablePlayer } from '../pitch/slotAssign'
```

3b. State + derived slots (near the other useState calls):

```tsx
  // playerId -> formation slot index (0 = GK). Keys ⊆ selected; values unique. The pitch
  // renders from this map and drag-and-drop edits it.
  const [slotByPlayerId, setSlotByPlayerId] = useState<Record<string, number>>({})
```

and after `const players = data?.players ?? []`:

```tsx
  const slots = pitchSlots(formation)

  const toAssignable = (p: PlayerResponse): AssignablePlayer =>
    ({ id: p.id, position: p.position, detailedPosition: p.detailedPosition, overall: p.overall })
```

3c. Hydration — the effect now also waits for the squad (`data`) so the legacy fallback can
read player attributes; replace the existing effect body:

```tsx
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current || !savedLineup.isSuccess || !data) return
    const lineup = savedLineup.data
    if (lineup) {
      setFormation(lineup.formation)
      setSelected(lineup.starters.map(s => s.playerId))
      setRoles(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.role])))
      setInstructions(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.instruction ?? ''])))
      setBench((lineup.bench ?? []).map(s => s.playerId))
      if (lineup.starters.every(s => s.slotIndex != null)) {
        setSlotByPlayerId(Object.fromEntries(
          lineup.starters.map(s => [s.playerId, s.slotIndex as number])))
      } else {
        // Legacy lineup without persisted slots: derive the same default the engine would.
        const xi = lineup.starters
          .map(s => data.players.find(p => p.id === s.playerId))
          .filter((p): p is PlayerResponse => Boolean(p))
        setSlotByPlayerId(assignSlots(xi.map(toAssignable), lineup.formation))
      }
    }
    hydrated.current = true
  }, [savedLineup.isSuccess, savedLineup.data, data])
```

3d. Replace `isBandFull` with a slot-seeking helper, and rewire `cyclePlayer`:

```tsx
  // First free slot for a newly picked starter: an exact detailed-position slot first, else
  // any free slot of the player's coarse line. Cross-line placement only happens by dragging.
  function firstFreeSlotFor(player: PlayerResponse): number | null {
    const taken = new Set(Object.values(slotByPlayerId))
    const free = slots.map((slot, index) => ({ slot, index })).filter(x => !taken.has(x.index))
    const exact = player.detailedPosition
      ? free.find(x => x.slot.accepted.includes(player.detailedPosition!))
      : undefined
    return (exact ?? free.find(x => x.slot.line === player.position))?.index ?? null
  }
```

In `cyclePlayer`: (1) in the removal branch, also clear the slot:

```tsx
      setSlotByPlayerId(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
```

(2) replace the `if (!isBandFull(player.position))` block with:

```tsx
    const slotIndex = firstFreeSlotFor(player)
    if (slotIndex !== null) {
      setSelected(prev => [...prev, player.id])
      setSlotByPlayerId(prev => ({ ...prev, [player.id]: slotIndex }))
      setRoles(prev => ({ ...prev, [player.id]: DEFAULT_ROLE[player.position] }))
      return
    }
```

Delete `isBandFull`, `positionCap`, and `BAND_LABEL` if nothing else references them
(`FORMATION_SHAPE` stays — `autoPickBestXI` uses it).

3e. `autoPickBestXI` — after `setSelected(pickedIds)` add:

```tsx
    setSlotByPlayerId(assignSlots(picked.map(toAssignable), formation))
```

`clearSelection` — add `setSlotByPlayerId({})`.

3f. Formation change re-derives (replace the inline `onChange`):

```tsx
  function changeFormation(next: string) {
    setSaveSuccess(false)
    setFormation(next)
    const xi = selected
      .map(id => players.find(p => p.id === id))
      .filter((p): p is PlayerResponse => Boolean(p))
    setSlotByPlayerId(assignSlots(xi.map(toAssignable), next))
  }
```

```tsx
      <NativeSelect value={formation} onChange={e => changeFormation(e.target.value)} inputProps={{ 'aria-label': 'Formação' }}>
```

3g. Drag callback:

```tsx
  // Drop from the pitch: the dragged player takes the target slot; a previous occupant takes
  // the vacated one (swap). Pitch guarantees fromSlot holds a player and toSlot is not the GK.
  function swapSlots(fromSlot: number, toSlot: number) {
    setSaveSuccess(false)
    setSlotByPlayerId(prev => {
      const fromId = Object.keys(prev).find(id => prev[id] === fromSlot)
      if (!fromId) return prev
      const toId = Object.keys(prev).find(id => prev[id] === toSlot)
      const next = { ...prev, [fromId]: toSlot }
      if (toId) next[toId] = fromSlot
      return next
    })
  }
```

3h. Validation — replace `validate` (module scope) and its call site:

```tsx
function validate(selected: string[], players: PlayerResponse[],
                  slotByPlayerId: Record<string, number>): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const counts = countByPosition(selected, players)
  if (counts.GK !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
  }
  // Every starter needs a distinct formation slot (drag-and-drop / auto-assign keeps this
  // true; the guard catches degenerate states like a saved starter no longer in the squad).
  const assigned = new Set(selected.map(id => slotByPlayerId[id]).filter(v => v !== undefined))
  if (assigned.size !== 11) {
    return 'Cada titular precisa de uma posição no campo — use o campo na aba Táticas para organizar.'
  }
  const unavailable = players.filter(p => selected.includes(p.id) && !p.eligible)
  if (unavailable.length > 0) {
    return `Jogador(es) indisponível(is) na escalação: ${unavailable.map(p => p.name).join(', ')}. Escolha um substituto.`
  }
  return null
}
```

```tsx
  const validationError = players.length > 0 ? validate(selected, players, slotByPlayerId) : null
```

3i. Helper line — slot occupancy per line (replace the `Formação {formation} — …` Typography
content):

```tsx
  const lineTotal = (line: 'GK' | 'DEF' | 'MID' | 'ATT') => slots.filter(s => s.line === line).length
  const lineFilled = (line: 'GK' | 'DEF' | 'MID' | 'ATT') =>
    Object.values(slotByPlayerId).filter(i => slots[i]?.line === line).length
```

```tsx
        Formação {formation} — Titulares {selected.length}/11 · Banco {bench.filter(Boolean).length}/9 · GOL {lineFilled('GK')}/{lineTotal('GK')} · DEF {lineFilled('DEF')}/{lineTotal('DEF')} · MEI {lineFilled('MID')}/{lineTotal('MID')} · ATA {lineFilled('ATT')}/{lineTotal('ATT')}
```

(`positionCounts` may now be unused — delete it and `countByPosition`'s call site if so;
`countByPosition` itself stays, `validate` uses it.)

3j. Save payload — starters carry their slot, bench carries null:

```tsx
      const toStarter = (id: string, slotIndex: number | null): LineupStarter => {
        const player = players.find(p => p.id === id)!
        return {
          playerId: id,
          role: roles[id] ?? DEFAULT_ROLE[player.position],
          instruction: instructions[id] || null,
          slotIndex,
        }
      }
      const starters: LineupStarter[] = selected.map(id => toStarter(id, slotByPlayerId[id] ?? null))
      const benchStarters: LineupStarter[] = bench
        .filter((id): id is string => id !== null)
        .map(id => toStarter(id, null))
```

3k. Pitch view-model + tactics-tab wiring:

```tsx
      .map((p): PlacedPlayer => ({
        id: p.id, surname: p.name, position: p.position,
        detailedPosition: p.detailedPosition, fitness: p.fitness,
        slotIndex: slotByPlayerId[p.id],
      })),
```

```tsx
          {pitchTeam && (
            <Box sx={{ flex: 1, minWidth: 0, mb: 2 }}>
              <Pitch home={pitchTeam} onSwap={swapSlots} />
              <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
                <Button
                  color="secondary"
                  disabled={!canSave || mutation.isPending}
                  onClick={() => mutation.mutate()}
                >
                  {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
                </Button>
              </Box>
            </Box>
          )}
```

- [ ] **Step 4: Run the SquadPage suite**

Run: `cd frontend && npx vitest run src/features/squad/SquadPage.test.tsx`
Expected: PASS — the 4 new tests and every pre-existing one (with ~line-402's old test deleted).

- [ ] **Step 5: Run the full frontend suite + type-check**

Run: `cd frontend && npx vitest run && npx tsc -b`
Expected: ALL PASS, no type errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(squad): drag-and-drop slot assignment wired into lineup state and save"
```

---

### Task 8: Full-suite verification + live end-to-end check

**Files:** none modified (verification only; fix regressions if found).

- [ ] **Step 1: Full backend suite**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL. (Retry on Gradle contention errors — concurrent sessions share this repo.)

- [ ] **Step 2: Full frontend suite + type-check + build**

Run: `cd frontend && npx vitest run && npx tsc -b && npm run build`
Expected: all green.

- [ ] **Step 3: Live drag verification in the browser**

1. Ensure PostgreSQL is up: `docker start carvoeiro-manager-db` (container may be stopped).
2. Start the `backend` and `frontend` dev servers via the browser-preview launch configs
   (`.claude/launch.json` has both; backend on 8088, frontend on 5174).
3. Register a throwaway user via `POST /api/v1/auth/register` (username letters/digits only),
   create a save game, inject the JWT into localStorage (`carvoeiro_manager_token`,
   `carvoeiro_manager_user`), open `/squad?saveGameId=…&clubId=…`.
4. Pick a full XI (Selecionar melhores), open **Táticas de jogo**, drag an outfield token onto
   another: verify the two tokens swap, the out-of-position amber tint appears when crossing
   lines, and the GK token refuses to drag.
5. Click the pitch's **Salvar escalação**, reload the page, reopen the tab: the arrangement
   must come back exactly as saved.
6. Screenshot the rearranged pitch as evidence; check the browser console for errors.
7. Delete the throwaway account (`DELETE /api/v1/auth/account`, expect 204).

- [ ] **Step 4: Report**

Summarize test counts + live-verification evidence to the user. Do NOT push — the user pushes
explicitly.
