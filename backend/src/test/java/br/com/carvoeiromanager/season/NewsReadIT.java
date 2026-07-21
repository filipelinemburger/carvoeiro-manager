package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NewsReadIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;

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
}
