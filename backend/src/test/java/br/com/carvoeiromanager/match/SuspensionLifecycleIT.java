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
class SuspensionLifecycleIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("susp_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH is played (skipping any TRANSFER stops). */
    private void advanceOneMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.save().id(), c.userId());
            if ("MATCH".equals(r.type())) {
                return;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void suspendedPlayerIsExcludedFromBestXi() {
        // Given a player in a club is suspended
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        Player victim = lineupService.bestXi(clubId, null, null).get(5);
        victim.addSuspension(1);
        playerRepository.save(victim);

        // When picking the best XI
        List<Player> xi = lineupService.bestXi(clubId, null, null);

        // Then the suspended player is not in it
        assertThat(xi).extracting(Player::getId).doesNotContain(victim.getId());
    }

    @Test
    void servingASuspensionClearsItAfterOneRound() {
        // Given a benched player (not in any XI) carrying a 1-match ban
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        UUID clubId = career.save().managedClubId();

        List<Player> xi = lineupService.bestXi(clubId, null, null);
        Player benched = playerRepository.findByClubId(clubId).stream()
                .filter(p -> xi.stream().noneMatch(s -> s.getId().equals(p.getId())))
                .findFirst().orElseThrow();
        benched.addSuspension(1);
        playerRepository.save(benched);

        // When a round is simulated (the benched player sits out)
        advanceOneMatch(career);

        // Then the ban is served
        Player reloaded = playerRepository.findById(benched.getId()).orElseThrow();
        assertThat(reloaded.getSuspensionRemaining()).isZero();
    }
}
