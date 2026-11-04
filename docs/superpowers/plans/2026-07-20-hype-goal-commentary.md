# Hype Goal Commentary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Append a hype signature line after a managed-club goal when either the club enters the match on a hot streak (3 wins in a row, or unbeaten over 5), or that specific goal pushes the in-match lead to 3+ goals — shown at most once per match, on the first goal that qualifies.

**Architecture:** Extends the existing `comebackGoal` append mechanism in `LiveCommentary.of(...)` with a new `hypeGoal` boolean parameter and its own signature line, appended independently (both can append to the same goal). `LiveMatchService` gains two new static, unit-testable helpers — `isHypeStreak` (sibling to the existing `isLosingStreak`) and `isHypeGoal` (sibling to the existing `isComebackGoal`) — plus a small refactor extracting the fixture-history query that `losingStreakHalfTime` already has, so the new streak check can reuse it instead of duplicating it.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), JUnit 5 + AssertJ. No frontend changes — `LiveEvent.text` already renders arbitrary-length strings verbatim.

**Spec:** `docs/superpowers/specs/2026-07-20-hype-goal-commentary-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference, this project only).
- Local `git commit` is allowed and expected on this project — each task ends by committing its own tested work. `git push` is never run without an explicit user ask.
- The hype line text, verbatim: `Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!`
- Streak condition: managed club's most recent 3 completed fixtures all wins, OR most recent 5 completed fixtures include no loss (draws don't break it). Either qualifies; evaluated once per match from fixture history *before* the match (same fixtures `losingStreakHalfTime` already reads).
- Margin condition: evaluated per goal — the goal being scored brings `managedGoals - opponentGoals` to `>= 3` in this match.
- The line shows **once per match**: on the first managed goal (chronologically) that satisfies either condition. Later qualifying goals in the same match get normal commentary only.
- If a goal satisfies both the hype condition and the existing comeback condition, both signature lines append, back to back, after the normal goal text — comeback line first, then hype line. They are independent and never suppress each other.
- Applies only to the managed club (`SaveGame.getManagedClubId()`), the same way `comebackGoal`/`losingStreakHalfTime` already work — never the opponent.
- **Deviation from the spec, flagged for visibility:** the spec proposed a new `LiveMatchHypeStreakIT` integration test mirroring `LiveMatchLosingStreakIT`. On inspection, that's not safely reproducible: unlike the half-time-override feature (which renders regardless of score), the hype line only ever appears on an actual goal event, and the live match engine's goal outcomes are simulated probabilistically through the public `kickoff`/`advance` API — there's no way to force a goal to guarantee the assertion has something to check, and looping to full-time without a scoring guarantee risks a flaky or vacuously-passing test. The structurally identical existing feature, `comebackGoal`, has exactly this same constraint and ships with **zero** integration tests — only unit tests on `isComebackGoal` plus `LiveCommentaryTest` append coverage. Task 2 below follows that same, already-proven testing tier instead: unit tests on `isHypeStreak`/`isHypeGoal` (Task 1) and on the `LiveCommentary` append behavior (Task 2), no new IT test file.
- Working dir for all commands: repo root `<repo-root>` unless stated. Backend test commands: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` — use a long timeout (600000ms).

---

