package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Task 19's core guarantee: the round's OTHER fixtures are pre-simulated (deterministically)
 * at live-match kickoff and REPLAYED — never re-simulated — at finish, so the persisted results are
 * byte-identical to what the legacy recompute-at-finish path would have produced. Also covers the
 * NULL {@code parallel_scores} compatibility case (in-flight matches from before this column existed).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchParallelSimulationIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired LiveMatchRepository liveMatchRepository;
    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired MatchEventRepository matchEventRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired ObjectMapper objectMapper;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_parallel_" + UUID.randomUUID(), "hash");
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

    private List<ParallelFixtureScore> parallelScoresOf(LiveMatch lm) {
        try {
            return objectMapper.readValue(lm.getParallelScores(), new TypeReference<List<ParallelFixtureScore>>() { });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Fixture> otherFixturesOfCurrentRound(UUID userFixtureId) {
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        List<Fixture> round = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, lm.getCompetitionId(), lm.getRound());
        return round.stream().filter(f -> !f.getId().equals(userFixtureId)).toList();
    }

    private void advanceToFullTime() {
        LiveMatchState s = liveMatchService.advance(saveGameId, userId);
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) {
            s = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(s.status()).isEqualTo("FULL_TIME");
    }

    @Test
    void kickoffPreSimulatesEveryOtherFixtureOfTheRound() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        UUID userFixtureId = UUID.fromString(state.fixtureId());

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        assertThat(lm.getParallelScores()).isNotNull();

        List<Fixture> others = otherFixturesOfCurrentRound(userFixtureId);
        List<ParallelFixtureScore> stored = parallelScoresOf(lm);

        assertThat(stored).extracting(ParallelFixtureScore::fixtureId)
                .containsExactlyInAnyOrderElementsOf(others.stream().map(Fixture::getId).toList());
        assertThat(stored).allSatisfy(s -> {
            assertThat(s.homeGoals()).isGreaterThanOrEqualTo(0);
            assertThat(s.awayGoals()).isGreaterThanOrEqualTo(0);
            assertThat(s.homeShort()).isNotBlank();
            assertThat(s.awayShort()).isNotBlank();
        });
    }

    @Test
    void finishPersistsFixtureResultsIdenticalToTheScoresPreSimulatedAtKickoff() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        UUID userFixtureId = UUID.fromString(state.fixtureId());

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        Map<UUID, ParallelFixtureScore> byFixtureId = parallelScoresOf(lm).stream()
                .collect(Collectors.toMap(ParallelFixtureScore::fixtureId, s -> s));

        advanceToFullTime();
        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);
        assertThat(resp.type()).isEqualTo("MATCH");

        for (UUID fixtureId : byFixtureId.keySet()) {
            ParallelFixtureScore expected = byFixtureId.get(fixtureId);
            Fixture persisted = fixtureRepository.findById(fixtureId).orElseThrow();

            assertThat(persisted.getHomeGoals()).as("home goals for fixture %s", fixtureId)
                    .isEqualTo(expected.homeGoals());
            assertThat(persisted.getAwayGoals()).as("away goals for fixture %s", fixtureId)
                    .isEqualTo(expected.awayGoals());

            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(fixtureId));
            long goalRows = events.stream().filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
            long expectedGoalEvents = expected.events().stream().filter(e -> e.type().isGoal()).count();
            assertThat(goalRows).as("goal-event rows for fixture %s", fixtureId).isEqualTo(expectedGoalEvents);

            List<FixtureAppearance> appearances = fixtureAppearanceRepository.findByFixtureIdIn(List.of(fixtureId));
            assertThat(appearances).as("appearances for fixture %s", fixtureId).hasSize(22);
        }
    }

    /**
     * The identity proof, in two independently-verified halves:
     *
     * <ol>
     *   <li><b>The stored value equals a fresh legacy recompute.</b> Right after kickoff — while the
     *       other fixtures' players are still untouched (their clubs are disjoint from the user's
     *       fixture and nothing mutates them during the live match) — recomputing each fixture via
     *       the pure {@code simulate()} path reproduces the stored {@code SimulatedMatch} exactly
     *       (score AND full event stream). This is precisely the value the LEGACY recompute-at-finish
     *       path would have produced, because it uses the same seed and the same untouched player
     *       state that path would read.</li>
     *   <li><b>The persisted value equals the stored value.</b> After finish REPLAYS the stored sims,
     *       the persisted Fixture scores and goal-event rows equal the stored scores/events.</li>
     * </ol>
     *
     * Transitively: persisted (replay) == stored == legacy recompute — so the replay path introduced
     * zero divergence from the legacy path for these seeds. (Recomputing AFTER finish is deliberately
     * NOT used as the probe: finish legitimately mutates the other clubs' players via recordAppearance,
     * so a post-finish recompute reads different inputs — which is exactly why pre-simulating and
     * replaying, rather than recomputing, is what guarantees identity.)
     */
    @Test
    void replayedResultsMatchWhatALegacyRecomputeWouldHaveProducedForTheSameSeeds() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        UUID userFixtureId = UUID.fromString(state.fixtureId());
        List<Fixture> others = otherFixturesOfCurrentRound(userFixtureId);

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        Map<UUID, ParallelFixtureScore> stored = parallelScoresOf(lm).stream()
                .collect(Collectors.toMap(ParallelFixtureScore::fixtureId, s -> s));

        // Half 1: the stored value == a fresh legacy recompute (untouched player state, same seed).
        for (Fixture f : others) {
            SimulatedMatch legacyRecompute = fixtureSimulator.simulatePure(f, saveGameId, managedClubId);
            ParallelFixtureScore s = stored.get(f.getId());
            assertThat(legacyRecompute.homeGoals()).isEqualTo(s.homeGoals());
            assertThat(legacyRecompute.awayGoals()).isEqualTo(s.awayGoals());
            assertThat(legacyRecompute.events()).isEqualTo(s.events());
        }

        advanceToFullTime();
        liveMatchService.finish(saveGameId, userId);

        // Half 2: the persisted (replayed) value == the stored value.
        for (Fixture f : others) {
            ParallelFixtureScore s = stored.get(f.getId());
            Fixture persisted = fixtureRepository.findById(f.getId()).orElseThrow();

            assertThat(persisted.getHomeGoals()).isEqualTo(s.homeGoals());
            assertThat(persisted.getAwayGoals()).isEqualTo(s.awayGoals());

            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(f.getId()));
            long persistedGoalRows = events.stream()
                    .filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
            long storedGoalEvents = s.events().stream().filter(e -> e.type().isGoal()).count();
            assertThat(persistedGoalRows).as("goal rows for fixture %s", f.getId()).isEqualTo(storedGoalEvents);
        }
    }

    @Test
    void nullParallelScoresFallsBackToTheLegacyRecomputePath() {
        liveMatchService.kickoff(saveGameId, userId);

        // Simulate an in-flight match created before this deploy: no parallel_scores snapshot.
        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        lm.setParallelScores(null);
        liveMatchRepository.save(lm);

        advanceToFullTime();
        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);

        assertThat(resp.type()).isEqualTo("MATCH");
        assertThat(resp.results()).isNotEmpty();
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isEmpty();
    }
}
