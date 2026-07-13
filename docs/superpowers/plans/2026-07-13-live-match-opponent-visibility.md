# Live-Match Opponent Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user see the opponent's side during a live match — an "Adversário" ratings sub-tab and a new dual-formation "Campo" pitch tab.

**Architecture:** Backend: generalize four managed-side-only computations in `LiveMatchService` (ratings, on-pitch roster, tactics) into side-aware core methods with `managed*`/`opponent*` delegates, add `role` to `LivePlayerResponse` and kit colors to `LiveMatchState`. Frontend: reuse `PlayerRatingsList` a second time behind a toggle, and reuse `Pitch.tsx`'s existing two-team support (`home`+`away`) in a new 3rd tab — neither component changes.

**Tech Stack:** Java 21 / Spring Boot (Gradle: `./gradlew test` from `backend/`), React 19 / MUI / Vitest (frontend), JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-13-live-match-opponent-visibility-design.md`

## Global Constraints

- Work directly on `main` — no branches, no worktrees (user preference).
- Local `git commit` is allowed and expected — each task ends by committing its own tested work. `git push` is never run without an explicit user ask.
- This is a purely additive change to `LiveMatchService`/`LiveMatchState`/`LivePlayerResponse` — every existing field's value for the managed side must stay byte-identical to today (verified by the existing test suite passing unmodified, since none of those tests need updating).
- `role` on `LivePlayerResponse` resolves via `profile.roles().getOrDefault(player.getId(), PlayerRole.defaultFor(player.getPosition()))` — never null, always a valid `PlayerRole` name.
- Do not touch `LiveCommentary.java`, `isComebackGoal`, `isLosingStreak`, or any commentary-related code — out of scope for this plan.
- Do not touch `Pitch.tsx`, `PlayerRatingsList.tsx`, `pitchSlots.ts`, or `PlayerToken.tsx` — all three are reused exactly as they are today.
- Backend commands run from `backend/`: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test ...` (long timeout, 300000-600000ms).
- Frontend commands run from `frontend/`: `npx tsc --noEmit && npx vitest run`.

---

### Task 1: Backend — opponent ratings, on-pitch roster, tactics, role, kit colors

**Files:**
- Create: `backend/src/main/java/br/com/carvoeiromanager/match/KitColors.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`
- Modify: `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`
- Test: `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchOpponentVisibilityIT.java` (new)

**Interfaces:**
- Produces: `KitColors(String primary, String secondary)`; `LivePlayerResponse`'s new trailing `role: String` field; `LiveMatchState`'s five new trailing fields — `opponentOnPitch: List<LivePlayerResponse>`, `opponentTactics: LiveTactics`, `opponentRatings: List<PlayerRating>`, `homeClubColors: KitColors`, `awayClubColors: KitColors`. These are the exact names Tasks 2-3 consume on the frontend (camelCase maps automatically through the existing JSON serialization, same as every other field).

- [ ] **Step 1: Create `KitColors`**

Create `backend/src/main/java/br/com/carvoeiromanager/match/KitColors.java`:

```java
package br.com.carvoeiromanager.match;

/** A club's kit colors for the live pitch view. */
public record KitColors(String primary, String secondary) {
}
```

- [ ] **Step 2: Add `role` to `LivePlayerResponse`**

Replace the entire content of `backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java`:

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
        boolean eligible,
        String role) {
}
```

- [ ] **Step 3: Add the five new fields to `LiveMatchState`**

Replace the entire content of `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java`:

```java
package br.com.carvoeiromanager.match;

import java.util.List;

