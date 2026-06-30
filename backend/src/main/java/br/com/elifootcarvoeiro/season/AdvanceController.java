package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.security.CurrentUser;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Date-driven season advance endpoint. Each POST processes exactly one calendar
 * event (a MATCH round or a TRANSFER bid window stop) and returns its outcome.
 * GET /news returns the persisted feed (most recent first) for a save game.
 */
@RestController
@RequestMapping("/api/v1/save-games")
public class AdvanceController {

    private final SeasonAdvanceService seasonAdvanceService;
    private final AdvancePreviewService advancePreviewService;
    private final NewsItemRepository newsItemRepository;
    private final SaveGameService saveGameService;
    private final ObjectMapper objectMapper;

    public AdvanceController(SeasonAdvanceService seasonAdvanceService,
                             AdvancePreviewService advancePreviewService,
                             NewsItemRepository newsItemRepository,
                             SaveGameService saveGameService,
                             ObjectMapper objectMapper) {
        this.seasonAdvanceService = seasonAdvanceService;
        this.advancePreviewService = advancePreviewService;
        this.newsItemRepository = newsItemRepository;
        this.saveGameService = saveGameService;
        this.objectMapper = objectMapper;
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

    @GetMapping("/{saveGameId}/news")
    public ResponseEntity<List<AdvanceResponse>> getNews(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId);
        List<AdvanceResponse> responses = items.stream()
                .map(item -> {
                    try {
                        return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize news item " + item.getId(), e);
                    }
                })
                .toList();
        return ResponseEntity.ok(responses);
    }
}
