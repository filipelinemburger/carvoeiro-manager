package br.com.carvoeiromanager.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A Bosman-style pre-contract: a final-contract-year player has agreed terms with a new
 * club and joins for free when his contract lapses at rollover. Lifecycle: AGREED (the
 * current club may still counter-renew on the next TRANSFER day) -> LOCKED (binding) or
 * CANCELLED (the player renewed with his current club instead). Rows are season-scoped:
 * rollover executes LOCKED deals and purges the table.
 */
@Entity
@Table(name = "pre_contract_agreement")
public class PreContractAgreement {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "from_club_id", nullable = false) private UUID fromClubId;
    @Column(name = "to_club_id", nullable = false) private UUID toClubId;
    @Column(name = "wage", nullable = false) private BigDecimal wage;
    @Column(name = "contract_years", nullable = false) private int contractYears;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10) private PreContractStatus status;
    @Column(name = "created_date", nullable = false) private LocalDate createdDate;

    protected PreContractAgreement() { }

    private PreContractAgreement(UUID id, UUID saveGameId, UUID playerId, UUID fromClubId,
                                 UUID toClubId, BigDecimal wage, int contractYears,
                                 PreContractStatus status, LocalDate createdDate) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.playerId = playerId;
        this.fromClubId = fromClubId;
        this.toClubId = toClubId;
        this.wage = wage;
        this.contractYears = contractYears;
        this.status = status;
        this.createdDate = createdDate;
    }

    public static PreContractAgreement create(UUID saveGameId, UUID playerId, UUID fromClubId,
                                              UUID toClubId, BigDecimal wage, int contractYears,
                                              LocalDate createdDate) {
        return new PreContractAgreement(UUID.randomUUID(), saveGameId, playerId, fromClubId,
                toClubId, wage, contractYears, PreContractStatus.AGREED, createdDate);
    }

    public void lock() { this.status = PreContractStatus.LOCKED; }
    public void cancel() { this.status = PreContractStatus.CANCELLED; }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getFromClubId() { return fromClubId; }
    public UUID getToClubId() { return toClubId; }
    public BigDecimal getWage() { return wage; }
    public int getContractYears() { return contractYears; }
    public PreContractStatus getStatus() { return status; }
    public LocalDate getCreatedDate() { return createdDate; }
}
