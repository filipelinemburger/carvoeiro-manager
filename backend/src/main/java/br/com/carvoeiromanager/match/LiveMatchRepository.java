package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface LiveMatchRepository extends JpaRepository<LiveMatch, UUID> {

    Optional<LiveMatch> findBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
