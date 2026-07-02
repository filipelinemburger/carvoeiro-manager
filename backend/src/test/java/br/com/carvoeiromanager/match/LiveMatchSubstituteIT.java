package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchSubstituteIT {

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
        User user = User.create("live_sub_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        // Save a valid 11-player XI + bench players for the managed club
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
    void substituteBringsOnABenchPlayerAndDecrementsRemaining() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        liveMatchService.advance(saveGameId, userId);   // reveal at least the first event

        UUID out = UUID.fromString(start.onPitch().get(6).playerId());
        UUID in = UUID.fromString(start.bench().get(0).playerId());
        LiveMatchState after = liveMatchService.substitute(saveGameId, userId, new SubstituteRequest(out, in));

        assertThat(after.subsRemaining()).isEqualTo(4);
        // The incoming player is now on the pitch once the clock reaches the sub minute.
        LiveMatchState later = after;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(later.status()); i++) later = liveMatchService.advance(saveGameId, userId);
        assertThat(later.events()).anyMatch(e -> "SUBSTITUTION".equals(e.type()) && in.toString().equals(e.playerInId()));
    }

    @Test
    void substituteRejectsAPlayerNotOnTheBench() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        UUID out = UUID.fromString(start.onPitch().get(0).playerId());
        UUID alsoOnPitch = UUID.fromString(start.onPitch().get(1).playerId());
        assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId, new SubstituteRequest(out, alsoOnPitch)))
                .isInstanceOf(ApiException.class);
    }
}
