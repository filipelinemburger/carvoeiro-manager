// FitnessLifecycleIT.java
package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
class FitnessLifecycleIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("fitness_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH is played, skipping any TRANSFER stops. */
    private AdvanceResponse advanceOneMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.save().id(), c.userId());
            if ("MATCH".equals(r.type())) return r;
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void playersWhoFeatureLoseFitnessAndRecordTheMatchDate() {
        // Given a fresh career: everyone is fully fit with no last-match date
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        assertThat(playerRepository.findBySaveGameId(saveGameId))
                .allSatisfy(p -> {
                    assertThat(p.getFitness()).isEqualTo(100);
                    assertThat(p.getLastMatchDate()).isNull();
                });

        // When a few matchdays are played (guarantees the managed club has featured)
        for (int i = 0; i < 3; i++) advanceOneMatch(career);

        // Then at least some players have lost fitness and recorded a match date...
        List<Player> played = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getLastMatchDate() != null)
                .toList();
        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(p -> assertThat(p.getFitness()).isLessThan(100));

        // ...while players who never featured keep full stored fitness.
        assertThat(playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getLastMatchDate() == null))
                .allSatisfy(p -> assertThat(p.getFitness()).isEqualTo(100));
    }

    @Test
    void aFatiguedStarterEndsUpLessFitThanAnUnusedPlayer() {
        // Given a fresh career, capture the managed club's first-choice XI
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        List<UUID> firstXi = lineupService.bestXi(clubId).stream().map(Player::getId).toList();
        Player benched = playerRepository.findByClubId(clubId).stream()
                .filter(p -> !firstXi.contains(p.getId()))
                .findFirst().orElseThrow();

        // When several matchdays are played (the first-choice XI keeps starting via bestXi)
        for (int i = 0; i < 4; i++) advanceOneMatch(career);

        // Then a repeat starter is more fatigued than the player who never featured
        Player benchedReloaded = playerRepository.findById(benched.getId()).orElseThrow();
        List<Player> repeatStarters = playerRepository.findByClubId(clubId).stream()
                .filter(p -> firstXi.contains(p.getId()) && p.getLastMatchDate() != null)
                .toList();
        assertThat(repeatStarters).isNotEmpty();
        assertThat(repeatStarters).anySatisfy(starter ->
                assertThat(starter.getFitness()).isLessThan(benchedReloaded.getFitness()));
    }
}
