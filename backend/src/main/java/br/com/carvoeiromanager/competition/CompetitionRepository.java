package br.com.carvoeiromanager.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompetitionRepository extends JpaRepository<Competition, UUID> {
    List<Competition> findBySaveGameId(UUID saveGameId);
}
