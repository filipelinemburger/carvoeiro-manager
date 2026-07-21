package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Date-driven season advance endpoint. Each POST processes exactly one calendar
 * event (a MATCH round or a TRANSFER bid window stop) and returns its outcome.
 */
@RestController
@RequestMapping("/api/v1/save-games")
public class AdvanceController {

    private final SeasonAdvanceService seasonAdvanceService;
    private final AdvancePreviewService advancePreviewService;

    public AdvanceController(SeasonAdvanceService seasonAdvanceService,
                             AdvancePreviewService advancePreviewService) {
        this.seasonAdvanceService = seasonAdvanceService;
        this.advancePreviewService = advancePreviewService;
    }

    @PostMapping("/{saveGameId}/advance")
    public ResponseEntity<AdvanceResponse> advance(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(seasonAdvanceService.advance(saveGameId, userId));
    }

    @GetMapping("/{saveGameId}/advance-preview")
    public ResponseEntity<AdvancePreviewResponse> advancePreview(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(advancePreviewService.preview(saveGameId, userId));
    }
}
