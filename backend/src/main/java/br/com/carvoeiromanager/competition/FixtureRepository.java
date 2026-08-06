package br.com.carvoeiromanager.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FixtureRepository extends JpaRepository<Fixture, UUID> {
    List<Fixture> findBySaveGameIdAndCompetitionIdAndRound(UUID saveGameId, UUID competitionId, int round);

    List<Fixture> findBySaveGameIdAndCompetitionId(UUID saveGameId, UUID competitionId);

    List<Fixture> findBySaveGameIdAndCompetitionIdOrderByRound(UUID saveGameId, UUID competitionId);

    List<Fixture> findBySaveGameIdAndMatchDate(UUID saveGameId, LocalDate matchDate);

    List<Fixture> findBySaveGameId(UUID saveGameId);

    List<Fixture> findBySaveGameIdAndPhaseId(UUID saveGameId, UUID phaseId);

    List<Fixture> findByTieId(UUID tieId);
}
