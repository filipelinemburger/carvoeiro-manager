package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Writes the board's news items. News payloads are serialized {@link AdvanceResponse}s (the feed
 * deserializes every item as one), so board news is the same sparse shape
 * {@code PhaseResolver.persistNews} writes: {@code type}, {@code date} and {@code board} filled,
 * everything else empty. No pt-BR text goes into any string field — the frontend renders the item
 * from {@code board} (spec §10).
 */
@Component
public class BoardNewsWriter {

    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;

    public BoardNewsWriter(NewsItemRepository newsItemRepository, ObjectMapper objectMapper) {
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    public void write(UUID saveGameId, LocalDate date, String type, BoardEventResponse board) {
        AdvanceResponse response = new AdvanceResponse(type, date.toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, 0, false, List.of(), List.of(),
                null, List.of(), List.of(), null, null, board);
        String payload = objectMapper.writeValueAsString(response);
        int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
        newsItemRepository.save(NewsItem.create(saveGameId, seq, date, type, payload));
    }
}
