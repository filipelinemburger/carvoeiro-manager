package br.com.elifootcarvoeiro.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonStandingRepository extends JpaRepository<SeasonStanding, UUID> {
    List<SeasonStanding> findBySeasonIdOrderByPosition(UUID seasonId);
}
