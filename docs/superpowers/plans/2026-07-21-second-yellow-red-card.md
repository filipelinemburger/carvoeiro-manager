# Second Yellow = Red Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A player who receives a second yellow card in the same match is sent off with a red card (shown right after the second yellow), in both the live engine and the instant simulator; a red card expunges that match's yellows from the suspension-accumulation tally (CBF rule).

**Architecture:** No new `EventType`. A second-yellow send-off is a YELLOW_CARD event followed by a RED_CARD event in the same minute for the same player. The live engine tracks a `booked` set per side; the instant simulator tracks `booked`/`sentOff` sets during attribution and excludes sent-off players from later picks. The live RED_CARD carries `detail = "SECOND_YELLOW"` (timeline-only, not persisted) so `LiveCommentary` uses a dedicated pool. `MatchOutcomeApplier` skips yellow accumulation for any player red-carded in the match.

**Tech Stack:** Java 21 / Spring Boot, JUnit 5 + AssertJ, Testcontainers Postgres for `*IT` tests.

**Spec:** `docs/superpowers/specs/2026-07-21-second-yellow-red-card-design.md`

## Global Constraints

- Test command (from repo root): `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test` — use a 600000ms timeout for the full suite. Focused runs use `--tests 'br.com.carvoeiromanager.match.<Class>'`.
- Git diffs in this repo MUST use `git -c diff.external= diff --no-ext-diff` (broken `diff.external` config).
- Work directly on `main` (no branches). Commit after each task with a `feat(match):`/`test(match):` conventional message ending in `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Do NOT push; the controller pushes at the end.
- Known pre-existing flaky test (unrelated): `TransferServiceSellingIT.listedManagedPlayersDrawBoostedBids` — if it fails alone, re-run it before concluding anything.

---

### Task 1: Live engine — second yellow becomes a same-minute red

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java` (Side class ~line 57; `maybeCard` ~line 321)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java`

**Interfaces:**
- Produces: `LiveTimelineEvent.SECOND_YELLOW_DETAIL` — `public static final String` = `"SECOND_YELLOW"`. Task 2 (commentary) reads it. RED_CARD events produced by a second yellow carry it as `detail`; straight reds keep `detail = null`.

- [ ] **Step 1: Write the failing tests**

Append to `LiveMatchEngineTest.java` (before the closing brace), following the existing seed-sweep style:

```java
    // ---- second yellow = red ----

    @Test
    void aSecondYellowAlwaysComesWithASameMinuteSecondYellowRedAndEndsThePlayersMatch() {
        boolean sawSecondYellow = false;
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            Map<UUID, List<LiveTimelineEvent>> yellowsByPlayer = new HashMap<>();
            Map<UUID, List<LiveTimelineEvent>> redsByPlayer = new HashMap<>();
            for (LiveTimelineEvent e : t.events()) {
                if (e.type() == EventType.YELLOW_CARD) {
                    yellowsByPlayer.computeIfAbsent(e.playerId(), k -> new ArrayList<>()).add(e);
                }
                if (e.type() == EventType.RED_CARD) {
                    redsByPlayer.computeIfAbsent(e.playerId(), k -> new ArrayList<>()).add(e);
                }
            }
            for (Map.Entry<UUID, List<LiveTimelineEvent>> en : yellowsByPlayer.entrySet()) {
                List<LiveTimelineEvent> yellows = en.getValue();
                assertThat(yellows.size())
                        .as("seed %d: a player can never collect more than two yellows", seed)
                        .isLessThanOrEqualTo(2);
                if (yellows.size() == 2) {
                    sawSecondYellow = true;
                    List<LiveTimelineEvent> reds = redsByPlayer.getOrDefault(en.getKey(), List.of());
                    assertThat(reds).as("seed %d: two yellows must carry exactly one red", seed).hasSize(1);
                    assertThat(reds.get(0).minute())
                            .as("seed %d: the red lands in the second yellow's minute", seed)
                            .isEqualTo(yellows.get(1).minute());
                    assertThat(reds.get(0).detail())
                            .isEqualTo(LiveTimelineEvent.SECOND_YELLOW_DETAIL);
                    assertThat(t.minutesPlayed().get(en.getKey()))
                            .as("seed %d: the send-off ends the player's minutes", seed)
                            .isEqualTo(reds.get(0).minute());
                }
            }
            for (Map.Entry<UUID, List<LiveTimelineEvent>> en : redsByPlayer.entrySet()) {
                assertThat(en.getValue()).as("seed %d: at most one red per player", seed).hasSize(1);
                int redMinute = en.getValue().get(0).minute();
                UUID sentOff = en.getKey();
                assertThat(t.events()).as("seed %d: no involvement after a send-off", seed)
                        .noneMatch(e -> e.minute() > redMinute
                                && (sentOff.equals(e.playerId()) || sentOff.equals(e.playerInId())));
            }
        }
        assertThat(sawSecondYellow)
                .as("300 seeds must produce at least one second-yellow send-off")
                .isTrue();
    }

    @Test
    void aStraightRedCarriesNoSecondYellowDetail() {
        for (long seed = 0; seed < 300; seed++) {
            LiveTimeline t = engine.simulate(config(seed, List.of()));
            t.events().stream().filter(e -> e.type() == EventType.RED_CARD).forEach(e -> {
                long yellows = t.events().stream()
                        .filter(y -> y.type() == EventType.YELLOW_CARD && y.playerId().equals(e.playerId()))
                        .count();
                if (yellows < 2) {
                    assertThat(e.detail()).as("straight red keeps a null detail").isNull();
                } else {
                    assertThat(e.detail()).isEqualTo(LiveTimelineEvent.SECOND_YELLOW_DETAIL);
                }
            });
        }
    }