public record LiveMatchState(
        String fixtureId,
        String competitionName,
        Integer round,
        String matchDate,
        String homeClubName,
        String homeClubShortName,
        String awayClubName,
        String awayClubShortName,
        boolean managedIsHome,
        int minute,
        int homeGoals,
        int awayGoals,
        String status,
        List<LiveEventResponse> events,
        List<LivePlayerResponse> onPitch,
        List<LivePlayerResponse> bench,
        int subsRemaining,
        LiveTactics currentTactics,
        List<PlayerRating> playerRatings,
        List<LivePlayerResponse> opponentOnPitch,
        LiveTactics opponentTactics,
        List<PlayerRating> opponentRatings,
        KitColors homeClubColors,
        KitColors awayClubColors) {
}
```

- [ ] **Step 4: Add the `PlayerRole` import to `LiveMatchService.java`**

In `backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java`, replace:

```java
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
```

with:

```java
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
```

- [ ] **Step 5: Generalize `managedRatings` into `ratingsFor` + `opponentRatings`**

In `LiveMatchService.java`, replace:

```java
    private List<PlayerRating> managedRatings(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute) {
        boolean managedIsHome = lm.isManagedIsHome();
        List<Player> managedXi = managedIsHome ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = managedIsHome ? cfg.homeBench() : cfg.awayBench();

        Map<UUID, Integer> minutes = minutesThrough(managedXi, timeline, minute, managedIsHome);
        List<Player> everyone = new ArrayList<>(managedXi);
        everyone.addAll(managedBench);   // rate() self-filters to players with minutes > 0

        // A missed penalty must reach the rating calculator (it is NOT persisted; the finish()
        // persist loop and match-event repository still key off plain isCardOrGoal).
        List<AttributedEvent> sideEvents = timeline.eventsThrough(minute).stream()
                .filter(e -> e.home() == managedIsHome
                        && (isCardOrGoal(e.type()) || e.type() == EventType.PENALTY_MISSED))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();

        int scored = managedIsHome ? timeline.homeGoalsAt(minute) : timeline.awayGoalsAt(minute);
        int conceded = managedIsHome ? timeline.awayGoalsAt(minute) : timeline.homeGoalsAt(minute);
        return playerRatingCalculator.rate(everyone, sideEvents, minutes, scored, conceded);
    }
```

with:

```java
    private List<PlayerRating> managedRatings(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute) {
        return ratingsFor(cfg, timeline, minute, lm.isManagedIsHome());
    }

    /**
     * Computes one side's 0–10 grades from everything revealed through {@code minute}: the
     * side's players who have featured, their goals/cards so far, minutes played so far, and
     * the score at that minute. Used live on every advance for both the managed side and the
     * opponent, and with {@code minute == MINUTES}, produces the final full-time grades
     * persisted into the news summary (managed side only).
     */
    private List<PlayerRating> opponentRatings(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute) {
        return ratingsFor(cfg, timeline, minute, !lm.isManagedIsHome());
    }

    private List<PlayerRating> ratingsFor(LiveMatchConfig cfg, LiveTimeline timeline, int minute, boolean isHome) {
        List<Player> xi = isHome ? cfg.homeXi() : cfg.awayXi();
        List<Player> bench = isHome ? cfg.homeBench() : cfg.awayBench();

        Map<UUID, Integer> minutes = minutesThrough(xi, timeline, minute, isHome);
        List<Player> everyone = new ArrayList<>(xi);
        everyone.addAll(bench);   // rate() self-filters to players with minutes > 0

        // A missed penalty must reach the rating calculator (it is NOT persisted; the finish()
        // persist loop and match-event repository still key off plain isCardOrGoal).
        List<AttributedEvent> sideEvents = timeline.eventsThrough(minute).stream()
                .filter(e -> e.home() == isHome
                        && (isCardOrGoal(e.type()) || e.type() == EventType.PENALTY_MISSED))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();

        int scored = isHome ? timeline.homeGoalsAt(minute) : timeline.awayGoalsAt(minute);
        int conceded = isHome ? timeline.awayGoalsAt(minute) : timeline.homeGoalsAt(minute);
        return playerRatingCalculator.rate(everyone, sideEvents, minutes, scored, conceded);
    }
