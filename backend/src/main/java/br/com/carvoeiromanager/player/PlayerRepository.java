package br.com.carvoeiromanager.player;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, UUID> {
    List<Player> findByClubId(UUID clubId);
    List<Player> findByClubIdAndYouthProspectFalse(UUID clubId);
    List<Player> findByClubIdAndYouthProspectTrue(UUID clubId);
    List<Player> findBySaveGameId(UUID saveGameId);
    List<Player> findBySaveGameIdAndClubIdIsNull(UUID saveGameId);

    /**
     * A club's own squad plus whatever it currently has out on loan: {@code clubId = clubId}
     * (who plays for him, including players he's borrowed) OR {@code ownerClubId = ownerClubId}
     * (players he owns but has loaned out, who play elsewhere). Callers pass the same club id
     * for both parameters — the two-parameter shape is Spring Data's derived-query convention
     * for an OR across two different properties.
     */
    List<Player> findByClubIdOrOwnerClubId(UUID clubId, UUID ownerClubId);
}
