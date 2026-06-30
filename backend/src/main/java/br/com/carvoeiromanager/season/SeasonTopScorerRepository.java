package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonTopScorerRepository extends JpaRepository<SeasonTopScorer, UUID> {
    List<SeasonTopScorer> findBySeasonIdOrderByRank(UUID seasonId);
}