```

Note: the `/** Computes the managed club's 0–10 grades... */` javadoc that used to sit directly
above `managedRatings` should be deleted as part of this replacement (its content moved to
`opponentRatings`'s new javadoc above, since that's now the method documenting the shared
behavior for both sides).

- [ ] **Step 6: Generalize `computeCurrentTactics` into `tacticsFor` + `computeOpponentTactics`**

In `LiveMatchService.java`, replace:

```java
    /** Derives the managed side's active tactics at {@code minute}, applying any scheduled changes. */
    private LiveTactics computeCurrentTactics(LiveMatch lm, LiveMatchConfig cfg, int minute) {
        TacticalProfile base = lm.isManagedIsHome() ? cfg.homeProfile() : cfg.awayProfile();
        String mentality = base.mentality().name();
        String pressing = base.pressing().name();
        String marking = base.marking().name();
        String formation = base.formation();

        // Apply the last scheduled change for the managed side whose minute <= committedMinute
        for (TimedTactics tt : cfg.tacticsSchedule()) {
            if (tt.home() == lm.isManagedIsHome() && tt.minute() <= minute) {
                mentality = tt.mentality().name();
                pressing = tt.pressing().name();
                marking = tt.marking().name();
                formation = tt.formation();
            }
        }
        return new LiveTactics(mentality, pressing, marking, formation);
    }
```

with:

```java
    /** Derives the managed side's active tactics at {@code minute}, applying any scheduled changes. */
    private LiveTactics computeCurrentTactics(LiveMatch lm, LiveMatchConfig cfg, int minute) {
        return tacticsFor(cfg, minute, lm.isManagedIsHome());
    }

    /** Derives the opponent side's active tactics at {@code minute}, applying any scheduled changes. */
    private LiveTactics computeOpponentTactics(LiveMatch lm, LiveMatchConfig cfg, int minute) {
        return tacticsFor(cfg, minute, !lm.isManagedIsHome());
    }

    private LiveTactics tacticsFor(LiveMatchConfig cfg, int minute, boolean isHome) {
        TacticalProfile base = isHome ? cfg.homeProfile() : cfg.awayProfile();
        String mentality = base.mentality().name();
        String pressing = base.pressing().name();
        String marking = base.marking().name();
        String formation = base.formation();

        // Apply the last scheduled change for this side whose minute <= committedMinute
        for (TimedTactics tt : cfg.tacticsSchedule()) {
            if (tt.home() == isHome && tt.minute() <= minute) {
                mentality = tt.mentality().name();
                pressing = tt.pressing().name();
                marking = tt.marking().name();
                formation = tt.formation();
            }
        }
        return new LiveTactics(mentality, pressing, marking, formation);
    }
```

- [ ] **Step 7: Generalize `onPitchManaged` into `onPitchFor` + `onPitchOpponent`**

In `LiveMatchService.java`, replace:

```java
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
```

with:

```java
    /** One side's current on-pitch set, hobbled set, and bench-players-already-used, at {@code minute}. */
    private record OnPitch(List<UUID> onPitch, Set<UUID> hobbled, Set<UUID> usedIn) {
    }

    private OnPitch onPitchManaged(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> managedXi) {
        return onPitchFor(timeline, minute, lm.isManagedIsHome(), managedXi);
    }

    private OnPitch onPitchOpponent(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> opponentXi) {
        return onPitchFor(timeline, minute, !lm.isManagedIsHome(), opponentXi);
    }

    private OnPitch onPitchFor(LiveTimeline timeline, int minute, boolean isHome, List<Player> xi) {
        List<UUID> onPitch = new ArrayList<>(xi.stream().map(Player::getId).toList());
        Set<UUID> hobbled = new LinkedHashSet<>();
        Set<UUID> usedIn = new LinkedHashSet<>();
        for (LiveTimelineEvent e : timeline.eventsThrough(minute)) {
            if (e.home() != isHome) continue;
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
```

- [ ] **Step 8: Add `role` resolution to `livePlayer`**

In `LiveMatchService.java`, replace:

```java
    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible());
    }
```

with:

```java
    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, TacticalProfile profile,
                                          boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        PlayerRole role = profile.roles().getOrDefault(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible(), role.name());
    }
