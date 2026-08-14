package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Archived finished season (one row per competition per year). Denormalized snapshot.
 *
 * <p>Two kinds of row live here. A <em>managed</em> competition — one the player's club has a
 * membership row in — carries the full managed-club triple. A <em>background</em> competition is
 * archived for its champion and its table alone, and its {@code managedClubId},
 * {@code managedClubName} and {@code managedClubPosition} are all null: the player was never in it,
 * so there is no position to record. Readers must treat a null position as "not your competition",
 * never as a placeholder to be defaulted.
 *
 * @see #create
 * @see #createForBackgroundCompetition
 */
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
    // Null for a background competition — see the class javadoc and V52.
    @Column(name = "managed_club_id") private UUID managedClubId;
    @Column(name = "managed_club_name", length = 80) private String managedClubName;
    @Column(name = "managed_club_position") private Integer managedClubPosition;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected Season() { }

    private Season(UUID id, UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                   UUID championClubId, String championClubName, UUID managedClubId, String managedClubName,
                   Integer managedClubPosition, Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.competitionId = competitionId;
        this.seasonYear = seasonYear; this.competitionName = competitionName;
        this.championClubId = championClubId; this.championClubName = championClubName;
        this.managedClubId = managedClubId; this.managedClubName = managedClubName;
        this.managedClubPosition = managedClubPosition; this.createdAt = createdAt;
    }

    /**
     * Archives a competition the managed club actually played in: champion, table, and the
     * player's own finishing position. {@code managedClubPosition} is a real 1-based placing taken
     * from the final classification.
     */
    public static Season create(UUID saveGameId, UUID competitionId, int seasonYear, String competitionName,
                                UUID championClubId, String championClubName, UUID managedClubId,
                                String managedClubName, int managedClubPosition) {
        return new Season(UUID.randomUUID(), saveGameId, competitionId, seasonYear, competitionName,
                championClubId, championClubName, managedClubId, managedClubName, managedClubPosition,
                Instant.now());
    }

    /**
     * Archives a competition the managed club is <em>not</em> a member of — Série C for a
     * Catarinense/Série B career, say. Its champion and its table are part of the world's history
     * and must survive rollover, but the player has no placing in it, so all three managed-club
     * columns are left null.
     *
     * <p>This factory deliberately takes no managed-club arguments. A caller cannot accidentally
     * stamp the player onto a competition they never entered, and in particular cannot repeat the
     * bug this replaces: deriving the position from {@code finalOrder.indexOf(managedClubId)},
     * which returns -1 for a non-member and used to be defaulted into "finished last".
     */
    public static Season createForBackgroundCompetition(UUID saveGameId, UUID competitionId, int seasonYear,
                                                        String competitionName, UUID championClubId,
                                                        String championClubName) {
        return new Season(UUID.randomUUID(), saveGameId, competitionId, seasonYear, competitionName,
                championClubId, championClubName, null, null, null, Instant.now());
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
    /** Null when this row archives a background competition — the player had no placing in it. */
    public Integer getManagedClubPosition() { return managedClubPosition; }
    public Instant getCreatedAt() { return createdAt; }
}
