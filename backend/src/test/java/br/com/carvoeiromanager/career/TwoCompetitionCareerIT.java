package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
// Runs on the FULL seed, Série A included: it asserts what career creation really produces, so it must see the production seed.
// The test profile otherwise trims the seed to the player's own competitions to keep
// the suite fast — see carvoeiro.seed.competitions in application-test.yml.
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class TwoCompetitionCareerIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;

    @Test
    void careerHasFourCompetitionsWithCriciumaInBothManagedOnes() {
        User u = User.create("two_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());

        // Four competitions exist, but Criciúma plays only two of them: Série A and Série C run
        // in the background. The membership assertion at the bottom is what still says "in both".
        var comps = competitionRepository.findBySaveGameId(save.id());
        assertThat(comps).hasSize(4);
        Competition serieB = comps.stream()
                .filter(c -> c.getName().contains("Série B"))
                .findFirst().orElseThrow();
        Competition cat = comps.stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        assertThat(competitionClubRepository.findByCompetitionId(serieB.getId())).hasSize(20);
        assertThat(competitionClubRepository.findByCompetitionId(cat.getId())).hasSize(12);
        // Criciúma (managed) is a member of BOTH managed competitions — and of neither more:
        // it must not have picked up a membership row in the background Série A.
        UUID managed = save.managedClubId();
        assertThat(competitionClubRepository.findBySaveGameIdAndClubId(save.id(), managed)).hasSize(2);
        Competition serieA = comps.stream()
                .filter(c -> c.getName().contains("Série A"))
                .findFirst().orElseThrow();
        assertThat(competitionClubRepository.findByCompetitionId(serieA.getId()))
                .hasSize(20)
                .noneMatch(cc -> cc.getClubId().equals(managed));
    }
}
