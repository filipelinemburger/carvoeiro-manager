package br.com.carvoeiromanager.career;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SaveGameRepository extends JpaRepository<SaveGame, UUID> {

    /**
     * Deletes every save game owned by a user. The {@code ON DELETE CASCADE}
     * foreign keys on save_game_id then clear all game data (clubs, players,
     * fixtures, finances, transfers, news, seasons, …). Used when a user
     * deletes their account.
     */
    @Modifying
    @Query(value = "DELETE FROM save_games WHERE user_id = :userId", nativeQuery = true)
    void deleteByUserId(@Param("userId") UUID userId);
}
