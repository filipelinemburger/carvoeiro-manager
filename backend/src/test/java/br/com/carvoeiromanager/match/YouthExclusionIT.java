package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class YouthExclusionIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    @Test
    void youthProspectsAreExcludedFromTheSquadAndBestXi() {
        User user = User.create("youth_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID clubId = save.managedClubId();

        int seniorCountBefore = lineupService.getSquad(save.id(), clubId, user.getId()).players().size();

        Player prospect = Player.create(save.id(), clubId, "Jóia da Base", Position.ATT, 16, "Brasil",
                60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        playerRepository.save(prospect);

        // The squad must not include the prospect...
        assertThat(lineupService.getSquad(save.id(), clubId, user.getId()).players())
                .noneMatch(pr -> pr.id().equals(prospect.getId()));
        assertThat(lineupService.getSquad(save.id(), clubId, user.getId()).players()).hasSize(seniorCountBefore);
        // ...and neither must bestXi.
        assertThat(lineupService.bestXi(clubId, null, null)).noneMatch(p -> p.getId().equals(prospect.getId()));
    }

    @Test
    void youthProspectAsStarterIsRejectedBySetLineup() {
        // Given
        User user = User.create("youth_starter_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();
        UUID clubId = save.managedClubId();
        UUID userId = user.getId();

        // Seed a prospect for this club
        Player prospect = Player.create(saveGameId, clubId, "Promessa Titular", Position.ATT, 17, "Brasil",
                65, 65, 60, 45, 10, 65, 60);
        prospect.markAsProspect(90);
        playerRepository.save(prospect);

        // Build an otherwise-valid 11 from bestXi (all seniors) and replace one starter with the prospect
        List<LineupStarter> starters = new ArrayList<>(
                lineupService.bestXi(clubId, null, null).stream()
                        .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                        .collect(Collectors.toList())
        );
        // Replace last outfield starter with the prospect (same role: ATT position → POACHER)
        starters.set(10, new LineupStarter(prospect.getId(),
                RoleArchetype.defaultFor(prospect.getDetailedPosition()).name()));

        SetLineupRequest req = new SetLineupRequest("4-4-2", starters, List.of());

        // When / Then — prospect in starting XI must be rejected
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP);
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void youthProspectOnBenchIsRejectedBySetLineup() {
        // Given
        User user = User.create("youth_bench_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();
        UUID clubId = save.managedClubId();
        UUID userId = user.getId();

        // Seed a prospect for this club
        Player prospect = Player.create(saveGameId, clubId, "Promessa Banco", Position.MID, 16, "Brasil",
                62, 58, 60, 50, 10, 62, 58);
        prospect.markAsProspect(85);
        playerRepository.save(prospect);

        // Build a valid senior starting XI
        List<LineupStarter> starters = lineupService.bestXi(clubId, null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());

        // Place the prospect only on the bench
        List<LineupStarter> bench = List.of(
                new LineupStarter(prospect.getId(), RoleArchetype.defaultFor(prospect.getDetailedPosition()).name())
        );

        SetLineupRequest req = new SetLineupRequest("4-4-2", starters, bench);

        // When / Then — prospect on bench must be rejected
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP);
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }
}
