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
 * A FOUL event may or may not occur in any given simulated match (it's probabilistic), but it
 * must NEVER be visible in the live feed regardless — this asserts that invariant across a full
 * match's worth of advances, which is deterministic to check unconditionally.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchFoulVisibilityIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_foul_" + UUID.randomUUID(), "hash");
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
    void noEventInTheLiveFeedIsEverTypeFoul() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(state).isNotNull();
        assertThat(state.events()).noneMatch(e -> "FOUL".equals(e.type()));
    }
}