```

(`Map`, `HashMap`, `ArrayList`, `List`, `UUID` are already imported in this test class.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'`
Expected: COMPILATION FAILURE — `LiveTimelineEvent.SECOND_YELLOW_DETAIL` does not exist yet. (That is the failing state for this cycle.)

- [ ] **Step 3: Implement**

In `LiveTimelineEvent.java`, add the constant inside the record body and extend the record Javadoc's `detail` sentence:

```java
public record LiveTimelineEvent(
        int minute,
        EventType type,
        boolean home,
        UUID playerId,
        UUID playerInId,
        String severity,
        String detail) {

    /** {@code detail} value on a RED_CARD produced by a second yellow in the same match. */
    public static final String SECOND_YELLOW_DETAIL = "SECOND_YELLOW";
}
```

Javadoc: change the `detail` sentence to say it is the `AiManagerBrain.Posture` name on AI-produced TACTICS_CHANGE (null = the user's own change), or {@link #SECOND_YELLOW_DETAIL} on a RED_CARD produced by a second yellow — else null.

In `LiveMatchEngine.java`:

1. Add to `Side` (after `hobbled`): `final Set<UUID> booked = new LinkedHashSet<>();  // players shown a yellow this match` (`Set`/`LinkedHashSet` already imported).
2. Replace `maybeCard` with:

```java
    private void maybeCard(int minute, Side side, TeamMatchInput input, Random rng, Random attrRng,
                           List<LiveTimelineEvent> events) {
        if (rng.nextDouble() < BASE_CARD_RATE * input.aggression() && !side.onPitch.isEmpty()) {
            EventType t = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
            // Fouls skew toward aggressive tacklers; the +1 keeps everyone eligible (unchanged formula).
            UUID booked = pickByWeight(side, p -> p.getTackling() + 1, attrRng);
            boolean secondYellow = t == EventType.YELLOW_CARD && !side.booked.add(booked);
            events.add(new LiveTimelineEvent(minute, t, side.home, booked, null, null, null));
            if (secondYellow) {
                events.add(new LiveTimelineEvent(minute, EventType.RED_CARD, side.home, booked, null, null,
                        LiveTimelineEvent.SECOND_YELLOW_DETAIL));
            }
            if (t == EventType.RED_CARD || secondYellow) {
                side.onPitch.remove(booked);
                side.exitMinute.put(booked, minute);
                side.redCarded = true;
                side.dirty = true;
            }
        }
    }
```

Note `!side.booked.add(booked)`: the first yellow registers the booking and returns true (not a second yellow); the second returns false. A straight RED short-circuits and never touches `booked` (irrelevant once off the pitch).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveMatchEngineTest'`
Expected: PASS (all tests in the class, including the pre-existing determinism ones).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveTimelineEvent.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchEngine.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchEngineTest.java
git commit -m "feat(match): second yellow sends the player off with a same-minute red (live engine)"
```

---

### Task 2: LiveCommentary — dedicated second-yellow-red lines

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java` (pools ~line 40; `of` RED_CARD case line 136; Javadoc lines 110-123)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java`

**Interfaces:**
- Consumes: `LiveTimelineEvent.SECOND_YELLOW_DETAIL` (Task 1). No signature change to `of(...)` — the `detail` param already flows from `LiveMatchService.toState` (line 431).

- [ ] **Step 1: Write the failing tests**

Append to `LiveCommentaryTest.java`:

```java
    @Test
    void aSecondYellowRedUsesTheDedicatedPool() {
        assertThat(LiveCommentary.of(EventType.RED_CARD, "Otero", null, 60,
                LiveTimelineEvent.SECOND_YELLOW_DETAIL, false, false, false)).isIn(
                "Segundo amarelo pra Otero e consequentemente o VERMELHO! Tá expulso, vai mais cedo pro chuveiro!",
                "Otero já tinha amarelo e fez outra! Segundo cartão amarelo, vermelho na sequência: RUA!",
                "Não podia fazer essa falta, Otero! O juiz mostra o segundo amarelo e depois o vermelho: EXPULSO!",
                "Bobeira de Otero! Segundo amarelo no jogo, o vermelho vem junto e o time fica com um a menos!");
    }

    @Test
    void aStraightRedKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.RED_CARD, "Otero", null, 60, null, false, false, false)).isIn(
                "É VERMELHOOOO! Otero está expulso e vai direto pro vestiário!",
                "Otero recebe o vermelho e deixa o time com um a menos. Agora vai ter que suar a camisa!",
                "Vermelho direto! Otero vai pro chuveiro mais cedo, e olha que sufoco pro time que fica reduzido!",
                "Não teve jeito, Otero: o juiz mostrou o vermelho e a arquibancada foi à loucura!");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`
Expected: FAIL — `aSecondYellowRedUsesTheDedicatedPool` gets a generic RED line, not a pool member. `aStraightRedKeepsTheGenericPool` passes already (that is fine — it is the regression guard).

- [ ] **Step 3: Implement**

In `LiveCommentary.java`, add after the `RED` array:

```java
    private static final String[] SECOND_YELLOW_RED = {
            "Segundo amarelo pra %s e consequentemente o VERMELHO! Tá expulso, vai mais cedo pro chuveiro!",
            "%s já tinha amarelo e fez outra! Segundo cartão amarelo, vermelho na sequência: RUA!",
            "Não podia fazer essa falta, %s! O juiz mostra o segundo amarelo e depois o vermelho: EXPULSO!",
            "Bobeira de %s! Segundo amarelo no jogo, o vermelho vem junto e o time fica com um a menos!"};
