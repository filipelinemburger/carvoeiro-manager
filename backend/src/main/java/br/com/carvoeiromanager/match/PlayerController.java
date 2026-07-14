package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/players/{playerId}")
public class PlayerController {

    private final LineupService lineupService;

    public PlayerController(LineupService lineupService) {
        this.lineupService = lineupService;
    }

    /**
     * Full player details for any player in the save game — a club's squad member or a free
     * agent. Used by contexts that only have a bare player id (transfer market, free agents)
     * and need the full attribute board to open the details dialog.
     */
    @GetMapping
    public ResponseEntity<PlayerResponse> getPlayer(
            @PathVariable UUID saveGameId,
            @PathVariable UUID playerId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.getPlayer(saveGameId, playerId, userId));
    }
}
