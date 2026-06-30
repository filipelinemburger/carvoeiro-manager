package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "competition_club")
public class CompetitionClub {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "competition_id", nullable = false) private UUID competitionId;
    @Column(name = "club_id", nullable = false) private UUID clubId;

    protected CompetitionClub() { }

    private CompetitionClub(UUID id, UUID saveGameId, UUID competitionId, UUID clubId) {
        this.id = id; this.saveGameId = saveGameId; this.competitionId = competitionId; this.clubId = clubId;
    }

    public static CompetitionClub create(UUID saveGameId, UUID competitionId, UUID clubId) {
        return new CompetitionClub(UUID.randomUUID(), saveGameId, competitionId, clubId);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public UUID getClubId() { return clubId; }
}
