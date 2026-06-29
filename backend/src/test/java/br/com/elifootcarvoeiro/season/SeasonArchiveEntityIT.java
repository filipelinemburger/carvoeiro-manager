package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonArchiveEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonStandingRepository seasonStandingRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;

    @Test
    void persistsAndReadsBackAnArchivedSeasonWithTableAndScorers() {
        // Given a user and save game
        User user = User.create("season_test_user", "hash");
        userRepository.save(user);

        SaveGame saveGame = SaveGame.create(user.getId(), 2026);
        saveGameRepository.save(saveGame);
        UUID saveGameId = saveGame.getId();
        UUID competitionId = UUID.randomUUID();
        UUID championId = UUID.randomUUID();
        UUID managedId = UUID.randomUUID();

        Season season = Season.create(saveGameId, competitionId, 2026, "Campeonato Catarinense",
                championId, "Campeao FC", managedId, "Criciúma EC", 3);
        seasonRepository.save(season);

        seasonStandingRepository.save(SeasonStanding.create(season.getId(), 1, championId, "Campeao FC",
                18, 14, 2, 2, 40, 12, 28, 44));
        seasonStandingRepository.save(SeasonStanding.create(season.getId(), 2, managedId, "Criciúma EC",
                18, 12, 4, 2, 35, 15, 20, 40));

        UUID scorerId = UUID.randomUUID();
        seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), 1, scorerId, "Artilheiro",
                championId, "Campeao FC", 21));
        seasonTopScorerRepository.save(SeasonTopScorer.create(season.getId(), 2, UUID.randomUUID(), "Segundo",
                managedId, "Criciúma EC", 14));

        // When reading back via the finders
        List<Season> seasons = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(saveGameId);
        List<SeasonStanding> table = seasonStandingRepository.findBySeasonIdOrderByPosition(season.getId());
        List<SeasonTopScorer> scorers = seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId());

        // Then the data round-trips with correct ordering
        assertThat(seasons).hasSize(1);
        assertThat(seasons.get(0).getChampionClubName()).isEqualTo("Campeao FC");
        assertThat(seasons.get(0).getManagedClubPosition()).isEqualTo(3);
        assertThat(seasonRepository.findBySaveGameIdAndSeasonYearOrderByCompetitionName(saveGameId, 2026)).isNotEmpty();

        assertThat(table).extracting(SeasonStanding::getPosition).containsExactly(1, 2);
        assertThat(table.get(0).getPoints()).isEqualTo(44);
        assertThat(scorers).extracting(SeasonTopScorer::getRank).containsExactly(1, 2);
        assertThat(scorers.get(0).getGoals()).isEqualTo(21);
    }
}
