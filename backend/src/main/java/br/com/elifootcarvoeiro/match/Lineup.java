package br.com.elifootcarvoeiro.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Maps to the {@code lineups} table created in V2__game.sql.
 * The (save_game_id, club_id) pair is unique per the DB constraint.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 */
@Entity
@Table(name = "lineups")
public class Lineup {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Column(name = "formation", nullable = false, length = 10)
    private String formation;

    protected Lineup() {
        // for JPA
    }

    private Lineup(UUID id, UUID saveGameId, UUID clubId, String formation) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.formation = formation;
    }

    public static Lineup create(UUID saveGameId, UUID clubId, String formation) {
        return new Lineup(UUID.randomUUID(), saveGameId, clubId, formation);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public String getFormation() { return formation; }

    public void setFormation(String formation) { this.formation = formation; }
}