### Task 1: `LiveMatchService` streak and margin helpers

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java`

**Interfaces:**
- Consumes: nothing new — `Fixture` (`getHomeClubId()`, `getAwayClubId()`, `getHomeGoals()`, `getAwayGoals()`, `isPlayed()`), `LiveTimelineEvent` (`minute()`, `type()`, `home()`), `EventType.isGoal()` — all pre-existing.
- Produces: `static boolean isHypeStreak(List<Fixture> mostRecentFirst, UUID managedClubId)` and `static boolean isHypeGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome, boolean enteringMatchOnStreak)`, both consumed by Task 2's wiring into `toState()`. This task does **not** touch `toState()` or `LiveCommentary` — it only adds standalone, unit-tested helpers, so the build stays green and fully working throughout.

- [ ] **Step 1: Write the failing unit tests**

In `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java`, all needed imports (`Fixture`, `List`, `UUID`, `assertThat`) are already present. Add these test methods at the end of the class, immediately before the closing `}` (currently line 83):

```java
    @Test
    void threeWinsInARowTriggersTheHypeStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win2.recordResult(0, 1);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win3.recordResult(3, 1);

        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2, win3), managedClubId)).isTrue();
    }

    @Test
    void aDrawMixedIntoTheLastThreeBreaksTheThreeWinCheckButNotUnbeaten() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture draw = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        draw.recordResult(1, 1);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        win2.recordResult(1, 0);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win3.recordResult(0, 2);
        Fixture win4 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win4.recordResult(1, 0);

        // Last 3 are win1/draw/win2 in most-recent-first order below — not 3 straight wins —
        // but the last 5 (win1, draw, win2, win3, win4) have no loss, so unbeaten-over-5 still holds.
        assertThat(LiveMatchService.isHypeStreak(List.of(win1, draw, win2, win3, win4), managedClubId)).isTrue();
    }

    @Test
    void aLossInsideTheTopThreeBreaksBothChecks() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 5, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 4, opponentId, managedClubId, null);
        win2.recordResult(0, 1);
        Fixture loss = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 3, managedClubId, opponentId, null);
        loss.recordResult(0, 2);
        Fixture win3 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, opponentId, managedClubId, null);
        win3.recordResult(0, 1);
        Fixture win4 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, managedClubId, opponentId, null);
        win4.recordResult(1, 0);

        // Top 3 (win1, win2, loss) breaks the 3-win check; all 5 (win1, win2, loss, win3, win4)
        // contain a loss, breaking the unbeaten-5 check too — both legs must fail.
        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2, loss, win3, win4), managedClubId)).isFalse();
    }

    @Test
    void fewerThanThreePlayedFixturesNeverTriggersTheHypeStreak() {
        UUID managedClubId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        Fixture win1 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 2, managedClubId, opponentId, null);
        win1.recordResult(2, 0);
        Fixture win2 = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, opponentId, managedClubId, null);
        win2.recordResult(0, 1);

        assertThat(LiveMatchService.isHypeStreak(List.of(win1, win2), managedClubId)).isFalse();
    }

    @Test
    void aGoalThatReachesAThreeGoalMarginIsAHypeGoal() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        LiveTimelineEvent m3 = goal(50, true);
        List<LiveTimelineEvent> events = List.of(m1, m2, m3);

        assertThat(LiveMatchService.isHypeGoal(events, m3, true, false)).isTrue();
    }

    @Test
    void aGoalThatExtendsAnAlreadyThreeGoalMarginIsNotAHypeGoalAgain() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        LiveTimelineEvent m3 = goal(50, true);
        LiveTimelineEvent m4 = goal(70, true);
        List<LiveTimelineEvent> events = List.of(m1, m2, m3, m4);

        assertThat(LiveMatchService.isHypeGoal(events, m4, true, false)).isFalse();
    }

    @Test
    void enteringOnAStreakMakesTheFirstManagedGoalAHypeGoalRegardlessOfMargin() {
        LiveTimelineEvent m1 = goal(10, true);
        List<LiveTimelineEvent> events = List.of(m1);

        assertThat(LiveMatchService.isHypeGoal(events, m1, true, true)).isTrue();
    }

    @Test
    void enteringOnAStreakOnlyTheFirstManagedGoalIsAHypeGoal() {
        LiveTimelineEvent m1 = goal(10, true);
        LiveTimelineEvent m2 = goal(30, true);
        List<LiveTimelineEvent> events = List.of(m1, m2);

        assertThat(LiveMatchService.isHypeGoal(events, m2, true, true)).isFalse();
    }

    @Test
    void anOpponentGoalIsNeverAHypeGoal() {
        LiveTimelineEvent opponentGoal = goal(10, false);
        List<LiveTimelineEvent> events = List.of(opponentGoal);

        assertThat(LiveMatchService.isHypeGoal(events, opponentGoal, true, true)).isFalse();
    }
```

- [ ] **Step 2: Run the tests to verify they fail for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest'`

Expected: compile FAILURE — `isHypeStreak` and `isHypeGoal` do not exist on `LiveMatchService` yet.

