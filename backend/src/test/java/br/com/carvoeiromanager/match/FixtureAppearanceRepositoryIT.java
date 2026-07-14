package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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
class FixtureAppearanceRepositoryIT {

    @Autowired FixtureAppearanceRepository repository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired PlayerRepository playerRepository;

    @Test
    @Transactional
    void savesAndFindsByPlayerAndByFixtureAndDeletesByFixture() {
        // Create a career to get real save game, fixture, and player IDs
        User user = User.create("fixture_app_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        // Get a real fixture from the created career
        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(save.id());
        assertThat(fixtures).isNotEmpty();
        Fixture fixture = fixtures.get(0);

        // Get a real player from the managed club
        List<Player> players = playerRepository.findByClubId(save.managedClubId());
        assertThat(players).isNotEmpty();
        Player player = players.get(0);

        // Now use real IDs to create and test fixture appearance
        UUID fixtureId = fixture.getId();
        UUID playerId = player.getId();
        UUID clubId = save.managedClubId();

        repository.save(FixtureAppearance.create(fixtureId, playerId, clubId, 90, 3));

        List<FixtureAppearance> byPlayer = repository.findByPlayerId(playerId);
        assertThat(byPlayer).hasSize(1);
        assertThat(byPlayer.get(0).getMinutesPlayed()).isEqualTo(90);
        assertThat(byPlayer.get(0).getFouls()).isEqualTo(3);

        assertThat(repository.findByFixtureIdIn(List.of(fixtureId))).hasSize(1);

        repository.deleteByFixtureIdIn(List.of(fixtureId));
        assertThat(repository.findByPlayerId(playerId)).isEmpty();
    }
}
