package br.com.carvoeiromanager.watchlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One player the manager is tracking. Rival-club players and free agents only — never a
 * player on the managed club. Rows are removed on unwatch and cascade-deleted when the
 * player or save is deleted.
 */
@Entity
@Table(name = "watchlist_entry")
public class WatchlistEntry {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected WatchlistEntry() { }

    private WatchlistEntry(UUID id, UUID saveGameId, UUID playerId, Instant createdAt) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.playerId = playerId;
        this.createdAt = createdAt;
    }

    public static WatchlistEntry create(UUID saveGameId, UUID playerId, Instant createdAt) {
        return new WatchlistEntry(UUID.randomUUID(), saveGameId, playerId, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public Instant getCreatedAt() { return createdAt; }
}