- [ ] **Step 3: Implement the helpers**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
    /** True only when {@code managedClubId}'s two most recent completed fixtures were both losses. */
    static boolean isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        if (mostRecentFirst.size() < 2) {
            return false;
        }
        return mostRecentFirst.stream().limit(2).allMatch(f -> lostBy(f, managedClubId));
    }

    private static boolean lostBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals < opponentGoals;
    }

    private boolean losingStreakHalfTime(UUID saveGameId, UUID managedClubId) {
        // nullsFirst guards against a null matchDate (unscheduled/test fixtures) — without it,
        // Comparator.comparing's natural ordering throws NPE the moment two such fixtures are compared.
        List<Fixture> recent = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .sorted(java.util.Comparator.comparing(Fixture::getMatchDate,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(Fixture::getRound).reversed())
                .toList();
        return isLosingStreak(recent, managedClubId);
    }
```

with:

```java
    /** True only when {@code managedClubId}'s two most recent completed fixtures were both losses. */
    static boolean isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        if (mostRecentFirst.size() < 2) {
            return false;
        }
        return mostRecentFirst.stream().limit(2).allMatch(f -> lostBy(f, managedClubId));
    }

    /**
     * True when {@code managedClubId} enters the match on a hot streak: its 3 most recent
     * completed fixtures were all wins, or its 5 most recent completed fixtures included no loss
     * (a draw inside the last 5 doesn't break the unbeaten check, only the 3-win check).
     */
    static boolean isHypeStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        boolean threeWins = mostRecentFirst.size() >= 3
                && mostRecentFirst.stream().limit(3).allMatch(f -> wonBy(f, managedClubId));
        boolean fiveUnbeaten = mostRecentFirst.size() >= 5
                && mostRecentFirst.stream().limit(5).noneMatch(f -> lostBy(f, managedClubId));
        return threeWins || fiveUnbeaten;
    }

    private static boolean lostBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals < opponentGoals;
    }

    private static boolean wonBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals > opponentGoals;
    }

    private boolean losingStreakHalfTime(UUID saveGameId, UUID managedClubId) {
        return isLosingStreak(recentCompletedFixtures(saveGameId, managedClubId), managedClubId);
    }

    private boolean hypeStreak(UUID saveGameId, UUID managedClubId) {
        return isHypeStreak(recentCompletedFixtures(saveGameId, managedClubId), managedClubId);
    }

    /**
     * {@code managedClubId}'s completed fixtures involving it, most recent first. nullsFirst
     * guards against a null matchDate (unscheduled/test fixtures) — without it,
     * Comparator.comparing's natural ordering throws NPE the moment two such fixtures are compared.
     */
    private List<Fixture> recentCompletedFixtures(UUID saveGameId, UUID managedClubId) {
        return fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .sorted(java.util.Comparator.comparing(Fixture::getMatchDate,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(Fixture::getRound).reversed())
                .toList();
    }
```

Then, in the same file, replace:

```java
    /**
     * True only when {@code event} is a goal for the managed side that erases a deficit — the
     * managed club's goals strictly trailed the opponent's immediately before this event.
     */
    static boolean isComebackGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                } else {
                    opponentGoals++;
                }
            }
        }
        return managedGoals < opponentGoals;
    }
```

with:

```java
    /**
     * True only when {@code event} is a goal for the managed side that erases a deficit — the
     * managed club's goals strictly trailed the opponent's immediately before this event.
     */
    static boolean isComebackGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                } else {
                    opponentGoals++;
                }
            }
        }
        return managedGoals < opponentGoals;
    }

    /**
     * True only when {@code event} is the first managed-side goal in {@code events} to either
     * (a) arrive with the managed club already on a hot streak entering the match
     * ({@code enteringMatchOnStreak}), or (b) push the managed club's in-match lead to 3 or more
     * goals. Once an earlier managed goal has already qualified, later goals in the same match —
     * even ones that would also qualify — return false, so the signature line shows at most once
     * per match.
     */
    static boolean isHypeGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome,
                               boolean enteringMatchOnStreak) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                    if (enteringMatchOnStreak || managedGoals - opponentGoals >= 3) {
                        return false;
                    }
                } else {
                    opponentGoals++;
                }
            }
        }
        managedGoals++;
        return enteringMatchOnStreak || managedGoals - opponentGoals >= 3;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest'`

Expected: BUILD SUCCESSFUL, all tests pass (existing `isComebackGoal`/`isLosingStreak` tests plus the 8 new ones).

- [ ] **Step 5: Run the full backend suite to confirm nothing else broke**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`

Expected: BUILD SUCCESSFUL. `losingStreakHalfTime` now calls the extracted `recentCompletedFixtures` helper instead of its old inline query — behavior is identical, so `LiveMatchLosingStreakIT` must still pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchServiceCommentaryTest.java
git commit -m "$(cat <<'EOF'
feat(match): add isHypeStreak/isHypeGoal detection helpers

Sibling helpers to the existing isLosingStreak/isComebackGoal: a win
streak (3 in a row, or unbeaten over 5) computed from fixture history,
and a per-goal check for the first managed goal that either arrives on
that streak or pushes the in-match lead to 3+. Not yet wired into
toState() or LiveCommentary — that lands with the signature change in
the next commit so the build never sits in a broken intermediate state.
EOF
)"
```

