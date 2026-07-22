package br.com.carvoeiromanager.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** Server-authoritative live-match session. JSON snapshots are stored as TEXT (see NewsItem). */
@Entity
@Table(name = "live_matches")
public class LiveMatch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false, unique = true)
    private UUID saveGameId;

    @Column(name = "fixture_id", nullable = false)
    private UUID fixtureId;

    @Column(name = "competition_id", nullable = false)
    private UUID competitionId;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "match_date", nullable = false)
    private LocalDate matchDate;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "managed_is_home", nullable = false)
    private boolean managedIsHome;

    @Column(name = "committed_minute", nullable = false)
    private int committedMinute;

    @Column(name = "home_xi", nullable = false, columnDefinition = "TEXT")
    private String homeXi;

    @Column(name = "away_xi", nullable = false, columnDefinition = "TEXT")
    private String awayXi;

    @Column(name = "home_bench", nullable = false, columnDefinition = "TEXT")
    private String homeBench;

    @Column(name = "away_bench", nullable = false, columnDefinition = "TEXT")
    private String awayBench;

    @Column(name = "sub_schedule", nullable = false, columnDefinition = "TEXT")
    private String subSchedule;

    @Column(name = "tactics_schedule", nullable = false, columnDefinition = "TEXT")
    private String tacticsSchedule;

    /**
     * JSON snapshot of the round's OTHER fixtures, pre-simulated at kickoff (see
     * {@code LiveMatchService#kickoff}) so {@code finish} can replay them instead of
     * re-simulating — keeping the persisted results identical either way. Null for matches
     * created before this column existed; that in-flight case falls back to the legacy
     * recompute-at-finish path.
     */
    @Column(name = "parallel_scores", columnDefinition = "TEXT")
    private String parallelScores;

    protected LiveMatch() {
    }

    private LiveMatch(UUID id, UUID saveGameId, UUID fixtureId, UUID competitionId, int round,
                      LocalDate matchDate, long seed, boolean managedIsHome,
                      String homeXi, String awayXi, String homeBench, String awayBench, String subSchedule) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.fixtureId = fixtureId;
        this.competitionId = competitionId;
        this.round = round;
        this.matchDate = matchDate;
        this.seed = seed;
        this.managedIsHome = managedIsHome;
        this.committedMinute = 0;
        this.homeXi = homeXi;
        this.awayXi = awayXi;
        this.homeBench = homeBench;
        this.awayBench = awayBench;
        this.subSchedule = subSchedule;
        this.tacticsSchedule = "[]";
    }

    public static LiveMatch create(UUID saveGameId, UUID fixtureId, UUID competitionId, int round,
                                   LocalDate matchDate, long seed, boolean managedIsHome,
                                   String homeXi, String awayXi, String homeBench, String awayBench,
                                   String subSchedule) {
        return new LiveMatch(UUID.randomUUID(), saveGameId, fixtureId, competitionId, round, matchDate,
                seed, managedIsHome, homeXi, awayXi, homeBench, awayBench, subSchedule);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getFixtureId() { return fixtureId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getRound() { return round; }
    public LocalDate getMatchDate() { return matchDate; }
    public long getSeed() { return seed; }
    public boolean isManagedIsHome() { return managedIsHome; }
    public int getCommittedMinute() { return committedMinute; }
    public String getHomeXi() { return homeXi; }
    public String getAwayXi() { return awayXi; }
    public String getHomeBench() { return homeBench; }
    public String getAwayBench() { return awayBench; }
    public String getSubSchedule() { return subSchedule; }

    public String getTacticsSchedule() { return tacticsSchedule; }
    public String getParallelScores() { return parallelScores; }

    public void setCommittedMinute(int committedMinute) { this.committedMinute = committedMinute; }
    public void setSubSchedule(String subSchedule) { this.subSchedule = subSchedule; }
    public void setTacticsSchedule(String tacticsSchedule) { this.tacticsSchedule = tacticsSchedule; }
    public void setParallelScores(String parallelScores) { this.parallelScores = parallelScores; }
}
