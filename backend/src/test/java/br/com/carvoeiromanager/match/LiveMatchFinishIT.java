package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.season.AdvanceResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchFinishIT {

    @Autowired
    LiveMatchService liveMatchService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupService lineupService;

    @Autowired
    LiveMatchRepository liveMatchRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_finish_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        // Save a valid 11-player XI + 3 bench players for the managed club
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
    void finishRecordsTheResultSimulatesTheRoundAndClearsTheSession() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        String starterId = start.onPitch().get(0).playerId();

        LiveMatchState s = start;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) s = liveMatchService.advance(saveGameId, userId);

        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);

        assertThat(resp.type()).isEqualTo("MATCH");
        assertThat(resp.results()).isNotEmpty();
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isEmpty();
        // A player who started lost fitness (kicked off at 100).
        assertThat(playerRepository.findById(UUID.fromString(starterId)).orElseThrow().getFitness()).isLessThan(100);
    }

    @Test
    void finishBeforeFullTimeIsRejected() {
        liveMatchService.kickoff(saveGameId, userId);
        assertThatThrownBy(() -> liveMatchService.finish(saveGameId, userId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void gradesStartEmptyAppearDuringPlayAndAreCarriedIntoTheFinishResponse() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        // At kickoff (minute 0) nobody has played yet, so there are no grades.
        assertThat(start.playerRatings()).isEmpty();

        // One advance moves past the first event (<= minute 45): the managed XI now carry live grades.
        LiveMatchState mid = liveMatchService.advance(saveGameId, userId);
        assertThat(mid.minute()).isGreaterThanOrEqualTo(1);
        assertThat(mid.playerRatings()).isNotEmpty();
        assertThat(mid.playerRatings()).allSatisfy(r -> {
            assertThat(r.rating()).isBetween(3.0, 10.0);
            assertThat(r.minutesPlayed()).isGreaterThan(0);
            assertThat(r.name()).isNotBlank();
        });

        LiveMatchState s = mid;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) s = liveMatchService.advance(saveGameId, userId);
        assertThat(s.status()).isEqualTo("FULL_TIME");
        assertThat(s.playerRatings()).isNotEmpty();

        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);
        // The persisted news summary carries the same set of final grades.
        assertThat(resp.playerRatings()).hasSameSizeAs(s.playerRatings());
    }

    @Test
    void finishPopulatesShortClubNamesOnTheResult() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState s = null;
        for (int i = 0; i < 200 && (s == null || !"FULL_TIME".equals(s.status())); i++) {
            s = liveMatchService.advance(saveGameId, userId);
        }

        AdvanceResponse resp = liveMatchService.finish(saveGameId, userId);

        FixtureResultResponse userResult = resp.results().stream()
                .filter(r -> r.homeClubId().equals(managedClubId) || r.awayClubId().equals(managedClubId))
                .findFirst().orElseThrow();
        assertThat(userResult.homeClubShortName()).isNotBlank();
        assertThat(userResult.awayClubShortName()).isNotBlank();
    }
}