---

### Task 2: Wire the hype line into `LiveCommentary` and `LiveMatchService`

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`
- Modify: `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`

**Interfaces:**
- Consumes: `LiveMatchService.isHypeStreak`, `LiveMatchService.isHypeGoal` (Task 1).
- Produces: `LiveCommentary.of(EventType, String, String, int, String, boolean, boolean, boolean)` — the new 8-arg signature (inserts `hypeGoal` between `comebackGoal` and `losingStreakHalfTime`). This is the only production call site of `LiveCommentary.of(...)` in the codebase, so this task must update `LiveCommentary.java`, every call in `LiveCommentaryTest.java`, and the one call in `LiveMatchService.toState()` together — splitting them would leave the project in a non-compiling state between commits.

- [ ] **Step 1: Update the test file to the new signature and add hype-line tests**

Replace the full contents of `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java` with:

```java
package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    // Variant index = minute + type.ordinal() mod array length; every assertion below checks
    // membership in the full current pool rather than a fixed index or exact string, so
    // reordering or rewording variants (an ongoing content pass) never breaks these tests.

    @Test
    void userTacticsChangeKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null, false, false, false)).isIn(
                "O treinador mexe no time, vamos ver o que muda em campo!",
                "Mudança tática do treinador. Vamo ver se essa mudança pega bem",
                "Ajuste na estratégia — o comandante não fica parado no banco, hein!");
    }

    @Test
    void aiTacticsChangeGetsPostureSpecificPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PUSH", false, false, false)).isIn(
                "Eles sobem as linhas e vão pra cima. Segura aí, professor!",
                "O adversário se lança ao ataque. Agora esse relógio tem que passar voando",
                "Postura ofensiva do adversário, que quer pressionar lá na frente!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "ALL_OUT", false, false, false)).isIn(
                "É tudo ou nada agora! O adversário joga por tudo! Essa é hora de apostar no nosso contra-ataque!",
                "Tudo ou nada do adversário: 4-2-4 ofensivo!",
                "Sem medo de perder: o rival lança todo mundo no ataque! Te segura aí, Tigre!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PROTECT", false, false, false)).isIn(
                "O rival se fecha e tenta administrar a vantagem, temos que achar um jeito de penetrar nessa retranca e marcar um gol logo",
                "O adversário recua para segurar o resultado. Hora de avançarmos as linhas e pressioná-los no campo de defesa",
                "Eles priorizam a marcação para não sofrer o empate, mas nós vamos com tudo!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PARK_THE_BUS", false, false, false)).isIn(
                "Ônibus estacionado! Eles agora só pensam em defender! Tem que furar essa barreira",
                "Adversário fecha-se na defesa: 5-4-1. Tem que encurralar os caras, vamo pra cima!",
                "Ninguém passa! O rival montou uma muralha lá atrás! Bem coisa de time pequeno!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "NORMAL", false, false, false)).isIn(
                "O comandante rival ajusta as peças no tabuleiro!",
                "O adversário reorganiza a equipe. Postura mais equilibrada agora",
                "Reorganização no adversário, buscando equilíbrio dentro de campo.");
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null, false, false, false)).isIn(
                "Substituição! Entra Entra, sai Sai. Vamo que vamo!",
                "Troca no time: entra Entra no lugar de Sai. Fôlego novo pra reta final!",
                "Mudança do treinador: Entra entra, Sai sai de campo aplaudido, bem merecidamente!",
                "Aí vem a mexida! Entra pisa no gramado no lugar de Sai.");
    }

    @Test
    void penaltyGoalMissAndFreeKickLines() {
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "Pênalti! Otero converte com categoria!",
                "É PÊÊÊNAAALTII!!! Otero bate... É GOOOOOOOOOOOLLLL!",
                "É GOOOOOOOOOOOLLLL! Que cobrança maravilhooosaaa! Bateu forte e com gosto, Otero não erra da marca da cal!",
                "Vai pra bola... É GOOOOOOOOOLLLLLL!!! Goleiro pra um lado e bola para o outro, Otero converte de pênalti!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null, false, false, false)).isIn(
                "É PÊNALTI... Otero vai para a marca da cal eeee.... PERDEEEUUU! O que é isso meu filho? Essa aí tu mandou lá no Maracajá!",
                "Otero vai pra cobrança do pênalti eeeee... perdeeeeeuuuu! O goleiro foi buscar a bola lá na Seis de Janeiro!",
                "É PÊÊÊNAAALTII!!! Que sufoco... Otero bateu em cima do goleiro e perdeu a chance de ouro! Esse aí não serve nem pra jogar o praião",
                "É PÊÊÊNAAALTII!!! É agora, correu pra bola eee.... PERDEEEEEEEUUUU! O que é isso? Otero pelo amor de Deus, assim você não merece aumento");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "Falta perigosa, Otero na bola... bateeeeeeuuuu.... GOLAAAAAAÇOOOOO!!! GOOOOOOOOOOOOLLL! A bola foi por cima da barreira e morreu no fundo do gol!",
                "Mais uma falta perigosa, Otero correu... bateeeu... JOOOOGAAA A LUUUVA GOLEIRÃÃÃOOOO!!! GOOOOOOOOOOOLLLLLLL!!! Isso não foi uma falta, mas sim uma obra de arte!",
                "Pra ele ali é meio gol... bateeeeeuu... ÉÉÉÉÉÉÉÉÉÉ GOLAAAAÇOOOOOOOOO! Otero acerta o ângulo e vai pra torcidaa! Cuidado pra não tomar cartão amarelo!",
                "Falta cobrada por Otero... É GOOOOOOOOOOOOOOL! JOGA A LUUUUUVA GOLEIRÃOOOOOO");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null, false, false, false)).isIn(
                "Batedor levanta na área, e Zagueiro sobe mais alto que todos para cabecear: É GOOOOOL!",
                "Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!",
                "Cobrança de Batedor, e Zagueiro aparece de cabeça pra estufar a rede!",
                "Batedor bate o escanteio, Zagueiro sobe sozinho e manda pra dentro! Que jogada de bola parada!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null, false, false, false)).isIn(
                "Subiu mais que todo mundo! Zagueiro cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Zagueiro aparece na área e não perdoa!",
                "Bola levantada na área, Zagueiro testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Zagueiro não perdoa dentro da pequena área!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null, false, false, false)).isIn(
                "Subiu mais que todo mundo! Batedor cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Batedor aparece na área e não perdoa!",
                "Bola levantada na área, Batedor testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Batedor não perdoa dentro da pequena área!");
    }

    @Test
    void comebackGoalAppendsTheSignatureLineAfterTheNormalGoalDescriptionForEveryGoalFamilyType() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, false, false))
                .isEqualTo(baseGoal + " Homens de pouca fé... homens de muita fé!");

        String basePenalty = LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, true, false, false))
                .isEqualTo(basePenalty + " Homens de pouca fé... homens de muita fé!");

        String baseCorner = LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, true, false, false))
                .isEqualTo(baseCorner + " Homens de pouca fé... homens de muita fé!");
    }

    @Test
    void comebackGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, true, false, false)).isIn(
                "O juiz não pensou duas vezes: é amareeeelo pra Otero!",
                "Otero entra forte e o juiz não perdoa: cartão amarelo!",
                "Cartão amarelo pra Otero. Cuidado, hein, que com mais um ele desfalca o time!",
                "Otero exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!");
    }

    @Test
    void hypeGoalAppendsTheSignatureLineAfterTheNormalGoalDescription() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, true, false))
                .isEqualTo(baseGoal
                        + " Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!");
    }

    @Test
    void hypeGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, false, true, false)).isIn(
                "O juiz não pensou duas vezes: é amareeeelo pra Otero!",
                "Otero entra forte e o juiz não perdoa: cartão amarelo!",
                "Cartão amarelo pra Otero. Cuidado, hein, que com mais um ele desfalca o time!",
                "Otero exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!");
    }

    @Test
    void comebackAndHypeBothTrueAppendBothSignatureLinesInOrder() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, true, false))
                .isEqualTo(baseGoal
                        + " Homens de pouca fé... homens de muita fé!"
                        + " Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!");
    }

    @Test
    void losingStreakOverridesTheHalfTimeLine() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false, true))
                .isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeWithoutAStreakKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false, false)).isIn(
                "Apita o juiz e o primeiro tempo chega ao fim! Bora rever o que deu e o que não deu",
                "Intervalo — fim do primeiro tempo. Hora de ajustar as peças, professor!",
                "Hora do cafezinho, torcida! Os times vão pro vestiário e a gente já volta com mais",
                "Fim de primeiro tempo, e olha que jogo emocionante estamos tendo hoje!");
    }

    @Test
    void anAssistedGoalUsesTheTwoNamePoolNamingBothPlayers() {
        String result = LiveCommentary.of(EventType.GOAL, "Otero", "Diego", 60, null, false, false, false);
        assertThat(result).contains("Otero").contains("Diego");
    }

    @Test
    void anUnassistedGoalKeepsUsingTheOneNamePool() {
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "GOOOOOOOOOOOOOOOOOOL! Otero balança as redes!",
                "Otero marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
                "Otero aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
                "GOOOOL! Otero! Eu tô emocionado, gente, que golaço impressionante!",
                "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de Otero!",
                "Joga a luva, goleirão! Otero manda uma patada e é gooooooooooool!");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail for the right reason**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`

Expected: compile FAILURE — `LiveCommentary.of(...)` still takes 7 args, every call in the test file now passes 8.

- [ ] **Step 3: Update `LiveCommentary.of(...)` to the new signature**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java`, replace:

