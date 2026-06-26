package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games")
public class MatchController {

    private final RoundSimulationService roundSimulationService;

    public MatchController(RoundSimulationService roundSimulationService) {
        this.roundSimulationService = roundSimulationService;
    }

    /**
     * Advances the career to the next round by simulating all fixtures in that round.
     * Returns the round number, the fixture results, and whether the season is now finished.
     */
    @PostMapping("/{saveGameId}/advance")
    public ResponseEntity<SimulateRoundResponse> advanceRound(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        SimulateRoundResponse response = roundSimulationService.simulateNextRound(saveGameId, userId);
        return ResponseEntity.ok(response);
    }
}
