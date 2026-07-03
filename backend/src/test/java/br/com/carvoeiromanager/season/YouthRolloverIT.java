package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
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
class YouthRolloverIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    @Test
    void rolloverStocksTheManagedAcademyWithProspects() {
        User user = User.create("yr_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveId = save.id();
        UUID userId = user.getId();
        UUID clubId = save.managedClubId();

        // Finish the season so rollover is allowed
        int guard = 0;
        while (saveGameRepository.findById(saveId).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(saveId, userId);
        }

        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId)).isEmpty();

        RolloverResponse resp = rolloverService.rollover(saveId, userId);

        // Prospects are generated at ages 15-17 during the top-up (after the aging pass),
        // so they are stored at their generation age: 15-17.
        assertThat(resp.youthIntake()).isNotEmpty();
        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId)).isNotEmpty();
        assertThat(playerRepository.findByClubIdAndYouthProspectTrue(clubId))
                .allSatisfy(p -> { assertThat(p.isYouthProspect()).isTrue(); assertThat(p.getAge()).isBetween(15, 17); });
    }
}