```

- [ ] **Step 9: Rewrite `toState()` to build and return all five new fields**

In `LiveMatchService.java`, replace the entire `toState` method:

```java
    private LiveMatchState toState(LiveMatch lm, SaveGame save, LiveMatchConfig cfg, LiveTimeline timeline) {
        int minute = lm.getCommittedMinute();

        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        Map<UUID, Player> byId = indexAll(cfg);
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

        LiveTactics currentTactics = computeCurrentTactics(lm, cfg, minute);

        // Live grades: computed from what has been revealed so far (goals/cards/minutes/score through
        // the committed minute). They start near 6.0 and diverge as the game unfolds; at minute 90
        // they equal the final full-time grades. Empty at kickoff (nobody has played yet).
        List<PlayerRating> playerRatings = managedRatings(lm, cfg, timeline, minute);

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                clubs.get(fixture.getHomeClubId()).getName(), shortName(clubs.get(fixture.getHomeClubId())),
                clubs.get(fixture.getAwayClubId()).getName(), shortName(clubs.get(fixture.getAwayClubId())),
                lm.isManagedIsHome(), minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed, currentTactics, playerRatings);
    }
```

with:

```java
    private LiveMatchState toState(LiveMatch lm, SaveGame save, LiveMatchConfig cfg, LiveTimeline timeline) {
        int minute = lm.getCommittedMinute();

        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        Map<UUID, Player> byId = indexAll(cfg);
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

        String status = statusAt(lm, timeline, minute);
        int subsUsed = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(lm.getSaveGameId()).stream()
                .collect(java.util.stream.Collectors.toMap(Club::getId, c -> c));
        String competitionName = competitionRepository.findById(lm.getCompetitionId())
                .map(c -> c.getName()).orElse(null);

        boolean managedIsHome = lm.isManagedIsHome();
        TacticalProfile managedProfile = managedIsHome ? cfg.homeProfile() : cfg.awayProfile();
        TacticalProfile opponentProfile = managedIsHome ? cfg.awayProfile() : cfg.homeProfile();

        // Managed on-pitch/bench for the sub dialog.
        List<Player> managedXi = managedIsHome ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = managedIsHome ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, minute, managedXi);
        List<LivePlayerResponse> onPitch = managed.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, managedProfile, true, false, managed.hobbled().contains(id))).toList();
        List<LivePlayerResponse> bench = managedBench.stream()
                .map(p -> livePlayer(p, cfg, managedProfile, false, managed.usedIn().contains(p.getId()), false)).toList();

        // Opponent on-pitch for the ratings sub-tab and the dual-formation pitch view.
        List<Player> opponentXi = managedIsHome ? cfg.awayXi() : cfg.homeXi();
        OnPitch opponent = onPitchOpponent(lm, timeline, minute, opponentXi);
        List<LivePlayerResponse> opponentOnPitch = opponent.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, opponentProfile, true, false, opponent.hobbled().contains(id))).toList();

        LiveTactics currentTactics = computeCurrentTactics(lm, cfg, minute);
        LiveTactics opponentTactics = computeOpponentTactics(lm, cfg, minute);

        // Live grades: computed from what has been revealed so far (goals/cards/minutes/score through
        // the committed minute). They start near 6.0 and diverge as the game unfolds; at minute 90
        // they equal the final full-time grades. Empty at kickoff (nobody has played yet).
        List<PlayerRating> playerRatings = managedRatings(lm, cfg, timeline, minute);
        List<PlayerRating> opponentPlayerRatings = opponentRatings(lm, cfg, timeline, minute);

        Club homeClub = clubs.get(fixture.getHomeClubId());
        Club awayClub = clubs.get(fixture.getAwayClubId());
        KitColors homeClubColors = new KitColors(homeClub.getPrimaryColor(), homeClub.getSecondaryColor());
        KitColors awayClubColors = new KitColors(awayClub.getPrimaryColor(), awayClub.getSecondaryColor());

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                homeClub.getName(), shortName(homeClub),
                awayClub.getName(), shortName(awayClub),
                managedIsHome, minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed, currentTactics, playerRatings,
                opponentOnPitch, opponentTactics, opponentPlayerRatings, homeClubColors, awayClubColors);
    }
