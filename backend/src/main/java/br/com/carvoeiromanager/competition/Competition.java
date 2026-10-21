package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private CompetitionScope scope = CompetitionScope.NATIONAL;

    /** National division: 1 = Série A, 2 = B, 3 = C. Null for a STATE competition. */
    @Column(name = "tier")
    private Integer tier;

    protected Competition() {
        // for JPA
    }

    private Competition(UUID id, UUID saveGameId, String name, String type, int seasonYear, int totalRounds,
                        CompetitionScope scope, Integer tier) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.name = name;
        this.type = type;
        this.seasonYear = seasonYear;
        this.totalRounds = totalRounds;
        this.scope = scope;
        this.tier = tier;
    }

    /** A NATIONAL competition with no tier — for callers (mostly tests) that predate scope/tier. */
    public static Competition create(UUID saveGameId, String name, String type, int seasonYear, int totalRounds) {
        return create(saveGameId, name, type, seasonYear, totalRounds, CompetitionScope.NATIONAL, null);
    }

    public static Competition create(UUID saveGameId, String name, String type, int seasonYear, int totalRounds,
                                     CompetitionScope scope, Integer tier) {
        return new Competition(UUID.randomUUID(), saveGameId, name, type, seasonYear, totalRounds, scope, tier);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public int getSeasonYear() { return seasonYear; }
    public int getTotalRounds() { return totalRounds; }
    public CompetitionScope getScope() { return scope; }
    public Integer getTier() { return tier; }

    public void setSeasonYear(int seasonYear) { this.seasonYear = seasonYear; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
}
