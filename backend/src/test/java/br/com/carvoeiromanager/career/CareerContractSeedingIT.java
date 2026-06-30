package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.player.Player;
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
class CareerContractSeedingIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    @Test
    void everyPlayerGetsAStaggeredContractLength() {
        User user = User.create("seed_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        // Only club players have staggered contracts; free agents (clubId=null) have contractYears=0.
        var clubPlayers = playerRepository.findBySaveGameId(save.id()).stream()
                .filter(p -> p.getClubId() != null).toList();
        assertThat(clubPlayers).isNotEmpty();
        assertThat(clubPlayers).allMatch(p -> p.getContractYears() >= 1 && p.getContractYears() <= 4);
        // Staggered, not all identical.
        assertThat(clubPlayers.stream().map(Player::getContractYears).distinct().count()).isGreaterThan(1);
    }
}
