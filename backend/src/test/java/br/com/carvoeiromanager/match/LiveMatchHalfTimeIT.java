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

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchHalfTimeIT {

    @Autowired
    LiveMatchService liveMatchService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupService lineupService;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_halftime_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

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
    }

    @Test
    void advancePassesThroughHalfTimeAndEventuallyReachesFullTime() {
        // Given: a live match has been kicked off.
        liveMatchService.kickoff(saveGameId, userId);

        // When: advancing until FULL_TIME.
        boolean sawHalfTime = false;
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);

            // Then: at minute 45 with no later events beyond 45 in the revealed list,
            // the status must be HALF_TIME.
            if ("HALF_TIME".equals(state.status())) {
                assertThat(state.minute()).isEqualTo(45);
                sawHalfTime = true;
            }
        }

        // Then: we saw HALF_TIME at minute 45 during the match.
        assertThat(sawHalfTime).as("HALF_TIME status must be observed at minute 45").isTrue();

        // Then: the match reaches FULL_TIME at minute 90.
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("FULL_TIME");
        assertThat(state.minute()).isEqualTo(90);
    }
}
