package br.com.carvoeiromanager.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Archived per-player season stat line for a Season. Never purged. */
@Entity
@Table(name = "season_player_stats")
public class SeasonPlayerStats {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "season_id", nullable = false) private UUID seasonId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "player_name", nullable = false, length = 80) private String playerName;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "club_name", nullable = false, length = 80) private String clubName;
    @Column(name = "matches_played", nullable = false) private int matchesPlayed;
    @Column(name = "minutes_played", nullable = false) private int minutesPlayed;
    @Column(name = "goals", nullable = false) private int goals;
    @Column(name = "assists", nullable = false) private int assists;
    @Column(name = "fouls", nullable = false) private int fouls;
    @Column(name = "yellow_cards", nullable = false) private int yellowCards;
    @Column(name = "red_cards", nullable = false) private int redCards;

    protected SeasonPlayerStats() { }

    private SeasonPlayerStats(UUID id, UUID seasonId, UUID playerId, String playerName, UUID clubId,
                              String clubName, int matchesPlayed, int minutesPlayed, int goals,
                              int assists, int fouls, int yellowCards, int redCards) {
        this.id = id; this.seasonId = seasonId; this.playerId = playerId; this.playerName = playerName;
        this.clubId = clubId; this.clubName = clubName; this.matchesPlayed = matchesPlayed;
        this.minutesPlayed = minutesPlayed; this.goals = goals; this.assists = assists;
        this.fouls = fouls; this.yellowCards = yellowCards; this.redCards = redCards;
    }

    public static SeasonPlayerStats create(UUID seasonId, UUID playerId, String playerName, UUID clubId,
                                           String clubName, int matchesPlayed, int minutesPlayed, int goals,
                                           int assists, int fouls, int yellowCards, int redCards) {
        return new SeasonPlayerStats(UUID.randomUUID(), seasonId, playerId, playerName, clubId, clubName,
                matchesPlayed, minutesPlayed, goals, assists, fouls, yellowCards, redCards);
    }

    public UUID getId() { return id; }
    public UUID getSeasonId() { return seasonId; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public int getMatchesPlayed() { return matchesPlayed; }
    public int getMinutesPlayed() { return minutesPlayed; }
    public int getGoals() { return goals; }
    public int getAssists() { return assists; }
    public int getFouls() { return fouls; }
    public int getYellowCards() { return yellowCards; }
    public int getRedCards() { return redCards; }
}
