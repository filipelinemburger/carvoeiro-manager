package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/players/{playerId}")
public class PlayerStatsController {

    private final PlayerStatsService playerStatsService;

    public PlayerStatsController(PlayerStatsService playerStatsService) {
        this.playerStatsService = playerStatsService;
    }

    /** Season-by-season stats (matches, minutes, goals, assists, fouls, cards) for any player
     * in the save — the current in-progress season plus every archived past season. */
    @GetMapping("/stats")
    public ResponseEntity<PlayerStatsResponse> getStats(
            @PathVariable UUID saveGameId,
            @PathVariable UUID playerId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(playerStatsService.getStats(saveGameId, playerId, userId));
    }
}
