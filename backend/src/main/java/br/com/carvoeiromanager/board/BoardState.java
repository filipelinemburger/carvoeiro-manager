package br.com.carvoeiromanager.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** The board's standing with the manager, one row per save. Maps to {@code board_states} (V57). */
@Entity
@Table(name = "board_states")
public class BoardState {

    @Id
    @Column(name = "save_game_id", nullable = false, updatable = false)
    private UUID saveGameId;

    @Column(name = "confidence", nullable = false)
    private int confidence;

    @Column(name = "ultimatum_active", nullable = false)
    private boolean ultimatumActive;

    @Column(name = "matches_since_ultimatum", nullable = false)
    private int matchesSinceUltimatum;

    @Column(name = "sacking_enabled", nullable = false)
    private boolean sackingEnabled;

    /** The season the board may judge but not sack in; null when there is none. */
    @Column(name = "grace_season_year")
    private Integer graceSeasonYear;

    protected BoardState() { }

    private BoardState(UUID saveGameId, int confidence, boolean sackingEnabled, Integer graceSeasonYear) {
        this.saveGameId = saveGameId;
        this.confidence = confidence;
        this.sackingEnabled = sackingEnabled;
        this.graceSeasonYear = graceSeasonYear;
    }

    public static BoardState create(UUID saveGameId, int confidence, boolean sackingEnabled, Integer graceSeasonYear) {
        return new BoardState(saveGameId, confidence, sackingEnabled, graceSeasonYear);
    }

    public UUID getSaveGameId() { return saveGameId; }
    public int getConfidence() { return confidence; }
    public boolean isUltimatumActive() { return ultimatumActive; }
    public int getMatchesSinceUltimatum() { return matchesSinceUltimatum; }
    public boolean isSackingEnabled() { return sackingEnabled; }
    public Integer getGraceSeasonYear() { return graceSeasonYear; }

    public void setConfidence(int confidence) {
        this.confidence = BoardConfidenceCalculator.clamp(confidence);
    }

    public void issueUltimatum() {
        this.ultimatumActive = true;
        this.matchesSinceUltimatum = 0;
    }

    public void liftUltimatum() {
        this.ultimatumActive = false;
        this.matchesSinceUltimatum = 0;
    }

    /** Counts a managed match toward the ultimatum's deadline; a no-op while none is active. */
    public void countMatchSinceUltimatum() {
        if (ultimatumActive) {
            matchesSinceUltimatum++;
        }
    }

    public boolean isGraceSeason(int seasonYear) {
        return graceSeasonYear != null && graceSeasonYear == seasonYear;
    }

    /** False with sacking disabled or in the grace season: the sacking is then averted instead. */
    public boolean canSack(int seasonYear) {
        return sackingEnabled && !isGraceSeason(seasonYear);
    }
}
