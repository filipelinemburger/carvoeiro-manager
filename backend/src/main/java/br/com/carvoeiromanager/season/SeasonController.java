package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/seasons")
public class SeasonController {

    private final SeasonRolloverService rolloverService;
    private final SeasonHistoryService historyService;

    public SeasonController(SeasonRolloverService rolloverService, SeasonHistoryService historyService) {
        this.rolloverService = rolloverService;
        this.historyService = historyService;
    }

    /** Rolls the finished season into the next one (archive + age/retire/regen). */
    @PostMapping("/rollover")
    public ResponseEntity<RolloverResponse> rollover(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(rolloverService.rollover(saveGameId, userId));
    }

    /** Lists archived seasons (newest first). */
    @GetMapping
    public ResponseEntity<List<SeasonSummaryResponse>> history(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(historyService.getHistory(saveGameId, userId));
    }

    /** One archived season: full table + top scorers. */
    @GetMapping("/{seasonYear}")
    public ResponseEntity<SeasonDetailResponse> detail(
            @PathVariable UUID saveGameId, @PathVariable int seasonYear, @CurrentUser UUID userId) {
        return ResponseEntity.ok(historyService.getSeason(saveGameId, seasonYear, userId));
    }
}
