package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
class LineupServiceBenchTest {

    @Autowired
    LineupService lineupService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    // --- Helpers ---

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID managedClubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("bench_test_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save);
    }

    private List<LineupStarter> buildValidStarters(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
    }

    private List<LineupStarter> buildBenchPlayers(UUID clubId, List<LineupStarter> starters, int count) {
        List<UUID> usedIds = starters.stream().map(LineupStarter::playerId).toList();
        return playerRepository.findByClubId(clubId).stream()
                .filter(p -> !usedIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(count)
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
    }

    // --- Tests ---

    @Test
    void benchIsPersistedAndReturned() {
        // Given a career with enough players
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        List<LineupStarter> threeBench = buildBenchPlayers(clubId, starters, 3);
        assertThat(threeBench).hasSize(3);

        SetLineupRequest req = new SetLineupRequest("4-3-3", starters, threeBench);

        // When
        lineupService.setLineup(saveGameId, clubId, req, userId);

        // Then - getLineup returns the bench
        LineupResponse resp = lineupService.getLineup(saveGameId, clubId, userId).orElseThrow();
        assertThat(resp.bench()).hasSize(3);
        assertThat(resp.bench()).extracting(LineupStarter::playerId)
                .doesNotContainAnyElementsOf(resp.starters().stream().map(LineupStarter::playerId).toList());
    }

    @Test
    void benchOfTenIsRejected() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        List<LineupStarter> tenBench = buildBenchPlayers(clubId, starters, 10);
        assertThat(tenBench).hasSize(10);

        SetLineupRequest req = new SetLineupRequest("4-3-3", starters, tenBench);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bench");
    }

    @Test
    void setLineupWithNullBenchStillWorks() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        SetLineupRequest req = new SetLineupRequest("4-3-3", starters, null);

        // When
        LineupResponse resp = lineupService.setLineup(saveGameId, clubId, req, userId);

        // Then - bench is empty (null normalised to List.of())
        assertThat(resp.bench()).isEmpty();
    }

    @Test
    void benchPlayerOverlappingStartersIsRejected() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        // Put a starter on the bench too
        List<LineupStarter> badBench = List.of(starters.get(0));
        SetLineupRequest req = new SetLineupRequest("4-3-3", starters, badBench);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bench");
    }

    @Test
    void suspendedBenchPlayerIsRejected() {
        // Given - a valid XI plus a bench that includes a suspended player
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        List<LineupStarter> bench = buildBenchPlayers(clubId, starters, 1);
        assertThat(bench).hasSize(1);

        // Suspend the bench player (same mechanism as LineupServiceIT.suspendedPlayerCannotBeSelected)
        Player victim = playerRepository.findById(bench.get(0).playerId()).orElseThrow();
        victim.addSuspension(1);
        playerRepository.save(victim);

        SetLineupRequest req = new SetLineupRequest("4-3-3", starters, bench);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.SUSPENDED_PLAYER);
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }
}
