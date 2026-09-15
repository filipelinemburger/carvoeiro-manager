# Live-Match Manual Positioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the manager drag players between slots, change formation with real relocation, and set roles during a live match, with the engine grading every on-pitch player — substitutes included — by where they actually stand.

**Architecture:** A minute-stamped `slot_schedule` joins `sub_schedule` and `tactics_schedule`, so the timeline stays a pure function of its inputs. The engine keeps each side's live arrangement on its `Side` state and overlays it onto the tactical profile only when computing strength and attribution. It records an `ArrangementSnapshot` at kickoff and at every change, and the service reads the snapshot at the committed minute to report `slotIndex` per player. The frontend adds a **Posições** tab to the match-actions dialog that reuses the squad screen's `Pitch`.

**Tech Stack:** Java 25 / Spring Boot / JPA / Flyway / Jackson 3 / JUnit 5 + AssertJ + Testcontainers; React 19 / TypeScript / MUI 9 / vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-10-live-match-manual-positioning-design.md`

## Global Constraints

- Work directly on `main`. No branches, no worktrees.
- An untracked `CONTEXT.md` at the repo root is not yours: `git add` only the files you changed, never `git add -A` / `git add .`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **Every Gradle call pins Java 25** — the shell profile exports Java 8 and the build dies before compiling otherwise:
  `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests '<pattern>'; echo "EXIT=$?"`
  Capture the exit code directly as shown, never after a pipe.
- Integration tests (`*IT`) need Docker: if **every** IT fails at once with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate`, run `open -a OrbStack` and retry — do not debug the code.
- Other sessions share this checkout. A Gradle failure with `EOFException` or `NoSuchFileException` under `build/test-results` is build-directory contention, not a bug: check `pgrep -f "Gradle Test Executor"`, wait, retry. Never run `./gradlew --stop`.
- Run Gradle as a **blocking** call. Never background it and end your turn waiting — subagents that do this stall indefinitely.
- Do not run the full backend suite (~1h). Run the targeted `--tests` filters each task names; the controller runs the full suite once at the end.
- `TransferServiceSellingIT` fails ~26% of runs on clean `main` (correlated `Random` seed). It is not caused by this work.
- Frontend type-check is `npx tsc -b` from `frontend/`. **`tsc --noEmit` is a no-op in this repo** and proves nothing.
- Frontend tests: `npx vitest run <path>` while iterating, `npx vitest run` (full) before committing. Lint: `npm run lint`, report its exit code.
- UI copy is Portuguese (pt-BR), exactly as written in this plan.
- TDD: write the failing test, run it and see it fail, then implement.
- This codebase documents non-obvious decisions in comments. Match that density; every load-bearing ordering or invariant introduced here gets a comment at the site.

## Planning rulings against the spec

Writing this plan against the code surfaced defects and ambiguities in the spec. These rulings are binding and supersede the spec where they conflict:

- **R1 — Apply order.** The engine applies, per minute: `applyTacticChanges` home, away → `applySubs` home, away → **`applySlotChanges` home, away**. The spec placed slot changes directly after tactics; that is wrong, because an arrangement submitted together with a substitution names the incoming player, who is only on the pitch after `applySubs`.
- **R2 — Where the arrangement lives.** The live arrangement lives on the engine's `Side`, not inside `TacticalProfile`. `reactAiManager` rebuilds the AI's profile from its **kickoff identity** on every posture swap, and `applyTacticChanges` rebuilds the profile wholesale. Either would silently reset a profile-held arrangement. The engine overlays the side's arrangement via `TacticalProfile.withArrangement(...)` when computing strength and goal attribution. `TacticalProfile` gains `slotIndexes` only to carry the **kickoff** indexes in.
- **R3 — Why indexes at all.** `FormationSlots.Slot` compares by its accepted-position set, so the two centre-back slots of a 4-4-2 are *equal* `Slot`s. A slot index cannot be recovered from `slotAssignments`; it must be carried explicitly from `SlotAssigner` onward.
- **R4 — AI posture formation swaps do not relocate.** Decision 4 applies to formation changes arriving through `TimedTactics` (the manager). `AiManagerBrain` posture swaps (`ALL_OUT` → 4-2-4, `PARK_THE_BUS` → 5-4-1) keep today's grading, since relocating them is a balance change nobody asked for. AI **substitutes** still inherit and are graded (Decision 5 as amended in the spec).
- **R5 — `TimedSlots.roles` is `Map<UUID, RoleArchetype>`**, not `RoleAssignment`. Focus is always `archetype.defaultFocusFor(slotPosition)`, which matches the request shape (archetype names only).
- **R6 — Roles across a rearrangement.** A player whose slot index **and** formation are unchanged keeps their current role (including a pre-match role the manager set). A player who moved, or every player after a formation relocation, takes a valid override if one was sent, else `TacticsService.bestAssignmentFor(player, slot)`.
- **R7 — Validation keys.** An arrangement must be keyed by **exactly the managed side's on-pitch players at the target minute** (the committed on-pitch set, with substitutions already scheduled for that same minute applied) — 10 players after a red card, not "a permutation of 0..10 over eleven". Indexes must be distinct and `< FormationSlots.of(formation).size()`.
- **R8 — No no-op tactics entry.** When a request carries an arrangement and its tactics equal the tactics already in force at the target minute, the service writes **only** the `TimedSlots` entry. Otherwise an arrangement-only apply would put a spurious `TACTICS_CHANGE` into the commentary.
- **R9 — No timeline event for a rearrangement.** `applySlotChanges` emits no `LiveTimelineEvent`, so every existing event golden stays valid.
- **R10 — `slotIndex` honesty.** `LivePlayerResponse.slotIndex` is non-null only when the side's snapshot formation equals the formation the state displays for that side. Otherwise it is null and the pitch falls back to its line-zip.
- **R11 — Frontend payload.** The dialog nests the arrangement inside the existing `tactics` payload (`LiveTacticsRequest.arrangement`), **omitting the key** when nothing was rearranged, so every existing apply sends a byte-identical payload and `LiveMatchPage.handleApply` needs no change. The dialog gains a required `kit` prop for the pitch.
- **Scope note.** `FixtureSimulator` (background fixtures) makes no in-match substitutions, so Decision 5's behaviour change is confined to live matches — both sides of them.

---

## File Structure

**Backend — `backend/src/main/java/br/com/carvoeiromanager/match/`**
- `SlotAssigner.java` — modify: add `assignToSlotIndexes`, `slotsFor`; the pinned `assignToSlots` delegates to them.
- `TacticalProfile.java` — modify: add `slotIndexes` component, 12-arg compatibility constructor, `withArrangement`.
- `TacticsService.java` — modify: both profile builders produce indexes.
- `AiManagerBrain.java` — modify: posture swap carries `slotIndexes`.
- `TimedSlots.java` — create: one scheduled rearrangement.
- `ArrangementSnapshot.java` — create: one side's arrangement from a minute onward.
- `LiveMatchConfig.java` — modify: `slotSchedule` component + 15-arg compatibility constructor.
- `LiveTimeline.java` — modify: `arrangements` component + 7-arg compatibility constructor + `arrangementAt`.
- `LiveMatchEngine.java` — modify: arrangement tracking on `Side`, overlay, inheritance, relocation, scheduled rearrangement, snapshots.
- `ArrangementRequest.java` — create: request body fragment.
- `LiveTacticsRequest.java` — modify: optional `arrangement` + 6-arg compatibility constructor.
- `LivePlayerResponse.java` — modify: `slotIndex`, `detailedPosition`, `altPositions`.
- `LiveMatch.java` — modify: `slotSchedule` column.
- `LiveMatchService.java` — modify: config, validation, persistence, state.
- `backend/src/main/resources/db/migration/V56__live_match_slot_schedule.sql` — create.

**Backend tests — `backend/src/test/java/br/com/carvoeiromanager/match/`**
- `SlotAssignerTest.java`, `TeamStrengthCalculatorTest.java`, `AiManagerBrainTest.java`, `LiveMatchEngineTest.java` — modify.
- `LiveMatchArrangementIT.java` — create.

**Frontend — `frontend/src/`**
- `api/types.ts` — modify: `LivePlayer` fields, `LiveArrangement`, `LiveTacticsRequest.arrangement`.
- `features/match/LineupsTab.tsx` — modify: pass slot data into the pitch.
- `features/match/liveArrangement.ts` — create: pure roster/arrangement helpers + `SubPair`.
- `features/match/PositionsTabContent.tsx` — create: the Posições tab.
- `features/match/MatchActionsDialog.tsx` — modify: third tab, arrangement state, payload, footer.
- `features/match/LiveMatchPage.tsx` — modify: pass `kit`.
- Tests: `LineupsTab.test.tsx` (create), `liveArrangement.test.ts` (create), `MatchActionsDialog.test.tsx`, `matchSides.test.ts`, `livePlayerContext.test.ts`, `LiveMatchPage.test.tsx` (modify).

---

