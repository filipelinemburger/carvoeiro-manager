package br.com.carvoeiromanager.season;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface NewsItemRepository extends JpaRepository<NewsItem, UUID> {

    List<NewsItem> findBySaveGameIdOrderBySeqDesc(UUID saveGameId);

    int countBySaveGameId(UUID saveGameId);

    int countBySaveGameIdAndReadFalse(UUID saveGameId);

    NewsItem findFirstBySaveGameIdAndTypeOrderBySeqDesc(UUID saveGameId, String type);

    @Modifying
    @Transactional
    @Query("update NewsItem n set n.read = true where n.saveGameId = :saveGameId and n.read = false")
    int markAllRead(@Param("saveGameId") UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
