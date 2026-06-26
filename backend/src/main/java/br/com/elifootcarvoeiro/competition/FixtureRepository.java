package br.com.elifootcarvoeiro.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FixtureRepository extends JpaRepository<Fixture, UUID> {
    List<Fixture> findBySaveGameIdAndCompetitionIdAndRound(UUID saveGameId, UUID competitionId, int round);

    List<Fixture> findBySaveGameIdAndCompetitionId(UUID saveGameId, UUID competitionId);
}
