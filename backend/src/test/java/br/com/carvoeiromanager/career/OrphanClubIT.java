package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hercílio Luz ("HER") is a deliberate product decision: it lives in the club pool with a full
 * squad but belongs to no competition, so it never gets a fixture. These tests prove every path
 * that touches "all clubs of the save" (finance, standings, rollover) degrades to empty for it
 * rather than throwing — without ever special-casing the "HER" short name in production code.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class OrphanClubIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureRepository fixtureRepository;

    private record Career(UUID userId, UUID saveGameId) { }

    private Career createCareer() {
        User user = User.create("orphan_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save.id());
    }

    private void advanceToSeasonEnd(Career c) {
        int guard = 0;
        while (saveGameRepository.findById(c.saveGameId()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.saveGameId(), c.userId());
        }
    }

    @Test
    void anOrphanClubIsCreatedWithItsSquadButNoFixtures() {
        Career c = createCareer();

        Club her = clubRepository.findBySaveGameIdAndShortName(c.saveGameId(), "HER").orElseThrow();
        assertThat(playerRepository.findByClubId(her.getId())).isNotEmpty();
        assertThat(fixtureRepository.findBySaveGameIdAndClubId(c.saveGameId(), her.getId())).isEmpty();
    }

    @Test
    void aFullSeasonRunsToCompletionWithAnOrphanClubPresent() {
        Career c = createCareer();

        advanceToSeasonEnd(c);

        // Finance, standings and rollover all ignore the orphan rather than failing on it.
        assertThat(saveGameRepository.findById(c.saveGameId()).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.FINISHED);
    }
}
