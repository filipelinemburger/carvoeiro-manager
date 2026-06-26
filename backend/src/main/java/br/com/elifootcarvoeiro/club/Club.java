package br.com.elifootcarvoeiro.club;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
}
