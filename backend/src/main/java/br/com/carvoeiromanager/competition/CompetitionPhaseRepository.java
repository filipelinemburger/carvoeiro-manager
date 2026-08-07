package br.com.carvoeiromanager.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompetitionPhaseRepository extends JpaRepository<CompetitionPhase, UUID> {

    List<CompetitionPhase> findByCompetitionIdOrderByOrdinalAsc(UUID competitionId);

    List<CompetitionPhase> findBySaveGameId(UUID saveGameId);
}
