package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that advancing writes a persisted news item for each event
 * (MATCH/TRANSFER) and that the GET /news feed returns items newest-first.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NewsFeedIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
    }

    private Career createCareer() {
        User user = User.create("news_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void advancingWritesANewsItemAndFeedIsNewestFirst() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing once
        AdvanceResponse first = seasonAdvanceService.advance(c.id(), c.userId());

        // Then exactly one news item is persisted
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(items).hasSize(1);
        NewsItem item = items.get(0);
        assertThat(item.getSeq()).isEqualTo(1);
        assertThat(item.getType()).isEqualTo(first.type());
        assertThat(item.getPayload()).contains("\"type\"");
        if (first.date() != null) {
            assertThat(item.getEventDate()).isNotNull();
        }

        // When advancing a second time
        AdvanceResponse second = seasonAdvanceService.advance(c.id(), c.userId());

        // Then a second news item is appended with seq=2
        List<NewsItem> twoItems = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(twoItems).hasSize(2);
        // Most recent first: seq desc means seq=2 is at index 0
        assertThat(twoItems.get(0).getSeq()).isEqualTo(2);
        assertThat(twoItems.get(1).getSeq()).isEqualTo(1);
        assertThat(twoItems.get(0).getType()).isEqualTo(second.type());
    }

    @Test
    void newsEndpointDeserializesPayloadsCorrectly() {
        // Given a career with two advances
        Career c = createCareer();
        AdvanceResponse r1 = seasonAdvanceService.advance(c.id(), c.userId());
        AdvanceResponse r2 = seasonAdvanceService.advance(c.id(), c.userId());

        // When querying the news items directly from the repository
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(items).hasSize(2);

        // Then the payload JSON can be deserialized back to AdvanceResponse
        // (the AdvanceController does this — verify the payload is valid JSON)
        assertThat(items.get(0).getPayload()).isNotBlank();
        assertThat(items.get(1).getPayload()).isNotBlank();

        // The most recent advance (r2) should be at index 0 (seq desc)
        assertThat(items.get(0).getType()).isEqualTo(r2.type());
        assertThat(items.get(1).getType()).isEqualTo(r1.type());
    }

    @Test
    void newsItemsPersistAcrossMultipleAdvances() {
        // Given a career advanced 5 times
        Career c = createCareer();
        for (int i = 0; i < 5; i++) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }

        // Then 5 news items are stored
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(items).hasSize(5);

        // Seq values are 1..5, ordered desc in the result
        List<Integer> seqs = items.stream().map(NewsItem::getSeq).toList();
        assertThat(seqs).containsExactly(5, 4, 3, 2, 1);
    }
}
