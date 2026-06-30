package br.com.carvoeiromanager.player;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, UUID> {
    List<Player> findByClubId(UUID clubId);
    List<Player> findBySaveGameId(UUID saveGameId);
    List<Player> findBySaveGameIdAndClubIdIsNull(UUID saveGameId);
}