```

- [ ] **Step 10: Write the new integration test**

Create `backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchOpponentVisibilityIT.java`:

```java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchOpponentVisibilityIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired FixtureRepository fixtureRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_opponent_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    @Test
    void opponentOnPitchHasElevenPlayersFromTheAiClubAfterKickoff() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        UUID aiClubId = lm.isManagedIsHome() ? fixture.getAwayClubId() : fixture.getHomeClubId();

        assertThat(state.opponentOnPitch()).hasSize(11);
        assertThat(state.opponentOnPitch()).allSatisfy(p -> {
            assertThat(p.role()).isNotBlank();
            assertThat(p.onPitch()).isTrue();
        });

        List<UUID> onPitchIds = state.opponentOnPitch().stream()
                .map(p -> UUID.fromString(p.playerId())).toList();
        List<Player> aiClubPlayers = playerRepository.findByClubId(aiClubId);
        assertThat(aiClubPlayers.stream().map(Player::getId)).containsAll(onPitchIds);
    }

    @Test
    void managedOnPitchPlayersAlsoCarryARole() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.onPitch()).hasSize(11);
        assertThat(state.onPitch()).allSatisfy(p -> assertThat(p.role()).isNotBlank());
    }

    @Test
    void opponentRatingsStartEmptyAtKickoffLikeTheManagedSide() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.playerRatings()).isEmpty();
        assertThat(state.opponentRatings()).isEmpty();
    }

    @Test
    void opponentTacticsHaveAValidFormationAndMentality() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.opponentTactics().formation()).isNotBlank();
        assertThat(state.opponentTactics().mentality()).isIn("DEFENSIVE", "BALANCED", "ATTACKING");
        assertThat(state.opponentTactics().pressing()).isIn("LOW", "MEDIUM", "HIGH");
        assertThat(state.opponentTactics().marking()).isIn("ZONAL", "MAN_TIGHT");
    }

    @Test
    void bothClubsExposeKitColors() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.homeClubColors().primary()).isNotBlank();
        assertThat(state.homeClubColors().secondary()).isNotBlank();
        assertThat(state.awayClubColors().primary()).isNotBlank();
        assertThat(state.awayClubColors().secondary()).isNotBlank();
    }
}
```

- [ ] **Step 11: Run the tests**

Run: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test --tests 'br.com.carvoeiromanager.match.*'`
Expected: BUILD SUCCESSFUL — the 5 new tests pass, and every pre-existing test in the package still passes unmodified (this change is purely additive to the managed side's output).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/br/com/carvoeiromanager/match/KitColors.java backend/src/main/java/br/com/carvoeiromanager/match/LivePlayerResponse.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchState.java backend/src/main/java/br/com/carvoeiromanager/match/LiveMatchService.java backend/src/test/java/br/com/carvoeiromanager/match/LiveMatchOpponentVisibilityIT.java
git commit -m "$(cat <<'EOF'
feat(match): expose the opponent's ratings, on-pitch roster, and tactics

Generalizes the four managed-side-only computations in LiveMatchService
(ratings, on-pitch roster, tactics, plus a new role field) into shared
core methods with managed/opponent delegates. Also adds each club's kit
colors to LiveMatchState. Purely additive — every existing field's value
for the managed side is unchanged.
EOF
)"
```

---

### Task 2: Frontend types + Atuações opponent sub-tab

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/features/match/LiveMatchPage.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Consumes: the backend fields from Task 1 (`opponentOnPitch`, `opponentTactics`, `opponentRatings`, `homeClubColors`, `awayClubColors`, and `role` on every `LivePlayer`).
- Produces: `LiveKitColors` TS type (`{ primary: string; secondary: string }`) and the updated `LiveMatchState`/`LivePlayer` TS types — Task 3 relies on these already existing, so it makes no further changes to `types.ts`.

- [ ] **Step 1: Add `role` to the `LivePlayer` TS type**

In `frontend/src/api/types.ts`, replace:

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
}
```