```java
    private static final String COMEBACK_GOAL_LINE = "Homens de pouca fé... homens de muita fé!";
    private static final String LOSING_STREAK_HALF_TIME_LINE =
            "E não adianta me ligar que eu vou mandar a merda.";
```

with:

```java
    private static final String COMEBACK_GOAL_LINE = "Homens de pouca fé... homens de muita fé!";
    private static final String HYPE_GOAL_LINE =
            "Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!";
    private static final String LOSING_STREAK_HALF_TIME_LINE =
            "E não adianta me ligar que eu vou mandar a merda.";
```

Then replace:

```java
    /**
     * {@code inName} is used for substitutions, corner goals (the corner taker), and assisted
     * open-play goals (the assister — {@code playerInId} on the event). {@code detail} only for
     * TACTICS_CHANGE. {@code comebackGoal} true only for a goal-family type that erases a deficit
     * for the managed club — appends the signature line after the normal goal description, so
     * the scorer and the goal itself are still described. {@code losingStreakHalfTime} true only
     * at HALF_TIME when the managed club has lost each of its last 2 completed fixtures —
     * overrides the normal half-time pool with the signature line (half-time has no per-event
     * scorer/action to preserve, unlike a goal).
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean losingStreakHalfTime) {
        if (type == EventType.HALF_TIME && losingStreakHalfTime) {
            return LOSING_STREAK_HALF_TIME_LINE;
        }
        String base = switch (type) {
            case GOAL -> inName != null
                    ? pick2(GOAL_ASSISTED, inName, playerName, minute, type)
                    : pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> pick2(SUBSTITUTION_LINES, inName, playerName, minute, type);
            case TACTICS_CHANGE -> tacticsChange(detail, minute);
            case HALF_TIME -> pick0(HALF_TIME_LINES, minute, type);
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName, minute);
            case FOUL -> throw new IllegalStateException(
                    "FOUL is never rendered — LiveMatchService.toState() must filter it before calling of()");
        };
        return comebackGoal && type.isGoal() ? base + " " + COMEBACK_GOAL_LINE : base;
    }
```

