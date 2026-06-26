package br.com.elifootcarvoeiro.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived top-scorer row for a Season. */
@Entity
@Table(name = "season_top_scorer")
public class SeasonTopScorer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "rank", nullable = false) private int rank;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "player_name", nullable = false, length = 80) private String playerName;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "goals", nullable = false) private int goals;

    protected SeasonTopScorer() { }

    private SeasonTopScorer(UUID id, UUID seasonId, int rank, UUID playerId, String playerName,
                            UUID clubId, String clubName, int goals) {
        this.id = id; this.seasonId = seasonId; this.rank = rank; this.playerId = playerId;
        this.playerName = playerName; this.clubId = clubId; this.clubName = clubName; this.goals = goals;
    }

    public static SeasonTopScorer create(UUID seasonId, int rank, UUID playerId, String playerName,
                                         UUID clubId, String clubName, int goals) {
        return new SeasonTopScorer(UUID.randomUUID(), seasonId, rank, playerId, playerName,
                clubId, clubName, goals);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public int getRank() { return rank; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getGoals() { return goals; }
}
