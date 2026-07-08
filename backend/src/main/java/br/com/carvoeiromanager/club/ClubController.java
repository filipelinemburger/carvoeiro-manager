package br.com.carvoeiromanager.club;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs")
public class ClubController {

    private final ClubService clubService;

    public ClubController(ClubService clubService) {
        this.clubService = clubService;
    }

    /**
     * Returns every club of the save game, sorted by reputation (highest first). Unpaginated —
     * a save game has a small, fixed club pool (~28).
     */
    @GetMapping
    public ResponseEntity<List<ClubSummaryResponse>> getClubs(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(clubService.getClubs(saveGameId, userId));
    }
}
