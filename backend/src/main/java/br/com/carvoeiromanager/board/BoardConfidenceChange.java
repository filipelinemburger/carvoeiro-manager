package br.com.carvoeiromanager.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One reason confidence moved, so the card can say <em>why</em>. Season-scoped: purged at
 * rollover. {@code seq} orders rows within a save, like {@code news_item.seq}.
 */
@Entity
@Table(name = "board_confidence_changes")
public class BoardConfidenceChange {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private BoardChangeReason reason;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "confidence_after", nullable = false)
    private int confidenceAfter;

    /** JSON with what the label needs (opponent, score, position…); null when the reason says it all. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    protected BoardConfidenceChange() { }

    public static BoardConfidenceChange create(UUID saveGameId, LocalDate eventDate, int seq, BoardChangeReason reason,
                                               int delta, int confidenceAfter, String payload) {
        BoardConfidenceChange c = new BoardConfidenceChange();
        c.id = UUID.randomUUID();
        c.saveGameId = saveGameId;
        c.eventDate = eventDate;
        c.seq = seq;
        c.reason = reason;
        c.delta = delta;
        c.confidenceAfter = confidenceAfter;
        c.payload = payload;
        return c;
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public LocalDate getEventDate() { return eventDate; }
    public int getSeq() { return seq; }
    public BoardChangeReason getReason() { return reason; }
    public int getDelta() { return delta; }
    public int getConfidenceAfter() { return confidenceAfter; }
    public String getPayload() { return payload; }
}