with:

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
}
```

- [ ] **Step 2: Add the five new fields to the `LiveMatchState` TS type**

In `frontend/src/api/types.ts`, replace:

```ts
export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  homeClubName: string
  homeClubShortName: string
  awayClubName: string
  awayClubShortName: string
  managedIsHome: boolean
  minute: number
  homeGoals: number
  awayGoals: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  currentTactics: LiveTactics
  /** Managed players' 0–10 grades; populated only at full time, empty otherwise. */
  playerRatings: PlayerRating[]
}
```

with:

```ts
export type LiveKitColors = { primary: string; secondary: string }

export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  homeClubName: string
  homeClubShortName: string
  awayClubName: string
  awayClubShortName: string
  managedIsHome: boolean
  minute: number
  homeGoals: number
  awayGoals: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  currentTactics: LiveTactics
  /** Managed players' 0–10 grades; populated only at full time, empty otherwise. */
  playerRatings: PlayerRating[]
  opponentOnPitch: LivePlayer[]
  opponentTactics: LiveTactics
  opponentRatings: PlayerRating[]
  homeClubColors: LiveKitColors
  awayClubColors: LiveKitColors
}
```

- [ ] **Step 3: Add the "Meu Time"/"Adversário" toggle state**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
  const [feedTab, setFeedTab] = useState<0 | 1>(0)   // 0 = Lances (feed), 1 = Atuações (grades)
```

with:

```tsx
  const [feedTab, setFeedTab] = useState<0 | 1>(0)   // 0 = Lances (feed), 1 = Atuações (grades)
  const [ratingsSide, setRatingsSide] = useState<0 | 1>(0)   // 0 = Meu Time, 1 = Adversário
```

- [ ] **Step 4: Wire the toggle into the Atuações tab content**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
      {feedTab === 1 && (
        state.playerRatings.length > 0 ? (
          <Box sx={{ mt: 1 }}>
            <PlayerRatingsList ratings={state.playerRatings} />
          </Box>
        ) : (
          <Typography sx={{ mt: 1, color: 'text.secondary' }}>
            As atuações aparecem conforme o jogo evolui.
          </Typography>
        )
      )}
```

with:

```tsx
      {feedTab === 1 && (
        <Box sx={{ mt: 1 }}>
          <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
            <Button size="small" variant={ratingsSide === 0 ? 'contained' : 'outlined'} onClick={() => setRatingsSide(0)}>
              Meu Time
            </Button>
            <Button size="small" variant={ratingsSide === 1 ? 'contained' : 'outlined'} onClick={() => setRatingsSide(1)}>
              Adversário
            </Button>
          </Stack>
          {(ratingsSide === 0 ? state.playerRatings : state.opponentRatings).length > 0 ? (
            <PlayerRatingsList ratings={ratingsSide === 0 ? state.playerRatings : state.opponentRatings} />
          ) : (
            <Typography sx={{ color: 'text.secondary' }}>
              As atuações aparecem conforme o jogo evolui.
            </Typography>
          )}
        </Box>
      )}
```

- [ ] **Step 5: Update the shared `STATE` mock and add the toggle test**

In `frontend/src/features/match/LiveMatchPage.test.tsx`, replace:

```tsx
const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma',
  awayClubName: 'Rival Futebol Clube', awayClubShortName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
  playerRatings: [],
}
```

with:

```tsx
const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma',
  awayClubName: 'Rival Futebol Clube', awayClubShortName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
  playerRatings: [],
  opponentOnPitch: [],
  opponentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' },
  opponentRatings: [],
  homeClubColors: { primary: '#f7d417', secondary: '#111111' },
  awayClubColors: { primary: '#003399', secondary: '#ffffff' },
}
```

Then add this test inside the `describe('LiveMatchPage', ...)` block, immediately after the existing
`'shows a hint in the Atuações tab before any grades exist'` test:

```tsx
  it('toggles between own and opponent ratings in the Atuações tab', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      playerRatings: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', minutesPlayed: 12, goals: 1, rating: 7.0 },
      ],
      opponentRatings: [
        { playerId: 'o1', name: 'Rival Camisa 10', position: 'MID', minutesPlayed: 12, goals: 0, rating: 6.2 },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    expect(await screen.findByText('Camisa 9')).toBeInTheDocument()
    expect(screen.queryByText('Rival Camisa 10')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /adversário/i }))
    expect(await screen.findByText('Rival Camisa 10')).toBeInTheDocument()
    expect(screen.queryByText('Camisa 9')).not.toBeInTheDocument()
  })
