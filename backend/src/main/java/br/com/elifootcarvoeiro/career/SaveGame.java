package br.com.elifootcarvoeiro.career;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code save_games} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 */
@Entity
@Table(name = "save_games")
public class SaveGame {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "managed_club_id")
    private UUID managedClubId;

    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    @Column(name = "current_round", nullable = false)
    private int currentRound;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SaveGameStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SaveGame() {
        // for JPA
    }

    private SaveGame(UUID id, UUID userId, UUID managedClubId, int seasonYear,
                     int currentRound, SaveGameStatus status, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.managedClubId = managedClubId;
        this.seasonYear = seasonYear;
        this.currentRound = currentRound;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static SaveGame create(UUID userId, int seasonYear) {
        return new SaveGame(UUID.randomUUID(), userId, null, seasonYear, 0, SaveGameStatus.IN_PROGRESS, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getManagedClubId() { return managedClubId; }
    public int getSeasonYear() { return seasonYear; }
    public int getCurrentRound() { return currentRound; }
    public SaveGameStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setManagedClubId(UUID managedClubId) { this.managedClubId = managedClubId; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public void setStatus(SaveGameStatus status) { this.status = status; }

    /** Rolls this career forward into the next season. */
    public void advanceToNextSeason() {
        this.seasonYear += 1;
        this.currentRound = 0;
        this.status = SaveGameStatus.IN_PROGRESS;
    }
}
