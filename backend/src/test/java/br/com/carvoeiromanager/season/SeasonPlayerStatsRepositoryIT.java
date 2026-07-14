package br.com.carvoeiromanager.season;

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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonPlayerStatsRepositoryIT {

    @Autowired SeasonPlayerStatsRepository repository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;

    @Test
    @Transactional
    void savesAndFindsByPlayer() {
        // Create a real save game for valid FK reference
        User user = User.create("season_stats_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        Season season = Season.create(save.id(), UUID.randomUUID(), 2026, "Catarinense",
                UUID.randomUUID(), "Campeão FC", UUID.randomUUID(), "Meu Time", 3);
        seasonRepository.save(season);

        UUID playerId = UUID.randomUUID();
        repository.save(SeasonPlayerStats.create(season.getId(), playerId, "Jogador Um",
                UUID.randomUUID(), "Meu Time", 30, 2500, 12, 8, 45, 5, 1));

        List<SeasonPlayerStats> found = repository.findByPlayerId(playerId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getGoals()).isEqualTo(12);
        assertThat(found.get(0).getAssists()).isEqualTo(8);
        assertThat(found.get(0).getSeasonId()).isEqualTo(season.getId());
    }
}
