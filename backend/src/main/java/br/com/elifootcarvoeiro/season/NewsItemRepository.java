package br.com.elifootcarvoeiro.season;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface NewsItemRepository extends JpaRepository<NewsItem, UUID> {

    List<NewsItem> findBySaveGameIdOrderBySeqDesc(UUID saveGameId);

    int countBySaveGameId(UUID saveGameId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
