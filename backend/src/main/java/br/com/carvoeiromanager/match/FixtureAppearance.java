package br.com.carvoeiromanager.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One player's participation in one fixture, for the current in-progress season only.
 * Maps to {@code fixture_appearance} (V24). Purged at season rollover exactly like
 * {@link MatchEvent} — see {@code SeasonRolloverService}.
 */
@Entity
@Table(name = "fixture_appearance")
public class FixtureAppearance {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "fixture_id", nullable = false)
    private UUID fixtureId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Column(name = "minutes_played", nullable = false)
    private int minutesPlayed;

    @Column(name = "fouls", nullable = false)
    private int fouls;

    protected FixtureAppearance() {
        // for JPA
    }

    private FixtureAppearance(UUID id, UUID fixtureId, UUID playerId, UUID clubId,
                              int minutesPlayed, int fouls) {
        this.id = id;
        this.fixtureId = fixtureId;
        this.playerId = playerId;
        this.clubId = clubId;
        this.minutesPlayed = minutesPlayed;
        this.fouls = fouls;
    }

    public static FixtureAppearance create(UUID fixtureId, UUID playerId, UUID clubId,
                                           int minutesPlayed, int fouls) {
        return new FixtureAppearance(UUID.randomUUID(), fixtureId, playerId, clubId, minutesPlayed, fouls);
    }

    public UUID getId() { return id; }
    public UUID getFixtureId() { return fixtureId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getClubId() { return clubId; }
    public int getMinutesPlayed() { return minutesPlayed; }
    public int getFouls() { return fouls; }
}
