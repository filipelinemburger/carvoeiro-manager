package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Task 20: the live-match state exposes the round's OTHER fixtures' scores AS OF the
 * currently-committed minute (a read-only projection over the JSON pre-simulated at kickoff, counting
 * only goals with {@code minute <= committedMinute}), plus the competition id. The stored parallel
 * scores are crafted directly here — with goal events at known minutes — so the as-of-minute filtering
 * is asserted deterministically, independent of the simulation RNG.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchParallelScoresStateIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired LiveMatchRepository liveMatchRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired ObjectMapper objectMapper;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("lps_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());

        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    /** Overrides the stored parallel scores + committed minute of the live match, then fetches its state. */
    private LiveMatchState stateWith(String parallelScoresJson, int committedMinute) {
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        lm.setParallelScores(parallelScoresJson);
        lm.setCommittedMinute(committedMinute);
        liveMatchRepository.save(lm);
        return liveMatchService.getLive(saveGameId, userId).orElseThrow();
    }

    private String json(List<ParallelFixtureScore> scores) {
        try {
            return objectMapper.writeValueAsString(scores);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ParallelScore scoreOf(LiveMatchState state, UUID fixtureId) {
        return state.parallelScores().stream()
                .filter(ps -> ps.fixtureId().equals(fixtureId))
                .findFirst().orElseThrow();
    }

    @Test
    void parallelScoresCountOnlyGoalsThroughTheCommittedMinuteAndTickUp() {
        liveMatchService.kickoff(saveGameId, userId);

        UUID fx = UUID.randomUUID();
        List<SimulatedEvent> events = List.of(
                new SimulatedEvent(10, EventType.GOAL, true),
                new SimulatedEvent(20, EventType.YELLOW_CARD, false),   // non-goal: never counted
                new SimulatedEvent(30, EventType.GOAL, false),
                new SimulatedEvent(60, EventType.PENALTY_GOAL, true),   // goal-family via isGoal()
                new SimulatedEvent(85, EventType.GOAL, false));
        String stored = json(List.of(new ParallelFixtureScore(fx, "ABC", "XYZ", 2, 2, events, null, null)));

        // Kickoff (minute 0): nothing has happened yet.
        ParallelScore atStart = scoreOf(stateWith(stored, 0), fx);
        assertThat(atStart.homeGoals()).isZero();
        assertThat(atStart.awayGoals()).isZero();
        assertThat(atStart.homeShort()).isEqualTo("ABC");
        assertThat(atStart.awayShort()).isEqualTo("XYZ");

        // Advancing the committed minute strictly reveals more goals.
        assertThat(scoreOf(stateWith(stored, 15), fx)).extracting(ParallelScore::homeGoals, ParallelScore::awayGoals)
                .containsExactly(1, 0);
        assertThat(scoreOf(stateWith(stored, 35), fx)).extracting(ParallelScore::homeGoals, ParallelScore::awayGoals)
                .containsExactly(1, 1);
        assertThat(scoreOf(stateWith(stored, 70), fx)).extracting(ParallelScore::homeGoals, ParallelScore::awayGoals)
                .containsExactly(2, 1);

        // A goal exactly at the committed minute counts (minute <= committedMinute).
        assertThat(scoreOf(stateWith(stored, 85), fx)).extracting(ParallelScore::homeGoals, ParallelScore::awayGoals)
                .containsExactly(2, 2);
    }

    @Test
    void atFullTimeEachParallelScoreEqualsItsStoredFinal() {
        liveMatchService.kickoff(saveGameId, userId);

        UUID fxA = UUID.randomUUID();
        UUID fxB = UUID.randomUUID();
        ParallelFixtureScore a = new ParallelFixtureScore(fxA, "AAA", "BBB", 2, 1, List.of(
                new SimulatedEvent(5, EventType.GOAL, true),
                new SimulatedEvent(50, EventType.FREE_KICK_GOAL, true),
                new SimulatedEvent(70, EventType.GOAL, false)), null, null);
        ParallelFixtureScore b = new ParallelFixtureScore(fxB, "CCC", "DDD", 0, 1, List.of(
                new SimulatedEvent(88, EventType.CORNER_GOAL, false)), null, null);
        String stored = json(List.of(a, b));

        LiveMatchState state = stateWith(stored, LiveMatchService.MINUTES);

        ParallelScore projectedA = scoreOf(state, fxA);
        assertThat(projectedA.homeGoals()).isEqualTo(a.homeGoals());
        assertThat(projectedA.awayGoals()).isEqualTo(a.awayGoals());
        ParallelScore projectedB = scoreOf(state, fxB);
        assertThat(projectedB.homeGoals()).isEqualTo(b.homeGoals());
        assertThat(projectedB.awayGoals()).isEqualTo(b.awayGoals());
    }

    @Test
    void legacyNullParallelScoresYieldEmptyListWithNonNullCompetitionId() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        UUID competitionId = lm.getCompetitionId();

        LiveMatchState state = stateWith(null, 20);

        assertThat(state.parallelScores()).isEmpty();
        assertThat(state.competitionId()).isNotNull();
        assertThat(state.competitionId()).isEqualTo(competitionId.toString());
    }

    @Test
    void corruptParallelScoresJsonYieldsEmptyListWithoutBreakingTheLiveView() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        UUID competitionId = lm.getCompetitionId();

        // A malformed snapshot must never break getLive: the display projection swallows the parse
        // failure (logged at WARN) and shows no parallel scores; competitionId is still populated.
        LiveMatchState state = stateWith("{ not valid json", 30);

        assertThat(state.parallelScores()).isEmpty();
        assertThat(state.competitionId()).isEqualTo(competitionId.toString());
    }

    /** The round's fixtures other than the one the manager is playing, in fixture-row order. */
    private List<Fixture> otherFixturesOfTheRound() {
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        return fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, lm.getCompetitionId(), lm.getRound())
                .stream()
                .filter(f -> !f.getId().equals(lm.getFixtureId()))
                .toList();
    }

    private ParallelFixtureScore crafted(UUID fixtureId, String homeShort, String awayShort) {
        return new ParallelFixtureScore(fixtureId, homeShort, awayShort, 1, 0,
                List.of(new SimulatedEvent(10, EventType.GOAL, true)), null, null);
    }

    @Test
    void eachParallelScoreCarriesTheClubIdsOfItsOwnFixtureRow() {
        liveMatchService.kickoff(saveGameId, userId);

        List<Fixture> others = otherFixturesOfTheRound();
        assertThat(others).hasSizeGreaterThanOrEqualTo(2);

        // Stored in REVERSE fixture-row order, with shorts that deliberately do NOT name the real
        // clubs: a positional join, or one that leans on the snapshot's strings, cannot pass.
        List<ParallelFixtureScore> stored = new java.util.ArrayList<>(others.stream()
                .map(f -> crafted(f.getId(), "ZZZ", "YYY"))
                .toList());
        java.util.Collections.reverse(stored);

        LiveMatchState state = stateWith(json(stored), 30);

        for (Fixture fixture : others) {
            ParallelScore projected = scoreOf(state, fixture.getId());
            assertThat(projected.homeClubId())
                    .as("home club of fixture %s", fixture.getId())
                    .isEqualTo(fixture.getHomeClubId());
            assertThat(projected.awayClubId())
                    .as("away club of fixture %s", fixture.getId())
                    .isEqualTo(fixture.getAwayClubId());
        }
    }

    @Test
    void aParallelFixtureIdThatNoLongerResolvesKeepsItsScoreWithNullClubIds() {
        liveMatchService.kickoff(saveGameId, userId);

        Fixture real = otherFixturesOfTheRound().getFirst();
        UUID ghost = UUID.randomUUID();   // no fixture row will ever match this
        String stored = json(List.of(crafted(ghost, "ABC", "XYZ"), crafted(real.getId(), "DEF", "UVW")));

        LiveMatchState state = stateWith(stored, 30);

        // Degradation is per-entry: the unresolvable fixture keeps its score (the display must not
        // silently lose a result) but carries null ids, so no standings join can mis-attribute it.
        ParallelScore missing = scoreOf(state, ghost);
        assertThat(missing.homeClubId()).isNull();
        assertThat(missing.awayClubId()).isNull();
        assertThat(missing.homeShort()).isEqualTo("ABC");
        assertThat(missing.awayShort()).isEqualTo("XYZ");
        assertThat(missing.homeGoals()).isEqualTo(1);
        assertThat(missing.awayGoals()).isZero();

        // ...and it does not poison the entries that DO resolve.
        ParallelScore resolved = scoreOf(state, real.getId());
        assertThat(resolved.homeClubId()).isEqualTo(real.getHomeClubId());
        assertThat(resolved.awayClubId()).isEqualTo(real.getAwayClubId());
    }

    @Test
    void liveStateCarriesTheClubIdsOfTheFixtureBeingPlayed() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        Fixture fixture = fixtureRepository.findById(UUID.fromString(state.fixtureId())).orElseThrow();
        assertThat(state.homeClubId()).isEqualTo(fixture.getHomeClubId().toString());
        assertThat(state.awayClubId()).isEqualTo(fixture.getAwayClubId().toString());

        // The managed club must land on exactly the side managedIsHome claims — an independent
        // check that the two ids are not swapped.
        assertThat(state.managedIsHome() ? state.homeClubId() : state.awayClubId())
                .isEqualTo(managedClubId.toString());
        assertThat(state.managedIsHome() ? state.awayClubId() : state.homeClubId())
                .isNotEqualTo(managedClubId.toString());
    }
}
