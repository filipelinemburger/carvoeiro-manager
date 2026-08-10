package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
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

/**
 * Proves the {@link MatchStats} wired onto {@link LiveMatchState} behave correctly across a
 * real, advancing match. Because the timeline is never persisted — {@link LiveMatchService}
 * re-simulates it from the seed on every request and reveals only the committed minute —
 * statistics are recomputed per request and must hold at every revealed minute, not only at full
 * time. This also cross-checks that the statistics panel and the persisted per-player
 * {@code fixture_appearance} rows agree on fouls, since both are read off the same {@code FOUL}
 * events in the timeline.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchStatsIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;

    private UUID saveGameId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_stats_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        UUID managedClubId = save.managedClubId();

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

    @Test
    void statisticsInvariantsHoldAfterEveryAdvanceAllTheWayToFullTime() {
        liveMatchService.kickoff(saveGameId, userId);

        TeamStats previousHome = null;
        TeamStats previousAway = null;
        LiveMatchState state = null;

        for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);

            TeamStats home = state.stats().home();
            TeamStats away = state.stats().away();

            // shots >= shotsOnTarget >= goalsSoFarForThatSide, using the scoreboard the user
            // actually sees rather than re-deriving goals from the statistics themselves.
            assertThat(home.shots()).isGreaterThanOrEqualTo(home.shotsOnTarget());
            assertThat(home.shotsOnTarget()).isGreaterThanOrEqualTo(state.homeGoals());
            assertThat(away.shots()).isGreaterThanOrEqualTo(away.shotsOnTarget());
            assertThat(away.shotsOnTarget()).isGreaterThanOrEqualTo(state.awayGoals());

            assertThat(home.corners()).isGreaterThanOrEqualTo(0);
            assertThat(away.corners()).isGreaterThanOrEqualTo(0);
            assertThat(home.offsides()).isGreaterThanOrEqualTo(0);
            assertThat(away.offsides()).isGreaterThanOrEqualTo(0);
            assertThat(home.saves()).isGreaterThanOrEqualTo(0);
            assertThat(away.saves()).isGreaterThanOrEqualTo(0);

            assertThat(home.possession() + away.possession()).isEqualTo(100);

            // Every counter is monotonically non-decreasing relative to the previous advance's
            // snapshot — statistics may never go backwards as the clock runs. Possession is a
            // running average rather than a counter, so it is exempt (it may rise or fall as new
            // minutes are folded in) and is checked above only for the +100 split invariant.
            if (previousHome != null) {
                assertThat(home.shots()).isGreaterThanOrEqualTo(previousHome.shots());
                assertThat(home.shotsOnTarget()).isGreaterThanOrEqualTo(previousHome.shotsOnTarget());
                assertThat(home.saves()).isGreaterThanOrEqualTo(previousHome.saves());
                assertThat(home.corners()).isGreaterThanOrEqualTo(previousHome.corners());
                assertThat(home.offsides()).isGreaterThanOrEqualTo(previousHome.offsides());
                assertThat(home.fouls()).isGreaterThanOrEqualTo(previousHome.fouls());
                assertThat(home.yellowCards()).isGreaterThanOrEqualTo(previousHome.yellowCards());
                assertThat(home.redCards()).isGreaterThanOrEqualTo(previousHome.redCards());

                assertThat(away.shots()).isGreaterThanOrEqualTo(previousAway.shots());
                assertThat(away.shotsOnTarget()).isGreaterThanOrEqualTo(previousAway.shotsOnTarget());
                assertThat(away.saves()).isGreaterThanOrEqualTo(previousAway.saves());
                assertThat(away.corners()).isGreaterThanOrEqualTo(previousAway.corners());
                assertThat(away.offsides()).isGreaterThanOrEqualTo(previousAway.offsides());
                assertThat(away.fouls()).isGreaterThanOrEqualTo(previousAway.fouls());
                assertThat(away.yellowCards()).isGreaterThanOrEqualTo(previousAway.yellowCards());
                assertThat(away.redCards()).isGreaterThanOrEqualTo(previousAway.redCards());
            }

            previousHome = home;
            previousAway = away;
        }

        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("FULL_TIME");
    }

    @Test
    void foulsStatisticsTotalAtFullTimeMatchesPersistedFixtureAppearanceFoulsAndFoulEventsStayOffTheFeed() {
        liveMatchService.kickoff(saveGameId, userId);

        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("FULL_TIME");

        // FOUL events are counted in statistics but must never reach the commentary feed — the
        // same invariant LiveMatchFoulVisibilityIT proves, confirmed independently here.
        assertThat(state.events()).noneMatch(e -> "FOUL".equals(e.type()));

        int statsFoulsTotal = state.stats().home().fouls() + state.stats().away().fouls();
        UUID fixtureId = UUID.fromString(state.fixtureId());

        liveMatchService.finish(saveGameId, userId);

        int persistedFoulsTotal = fixtureAppearanceRepository.findByFixtureIdIn(List.of(fixtureId)).stream()
                .mapToInt(FixtureAppearance::getFouls)
                .sum();

        assertThat(statsFoulsTotal).isEqualTo(persistedFoulsTotal);
    }
}
