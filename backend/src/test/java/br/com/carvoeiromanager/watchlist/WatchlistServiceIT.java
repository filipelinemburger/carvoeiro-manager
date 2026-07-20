package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistServiceIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistService watchlistService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("wsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player anyRival(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .findFirst().orElseThrow();
    }

    @Test
    void addAndListAndRemoveARivalPlayer() {
        Career c = createCareer();
        Player rival = anyRival(c);

        watchlistService.add(c.id(), c.userId(), rival.getId());

        List<WatchlistPlayerResponse> list = watchlistService.getWatchlist(c.id(), c.userId());
        assertThat(list).extracting(WatchlistPlayerResponse::playerId).contains(rival.getId());
        assertThat(list).allSatisfy(w -> assertThat(w.clubName()).isNotBlank());

        watchlistService.remove(c.id(), c.userId(), rival.getId());
        assertThat(watchlistService.getWatchlist(c.id(), c.userId()))
                .extracting(WatchlistPlayerResponse::playerId).doesNotContain(rival.getId());
    }

    @Test
    void addingIsIdempotent() {
        Career c = createCareer();
        Player rival = anyRival(c);
        watchlistService.add(c.id(), c.userId(), rival.getId());
        watchlistService.add(c.id(), c.userId(), rival.getId()); // no throw, no duplicate
        assertThat(watchlistService.getWatchlist(c.id(), c.userId())).hasSize(1);
    }

    @Test
    void cannotWatchAnOwnSquadPlayer() {
        Career c = createCareer();
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);

        assertThatThrownBy(() -> watchlistService.add(c.id(), c.userId(), mine.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.CANNOT_WATCH_OWN_PLAYER));
    }

    @Test
    void aFreeAgentCanBeWatchedAndShowsAsFreeAgent() {
        Career c = createCareer();
        Player fa = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).get(0);
        watchlistService.add(c.id(), c.userId(), fa.getId());
        assertThat(watchlistService.getWatchlist(c.id(), c.userId()))
                .anySatisfy(w -> {
                    assertThat(w.playerId()).isEqualTo(fa.getId());
                    assertThat(w.freeAgent()).isTrue();
                });
    }
}
