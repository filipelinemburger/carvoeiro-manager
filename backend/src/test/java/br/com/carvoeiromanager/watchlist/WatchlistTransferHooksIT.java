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
import br.com.carvoeiromanager.transfer.TransferExecutor;
import br.com.carvoeiromanager.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class WatchlistTransferHooksIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired WatchlistService watchlistService;
    @Autowired WatchlistEntryRepository watchlistEntryRepository;
    @Autowired TransferExecutor transferExecutor;
    @Autowired TransferService transferService;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("wh_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<PlayerAlert> watchlistNews(UUID saveGameId) {
        return newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId).stream()
                .filter(n -> "WATCHLIST".equals(n.getType()))
                .map(NewsItem::getPayload)
                .map(PlayerAlert::from)
                .toList();
    }

    // tiny payload sniffer — asserts on the JSON text rather than deserializing
    private record PlayerAlert(String raw) {
        static PlayerAlert from(String payload) { return new PlayerAlert(payload); }
        boolean has(String kind, String playerName) { return raw.contains(kind) && raw.contains(playerName); }
    }

    @Test
    void sellingAWatchedRivalToAnotherClubFiresSold() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        // Two AI clubs and a rival player on the first.
        List<Club> aiClubs = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).toList();
        Club sellerAi = aiClubs.get(0);
        Club buyerAi = aiClubs.get(1);
        Player rival = playerRepository.findByClubIdAndYouthProspectFalse(sellerAi.getId()).get(0);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        transferExecutor.execute(save, rival.getId(), buyerAi.getId(), sellerAi.getId(),
                new BigDecimal("1000000"), null, null);

        assertThat(watchlistNews(c.id())).anySatisfy(a -> assertThat(a.has("SOLD", rival.getName())).isTrue());
    }

    @Test
    void buyingAWatchedRivalYourselfFiresNoSoldAlert() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        Club sellerAi = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(c.clubId())).findFirst().orElseThrow();
        Player rival = playerRepository.findByClubIdAndYouthProspectFalse(sellerAi.getId()).get(0);
        watchlistService.add(c.id(), c.userId(), rival.getId());

        transferExecutor.execute(save, rival.getId(), c.clubId(), sellerAi.getId(),
                new BigDecimal("1000000"), null, null);

        assertThat(watchlistNews(c.id())).noneSatisfy(a -> assertThat(a.has("SOLD", rival.getName())).isTrue());
    }

    @Test
    void anIncomingOfferOnAnOwnPlayerFiresOfferReceivedUnconditionally() {
        Career c = createCareer();
        // Move into an open transfer window (pre-season) so incoming offers generate.
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 1, 20));
        saveGameRepository.save(save);

        // Force offers by transfer-listing the whole squad, then run several market days.
        playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).forEach(p ->
                p.listForTransfer(br.com.carvoeiromanager.player.ListedReason.OWNER));
        playerRepository.saveAll(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()));

        boolean any = false;
        for (int d = 0; d < 5 && !any; d++) {
            transferService.generateIncomingOffers(c.id(), LocalDate.of(2026, 1, 20).plusDays(d * 3L));
            any = watchlistNews(c.id()).stream().anyMatch(a -> a.raw.contains("OFFER_RECEIVED"));
        }
        assertThat(any).as("an OFFER_RECEIVED watchlist news item was produced").isTrue();
    }
}
