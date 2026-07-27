package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
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