### Task 1: Slot assignment by index

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java:102-139`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `static Map<UUID, Integer> SlotAssigner.assignToSlotIndexes(List<Player> xi, String formation, Map<UUID, Integer> pinnedSlotIndexes)`
  - `static Map<UUID, FormationSlots.Slot> SlotAssigner.slotsFor(Map<UUID, Integer> slotIndexes, String formation)`

- [ ] **Step 1: Write the failing tests**

Add to `SlotAssignerTest` (it already has `wellFormed442()`), plus `import java.util.HashSet;`:

```java
    @Test
    void pinnedIndexesAreHonouredVerbatimAndEveryoneElseFillsTheFreeIndexes() {
        List<Player> xi = wellFormed442();
        Player first = xi.get(1);
        Player second = xi.get(2);

        Map<UUID, Integer> indexes = SlotAssigner.assignToSlotIndexes(
                xi, "4-4-2", Map.of(first.getId(), 3, second.getId(), 0));

        assertThat(indexes).hasSize(11);
        assertThat(indexes.get(first.getId())).isEqualTo(3);
        assertThat(indexes.get(second.getId())).isEqualTo(0);
        assertThat(new HashSet<>(indexes.values())).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void aClaimedOrOutOfRangePinIsIgnoredAndThatPlayerIsPlacedGreedily() {
        List<Player> xi = wellFormed442();
        Player claimant = xi.get(1);
        Player latecomer = xi.get(2);
        Player outOfRange = xi.get(3);

        Map<UUID, Integer> indexes = SlotAssigner.assignToSlotIndexes(xi, "4-4-2",
                Map.of(claimant.getId(), 4, latecomer.getId(), 4, outOfRange.getId(), 99));

        // XI order decides a contested pin: the earlier player keeps it.
        assertThat(indexes.get(claimant.getId())).isEqualTo(4);
        assertThat(indexes.get(latecomer.getId())).isNotEqualTo(4);
        assertThat(indexes.get(outOfRange.getId())).isBetween(0, 10);
        assertThat(new HashSet<>(indexes.values())).hasSize(11);
    }

    @Test
    void theSlotFormIsExactlyTheIndexFormResolvedAgainstTheFormation() {
        List<Player> xi = wellFormed442();
        Map<UUID, Integer> pinned = Map.of(xi.get(5).getId(), 7);

        Map<UUID, Integer> indexes = SlotAssigner.assignToSlotIndexes(xi, "4-3-3", pinned);

        assertThat(SlotAssigner.assignToSlots(xi, "4-3-3", pinned))
                .isEqualTo(SlotAssigner.slotsFor(indexes, "4-3-3"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.SlotAssigner*'; echo "EXIT=$?"`
Expected: compilation FAIL — `cannot find symbol assignToSlotIndexes` / `slotsFor`.

- [ ] **Step 3: Implement**

In `SlotAssigner.java`, replace the body of the three-argument `assignToSlots` and add the two methods directly after it:

```java
    static Map<UUID, FormationSlots.Slot> assignToSlots(List<Player> xi, String formation,
                                                        Map<UUID, Integer> pinnedSlotIndexes) {
        return slotsFor(assignToSlotIndexes(xi, formation, pinnedSlotIndexes), formation);
    }

    /**
     * Index form of {@link #assignToSlots(List, String, Map)} — the same pinned-first, greedy-leftover
     * assignment, reporting each player's INDEX into {@code FormationSlots.of(formation)} instead of the
     * {@link FormationSlots.Slot}. The index is the only thing that tells two slots accepting the same
     * positions apart (a 4-4-2's two centre-back slots are equal {@code Slot}s), so anything that must
     * know WHERE a player stands — the live pitch, a manual rearrangement — needs this form. A pin that
     * is out of range or already claimed is ignored and that player joins the leftovers.
     */
    static Map<UUID, Integer> assignToSlotIndexes(List<Player> xi, String formation,
                                                  Map<UUID, Integer> pinnedSlotIndexes) {
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, Integer> assignments = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        List<Player> leftovers = new ArrayList<>();
        for (Player p : xi) {
            Integer idx = pinnedSlotIndexes.get(p.getId());
            if (idx != null && idx >= 0 && idx < slots.size() && taken.add(idx)) {
                assignments.put(p.getId(), idx);
            } else {
                leftovers.add(p);
            }
        }
        List<Integer> freeIndexes = new ArrayList<>();
        List<FormationSlots.Slot> freeSlots = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!taken.contains(i)) {
                freeIndexes.add(i);
                freeSlots.add(slots.get(i));
            }
        }
        Comparator<Player> byRating =
                Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);
        Player[] picked = assign(freeSlots, leftovers, byRating);
        for (int i = 0; i < picked.length; i++) {
            if (picked[i] != null) {
                assignments.put(picked[i].getId(), freeIndexes.get(i));
            }
        }
        return assignments;
    }

    /** Resolves slot indexes against {@code formation}'s ordered slot list. Indexes must be in range. */
    static Map<UUID, FormationSlots.Slot> slotsFor(Map<UUID, Integer> slotIndexes, String formation) {
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, FormationSlots.Slot> resolved = new HashMap<>();
        slotIndexes.forEach((id, index) -> resolved.put(id, slots.get(index)));
        return resolved;
    }
```

Keep the existing javadoc on the three-argument `assignToSlots` unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.SlotAssigner*' --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'; echo "EXIT=$?"`
Expected: PASS, EXIT=0. `LiveMatchEngineTest` is included because its goldens prove the refactor changed no assignment.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/SlotAssigner.java backend/src/test/java/br/com/carvoeiromanager/match/SlotAssignerTest.java
git commit -m "feat(match): assign formation slots by index, not only by slot"
```

---

### Task 2: Carry kickoff slot indexes in the tactical profile

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TacticalProfile.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java:69-85` (`defaultProfile`) and `:143-160` (`fromLineup`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/AiManagerBrain.java:81-87` (`with`)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java:347-354` (`applyTacticChanges` constructor call only)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java`, `AiManagerBrainTest.java`

**Interfaces:**
- Consumes: `SlotAssigner.assignToSlotIndexes`, `SlotAssigner.slotsFor` (Task 1).
- Produces:
  - `TacticalProfile` 13th component `Map<UUID, Integer> slotIndexes` (empty = no kickoff indexes).
  - The existing 12-argument `TacticalProfile` constructor keeps compiling and yields `slotIndexes = Map.of()`.
  - `TacticalProfile withArrangement(Map<UUID, FormationSlots.Slot> arrangedSlots, Map<UUID, Integer> arrangedIndexes, Map<UUID, RoleAssignment> arrangedRoles)` — same tactics, replaced roles/slots/indexes.
  - Every profile built by `TacticsService` carries non-empty `slotIndexes` consistent with its `slotAssignments`.

- [ ] **Step 1: Write the failing tests**

Add to `TeamStrengthCalculatorTest` (add imports as needed: `AttackZone`, `GameStyle`, `Marking`, `Mentality`, `Pressing`, `Position`, `Player`, `List`, `ArrayList`, `Map`, `UUID`):

```java
    @Test
    void aGoalkeeperArrangedIntoAStrikerSlotContributesLessThanWhenUnslotted() {
        List<Player> xi = new ArrayList<>();
        Player keeper = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.GK, 25, "BRA",
                70, 70, 70, 70, 70, 70, 70);
        xi.add(keeper);
        for (Position pos : List.of(Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT)) {
            xi.add(Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                    70, 70, 70, 70, 10, 70, 70));
        }
        TacticalProfile neutral = new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO,
                "4-4-2", Pressing.MEDIUM, Marking.ZONAL, LegacyRoles.forXi(xi), null, null, null, Map.of(), Map.of());
        // Slot 9 of a 4-4-2 is a striker slot (GK 0, DEF 1-4, MID 5-8, ATT 9-10).
        FormationSlots.Slot strikerSlot = FormationSlots.of("4-4-2").get(9);

        TacticalProfile arranged = neutral.withArrangement(
                Map.of(keeper.getId(), strikerSlot), Map.of(keeper.getId(), 9), neutral.roles());

        TeamMatchInput unslotted = new TeamStrengthCalculator().compute(xi, neutral);
        TeamMatchInput misplaced = new TeamStrengthCalculator().compute(xi, arranged);
        assertThat(misplaced.attack() + misplaced.defense())
                .isLessThan(unslotted.attack() + unslotted.defense());
    }

    @Test
    void theTwelveArgumentConstructorCarriesNoSlotIndexes() {
        TacticalProfile legacy = new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO,
                "4-4-2", Pressing.MEDIUM, Marking.ZONAL, Map.of(), null, null, null, Map.of(), Map.of());
        assertThat(legacy.slotIndexes()).isEmpty();
    }
```

Add to `AiManagerBrainTest`:

```java
    @Test
    void aPostureSwapKeepsTheIdentitysKickoffSlotIndexes() {
        UUID playerId = UUID.randomUUID();
        TacticalProfile identity = new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO,
                "4-4-2", Pressing.MEDIUM, Marking.ZONAL, Map.of(), null, null, null, Map.of(),
                Map.of(), Map.of(playerId, 7));

        assertThat(AiManagerBrain.profileFor(AiManagerBrain.Posture.ALL_OUT, identity).slotIndexes())
                .containsExactly(Map.entry(playerId, 7));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.TeamStrengthCalculatorTest' --tests 'br.com.carvoeiromanager.match.AiManagerBrainTest'; echo "EXIT=$?"`
Expected: compilation FAIL — `cannot find symbol withArrangement` / `slotIndexes()` / no 13-argument constructor.

- [ ] **Step 3: Implement `TacticalProfile`**

Append the component and add the constructor and method inside the record body (keep the existing `instructionFor`):

```java
        Map<UUID, FormationSlots.Slot> slotAssignments,
        Map<UUID, Integer> slotIndexes) {

    /**
     * Pre-index constructor. Yields no slot indexes — the neutral "untracked" profile that legacy
     * callers and the engine's fixed-seed golden tests build, which the live engine never rearranges
     * (see {@code LiveMatchEngine}'s arrangement tracking), keeping those timelines byte-identical.
     */
    public TacticalProfile(Mentality mentality, GameStyle gameStyle, AttackZone attackZone, String formation,
                           Pressing pressing, Marking marking, Map<UUID, RoleAssignment> roles,
                           UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId,
                           Map<UUID, PlayerInstruction> instructions,
                           Map<UUID, FormationSlots.Slot> slotAssignments) {
        this(mentality, gameStyle, attackZone, formation, pressing, marking, roles, penaltyTakerId,
                freekickTakerId, cornerTakerId, instructions, slotAssignments, Map.of());
    }

    /**
     * The same tactics with a live arrangement laid over them: roles, slot assignments and slot
     * indexes replaced, everything else — mentality, style, takers, instructions — untouched.
     */
    public TacticalProfile withArrangement(Map<UUID, FormationSlots.Slot> arrangedSlots,
                                           Map<UUID, Integer> arrangedIndexes,
                                           Map<UUID, RoleAssignment> arrangedRoles) {
        return new TacticalProfile(mentality, gameStyle, attackZone, formation, pressing, marking,
                arrangedRoles, penaltyTakerId, freekickTakerId, cornerTakerId, instructions,
                arrangedSlots, arrangedIndexes);
    }
```

Update the class javadoc paragraph that begins `<p>{@code slotAssignments} maps each starter…`. Delete its final sentence ("NOTE: a mid-match formation change does NOT re-grade positions…"), and add:

```
 * <p>{@code slotIndexes} carries each starter's kickoff slot INDEX into {@code FormationSlots.of(formation)}
 * — the only thing that distinguishes two slots accepting the same positions. It is where the live engine
 * starts tracking the arrangement from; mid-match rearrangement, substitute slot inheritance and a
 * manager's formation relocation all happen on the engine's own side state, not in this record
 * (spec 2026-09-10). An empty map means "untracked".
```

- [ ] **Step 4: Implement the builders**

`TacticsService.defaultProfile` — replace the single `SlotAssigner.assignToSlots(xi, formation)` line with:

```java
        Map<UUID, Integer> slotIndexes = SlotAssigner.assignToSlotIndexes(xi, formation, Map.of());
        Map<UUID, FormationSlots.Slot> slotAssignments = SlotAssigner.slotsFor(slotIndexes, formation);
```

and pass `slotIndexes` as the last constructor argument: `roles, bestFinisher, bestPasser, bestFinisher, Map.of(), slotAssignments, slotIndexes);`

`TacticsService.fromLineup` — replace the `SlotAssigner.assignToSlots(xi, lineup.getFormation(), pinnedSlots)` assignment with:

```java
        Map<UUID, Integer> slotIndexes =
                SlotAssigner.assignToSlotIndexes(xi, lineup.getFormation(), pinnedSlots);
        Map<UUID, FormationSlots.Slot> slotAssignments =
                SlotAssigner.slotsFor(slotIndexes, lineup.getFormation());
```

and end the constructor call with `instructions, slotAssignments, slotIndexes);`

`AiManagerBrain.with` — end the constructor call with `identity.cornerTakerId(), identity.instructions(), identity.slotAssignments(), identity.slotIndexes());`

`LiveMatchEngine.applyTacticChanges` — end the constructor call with `old.instructions(), old.slotAssignments(), old.slotIndexes());`. Replace the comment above it with:

```java
                    // Instructions never change mid-match. Slot data is carried through untouched here;
                    // a tracked side's live arrangement lives on the Side and is overlaid at strength time.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.TeamStrengthCalculatorTest' --tests 'br.com.carvoeiromanager.match.AiManagerBrainTest' --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest' --tests 'br.com.carvoeiromanager.match.MatchCalibrationTest' --tests 'br.com.carvoeiromanager.match.PlayerInstruction*' --tests 'br.com.carvoeiromanager.match.WeatherEngineIntegrationTest' --tests 'br.com.carvoeiromanager.match.SlotAssigner*'; echo "EXIT=$?"`
Expected: PASS, EXIT=0.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/TacticalProfile.java backend/src/main/java/br/com/carvoeiromanager/match/TacticsService.java backend/src/main/java/br/com/carvoeiromanager/match/AiManagerBrain.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java backend/src/test/java/br/com/carvoeiromanager/match/TeamStrengthCalculatorTest.java backend/src/test/java/br/com/carvoeiromanager/match/AiManagerBrainTest.java
git commit -m "feat(match): carry kickoff slot indexes in the tactical profile"
```

---

### Task 3: Track the live arrangement in the engine

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/TimedSlots.java`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/ArrangementSnapshot.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimeline.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`

**Interfaces:**
- Consumes: `TacticalProfile.slotIndexes()`, `TacticalProfile.withArrangement(...)` (Task 2); `SlotAssigner.assignToSlotIndexes`, `SlotAssigner.slotsFor` (Task 1); `TacticsService.bestAssignmentFor(Player, FormationSlots.Slot)` (existing, package-private static).
- Produces:
  - `record TimedSlots(int minute, boolean home, Map<UUID, Integer> slotIndexes, Map<UUID, RoleArchetype> roles)`
  - `record ArrangementSnapshot(int minute, boolean home, String formation, Map<UUID, Integer> slotIndexes, Map<UUID, RoleAssignment> roles)`
  - `LiveMatchConfig` 16th component `List<TimedSlots> slotSchedule`. The existing 15-argument constructor keeps compiling with `List.of()`.
  - `LiveTimeline` 8th component `List<ArrangementSnapshot> arrangements`. The existing 7-argument constructor keeps compiling with `List.of()`.
  - `Optional<ArrangementSnapshot> LiveTimeline.arrangementAt(int minute, boolean home)` — the latest snapshot for that side at or before `minute`.

- [ ] **Step 1: Create the records and compatibility constructors**

`TimedSlots.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.RoleArchetype;

import java.util.Map;
import java.util.UUID;

/**
 * A manual rearrangement of one side, applied at {@code minute} (spec 2026-09-10). A complete snapshot,
 * not a delta: {@code slotIndexes} places every player on the pitch by slot index into the formation in
 * force at that minute, so it replays without knowing any earlier state. {@code roles} holds only the
 * archetypes the manager set by hand; everyone else takes their slot's natural role. Like
 * {@link TimedTactics} it draws no RNG.
 */
public record TimedSlots(int minute, boolean home, Map<UUID, Integer> slotIndexes,
                         Map<UUID, RoleArchetype> roles) {
}
```

`ArrangementSnapshot.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.RoleAssignment;

import java.util.Map;
import java.util.UUID;

/**
 * One side's live arrangement from {@code minute} onward: who holds which slot index of
 * {@code formation}, and each on-pitch player's role. {@link LiveMatchEngine} records one at kickoff
 * (minute 0) and one at every change — a substitution, a red card, a manager's formation relocation, a
 * scheduled {@link TimedSlots}. A side whose profile carried no slot indexes records none.
 */
public record ArrangementSnapshot(int minute, boolean home, String formation,
                                  Map<UUID, Integer> slotIndexes, Map<UUID, RoleAssignment> roles) {
}
```

`LiveMatchConfig.java` — append `List<TimedSlots> slotSchedule` as the last component, add a sentence to the javadoc (`{@code slotSchedule} is the ordered list of manual rearrangements; empty is the neutral default.`), and add inside the body:

```java
    /** Pre-rearrangement constructor: no slot schedule. */
    public LiveMatchConfig(long seed, List<Player> homeXi, List<Player> awayXi, List<Player> homeBench,
                           List<Player> awayBench, TacticalProfile homeProfile, TacticalProfile awayProfile,
                           Map<UUID, Integer> fitness, List<LiveSub> subSchedule,
                           List<TimedTactics> tacticsSchedule, Boolean aiHome, Weather weather,
                           boolean knockoutDecider, Integer firstLegHomeGoals, Integer firstLegAwayGoals) {
        this(seed, homeXi, awayXi, homeBench, awayBench, homeProfile, awayProfile, fitness, subSchedule,
                tacticsSchedule, aiHome, weather, knockoutDecider, firstLegHomeGoals, firstLegAwayGoals, List.of());
    }
```

`LiveTimeline.java` — append `List<ArrangementSnapshot> arrangements` as the last component, add `import java.util.Optional;`, and add inside the body:

```java
    /** Pre-arrangement constructor: no snapshots. */
    public LiveTimeline(int homeGoals, int awayGoals, List<LiveTimelineEvent> events,
                        Map<UUID, Integer> minutesPlayed, List<TimedInjury> injuries, int finalMinute,
                        List<MinuteStats> minuteStats) {
        this(homeGoals, awayGoals, events, minutesPlayed, injuries, finalMinute, minuteStats, List.of());
    }

    /**
     * {@code home}'s arrangement as it stood at {@code minute}: the latest snapshot at or before it.
     * Snapshots are appended chronologically per side, so the last match in list order wins. Empty for
     * an untracked side.
     */
    public Optional<ArrangementSnapshot> arrangementAt(int minute, boolean home) {
        ArrangementSnapshot latest = null;
        for (ArrangementSnapshot s : arrangements) {
            if (s.home() == home && s.minute() <= minute) {
                latest = s;
            }
        }
        return Optional.ofNullable(latest);
    }
```

Add `{@code arrangements}` to the record's `@param` javadoc: `every ArrangementSnapshot the engine recorded, per side in chronological order.`

- [ ] **Step 2: Write the failing engine tests**

Add to `LiveMatchEngineTest`. Add imports `br.com.carvoeiromanager.player.DetailedPosition` and `br.com.carvoeiromanager.player.RoleArchetype`; `HashSet`, `HashMap`, `Arrays` are already imported. Helpers first:

```java
    // ---- live arrangement (spec 2026-09-10) ----

    /** defaultProfile, but carrying real kickoff slot indexes — a TRACKED side. */
    private static TacticalProfile trackedProfile(List<Player> xi, String formation) {
        Map<UUID, Integer> indexes = SlotAssigner.assignToSlotIndexes(xi, formation, Map.of());
        return new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO, formation,
                Pressing.MEDIUM, Marking.ZONAL, LegacyRoles.forXi(xi), null, null, null, Map.of(),
                SlotAssigner.slotsFor(indexes, formation), indexes);
    }

    private LiveMatchConfig trackedConfig(long seed, List<LiveSub> subs, List<TimedTactics> tactics,
                                          List<TimedSlots> slots) {
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
        awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
        homeBench.forEach(pl -> fitness.put(pl.getId(), 100));
        return new LiveMatchConfig(seed, homeXi, awayXi, homeBench, List.of(),
                trackedProfile(homeXi, "4-4-2"), trackedProfile(awayXi, "4-4-2"), fitness, subs, tactics,
                null, Weather.neutral(), false, null, null, slots);
    }

    private static LiveMatchConfig withProfiles(LiveMatchConfig c, TacticalProfile home, TacticalProfile away) {
        return new LiveMatchConfig(c.seed(), c.homeXi(), c.awayXi(), c.homeBench(), c.awayBench(), home, away,
                c.fitness(), c.subSchedule(), c.tacticsSchedule(), c.aiHome(), c.weather(), c.knockoutDecider(),
                c.firstLegHomeGoals(), c.firstLegAwayGoals(), c.slotSchedule());
    }

    private static boolean sentOffBy(LiveTimeline t, UUID playerId, int minute) {
        return t.events().stream().anyMatch(e ->
                e.type() == EventType.RED_CARD && playerId.equals(e.playerId()) && e.minute() <= minute);
    }
```

Tests:

```java
    @Test
    void anUntrackedSideIgnoresAScheduledArrangementEntirely() {
        LiveMatchConfig plain = config(555L, List.of());
        UUID a = homeXi.get(1).getId();
        UUID b = homeXi.get(2).getId();
        LiveMatchConfig withSlots = new LiveMatchConfig(plain.seed(), plain.homeXi(), plain.awayXi(),
                plain.homeBench(), plain.awayBench(), plain.homeProfile(), plain.awayProfile(), plain.fitness(),
                plain.subSchedule(), plain.tacticsSchedule(), plain.aiHome(), plain.weather(),
                plain.knockoutDecider(), plain.firstLegHomeGoals(), plain.firstLegAwayGoals(),
                List.of(new TimedSlots(30, true, Map.of(a, 2, b, 1), Map.of())));

        LiveTimeline t = engine.simulate(withSlots);

        assertThat(t.events()).isEqualTo(engine.simulate(plain).events());
        assertThat(t.arrangements()).isEmpty();
    }

    @Test
    void aTrackedSideRecordsItsKickoffArrangementAtMinuteZero() {
        LiveMatchConfig cfg = trackedConfig(1L, List.of(), List.of(), List.of());

        LiveTimeline t = engine.simulate(cfg);

        ArrangementSnapshot kickoff = t.arrangementAt(0, true).orElseThrow();
        assertThat(kickoff.formation()).isEqualTo("4-4-2");
        assertThat(kickoff.slotIndexes()).isEqualTo(cfg.homeProfile().slotIndexes());
    }

    @Test
    void aSubstituteTakesTheOutgoingPlayersSlotIndex() {
        UUID out = homeXi.get(5).getId();
        UUID in = homeBench.get(0).getId();
        int checked = 0;
        for (long seed = 0; seed < 20; seed++) {
            LiveTimeline t = engine.simulate(trackedConfig(seed, List.of(new LiveSub(60, true, out, in)),
                    List.of(), List.of()));
            if (sentOffBy(t, out, 59)) continue;   // applySubs skips a player no longer on the pitch
            int outIndex = t.arrangementAt(59, true).orElseThrow().slotIndexes().get(out);

            ArrangementSnapshot after = t.arrangementAt(60, true).orElseThrow();
            if (sentOffBy(t, in, 60)) continue;
            assertThat(after.slotIndexes()).as("seed %d", seed)
                    .doesNotContainKey(out)
                    .containsEntry(in, outIndex);
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    void anAiSubstituteAlsoTakesTheOutgoingPlayersSlotIndex() {
        List<Player> awayBench = awayBenchWithCover();
        int checked = 0;
        for (long seed = 0; seed < 40; seed++) {
            LiveMatchConfig cfg = withProfiles(configWithAwayBench(seed, awayBench, List.of(), false),
                    trackedProfile(homeXi, "4-4-2"), trackedProfile(awayXi, "4-4-2"));
            LiveTimeline t = engine.simulate(cfg);
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() != EventType.SUBSTITUTION || e.home()) continue;
                if (sentOffBy(t, e.playerInId(), e.minute())) continue;
                Integer outIndex = t.arrangementAt(e.minute() - 1, false).orElseThrow()
                        .slotIndexes().get(e.playerId());
                if (outIndex == null) continue;
                assertThat(t.arrangementAt(e.minute(), false).orElseThrow().slotIndexes())
                        .as("seed %d minute %d", seed, e.minute())
                        .containsEntry(e.playerInId(), outIndex);
                checked++;
            }
        }
        assertThat(checked).as("at least one AI substitution was exercised").isPositive();
    }

    @Test
    void aScheduledArrangementTakesEffectFromItsMinute() {
        Map<UUID, Integer> kickoff = trackedProfile(homeXi, "4-4-2").slotIndexes();
        UUID a = homeXi.get(1).getId();
        UUID b = homeXi.get(2).getId();
        Map<UUID, Integer> swapped = new HashMap<>(kickoff);
        swapped.put(a, kickoff.get(b));
        swapped.put(b, kickoff.get(a));
        int checked = 0;
        for (long seed = 0; seed < 20; seed++) {
            LiveTimeline t = engine.simulate(trackedConfig(seed, List.of(), List.of(),
                    List.of(new TimedSlots(30, true, swapped, Map.of()))));
            if (sentOffBy(t, a, 30) || sentOffBy(t, b, 30)) continue;

            assertThat(t.arrangementAt(29, true).orElseThrow().slotIndexes().get(a)).isEqualTo(kickoff.get(a));
            ArrangementSnapshot after = t.arrangementAt(30, true).orElseThrow();
            assertThat(after.slotIndexes().get(a)).as("seed %d", seed).isEqualTo(kickoff.get(b));
            assertThat(after.slotIndexes().get(b)).as("seed %d", seed).isEqualTo(kickoff.get(a));
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    void aValidRoleOverrideIsUsedAndAnInvalidOneFallsBackToTheSlotsNaturalRole() {
        Map<UUID, Integer> kickoff = trackedProfile(homeXi, "4-4-2").slotIndexes();
        UUID outfielder = homeXi.get(1).getId();
        UUID other = homeXi.get(2).getId();
        DetailedPosition outfielderSlot = FormationSlots.of("4-4-2").get(kickoff.get(outfielder))
                .accepted().iterator().next();
        RoleArchetype valid = Arrays.stream(RoleArchetype.values())
                .filter(r -> r.validFor(outfielderSlot)).findFirst().orElseThrow();
        // GOALKEEPER is never valid for an outfield slot.
        Map<UUID, RoleArchetype> overrides = Map.of(outfielder, valid, other, RoleArchetype.GOALKEEPER);

        LiveTimeline t = engine.simulate(trackedConfig(4L, List.of(), List.of(),
                List.of(new TimedSlots(10, true, kickoff, overrides))));

        ArrangementSnapshot after = t.arrangementAt(10, true).orElseThrow();
        assertThat(after.roles().get(outfielder).archetype()).isEqualTo(valid);
        assertThat(after.roles().get(other).archetype()).isNotEqualTo(RoleArchetype.GOALKEEPER);
    }

    @Test
    void aManagerFormationChangeRelocatesThePlayersIntoTheNewShape() {
        TimedTactics toFourThreeThree = new TimedTactics(30, true, Mentality.BALANCED, null, null,
                Pressing.MEDIUM, Marking.ZONAL, "4-3-3");

        LiveTimeline t = engine.simulate(trackedConfig(3L, List.of(), List.of(toFourThreeThree), List.of()));

        assertThat(t.arrangementAt(29, true).orElseThrow().formation()).isEqualTo("4-4-2");
        ArrangementSnapshot after = t.arrangementAt(30, true).orElseThrow();
        assertThat(after.formation()).isEqualTo("4-3-3");
        assertThat(new HashSet<>(after.slotIndexes().values())).hasSize(after.slotIndexes().size());
    }

    @Test
    void anAiPostureSwapChangesFormationWithoutRelocating() {
        Map<UUID, Integer> idx = SlotAssigner.assignToSlotIndexes(awayXi, "4-3-3", Map.of());
        TacticalProfile attackingAway = new TacticalProfile(Mentality.ATTACKING, GameStyle.EQUILIBRADO,
                AttackZone.MISTO, "4-3-3", Pressing.MEDIUM, Marking.ZONAL, LegacyRoles.forXi(awayXi),
                null, null, null, Map.of(), SlotAssigner.slotsFor(idx, "4-3-3"), idx);
        boolean sawFormationPosture = false;
        for (long seed = 1; seed <= 40; seed++) {
            Map<UUID, Integer> fitness = new HashMap<>();
            homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
            awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
            LiveTimeline t = engine.simulate(new LiveMatchConfig(seed, homeXi, awayXi, List.of(), List.of(),
                    defaultProfile(homeXi), attackingAway, fitness, List.of(), List.of(), false, Weather.neutral(),
                    false, null, null, List.of()));
            sawFormationPosture |= t.events().stream().anyMatch(e -> e.type() == EventType.TACTICS_CHANGE
                    && !e.home() && ("ALL_OUT".equals(e.detail()) || "PARK_THE_BUS".equals(e.detail())));
            assertThat(t.arrangements().stream().filter(s -> !s.home()))
                    .as("seed %d", seed)
                    .allMatch(s -> s.formation().equals("4-3-3"));
        }
        assertThat(sawFormationPosture).as("some seed reached a formation-changing posture").isTrue();
    }

    @Test
    void aSentOffPlayerLeavesTheArrangement() {
        boolean saw = false;
        for (long seed = 0; seed < 300 && !saw; seed++) {
            LiveTimeline t = engine.simulate(trackedConfig(seed, List.of(), List.of(), List.of()));
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() != EventType.RED_CARD || !e.home()) continue;
                assertThat(t.arrangementAt(e.minute(), true).orElseThrow().slotIndexes())
                        .doesNotContainKey(e.playerId());
                saw = true;
            }
        }
        assertThat(saw).isTrue();
    }
```

If `anAiPostureSwapChangesFormationWithoutRelocating` never reaches a formation-changing posture within 40 seeds, raise the seed bound. Do not weaken the assertion. If `aValidRoleOverride…` happens to send either player off before minute 10 on seed 4, choose another seed and say so in your report.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'; echo "EXIT=$?"`
Expected: every test that reads a snapshot via `arrangementAt(...).orElseThrow()` FAILS with `NoSuchElementException` — nothing is recorded yet. Two new tests **pass before implementation, and that is correct**: `anUntrackedSideIgnoresAScheduledArrangementEntirely` (nothing is tracked yet) and `anAiPostureSwapChangesFormationWithoutRelocating` (`allMatch` over zero snapshots). Both are regression guards for behaviour the implementation must not break, not RED evidence; say so in your report. Existing tests PASS.

- [ ] **Step 4: Implement tracking in `LiveMatchEngine`**

Add `import br.com.carvoeiromanager.player.DetailedPosition;`.

Add to `Side`, after `aiPostureSubMade`:

```java
        // Live arrangement (spec 2026-09-10). Tracked only when the kickoff profile carries slot indexes;
        // an untracked side (legacy profiles, this engine's fixed-seed golden tests) never touches these
        // fields, which is what keeps those timelines byte-identical. The arrangement lives here and not in
        // currentProfile because tactics changes replace currentProfile wholesale and AI posture swaps
        // rebuild it from the kickoff identity — either would silently reset it.
        boolean arrangementTracked = false;
        String arrangementFormation;                       // the formation slotIndexes refer to
        final Map<UUID, Integer> slotIndexes = new HashMap<>();
        final Map<UUID, FormationSlots.Slot> slotAssignments = new HashMap<>();
        final Map<UUID, RoleAssignment> roles = new HashMap<>();
        final List<ArrangementSnapshot> snapshots = new ArrayList<>();
```

In `simulate`, directly after `away.currentProfile = cfg.awayProfile();`:

```java
        initArrangement(home, cfg.homeProfile());
        initArrangement(away, cfg.awayProfile());
```

Replace the four `strength(..., home.currentProfile, ...)` / `strength(..., away.currentProfile, ...)` arguments (the initial `homeInput`/`awayInput` and the two `dirty` rebuilds in the loop) with `profileOf(home)` / `profileOf(away)`.

Replace the loop's apply block with:

```java
            applyTacticChanges(cfg, minute, home, events);
            applyTacticChanges(cfg, minute, away, events);
            applySubs(cfg, minute, home, events);
            applySubs(cfg, minute, away, events);
            // After BOTH sides' substitutions: an arrangement submitted together with a substitution names
            // the player coming on, who is only on the pitch once applySubs has run (plan ruling R1).
            applySlotChanges(cfg, minute, home);
            applySlotChanges(cfg, minute, away);
```

In `resolveScoringEvent`'s open-play branch, replace:

```java
            Map<UUID, RoleAssignment> roles = side.currentProfile.roles();
            TacticalProfile profile = side.currentProfile;
```

with:

```java
            // Attribution reads roles, so it reads the live arrangement's roles, not the kickoff ones.
            TacticalProfile profile = profileOf(side);
            Map<UUID, RoleAssignment> roles = profile.roles();
```

In `applyTacticChanges`, after `side.dirty = true;`:

```java
            // A MANAGER formation change relocates the players into the new shape (spec Decision 4).
            // AI posture swaps change formation through reactAiManager instead and deliberately do not
            // relocate (plan ruling R4).
            if (side.arrangementTracked && !t.formation().equals(side.arrangementFormation)) {
                relocate(side, t.formation(), minute);
            }
```

In `applySubs`, after `side.dirty = true;`: `inheritSlot(side, sub.outId(), sub.inId(), minute);`

In `maybeAiSubstitution`, after `ai.dirty = true;`: `inheritSlot(ai, d.outId(), d.inId(), minute);`

In `maybeCard`, inside `if (t == EventType.RED_CARD || secondYellow) {`, after `side.dirty = true;`: `vacateSlot(side, booked, minute);`

Replace the final `return new LiveTimeline(...)` with:

```java
        List<ArrangementSnapshot> arrangements = new ArrayList<>(home.snapshots);
        arrangements.addAll(away.snapshots);
        return new LiveTimeline(hg, ag, List.copyOf(events), Map.copyOf(minutes), List.copyOf(injuries), finalMinute,
                List.copyOf(minuteStats), List.copyOf(arrangements));
```

Add a new section after `applyTacticChanges`:

```java
    // ---- live arrangement (spec 2026-09-10) ----

    private static void initArrangement(Side side, TacticalProfile profile) {
        if (profile.slotIndexes() == null || profile.slotIndexes().isEmpty()) return;
        side.arrangementTracked = true;
        side.arrangementFormation = profile.formation();
        side.slotIndexes.putAll(profile.slotIndexes());
        side.slotAssignments.putAll(profile.slotAssignments());
        side.roles.putAll(profile.roles());
        snapshot(side, 0);
    }

    /**
     * The profile this side actually plays: its tactical profile with the LIVE arrangement laid over it.
     * An untracked side plays its profile exactly as given.
     */
    private static TacticalProfile profileOf(Side side) {
        if (!side.arrangementTracked) return side.currentProfile;
        return side.currentProfile.withArrangement(
                Map.copyOf(side.slotAssignments), Map.copyOf(side.slotIndexes), Map.copyOf(side.roles));
    }

    /**
     * Applies any {@link TimedSlots} scheduled for this minute and side. Pins go through
     * {@link SlotAssigner#assignToSlotIndexes}, so a pin for a player no longer on the pitch is ignored
     * and everyone else still lands. Draws no RNG and emits no event (plan ruling R9).
     */
    private void applySlotChanges(LiveMatchConfig cfg, int minute, Side side) {
        if (!side.arrangementTracked) return;
        for (TimedSlots entry : cfg.slotSchedule()) {
            if (entry.minute() != minute || entry.home() != side.home) continue;
            List<Player> onPitch = side.onPitch.stream().map(side.all::get).toList();
            String formation = side.currentProfile.formation();
            Map<UUID, Integer> indexes = SlotAssigner.assignToSlotIndexes(onPitch, formation, entry.slotIndexes());
            replaceArrangement(side, formation, indexes, entry.roles() == null ? Map.of() : entry.roles());
            snapshot(side, minute);
        }
    }

    private static void relocate(Side side, String formation, int minute) {
        List<Player> onPitch = side.onPitch.stream().map(side.all::get).toList();
        replaceArrangement(side, formation, SlotAssigner.assignToSlotIndexes(onPitch, formation, Map.of()), Map.of());
        snapshot(side, minute);
    }

    /**
     * Installs a new arrangement. Roles follow plan ruling R6: a player whose slot index AND formation are
     * unchanged keeps their current role, which preserves a role the manager set before the match; anyone
     * who moved takes a valid hand-set override, else the best familiar role for the slot they now hold.
     */
    private static void replaceArrangement(Side side, String formation, Map<UUID, Integer> indexes,
                                           Map<UUID, RoleArchetype> overrides) {
        Map<UUID, Integer> previousIndexes = new HashMap<>(side.slotIndexes);
        Map<UUID, RoleAssignment> previousRoles = new HashMap<>(side.roles);
        boolean sameFormation = formation.equals(side.arrangementFormation);
        Map<UUID, FormationSlots.Slot> slots = SlotAssigner.slotsFor(indexes, formation);

        side.arrangementFormation = formation;
        side.slotIndexes.clear();
        side.slotIndexes.putAll(indexes);
        side.slotAssignments.clear();
        side.slotAssignments.putAll(slots);
        side.roles.clear();
        for (Map.Entry<UUID, FormationSlots.Slot> e : slots.entrySet()) {
            UUID id = e.getKey();
            DetailedPosition position = e.getValue().accepted().iterator().next();
            RoleArchetype override = overrides.get(id);
            RoleAssignment role;
            if (override != null && override.validFor(position)) {
                role = new RoleAssignment(override, override.defaultFocusFor(position));
            } else if (sameFormation && indexes.get(id).equals(previousIndexes.get(id)) && previousRoles.containsKey(id)) {
                role = previousRoles.get(id);
            } else {
                role = TacticsService.bestAssignmentFor(side.all.get(id), e.getValue());
            }
            side.roles.put(id, role);
        }
        side.dirty = true;
    }

    /** Spec Decision 5: the player coming on takes the outgoing player's slot, and is graded for it. */
    private static void inheritSlot(Side side, UUID outId, UUID inId, int minute) {
        if (!side.arrangementTracked) return;
        Integer index = side.slotIndexes.remove(outId);
        FormationSlots.Slot slot = side.slotAssignments.remove(outId);
        side.roles.remove(outId);
        if (index != null && slot != null) {
            side.slotIndexes.put(inId, index);
            side.slotAssignments.put(inId, slot);
            side.roles.put(inId, TacticsService.bestAssignmentFor(side.all.get(inId), slot));
        }
        snapshot(side, minute);
    }

    private static void vacateSlot(Side side, UUID playerId, int minute) {
        if (!side.arrangementTracked) return;
        side.slotIndexes.remove(playerId);
        side.slotAssignments.remove(playerId);
        side.roles.remove(playerId);
        snapshot(side, minute);
    }

    private static void snapshot(Side side, int minute) {
        side.snapshots.add(new ArrangementSnapshot(minute, side.home, side.arrangementFormation,
                Map.copyOf(side.slotIndexes), Map.copyOf(side.roles)));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest' --tests 'br.com.carvoeiromanager.match.LiveTimeline*' --tests 'br.com.carvoeiromanager.match.TeamStrengthCalculatorTest' --tests 'br.com.carvoeiromanager.match.AiManagerBrainTest' --tests 'br.com.carvoeiromanager.match.MatchCalibrationTest' --tests 'br.com.carvoeiromanager.match.PlayerInstruction*' --tests 'br.com.carvoeiromanager.match.WeatherEngineIntegrationTest' --tests 'br.com.carvoeiromanager.match.SlotAssigner*'; echo "EXIT=$?"`
Expected: PASS, EXIT=0, and every pre-existing golden in `LiveMatchEngineTest` unchanged. **If a pre-existing golden changes, stop and report it** — the untracked path leaked. Do not edit a golden.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/TimedSlots.java backend/src/main/java/br/com/carvoeiromanager/match/ArrangementSnapshot.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchConfig.java backend/src/main/java/br/com/carvoeiromanager/match/LiveTimeline.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java
git commit -m "feat(match): track each side's live arrangement in the engine"
```

---

### Task 4: Persist, validate and report the arrangement

**Files:**
- Create: `backend/src/main/resources/db/migration/V56__live_match_slot_schedule.sql`
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/ArrangementRequest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTacticsRequest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatch.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` — `changeTactics` (:263-285), `configFor` (:582-628), `toState` (:690-712), `livePlayer` (:1159-1166), deserialize helpers (:1216-1224)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchArrangementIT.java`

**Interfaces:**
- Consumes: `TimedSlots`, `ArrangementSnapshot`, `LiveMatchConfig` 16-arg, `LiveTimeline.arrangementAt` (Task 3).
- Produces:
  - `record ArrangementRequest(Map<String, Integer> slotIndexes, Map<String, String> roles)`
  - `LiveTacticsRequest` 7th component `ArrangementRequest arrangement`; the 6-argument constructor keeps compiling with `null`.
  - `LivePlayerResponse(..., String role, Integer slotIndex, String detailedPosition, List<String> altPositions)`
  - HTTP: `POST /save-games/{id}/live-match/tactics` accepts optional `arrangement` and rejects an invalid one with `INVALID_LINEUP` (422).

- [ ] **Step 1: Write the failing integration test**

Create `LiveMatchArrangementIT.java`. Copy the class annotations, the five autowired fields, the three id fields and the whole `setUp()` **verbatim** from `LiveMatchTacticsIT.java:26-74`, renaming the user prefix to `"live_arrangement_"`. Then add:

```java
    private static Map<String, Integer> slotsOf(LiveMatchState s) {
        return s.onPitch().stream()
                .collect(Collectors.toMap(LivePlayerResponse::playerId, LivePlayerResponse::slotIndex));
    }

    private static LiveTacticsRequest currentTacticsWith(LiveMatchState s, ArrangementRequest arrangement) {
        LiveTactics t = s.currentTactics();
        return new LiveTacticsRequest(t.mentality(), t.gameStyle(), t.attackZone(), t.pressing(), t.marking(),
                t.formation(), arrangement);
    }

    private LiveMatchState advanceToMinuteOne() {
        LiveMatchState state = liveMatchService.advance(saveGameId, userId);
        for (int i = 0; i < 90 && state.minute() < 1; i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        return state;
    }

    private static void assertInvalidLineup(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_LINEUP));
    }

    @Test
    void kickoffReportsADistinctSlotIndexForEveryPlayerOnThePitch() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.onPitch()).hasSize(11)
                .allSatisfy(p -> {
                    assertThat(p.slotIndex()).isBetween(0, 10);
                    assertThat(p.detailedPosition()).isNotNull();
                });
        assertThat(new HashSet<>(slotsOf(state).values())).hasSize(11);
        assertThat(state.bench()).allSatisfy(p -> assertThat(p.slotIndex()).isNull());
    }

    @Test
    void aSwapAppliesFromTheNextMinuteWithoutASpuriousTacticsChange() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        String a = state.onPitch().get(1).playerId();
        String b = state.onPitch().get(2).playerId();
        int aIndex = slots.get(a);
        int bIndex = slots.get(b);
        slots.put(a, bIndex);
        slots.put(b, aIndex);

        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).containsEntry(a, bIndex).containsEntry(b, aIndex);
        assertThat(after.events())
                .noneMatch(e -> "TACTICS_CHANGE".equals(e.type()) && e.home() == after.managedIsHome());
    }

    @Test
    void aSubstituteInheritsTheOutgoingPlayersSlot() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        String out = state.onPitch().get(5).playerId();
        int outIndex = slotsOf(state).get(out);
        String in = state.bench().get(0).playerId();

        liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(
                List.of(new SubstituteRequest(UUID.fromString(out), UUID.fromString(in)))));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).doesNotContainKey(out).containsEntry(in, outIndex);
    }

    @Test
    void anArrangementMayPlaceAPlayerSubstitutedOnInTheSameApply() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        String out = state.onPitch().get(5).playerId();
        String keeper = state.onPitch().get(0).playerId();
        String in = state.bench().get(0).playerId();
        liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(
                List.of(new SubstituteRequest(UUID.fromString(out), UUID.fromString(in)))));

        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        int outIndex = slots.remove(out);
        int keeperIndex = slots.get(keeper);
        slots.put(in, keeperIndex);
        slots.put(keeper, outIndex);
        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).containsEntry(in, keeperIndex).containsEntry(keeper, outIndex);
    }

    @Test
    void anArrangementThatLeavesAPlayerOutIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        slots.remove(state.onPitch().get(3).playerId());

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of()))));
    }

    @Test
    void anArrangementThatPutsTwoPlayersInOneSlotIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        slots.put(state.onPitch().get(1).playerId(), slots.get(state.onPitch().get(2).playerId()));

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of()))));
    }

    @Test
    void aRoleThatIsInvalidForTheSlotIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = slotsOf(state);
        String outfielder = state.onPitch().stream()
                .filter(p -> !"GK".equals(p.position())).findFirst().orElseThrow().playerId();

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of(outfielder, "GOALKEEPER")))));
    }