with:

```java
    /**
     * {@code inName} is used for substitutions, corner goals (the corner taker), and assisted
     * open-play goals (the assister — {@code playerInId} on the event). {@code detail} only for
     * TACTICS_CHANGE. {@code comebackGoal} true only for a goal-family type that erases a deficit
     * for the managed club — appends the signature line after the normal goal description, so
     * the scorer and the goal itself are still described. {@code hypeGoal} true only for a
     * goal-family type that is the first in the match to either arrive with the managed club
     * already on a hot streak entering the match, or push the managed club's in-match lead to 3+
     * goals — appends its own signature line the same way, independently of (and combinable
     * with) {@code comebackGoal}. {@code losingStreakHalfTime} true only at HALF_TIME when the
     * managed club has lost each of its last 2 completed fixtures — overrides the normal
     * half-time pool with the signature line (half-time has no per-event scorer/action to
     * preserve, unlike a goal).
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean hypeGoal, boolean losingStreakHalfTime) {
        if (type == EventType.HALF_TIME && losingStreakHalfTime) {
            return LOSING_STREAK_HALF_TIME_LINE;
        }
        String base = switch (type) {
            case GOAL -> inName != null
                    ? pick2(GOAL_ASSISTED, inName, playerName, minute, type)
                    : pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> pick2(SUBSTITUTION_LINES, inName, playerName, minute, type);
            case TACTICS_CHANGE -> tacticsChange(detail, minute);
            case HALF_TIME -> pick0(HALF_TIME_LINES, minute, type);
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName, minute);
            case FOUL -> throw new IllegalStateException(
                    "FOUL is never rendered — LiveMatchService.toState() must filter it before calling of()");
        };
        if (!type.isGoal()) {
            return base;
        }
        String result = base;
        if (comebackGoal) {
            result += " " + COMEBACK_GOAL_LINE;
        }
        if (hypeGoal) {
            result += " " + HYPE_GOAL_LINE;
        }
        return result;
    }
```

