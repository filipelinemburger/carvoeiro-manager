package br.com.carvoeiromanager.club;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubControllerIT {

    @Autowired ClubService clubService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("clubs_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void listsEveryClubOfTheSaveSortedByReputationDesc() {
        Career c = createCareer();

        List<ClubSummaryResponse> clubs = clubService.getClubs(c.id(), c.userId());

        int expected = clubRepository.findBySaveGameId(c.id()).size();
        assertThat(clubs).hasSize(expected);
        assertThat(clubs).isSortedAccordingTo(
                Comparator.comparingInt(ClubSummaryResponse::reputation).reversed());
        assertThat(clubs).anyMatch(club -> club.id().equals(c.clubId()));
        assertThat(clubs).allMatch(club -> club.name() != null && !club.name().isBlank());
        assertThat(clubs).allMatch(club -> club.squadSize() > 0);
    }

    @Test
    void squadSizeExcludesYouthProspects() {
        Career c = createCareer();
        int before = clubService.getClubs(c.id(), c.userId()).stream()
                .filter(club -> club.id().equals(c.clubId()))
                .findFirst().orElseThrow().squadSize();

        Player prospect = Player.create(c.id(), c.clubId(), "Promessa Clube", Position.MID,
                16, "Brasil", 55, 55, 55, 45, 10, 60, 55);
        prospect.markAsProspect(80);
        playerRepository.save(prospect);

        int after = clubService.getClubs(c.id(), c.userId()).stream()
                .filter(club -> club.id().equals(c.clubId()))
                .findFirst().orElseThrow().squadSize();

        assertThat(after).isEqualTo(before);
        assertThat(after).isEqualTo(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size());
    }

    @Test
    void foreignUserGets404ForAnotherUsersSave() {
        Career owner = createCareer();
        Career intruder = createCareer();

        assertThatThrownBy(() -> clubService.getClubs(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SAVE_GAME_NOT_FOUND);
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }
}
