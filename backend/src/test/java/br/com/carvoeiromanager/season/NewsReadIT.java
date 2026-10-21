package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NewsReadIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired NewsService newsService;
    @Autowired NewsController newsController;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private record Career(UUID userId, UUID saveGameId) { }

    /** Fresh career with any incidental news (e.g. initial AI bids) cleared, so tests
     *  assert only on the items they create. */
    private Career createCareer() {
        User user = User.create("news_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        newsItemRepository.deleteBySaveGameId(save.id());
        return new Career(user.getId(), save.id());
    }

    @Test
    void newlyCreatedItemsAreUnreadAndCounted() {
        Career c = createCareer();
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 1, null, "MATCH", "{}"));
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 2, null, "MATCH", "{}"));

        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isEqualTo(2);
    }

    @Test
    void markAllReadClearsTheUnreadCount() {
        Career c = createCareer();
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 1, null, "MATCH", "{}"));
        newsItemRepository.save(NewsItem.create(c.saveGameId(), 2, null, "MATCH", "{}"));

        int updated = newsItemRepository.markAllRead(c.saveGameId());

        assertThat(updated).isEqualTo(2);
        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isZero();
    }

    /** Persists a MATCH item carrying a real (deserializable) AdvanceResponse payload. */
    private UUID persistMatch(UUID saveGameId, int seq, boolean read) {
        AdvanceResponse minimal = new AdvanceResponse("MATCH", null, null, null, List.of(), List.of(),
                List.of(), List.of(), null, 0, false, null, null, null, null, null, null, null, null);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(minimal);
        } catch (Exception e) {
            throw new AssertionError("Failed to serialize test payload", e);
        }
        NewsItem item = NewsItem.create(saveGameId, seq, null, "MATCH", payload);
        if (read) {
            item.markRead();
        }
        newsItemRepository.save(item);
        return item.getId();
    }

    @Test
    void getFeedReturnsWrappersCarryingIdReadFlagAndPayload() {
        Career c = createCareer();
        UUID unreadId = persistMatch(c.saveGameId(), 1, false);
        UUID readId = persistMatch(c.saveGameId(), 2, true);

        PageResponse<NewsFeedItemResponse> feed = newsService.getFeed(c.saveGameId(), c.userId(), 1, 10);

        assertThat(feed.content()).hasSize(2);
        // Newest-first: seq 2 (read) at index 0, seq 1 (unread) at index 1.
        assertThat(feed.content().get(0).id()).isEqualTo(readId);
        assertThat(feed.content().get(0).read()).isTrue();
        assertThat(feed.content().get(1).id()).isEqualTo(unreadId);
        assertThat(feed.content().get(1).read()).isFalse();
        assertThat(feed.content().get(0).payload().type()).isEqualTo("MATCH");
    }

    @Test
    void unreadCountCountsOnlyUnreadItems() {
        Career c = createCareer();
        persistMatch(c.saveGameId(), 1, false);
        persistMatch(c.saveGameId(), 2, false);
        persistMatch(c.saveGameId(), 3, true);

        assertThat(newsService.unreadCount(c.saveGameId(), c.userId())).isEqualTo(2);
    }

    @Test
    void markReadFlipsOneItemAndIsIdempotent() {
        Career c = createCareer();
        UUID id = persistMatch(c.saveGameId(), 1, false);

        newsService.markRead(c.saveGameId(), c.userId(), id);
        newsService.markRead(c.saveGameId(), c.userId(), id); // idempotent

        assertThat(newsItemRepository.countBySaveGameIdAndReadFalse(c.saveGameId())).isZero();
    }

    @Test
    void markReadOnAForeignItemThrowsNotFound() {
        Career owner = createCareer();
        Career other = createCareer();
        UUID foreignItem = persistMatch(other.saveGameId(), 1, false);

        assertThatThrownBy(() -> newsService.markRead(owner.saveGameId(), owner.userId(), foreignItem))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NEWS_ITEM_NOT_FOUND));
    }

    @Test
    void markAllReadViaServiceClearsUnread() {
        Career c = createCareer();
        persistMatch(c.saveGameId(), 1, false);
        persistMatch(c.saveGameId(), 2, false);

        newsService.markAllRead(c.saveGameId(), c.userId());

        assertThat(newsService.unreadCount(c.saveGameId(), c.userId())).isZero();
    }

    @Test
    void newsControllerExposesUnreadCountAndMarkRead() {
        Career c = createCareer();
        UUID id = persistMatch(c.saveGameId(), 1, false);

        assertThat(newsController.unreadCount(c.saveGameId(), c.userId()).getBody().count()).isEqualTo(1);

        newsController.markRead(c.saveGameId(), id, c.userId());

        assertThat(newsController.unreadCount(c.saveGameId(), c.userId()).getBody().count()).isZero();
    }
}
