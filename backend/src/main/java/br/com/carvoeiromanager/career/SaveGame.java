package br.com.carvoeiromanager.career;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
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

    @Column(name = "last_played_at")
    private Instant lastPlayedAt;

    @Column(name = "\"current_date\"")
    private LocalDate currentDate;

    /**
     * YYYYMM key of the last in-game month for which monthly finance costs were charged.
     * 0 = nothing charged yet this season. Reset to 0 on season rollover so each new season
     * charges monthly costs fresh.
     */
    @Column(name = "last_finance_month", nullable = false)
    private int lastFinanceMonth = 0;

    @Column(name = "last_training_week", nullable = false)
    private int lastTrainingWeek = 0;

    @Column(name = "manager_name", nullable = false, length = 80)
    private String managerName;

    @Column(name = "manager_nationality", nullable = false, length = 40)
    private String managerNationality;

    protected SaveGame() {
        // for JPA
    }

    private SaveGame(UUID id, UUID userId, UUID managedClubId, int seasonYear,
                     int currentRound, SaveGameStatus status, Instant createdAt,
                     String managerName, String managerNationality) {
        this.id = id;
        this.userId = userId;
        this.managedClubId = managedClubId;
        this.seasonYear = seasonYear;
        this.currentRound = currentRound;
        this.status = status;
        this.createdAt = createdAt;
        this.lastPlayedAt = createdAt;
        this.managerName = managerName;
        this.managerNationality = managerNationality;
    }

    public static SaveGame create(UUID userId, int seasonYear, String managerName, String managerNationality) {
        return new SaveGame(UUID.randomUUID(), userId, null, seasonYear, 0, SaveGameStatus.IN_PROGRESS,
                Instant.now(), managerName, managerNationality);
    }

    /** Convenience overload for entity-level tests that don't care about the manager identity. */
    public static SaveGame create(UUID userId, int seasonYear) {
        return create(userId, seasonYear, "Técnico", "Brasil");
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getManagedClubId() { return managedClubId; }
    public int getSeasonYear() { return seasonYear; }
    public int getCurrentRound() { return currentRound; }
    public SaveGameStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastPlayedAt() { return lastPlayedAt; }
    public void setLastPlayedAt(Instant lastPlayedAt) { this.lastPlayedAt = lastPlayedAt; }
    public String getManagerName() { return managerName; }
    public String getManagerNationality() { return managerNationality; }

    public void setManagedClubId(UUID managedClubId) { this.managedClubId = managedClubId; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public void setStatus(SaveGameStatus status) { this.status = status; }
    public LocalDate getCurrentDate() { return currentDate; }
    public void setCurrentDate(LocalDate currentDate) { this.currentDate = currentDate; }

    public int getLastFinanceMonth() { return lastFinanceMonth; }
    public void setLastFinanceMonth(int lastFinanceMonth) { this.lastFinanceMonth = lastFinanceMonth; }

    public int getLastTrainingWeek() { return lastTrainingWeek; }
    public void setLastTrainingWeek(int lastTrainingWeek) { this.lastTrainingWeek = lastTrainingWeek; }

    /** Rolls this career forward into the next season. */
    public void advanceToNextSeason() {
        this.seasonYear += 1;
        this.currentRound = 0;
        this.currentDate = LocalDate.of(this.seasonYear, 1, 15);
        this.status = SaveGameStatus.IN_PROGRESS;
        this.lastFinanceMonth = 0; // reset so each new season charges monthly costs fresh
        this.lastTrainingWeek = 0; // reset so each new season trains weekly from scratch
    }
}