```

Replace the RED_CARD case in `of(...)`:

```java
            case RED_CARD -> LiveTimelineEvent.SECOND_YELLOW_DETAIL.equals(detail)
                    ? pick(SECOND_YELLOW_RED, playerName, minute, type)
                    : pick(RED, playerName, minute, type);
```

Update the `of(...)` Javadoc's `detail` sentence: "`detail` only for TACTICS_CHANGE (AI posture name) and for a RED_CARD produced by a second yellow (`LiveTimelineEvent.SECOND_YELLOW_DETAIL` — picks the dedicated pool)."

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.LiveCommentaryTest'`
Expected: PASS (whole class).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/LiveCommentary.java backend/src/test/java/br/com/carvoeiromanager/match/LiveCommentaryTest.java
git commit -m "feat(match): dedicated PT-BR commentary for a second-yellow red"
```

---

### Task 3: Instant sim — attribution tracks bookings and send-offs

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java` (`saveEvents`, lines 197-231)
- Test (create): `backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorCardsIT.java`

**Interfaces:**
- Consumes: nothing from Tasks 1-2 (`match_events` rows are plain type strings; no detail column exists).
- Produces: for a second yellow, `saveEvents` persists a same-minute RED_CARD `MatchEvent` row and appends `new AttributedEvent(playerId, EventType.RED_CARD, home)` — Task 4's applier consumes that.

- [ ] **Step 1: Write the failing IT**

