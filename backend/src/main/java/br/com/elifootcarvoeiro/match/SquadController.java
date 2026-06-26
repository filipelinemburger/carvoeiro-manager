package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}")
public class SquadController {

    private final LineupService lineupService;

    public SquadController(LineupService lineupService) {
        this.lineupService = lineupService;
    }

    /**
     * Returns all players in the club's squad.
     */
    @GetMapping("/players")
    public ResponseEntity<SquadResponse> getSquad(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.getSquad(saveGameId, clubId, userId));
    }

    /**
     * Sets (upserts) the starting XI for the club within a save game.
     * Validates: exactly 11 starters, exactly 1 GK, all from this club.
     */
    @PutMapping("/lineup")
    public ResponseEntity<LineupResponse> setLineup(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @Valid @RequestBody SetLineupRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.setLineup(saveGameId, clubId, request, userId));
    }
}
