package br.com.carvoeiromanager.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoardObjectiveRepository extends JpaRepository<BoardObjective, UUID> {
    List<BoardObjective> findBySaveGameIdAndSeasonYear(UUID saveGameId, int seasonYear);
    List<BoardObjective> findBySaveGameIdOrderBySeasonYearAsc(UUID saveGameId);
}
