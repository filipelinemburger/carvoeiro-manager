package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
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
import java.util.Set;
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

    private Set<UUID> ids(List<Player> players) {
        return players.stream().map(Player::getId).collect(Collectors.toSet());
    }

    /**
     * Snapshots, for every "other" fixture of the round, the XI {@code resolveXi} will select —
     * both at kickoff (inside {@code simulatePure}) and again at finish (for attribution). Must be
     * captured BEFORE {@code finish()} runs: these clubs' players are untouched at that point (finish
     * mutates them via {@code recordAppearance}), so this is exactly the input both calls read.
     * {@code bestXi} is used directly (not {@code resolveXi}) because none of these fixtures can
     * involve the managed club — it is playing the live match itself.
     */
    private Map<UUID, Set<UUID>> expectedXiIdsBySide(List<Fixture> others, boolean home) {
        return others.stream().collect(Collectors.toMap(Fixture::getId,
                f -> ids(lineupService.bestXi(home ? f.getHomeClubId() : f.getAwayClubId(), f.getMatchDate()))));
    }

    /**
     * For every stored goal-type event, asserts a persisted MatchEvent at the same minute (and a
     * goal-counting type) attributed the goal to a player who actually belongs to the expected side's
     * XI. This is the content-based check Fix 3 requires: it would FAIL if the same number of goals
     * were persisted but credited to a DIFFERENT player than the XI the scoreline was computed from
     * (e.g. if bestXi's tie-break were non-deterministic and finish resolved a different XI than
     * kickoff did).
     */
    private void assertGoalAttributionMatchesExpectedXi(UUID fixtureId, List<SimulatedEvent> storedEvents,
                                                        Set<UUID> expectedHomeXiIds, Set<UUID> expectedAwayXiIds) {
        List<MatchEvent> persisted = matchEventRepository.findByFixtureIdIn(List.of(fixtureId));
        List<SimulatedEvent> storedGoals = storedEvents.stream().filter(e -> e.type().isGoal()).toList();

        long goalRows = persisted.stream().filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
        assertThat(goalRows).as("goal-event rows for fixture %s", fixtureId).isEqualTo(storedGoals.size());

        for (SimulatedEvent goal : storedGoals) {
            Set<UUID> expectedPool = goal.home() ? expectedHomeXiIds : expectedAwayXiIds;
            boolean attributedWithinExpectedXi = persisted.stream()
                    .anyMatch(me -> me.getMinute() == goal.minute()
                            && EventType.goalTypeNames().contains(me.getType())
                            && expectedPool.contains(me.getPlayerId()));
            assertThat(attributedWithinExpectedXi)
                    .as("goal at minute %d for fixture %s attributed to a player in the expected %s XI",
                            goal.minute(), fixtureId, goal.home() ? "home" : "away")
                    .isTrue();
        }
    }

    /**
     * Asserts persisted appearances match the expected XI by CONTENT — the exact player-id set on
     * each side — not just {@code size() == 22}. A regression that swapped in a different (but
     * equal-count) set of players would previously slip through; this fails it.
     */
    private void assertAppearancesMatchExpectedXi(UUID fixtureId, Fixture fixture,
                                                  Set<UUID> expectedHomeXiIds, Set<UUID> expectedAwayXiIds) {
        List<FixtureAppearance> appearances = fixtureAppearanceRepository.findByFixtureIdIn(List.of(fixtureId));
        assertThat(appearances).as("appearances for fixture %s", fixtureId).hasSize(22);

        Set<UUID> homeAppearanceIds = appearances.stream()
                .filter(a -> a.getClubId().equals(fixture.getHomeClubId()))
                .map(FixtureAppearance::getPlayerId).collect(Collectors.toSet());
        Set<UUID> awayAppearanceIds = appearances.stream()
                .filter(a -> a.getClubId().equals(fixture.getAwayClubId()))
                .map(FixtureAppearance::getPlayerId).collect(Collectors.toSet());

        assertThat(homeAppearanceIds).as("home XI membership for fixture %s", fixtureId)
                .isEqualTo(expectedHomeXiIds);
        assertThat(awayAppearanceIds).as("away XI membership for fixture %s", fixtureId)
                .isEqualTo(expectedAwayXiIds);
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
        List<Fixture> others = otherFixturesOfCurrentRound(userFixtureId);

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        Map<UUID, ParallelFixtureScore> byFixtureId = parallelScoresOf(lm).stream()
                .collect(Collectors.toMap(ParallelFixtureScore::fixtureId, s -> s));

        // Captured BEFORE finish (untouched player state) — see expectedXiIdsBySide's javadoc.
        Map<UUID, Set<UUID>> expectedHomeXiIds = expectedXiIdsBySide(others, true);
        Map<UUID, Set<UUID>> expectedAwayXiIds = expectedXiIdsBySide(others, false);

        advanceToFullTime();
        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);
        assertThat(resp.type()).isEqualTo("MATCH");

        for (Fixture fixture : others) {
            UUID fixtureId = fixture.getId();
            ParallelFixtureScore expected = byFixtureId.get(fixtureId);
            Fixture persisted = fixtureRepository.findById(fixtureId).orElseThrow();

            assertThat(persisted.getHomeGoals()).as("home goals for fixture %s", fixtureId)
                    .isEqualTo(expected.homeGoals());
            assertThat(persisted.getAwayGoals()).as("away goals for fixture %s", fixtureId)
                    .isEqualTo(expected.awayGoals());

            assertGoalAttributionMatchesExpectedXi(fixtureId, expected.events(),
                    expectedHomeXiIds.get(fixtureId), expectedAwayXiIds.get(fixtureId));
            assertAppearancesMatchExpectedXi(fixtureId, fixture,
                    expectedHomeXiIds.get(fixtureId), expectedAwayXiIds.get(fixtureId));
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

        // Captured BEFORE finish (untouched player state) — see expectedXiIdsBySide's javadoc.
        Map<UUID, Set<UUID>> expectedHomeXiIds = expectedXiIdsBySide(others, true);
        Map<UUID, Set<UUID>> expectedAwayXiIds = expectedXiIdsBySide(others, false);

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

        // Half 2: the persisted (replayed) value == the stored value, by CONTENT — per event
        // (minute, type, and for goals the attributed player id) and per appearance (exact XI
        // player-id set), not just scalar counts. This would fail if the same number of goals were
        // attributed to a DIFFERENT player than the XI the pre-simulated scoreline was computed from.
        for (Fixture f : others) {
            UUID fixtureId = f.getId();
            ParallelFixtureScore s = stored.get(fixtureId);
            Fixture persisted = fixtureRepository.findById(fixtureId).orElseThrow();

            assertThat(persisted.getHomeGoals()).isEqualTo(s.homeGoals());
            assertThat(persisted.getAwayGoals()).isEqualTo(s.awayGoals());

            assertGoalAttributionMatchesExpectedXi(fixtureId, s.events(),
                    expectedHomeXiIds.get(fixtureId), expectedAwayXiIds.get(fixtureId));
            assertAppearancesMatchExpectedXi(fixtureId, f,
                    expectedHomeXiIds.get(fixtureId), expectedAwayXiIds.get(fixtureId));
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
