package br.com.carvoeiromanager.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A club role the player emptied — today only by poaching its holder. The row records the EVENT,
 * not the current state: "this slot is empty because someone took the person out of it".
 *
 * <p>It exists purely so {@link StaffEffectsService} can tell that apart from the other reason a
 * slot is empty — the seed research never found anybody for it. Both look identical in the
 * {@code staff} table (no row), and conflating them handed the victim of a poach the
 * {@code AI_BASELINE_AVERAGE} floor, which for 46 of the 53 poachable rows is an upgrade.
 *
 * <p>Never written for the managed club: hiring is only ever done BY the managed club, and the
 * managed club's empty slots are already neutral without any of this.
 */
@Entity
@Table(name = "staff_vacancy")
public class StaffVacancy {

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

    protected StaffVacancy() {
        // for JPA
    }

    private StaffVacancy(UUID id, UUID saveGameId, UUID clubId, StaffRole role) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.role = role;
    }

    public static StaffVacancy create(UUID saveGameId, UUID clubId, StaffRole role) {
        return new StaffVacancy(UUID.randomUUID(), saveGameId, clubId, role);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public StaffRole getRole() { return role; }
}
