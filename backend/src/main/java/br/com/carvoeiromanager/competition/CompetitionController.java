package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/competitions")
public class CompetitionController {

    private final StandingsService standingsService;
    private final TopScorersService topScorersService;

    public CompetitionController(StandingsService standingsService, TopScorersService topScorersService) {
        this.standingsService = standingsService;
        this.topScorersService = topScorersService;
    }

    /**
     * Returns the current league table for a competition, or for a single phase of it when
     * {@code phaseId} is given. Only played fixtures are counted; unplayed fixtures are ignored.
     * A knockout phase has no meaningful table — callers should render its ties instead.
     */
    @GetMapping("/{competitionId}/standings")
    public ResponseEntity<List<StandingRow>> getStandings(
            @PathVariable UUID saveGameId,
            @PathVariable UUID competitionId,
            @RequestParam(required = false) UUID phaseId,
            @CurrentUser UUID userId) {
        List<StandingRow> standings = standingsService.getStandings(saveGameId, competitionId, phaseId, userId);
        return ResponseEntity.ok(standings);
    }

    /** Current-season top scorers for a competition (used by the end-of-season screen). */
    @GetMapping("/{competitionId}/top-scorers")
    public ResponseEntity<List<TopScorerResponse>> getTopScorers(
            @PathVariable UUID saveGameId,
            @PathVariable UUID competitionId,
            @CurrentUser UUID userId) {
        List<TopScorerResponse> body = topScorersService
                .getTopScorers(saveGameId, competitionId, userId).stream()
                .map(r -> new TopScorerResponse(r.playerName(), r.clubName(), r.goals()))
                .toList();
        return ResponseEntity.ok(body);
    }
}
