package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SeasonEventRepository extends JpaRepository<SeasonEvent, UUID> {
    SeasonEvent findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(UUID saveGameId);
    List<SeasonEvent> findBySaveGameIdAndProcessedFalse(UUID saveGameId);
    List<SeasonEvent> findBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