```

Imports: `ApiException`, `ErrorCode`, `org.assertj.core.api.ThrowableAssert`, `java.util.HashMap`, `java.util.HashSet`, `java.util.Map`, plus those already copied from `LiveMatchTacticsIT`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchArrangementIT'; echo "EXIT=$?"`
Expected: compilation FAIL — `ArrangementRequest`, `slotIndex()`, 7-arg `LiveTacticsRequest` do not exist.

- [ ] **Step 3: Migration, entity and request/response records**

`V56__live_match_slot_schedule.sql`:

```sql
ALTER TABLE live_matches ADD COLUMN slot_schedule TEXT NOT NULL DEFAULT '[]';
```

`LiveMatch.java` — after the `tacticsSchedule` field:

```java
    @Column(name = "slot_schedule", nullable = false, columnDefinition = "TEXT")
    private String slotSchedule;
```

In the private constructor, after `this.tacticsSchedule = "[]";` add `this.slotSchedule = "[]";`. Add the getter and setter next to the tactics ones:

```java
    public String getSlotSchedule() { return slotSchedule; }
    public void setSlotSchedule(String slotSchedule) { this.slotSchedule = slotSchedule; }
```

`ArrangementRequest.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.Map;

/**
 * A manual rearrangement of the managed side, sent inside a {@link LiveTacticsRequest}. String-keyed
 * because it crosses JSON. {@code slotIndexes}: playerId → slot index, for exactly the players on the
 * pitch. {@code roles}: playerId → {@code RoleArchetype} name for hand-set roles only; may be empty.
 */
public record ArrangementRequest(Map<String, Integer> slotIndexes, Map<String, String> roles) {
}
```

