package br.com.carvoeiromanager.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single loan negotiation (season-scoped; purged at rollover). {@code ownerWageShare} is the
 * percentage of the wage the OWNER pays, and {@code mayFaceOwner} whether the borrower may field
 * him against his owner — the two terms the AI can counter.
 */
@Entity
@Table(name = "loan_offer")
public class LoanOffer {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "owner_club_id", nullable = false) private UUID ownerClubId;
    @Column(name = "borrower_club_id", nullable = false) private UUID borrowerClubId;
    @Column(name = "owner_wage_share", nullable = false) private int ownerWageShare;
    @Column(name = "may_face_owner", nullable = false) private boolean mayFaceOwner;
    @Column(name = "counter_owner_wage_share") private Integer counterOwnerWageShare;
    @Column(name = "counter_may_face_owner") private Boolean counterMayFaceOwner;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12) private LoanOfferStatus status;
    @Column(name = "round", nullable = false) private int round;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected LoanOffer() { }

    private LoanOffer(UUID id, UUID saveGameId, UUID playerId, UUID ownerClubId, UUID borrowerClubId,
                      int ownerWageShare, boolean mayFaceOwner, LoanOfferStatus status, int round,
                      Instant createdAt) {
        this.id = id; this.saveGameId = saveGameId; this.playerId = playerId;
        this.ownerClubId = ownerClubId; this.borrowerClubId = borrowerClubId;
        this.ownerWageShare = ownerWageShare; this.mayFaceOwner = mayFaceOwner;
        this.status = status; this.round = round; this.createdAt = createdAt;
    }

    public static LoanOffer create(UUID saveGameId, UUID playerId, UUID ownerClubId,
                                   UUID borrowerClubId, int ownerWageShare, boolean mayFaceOwner,
                                   int round) {
        return new LoanOffer(UUID.randomUUID(), saveGameId, playerId, ownerClubId, borrowerClubId,
                ownerWageShare, mayFaceOwner, LoanOfferStatus.PENDING, round, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getOwnerClubId() { return ownerClubId; }
    public UUID getBorrowerClubId() { return borrowerClubId; }
    public int getOwnerWageShare() { return ownerWageShare; }
    public boolean isMayFaceOwner() { return mayFaceOwner; }
    public Integer getCounterOwnerWageShare() { return counterOwnerWageShare; }
    public Boolean getCounterMayFaceOwner() { return counterMayFaceOwner; }
    public LoanOfferStatus getStatus() { return status; }
    public int getRound() { return round; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCountered(int counterOwnerWageShare, boolean counterMayFaceOwner) {
        this.counterOwnerWageShare = counterOwnerWageShare;
        this.counterMayFaceOwner = counterMayFaceOwner;
        this.status = LoanOfferStatus.COUNTERED;
    }
    public void markAccepted() { this.status = LoanOfferStatus.ACCEPTED; }
    public void markRejected() { this.status = LoanOfferStatus.REJECTED; }
    public void markWithdrawn() { this.status = LoanOfferStatus.WITHDRAWN; }
}