- [ ] **Step 4: Update the one production call site in `LiveMatchService.toState()`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
        boolean losingStreak = losingStreakHalfTime(lm.getSaveGameId(), save.getManagedClubId());
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()), losingStreak))).toList();
```

with:

```java
        boolean losingStreak = losingStreakHalfTime(lm.getSaveGameId(), save.getManagedClubId());
        boolean enteringMatchOnStreak = hypeStreak(lm.getSaveGameId(), save.getManagedClubId());
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()),
                        isHypeGoal(revealed, e, lm.isManagedIsHome(), enteringMatchOnStreak), losingStreak))).toList();
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest' --tests 'br.com.carvoeiromanager.match.LiveMatchServiceCommentaryTest'`

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`

Expected: BUILD SUCCESSFUL. This confirms `LiveMatchLosingStreakIT` (which exercises `toState()` end-to-end) still passes with the new `enteringMatchOnStreak` computation and the extra `LiveCommentary.of(...)` argument in place.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java
git commit -m "$(cat <<'EOF'
feat(match): hype-goal commentary for a win streak or a 3-goal lead

Appends a signature line after a managed goal when the club either
enters the match on a hot streak (3 wins in a row, or unbeaten over 5)
or that goal pushes the in-match lead to 3+ — once per match, on
whichever goal qualifies first. Combines with the existing
comeback-goal line when both apply to the same goal.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** streak condition (Task 1's `isHypeStreak`), margin condition (Task 1's
  `isHypeGoal`), once-per-match dedup (`isHypeGoal`'s "first qualifying goal" scan), the new
  signature line and its additive combination with `comebackGoal` (Task 2's `LiveCommentary`
  changes), and the `toState()` wiring (Task 2 Step 4) are all covered. The
  `recentCompletedFixtures` extraction the spec asked for is in Task 1 Step 3. No frontend
  changes, matching the spec's explicit out-of-scope note.
- **Deviation from spec, called out in Global Constraints:** dropped the spec's proposed
  `LiveMatchHypeStreakIT` integration test. Reason: the hype line only ever renders on an actual
  goal event, and the live match engine's goals are simulated probabilistically through the
  public API with no way to force one — the exact same constraint the pre-existing, structurally
  identical `comebackGoal` feature has, which is why `comebackGoal` itself ships with zero
  integration tests. Task 2 relies on the same unit + `LiveCommentaryTest` tier that already
  proves `comebackGoal` correct in production. Flagged in Global Constraints for the human to
  override if they'd rather have the (potentially flaky) IT test anyway.
- **Placeholder scan:** every step has complete, runnable code — no TBDs, no "add tests for the
  above" without the actual test code.
- **Type/name consistency:** `isHypeStreak(List<Fixture>, UUID): boolean` and
  `isHypeGoal(List<LiveTimelineEvent>, LiveTimelineEvent, boolean, boolean): boolean` are defined
  once in Task 1 and consumed with identical signatures in Task 2's `toState()` wiring.
  `LiveCommentary.of(...)`'s new 8-arg signature is identical across Task 2's test file rewrite
  and the `toState()` call site update. `HYPE_GOAL_LINE`'s text matches the Global Constraints
  value verbatim in both the production constant and every test assertion.
- **Known judgment call:** Task 2 is larger than a typical task because `LiveCommentary.of(...)`'s
  signature change and its single production call site can't be split across commits without a
  non-compiling intermediate state — this is disclosed in Task 2's Interfaces section rather than
  artificially split.