`LiveTacticsRequest.java` — append the component and constructor:

```java
        @NotBlank String formation,
        // Optional: null means "no rearrangement" — every client from before live positioning.
        ArrangementRequest arrangement) {

    public LiveTacticsRequest(String mentality, String gameStyle, String attackZone,
                              String pressing, String marking, String formation) {
        this(mentality, gameStyle, attackZone, pressing, marking, formation, null);
    }
}
```

`LivePlayerResponse.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.List;

public record LivePlayerResponse(
        String playerId,
        String name,
        String position,
        int overall,
        int fitness,
        boolean hobbled,
        boolean onPitch,
        boolean used,
        boolean eligible,
        String role,
        /** Slot index into the side's displayed formation; null on the bench or when not knowable. */
        Integer slotIndex,
        String detailedPosition,
        List<String> altPositions) {
}
```

- [ ] **Step 4: Service**

`configFor` — after `List<TimedTactics> tacticsSchedule = …`: `List<TimedSlots> slotSchedule = deserializeSlots(lm.getSlotSchedule());`. Pass `slotSchedule` as the new last argument of `new LiveMatchConfig(...)`.

Deserialize helper next to `deserializeTactics`:

```java
    List<TimedSlots> deserializeSlots(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<TimedSlots>>() { }); }
        catch (Exception e) { throw new RuntimeException("Failed to read slot schedule", e); }
    }
```