```

- [ ] **Step 6: Run the tests and typecheck**

Run: `cd frontend && npx tsc --noEmit && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: tsc clean; all tests in the file pass, including the new toggle test.

- [ ] **Step 7: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/features/match/LiveMatchPage.tsx frontend/src/features/match/LiveMatchPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(match): add an opponent sub-tab to Atuações

Meu Time / Adversário toggle reusing PlayerRatingsList unchanged for
both sides.
EOF
)"
```

---

### Task 3: New "Campo" dual-formation pitch tab

**Files:**
- Modify: `frontend/src/features/match/LiveMatchPage.tsx`
- Modify: `frontend/src/features/match/LiveMatchPage.test.tsx`

**Interfaces:**
- Consumes: `LiveMatchState`'s `opponentOnPitch`/`opponentTactics`/`homeClubColors`/`awayClubColors`/`onPitch`/`currentTactics` (Task 1/2). `Pitch`, `PlacedPlayer`, `TeamOnPitch` from `../pitch/Pitch` (unchanged, pre-existing).
- Produces: nothing consumed elsewhere — this is the final task.

- [ ] **Step 1: Add the `Pitch` import and a module-level team-building helper**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
import type { LiveEvent, LiveMatchState, LiveTacticsRequest } from '../../api/types'
import { MatchActionsDialog } from './MatchActionsDialog'
import type { SubPair } from './MatchActionsDialog'
import { PlayerRatingsList } from './PlayerRatingsList'

const EVENT_ICON: Record<LiveEvent['type'], string> = {
```

with:

```tsx
import type { LiveEvent, LiveMatchState, LiveTacticsRequest, LivePlayer, LiveKitColors } from '../../api/types'
import { MatchActionsDialog } from './MatchActionsDialog'
import type { SubPair } from './MatchActionsDialog'
import { PlayerRatingsList } from './PlayerRatingsList'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'

/** Builds one side's pitch view-model from its live on-pitch roster. Module-scope (no
 * component state) so it's freely reusable for either side without a closure. */
function buildTeam(players: LivePlayer[], formation: string, colors: LiveKitColors): TeamOnPitch {
  return {
    formation,
    kit: colors,
    showNames: true,
    players: players.map((p): PlacedPlayer => ({
      id: p.playerId,
      surname: p.name,
      position: p.position as PlacedPlayer['position'],
      fitness: p.fitness,
      badges: p.hobbled ? { injured: true } : undefined,
    })),
  }
}

const EVENT_ICON: Record<LiveEvent['type'], string> = {
```

- [ ] **Step 2: Add the 3rd tab**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
  const [feedTab, setFeedTab] = useState<0 | 1>(0)   // 0 = Lances (feed), 1 = Atuações (grades)
  const [ratingsSide, setRatingsSide] = useState<0 | 1>(0)   // 0 = Meu Time, 1 = Adversário
```

with:

```tsx
  const [feedTab, setFeedTab] = useState<0 | 1 | 2>(0)   // 0 = Lances, 1 = Atuações, 2 = Campo
  const [ratingsSide, setRatingsSide] = useState<0 | 1>(0)   // 0 = Meu Time, 1 = Adversário
```

Then replace:

```tsx
      <Tabs value={feedTab} onChange={(_, v) => setFeedTab(v as 0 | 1)} sx={{ mt: 2, minHeight: 40 }}>
        <Tab label="Lances" sx={{ minHeight: 40 }} />
        <Tab label="Atuações" sx={{ minHeight: 40 }} />
      </Tabs>
```

with:

```tsx
      <Tabs value={feedTab} onChange={(_, v) => setFeedTab(v as 0 | 1 | 2)} sx={{ mt: 2, minHeight: 40 }}>
        <Tab label="Lances" sx={{ minHeight: 40 }} />
        <Tab label="Atuações" sx={{ minHeight: 40 }} />
        <Tab label="Campo" sx={{ minHeight: 40 }} />
      </Tabs>
