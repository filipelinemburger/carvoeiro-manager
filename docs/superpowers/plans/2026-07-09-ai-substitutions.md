# AI Substitutions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The AI opponent in the user's live match gets a real bench and makes deterministic substitutions — replacing hobbled players, matching personnel to posture escalations, and freshening tired starters.

**Architecture:** A pure, RNG-free `AiSubstitutionBrain` (one decision per minute, priority injury > posture > fatigue) is evaluated in `LiveMatchEngine`'s loop right after the posture evaluation, mutating the AI `Side` exactly like `applySubs` does and emitting normal `SUBSTITUTION` events. `LineupService.bestBench` gives AI clubs a 7-man bench at kickoff (persisted in the existing bench columns — no migration). No new persisted state: AI subs replay identically on every re-simulation, like the tactics reactions.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 + AssertJ (backend only; no frontend changes).

**Spec:** `docs/superpowers/specs/2026-07-09-ai-substitutions-design.md`

## Global Constraints

- Gradle: run from `backend/` with `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew …`, foreground only, never two gradle invocations concurrently.
- Commit locally on `main` after each task. NEVER `git push`.
- `AiSubstitutionBrain` must be pure and RNG-free; the engine hook consumes zero RNG. Timelines stay pure functions of `LiveMatchConfig`. `MatchSimulationService`/`FixtureSimulator` (instant engine) untouched.
- Rules, verbatim from the spec: at most ONE sub per evaluated minute; priority injury > posture > fatigue; total AI subs ≤ 5; posture and fatigue subs only while `totalSubsMade < 4` (one held back for injuries); max one posture sub per match; max two fatigue subs; fatigue from minute ≥ 65 for a non-GK with kickoff fitness < 70; ALL_OUT swap requires more than 3 DEF on pitch and a bench ATT; PARK_THE_BUS swap requires more than 1 ATT on pitch and a bench DEF; injury cover = best same-`Position` bench player (hobbled GK only by a bench GK), scan hobbled players in on-pitch order and sub the FIRST with cover.
- Selection determinism: "best" = highest `overall`, ties broken by list order (first wins); "lowest" keeps the first on ties.
- Bench: best remaining eligible GK first, then best others by overall, cap 7 total; candidates = non-youth (`findByClubIdAndYouthProspectFalse`), `isEligible()`, not in the XI; short/empty bench is fine (no ineligible fallback). Empty AI bench (old saves) → zero AI subs, no errors.
- No UI/frontend changes; the feed already renders SUBSTITUTION events.

---

### Task 1: `AiSubstitutionBrain` (pure decision engine)

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/AiSubstitutionBrain.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/AiSubstitutionBrainTest.java`

**Interfaces:**
- Consumes: `player/Player` (`getId`, `getPosition`, `getOverall`), `player/Position`, `AiManagerBrain.Posture`.
- Produces (Task 3 relies on these exact signatures):
  - nested `public enum Kind { INJURY, POSTURE, FATIGUE }`
  - nested `public record SubDecision(UUID outId, UUID inId, Kind kind)`
  - `public static Optional<SubDecision> decide(int minute, AiManagerBrain.Posture posture, boolean postureSubMade, int fatigueSubsMade, int totalSubsMade, List<Player> onPitch, Set<UUID> hobbled, List<Player> benchAvailable, Map<UUID, Integer> kickoffFitness)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/carvoeiromanager/match/AiSubstitutionBrainTest.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.match.AiSubstitutionBrain.Kind;
