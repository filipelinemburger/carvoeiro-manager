package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonPlayerStatsRepository extends JpaRepository<SeasonPlayerStats, UUID> {
    List<SeasonPlayerStats> findByPlayerId(UUID playerId);
}
