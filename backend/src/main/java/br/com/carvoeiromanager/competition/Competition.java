package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps to the {@code competitions} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 */
@Entity
@Table(name = "competitions")
public class Competition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    @Column(name = "total_rounds", nullable = false)
    private int totalRounds;

    protected Competition() {
        // for JPA
    }

    private Competition(UUID id, UUID saveGameId, String name, String type, int seasonYear, int totalRounds) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.name = name;
        this.type = type;
        this.seasonYear = seasonYear;
        this.totalRounds = totalRounds;
    }

    public static Competition create(UUID saveGameId, String name, String type, int seasonYear, int totalRounds) {
        return new Competition(UUID.randomUUID(), saveGameId, name, type, seasonYear, totalRounds);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public int getSeasonYear() { return seasonYear; }
    public int getTotalRounds() { return totalRounds; }

    public void setSeasonYear(int seasonYear) { this.seasonYear = seasonYear; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
}
