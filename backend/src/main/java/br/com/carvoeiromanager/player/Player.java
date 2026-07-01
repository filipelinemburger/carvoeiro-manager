package br.com.carvoeiromanager.player;

import jakarta.persistence.Column;
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

    @Column(name = "pace", nullable = false)
    private int pace;

    @Column(name = "finishing", nullable = false)
    private int finishing;

    @Column(name = "passing", nullable = false)
    private int passing;

    @Column(name = "tackling", nullable = false)
    private int tackling;

    @Column(name = "goalkeeping", nullable = false)
    private int goalkeeping;

    @Column(name = "stamina", nullable = false)
    private int stamina;

    @Column(name = "overall", nullable = false)
    private int overall;

    @Column(name = "form", nullable = false)
    private int form;

    @Column(name = "yellow_cards", nullable = false)
    private int yellowCards;

    @Column(name = "suspension_remaining", nullable = false)
    private int suspensionRemaining;

    @Column(name = "injury_matches", nullable = false)
    private int injuryMatches;

    @Column(name = "fitness", nullable = false)
    private int fitness = 100;

    @Column(name = "last_match_date")
    private LocalDate lastMatchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "injury_severity", nullable = false, length = 10)
    private InjurySeverity injurySeverity = InjurySeverity.NONE;

    @Column(name = "wage", nullable = false)
    private BigDecimal wage = BigDecimal.ZERO;

    @Column(name = "contract_years", nullable = false)
    private int contractYears;

    protected Player() {
        // for JPA
    }

    private Player(UUID id, UUID saveGameId, UUID clubId, String name, Position position,
                   int age, String nationality, int pace, int finishing, int passing,
                   int tackling, int goalkeeping, int stamina, int overall) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.name = name;
        this.position = position;
        this.age = age;
        this.nationality = nationality;
        this.pace = pace;
        this.finishing = finishing;
        this.passing = passing;
        this.tackling = tackling;
        this.goalkeeping = goalkeeping;
        this.stamina = stamina;
        this.overall = overall;
    }

    public static Player create(UUID saveGameId, UUID clubId, String name, Position position,
                                int age, String nationality, int pace, int finishing,
                                int passing, int tackling, int goalkeeping, int stamina, int overall) {
        return new Player(UUID.randomUUID(), saveGameId, clubId, name, position, age, nationality,
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public String getName() { return name; }
    public Position getPosition() { return position; }
    public int getAge() { return age; }
    public String getNationality() { return nationality; }
    public int getPace() { return pace; }
    public int getFinishing() { return finishing; }
    public int getPassing() { return passing; }
    public int getTackling() { return tackling; }
    public int getGoalkeeping() { return goalkeeping; }
    public int getStamina() { return stamina; }
    public int getOverall() { return overall; }

    public void setClubId(UUID clubId) { this.clubId = clubId; }
    public void setAge(int age) { this.age = age; }
    public void setOverall(int overall) { this.overall = overall; }

    public int getForm() { return form; }
    public int getYellowCards() { return yellowCards; }
    public int getSuspensionRemaining() { return suspensionRemaining; }

    public void applyForm(int delta) {
        this.form = Math.max(-5, Math.min(5, this.form + delta));
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
    }

    /** Stamps an injury: severity tier + layoff length in matches. */
    public void applyInjury(InjurySeverity severity, int matches) {
        this.injurySeverity = severity;
        this.injuryMatches += matches;
    }

    public boolean isEligible() { return this.suspensionRemaining == 0 && this.injuryMatches == 0; }

    /** Replaces all six attributes and overall after the season aging curve. */
    public void evolveAttributes(int pace, int finishing, int passing,
                                 int tackling, int goalkeeping, int stamina, int overall) {
        this.pace = pace;
        this.finishing = finishing;
        this.passing = passing;
        this.tackling = tackling;
        this.goalkeeping = goalkeeping;
        this.stamina = stamina;
        this.overall = overall;
    }

    /** Clears per-season state when a player carries over into a new season. */
    public void resetSeasonState() {
        this.form = 0;
        this.yellowCards = 0;
        this.suspensionRemaining = 0;
        this.injuryMatches = 0;
        this.injurySeverity = InjurySeverity.NONE;
        this.fitness = 100;
        this.lastMatchDate = null;
    }

    public BigDecimal getWage() { return wage; }
    public void setWage(BigDecimal wage) { this.wage = wage; }

    public int getContractYears() { return contractYears; }
    public void setContractYears(int contractYears) { this.contractYears = contractYears; }

    public boolean isFreeAgent() { return clubId == null; }
}
