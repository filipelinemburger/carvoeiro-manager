package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsApiIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("tac_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<LineupStarter> starters(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(p.getId(),
                        br.com.carvoeiromanager.player.PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
    }

    @Test
    void setAndGetTacticsRoundTrips() {
        Career c = createCareer();
        List<LineupStarter> xi = starters(c.clubId());
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());
        UUID taker = xi.get(10).playerId();

        TacticsResponse saved = lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("ATTACKING", "HIGH", "MAN_TIGHT", taker, taker, taker), c.userId());
        assertThat(saved.mentality()).isEqualTo("ATTACKING");
        assertThat(saved.pressing()).isEqualTo("HIGH");
        assertThat(saved.penaltyTakerId()).isEqualTo(taker);

        TacticsResponse loaded = lineupService.getTactics(c.id(), c.clubId(), c.userId());
        assertThat(loaded.marking()).isEqualTo("MAN_TIGHT");
        assertThat(loaded.freekickTakerId()).isEqualTo(taker);
    }

    @Test
    void getReturnsDefaultsWhenNoLineupYet() {
        Career c = createCareer();
        TacticsResponse loaded = lineupService.getTactics(c.id(), c.clubId(), c.userId());
        assertThat(loaded.mentality()).isEqualTo("BALANCED");
        assertThat(loaded.pressing()).isEqualTo("MEDIUM");
        assertThat(loaded.marking()).isEqualTo("ZONAL");
        assertThat(loaded.penaltyTakerId()).isNull();
    }

    @Test
    void invalidEnumIsRejected() {
        Career c = createCareer();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters(c.clubId()), null), c.userId());
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("WILD", "HIGH", "ZONAL", null, null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
    }

    @Test
    void settingTacticsBeforeLineupIsRejected() {
        Career c = createCareer();
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", null, null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.LINEUP_REQUIRED));
    }

    @Test
    void takerNotInXiIsRejected() {
        Career c = createCareer();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters(c.clubId()), null), c.userId());
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", UUID.randomUUID(), null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_TACTICS));
    }

    @Test
    void foreignUserCannotReadTactics() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> lineupService.getTactics(owner.id(), owner.clubId(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void foreignUserCannotSetTactics() {
        // Ownership is enforced before any other validation, so no lineup setup is
        // needed: the intruder must be rejected with 404 on the mutation endpoint too.
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> lineupService.setTactics(owner.id(), owner.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", null, null, null), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
