package br.com.carvoeiromanager.competition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One ordered phase of a competition. A competition with no phases is a plain
 * double-round-robin league (the pre-V47 behaviour), so this table is purely additive.
 */
@Entity
@Table(name = "competition_phase")
public class CompetitionPhase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "competition_id", nullable = false)
    private UUID competitionId;

    /** 0-based order within the competition. */
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PhaseKind kind;

    /** 1 or 2. A two-legged knockout round occupies two match-units. */
    @Column(name = "legs", nullable = false)
    private int legs;

    /** How many match-units this phase occupies in the calendar. */
    @Column(name = "rounds", nullable = false)
    private int rounds;

    /** ROUND_ROBIN: how many clubs advance. Null for KNOCKOUT. */
    @Column(name = "qualifiers")
    private Integer qualifiers;

    protected CompetitionPhase() {
        // for JPA
    }

    private CompetitionPhase(UUID id, UUID saveGameId, UUID competitionId, int ordinal, String name,
                             PhaseKind kind, int legs, int rounds, Integer qualifiers) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.competitionId = competitionId;
        this.ordinal = ordinal;
        this.name = name;
        this.kind = kind;
        this.legs = legs;
        this.rounds = rounds;
        this.qualifiers = qualifiers;
    }

    public static CompetitionPhase create(UUID saveGameId, UUID competitionId, int ordinal, String name,
                                          PhaseKind kind, int legs, int rounds, Integer qualifiers) {
        return new CompetitionPhase(UUID.randomUUID(), saveGameId, competitionId, ordinal, name,
                kind, legs, rounds, qualifiers);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getCompetitionId() { return competitionId; }
    public int getOrdinal() { return ordinal; }
    public String getName() { return name; }
    public PhaseKind getKind() { return kind; }
    public int getLegs() { return legs; }
    public int getRounds() { return rounds; }
    public Integer getQualifiers() { return qualifiers; }
}
