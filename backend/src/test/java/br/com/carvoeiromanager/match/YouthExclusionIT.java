package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(lineupService.bestXi(clubId)).noneMatch(p -> p.getId().equals(prospect.getId()));
    }
}