Create `FixtureSimulatorCardsIT.java` (harness copied from `FixtureSimulatorAssistFoulIT`):

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureSimulatorCardsIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchEventRepository matchEventRepository;

    private UUID saveGameId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("instant_cards_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        saveGameId = save.id();
        managedClubId = save.managedClubId();
    }

    @Test
    void aSecondYellowAlwaysComesWithASameMinuteRedAndEndsThePlayersMatch() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        // 80 AI fixtures make at least one same-player double booking statistically certain
        // (expected occurrences well over 10; P(zero) < 1e-6).
        List<Fixture> aiFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(80)
                .toList();
        assertThat(aiFixtures).isNotEmpty();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        boolean sawSecondYellow = false;
        for (Fixture fixture : aiFixtures) {
            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(fixture.getId()));
            Map<UUID, List<MatchEvent>> yellows = events.stream()
                    .filter(e -> "YELLOW_CARD".equals(e.getType()))
                    .collect(Collectors.groupingBy(MatchEvent::getPlayerId));
            Map<UUID, List<MatchEvent>> reds = events.stream()
                    .filter(e -> "RED_CARD".equals(e.getType()))
                    .collect(Collectors.groupingBy(MatchEvent::getPlayerId));

            for (Map.Entry<UUID, List<MatchEvent>> en : yellows.entrySet()) {
                assertThat(en.getValue().size())
                        .as("a player can never collect more than two yellows in one match")
                        .isLessThanOrEqualTo(2);
                if (en.getValue().size() == 2) {
                    sawSecondYellow = true;
                    int secondYellowMinute = en.getValue().stream()
                            .mapToInt(MatchEvent::getMinute).max().orElseThrow();
                    List<MatchEvent> playerReds = reds.getOrDefault(en.getKey(), List.of());
                    assertThat(playerReds).as("two yellows must carry exactly one red").hasSize(1);
                    assertThat(playerReds.get(0).getMinute()).isEqualTo(secondYellowMinute);
                }
            }
            for (Map.Entry<UUID, List<MatchEvent>> en : reds.entrySet()) {
                assertThat(en.getValue()).as("at most one red per player").hasSize(1);
                int redMinute = en.getValue().get(0).getMinute();
                UUID sentOff = en.getKey();
                assertThat(events)
                        .as("a sent-off player must not be attributed any later event")
                        .noneMatch(e -> e.getMinute() > redMinute && sentOff.equals(e.getPlayerId()));
            }
        }
        assertThat(sawSecondYellow)
                .as("80 AI fixtures should produce at least one second-yellow send-off")
                .isTrue();
    }
}
```

- [ ] **Step 2: Run the IT to verify it fails**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorCardsIT'` (timeout 600000)
Expected: FAIL — either a player with two yellows and zero reds, or a >2 yellow count (the pre-fix behavior).

- [ ] **Step 3: Implement**

In `FixtureSimulator.saveEvents` (events arrive minute-ordered — `MatchSimulationService.simulate` appends inside its minute loop). Replace the method with:

```java
    private List<AttributedEvent> saveEvents(Fixture fixture, SimulatedMatch match,
                                             List<Player> homeXi, List<Player> awayXi,
                                             TacticalProfile homeProfile, TacticalProfile awayProfile,
                                             UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        Random assistRng = new Random(seed ^ 0x5A551571L);
        List<AttributedEvent> attributed = new ArrayList<>();
        Set<UUID> booked = new HashSet<>();
        Set<UUID> sentOff = new HashSet<>();
        for (SimulatedEvent event : match.events()) {
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            // Sent-off players take no further part. The fallback to the full XI is practically
            // unreachable (it would need 11 send-offs on one side) but beats an empty-pool crash.
            List<Player> available = xi.stream().filter(p -> !sentOff.contains(p.getId())).toList();
            if (available.isEmpty()) {
                available = xi;
            }

            Player player = switch (event.type()) {
                case GOAL -> pickByWeight(available, FixtureSimulator::goalWeight, rng);
                case PENALTY_GOAL -> SetPieces.takerOrFallback(available, profile.penaltyTakerId(), Player::getFinishing);
                case FREE_KICK_GOAL -> SetPieces.takerOrFallback(available, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                case CORNER_GOAL -> pickByWeight(available, SetPieces::headerWeight, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(available, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED, SET_PIECE_GOAL, FOUL ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
            attributed.add(new AttributedEvent(player.getId(), event.type(), event.home()));

            Player assister = assisterFor(event, player, available, profile, assistRng);
            if (assister != null) {
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), event.minute(), "ASSIST", assister.getId(), clubId));
            }

            // Second yellow to the same player = an immediate red in the same minute (real-football
            // rule); either kind of red removes the player from all later attribution.
            if (event.type() == EventType.YELLOW_CARD && !booked.add(player.getId())) {
                matchEventRepository.save(MatchEvent.create(fixture.getId(), event.minute(),
                        EventType.RED_CARD.name(), player.getId(), clubId));
                attributed.add(new AttributedEvent(player.getId(), EventType.RED_CARD, event.home()));
                sentOff.add(player.getId());
            } else if (event.type() == EventType.RED_CARD) {
                sentOff.add(player.getId());
            }
        }
        return attributed;
    }
```

Add `java.util.HashSet` and `java.util.Set` to the imports if not present (`HashSet` is already imported in this file; verify `Set`).

