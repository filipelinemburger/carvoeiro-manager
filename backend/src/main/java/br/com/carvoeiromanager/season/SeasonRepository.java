package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonRepository extends JpaRepository<Season, UUID> {
    List<Season> findBySaveGameIdOrderBySeasonYearDesc(UUID saveGameId);
    List<Season> findBySaveGameIdAndSeasonYearOrderByCompetitionName(UUID saveGameId, int seasonYear);
}
