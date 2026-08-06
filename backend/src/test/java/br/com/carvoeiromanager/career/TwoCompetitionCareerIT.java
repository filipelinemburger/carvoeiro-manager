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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TwoCompetitionCareerIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;

    @Test
    void careerHasTwoCompetitionsWithCriciumaInBoth() {
        User u = User.create("two_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());

        var comps = competitionRepository.findBySaveGameId(save.id());
        assertThat(comps).hasSize(2);
        Competition serieB = comps.stream()
                .filter(c -> c.getName().contains("Série B"))
                .findFirst().orElseThrow();
        Competition cat = comps.stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        assertThat(competitionClubRepository.findByCompetitionId(serieB.getId())).hasSize(20);
        assertThat(competitionClubRepository.findByCompetitionId(cat.getId())).hasSize(12);
        // Criciúma (managed) is a member of BOTH.
        UUID managed = save.managedClubId();
        assertThat(competitionClubRepository.findBySaveGameIdAndClubId(save.id(), managed)).hasSize(2);
    }
}