`changeTactics` — replace everything from `int minute = …` to `liveMatchRepository.save(lm);` with:

```java
        int minute = Math.min(precheckTimeline.finalMinute(), lm.getCommittedMinute() + 1);

        // Validated before anything is persisted, so a bad arrangement rejects the whole request.
        TimedSlots slots = req.arrangement() == null ? null
                : parseArrangement(lm, precheckCfg, precheckTimeline, minute, formation, req.arrangement());

        // Plan ruling R8: an arrangement-only apply re-sends the tactics already in force. Recording them
        // again would schedule a no-op TimedTactics and put a spurious "mudança tática" in the commentary,
        // so they are written only when something in them actually changed. Compared against the tactics
        // in force at `minute`, which includes a change already scheduled for that same minute.
        LiveTactics inForce = tacticsFor(precheckCfg, minute, lm.isManagedIsHome());
        boolean tacticsChanged = !inForce.mentality().equals(mentality.name())
                || !inForce.pressing().equals(pressing.name())
                || !inForce.marking().equals(marking.name())
                || !inForce.formation().equals(formation)
                || (gameStyle != null && !inForce.gameStyle().equals(gameStyle.name()))
                || (attackZone != null && !inForce.attackZone().equals(attackZone.name()));
        if (slots == null || tacticsChanged) {
            List<TimedTactics> tactics = new ArrayList<>(deserializeTactics(lm.getTacticsSchedule()));
            // A null gameStyle/attackZone (client omitted it) records "keep current" in the schedule entry.
            tactics.add(new TimedTactics(minute, lm.isManagedIsHome(), mentality, gameStyle, attackZone,
                    pressing, marking, formation));
            lm.setTacticsSchedule(serialize(tactics));
        }
        if (slots != null) {
            List<TimedSlots> schedule = new ArrayList<>(deserializeSlots(lm.getSlotSchedule()));
            schedule.add(slots);
            lm.setSlotSchedule(serialize(schedule));
        }
        liveMatchRepository.save(lm);
```

