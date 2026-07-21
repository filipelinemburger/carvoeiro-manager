package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Read-side of the persisted news feed. Loads items newest-first, deserializes each stored
 * {@link AdvanceResponse} payload, and manages per-item read state. All entry points require
 * the save game to be owned by the caller.
 */
@Service
public class NewsService {

    private final SaveGameService saveGameService;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public NewsService(SaveGameService saveGameService,
                       NewsItemRepository newsItemRepository,
                       ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public PageResponse<NewsFeedItemResponse> getFeed(UUID saveGameId, UUID userId, int page, int size) {
        saveGameService.requireOwned(saveGameId, userId);
        List<NewsFeedItemResponse> responses = newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId)
                .stream()
                .map(item -> new NewsFeedItemResponse(item.getId(), item.isRead(), deserialize(item)))
                .toList();
        return PageResponse.of(responses, page, size);
    }

    public int unreadCount(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return newsItemRepository.countBySaveGameIdAndReadFalse(saveGameId);
    }

    @Transactional
    public void markRead(UUID saveGameId, UUID userId, UUID newsItemId) {
        saveGameService.requireOwned(saveGameId, userId);
        NewsItem item = newsItemRepository.findById(newsItemId)
                .filter(n -> n.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.NEWS_ITEM_NOT_FOUND, "News item not found"));
        item.markRead();
        newsItemRepository.save(item);
    }

    @Transactional
    public void markAllRead(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        newsItemRepository.markAllRead(saveGameId);
    }

    private AdvanceResponse deserialize(NewsItem item) {
        try {
            return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize news item " + item.getId(), e);
        }
    }
}
