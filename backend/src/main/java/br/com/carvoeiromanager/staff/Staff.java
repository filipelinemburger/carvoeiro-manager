package br.com.carvoeiromanager.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A hired technical-staff member. Only ever persisted for the save's managed club — AI clubs
 * never hire and are handled entirely by {@code StaffEffectsService}'s fixed baseline.
 */
@Entity
@Table(name = "staff")
public class Staff {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality", nullable = false, length = 10)
    private StaffQuality quality;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "wage", nullable = false)
    private BigDecimal wage;

    protected Staff() {
        // for JPA
    }

    private Staff(UUID id, UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                  String name, BigDecimal wage) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.role = role;
        this.quality = quality;
        this.name = name;
        this.wage = wage;
    }

    public static Staff create(UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                               String name, BigDecimal wage) {
        return new Staff(UUID.randomUUID(), saveGameId, clubId, role, quality, name, wage);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public StaffRole getRole() { return role; }
    public StaffQuality getQuality() { return quality; }
    public String getName() { return name; }
    public BigDecimal getWage() { return wage; }
}