import br.com.carvoeiromanager.match.AiSubstitutionBrain.SubDecision;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiSubstitutionBrainTest {

    private static Player p(Position pos, int overall) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), pos + "-" + overall, pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, overall);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, distinct overalls so selections are unambiguous. */
    private static List<Player> pitch() {
        return List.of(
                p(Position.GK, 70),
                p(Position.DEF, 68), p(Position.DEF, 69), p(Position.DEF, 71), p(Position.DEF, 72),
                p(Position.MID, 70), p(Position.MID, 71), p(Position.MID, 72), p(Position.MID, 73),
                p(Position.ATT, 74), p(Position.ATT, 75));
    }

    private static Map<UUID, Integer> fullFitness(List<Player> players) {
        return players.stream().collect(java.util.stream.Collectors.toMap(Player::getId, x -> 100));
    }

    private static Optional<SubDecision> decide(List<Player> onPitch, Set<UUID> hobbled,
                                                List<Player> bench, Map<UUID, Integer> fitness,
                                                int minute, Posture posture,
                                                boolean postureSubMade, int fatigueSubs, int totalSubs) {
        return AiSubstitutionBrain.decide(minute, posture, postureSubMade, fatigueSubs, totalSubs,
                onPitch, hobbled, bench, fitness);
    }

    // ---- injury rule ----

    @Test
    void aHobbledPlayerIsReplacedByTheBestSamePositionBenchPlayer() {
        List<Player> onPitch = pitch();
        Player hurt = onPitch.get(5);                      // MID 70
        Player weakMid = p(Position.MID, 60);
        Player strongMid = p(Position.MID, 66);
        Optional<SubDecision> d = decide(onPitch, Set.of(hurt.getId()),
                List.of(weakMid, strongMid), fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(hurt.getId(), strongMid.getId(), Kind.INJURY));
    }

    @Test
    void aHobbledKeeperIsOnlyReplacedByABenchKeeper() {
        List<Player> onPitch = pitch();
        Player gk = onPitch.get(0);
        // No bench GK -> no injury sub (and nothing else applies at minute 30, NORMAL).
        assertThat(decide(onPitch, Set.of(gk.getId()), List.of(p(Position.DEF, 80)),
                fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Bench GK -> replaced.
        Player benchGk = p(Position.GK, 60);
        assertThat(decide(onPitch, Set.of(gk.getId()), List.of(benchGk),
                fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0))
                .hasValue(new SubDecision(gk.getId(), benchGk.getId(), Kind.INJURY));
    }

    @Test
    void theFirstHobbledPlayerWithCoverWinsTheScan() {
        List<Player> onPitch = pitch();
        Player hurtDef = onPitch.get(1);                   // earlier in pitch order, no DEF on bench
        Player hurtAtt = onPitch.get(9);                   // later, but the only one with cover
        Player benchAtt = p(Position.ATT, 62);
        Optional<SubDecision> d = decide(onPitch, Set.of(hurtDef.getId(), hurtAtt.getId()),
                List.of(benchAtt), fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(hurtAtt.getId(), benchAtt.getId(), Kind.INJURY));
    }

    @Test
    void injurySubsAreAllowedUpToTheFifthSubButNotBeyond() {
        List<Player> onPitch = pitch();
        Player hurt = onPitch.get(5);
        List<Player> bench = List.of(p(Position.MID, 60));
        assertThat(decide(onPitch, Set.of(hurt.getId()), bench, fullFitness(onPitch),
                30, Posture.NORMAL, false, 0, 4)).isPresent();     // 5th sub OK for injuries
        assertThat(decide(onPitch, Set.of(hurt.getId()), bench, fullFitness(onPitch),
                30, Posture.NORMAL, false, 0, 5)).isEmpty();       // no 6th sub ever
    }

    // ---- posture rule ----

    @Test
    void allOutBringsTheBestBenchAttackerOnForTheWorstDefender() {
        List<Player> onPitch = pitch();                    // 4 DEF (> 3), worst DEF overall 68
        Player worstDef = onPitch.get(1);
        Player att1 = p(Position.ATT, 64);
        Player att2 = p(Position.ATT, 67);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(att1, att2),
                fullFitness(onPitch), 75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(worstDef.getId(), att2.getId(), Kind.POSTURE));
    }

    @Test
    void parkTheBusBringsADefenderOnForTheWorstAttacker() {
        List<Player> onPitch = pitch();                    // 2 ATT (> 1), worst ATT overall 74
        Player worstAtt = onPitch.get(9);
        Player benchDef = p(Position.DEF, 63);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(benchDef),
                fullFitness(onPitch), 80, Posture.PARK_THE_BUS, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(worstAtt.getId(), benchDef.getId(), Kind.POSTURE));
    }

    @Test
    void postureSubsRespectTheFloorsTheOncePerMatchRuleAndTheHeldBackSub() {
        // Floor: only 3 DEF on pitch -> no ALL_OUT swap.
        List<Player> threeDefPitch = List.of(
                p(Position.GK, 70),
                p(Position.DEF, 68), p(Position.DEF, 69), p(Position.DEF, 71),
                p(Position.MID, 70), p(Position.MID, 71), p(Position.MID, 72),
                p(Position.MID, 73), p(Position.MID, 74),
                p(Position.ATT, 74), p(Position.ATT, 75));
        List<Player> bench = List.of(p(Position.ATT, 64));
        assertThat(decide(threeDefPitch, Set.of(), bench, fullFitness(threeDefPitch),
                75, Posture.ALL_OUT, false, 0, 0)).isEmpty();
        // Once per match: postureSubMade blocks a second one.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.ALL_OUT, true, 0, 1)).isEmpty();
        // Held-back sub: 4 subs already made -> no posture sub.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.ALL_OUT, false, 0, 4)).isEmpty();
        // Non-extreme postures never produce a posture sub.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.PUSH, false, 0, 0)).isEmpty();
    }

    // ---- fatigue rule ----

    @Test
    void theMostTiredOutfielderIsFreshenedFromMinuteSixtyFive() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        Player tired = onPitch.get(6);                     // MID 71
        Player evenMoreTired = onPitch.get(7);             // MID 72 — lower fitness wins, not overall
        fitness.put(tired.getId(), 65);
        fitness.put(evenMoreTired.getId(), 55);
        Player benchMid = p(Position.MID, 61);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(benchMid), fitness,
                65, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(evenMoreTired.getId(), benchMid.getId(), Kind.FATIGUE));
    }

    @Test
    void fatigueRespectsMinuteThresholdCapAndGoalkeeper() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        fitness.put(onPitch.get(6).getId(), 55);
        List<Player> bench = List.of(p(Position.MID, 61));
        // Minute 64: too early.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 64, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Fitness exactly 70 is NOT tired.
        Map<UUID, Integer> borderline = fullFitness(onPitch);
        borderline.put(onPitch.get(6).getId(), 70);
        assertThat(decide(onPitch, Set.of(), bench, borderline, 70, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Two fatigue subs already made: capped.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 70, Posture.NORMAL, false, 2, 2)).isEmpty();
        // A tired GK is never fatigue-subbed.
        Map<UUID, Integer> tiredGk = fullFitness(onPitch);
        tiredGk.put(onPitch.get(0).getId(), 40);
        assertThat(decide(onPitch, Set.of(), List.of(p(Position.GK, 60)), tiredGk,
                70, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Held-back sub applies to fatigue too.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 70, Posture.NORMAL, false, 0, 4)).isEmpty();
    }

    // ---- priority & degenerate cases ----

    @Test
    void injuryBeatsPostureBeatsFatigueInTheSameMinute() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        Player tired = onPitch.get(6);
        fitness.put(tired.getId(), 50);
        Player hurt = onPitch.get(5);
        List<Player> bench = List.of(p(Position.MID, 60), p(Position.ATT, 64));
        // Hobbled + ALL_OUT + tired, all at once: injury wins.
        Optional<SubDecision> d = decide(onPitch, Set.of(hurt.getId()), bench, fitness,
                75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d.orElseThrow().kind()).isEqualTo(Kind.INJURY);
        // Same but nobody hobbled: posture wins over fatigue.
        Optional<SubDecision> d2 = decide(onPitch, Set.of(), bench, fitness,
                75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d2.orElseThrow().kind()).isEqualTo(Kind.POSTURE);
    }

    @Test
    void anEmptyBenchNeverProducesADecision() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        fitness.put(onPitch.get(6).getId(), 40);
        assertThat(decide(onPitch, Set.of(onPitch.get(5).getId()), List.of(), fitness,
                80, Posture.ALL_OUT, false, 0, 0)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.AiSubstitutionBrainTest"
```
Expected: compilation FAILURE — `AiSubstitutionBrain` does not exist.

- [ ] **Step 3: Implement AiSubstitutionBrain**

`backend/src/main/java/br/com/carvoeiromanager/match/AiSubstitutionBrain.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, RNG-free substitution brain for the AI side of a live match. Evaluated once
 * per minute by the engine; returns at most ONE decision, in strict priority order:
 * injury (replace a hobbled player) > posture (personnel for an extreme posture) > fatigue
 * (freshen a tired starter). Non-injury subs stop at four so one sub is always held back for
 * a late injury. All selection is deterministic: "best" = highest overall, ties broken by
 * list order; re-simulations therefore replay identical substitutions.
 */
public final class AiSubstitutionBrain {

    /** Why a substitution was made — the engine uses this to bump the right counters. */
    public enum Kind { INJURY, POSTURE, FATIGUE }

    public record SubDecision(UUID outId, UUID inId, Kind kind) { }

    static final int MAX_SUBS = 5;
    private static final int NON_INJURY_SUB_LIMIT = 4;   // one sub always held back for injuries
    private static final int FATIGUE_SUB_LIMIT = 2;
    private static final int FATIGUE_FROM_MINUTE = 65;
    private static final int FATIGUE_THRESHOLD = 70;     // kickoff fitness below this = tired
    private static final int MIN_DEF_ON_PITCH = 3;
    private static final int MIN_ATT_ON_PITCH = 1;

    private AiSubstitutionBrain() {
    }

    public static Optional<SubDecision> decide(
            int minute,
            AiManagerBrain.Posture posture,
            boolean postureSubMade,
            int fatigueSubsMade,
            int totalSubsMade,
            List<Player> onPitch,
            Set<UUID> hobbled,
            List<Player> benchAvailable,
            Map<UUID, Integer> kickoffFitness) {

        if (benchAvailable.isEmpty() || totalSubsMade >= MAX_SUBS) return Optional.empty();

        // 1. Injury: the first hobbled on-pitch player (pitch order) that has positional cover.
        for (Player out : onPitch) {
            if (!hobbled.contains(out.getId())) continue;
            Optional<Player> cover = bestByPosition(benchAvailable, out.getPosition());
            if (cover.isPresent()) {
                return Optional.of(new SubDecision(out.getId(), cover.get().getId(), Kind.INJURY));
            }
        }

        if (totalSubsMade >= NON_INJURY_SUB_LIMIT) return Optional.empty();

        // 2. Posture: once per match, swap personnel to match an extreme posture.
        if (!postureSubMade) {
            if (posture == AiManagerBrain.Posture.ALL_OUT) {
                Optional<SubDecision> swap =
                        postureSwap(onPitch, benchAvailable, Position.DEF, MIN_DEF_ON_PITCH, Position.ATT);
                if (swap.isPresent()) return swap;
            } else if (posture == AiManagerBrain.Posture.PARK_THE_BUS) {
                Optional<SubDecision> swap =
                        postureSwap(onPitch, benchAvailable, Position.ATT, MIN_ATT_ON_PITCH, Position.DEF);
                if (swap.isPresent()) return swap;
            }
        }

        // 3. Fatigue: freshen the most tired outfield starter from minute 65.
        if (minute >= FATIGUE_FROM_MINUTE && fatigueSubsMade < FATIGUE_SUB_LIMIT) {
            Player tired = null;
            for (Player p : onPitch) {
                if (p.getPosition() == Position.GK) continue;
                int fit = kickoffFitness.getOrDefault(p.getId(), 100);
                if (fit >= FATIGUE_THRESHOLD) continue;
                if (tired == null || fit < kickoffFitness.getOrDefault(tired.getId(), 100)) tired = p;
            }
            if (tired != null) {
                Optional<Player> fresh = bestByPosition(benchAvailable, tired.getPosition());
                if (fresh.isPresent()) {
                    return Optional.of(new SubDecision(tired.getId(), fresh.get().getId(), Kind.FATIGUE));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Take off the lowest-overall on-pitch {@code outPosition} player for the best bench
     * {@code inPosition} player. Allowed only while the current on-pitch count of the outgoing
     * position strictly exceeds {@code minOutPositionOnPitch} (the floor is never crossed).
     */
    private static Optional<SubDecision> postureSwap(List<Player> onPitch, List<Player> bench,
                                                     Position outPosition, int minOutPositionOnPitch,
                                                     Position inPosition) {
        long count = onPitch.stream().filter(p -> p.getPosition() == outPosition).count();
        if (count <= minOutPositionOnPitch) return Optional.empty();
        Optional<Player> in = bestByPosition(bench, inPosition);
        if (in.isEmpty()) return Optional.empty();
        Player out = null;
        for (Player p : onPitch) {
            if (p.getPosition() != outPosition) continue;
            if (out == null || p.getOverall() < out.getOverall()) out = p;
        }
        return Optional.of(new SubDecision(out.getId(), in.get().getId(), Kind.POSTURE));
    }

    /** Highest-overall bench player of the given position; ties keep the first in bench order. */
    private static Optional<Player> bestByPosition(List<Player> bench, Position position) {
        Player best = null;
        for (Player p : bench) {
            if (p.getPosition() != position) continue;
            if (best == null || p.getOverall() > best.getOverall()) best = p;
        }
        return Optional.ofNullable(best);
    }
}
```
(Note: fix the javadoc on `postureSwap` while transcribing — drop the trailing "…i.e." duplication and keep one clear sentence: "allowed only while the current count strictly exceeds the floor".)

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.AiSubstitutionBrainTest"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/AiSubstitutionBrain.java backend/src/test/java/br/com/carvoeiromanager/match/AiSubstitutionBrainTest.java
git commit -m "feat(match): AiSubstitutionBrain — deterministic AI sub decisions"
```

---

### Task 2: AI bench — `bestBench` + kickoff wiring

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LineupService.java` (new `bestBench`, near `bestXi` ~line 236)
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java` (kickoff bench lines, ~120-122)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LineupServiceIT.java` (or the file where `bestXi` is tested — follow the existing location), `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceKickoffIT.java`

**Interfaces:**
- Consumes: `playerRepository.findByClubIdAndYouthProspectFalse(clubId)`, `Player.isEligible()`, `Player.getOverall()`, `Position.GK`.
- Produces (Task 3's tests rely on real benches existing): `public List<Player> bestBench(UUID clubId, List<Player> xi)` on `LineupService`.

- [ ] **Step 1: Write the failing tests**

1. In the file that already tests `LineupService`'s XI resolution (find where `bestXi`/`startingXi` is covered — likely `LineupServiceIT.java`; follow its career/club fixture conventions), add:

```java
    @Test
    void bestBenchPicksTheBestRemainingPlayersWithAKeeperFirst() {
        // Given an AI club's XI (lineupService.startingXi for a club with no saved lineup),
        // bestBench(clubId, xi): size <= 7; contains NO player from the XI; contains exactly
        // one GK when the squad has a spare eligible GK, and that GK is the best remaining one;
        // the non-GK slots are the highest-overall remaining eligible non-youth players.
    }

    @Test
    void bestBenchExcludesYouthAndIneligiblePlayersAndCanBeShort() {
        // Squad manipulated so only 2 eligible non-youth non-XI candidates exist ->
        // bench has exactly those 2. Youth prospects and ineligible players never appear.
    }
```
Write these as real assertions using the file's existing helpers (career creation gives AI
clubs full squads; use `playerRepository` to mark players youth/ineligible where the scenario
needs it, following how neighboring tests manipulate players).

2. In `LiveMatchServiceKickoffIT.java` (follow its existing kickoff fixture), add:

```java
    @Test
    void kickoffGivesTheAiSideABench() {
        // After kickoff: the NON-managed side's persisted bench ids are non-empty (<= 7),
        // resolve to players of the AI club, and include a GK when the AI squad has a spare.
        // The managed side's bench behavior is unchanged.
    }
```
Write as real assertions (read the persisted LiveMatch via its repository, resolve ids
through playerRepository).

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LineupServiceIT" --tests "br.com.carvoeiromanager.match.LiveMatchServiceKickoffIT"
```
Expected: compilation FAILURE (`bestBench` does not exist) / kickoff bench assertion fails.

- [ ] **Step 3: Implement**

3a. `LineupService.java` — next to `bestXi`:

```java
    private static final int BENCH_SIZE = 7;

    /**
     * Best available bench for a club without a saved lineup (AI clubs): the best remaining
     * eligible GK first (injury cover for the keeper), then the best other eligible non-youth
     * players by overall, up to {@value #BENCH_SIZE} total. Players already in the XI are
     * excluded. Unlike {@link #bestXi}, there is no ineligible fallback — a short (even empty)
     * bench is fine.
     */
    public List<Player> bestBench(UUID clubId, List<Player> xi) {
        Set<UUID> xiIds = xi.stream().map(Player::getId).collect(Collectors.toSet());
        List<Player> candidates = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .filter(Player::isEligible)
                .filter(p -> !xiIds.contains(p.getId()))
                .toList();

        List<Player> bench = new ArrayList<>();
        candidates.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall))
                .ifPresent(bench::add);
        candidates.stream()
                .filter(p -> bench.stream().noneMatch(b -> b.getId().equals(p.getId())))
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .limit(BENCH_SIZE - bench.size())
                .forEach(bench::add);
        return bench;
    }
```
(add imports `Set`/`Collectors` if missing; the file already imports `Comparator`, `ArrayList`, `Position`.)

3b. `LiveMatchService.kickoff` — the two bench lines become:

```java
        List<Player> homeBench = managedIsHome ? lineupService.benchPlayers(saveGameId, homeClubId)
                : lineupService.bestBench(homeClubId, homeXi);
        List<Player> awayBench = managedIsHome ? lineupService.bestBench(awayClubId, awayXi)
                : lineupService.benchPlayers(saveGameId, awayClubId);
```

- [ ] **Step 4: Run the tests, then the match package**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL. (Live-match ITs now run with an AI bench present but nothing uses
it yet — no behavior change until Task 3.)

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(match): AI clubs get a real bench at live-match kickoff"
```

---

### Task 3: Engine integration — the AI makes substitutions

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java` (Side counters, hook after `reactAiManager`, `maybeAiSubstitution`)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java` (extend)

**Interfaces:**
- Consumes (Tasks 1–2): `AiSubstitutionBrain.decide(...)` / `SubDecision(outId, inId, kind)` / `Kind`; real AI benches arrive via `LiveMatchConfig` benches (engine already adds bench players to `Side.all`).
- Produces: AI-side `SUBSTITUTION` timeline events; no API/wire changes.

- [ ] **Step 1: Write the failing tests**

In `LiveMatchEngineTest.java` (reuse its `config(...)` factories; give the away side a bench by
building configs directly where the shared factory passes `List.of()` — follow the pattern of
the existing AI-reaction tests that build custom configs):

```java
    @Test
    void aHobbledAiPlayerWithCoverIsSubstituted() {
        // Away side is the AI (aiHome = false) with a bench containing cover for every
        // position. Sweep seeds 0..300; for every away INJURY event at minute m where the away
        // side still has subs left and bench cover for the victim's position, assert an away
        // SUBSTITUTION with playerId == victim exists at some minute > m, and the victim's
        // minutesPlayed ends at that sub minute.
    }

    @Test
    void aiSubsNeverExceedTheCapAndOnlyBenchPlayersComeOn() {
        // Sweep seeds 0..300 (AI bench present): away SUBSTITUTION events count <= 5 per
        // match; every SUBSTITUTION's playerInId is a bench player id and never appears as
        // playerInId twice in one match.
    }

    @Test
    void anEmptyAiBenchMeansNoAiSubstitutions() {
        // aiHome = false, away bench = List.of() (the old-save shape): sweep seeds 0..50 and
        // assert zero away-side SUBSTITUTION events (user schedule is empty in these configs).
    }

    @Test
    void aiSubstitutionsAreDeterministic() {
        // One config with AI bench: simulate twice, timelines identical (events + minutes).
    }

    @Test
    void managedScheduleSubsAndAiSubsCoexist() {
        // Home (managed) has a scheduled LiveSub at minute 60; away is AI with a bench.
        // The home SUBSTITUTION appears exactly as scheduled; away subs (if any) are all
        // away-side events; home never gets a brain-produced sub (no home SUBSTITUTION other
        // than the scheduled one).
    }
```
Write these as REAL tests. For the injury test, derive expectations from the timeline the way
the AI-reaction oracle test (`aiReactionsMatchTheBrainAppliedToTheRunningScore`) does — but
keep it structural (victim is eventually subbed) rather than re-running the full brain: the
precise decision logic is already unit-tested in Task 1. Build the away bench with
position-diverse players via the file's `p(Position, rating)` helper (e.g. 1 GK + 2 DEF +
2 MID + 2 ATT, rated 65).

- [ ] **Step 2: Run to verify failure**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.LiveMatchEngineTest"
```
Expected: FAIL — no AI substitutions are produced yet.

- [ ] **Step 3: Implement the engine hook**

3a. `Side` gains three fields (below `posture`):

```java
        int aiSubsMade = 0;                                // engine-made AI subs this match
        int aiFatigueSubsMade = 0;
        boolean aiPostureSubMade = false;
```

3b. In the minute loop, extend the existing AI block so subs are evaluated right after the
posture reaction:

```java
            if (aiSide != null) {
                int scoreDiff = aiSide.home ? homeGoals - awayGoals : awayGoals - homeGoals;
                reactAiManager(minute, aiSide, aiIdentity, scoreDiff, events);
                maybeAiSubstitution(minute, aiSide, cfg, events);
            }
```

3c. New private method (place after `applySubs`; imports: `java.util.Set`, `java.util.LinkedHashSet`, `java.util.Optional`):

```java
    /**
     * AI bench decision, evaluated once per minute right after the posture evaluation (so a
     * posture escalation and its personnel change land in the same minute). Deterministic and
     * RNG-free: reads only pitch/bench/fitness state, so re-simulations replay identical subs.
     * Applies at most one substitution per minute with the same bookkeeping as
     * {@link #applySubs}; an empty bench (pre-feature saves) means no decisions, ever.
     */
    private void maybeAiSubstitution(int minute, Side ai, LiveMatchConfig cfg,
                                     List<LiveTimelineEvent> events) {
        List<Player> benchAvailable = (ai.home ? cfg.homeBench() : cfg.awayBench()).stream()
                .filter(p -> !ai.entryMinute.containsKey(p.getId()))
                .toList();
        if (benchAvailable.isEmpty()) return;

        List<Player> onPitch = ai.onPitch.stream().map(ai.all::get).toList();
        Set<UUID> hobbledIds = new LinkedHashSet<>();
        for (UUID id : ai.onPitch) {
            if (Boolean.TRUE.equals(ai.hobbled.get(id))) hobbledIds.add(id);
        }

        Optional<AiSubstitutionBrain.SubDecision> decision = AiSubstitutionBrain.decide(
                minute, ai.posture, ai.aiPostureSubMade, ai.aiFatigueSubsMade, ai.aiSubsMade,
                onPitch, hobbledIds, benchAvailable, cfg.fitness());
        if (decision.isEmpty()) return;

        AiSubstitutionBrain.SubDecision d = decision.get();
        int idx = ai.onPitch.indexOf(d.outId());
        ai.onPitch.set(idx, d.inId());
        ai.exitMinute.put(d.outId(), minute);
        ai.entryMinute.put(d.inId(), minute);
        ai.dirty = true;
        ai.aiSubsMade++;
        switch (d.kind()) {
            case POSTURE -> ai.aiPostureSubMade = true;
            case FATIGUE -> ai.aiFatigueSubsMade++;
            case INJURY -> { }
        }
        events.add(new LiveTimelineEvent(minute, EventType.SUBSTITUTION, ai.home,
                d.outId(), d.inId(), null, null));
    }
```

3d. Update the class javadoc's determinism line to mention AI subs alongside AI tactics
reactions (both derive purely from the config).

- [ ] **Step 4: Run targeted tests, the match package, then the FULL backend suite**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests "br.com.carvoeiromanager.match.*"
```
Expected: BUILD SUCCESSFUL. Note: live-match ITs now run with AI benches AND AI subs active
(Task 2 wired the bench); if an IT assumed "all SUBSTITUTION events are the user's", fix the
TEST to filter by side — the production behavior is the feature. Then:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test
```
Expected: BUILD SUCCESSFUL (~545+ tests). Frontend is untouched — no frontend run needed.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(match): AI substitutions — injuries, posture and fatigue drive the AI bench"
```

---

## Verification (end-to-end sanity, after all tasks)

Play a live match and injure-bait the opponent (or just watch): when an AI player goes down,
a "Substituição: entra X, sai Y." feed entry for the away side should follow within a minute;
late in a match the AI freshens tired legs; if it goes ALL_OUT chasing, expect a striker on
for a defender right after the "Tudo ou nada" tactics line. Your own sub flow (batch dialog,
subsRemaining) is unchanged.
