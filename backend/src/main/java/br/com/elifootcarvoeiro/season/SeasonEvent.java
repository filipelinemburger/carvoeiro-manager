package br.com.elifootcarvoeiro.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** One dated entry in a save's season calendar (a MATCH round or a TRANSFER bid window stop). */
@Entity
@Table(name = "season_event")
public class SeasonEvent {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "event_date", nullable = false) private LocalDate eventDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12) private SeasonEventType type;
    @Column(name = "competition_id") private UUID competitionId;
    @Column(name = "round") private Integer round;
    @Column(name = "sequence", nullable = false) private int sequence;
    @Column(name = "processed", nullable = false) private boolean processed;

    protected SeasonEvent() { }

    private SeasonEvent(UUID id, UUID saveGameId, LocalDate eventDate, SeasonEventType type,
                        UUID competitionId, Integer round, int sequence) {
        this.id = id; this.saveGameId = saveGameId; this.eventDate = eventDate; this.type = type;
        this.competitionId = competitionId; this.round = round; this.sequence = sequence;
        this.processed = false;
    }

    public static SeasonEvent createMatch(UUID saveGameId, LocalDate eventDate, UUID competitionId,
                                          int round, int sequence) {
        return new SeasonEvent(UUID.randomUUID(), saveGameId, eventDate, SeasonEventType.MATCH,
                competitionId, round, sequence);
    }

    public static SeasonEvent createTransfer(UUID saveGameId, LocalDate eventDate, int sequence) {
        return new SeasonEvent(UUID.randomUUID(), saveGameId, eventDate, SeasonEventType.TRANSFER,
                null, null, sequence);
    }

    public void markProcessed() { this.processed = true; }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public LocalDate getEventDate() { return eventDate; }
    public SeasonEventType getType() { return type; }
    public UUID getCompetitionId() { return competitionId; }
    public Integer getRound() { return round; }
    public int getSequence() { return sequence; }
    public boolean isProcessed() { return processed; }
}