```

- [ ] **Step 3: Render the pitch**

In `frontend/src/features/match/LiveMatchPage.tsx`, replace:

```tsx
      <MatchActionsDialog
        key={String(actionsOpen)}
```

with:

```tsx
      {feedTab === 2 && (
        <Box sx={{ mt: 1 }}>
          <Pitch
            home={buildTeam(
              state.managedIsHome ? state.onPitch : state.opponentOnPitch,
              state.managedIsHome ? state.currentTactics.formation : state.opponentTactics.formation,
              state.homeClubColors,
            )}
            away={buildTeam(
              state.managedIsHome ? state.opponentOnPitch : state.onPitch,
              state.managedIsHome ? state.opponentTactics.formation : state.currentTactics.formation,
              state.awayClubColors,
            )}
          />
        </Box>
      )}

      <MatchActionsDialog
        key={String(actionsOpen)}
```

- [ ] **Step 4: Write the new test**

In `frontend/src/features/match/LiveMatchPage.test.tsx`, add this test immediately after the
`'toggles between own and opponent ratings in the Atuações tab'` test added in Task 2:

```tsx
  it('shows both teams on the Campo tab with their names and an injury badge', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      onPitch: [
        { playerId: 'p1', name: 'Goleiro Titular', position: 'GK', overall: 70, fitness: 100,
          hobbled: false, onPitch: true, used: false, eligible: true, role: 'GOALKEEPER' },
      ],
      opponentOnPitch: [
        { playerId: 'o1', name: 'Zagueiro Rival', position: 'DEF', overall: 65, fitness: 100,
          hobbled: true, onPitch: true, used: false, eligible: true, role: 'STOPPER' },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /campo/i }))
    expect(await screen.findByTestId('pitch')).toBeInTheDocument()
    expect(await screen.findByText('Goleiro Titular')).toBeInTheDocument()
    expect(screen.getByText('Zagueiro Rival')).toBeInTheDocument()
    expect(screen.getByLabelText('Lesionado')).toBeInTheDocument()
  })
```

- [ ] **Step 5: Run the tests and typecheck**

Run: `cd frontend && npx tsc --noEmit && npx vitest run src/features/match/LiveMatchPage.test.tsx`
Expected: tsc clean; all tests pass, including the new Campo tab test.

- [ ] **Step 6: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/match/LiveMatchPage.tsx frontend/src/features/match/LiveMatchPage.test.tsx
git commit -m "$(cat <<'EOF'
feat(match): add a live dual-formation Campo tab

Reuses Pitch.tsx's existing two-team support (home+away) unchanged —
this is the first production use of its away-side rendering path.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** all four generalized computations (Task 1), `role` field (Task 1), kit colors
  (Task 1), Atuações opponent sub-tab (Task 2), Campo pitch tab reusing `Pitch.tsx` unchanged
  (Task 3) — every spec section maps to a task.
- **Verified, not assumed:** every method/record/field referenced in this plan
  (`TacticalProfile.roles()`, `PlayerRole.defaultFor()`, `Club.getPrimaryColor()`/
  `getSecondaryColor()`, `LiveMatchService.requireLive()`, `Pitch.tsx`'s `data-testid="pitch"`,
  `PlayerToken`'s `aria-label="Lesionado"` injury badge, the exact tab-switching test pattern
  `findByRole('tab', {name: /atuações/i})`) was read directly from the current source during
  plan-writing.
- **Known judgment call:** `opponentBench` was deliberately dropped from the design (caught
  during the spec's own self-review) since neither new UI surface renders a bench for the
  opponent side — the pitch only draws the on-pitch 11, and the ratings sub-tab reuses
  `PlayerRatingsList` which already only cares about the flat `ratings` array, not bench
  membership.
- **Type consistency:** `LiveMatchState`'s five new field names in Task 1's Java record
  (`opponentOnPitch`, `opponentTactics`, `opponentRatings`, `homeClubColors`, `awayClubColors`)
  match exactly the TS field names added in Task 2, and Task 3's `buildTeam` helper consumes
  those same names with no renaming anywhere in the chain.
