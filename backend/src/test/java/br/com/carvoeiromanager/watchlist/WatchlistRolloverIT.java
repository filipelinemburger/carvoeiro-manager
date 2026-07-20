package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.RolloverResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonRolloverService;
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
class WatchlistRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistService watchlistService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("wroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    @Test
    void aWatchedRivalWhoLapsesAtRolloverAppearsInWatchlistEvents() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        // A rival, age <= 33 so it survives rollover, forced to expire and watched.
        Player rival = all.stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getAge() <= 33)
                .findFirst().orElseThrow();
        rival.setContractYears(1);
        playerRepository.saveAll(all);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        assertThat(r.watchlistEvents())
                .anySatisfy(a -> {
                    assertThat(a.kind()).isEqualTo("FREE_AGENT");
                    assertThat(a.playerName()).isEqualTo(rival.getName());
                });
        assertThat(playerRepository.findById(rival.getId()).orElseThrow().isFreeAgent()).isTrue();
    }
}
