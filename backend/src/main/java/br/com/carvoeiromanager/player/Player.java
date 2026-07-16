package br.com.carvoeiromanager.player;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps to the {@code players} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 * All FKs are stored as plain UUID columns (no JPA associations).
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "club_id")
    private UUID clubId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "position", nullable = false, length = 3)
    private Position position;

    @Column(name = "age", nullable = false)
    private int age;

    @Column(name = "nationality", nullable = false, length = 40)
    private String nationality;

    @Embedded
    private Attributes attributes = new Attributes();

    @Column(name = "overall", nullable = false)
    private int overall;

    @Column(name = "form", nullable = false)
    private int form;

    @Column(name = "morale", nullable = false)
    private int morale;

    @Column(name = "yellow_cards", nullable = false)
    private int yellowCards;

    @Column(name = "suspension_remaining", nullable = false)
    private int suspensionRemaining;

    @Column(name = "injury_matches", nullable = false)
    private int injuryMatches;

    @Column(name = "fitness", nullable = false)
    private int fitness = 100;

    @Column(name = "youth_prospect", nullable = false)
    private boolean youthProspect;

    @Column(name = "potential", nullable = false)
    private int potential;

    @Column(name = "last_match_date")
    private LocalDate lastMatchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "injury_severity", nullable = false, length = 10)
    private InjurySeverity injurySeverity = InjurySeverity.NONE;

    @Column(name = "wage", nullable = false)
    private BigDecimal wage = BigDecimal.ZERO;

    @Column(name = "contract_years", nullable = false)
    private int contractYears;

    @Column(name = "transfer_listed", nullable = false)
    private boolean transferListed;

    @Enumerated(EnumType.STRING)
    @Column(name = "listed_reason", length = 16)
    private ListedReason listedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "training_focus", length = 20)
    private Attribute trainingFocus;

    @Enumerated(EnumType.STRING)
    @Column(name = "training_intensity", nullable = false, length = 10)
    private TrainingIntensity trainingIntensity = TrainingIntensity.NORMAL;

    /** Accumulated weekly training, in fortieths of an attribute point (a point fires at 40). */
    @Column(name = "training_progress", nullable = false)
    private int trainingProgress = 0;

    @Column(name = "season_appearances", nullable = false)
    private int seasonAppearances;

    protected Player() {
        // for JPA
    }

    private Player(UUID id, UUID saveGameId, UUID clubId, String name, Position position,
                   int age, String nationality, Attributes attributes, int overall) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.name = name;
        this.position = position;
        this.age = age;
        this.nationality = nationality;
        this.attributes = attributes;
        this.overall = overall;
    }

    public static Player create(UUID saveGameId, UUID clubId, String name, Position position,
                                int age, String nationality, int pace, int finishing,
                                int passing, int tackling, int goalkeeping, int stamina, int overall) {
        Attributes attributes = Attributes.builder()
                .set(Attribute.VELOCIDADE, pace)
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passing)
                .set(Attribute.DESARME, tackling)
                .set(Attribute.REFLEXOS, goalkeeping)
                .set(Attribute.FOLEGO, stamina)
                .build();
        return new Player(UUID.randomUUID(), saveGameId, clubId, name, position, age, nationality,
                attributes, overall);
    }

    /** Creates a player from a full attribute profile (generators + seed loader). */
    public static Player create(UUID saveGameId, UUID clubId, String name, Position position,
                                int age, String nationality, Attributes attributes, int overall) {
        return new Player(UUID.randomUUID(), saveGameId, clubId, name, position, age, nationality,
                attributes, overall);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public String getName() { return name; }
    public Position getPosition() { return position; }
    public int getAge() { return age; }
    public String getNationality() { return nationality; }

    public boolean isYouthProspect() { return youthProspect; }
    public int getPotential() { return potential; }

    public void markAsProspect(int potential) {
        this.youthProspect = true;
        this.potential = potential;
    }

    public void promoteFromYouth() {
        this.youthProspect = false;
    }
    // The six legacy getters delegate to the embedded attribute profile (anchors), so every
    // existing caller and scoring path is unchanged by the storage move.
    public int getPace() { return attributes.get(Attribute.VELOCIDADE); }
    public int getFinishing() { return attributes.get(Attribute.FINALIZACAO); }
    public int getPassing() { return attributes.get(Attribute.PASSE); }
    public int getTackling() { return attributes.get(Attribute.DESARME); }
    public int getGoalkeeping() { return attributes.get(Attribute.REFLEXOS); }
    public int getStamina() { return attributes.get(Attribute.FOLEGO); }
    public int getOverall() { return overall; }

    public Attributes attributes() { return attributes; }
    public int attribute(Attribute a) { return attributes.get(a); }

    public void setClubId(UUID clubId) { this.clubId = clubId; }
    public void setAge(int age) { this.age = age; }
    public void setOverall(int overall) { this.overall = overall; }

    public int getForm() { return form; }
    public int getMorale() { return morale; }
    public int getYellowCards() { return yellowCards; }
    public int getSuspensionRemaining() { return suspensionRemaining; }

    public void applyForm(int delta) {
        this.form = Math.max(-5, Math.min(5, this.form + delta));
    }

    public void applyMorale(int delta) {
        this.morale = Math.max(-5, Math.min(5, this.morale + delta));
    }

    public void addYellowCard() { this.yellowCards++; }

    public void addSuspension(int matches) { this.suspensionRemaining += matches; }

    public void decrementSuspension() {
        if (this.suspensionRemaining > 0) {
            this.suspensionRemaining--;
        }
    }

    public int getInjuryMatches() { return injuryMatches; }
    public int getFitness() { return fitness; }
    public LocalDate getLastMatchDate() { return lastMatchDate; }
    public InjurySeverity getInjurySeverity() { return injurySeverity; }

    public void addInjury(int matches) { this.injuryMatches += matches; }

    public void tickInjury() {
        if (this.injuryMatches > 0) {
            this.injuryMatches--;
            if (this.injuryMatches == 0) {
                this.injurySeverity = InjurySeverity.NONE;
            }
        }
    }

    /** Persists post-match base fitness and the date the player featured. */
    public void recordAppearance(int newBaseFitness, LocalDate matchDate) {
        Objects.requireNonNull(matchDate, "matchDate must not be null");
        this.fitness = Math.max(0, Math.min(100, newBaseFitness));
        this.lastMatchDate = matchDate;
        this.seasonAppearances++;
    }

    /** Stamps an injury: severity tier + layoff length in matches. */
    public void applyInjury(InjurySeverity severity, int matches) {
        this.injurySeverity = severity;
        this.injuryMatches += matches;
    }

    public boolean isEligible() { return this.suspensionRemaining == 0 && this.injuryMatches == 0; }

    /** Replaces the six anchor attributes + overall after the season aging curve; the other attributes carry over. */
    public void evolveAttributes(int pace, int finishing, int passing,
                                 int tackling, int goalkeeping, int stamina, int overall) {
        this.attributes = this.attributes
                .with(Attribute.VELOCIDADE, pace)
                .with(Attribute.FINALIZACAO, finishing)
                .with(Attribute.PASSE, passing)
                .with(Attribute.DESARME, tackling)
                .with(Attribute.REFLEXOS, goalkeeping)
                .with(Attribute.FOLEGO, stamina);
        this.overall = overall;
    }

    /** Clears per-season state when a player carries over into a new season. */
    public void resetSeasonState() {
        this.form = 0;
        this.morale = 0;
        this.yellowCards = 0;
        this.suspensionRemaining = 0;
        this.injuryMatches = 0;
        this.injurySeverity = InjurySeverity.NONE;
        this.fitness = 100;
        this.lastMatchDate = null;
        this.transferListed = false;
        this.listedReason = null;
        this.seasonAppearances = 0;
        this.trainingProgress = 0;
    }

    public BigDecimal getWage() { return wage; }
    public void setWage(BigDecimal wage) { this.wage = wage; }

    public int getContractYears() { return contractYears; }
    public void setContractYears(int contractYears) { this.contractYears = contractYears; }

    public boolean isFreeAgent() { return clubId == null; }

    public boolean isTransferListed() { return transferListed; }
    public ListedReason getListedReason() { return listedReason; }

    public Attribute getTrainingFocus() { return trainingFocus; }
    public TrainingIntensity getTrainingIntensity() { return trainingIntensity; }

    /** Sets the manager's standing training instruction for this player; a null focus opts out. */
    public void setTrainingAssignment(Attribute focus, TrainingIntensity intensity) {
        this.trainingFocus = focus;
        this.trainingIntensity = intensity == null ? TrainingIntensity.NORMAL : intensity;
    }

    public int getTrainingProgress() { return trainingProgress; }
    public void setTrainingProgress(int trainingProgress) { this.trainingProgress = trainingProgress; }

    public int getSeasonAppearances() { return seasonAppearances; }

    /** Puts the player up for transfer with the given reason; overwrites any prior reason. */
    public void listForTransfer(ListedReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        this.transferListed = true;
        this.listedReason = reason;
    }

    /** Pulls the player off the transfer list. */
    public void unlist() {
        this.transferListed = false;
        this.listedReason = null;
    }
}
