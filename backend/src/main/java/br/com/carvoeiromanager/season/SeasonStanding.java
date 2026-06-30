package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived final-table row for a Season. */
@Entity
@Table(name = "season_standing")
public class SeasonStanding {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "position", nullable = false) private int position;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "played", nullable = false) private int played;
    @Column(name = "won", nullable = false) private int won;
    @Column(name = "drawn", nullable = false) private int drawn;
    @Column(name = "lost", nullable = false) private int lost;
    @Column(name = "goals_for", nullable = false) private int goalsFor;
    @Column(name = "goals_against", nullable = false) private int goalsAgainst;
    @Column(name = "goal_difference", nullable = false) private int goalDifference;
    @Column(name = "points", nullable = false) private int points;

    protected SeasonStanding() { }

    private SeasonStanding(UUID id, UUID seasonId, int position, UUID clubId, String clubName,
                           int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst,
                           int goalDifference, int points) {
        this.id = id; this.seasonId = seasonId; this.position = position; this.clubId = clubId;
        this.clubName = clubName; this.played = played; this.won = won; this.drawn = drawn;
        this.lost = lost; this.goalsFor = goalsFor; this.goalsAgainst = goalsAgainst;
        this.goalDifference = goalDifference; this.points = points;
    }

    public static SeasonStanding create(UUID seasonId, int position, UUID clubId, String clubName,
                                        int played, int won, int drawn, int lost, int goalsFor,
                                        int goalsAgainst, int goalDifference, int points) {
        return new SeasonStanding(UUID.randomUUID(), seasonId, position, clubId, clubName,
                played, won, drawn, lost, goalsFor, goalsAgainst, goalDifference, points);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public int getPosition() { return position; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getPlayed() { return played; }
    public int getWon() { return won; }
    public int getDrawn() { return drawn; }
    public int getLost() { return lost; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public int getGoalDifference() { return goalDifference; }
    public int getPoints() { return points; }
}
