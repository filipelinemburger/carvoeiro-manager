package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.KnockoutTie;
import br.com.carvoeiromanager.competition.TieResolver;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DB-backed coverage for the LIVE engine's shootout path, closing the gap flagged during Task 9's
 * review: {@link FixtureSimulatorExtraTimeIT} covers the instant engine end-to-end, including
 * persistence, but nothing previously drove {@link LiveMatchService#finish} all the way through a
 * penalty shootout and asserted what actually lands in Postgres. A persistence-ordering bug on
 * exactly this kind of assumption already bit once in Task 8.
 *
 * <p>Forcing a shootout needs the tie level on aggregate not just at minute 90 (the {@link
 * FixtureSimulatorExtraTimeIT} trick — mirror leg 2's own natural 90-minute score onto leg 1, so
 * the crossover aggregate is trivially equal) but ALSO after the 30 minutes of extra time that
 * follows, which extra time itself may break. Since {@link LiveMatchEngine#simulate} is a pure,
 * deterministic function of {@link LiveMatchConfig} (itself keyed off the fixture's random id),
 * each attempt below PEEKS the full 120-minute outcome for a freshly-created leg 2 fixture before
 * committing to playing it out live; an attempt whose extra time decides the tie outright is
 * discarded (fixtures + the scratch {@link LiveMatch} row deleted) and retried with a new fixture
 * id, i.e. a new seed — exactly how {@link ShootoutTest} explores many seeds for a property that
 * doesn't hold for every one of them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchShootoutIT {

    private static final int MAX_ATTEMPTS = 40;
    private static final int LEG1_ROUND = -999; // never selected by any SeasonEvent; avoids round collisions

    @Autowired LiveMatchService liveMatchService;
    @Autowired LiveMatchEngine liveMatchEngine;
    @Autowired LiveMatchRepository liveMatchRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired MatchEventRepository matchEventRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;
    private UUID competitionId;
    private UUID opponentClubId;
    private int round;
    private LocalDate matchDate;

    @BeforeEach
    void setUp() {
        User user = User.create("live_shootout_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        // A valid 11-player XI + 3 bench players for the managed club (same recipe as
        // LiveMatchFinishIT), so kickoff() has a lineup to build a LiveMatchConfig from.
        List<Player> bestXi = lineupService.bestXi(managedClubId, null, null);
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

        // kickoff() always plays whatever fixture the managed club has in the NEXT unprocessed
        // MATCH event's round, in THAT event's own competition — createCareer() enrolls the
        // managed club in more than one competition, and CompetitionRepository#findBySaveGameId
        // makes no ordering guarantee, so the competition actually being played must come from
        // the event itself rather than an assumed index. Hijack that fixture: replace the league
        // pairing with our own knockout leg 2 for the same round, freeing up its original opponent.
        SeasonEvent nextEvent = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        competitionId = nextEvent.getCompetitionId();
        round = nextEvent.getRound();
        Fixture original = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, competitionId, round).stream()
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .findFirst().orElseThrow();
        opponentClubId = original.getHomeClubId().equals(managedClubId)
                ? original.getAwayClubId() : original.getHomeClubId();
        matchDate = original.getMatchDate();
        fixtureRepository.delete(original);
    }

    @Test
    void aLiveKnockoutSecondLegThatStaysLevelGoesToAShootoutAndTheTieResolves() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            UUID tieId = UUID.randomUUID();

            // Leg 2: the managed club hosts (the "higher seed" by this test's own convention).
            Fixture leg2 = Fixture.create(saveGameId, competitionId, round, managedClubId, opponentClubId, matchDate);
            leg2.setTieId(tieId);
            leg2.setLeg(2);
            fixtureRepository.save(leg2);

            // Peek leg 2's natural 90-minute score: kickoff with NO leg 1 recorded yet means
            // configFor's knockoutDecider is false, so the engine never extends past 90.
            liveMatchService.kickoff(saveGameId, userId);
            LiveMatch peekLm = liveMatchRepository.findBySaveGameId(saveGameId).orElseThrow();
            LiveMatchConfig peekCfg = liveMatchService.configFor(peekLm);
            LiveTimeline peekTimeline = liveMatchEngine.simulate(peekCfg);
            int naturalHomeGoals = peekTimeline.homeGoals();
            int naturalAwayGoals = peekTimeline.awayGoals();
            liveMatchRepository.deleteBySaveGameId(saveGameId);

            // Leg 1: the reverse fixture, scored with leg 2's OWN natural tuple (not swapped) —
            // the crossover trick that makes the aggregate trivially level at minute 90 regardless
            // of what that natural score actually is (see FixtureSimulatorExtraTimeIT).
            Fixture leg1 = Fixture.create(saveGameId, competitionId, LEG1_ROUND, opponentClubId, managedClubId,
                    matchDate.minusDays(7));
            leg1.recordResult(naturalHomeGoals, naturalAwayGoals);
            leg1.setTieId(tieId);
            leg1.setLeg(1);
            fixtureRepository.save(leg1);

            // Real kickoff: leg 1 now exists and is played, so configFor's knockoutDecider is
            // true from the start and minutes 1-90 replay byte-identical to the peek above (same
            // seed: same fixture id, same round).
            liveMatchService.kickoff(saveGameId, userId);
            LiveMatch lm = liveMatchRepository.findBySaveGameId(saveGameId).orElseThrow();
            LiveMatchConfig cfg = liveMatchService.configFor(lm);
            LiveTimeline timeline = liveMatchEngine.simulate(cfg);

            boolean wentToExtraTime = timeline.finalMinute() == MatchMath.EXTRA_TIME_END;
            boolean stillLevelAfterExtraTime = wentToExtraTime
                    && (timeline.homeGoals() + cfg.firstLegAwayGoals()) == (timeline.awayGoals() + cfg.firstLegHomeGoals());

            if (!stillLevelAfterExtraTime) {
                // Extra time settled it outright (or, vanishingly unlikely given the crossover
                // trick, minute 90 itself wasn't level) — this seed doesn't exercise a shootout.
                // Discard and retry with a fresh fixture id.
                liveMatchRepository.deleteBySaveGameId(saveGameId);
                fixtureRepository.delete(leg1);
                fixtureRepository.delete(leg2);
                continue;
            }

            // Found a seed that stays level through 120 minutes: play it out for real and let
            // finish() run the actual shootout + persistence path.
            liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME);
            liveMatchService.finish(saveGameId, userId);

            Fixture persistedLeg2 = fixtureRepository.findById(leg2.getId()).orElseThrow();
            assertThat(persistedLeg2.isPlayed()).isTrue();
            assertThat(persistedLeg2.getPenaltyHome()).isNotNull();
            assertThat(persistedLeg2.getPenaltyAway()).isNotNull();
            assertThat(persistedLeg2.getPenaltyHome()).isNotEqualTo(persistedLeg2.getPenaltyAway());

            List<MatchEvent> shootoutEvents = matchEventRepository.findByFixtureIdIn(List.of(leg2.getId())).stream()
                    .filter(e -> e.getType().startsWith("PENALTY_SHOOTOUT_")).toList();
            assertThat(shootoutEvents).isNotEmpty();
            long homeScoredKicks = shootoutEvents.stream()
                    .filter(e -> "PENALTY_SHOOTOUT_SCORED".equals(e.getType()) && e.getClubId().equals(managedClubId))
                    .count();
            long awayScoredKicks = shootoutEvents.stream()
                    .filter(e -> "PENALTY_SHOOTOUT_SCORED".equals(e.getType()) && e.getClubId().equals(opponentClubId))
                    .count();
            // The persisted penalty score must match the scored-kick tally in match_events —
            // exactly the kind of persistence-ordering assumption that broke once in Task 8.
            assertThat((long) persistedLeg2.getPenaltyHome()).isEqualTo(homeScoredKicks);
            assertThat((long) persistedLeg2.getPenaltyAway()).isEqualTo(awayScoredKicks);

            // The tie resolves through the shootout, and TieResolver agrees with what was persisted.
            List<Fixture> tieFixtures = fixtureRepository.findByTieId(tieId);
            assertThat(tieFixtures).hasSize(2);
            KnockoutTie tie = TieResolver.tiesOf(tieFixtures).get(0);
            UUID winner = TieResolver.winner(tie);
            UUID expectedWinner = persistedLeg2.getPenaltyHome() > persistedLeg2.getPenaltyAway()
                    ? persistedLeg2.getHomeClubId() : persistedLeg2.getAwayClubId();
            assertThat(winner).isEqualTo(expectedWinner);
            return;
        }
        fail("No seed among %d attempts stayed level through 120 minutes; widen MAX_ATTEMPTS".formatted(MAX_ATTEMPTS));
    }
}
