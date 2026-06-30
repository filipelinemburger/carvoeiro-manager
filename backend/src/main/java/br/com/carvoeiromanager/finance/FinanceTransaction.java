package br.com.carvoeiromanager.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Itemized finance ledger row (recorded for the managed club only). */
@Entity
@Table(name = "finance_transaction")
public class FinanceTransaction {

    @Id @Column(name = "id", nullable = false, updatable = false) private UUID id;
    @Column(name = "save_game_id", nullable = false) private UUID saveGameId;
    @Column(name = "club_id", nullable = false) private UUID clubId;
    @Column(name = "season_year", nullable = false) private int seasonYear;
    @Column(name = "round", nullable = false) private int round;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16) private FinanceTransactionType type;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "description", nullable = false, length = 120) private String description;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    /** In-game date the transaction was charged; used for monthly grouping on the Finances page. */
    @Column(name = "tx_date") private LocalDate txDate;

    protected FinanceTransaction() { }

    private FinanceTransaction(UUID id, UUID saveGameId, UUID clubId, int seasonYear, int round,
                              FinanceTransactionType type, BigDecimal amount, String description,
                              Instant createdAt, LocalDate txDate) {
        this.id = id; this.saveGameId = saveGameId; this.clubId = clubId; this.seasonYear = seasonYear;
        this.round = round; this.type = type; this.amount = amount; this.description = description;
        this.createdAt = createdAt; this.txDate = txDate;
    }

    public static FinanceTransaction create(UUID saveGameId, UUID clubId, int seasonYear, int round,
                                            FinanceTransactionType type, BigDecimal amount, String description) {
        return new FinanceTransaction(UUID.randomUUID(), saveGameId, clubId, seasonYear, round,
                type, amount, description, Instant.now(), null);
    }

    public static FinanceTransaction create(UUID saveGameId, UUID clubId, int seasonYear, int round,
                                            FinanceTransactionType type, BigDecimal amount,
                                            String description, LocalDate txDate) {
        return new FinanceTransaction(UUID.randomUUID(), saveGameId, clubId, seasonYear, round,
                type, amount, description, Instant.now(), txDate);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public int getSeasonYear() { return seasonYear; }
    public int getRound() { return round; }
    public FinanceTransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getTxDate() { return txDate; }
}
