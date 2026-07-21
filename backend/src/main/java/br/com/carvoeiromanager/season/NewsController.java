package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Persisted news feed for a save game: paginated newest-first, plus per-item read state
 * and an unread count for the News nav badge.
 */
@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/news")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<NewsFeedItemResponse>> getNews(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(newsService.getFeed(saveGameId, userId, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<NewsUnreadCountResponse> unreadCount(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(new NewsUnreadCountResponse(newsService.unreadCount(saveGameId, userId)));
    }

    @PostMapping("/{newsItemId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID saveGameId,
            @PathVariable UUID newsItemId,
            @CurrentUser UUID userId) {
        newsService.markRead(saveGameId, userId, newsItemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            @PathVariable UUID saveGameId,
            @CurrentUser UUID userId) {
        newsService.markAllRead(saveGameId, userId);
        return ResponseEntity.noContent().build();
    }
}
