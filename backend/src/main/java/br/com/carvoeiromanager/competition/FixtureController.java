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
@RequestMapping("/api/v1/save-games/{saveGameId}/fixtures")
public class FixtureController {

    private final FixtureService fixtureService;

    public FixtureController(FixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    /**
     * Returns the managed club's season fixtures for a competition, ordered by round and
     * oriented from that club's point of view (home/away, goals for/against, opponent).
     */
    @GetMapping
    public ResponseEntity<List<FixtureEntryResponse>> getFixtures(
            @PathVariable UUID saveGameId,
            @RequestParam UUID competitionId,
            @CurrentUser UUID userId) {
        List<FixtureEntryResponse> fixtures = fixtureService.getFixtures(saveGameId, competitionId, userId);
        return ResponseEntity.ok(fixtures);
    }
}
