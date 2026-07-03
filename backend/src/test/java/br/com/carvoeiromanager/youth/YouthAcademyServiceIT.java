package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
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
class YouthAcademyServiceIT {

    @Autowired YouthAcademyService youthAcademyService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private UUID saveId, clubId, userId, prospectId;

    private void setup() {
        User user = User.create("yas_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();
        SaveGameResponse save = saveGameService.createCareer(userId);
        saveId = save.id();
        clubId = save.managedClubId();
        Player prospect = Player.create(saveId, clubId, "Promessa", Position.ATT, 16, "Brasil",
                60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        playerRepository.save(prospect);
        prospectId = prospect.getId();
    }

    @Test
    void listsProspectsWithStarsButNeverRawPotential() {
        setup();
        var list = youthAcademyService.listProspects(saveId, userId);
        assertThat(list).extracting(YouthProspectResponse::id).contains(prospectId);
        assertThat(list).allSatisfy(pr -> assertThat(pr.potentialStars()).isBetween(1, 5));
    }

    @Test
    void promotingMovesTheProspectIntoTheSeniorSquad() {
        setup();
        youthAcademyService.promote(saveId, prospectId, userId);
        Player promoted = playerRepository.findById(prospectId).orElseThrow();
        assertThat(promoted.isYouthProspect()).isFalse();
        assertThat(promoted.getContractYears()).isEqualTo(3);
        assertThat(lineupService.getSquad(saveId, clubId, userId).players())
                .anyMatch(pr -> pr.id().equals(prospectId));
        assertThat(youthAcademyService.listProspects(saveId, userId))
                .noneMatch(pr -> pr.id().equals(prospectId));
    }

    @Test
    void releasingRemovesTheProspect() {
        setup();
        youthAcademyService.release(saveId, prospectId, userId);
        assertThat(playerRepository.findById(prospectId)).isEmpty();
    }
}
