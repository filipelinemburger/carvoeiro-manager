package br.com.carvoeiromanager.club;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps to the {@code clubs} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 */
@Entity
@Table(name = "clubs")
public class Club {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "short_name", nullable = false, length = 20)
    private String shortName;

    @Column(name = "nickname", length = 40)
    private String nickname;

    @Column(name = "primary_color", length = 9)
    private String primaryColor;

    @Column(name = "secondary_color", length = 9)
    private String secondaryColor;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "reputation", nullable = false)
    private int reputation;

    @Column(name = "stadium_capacity", nullable = false)
    private int stadiumCapacity;

    @Column(name = "sponsorship_per_round", nullable = false)
    private BigDecimal sponsorshipPerRound = BigDecimal.ZERO;

    @Column(name = "stadium_upkeep_per_round", nullable = false)
    private BigDecimal stadiumUpkeepPerRound = BigDecimal.ZERO;

    protected Club() {
        // for JPA
    }

    private Club(UUID id, UUID saveGameId, String name, String shortName,
                 String nickname, String primaryColor, String secondaryColor) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.name = name;
        this.shortName = shortName;
        this.nickname = nickname;
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
    }

    public static Club create(UUID saveGameId, String name, String shortName) {
        return new Club(UUID.randomUUID(), saveGameId, name, shortName, null, null, null);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public String getName() { return name; }
    public String getShortName() { return shortName; }
    public String getNickname() { return nickname; }
    public String getPrimaryColor() { return primaryColor; }
    public String getSecondaryColor() { return secondaryColor; }

    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }

    public BigDecimal getBalance() { return balance; }
    public int getReputation() { return reputation; }
    public int getStadiumCapacity() { return stadiumCapacity; }
    public BigDecimal getSponsorshipPerRound() { return sponsorshipPerRound; }
    public BigDecimal getStadiumUpkeepPerRound() { return stadiumUpkeepPerRound; }

    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setReputation(int reputation) { this.reputation = reputation; }
    public void setStadiumCapacity(int stadiumCapacity) { this.stadiumCapacity = stadiumCapacity; }
    public void setSponsorshipPerRound(BigDecimal v) { this.sponsorshipPerRound = v; }
    public void setStadiumUpkeepPerRound(BigDecimal v) { this.stadiumUpkeepPerRound = v; }

    /** Applies a signed delta to the balance (income positive, costs negative). */
    public void adjustBalance(BigDecimal delta) { this.balance = this.balance.add(delta); }
}
