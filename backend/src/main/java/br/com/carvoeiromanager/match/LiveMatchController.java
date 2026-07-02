package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.common.security.CurrentUser;
import br.com.carvoeiromanager.season.AdvanceResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/live-match")
public class LiveMatchController {

    private final LiveMatchService liveMatchService;

    public LiveMatchController(LiveMatchService liveMatchService) {
        this.liveMatchService = liveMatchService;
    }

    @PostMapping("/kickoff")
    public ResponseEntity<LiveMatchState> kickoff(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.kickoff(saveGameId, userId));
    }

    @GetMapping
    public ResponseEntity<LiveMatchState> live(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return liveMatchService.getLive(saveGameId, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/advance")
    public ResponseEntity<LiveMatchState> advance(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.advance(saveGameId, userId));
    }

    @PostMapping("/substitute")
    public ResponseEntity<LiveMatchState> substitute(
            @PathVariable UUID saveGameId,
            @Valid @RequestBody SubstituteRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.substitute(saveGameId, userId, request));
    }

    @PostMapping("/tactics")
    public ResponseEntity<LiveMatchState> tactics(
            @PathVariable UUID saveGameId,
            @Valid @RequestBody LiveTacticsRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.changeTactics(saveGameId, userId, request));
    }

    @PostMapping("/finish")
    public ResponseEntity<AdvanceResponse> finish(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(liveMatchService.finish(saveGameId, userId));
    }
}
