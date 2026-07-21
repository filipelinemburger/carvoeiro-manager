package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted news item produced by a season advance (MATCH, TRANSFER, or FINISHED).
 * The {@code payload} column stores the JSON-serialized {@link AdvanceResponse} so the
 * frontend can re-render each entry using the same timeline component it already uses for
 * the live advance.
 */
@Entity
@Table(name = "news_item")
public class NewsItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    protected NewsItem() { }

    private NewsItem(UUID id, UUID saveGameId, int seq, LocalDate eventDate,
                     String type, String payload, Instant createdAt) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.seq = seq;
        this.eventDate = eventDate;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static NewsItem create(UUID saveGameId, int seq, LocalDate eventDate,
                                  String type, String payload) {
        return new NewsItem(UUID.randomUUID(), saveGameId, seq, eventDate,
                type, payload, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public int getSeq() { return seq; }
    public LocalDate getEventDate() { return eventDate; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isRead() { return read; }

    public void markRead() { this.read = true; }
}
