package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
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
class SquadContractInfoIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired LineupService lineupService;

    @Test
    void squadPlayersExposeContractYearsAndWageDemand() {
        User user = User.create("squadc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        SquadResponse squad = lineupService.getSquad(save.id(), save.managedClubId(), user.getId());

        assertThat(squad.players()).isNotEmpty();
        assertThat(squad.players()).allMatch(p -> p.contractYears() >= 1 && p.contractYears() <= 4);
        assertThat(squad.players()).allMatch(p -> p.wageDemand().signum() > 0);
    }
}
