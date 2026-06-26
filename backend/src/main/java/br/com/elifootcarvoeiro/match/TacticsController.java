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
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/tactics")
public class TacticsController {

    private final LineupService lineupService;

    public TacticsController(LineupService lineupService) {
        this.lineupService = lineupService;
    }

    @GetMapping
    public ResponseEntity<TacticsResponse> getTactics(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.getTactics(saveGameId, clubId, userId));
    }

    @PutMapping
    public ResponseEntity<TacticsResponse> setTactics(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @Valid @RequestBody SetTacticsRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.setTactics(saveGameId, clubId, request, userId));
    }
}
