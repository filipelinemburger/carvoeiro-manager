package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistListingHookIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired WatchlistService watchlistService;
    @Autowired TransferService transferService;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    @Test
    void listingAWatchedRivalFiresListed() {
        User user = User.create("wl_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 1, 20)); // open window
        saveGameRepository.save(save);

        // Watch every rival player, then run market days until AI lists someone we watch.
        List<Club> aiClubs = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).toList();
        aiClubs.forEach(club -> playerRepository.findByClubIdAndYouthProspectFalse(club.getId())
                .forEach(p -> watchlistService.add(c.id(), c.userId(), p.getId())));

        boolean any = false;
        for (int d = 0; d < 10 && !any; d++) {
            transferService.processMarketDay(c.id(), LocalDate.of(2026, 1, 20).plusDays(d * 3L));
            any = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id()).stream()
                    .filter(n -> "WATCHLIST".equals(n.getType()))
                    .map(NewsItem::getPayload)
                    .anyMatch(pl -> pl.contains("LISTED"));
        }
        assertThat(any).as("a LISTED watchlist news item was produced by AI listing").isTrue();
    }
}