- [ ] **Step 4: Run the IT and the sibling IT to verify they pass**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.FixtureSimulatorCardsIT' --tests 'br.com.carvoeiromanager.match.FixtureSimulatorAssistFoulIT'` (timeout 600000)
Expected: PASS (both classes — the assist IT guards the attribution refactor).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/FixtureSimulator.java backend/src/test/java/br/com/carvoeiromanager/match/FixtureSimulatorCardsIT.java
git commit -m "feat(match): instant-sim second yellow = red; sent-off players leave attribution"
```

---

### Task 4: MatchOutcomeApplier — a red expunges that match's yellows from the tally; full suite

**Files:**
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java` (`applyTeam`, lines 67-91)
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java`

**Interfaces:**
- Consumes: `AttributedEvent` lists that may now contain YELLOW_CARD ×2 + RED_CARD for one player (Tasks 1/3 produce them via `finish()` / `saveEvents`).
- Produces: unchanged public API; only accumulation behavior changes.

- [ ] **Step 1: Write the failing tests**

Append to `MatchOutcomeApplierTest.java`:

```java
    @Test
    void aSecondYellowSendOffBansOneMatchWithoutCountingTheYellows() {
        Player p = player(Position.MID);
        p.addYellowCard(); // tally 1 from earlier matches
        List<AttributedEvent> events = List.of(
                new AttributedEvent(p.getId(), EventType.YELLOW_CARD, true),
                new AttributedEvent(p.getId(), EventType.YELLOW_CARD, true),
                new AttributedEvent(p.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 1, 1, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 1, 1, false), acc);

        assertThat(p.getYellowCards()).isEqualTo(1);          // send-off match yellows are expunged
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);  // the red's ban only
        assertThat(acc.newSuspensions()).hasSize(1);
        assertThat(acc.bookings()).extracting(BookingResponse::type)
                .containsExactly("YELLOW_CARD", "YELLOW_CARD", "RED_CARD");
    }

    @Test
    void aStraightRedAfterOneYellowLeavesTheTallyUntouched() {
        Player p = player(Position.DEF);
        p.addYellowCard();
        p.addYellowCard(); // tally 2: a counted third yellow would wrongly stack an accumulation ban
        List<AttributedEvent> events = List.of(
                new AttributedEvent(p.getId(), EventType.YELLOW_CARD, true),
                new AttributedEvent(p.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 0, 2, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 2, 0, false), acc);

        assertThat(p.getYellowCards()).isEqualTo(2);          // not counted toward 3
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);  // one ban: the red's, not accumulation
        assertThat(acc.newSuspensions()).hasSize(1);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchOutcomeApplierTest'`
Expected: FAIL — first test: tally 3 and two suspensions accrued; second test: tally 3 with an extra accumulation suspension.

- [ ] **Step 3: Implement**

In `applyTeam`, replace the yellow loop (keep the red block below it unchanged):

```java
            boolean sentOffThisMatch = reds.contains(p.getId());
            int yellowCount = yellows.getOrDefault(p.getId(), 0);
            for (int i = 0; i < yellowCount; i++) {
                if (team.managed()) {
                    acc.bookings().add(new BookingResponse(p.getId(), p.getName(), "YELLOW_CARD"));
                }
                // CBF rule: a red card (straight or second-yellow) expunges this match's yellows
                // from the accumulation tally — the send-off ban is the only punishment.
                if (sentOffThisMatch) {
                    continue;
                }
                p.addYellowCard();
                if (p.getYellowCards() % 3 == 0) {
                    p.addSuspension(1);
                    if (team.managed()) {
                        acc.newSuspensions().add(new SuspensionResponse(p.getId(), p.getName(), 1));
                    }
                }
            }
```

Also update the class Javadoc's first sentence to mention the expunge rule ("applies cards -> suspensions (a red expunges that match's yellows from the accumulation tally)").

- [ ] **Step 4: Run the focused tests, then the FULL backend suite**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.MatchOutcomeApplierTest' --tests 'br.com.carvoeiromanager.match.MatchOutcomeApplierTimelineTest' --tests 'br.com.carvoeiromanager.match.SuspensionLifecycleIT'`
Expected: PASS.

Then the full suite (timeout 600000): `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`
Expected: PASS (see Global Constraints for the one known-flaky unrelated IT).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/MatchOutcomeApplier.java backend/src/test/java/br/com/carvoeiromanager/match/MatchOutcomeApplierTest.java
git commit -m "feat(match): a red card expunges that match's yellows from the suspension tally"
```