Add after `parseFormation`:

```java
    /**
     * Plan ruling R7: an arrangement must place exactly the managed side's players on the pitch at
     * `minute` — the committed on-pitch set with the substitutions already scheduled for that same
     * minute applied, since those land before the arrangement does (engine order: tactics, subs, slots).
     * After a red card that is ten players, not eleven.
     */
    private TimedSlots parseArrangement(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute,
                                        String formation, ArrangementRequest req) {
        boolean home = lm.isManagedIsHome();
        List<Player> managedXi = home ? cfg.homeXi() : cfg.awayXi();
        List<UUID> onPitch = new ArrayList<>(onPitchManaged(lm, timeline, lm.getCommittedMinute(), managedXi).onPitch());
        for (LiveSub sub : cfg.subSchedule()) {
            if (sub.home() == home && sub.minute() == minute && onPitch.remove(sub.outId())) {
                onPitch.add(sub.inId());
            }
        }

        if (req.slotIndexes() == null) {
            throw invalidArrangement("An arrangement must place every player on the pitch");
        }
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, Integer> indexes = new HashMap<>();
        for (Map.Entry<String, Integer> e : req.slotIndexes().entrySet()) {
            Integer index = e.getValue();
            if (index == null || index < 0 || index >= slots.size()) {
                throw invalidArrangement("Slot index out of range for " + formation + ": " + index);
            }
            if (indexes.containsValue(index)) {
                throw invalidArrangement("Two players cannot share slot " + index);
            }
            indexes.put(parsePlayerId(e.getKey()), index);
        }
        if (!indexes.keySet().equals(new HashSet<>(onPitch))) {
            throw invalidArrangement("An arrangement must place exactly the players on the pitch");
        }

        Map<UUID, RoleArchetype> roles = new HashMap<>();
        if (req.roles() != null) {
            for (Map.Entry<String, String> e : req.roles().entrySet()) {
                UUID id = parsePlayerId(e.getKey());
                Integer index = indexes.get(id);
                if (index == null) {
                    throw invalidArrangement("A role was given for a player the arrangement does not place");
                }
                RoleArchetype archetype;
                try {
                    archetype = RoleArchetype.valueOf(e.getValue());
                } catch (IllegalArgumentException | NullPointerException ex) {
                    throw invalidArrangement("Unknown role: " + e.getValue());
                }
                DetailedPosition position = slots.get(index).accepted().iterator().next();
                if (!archetype.validFor(position)) {
                    throw invalidArrangement(archetype + " is not a valid role for " + position);
                }
                roles.put(id, archetype);
            }
        }
        return new TimedSlots(minute, home, Map.copyOf(indexes), Map.copyOf(roles));
    }

    private static UUID parsePlayerId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalidArrangement("Unknown player id: " + raw);
        }
    }

    private static ApiException invalidArrangement(String message) {
        return new ApiException(ErrorCode.INVALID_LINEUP, message);
    }
```

`toState` — move the two lines computing `currentTactics` and `opponentTactics` up to **directly above** the `// Managed on-pitch/bench for the sub dialog.` comment. Then build the rosters with the arrangement:

```java
        Optional<ArrangementSnapshot> managedArrangement = timeline.arrangementAt(minute, managedIsHome);
        Optional<ArrangementSnapshot> opponentArrangement = timeline.arrangementAt(minute, !managedIsHome);
```

and pass `managedArrangement, currentTactics.formation()` (for `onPitch` and `bench`) or `opponentArrangement, opponentTactics.formation()` (for `opponentOnPitch`) as the new 4th and 5th arguments of each `livePlayer(...)` call.

Replace `livePlayer`:

```java
    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, TacticalProfile profile,
                                          Optional<ArrangementSnapshot> arrangement, String displayedFormation,
                                          boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        // Plan ruling R10: an index is only meaningful against the formation it was assigned under. A
        // manager's formation change relocates, so the two agree; if they ever differ, "unknown" is the
        // honest answer and the pitch falls back to its line-zip instead of drawing players in wrong slots.
        Integer slotIndex = arrangement
                .filter(a -> a.formation().equals(displayedFormation))
                .map(a -> a.slotIndexes().get(p.getId()))
                .orElse(null);
        RoleAssignment role = arrangement
                .map(a -> a.roles().get(p.getId()))
                .orElseGet(() -> RoleAssignment.resolve(profile.roles(), p));
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible(), role.archetype().name(),
                slotIndex,
                p.getDetailedPosition() == null ? null : p.getDetailedPosition().name(),
                p.getAltPositions().stream().map(Enum::name).toList());
    }
```

Add any missing imports the compiler reports (`Optional`, `HashSet`, `DetailedPosition`, `RoleArchetype`).

- [ ] **Step 5: Run the tests to verify they pass**

Run (blocking; Docker must be up): `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatch*' --tests 'br.com.carvoeiromanager.match.Tactics*' --tests 'br.com.carvoeiromanager.match.LoanLiveMatchEligibilityIT'; echo "EXIT=$?"`
Expected: PASS, EXIT=0.

A pre-existing live-match IT that asserts an exact score, rating or event sequence **with a substitution in it** may now legitimately differ, because substitutes are graded (Decision 5). If one fails, confirm the failing scenario contains a substitution, then update the expectation and say so explicitly in your report. A failure in a scenario **without** a substitution or rearrangement is a real regression — report it, do not update it.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V56__live_match_slot_schedule.sql backend/src/main/java/br/com/carvoeiromanager/match/ArrangementRequest.java backend/src/main/java/br/com/carvoeiromanager/match/LiveTacticsRequest.java backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatch.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchArrangementIT.java
git commit -m "feat(match): accept, validate and report live-match arrangements"
```

(Add any IT you had to update under the rule in Step 5 to this commit.)

---

### Task 5: Frontend types and a truthful Escalações pitch

**Files:**
- Modify: `frontend/src/api/types.ts:846-878`
- Modify: `frontend/src/features/match/LineupsTab.tsx:10-24`
- Create: `frontend/src/features/match/LineupsTab.test.tsx`
- Modify (fixtures only): `frontend/src/features/match/matchSides.test.ts`, `frontend/src/features/match/livePlayerContext.test.ts`, `frontend/src/features/match/MatchActionsDialog.test.tsx`, `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Consumes: the HTTP shape from Task 4.
- Produces:
  - `LivePlayer` gains `slotIndex: number | null`, `detailedPosition: DetailedPosition | null`, `altPositions: DetailedPosition[]`.
  - `export type LiveArrangement = { slotIndexes: Record<string, number>; roles: Record<string, RoleArchetype> }`
  - `LiveTacticsRequest` gains `arrangement?: LiveArrangement`.
  - `buildTeam(...)` places players by `slotIndex` when every player has one.

- [ ] **Step 1: Write the failing test**

`LineupsTab.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { buildTeam } from './LineupsTab'
import type { LivePlayer } from '../../api/types'

const KIT = { primary: '#ffcc00', secondary: '#000000' }

function livePlayer(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Jogador', position: 'DEF', overall: 70, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'STOPPER',
    slotIndex: null, detailedPosition: null, altPositions: [],
    ...overrides,
  }
}

describe('buildTeam', () => {
  it('carries the server slot index and positions into the pitch view-model', () => {
    const team = buildTeam(
      [livePlayer({ playerId: 'z', slotIndex: 2, detailedPosition: 'ZAGUEIRO', altPositions: ['VOLANTE'] })],
      '4-4-2', KIT,
    )

    expect(team.players[0]).toMatchObject({
      id: 'z', slotIndex: 2, detailedPosition: 'ZAGUEIRO', altPositions: ['VOLANTE'],
    })
  })

  it('leaves slotIndex undefined when the server sent none, so the pitch falls back to its line-zip', () => {
    const team = buildTeam([livePlayer({ playerId: 'z', slotIndex: null })], '4-4-2', KIT)

    expect(team.players[0].slotIndex).toBeUndefined()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/match/LineupsTab.test.tsx`
Expected: FAIL — `slotIndex` / `detailedPosition` / `altPositions` are not in the view-model (`toMatchObject` mismatch).

- [ ] **Step 3: Implement**

`types.ts` — extend `LivePlayer` and `LiveTacticsRequest`, and add `LiveArrangement` above `LiveTacticsRequest`:

```ts
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
  role: string
  /** Slot index into the side's displayed formation; null on the bench or when the server can't know it. */
  slotIndex: number | null
  detailedPosition: DetailedPosition | null
  altPositions: DetailedPosition[]
}

/** A manual rearrangement: every on-pitch player's slot index, plus only the roles set by hand. */
export type LiveArrangement = {
  slotIndexes: Record<string, number>
  roles: Record<string, RoleArchetype>
}

export type LiveTacticsRequest = {
  mentality: string
  gameStyle: string
  attackZone: string
  pressing: string
  marking: string
  formation: string
  /** Omitted when nothing was rearranged, so a tactics-only apply sends exactly what it always has. */
  arrangement?: LiveArrangement
}
```

