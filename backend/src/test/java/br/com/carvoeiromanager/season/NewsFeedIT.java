package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
    @Autowired NewsController newsController;
    @Autowired StaffRepository staffRepository;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("news_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career career = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        // The managed club now starts with a researched ASSISTENTE seeded at career creation
        // (Task 4). MatchPreviewService.maybePersistPreview is self-guarding on an ASSISTENTE
        // being hired, and every advance calls it unconditionally — so with the seeded row
        // present, most advances also write an extra MATCH_PREVIEW news item (not filtered by
        // nonWatchlistItems, which only excludes WATCHLIST). These tests are about the
        // one-item-per-advance invariant, not the assistant's opponent-analysis feature (that is
        // MatchPreviewServiceIT's job), so remove the seeded row to restore that premise.
        staffRepository.findByClubIdAndRole(career.clubId(), StaffRole.ASSISTENTE)
                .ifPresent(staffRepository::delete);
        return career;
    }

    /**
     * News items for {@code saveGameId}, newest-first, excluding {@code WATCHLIST} items.
     * TRANSFER advances unconditionally write standalone WATCHLIST OFFER_RECEIVED alerts
     * (WatchlistNotifier) alongside the advance's own item; these tests are about the
     * one-item-per-advance invariant, not the watchlist notifier, so they filter those out.
     */
    private List<NewsItem> nonWatchlistItems(UUID saveGameId) {
        return newsItemRepository.findBySaveGameIdOrderBySeqDesc(saveGameId).stream()
                .filter(i -> !"WATCHLIST".equals(i.getType()))
                .toList();
    }

    @Test
    void advancingWritesANewsItemAndFeedIsNewestFirst() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing once
        AdvanceResponse first = seasonAdvanceService.advance(c.id(), c.userId());

        // Then exactly one advance-owned news item is persisted (a TRANSFER advance may also
        // write extra WATCHLIST alerts; excluded via nonWatchlistItems — see its javadoc).
        List<NewsItem> afterFirst = nonWatchlistItems(c.id());
        assertThat(afterFirst).hasSize(1);
        NewsItem item = afterFirst.get(0);
        assertThat(item.getType()).isEqualTo(first.type());
        assertThat(item.getPayload()).contains("\"type\"");
        if (first.date() != null) {
            assertThat(item.getEventDate()).isNotNull();
        }

        // When advancing a second time
        AdvanceResponse second = seasonAdvanceService.advance(c.id(), c.userId());

        // Then a second advance-owned item is appended, newer than the first.
        List<NewsItem> afterSecond = nonWatchlistItems(c.id());
        assertThat(afterSecond).hasSize(2);
        // Most recent first: newest advance item is at index 0.
        assertThat(afterSecond.get(0).getType()).isEqualTo(second.type());
        assertThat(afterSecond.get(1).getType()).isEqualTo(first.type());
        assertThat(afterSecond.get(0).getSeq()).isGreaterThan(afterSecond.get(1).getSeq());
    }

    @Test
    void newsEndpointDeserializesPayloadsCorrectly() {
        // Given a career with two advances
        Career c = createCareer();
        AdvanceResponse r1 = seasonAdvanceService.advance(c.id(), c.userId());
        AdvanceResponse r2 = seasonAdvanceService.advance(c.id(), c.userId());

        // When querying the news items directly from the repository (may include unconditional
        // WATCHLIST OFFER_RECEIVED alerts written during a TRANSFER advance, alongside r1/r2's
        // own items)
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());

        // Then every stored payload round-trips back to a fully-formed AdvanceResponse — this is
        // exactly what NewsController.getNews does for the feed, and it must not throw even
        // for WATCHLIST payloads (AdvanceResponse.playerAlerts carries their content).
        items.forEach(i -> deserialize(i.getPayload()));

        // And the two advances' own items round-trip with matching type, newest-first.
        List<NewsItem> advanceItems = nonWatchlistItems(c.id());
        assertThat(advanceItems).hasSize(2);
        AdvanceResponse decoded0 = deserialize(advanceItems.get(0).getPayload());
        AdvanceResponse decoded1 = deserialize(advanceItems.get(1).getPayload());

        // Most recent advance (r2) is at index 0 (seq desc), and the decoded payloads
        // match the advances that produced them.
        assertThat(decoded0.type()).isEqualTo(r2.type());
        assertThat(decoded1.type()).isEqualTo(r1.type());
        // The decoded response carries the same non-null list fields the live response has
        // (records deserialize with all components populated, never null collections).
        assertThat(decoded0.results()).isNotNull();
        assertThat(decoded0.bookings()).isNotNull();
        assertThat(decoded0.newSuspensions()).isNotNull();
        assertThat(decoded0.newInjuries()).isNotNull();
    }

    private AdvanceResponse deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, AdvanceResponse.class);
        } catch (Exception e) {
            throw new AssertionError("Stored news payload is not valid AdvanceResponse JSON: " + payload, e);
        }
    }

    @Test
    void newsItemsPersistAcrossMultipleAdvances() {
        // Given a career advanced 5 times
        Career c = createCareer();
        for (int i = 0; i < 5; i++) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }

        // Then 5 advance-owned news items are stored. TRANSFER advances may also write
        // unconditional WATCHLIST OFFER_RECEIVED alerts interleaved at lower seqs; exclude
        // those (see nonWatchlistItems javadoc) since this test is about one item per advance.
        List<NewsItem> items = nonWatchlistItems(c.id());
        assertThat(items).hasSize(5);

        // The 5 advance items are strictly newest-first (each seq greater than the next).
        List<Integer> seqs = items.stream().map(NewsItem::getSeq).toList();
        for (int i = 0; i < seqs.size() - 1; i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i + 1));
        }
    }

    /** Persists {@code count} minimal MATCH news items (seq 1..count) without driving a real
     *  calendar advance — isolates the pagination math from how many events a season has.
     *  Clears any items already on {@code saveGameId} first: career creation itself can seed
     *  incidental unconditional WATCHLIST OFFER_RECEIVED alerts (initial AI bids on the new
     *  squad), which would otherwise inflate the totals this test asserts on. */
    private void persistNewsItems(UUID saveGameId, int count) {
        newsItemRepository.deleteBySaveGameId(saveGameId);
        AdvanceResponse minimal = new AdvanceResponse("MATCH", null, null, null, List.of(), List.of(),
                List.of(), List.of(), null, 0, false, null, null, null, null, null, null, null);
        for (int seq = 1; seq <= count; seq++) {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(minimal);
            } catch (Exception e) {
                throw new AssertionError("Failed to serialize test news payload", e);
            }
            newsItemRepository.save(NewsItem.create(saveGameId, seq, null, "MATCH", payload));
        }
    }

    @Test
    void newsFeedDefaultsToPageOneSizeTen() {
        Career c = createCareer();
        persistNewsItems(c.id(), 15);

        PageResponse<NewsFeedItemResponse> page = newsController.getNews(c.id(), c.userId(), 1, 10).getBody();

        assertThat(page.content()).hasSize(10);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(15);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void newsFeedSecondPageReturnsTheRemainingOlderItems() {
        Career c = createCareer();
        persistNewsItems(c.id(), 15);

        PageResponse<NewsFeedItemResponse> page = newsController.getNews(c.id(), c.userId(), 2, 10).getBody();

        assertThat(page.content()).hasSize(5);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(15);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void newsFeedOutOfRangePageReturnsEmptyContentWithCorrectTotals() {
        Career c = createCareer();
        persistNewsItems(c.id(), 3);

        PageResponse<NewsFeedItemResponse> page = newsController.getNews(c.id(), c.userId(), 5, 10).getBody();

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }
}
