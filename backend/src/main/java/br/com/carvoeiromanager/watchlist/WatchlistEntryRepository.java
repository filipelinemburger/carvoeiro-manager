package br.com.carvoeiromanager.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {

    List<WatchlistEntry> findBySaveGameId(UUID saveGameId);

    boolean existsBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    Optional<WatchlistEntry> findBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    @Transactional
    void deleteBySaveGameIdAndPlayerId(UUID saveGameId, UUID playerId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
