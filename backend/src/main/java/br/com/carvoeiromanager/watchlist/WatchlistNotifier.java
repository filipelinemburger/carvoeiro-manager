package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes WATCHLIST news items when something happens to a watched player. Mirrors
 * PreContractService.persistPreContractNews: an independent news_item writer with an
 * AdvanceResponse-shaped payload ({type, date, playerAlerts}) the frontend renders with
 * the same card it uses for advances. SOLD/LISTED are gated on the watchlist;
 * OFFER_RECEIVED is unconditional (the player is the manager's own).
 */
@Service
public class WatchlistNotifier {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public WatchlistNotifier(WatchlistEntryRepository watchlistEntryRepository,
                             NewsItemRepository newsItemRepository,
                             ObjectMapper objectMapper) {
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public boolean isWatched(UUID saveGameId, UUID playerId) {
        return watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId);
    }

    public void notifySold(UUID saveGameId, LocalDate date, Player player,
                           String newClubName, BigDecimal fee) {
        if (!isWatched(saveGameId, player.getId())) {
            return;
        }
        persist(saveGameId, date, new PlayerAlertResponse(
                "SOLD", player.getName(), newClubName, fee == null ? null : fee.toPlainString()));
    }

    public void notifyListed(UUID saveGameId, LocalDate date, Player player,
                             String clubName, String reasonDetail) {
        if (!isWatched(saveGameId, player.getId())) {
            return;
        }
        persist(saveGameId, date, new PlayerAlertResponse(
                "LISTED", player.getName(), clubName, reasonDetail));
    }

    public void notifyOfferReceived(UUID saveGameId, LocalDate date, Player player,
                                    String biddingClubName, BigDecimal amount) {
        // Unconditional — the player is the manager's own; no watchlist check.
        persist(saveGameId, date, new PlayerAlertResponse(
                "OFFER_RECEIVED", player.getName(), biddingClubName,
                amount == null ? null : amount.toPlainString()));
    }

    private void persist(UUID saveGameId, LocalDate date, PlayerAlertResponse alert) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "WATCHLIST",
                    "date", date.toString(),
                    "playerAlerts", List.of(alert)));
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, date, "WATCHLIST", payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize watchlist news item", e);
        }
    }
}
