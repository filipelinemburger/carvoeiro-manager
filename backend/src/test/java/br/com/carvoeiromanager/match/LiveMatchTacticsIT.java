package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchTacticsIT {

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
        User user = User.create("live_tactics_" + UUID.randomUUID(), "hash");
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
    void changeTacticsScheduledAndReflectedInTimelineAtFullTime() {
        liveMatchService.kickoff(saveGameId, userId);

        // Change tactics after kickoff (at minute 0, the change will be at minute 1)
        liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", null, null, "HIGH", "ZONAL", "4-3-3"));

        // Advance match all the way to FULL_TIME
        LiveMatchState state = null;
        for (int i = 0; i < 200; i++) {
            state = liveMatchService.advance(saveGameId, userId);
            if ("FULL_TIME".equals(state.status())) break;
        }
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("FULL_TIME");

        // A TACTICS_CHANGE event must appear in the timeline
        assertThat(state.events())
                .anyMatch(e -> "TACTICS_CHANGE".equals(e.type()));
    }

    @Test
    void currentTacticsReflectsScheduledChangeAfterAdvancing() {
        liveMatchService.kickoff(saveGameId, userId);

        // Change tactics: schedules a TimedTactics at minute 1
        liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", null, null, "HIGH", "ZONAL", "4-3-3"));

        // Advance past minute 1 so the change is committed
        LiveMatchState state = liveMatchService.advance(saveGameId, userId);
        // Keep advancing until minute >= 1 (may already be there)
        for (int i = 0; i < 90 && state.minute() < 1; i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }

        assertThat(state.currentTactics()).isNotNull();
        assertThat(state.currentTactics().mentality()).isEqualTo("ATTACKING");
        assertThat(state.currentTactics().pressing()).isEqualTo("HIGH");
        assertThat(state.currentTactics().marking()).isEqualTo("ZONAL");
        assertThat(state.currentTactics().formation()).isEqualTo("4-3-3");
    }

    @Test
    void liveGameStyleChangeReflectsFromItsMinuteOnward() {
        LiveMatchState atKickoff = liveMatchService.kickoff(saveGameId, userId);
        // The managed club's saved lineup starts on the neutral style (V36 default).
        assertThat(atKickoff.currentTactics().gameStyle()).isEqualTo("EQUILIBRADO");

        // Schedule a game-style change at minute 1.
        liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", "PRESSAO_ALTA", null, "HIGH", "ZONAL", "4-3-3"));

        // Advance past minute 1 so the change is committed.
        LiveMatchState state = liveMatchService.advance(saveGameId, userId);
        for (int i = 0; i < 90 && state.minute() < 1; i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(state.currentTactics().gameStyle()).isEqualTo("PRESSAO_ALTA");
    }

    @Test
    void liveAttackZoneChangeReflectsFromItsMinuteOnward() {
        LiveMatchState atKickoff = liveMatchService.kickoff(saveGameId, userId);
        // The managed club's saved lineup starts on the neutral zone (V37 default).
        assertThat(atKickoff.currentTactics().attackZone()).isEqualTo("MISTO");

        // Schedule an attack-zone change at minute 1.
        liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", null, "LATERAIS", "HIGH", "ZONAL", "4-3-3"));

        // Advance past minute 1 so the change is committed.
        LiveMatchState state = liveMatchService.advance(saveGameId, userId);
        for (int i = 0; i < 90 && state.minute() < 1; i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(state.currentTactics().attackZone()).isEqualTo("LATERAIS");
    }

    @Test
    void invalidAttackZoneThrowsApiException() {
        liveMatchService.kickoff(saveGameId, userId);

        assertThatThrownBy(() -> liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", null, "NOT_A_ZONE", "HIGH", "ZONAL", "4-3-3")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS);
                    assertThat(ex.getMessage()).isEqualTo("Zona de ataque inválida: NOT_A_ZONE");
                });
    }

    @Test
    void invalidGameStyleThrowsApiException() {
        liveMatchService.kickoff(saveGameId, userId);

        assertThatThrownBy(() -> liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", "NOT_A_STYLE", null, "HIGH", "ZONAL", "4-3-3")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS);
                    assertThat(ex.getMessage()).isEqualTo("Estilo de jogo inválido: NOT_A_STYLE");
                });
    }

    @Test
    void invalidMentalityThrowsApiException() {
        liveMatchService.kickoff(saveGameId, userId);

        assertThatThrownBy(() -> liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("BOGUS", null, null, "HIGH", "ZONAL", "4-3-3")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
    }

    @Test
    void invalidFormationThrowsApiException() {
        liveMatchService.kickoff(saveGameId, userId);

        assertThatThrownBy(() -> liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest("ATTACKING", null, null, "HIGH", "ZONAL", "9-9-9")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
    }
}
