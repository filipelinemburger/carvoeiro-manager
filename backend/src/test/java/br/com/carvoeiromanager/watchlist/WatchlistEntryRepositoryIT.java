package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistEntryRepositoryIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired WatchlistEntryRepository watchlistEntryRepository;

    private UUID createSave() {
        User user = User.create("watch_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    @Test
    void addFindExistsAndRemoveRoundTrip() {
        UUID saveGameId = createSave();
        Player anyPlayer = playerRepository.findBySaveGameId(saveGameId).get(0);
        UUID playerId = anyPlayer.getId();

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isFalse();

        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, playerId, Instant.now()));

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isTrue();
        assertThat(watchlistEntryRepository.findBySaveGameId(saveGameId))
                .extracting(WatchlistEntry::getPlayerId).containsExactly(playerId);

        watchlistEntryRepository.deleteBySaveGameIdAndPlayerId(saveGameId, playerId);
        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)).isFalse();
    }

    @Test
    void deletingThePlayerCascadesTheWatchlistEntry() {
        UUID saveGameId = createSave();
        Player anyPlayer = playerRepository.findBySaveGameId(saveGameId).get(0);
        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, anyPlayer.getId(), Instant.now()));

        playerRepository.deleteById(anyPlayer.getId());

        assertThat(watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, anyPlayer.getId()))
                .isFalse();
    }
}
