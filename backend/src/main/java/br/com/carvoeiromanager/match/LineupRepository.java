package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LineupRepository extends JpaRepository<Lineup, UUID> {
    Optional<Lineup> findBySaveGameIdAndClubId(UUID saveGameId, UUID clubId);
}
