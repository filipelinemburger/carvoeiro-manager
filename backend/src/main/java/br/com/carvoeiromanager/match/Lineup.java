package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Pressing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "mentality", nullable = false, length = 12)
    private Mentality mentality;

    @Enumerated(EnumType.STRING)
    @Column(name = "pressing", nullable = false, length = 8)
    private Pressing pressing;

    @Enumerated(EnumType.STRING)
    @Column(name = "marking", nullable = false, length = 10)
    private Marking marking;

    @Column(name = "penalty_taker_id")
    private UUID penaltyTakerId;

    @Column(name = "freekick_taker_id")
    private UUID freekickTakerId;

    @Column(name = "corner_taker_id")
    private UUID cornerTakerId;

    protected Lineup() {
        // for JPA
    }

    private Lineup(UUID id, UUID saveGameId, UUID clubId, String formation) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.formation = formation;
        this.mentality = Mentality.BALANCED;
        this.pressing = Pressing.MEDIUM;
        this.marking = Marking.ZONAL;
    }

    public static Lineup create(UUID saveGameId, UUID clubId, String formation) {
        return new Lineup(UUID.randomUUID(), saveGameId, clubId, formation);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public String getFormation() { return formation; }
    public Mentality getMentality() { return mentality; }
    public Pressing getPressing() { return pressing; }
    public Marking getMarking() { return marking; }
    public UUID getPenaltyTakerId() { return penaltyTakerId; }
    public UUID getFreekickTakerId() { return freekickTakerId; }
    public UUID getCornerTakerId() { return cornerTakerId; }

    public void setFormation(String formation) { this.formation = formation; }
    public void setMentality(Mentality mentality) { this.mentality = mentality; }
    public void setPressing(Pressing pressing) { this.pressing = pressing; }
    public void setMarking(Marking marking) { this.marking = marking; }
    public void setPenaltyTakerId(UUID id) { this.penaltyTakerId = id; }
    public void setFreekickTakerId(UUID id) { this.freekickTakerId = id; }
    public void setCornerTakerId(UUID id) { this.cornerTakerId = id; }
}
