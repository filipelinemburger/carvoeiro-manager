package br.com.carvoeiromanager.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps to the {@code match_events} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 */
@Entity
@Table(name = "match_events")
public class MatchEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "fixture_id", nullable = false)
    private UUID fixtureId;

    @Column(name = "minute", nullable = false)
    private int minute;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    protected MatchEvent() {
        // for JPA
    }

    private MatchEvent(UUID id, UUID fixtureId, int minute, String type, UUID playerId, UUID clubId) {
        this.id = id;
        this.fixtureId = fixtureId;
        this.minute = minute;
        this.type = type;
        this.playerId = playerId;
        this.clubId = clubId;
    }

    public static MatchEvent create(UUID fixtureId, int minute, String type, UUID playerId, UUID clubId) {
        return new MatchEvent(UUID.randomUUID(), fixtureId, minute, type, playerId, clubId);
    }

    public UUID getId() { return id; }
    public UUID getFixtureId() { return fixtureId; }
    public int getMinute() { return minute; }
    public String getType() { return type; }
    public UUID getPlayerId() { return playerId; }
    public UUID getClubId() { return clubId; }
}
