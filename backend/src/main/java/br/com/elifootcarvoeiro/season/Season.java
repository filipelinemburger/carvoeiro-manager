package br.com.elifootcarvoeiro.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Archived finished season (one row per competition per year). Denormalized snapshot. */
@Entity
@Table(name = "season")
public class Season {

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "competition_id", nullable = false) private UUID competitionId;
    @Column(name = "season_year", nullable = false) private int seasonYear;
    @Column(name = "competition_name", nullable = false, length = 80) private String competitionName;
    @Column(name = "champion_club_id", nullable = false) private UUID championClubId;
    @Column(name = "champion_club_name", nullable = false, length = 80) private String championClubName;
    @Column(name = "managed_club_id", nullable = false) private UUID managedClubId;
    @Column(name = "managed_club_name", nullable = false, length = 80) private String managedClubName;
    @Column(name = "managed_club_position", nullable = false) private int managedClubPosition;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected Season() { }

    private Season(UUID id, UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                   UUID championClubId, String championClubName, UUID managedClubId, String managedClubName,
                   int managedClubPosition, Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.competitionId = competitionId;
        this.seasonYear = seasonYear; this.competitionName = competitionName;
        this.championClubId = championClubId; this.championClubName = championClubName;
        this.managedClubId = managedClubId; this.managedClubName = managedClubName;
        this.managedClubPosition = managedClubPosition; this.createdAt = createdAt;
    }

    public static Season create(UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                                UUID championClubId, String championClubName, UUID managedClubId,
                                String managedClubName, int managedClubPosition) {
        return new Season(UUID.randomUUID(), saveGameId, competitionId, seasonYear, competitionName,
                championClubId, championClubName, managedClubId, managedClubName, managedClubPosition,
                Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getSeasonYear() { return seasonYear; }
    public String getCompetitionName() { return competitionName; }
    public UUID getChampionClubId() { return championClubId; }
    public String getChampionClubName() { return championClubName; }
    public UUID getManagedClubId() { return managedClubId; }
    public String getManagedClubName() { return managedClubName; }
    public int getManagedClubPosition() { return managedClubPosition; }
    public Instant getCreatedAt() { return createdAt; }
}
