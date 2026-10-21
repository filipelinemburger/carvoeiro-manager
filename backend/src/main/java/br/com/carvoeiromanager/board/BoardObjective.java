package br.com.carvoeiromanager.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One season objective. Kept for every season (never purged at rollover): past objectives and their
 * outcomes are the manager's record. Maps to {@code board_objectives} (V57).
 */
@Entity
@Table(name = "board_objectives")
public class BoardObjective {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ObjectiveKind kind;

    /** Null for FINANCE. */
    @Column(name = "competition_id")
    private UUID competitionId;

    /** LEAGUE only: the division the target was set for, so the record reads right after a move. */
    @Column(name = "tier")
    private Integer tier;

    @Column(name = "target_position")
    private Integer targetPosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_stage", length = 20)
    private Stage targetStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ObjectiveOutcome outcome = ObjectiveOutcome.PENDING;

    protected BoardObjective() { }

    public static BoardObjective create(UUID saveGameId, int seasonYear, ObjectiveKind kind, UUID competitionId,
                                        Integer tier, Integer targetPosition, Stage targetStage) {
        BoardObjective o = new BoardObjective();
        o.id = UUID.randomUUID();
        o.saveGameId = saveGameId;
        o.seasonYear = seasonYear;
        o.kind = kind;
        o.competitionId = competitionId;
        o.tier = tier;
        o.targetPosition = targetPosition;
        o.targetStage = targetStage;
        return o;
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public int getSeasonYear() { return seasonYear; }
    public ObjectiveKind getKind() { return kind; }
    public UUID getCompetitionId() { return competitionId; }
    public Integer getTier() { return tier; }
    public Integer getTargetPosition() { return targetPosition; }
    public Stage getTargetStage() { return targetStage; }
    public ObjectiveOutcome getOutcome() { return outcome; }
    public boolean isPending() { return outcome == ObjectiveOutcome.PENDING; }

    /** An objective resolves exactly once; resolving it again is a bug in the caller. */
    public void resolve(ObjectiveOutcome outcome) {
        if (this.outcome != ObjectiveOutcome.PENDING) {
            throw new IllegalStateException("Objective " + id + " (" + kind + ") is already " + this.outcome);
        }
        if (outcome == ObjectiveOutcome.PENDING) {
            throw new IllegalArgumentException("Cannot resolve an objective to PENDING");
        }
        this.outcome = outcome;
    }
}
