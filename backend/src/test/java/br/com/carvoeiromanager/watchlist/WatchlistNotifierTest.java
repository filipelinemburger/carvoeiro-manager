package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistNotifierTest {

    private final WatchlistEntryRepository watchlistRepo = mock(WatchlistEntryRepository.class);
    private final NewsItemRepository newsRepo = mock(NewsItemRepository.class);
    private final WatchlistNotifier notifier =
            new WatchlistNotifier(watchlistRepo, newsRepo, new ObjectMapper());

    private static Player player(UUID saveGameId) {
        return Player.create(saveGameId, UUID.randomUUID(), "Fulano", Position.ATT, 27,
                "Brasil", 70, 70, 60, 40, 8, 70, 70);
    }

    @Test
    void soldFiresOnlyForWatchedPlayers() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(false);
        notifier.notifySold(save, LocalDate.of(2026, 8, 1), p, "Rival FC", new BigDecimal("1000000"));
        verify(newsRepo, never()).save(any());

        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(true);
        when(newsRepo.countBySaveGameId(save)).thenReturn(4);
        notifier.notifySold(save, LocalDate.of(2026, 8, 1), p, "Rival FC", new BigDecimal("1000000"));

        ArgumentCaptor<NewsItem> captor = ArgumentCaptor.forClass(NewsItem.class);
        verify(newsRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("WATCHLIST");
        assertThat(captor.getValue().getPayload()).contains("SOLD").contains("Fulano").contains("Rival FC");
    }

    @Test
    void listedFiresOnlyForWatchedPlayers() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        when(watchlistRepo.existsBySaveGameIdAndPlayerId(save, p.getId())).thenReturn(false);
        notifier.notifyListed(save, LocalDate.of(2026, 8, 1), p, "Rival FC", "FINANCIAL");
        verify(newsRepo, never()).save(any());
    }

    @Test
    void offerReceivedFiresUnconditionally() {
        UUID save = UUID.randomUUID();
        Player p = player(save);
        // No watchlist stub — must NOT be consulted for own-player offers.
        when(newsRepo.countBySaveGameId(save)).thenReturn(0);
        notifier.notifyOfferReceived(save, LocalDate.of(2026, 8, 1), p, "Rich AI", new BigDecimal("500000"));

        ArgumentCaptor<NewsItem> captor = ArgumentCaptor.forClass(NewsItem.class);
        verify(newsRepo).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("OFFER_RECEIVED");
        verify(watchlistRepo, never()).existsBySaveGameIdAndPlayerId(any(), any());
    }
}
