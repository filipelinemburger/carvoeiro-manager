package br.com.carvoeiromanager.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface BoardConfidenceChangeRepository extends JpaRepository<BoardConfidenceChange, UUID> {
    int countBySaveGameId(UUID saveGameId);
    List<BoardConfidenceChange> findTop8BySaveGameIdOrderBySeqDesc(UUID saveGameId);
    List<BoardConfidenceChange> findBySaveGameIdOrderBySeqAsc(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
