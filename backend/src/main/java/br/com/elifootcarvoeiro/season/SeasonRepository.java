package br.com.elifootcarvoeiro.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends JpaRepository<Season, UUID> {
    List<Season> findBySaveGameIdOrderBySeasonYearDesc(UUID saveGameId);
    // One competition per save today; generalizes when 2D (multiple competitions) lands.
    Optional<Season> findBySaveGameIdAndSeasonYear(UUID saveGameId, int seasonYear);
}
