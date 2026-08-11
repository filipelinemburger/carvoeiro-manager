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
 * A technical-staff member: the managed club's commission (researched staff seeded at career
 * creation, until the player hires over a role) or a rival club's researched staff, also seeded
 * at career creation. {@code StaffEffectsService} reads these rows for every club; only a slot no
 * row covers falls back to a baseline, and then only when no {@link StaffVacancy} explains it.
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

    @Column(name = "primary_attribute", nullable = false)
    private int primaryAttribute;

    @Column(name = "secondary_attribute", nullable = false)
    private int secondaryAttribute;

    protected Staff() {
        // for JPA
    }

    private Staff(UUID id, UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                  String name, BigDecimal wage, int primaryAttribute, int secondaryAttribute) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.role = role;
        this.quality = quality;
        this.name = name;
        this.wage = wage;
        this.primaryAttribute = primaryAttribute;
        this.secondaryAttribute = secondaryAttribute;
    }

    public static Staff create(UUID saveGameId, UUID clubId, StaffRole role, StaffQuality quality,
                               String name, BigDecimal wage, int primaryAttribute, int secondaryAttribute) {
        return new Staff(UUID.randomUUID(), saveGameId, clubId, role, quality, name, wage,
                primaryAttribute, secondaryAttribute);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public StaffRole getRole() { return role; }
    public StaffQuality getQuality() { return quality; }
    public String getName() { return name; }
    public BigDecimal getWage() { return wage; }
    public int getPrimaryAttribute() { return primaryAttribute; }
    public int getSecondaryAttribute() { return secondaryAttribute; }

    /** The single number the continuous staff effects are computed from. */
    public double attributeAverage() { return (primaryAttribute + secondaryAttribute) / 2.0; }
}
