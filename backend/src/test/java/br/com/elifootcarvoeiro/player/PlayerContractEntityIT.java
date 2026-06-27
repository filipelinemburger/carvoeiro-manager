package br.com.elifootcarvoeiro.player;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class PlayerContractEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private UUID newSave() {
        User user = User.create("contract_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    @Test
    void contractYearsRoundTrips() {
        UUID saveId = newSave();
        // Explicitly pick a club player; free agents (clubId=null) are also seeded at career creation.
        Player p = playerRepository.findBySaveGameId(saveId).stream()
                .filter(pl -> pl.getClubId() != null).findFirst().orElseThrow();
        p.setContractYears(4);
        playerRepository.saveAndFlush(p);

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getContractYears()).isEqualTo(4);
        assertThat(reloaded.isFreeAgent()).isFalse();
    }

    @Test
    void aFreeAgentHasNoClubAndPersists() {
        UUID saveId = newSave();
        Player fa = Player.create(saveId, null, "Livre Atleta", Position.MID, 28, "Brasil",
                60, 55, 65, 58, 10, 60, 60);
        playerRepository.saveAndFlush(fa);

        Player reloaded = playerRepository.findById(fa.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isNull();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(saveId))
                .extracting(Player::getId).contains(fa.getId());
    }
}
