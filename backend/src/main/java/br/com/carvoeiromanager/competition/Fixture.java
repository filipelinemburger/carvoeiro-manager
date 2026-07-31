package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps to the {@code fixtures} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 * homeGoals and awayGoals are nullable until the match is played.
 */
@Entity
@Table(name = "fixtures")
public class Fixture {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "competition_id", nullable = false)
    private UUID competitionId;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "home_club_id", nullable = false)
    private UUID homeClubId;

    @Column(name = "away_club_id", nullable = false)
    private UUID awayClubId;

    @Column(name = "home_goals")
    private Integer homeGoals;

    @Column(name = "away_goals")
    private Integer awayGoals;

    @Column(name = "played", nullable = false)
    private boolean played;

    @Column(name = "match_date")
    private LocalDate matchDate;

    @Column(name = "attendance")
    private Integer attendance;

    protected Fixture() {
        // for JPA
    }

    private Fixture(UUID id, UUID saveGameId, UUID competitionId, int round,
                    UUID homeClubId, UUID awayClubId, LocalDate matchDate) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.competitionId = competitionId;
        this.round = round;
        this.homeClubId = homeClubId;
        this.awayClubId = awayClubId;
        this.homeGoals = null;
        this.awayGoals = null;
        this.played = false;
        this.matchDate = matchDate;
    }

    public static Fixture create(UUID saveGameId, UUID competitionId, int round,
                                 UUID homeClubId, UUID awayClubId, LocalDate matchDate) {
        return new Fixture(UUID.randomUUID(), saveGameId, competitionId, round, homeClubId, awayClubId, matchDate);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getRound() { return round; }
    public UUID getHomeClubId() { return homeClubId; }
    public UUID getAwayClubId() { return awayClubId; }
    public Integer getHomeGoals() { return homeGoals; }
    public Integer getAwayGoals() { return awayGoals; }
    public boolean isPlayed() { return played; }
    public LocalDate getMatchDate() { return matchDate; }
    public void setMatchDate(LocalDate matchDate) { this.matchDate = matchDate; }
    public Integer getAttendance() { return attendance; }
    public void setAttendance(Integer attendance) { this.attendance = attendance; }

    public void recordResult(int homeGoals, int awayGoals) {
        this.homeGoals = homeGoals;
        this.awayGoals = awayGoals;
        this.played = true;
    }
}
