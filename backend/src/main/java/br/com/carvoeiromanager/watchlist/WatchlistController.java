package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public ResponseEntity<List<WatchlistPlayerResponse>> list(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(watchlistService.getWatchlist(saveGameId, userId));
    }

    @PostMapping("/{playerId}")
    public ResponseEntity<Void> add(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        watchlistService.add(saveGameId, userId, playerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{playerId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        watchlistService.remove(saveGameId, userId, playerId);
        return ResponseEntity.noContent().build();
    }
}
