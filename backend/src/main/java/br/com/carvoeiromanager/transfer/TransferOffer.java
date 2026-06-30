package br.com.carvoeiromanager.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A single transfer negotiation (season-scoped; purged at rollover). */
@Entity
@Table(name = "transfer_offer")
public class TransferOffer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "from_club_id", nullable = false) private UUID fromClubId;
    @Column(name = "to_club_id", nullable = false) private UUID toClubId;
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8) private TransferDirection direction;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "counter_amount") private BigDecimal counterAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12) private TransferOfferStatus status;
    @Column(name = "round", nullable = false) private int round;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "wage") private BigDecimal wage;
    @Column(name = "contract_years") private Integer contractYears;

    protected TransferOffer() { }

    private TransferOffer(UUID id, UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId,
                          TransferDirection direction, BigDecimal amount, TransferOfferStatus status,
                          int round, Instant createdAt, BigDecimal wage, Integer contractYears) {
        this.id = id; this.saveGameId = saveGameId; this.playerId = playerId;
        this.fromClubId = fromClubId; this.toClubId = toClubId; this.direction = direction;
        this.amount = amount; this.status = status; this.round = round; this.createdAt = createdAt;
        this.wage = wage; this.contractYears = contractYears;
    }

    public static TransferOffer create(UUID saveGameId, UUID playerId, UUID fromClubId, UUID toClubId,
                                       TransferDirection direction, BigDecimal amount, int round,
                                       BigDecimal wage, Integer contractYears) {
        return new TransferOffer(UUID.randomUUID(), saveGameId, playerId, fromClubId, toClubId,
                direction, amount, TransferOfferStatus.PENDING, round, Instant.now(), wage, contractYears);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getFromClubId() { return fromClubId; }
    public UUID getToClubId() { return toClubId; }
    public TransferDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public TransferOfferStatus getStatus() { return status; }
    public int getRound() { return round; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getWage() { return wage; }
    public Integer getContractYears() { return contractYears; }

    public void markCountered(BigDecimal counterAmount) {
        this.counterAmount = counterAmount;
        this.status = TransferOfferStatus.COUNTERED;
    }
    public void markAccepted() { this.status = TransferOfferStatus.ACCEPTED; }
    public void markRejected() { this.status = TransferOfferStatus.REJECTED; }
    public void markWithdrawn() { this.status = TransferOfferStatus.WITHDRAWN; }
}
