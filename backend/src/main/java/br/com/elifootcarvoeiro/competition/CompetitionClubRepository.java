package br.com.elifootcarvoeiro.competition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CompetitionClubRepository extends JpaRepository<CompetitionClub, UUID> {
    List<CompetitionClub> findByCompetitionId(UUID competitionId);
    List<CompetitionClub> findBySaveGameIdAndClubId(UUID saveGameId, UUID clubId);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