`LineupsTab.tsx` — in `buildTeam`'s player mapping add:

```tsx
      detailedPosition: p.detailedPosition,
      altPositions: p.altPositions,
      // Pitch places explicitly only when EVERY player has an index; one null falls back to the line-zip.
      slotIndex: p.slotIndex ?? undefined,
```

- [ ] **Step 4: Update the existing fixtures**

Run `cd frontend && npx tsc -b`. It now reports every `LivePlayer` literal missing the three new fields. Add `slotIndex: null, detailedPosition: null, altPositions: []` to each:
- `matchSides.test.ts` — `managedPlayer` and `opponentPlayer`
- `livePlayerContext.test.ts` — the `player()` helper's defaults
- `MatchActionsDialog.test.tsx` — the `player()` helper's defaults
- `LiveMatchPage.test.tsx` — every inline on-pitch/bench literal (lines ~107, 110, 130, 133, 478, 500, 504, 522, 526, and any others `tsc -b` names)

Do not change any assertion.

- [ ] **Step 5: Verify**

Run: `cd frontend && npx tsc -b; echo "TSC=$?" && npx vitest run && npm run lint; echo "LINT=$?"`
Expected: TSC=0, all tests PASS (previous count + 2), LINT=0.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/match/LineupsTab.tsx frontend/src/features/match/LineupsTab.test.tsx frontend/src/features/match/matchSides.test.ts frontend/src/features/match/livePlayerContext.test.ts frontend/src/features/match/MatchActionsDialog.test.tsx frontend/src/features/match/LiveMatchPage.test.tsx
git commit -m "feat(match): draw the live pitch from the server's real arrangement"
```

---

### Task 6: The Posições tab

**Files:**
- Create: `frontend/src/features/match/liveArrangement.ts`
- Create: `frontend/src/features/match/liveArrangement.test.ts`
- Create: `frontend/src/features/match/PositionsTabContent.tsx`
- Modify: `frontend/src/features/match/MatchActionsDialog.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.tsx` (the `<MatchActionsDialog>` element only)
- Test: `frontend/src/features/match/MatchActionsDialog.test.tsx`

**Interfaces:**
- Consumes: `LivePlayer.slotIndex/detailedPosition/altPositions`, `LiveArrangement`, `LiveTacticsRequest.arrangement` (Task 5); `assignSlots`, `AssignablePlayer` from `../pitch/slotAssign`; `Pitch`, `PlacedPlayer`, `TeamOnPitch` from `../pitch/Pitch`; `pitchSlots` from `../pitch/pitchSlots`; `RoleContextMenu`, `RoleMenuAnchor` from `../squad/RoleContextMenu`; `ARCHETYPE_LABEL`, `ARCHETYPES_FOR_POSITION` from `../players/roleLabels`.
- Produces:
  - `liveArrangement.ts`: `interface SubPair`, `type SlotMap`, `type RoleOverrides`, `workingRoster`, `baselineArrangement`, `swapSlots`, `sameSlots`.
  - `MatchActionsDialog` props: `initialTab: 0 | 1 | 2`, new required `kit: LiveKitColors`. `SubPair` is still importable from `./MatchActionsDialog`.

- [ ] **Step 1: Write the failing pure-module tests**

`liveArrangement.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { workingRoster, baselineArrangement, swapSlots, sameSlots } from './liveArrangement'
import type { LivePlayer } from '../../api/types'

function livePlayer(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Jogador', position: 'MID', overall: 70, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX',
    slotIndex: null, detailedPosition: null, altPositions: [],
    ...overrides,
  }
}

describe('workingRoster', () => {
  it('replaces a queued outgoing player with the incoming one, who inherits the slot index', () => {
    const out = livePlayer({ playerId: 'out', slotIndex: 6 })
    const stays = livePlayer({ playerId: 'stays', slotIndex: 0 })
    const incoming = livePlayer({ playerId: 'in', onPitch: false, slotIndex: null })

    const roster = workingRoster([stays, out], [{ out, in: incoming }])

    expect(roster.map(p => p.playerId)).toEqual(['stays', 'in'])
    expect(roster[1].slotIndex).toBe(6)
  })
})

describe('baselineArrangement', () => {
  it('uses the server indexes when the formation is unchanged and every player has one', () => {
    const roster = [livePlayer({ playerId: 'a', slotIndex: 3 }), livePlayer({ playerId: 'b', slotIndex: 7 })]

    expect(baselineArrangement(roster, '4-4-2', '4-4-2')).toEqual({ a: 3, b: 7 })
  })

  it('reassigns every player to a distinct slot when the formation changed', () => {
    const roster = [
      livePlayer({ playerId: 'gk', position: 'GK', detailedPosition: 'GOLEIRO', slotIndex: 0 }),
      livePlayer({ playerId: 'st', position: 'ATT', detailedPosition: 'ATACANTE', slotIndex: 9 }),
    ]

    const slots = baselineArrangement(roster, '4-3-3', '4-4-2')

    expect(Object.keys(slots).sort()).toEqual(['gk', 'st'])
    expect(new Set(Object.values(slots)).size).toBe(2)
    expect(slots.gk).toBe(0)
  })

  it('reassigns when any player lacks a server index', () => {
    const roster = [livePlayer({ playerId: 'a', slotIndex: 3 }), livePlayer({ playerId: 'b', slotIndex: null })]

    const slots = baselineArrangement(roster, '4-4-2', '4-4-2')

    expect(Object.keys(slots).sort()).toEqual(['a', 'b'])
  })
})

describe('swapSlots', () => {
  it('exchanges the two occupants', () => {
    expect(swapSlots({ a: 1, b: 2 }, 1, 2)).toEqual({ a: 2, b: 1 })
  })

  it('moves a player into an empty slot', () => {
    expect(swapSlots({ a: 1 }, 1, 5)).toEqual({ a: 5 })
  })

  it('leaves the map untouched when the origin slot is empty', () => {
    const slots = { a: 1 }
    expect(swapSlots(slots, 4, 5)).toBe(slots)
  })
})

