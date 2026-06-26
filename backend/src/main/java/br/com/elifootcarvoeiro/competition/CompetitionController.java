package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/competitions")
public class CompetitionController {

    private final StandingsService standingsService;

    public CompetitionController(StandingsService standingsService) {
        this.standingsService = standingsService;
    }

    /**
     * Returns the current league table for a competition.
     * Only played fixtures are counted; unplayed fixtures are ignored.
     */
    @GetMapping("/{competitionId}/standings")
    public ResponseEntity<List<StandingRow>> getStandings(
            @PathVariable UUID saveGameId,
            @PathVariable UUID competitionId,
            @CurrentUser UUID userId) {
        List<StandingRow> standings = standingsService.getStandings(saveGameId, competitionId, userId);
        return ResponseEntity.ok(standings);
    }
}
