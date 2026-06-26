package br.com.elifootcarvoeiro.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "club_id", nullable = false)
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
}