describe('sameSlots', () => {
  it('is true only for identical assignments', () => {
    expect(sameSlots({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true)
    expect(sameSlots({ a: 1, b: 2 }, { a: 2, b: 1 })).toBe(false)
    expect(sameSlots({ a: 1 }, { a: 1, b: 2 })).toBe(false)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/match/liveArrangement.test.ts`
Expected: FAIL — cannot resolve `./liveArrangement`.

- [ ] **Step 3: Implement `liveArrangement.ts`**

```ts
import type { LivePlayer, RoleArchetype } from '../../api/types'
import { assignSlots, type AssignablePlayer } from '../pitch/slotAssign'

export interface SubPair {
  out: LivePlayer
  in: LivePlayer
}

/** playerId -> slot index into pitchSlots(formation). */
export type SlotMap = Record<string, number>
/** playerId -> archetype, for roles the manager set by hand only. */
export type RoleOverrides = Record<string, RoleArchetype>

/**
 * The on-pitch roster as it will stand once the queued substitutions land. Each outgoing player is
 * replaced in place by the incoming one, who inherits the outgoing player's slot index — the rule the
 * engine applies (spec Decision 5) — so the Posições tab shows the pitch the server will actually grade.
 */
export function workingRoster(onPitch: LivePlayer[], queue: SubPair[]): LivePlayer[] {
  return onPitch.map(p => {
    const pair = queue.find(q => q.out.playerId === p.playerId)
    return pair ? { ...pair.in, onPitch: true, slotIndex: p.slotIndex } : p
  })
}

const toAssignable = (p: LivePlayer): AssignablePlayer => ({
  id: p.playerId,
  position: p.position as AssignablePlayer['position'],
  detailedPosition: p.detailedPosition,
  altPositions: p.altPositions,
  overall: p.overall,
})

/**
 * Where editing starts from. When the formation is the one the server arranged against and every player
 * carries a server index, the server's arrangement IS the baseline. Otherwise — a formation change, or a
 * payload without indexes — it is `assignSlots`, the client mirror of the server's greedy assignment,
 * which is exactly the relocation the engine performs for a formation change.
 */
export function baselineArrangement(roster: LivePlayer[], formation: string, serverFormation: string): SlotMap {
  if (formation === serverFormation && roster.every(p => p.slotIndex !== null)) {
    return Object.fromEntries(roster.map(p => [p.playerId, p.slotIndex as number]))
  }
  return assignSlots(roster.map(toAssignable), formation)
}

/** Swaps whoever holds `fromSlot` with whoever holds `toSlot`; into an empty slot it is a plain move. */
export function swapSlots(slots: SlotMap, fromSlot: number, toSlot: number): SlotMap {
  const fromId = Object.keys(slots).find(id => slots[id] === fromSlot)
  if (fromId === undefined) return slots
  const toId = Object.keys(slots).find(id => slots[id] === toSlot)
  const next: SlotMap = { ...slots, [fromId]: toSlot }
  if (toId !== undefined) next[toId] = fromSlot
  return next
}

export function sameSlots(a: SlotMap, b: SlotMap): boolean {
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every(k => a[k] === b[k])
}
```

Run `npx vitest run src/features/match/liveArrangement.test.ts` — expected PASS.

- [ ] **Step 4: Write the failing dialog tests**

In `MatchActionsDialog.test.tsx`:
- add `const KIT = { primary: '#ffcc00', secondary: '#000000' }` under `DEFAULT_TACTICS`;
- pass `kit={KIT}` in `renderTacticsTab`, `renderDialog` and `renderSubTab`;
- add `waitFor` to the `@testing-library/react` import;
- add `import { ARCHETYPES_FOR_POSITION } from '../players/roleLabels'`.

Then append:

```tsx
describe('MatchActionsDialog positions tab', () => {
  const gk = player({ playerId: 'gk', name: 'Goleiro', position: 'GK', role: 'GOALKEEPER', slotIndex: 0, detailedPosition: 'GOLEIRO' })
  const zag = player({ playerId: 'zag', name: 'Zagueiro', position: 'DEF', role: 'STOPPER', slotIndex: 2, detailedPosition: 'ZAGUEIRO' })
  const reserve = player({ playerId: 'res', name: 'Reserva', position: 'DEF', role: 'STOPPER', onPitch: false, slotIndex: null, detailedPosition: 'ZAGUEIRO' })

  function openPositions(overrides: Parameters<typeof renderDialog>[0] = {}) {
    renderDialog({ onPitch: [gk, zag], bench: [reserve], ...overrides })
    fireEvent.click(screen.getByRole('tab', { name: 'Posições' }))
  }

  it('draws every on-pitch player on the pitch', () => {
    openPositions()

    const pitch = screen.getByTestId('pitch')
    expect(within(pitch).getByText('Goleiro')).toBeInTheDocument()
    expect(within(pitch).getByText('Zagueiro')).toBeInTheDocument()
  })

  it('leaves the apply button disabled when nothing was rearranged', () => {
    openPositions()

    expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled()
  })

  it('sends a hand-set role as one repositioning, alongside the unchanged tactics', async () => {
    const onApply = vi.fn()
    openPositions({ onApply })
    const archetype = ARCHETYPES_FOR_POSITION.ZAGUEIRO.find(a => a !== 'STOPPER')!

    fireEvent.contextMenu(within(screen.getByTestId('pitch')).getByText('Zagueiro'))
    fireEvent.change(screen.getByLabelText('Função'), { target: { value: archetype } })
    fireEvent.keyDown(screen.getByLabelText('Função'), { key: 'Escape' })

    const apply = await waitFor(() => screen.getByRole('button', { name: 'Aplicar 1 alteração' }))
    expect(screen.getByText('1 reposicionamento')).toBeInTheDocument()
    fireEvent.click(apply)
    expect(onApply).toHaveBeenCalledWith({
      subs: [],
      tactics: { ...DEFAULT_TACTICS, arrangement: { slotIndexes: { gk: 0, zag: 2 }, roles: { zag: archetype } } },
    })
  })

  it('shows the incoming player in place of the outgoing one once a substitution is queued', () => {
    renderDialog({ onPitch: [gk, zag], bench: [reserve] })
    fireEvent.click(within(outSection()).getByText('Zagueiro'))
    fireEvent.click(within(inSection()).getByText('Reserva'))
    fireEvent.click(screen.getByRole('tab', { name: 'Posições' }))

    const pitch = screen.getByTestId('pitch')
    expect(within(pitch).getByText('Reserva')).toBeInTheDocument()
    expect(within(pitch).queryByText('Zagueiro')).not.toBeInTheDocument()
  })

  it('discards rearrangement edits when the formation changes', async () => {
    openPositions()
    const archetype = ARCHETYPES_FOR_POSITION.ZAGUEIRO.find(a => a !== 'STOPPER')!
    fireEvent.contextMenu(within(screen.getByTestId('pitch')).getByText('Zagueiro'))
    fireEvent.change(screen.getByLabelText('Função'), { target: { value: archetype } })
    fireEvent.keyDown(screen.getByLabelText('Função'), { key: 'Escape' })
    await waitFor(() => screen.getByText('1 reposicionamento'))

    fireEvent.click(screen.getByRole('tab', { name: 'Instruções táticas' }))
    fireEvent.change(screen.getByLabelText('Formação'), { target: { value: '4-3-3' } })

    expect(screen.queryByText(/reposicionamento/)).not.toBeInTheDocument()
    expect(screen.getByText('1 ajuste tático')).toBeInTheDocument()
  })
})
```

Also update the existing test that pins the dialog's tab list, if any, to include `Posições`.

- [ ] **Step 5: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/match/MatchActionsDialog.test.tsx`
Expected: the new tests FAIL — no tab named `Posições`. Existing tests fail only on the missing `kit` prop type until the dialog is updated.

- [ ] **Step 6: Implement `PositionsTabContent.tsx`**

```tsx
import { useState } from 'react'
import { Box, NativeSelect, Typography } from '@mui/material'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { pitchSlots } from '../pitch/pitchSlots'
import { RoleContextMenu, type RoleMenuAnchor } from '../squad/RoleContextMenu'
import { ARCHETYPE_LABEL, ARCHETYPES_FOR_POSITION } from '../players/roleLabels'
import type { LiveKitColors, LivePlayer, RoleArchetype } from '../../api/types'
import type { RoleOverrides, SlotMap } from './liveArrangement'

/**
 * Posições: the managed side on the squad screen's own pitch editor. Drag swaps two players' slots; a
 * right-click or long-press opens the role menu. Everything is controlled — the dialog owns the
 * arrangement, so the footer can count it and the apply can send it.
 */
export function PositionsTabContent({ roster, formation, slots, roleOverrides, kit, onSwap, onRoleChange }: {
  roster: LivePlayer[]
  formation: string
  slots: SlotMap
  roleOverrides: RoleOverrides
  kit: LiveKitColors
  onSwap: (fromSlot: number, toSlot: number) => void
  onRoleChange: (playerId: string, archetype: RoleArchetype | null) => void
}) {
  const [roleMenu, setRoleMenu] = useState<RoleMenuAnchor | null>(null)

  const team: TeamOnPitch = {
    formation,
    kit,
    showNames: true,
    players: roster.map((p): PlacedPlayer => ({
      id: p.playerId,
      surname: p.name,
      position: p.position as PlacedPlayer['position'],
      detailedPosition: p.detailedPosition,
      altPositions: p.altPositions,
      fitness: p.fitness,
      slotIndex: slots[p.playerId],
      badges: p.hobbled ? { injured: true } : undefined,
    })),
  }

  const menuPlayer = roster.find(p => p.playerId === roleMenu?.playerId) ?? null
  const menuSlot = menuPlayer ? pitchSlots(formation)[slots[menuPlayer.playerId]] : undefined
  const archetypes = menuSlot ? ARCHETYPES_FOR_POSITION[menuSlot.accepted[0]] : []

  return (
    <Box sx={{ pt: 1 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 1 }}>
        Arraste um jogador para trocar de posição. Clique com o botão direito (ou segure) para escolher a função.
      </Typography>
      <Pitch
        home={team}
        onSwap={onSwap}
        onSlotContextMenu={(slotIndex, x, y) => {
          const playerId = Object.keys(slots).find(id => slots[id] === slotIndex)
          if (playerId) setRoleMenu({ playerId, x, y })
        }}
      />
      <RoleContextMenu
        anchor={menuPlayer ? roleMenu : null}
        playerName={menuPlayer?.name ?? ''}
        funcaoControl={menuPlayer ? (
          <NativeSelect
            fullWidth
            value={roleOverrides[menuPlayer.playerId] ?? ''}
            onChange={e => onRoleChange(
              menuPlayer.playerId,
              e.target.value === '' ? null : e.target.value as RoleArchetype,
            )}
            inputProps={{ 'aria-label': 'Função' }}
          >
            <option value="">Automática (melhor para a vaga)</option>
            {archetypes.map(a => <option key={a} value={a}>{ARCHETYPE_LABEL[a]}</option>)}
          </NativeSelect>
        ) : null}
        focoControl={<Typography variant="body2" sx={{ color: 'text.secondary' }}>Padrão da função</Typography>}
        onClose={() => setRoleMenu(null)}
      />
    </Box>
  )
}
```

- [ ] **Step 7: Wire the dialog**

In `MatchActionsDialog.tsx`:

1. Imports. Delete the local `export interface SubPair { … }` block and its section banner, then add:

```tsx
import type { LiveEvent, LiveKitColors, LivePlayer, LiveTactics, LiveTacticsRequest, Mentality, GameStyle, AttackZone, Pressing, Marking } from '../../api/types'
import { workingRoster, baselineArrangement, swapSlots, sameSlots, type SubPair, type SlotMap, type RoleOverrides } from './liveArrangement'
import { PositionsTabContent } from './PositionsTabContent'

export type { SubPair } from './liveArrangement'
```

2. Props: `initialTab: 0 | 1 | 2`, and add `/** The managed side's kit, for the Posições pitch. */ kit: LiveKitColors`. Destructure `kit`. `useState<0 | 1 | 2>(initialTab)`.

3. After the tactics state declarations:

```tsx
  // Posições state. Edits are remembered together with the formation and roster they were made on.
  // Changing the formation, or queueing/removing a substitution, changes what the edits refer to, so
  // they are discarded and the tab re-derives its baseline — never applied to a pitch they weren't made on.
  const [arrangementEdits, setArrangementEdits] = useState<{
    formation: string
    rosterKey: string
    slots: SlotMap
    roles: RoleOverrides
  } | null>(null)

  const roster = workingRoster(onPitch, queue)
  const rosterKey = roster.map(p => p.playerId).sort().join(',')
  const baseline = baselineArrangement(roster, formation, currentTactics.formation)
  const liveEdits = arrangementEdits !== null
    && arrangementEdits.formation === formation
    && arrangementEdits.rosterKey === rosterKey
    ? arrangementEdits
    : null
  const slots = liveEdits?.slots ?? baseline
  const roleOverrides = liveEdits?.roles ?? {}
  // Dragging two players and back again is not a change.
  const arrangementChanged = liveEdits !== null
    && (!sameSlots(liveEdits.slots, baseline) || Object.keys(liveEdits.roles).length > 0)

  function editArrangement(next: { slots?: SlotMap; roles?: RoleOverrides }) {
    setArrangementEdits({ formation, rosterKey, slots: next.slots ?? slots, roles: next.roles ?? roleOverrides })
  }

  function handleSwap(fromSlot: number, toSlot: number) {
    const nextSlots = swapSlots(slots, fromSlot, toSlot)
    // A moved player's hand-set role may not be valid in their new slot; they take the slot's natural
    // role instead (spec Decision 6), exactly as the server would.
    const roles = Object.fromEntries(
      Object.entries(roleOverrides).filter(([id]) => nextSlots[id] === slots[id]),
    )
    editArrangement({ slots: nextSlots, roles })
  }

  function handleRoleChange(playerId: string, archetype: RoleArchetype | null) {
    const roles = { ...roleOverrides }
    if (archetype === null) delete roles[playerId]
    else roles[playerId] = archetype
    editArrangement({ roles })
  }
```

Add `RoleArchetype` to the `../../api/types` type import.

4. Replace `changeCount`, `handleContinue` and the summary pieces:

```tsx
  const changeCount = queue.length + tacticsChangedCount + (arrangementChanged ? 1 : 0)

  function handleContinue() {
    const sendTactics = tacticsChangedCount > 0 || arrangementChanged
    const tactics: LiveTacticsRequest | null = sendTactics
      ? {
          mentality, gameStyle, attackZone, pressing, marking, formation,
          // Omitted rather than null when untouched, so a tactics-only apply sends exactly the payload it
          // always has. The server skips re-recording unchanged tactics when an arrangement rides along.
          ...(arrangementChanged ? { arrangement: { slotIndexes: slots, roles: roleOverrides } } : {}),
        }
      : null
    onApply({ subs: queue, tactics })
  }
```

After `tacticsSummary`: `const arrangementSummary = arrangementChanged ? '1 reposicionamento' : null`. Change the summary join to `[subsSummary, tacticsSummary, arrangementSummary].filter(Boolean).join(' · ')`.

5. Tabs: `onChange={(_, v: 0 | 1 | 2) => setActiveTab(v)}`, and after the tactics tab: `<Tab label="Posições" id="tab-positions" aria-controls="tabpanel-positions" />`.

6. After the tactics tab panel, inside `DialogContent`:

```tsx
        <Box
          role="tabpanel"
          id="tabpanel-positions"
          aria-labelledby="tab-positions"
          hidden={activeTab !== 2}
        >
          {activeTab === 2 && (
            <PositionsTabContent
              roster={roster}
              formation={formation}
              slots={slots}
              roleOverrides={roleOverrides}
              kit={kit}
              onSwap={handleSwap}
              onRoleChange={handleRoleChange}
            />
          )}
        </Box>
```

In `LiveMatchPage.tsx`, add to `<MatchActionsDialog …>`:

```tsx
        kit={state.managedIsHome ? state.homeClubColors : state.awayClubColors}
```

- [ ] **Step 8: Verify**

Run: `cd frontend && npx tsc -b; echo "TSC=$?" && npx vitest run && npm run lint; echo "LINT=$?"`
Expected: TSC=0, all tests PASS, LINT=0.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/match/liveArrangement.ts frontend/src/features/match/liveArrangement.test.ts frontend/src/features/match/PositionsTabContent.tsx frontend/src/features/match/MatchActionsDialog.tsx frontend/src/features/match/MatchActionsDialog.test.tsx frontend/src/features/match/LiveMatchPage.tsx
git commit -m "feat(match): rearrange the team on the pitch during a live match"
```

---

## Final verification (controller)

- Full backend suite, once, blocking: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test; echo "EXIT=$?"`. A failure only in `TransferServiceSellingIT` is the known flake.
- Full frontend: `cd frontend && npx tsc -b && npx vitest run && npm run lint`.
